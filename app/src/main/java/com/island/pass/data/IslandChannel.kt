package com.island.pass.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.island.pass.hook.IslandLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 模块 <-> 宿主持久通道（libxposed 迁移版，替代已废弃的 YukiHookDataChannel）。
 *
 * 实现：显式包名定向广播（setPackage + 运行时注册接收器，RECEIVER_EXPORTED）。
 *  - 免后台限制：定向广播不受后台广播启动限制约束；
 *  - 抗 MIUI Greezer cached 拦截：发送统一带 FLAG_RECEIVER_FOREGROUND（1.0.21）；
 *  - 跨进程可达：模块进程与宿主进程（系统界面 / 实况通知）均为独立进程；
 *  - 安全姿态与旧通道等同：仅包名定向、无令牌（宿主均为系统/受信应用）。
 *
 * 协议键（[ChannelKeys]）与旧通道完全一致，两侧逻辑零迁移成本：
 *  - QUERY：模块 -> 宿主，请求注入状态（无值监听）；
 *  - STATUS：宿主 -> 模块，回报 [HostStatus] JSON；
 *  - RESTART：模块 -> 宿主，请求重启自身进程（无值监听）；
 *  - CONFIG_RELOAD：模块 -> 宿主，配置已变更（黑白名单/功能开关），
 *    宿主重读远程偏好快照实现热更新，无需重启作用域应用；
 *  - LIST_REQUEST / LIST_PUSH / LIST_VERIFY / LIST_VERIFY_REPLY（1.0.22）：
 *    生效名单显式同步协议，见 [ListSync] 与 [ListSyncCoordinator]。
 */
object IslandChannel {

    const val ACTION = "com.island.pass.CHANNEL"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_VALUE = "value"
    private const val EXTRA_FROM = "from"

    // ===== 发送（双向通用） =====

    /**
     * 向目标包发送通道消息。
     *
     * FLAG_RECEIVER_FOREGROUND（1.0.21 起，MIUI Greezer 抗拦截）：MIUI 的 cached 进程
     * 广播节流（Greezer）会把发给 cached 状态接收者的广播挂起/丢弃（日志表现为
     * `Greezer Denial: ... need cached broadcast`），导致宿主 -> 模块的 STATUS 回报
     * 丢失、探测超时误报。加该标志可将广播按前台优先级投递，绕开 cached 拦截。
     *
     * @param context  发送方上下文（模块侧为 Application 上下文；宿主侧为宿主应用上下文）
     * @param targetPkg 目标包名
     * @param key      协议键（见 [ChannelKeys]）
     * @param value    载荷（STATUS 为 JSON，其余为空）
     * @param from     来源包名（宿主 -> 模块的 STATUS 必须携带，供模块侧识别来源）
     */
    fun send(context: Context, targetPkg: String, key: String, value: String = "", from: String = "") {
        runCatching {
            context.sendBroadcast(
                Intent(ACTION)
                    .setPackage(targetPkg)
                    .putExtra(EXTRA_KEY, key)
                    .putExtra(EXTRA_VALUE, value)
                    .putExtra(EXTRA_FROM, from)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            )
        }.onFailure { IslandLog.w("channel send $key -> $targetPkg failed: ${it.message}") }
    }

    // ===== 宿主侧接收 =====

    /**
     * 宿主侧注册通道接收器（QUERY / RESTART / CONFIG_RELOAD / LIST_PUSH / LIST_VERIFY）。
     * 于宿主 Application.attach 后调用（[com.island.pass.IslandModuleEntry.handleApplicationCreated]）。
     *
     * @param context   宿主应用上下文
     * @param onCommand 收到指令回调（携带 [ChannelKeys] 键名与载荷值，运行于宿主主线程）
     * @return 是否注册成功
     */
    fun registerHostSide(context: Context, onCommand: (String, String) -> Unit): Boolean = runCatching {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != ACTION) return
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                val value = intent.getStringExtra(EXTRA_VALUE) ?: ""
                onCommand(key, value)
            }
        }
        context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
        true
    }.getOrElse {
        IslandLog.w("channel host register failed: ${it.message}")
        false
    }

    // ===== 模块侧接收（STATUS / LIST_REQUEST / LIST_VERIFY_REPLY） =====

    /** 每个宿主包名至多一个 STATUS 监听（设置页重复注册时替换，避免回调叠加） */
    private val statusListeners = CopyOnWriteArrayList<Pair<String, (String) -> Unit>>()

    /** 模块侧命令监听（LIST_REQUEST / LIST_VERIFY_REPLY 等非 STATUS 键） */
    private val commandListeners = CopyOnWriteArrayList<(String, String, String) -> Unit>()

    /**
     * 模块侧注册 STATUS 接收器（进程级，Application.onCreate 调用一次）。
     *
     * 同时分发非 STATUS 键至命令监听（携带键名、来源包名、载荷值）。
     *
     * @param context 模块 Application 上下文
     * @return 是否注册成功
     */
    fun registerModuleSide(context: Context): Boolean = runCatching {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != ACTION) return
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                val from = intent.getStringExtra(EXTRA_FROM) ?: return
                val value = intent.getStringExtra(EXTRA_VALUE) ?: ""
                if (key == ChannelKeys.STATUS) {
                    statusListeners.forEach { (pkg, cb) -> if (pkg == from) cb(value) }
                } else {
                    commandListeners.forEach { cb -> cb(key, from, value) }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
        true
    }.getOrElse {
        IslandLog.w("channel module register failed: ${it.message}")
        false
    }

    /** 模块侧监听指定宿主的 STATUS 回报（设置页常驻监听，运行于模块主线程） */
    fun listenStatus(pkg: String, onJson: (String) -> Unit) {
        statusListeners.removeAll { it.first == pkg }
        statusListeners += pkg to onJson
    }

    /**
     * 模块侧监听宿主命令（LIST_REQUEST / LIST_VERIFY_REPLY）。
     *
     * @param onCommand 回调（键名、来源包名、载荷值），运行于模块主线程
     */
    fun listenCommands(onCommand: (key: String, from: String, value: String) -> Unit) {
        commandListeners += onCommand
    }
}
