package com.island.pass.data

import android.content.SharedPreferences

/**
 * 模块配置键与目标包名常量。
 *
 * 配置跨进程共享（1.0.12 起：本地偏好为唯一事实源，远程偏好为 hooker 侧只读镜像）：
 *  - 模块 App（SettingsActivity / ListManageActivity 等）侧读写本地偏好，每次写操作后经
 *    [IslandFramework.mirrorToRemote] 全量补镜至远程偏好；
 *  - Hooker 侧经 XposedInterface 远程偏好读取（只读，框架推送保持最新）；
 *  - 升级兼容：≤1.0.11 写在远程偏好的旧配置由 [ConfigMirror] 首次启动一次性迁回本地。
 *
 * 名单模型（1.0.9 起：白名单与黑名单相互独立、可叠加）：
 *  - 远程偏好只存「生效名单」：[KEY_WHITELIST_PKGS] / [KEY_BLACKLIST_PKGS]，
 *    由模块侧 [IslandListManager] 汇总「全局名单 ∪ 已启用个性名单」后写入本地并镜像广播热更新；
 *  - 全局名单（模块本地偏好 island_pass_local）与个性名单（JSON 文件）均不跨进程，见 [IslandListFiles]；
 *  - 旧版「三态共享名单」（[KEY_ISLAND_LIST_MODE] / [KEY_ISLAND_LIST_PKGS]）由
 *    [migrateLegacy] 一次性迁移，迁移后旧键移除。
 */
object IslandConfig {

    /** 偏好文件名 */
    const val PREFS_NAME = "island_pass_config"

    /** 键：超级岛白名单/签名剔除（com.android.systemui，默认开） */
    const val KEY_BYPASS_WHITELIST = "bypass_whitelist"

    /** 键：实况通知 SDK 映射至系统原生超级岛（com.spark.noticeflow，默认开） */
    const val KEY_SDK_MAPPING = "sdk_mapping"

    /** 键：生效白名单包名集合（换行分隔；= 全局 ∪ 已启用个性名单，默认空 = 不限制） */
    const val KEY_WHITELIST_PKGS = "whitelist_pkgs"

    /** 键：生效黑名单包名集合（换行分隔；= 全局 ∪ 已启用个性名单，默认空） */
    const val KEY_BLACKLIST_PKGS = "blacklist_pkgs"

    /** 键：白名单是否启用（默认开；关闭后白名单不参与判定） */
    const val KEY_WHITELIST_ENABLED = "whitelist_enabled"

    /** 键：黑名单是否启用（默认开；关闭后黑名单不参与判定） */
    const val KEY_BLACKLIST_ENABLED = "blacklist_enabled"

    /** 键：已启用个性名单 id 集合（逗号分隔；仅模块 App 侧维护，hooker 不读） */
    const val KEY_ENABLED_LISTS = "enabled_lists"

    /** 旧键（1.0.9 前）：三态名单模式（0=关 1=黑 2=白），迁移后移除 */
    const val KEY_ISLAND_LIST_MODE = "island_list_mode"

    /** 旧键（1.0.9 前）：三态共享名单包名，迁移后移除 */
    const val KEY_ISLAND_LIST_PKGS = "island_list_pkgs"

    /** 键：主题模式（int，0=跟随系统 1=浅色 2=深色） */
    const val KEY_THEME_MODE = "theme_mode"

    /** 键：Monet 动态取色（跟随壁纸取色，需 Android 12+，默认关） */
    const val KEY_MONET = "monet_colors"

    /** 键：悬浮底栏（Apple 风格圆角悬浮导航栏，默认关） */
    const val KEY_FLOATING_BAR = "floating_bar"

    /** 键：底部导航仅显示图标（默认关，即图标+文字） */
    const val KEY_NAV_ICON_ONLY = "nav_icon_only"

    /** 键：界面缩放（float，0.85~1.15，默认 1.0） */
    const val KEY_UI_SCALE = "ui_scale"

    /** 键：页面切换动画（默认开） */
    const val KEY_PAGE_ANIM = "page_anim"

    /** 键：详细日志（hooker 侧向 logcat 输出注入过程明细，默认关） */
    const val KEY_VERBOSE_LOG = "verbose_log"

    /** 键：调试模式（首页显示 HostStatus 原始明细，默认关） */
    const val KEY_DEBUG_MODE = "debug_mode"

    /** 宿主：系统界面 */
    const val PKG_SYSTEMUI = "com.android.systemui"

