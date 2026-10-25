package com.island.pass.data

import android.content.Context
import com.island.pass.hook.IslandLog

/**
 * 配置镜像编排（1.0.12 起，1.0.14 定稿，1.0.20 加固升级保底迁移）。
 *
 * 背景：≤1.0.11 版本的配置直接读写远程偏好（libxposed 框架数据库），读写路径随
 * service 绑定状态分裂，表现为「修改不保存」。1.0.12 起改为：
 *
 *  - **本地偏好为唯一事实源**：模块 App 侧所有读写走本地（见 [IslandFramework.prefs]）；
 *  - **远程偏好为 hooker 侧只读镜像**：每次本地写操作后经 [IslandFramework.mirrorToRemote]
 *    以同步 commit 补镜（远程落地后才发广播，无 IPC 竞态）；
 *  - **升级无缝**：旧版残留在远程偏好里的配置，首次绑定时一次性补拷回本地
 *    （仅本地缺失键，成功后写标记防重复），保证升级不丢配置。
 *
 * 1.0.20 加固（≤1.0.11 升级名单保底）：
 *  ≤1.0.11 旧版没有「全局名单」概念，用户添加的名单直接以生效名单形式写在远程
 *  派生键（whitelist_pkgs / blacklist_pkgs）。若仅按「跳过派生键」处理，
 *  随后的 refresh 会以空名单重算并覆写远程，导致旧名单丢失。现增加保底迁移：
 *  远程旧派生值非空且本地全局名单为空时，将其灌为全局名单，使重算结果保留旧名单。
 *
 * 编排时机（[IslandFramework] service 绑定主线程回调，严格按序）：
 *  1) [migrate] 远程→本地迁移（先于业务钩子，保证 refresh 读到迁移后的 enabled_lists）；
 *  2) 业务钩子 IslandListManager.refresh 重算生效名单并镜像、广播；
 *  3) IslandFramework 兜底全量镜像。
 * 迁移完成前不做本地→远程镜像，避免把迁移前的陈旧派生名单值推到远程。
 */
object ConfigMirror {

    /** 迁移完成标记（存本地偏好自身；存在即不再迁移） */
    private const val KEY_MIGRATED = "config_migrated_from_remote"

    /**
     * 派生键：生效名单（全局 ∪ 已启用个性名单的汇总结果）→ 全局名单类型映射。
     *
     * 迁移时不直接搬本地——其源数据（全局名单在 island_pass_local 偏好、已启用 id 在
     * [IslandConfig.KEY_ENABLED_LISTS]）均在本地/可迁移，由 [IslandListManager.refresh]
     * 在迁移后重算，避免把旧版可能过期的汇总值搬过来。
     * 远程旧值仅用于 [salvageLegacyEffectiveLists] 的保底判定。
     */
    private val DERIVED_KEYS = mapOf(
        IslandConfig.KEY_WHITELIST_PKGS to ListType.WHITELIST,
        IslandConfig.KEY_BLACKLIST_PKGS to ListType.BLACKLIST
    )

    /**
     * 远程旧配置一次性补拷回本地（仅本地缺失键）。
     *
     *  - 首次绑定（无标记）且远程可达时执行，完成后写 [KEY_MIGRATED] 标记；
     *  - 远程不可达（框架未安装/模块未激活）时跳过：该场景下旧版本就回退写本地，
     *    本地已持有全部配置，无需迁移；
     *  - 失败不写标记（下次绑定重试），仅记日志。
     */
    fun migrate(context: Context) {
        val local = IslandFramework.prefs(context, IslandConfig.PREFS_NAME)
        if (local.getBoolean(KEY_MIGRATED, false)) return
        val remote = IslandFramework.remotePrefsOrNull(IslandConfig.PREFS_NAME) ?: return
        runCatching {
            val editor = local.edit()
            var copied = 0
            val remoteDerived = HashMap<String, String>(DERIVED_KEYS.size)
            remote.all.forEach { (k, v) ->
                if (k == KEY_MIGRATED) return@forEach
                if (k in DERIVED_KEYS) {
                    // 派生键不直接搬本地，记录旧值供保底迁移判定
                    (v as? String)?.let { remoteDerived[k] = it }
                    return@forEach
                }
                if (local.contains(k)) return@forEach
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is String -> editor.putString(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Float -> editor.putFloat(k, v)
                    is Set<*> -> editor.putStringSet(k, v.mapNotNull { it as? String }.toSet())
                    else -> return@forEach
                }
                copied++
            }
            salvageLegacyEffectiveLists(context, remoteDerived)
            editor.putBoolean(KEY_MIGRATED, true).apply()
            if (copied > 0) IslandLog.d("config mirror: migrated $copied keys from remote prefs")
        }.onFailure { IslandLog.w("config mirror migration failed: ${it.message}") }
    }

    /**
     * ≤1.0.11 升级保底：远程旧生效名单灌为本地全局名单。
     *
     * 旧版无全局名单源数据，名单仅存在于远程派生键；若本地全局名单为空而远程旧值
     * 非空，灌入后 refresh 重算（全局 ∪ 已启用个性）即可保留旧名单。
     * 本地已有全局名单（1.0.9+ 用户）时不动，避免重复灌入。
     */
    private fun salvageLegacyEffectiveLists(context: Context, remoteDerived: Map<String, String>) {
        if (remoteDerived.isEmpty()) return
        val localLists = IslandListManager.localPrefs(context)
        val editor = localLists.edit()
        var salvaged = 0
        DERIVED_KEYS.forEach { (derivedKey, type) ->
            val remoteValue = remoteDerived[derivedKey]?.takeIf { it.isNotBlank() } ?: return@forEach
            if (localLists.getString(IslandListManager.globalKey(type), "").isNullOrBlank()) {
                editor.putString(IslandListManager.globalKey(type), remoteValue)
                salvaged++
            }
        }
        if (salvaged > 0) {
            editor.apply()
            IslandLog.d("config mirror: salvaged $salvaged legacy effective list(s) into global lists")
        }
    }
}
