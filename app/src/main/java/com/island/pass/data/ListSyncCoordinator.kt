package com.island.pass.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.island.pass.hook.IslandLog

/**
 * 生效配置同步协调器（模块 App 侧，1.0.22 建立，1.0.23 扩展为全功能字段）。
 *
 * 对应用户明确的同步设计：宿主（作用域）重启后内存名单清空 → 重启完重新推送并存入内存；
 * 每次同步先请求宿主名单指纹与本地比对，不一致用新的覆盖旧的，完全一致即停止同步，
 * 直至下一次名单变更。
 *
 * 触发点：
 *  - 宿主重启：attachHostContext 发 LIST_REQUEST -> [onHostListRequest] 先校验后推送
 *    （实况通知无完整配置快照、不参与名单判定，对其免校验直接推送开关字段）；
 *  - 名单变更：[IslandListManager.refresh] 末尾 [pushToHost] 直推（已知变更，免校验）；
 *  - 开关变更：[pushAllHosts] 直推两个宿主（1.0.23：sdkMapping / bypass /
 *    名单开关切换不再依赖远程偏好镜像——该设备镜像链路已实证不可靠）；
 *  - 模块启动 / UI 前台：[initiateVerify] 主动校验系统界面 + 直推实况通知自愈，
 *    覆盖「模块死亡期间广播被拦」导致的内存陈旧（Greezer 拦截 CONFIG_RELOAD 的实证场景）；
 *  - 超时兜底：LIST_VERIFY 后 [VERIFY_TIMEOUT_MS] 内无回报，直接推送。
 *
 * 同步目标：系统界面（名单门禁唯一宿主，走校验闭环）与实况通知（仅消费
 * sdkMapping 开关字段，免校验直推）。
 */
object ListSyncCoordinator {

    /** LIST_VERIFY 无回报兜底推送延迟 */
    private const val VERIFY_TIMEOUT_MS = 3_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 等待指纹回报的宿主集合（超时兜底推送用） */
    private val pendingVerify = mutableSetOf<String>()

    /** 宿主重启后请求名单：系统界面走指纹校验闭环；实况通知免校验直接推送 */
    fun onHostListRequest(context: Context, hostPkg: String) {
        val app = context.applicationContext
        if (hostPkg == IslandConfig.PKG_NOTICEFLOW) {
            // 实况通知无 IslandSettings 完整快照（仅消费 sdkMapping），不参与
            // 指纹闭环；重启后可能读到陈旧远程值（镜像断），直接推送权威开关
            IslandLog.d("list request from $hostPkg: direct push (noticeflow consumes sdkMapping only)")
            pushToHost(app, hostPkg)
            return
        }
        IslandLog.d("list request from $hostPkg: verifying fingerprint before push")
        sendVerify(app, hostPkg)
    }

    /** 宿主指纹回报：与本地一致即收敛；不一致则直推覆盖 */
    fun onHostVerifyReply(context: Context, hostPkg: String, hostFingerprint: String) {
        if (!pendingVerify.remove(hostPkg)) return // 过期/重复回报
        val app = context.applicationContext
        val local = localFingerprint(app)
        if (hostFingerprint == local) {
            IslandLog.d("list sync converged with $hostPkg (fp=$local)")
        } else {
            // 状态改变（五类之一）：宿主内存名单与本地不一致，推送覆盖
            IslandLog.w(
                "list sync: $hostPkg out of sync (local=$local, host=$hostFingerprint), pushing effective lists"
            )
            pushToHost(app, hostPkg)
        }
    }

    /**
     * 模块启动 / UI 前台自愈（1.0.23）：
     *  - 系统界面：主动校验内存名单指纹，不一致即补推；
     *  - 实况通知：已安装则直接推送权威开关——其装载时读远程偏好可能得到陈旧
     *    sdk_mapping=false（镜像断），不装钩、探测无回报，误报「未激活」。
     */
    fun initiateVerify(context: Context) {
        val app = context.applicationContext
        sendVerify(app, IslandConfig.PKG_SYSTEMUI)
        if (isHostInstalled(app, IslandConfig.PKG_NOTICEFLOW)) {
            pushToHost(app, IslandConfig.PKG_NOTICEFLOW)
        }
    }

    /** 名单变更直推系统界面（已知变更，免校验；实况通知不参与名单判定） */
    fun pushToHost(context: Context, hostPkg: String = IslandConfig.PKG_SYSTEMUI) {
        val app = context.applicationContext
        val payload = localPayload(app)
        IslandChannel.send(app, hostPkg, ChannelKeys.LIST_PUSH, ListSync.encode(payload))
        IslandLog.d("list pushed to $hostPkg: fp=${ListSync.fingerprint(payload)}")
    }

    /**
     * 开关变更直推全部宿主（1.0.23）。
     *
     * 覆盖 bypass / sdkMapping / 白名单启用 / 黑名单启用四个功能开关：
     * 这些字段此前的唯一传播路径是远程偏好镜像 + CONFIG_RELOAD，而镜像链路
     * 在本机未生效（service 未绑或 commit 静默失败），导致开关热更新失效、
     * 「关了又恢复」。改走与名单相同的载荷直推通道（发送带
     * FLAG_RECEIVER_FOREGROUND，抗 Greezer 拦截）。
     *
     * 实况通知未安装时跳过（无接收者，广播静默丢弃）。
     */
    fun pushAllHosts(context: Context) {
        val app = context.applicationContext
        pushToHost(app, IslandConfig.PKG_SYSTEMUI)
        if (isHostInstalled(app, IslandConfig.PKG_NOTICEFLOW)) {
            pushToHost(app, IslandConfig.PKG_NOTICEFLOW)
        }
    }

    private fun sendVerify(context: Context, hostPkg: String) {
        pendingVerify.add(hostPkg)
        IslandChannel.send(context, hostPkg, ChannelKeys.LIST_VERIFY, localFingerprint(context))
        mainHandler.postDelayed({
            if (pendingVerify.remove(hostPkg)) {
                // 宿主未回报（进程死亡 / 回报被拦）：直接推送兜底
                IslandLog.d("list verify to $hostPkg timed out (${VERIFY_TIMEOUT_MS}ms), pushing anyway")
                pushToHost(context, hostPkg)
            }
        }, VERIFY_TIMEOUT_MS)
    }

    /** 目标宿主是否已安装（未安装时跳过推送，避免无接收者广播） */
    private fun isHostInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /**
     * 本地生效配置载荷 = 全局 ∪ 已启用个性名单（同类合并）+ 全部功能开关
     * （均读本地偏好——本地为唯一事实源，仅模块 App 侧计算）。
     */
    fun localPayload(context: Context): ListSync.Payload {
        val app = context.applicationContext
        val settings = IslandConfig.read(IslandFramework.prefs(app, IslandConfig.PREFS_NAME))
        return ListSync.Payload(
            whitelist = IslandListManager.effectiveList(app, ListType.WHITELIST).toSet(),
            blacklist = IslandListManager.effectiveList(app, ListType.BLACKLIST).toSet(),
            whitelistEnabled = settings.whitelistEnabled,
            blacklistEnabled = settings.blacklistEnabled,
            sdkMapping = settings.sdkMapping,
            bypass = settings.bypassWhitelist
        )
    }

    fun localFingerprint(context: Context): String = ListSync.fingerprint(localPayload(context))
}