    /** 超级岛插件：系统界面组件（MIUI DynamicIsland 实现所在） */
    const val PKG_ISLAND_PLUGIN = "miui.systemui.plugin"

    /** 调用方：实况通知 */
    const val PKG_NOTICEFLOW = "com.spark.noticeflow"

    /** 读取生效配置（Hooker 侧，远程偏好语义） */
    fun read(prefs: SharedPreferences): IslandSettings = IslandSettings(
        bypassWhitelist = prefs.getBoolean(KEY_BYPASS_WHITELIST, true),
        sdkMapping = prefs.getBoolean(KEY_SDK_MAPPING, true),
        whitelist = decodeList(prefs.getString(KEY_WHITELIST_PKGS, "")),
        whitelistEnabled = prefs.getBoolean(KEY_WHITELIST_ENABLED, true),
        blacklist = decodeList(prefs.getString(KEY_BLACKLIST_PKGS, "")),
        blacklistEnabled = prefs.getBoolean(KEY_BLACKLIST_ENABLED, true),
        verboseLog = prefs.getBoolean(KEY_VERBOSE_LOG, false),
        debugMode = prefs.getBoolean(KEY_DEBUG_MODE, false)
    )

    /** 模块 App 侧默认值（与 Hooker 侧保持一致） */
    fun defaults() = IslandSettings()

    /**
     * 名单包名集合序列化为偏好字符串。
     * 采用换行分隔（包名不可能含换行），避免引入 org.json 依赖。
     */
    fun encodeList(pkgs: Set<String>): String =
        pkgs.filter { it.isNotBlank() }.joinToString("\n")

    /** 偏好字符串反序列化为名单包名集合 */
    fun decodeList(raw: String?): Set<String> =
        raw?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    /**
     * 旧版三态共享名单一次性迁移（1.0.9 前 -> 独立黑白名单）。
     *
     *  - 旧模式 2（白名单）-> 写入 [KEY_WHITELIST_PKGS]；
     *  - 旧模式 1（黑名单）-> 写入 [KEY_BLACKLIST_PKGS]；
     *  - 旧模式 0（不限制）-> 直接丢弃旧名单；
     *  - 迁移后移除旧键，重复调用无副作用（幂等）。
     *
     * @return 是否发生了迁移
     */
    fun migrateLegacy(prefs: SharedPreferences): Boolean {
        if (!prefs.contains(KEY_ISLAND_LIST_MODE) && !prefs.contains(KEY_ISLAND_LIST_PKGS)) {
            return false
        }
        val mode = prefs.getInt(KEY_ISLAND_LIST_MODE, -1)
        val pkgs = decodeList(prefs.getString(KEY_ISLAND_LIST_PKGS, ""))
        val editor = prefs.edit()
            .remove(KEY_ISLAND_LIST_MODE)
            .remove(KEY_ISLAND_LIST_PKGS)
        when (mode) {
            1 -> editor.putString(KEY_BLACKLIST_PKGS, encodeList(pkgs))
            2 -> editor.putString(KEY_WHITELIST_PKGS, encodeList(pkgs))
        }
        editor.apply()
        return true
    }
}

/**
 * 生效配置快照。
 *
 * 白名单与黑名单相互独立（1.0.9 起），且各自可单独启用/停用：
 *  - 停用的名单不参与判定（两个都停用 = 不限制上岛）；
 *  - 启用的黑名单优先拦截；
 *  - 启用的白名单留空 = 不限制，非空 = 仅名单内放行。
 *
 * 远程偏好中的名单为模块侧汇总后的「生效名单」（全局 ∪ 已启用个性名单）；
 * 名单变更经 CONFIG_RELOAD 热更新（见 [IslandListManager.refresh]）。
 */
data class IslandSettings(
    val bypassWhitelist: Boolean = true,
    val sdkMapping: Boolean = true,
    val whitelist: Set<String> = emptySet(),
    val whitelistEnabled: Boolean = true,
    val blacklist: Set<String> = emptySet(),
    val blacklistEnabled: Boolean = true,
    val verboseLog: Boolean = false,
    val debugMode: Boolean = false
) {
    /**
     * 名单准入判定：该应用是否允许上岛。
     *
     *  - 黑名单启用时：命中黑名单一律拒绝（优先于白名单）；
     *  - 白名单启用且非空时：仅名单内放行；
     *  - 停用/留空的对应名单不产生约束。
     */
    fun allowsIsland(pkg: String): Boolean = when {
        blacklistEnabled && pkg in blacklist -> false
        whitelistEnabled && whitelist.isNotEmpty() && pkg !in whitelist -> false
        else -> true
    }
}
