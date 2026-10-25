package com.island.pass.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.hchen.hooktool.AbsModule
import com.hchen.hooktool.ModuleData
import com.hchen.hooktool.core.CoreTool.Companion.findClassIfExists
import com.hchen.hooktool.core.CoreTool.Companion.hook
import com.hchen.hooktool.core.CoreTool.Companion.prefs
import com.hchen.hooktool.hook.AbsHook
import com.island.pass.BuildConfig
import com.island.pass.data.ChannelKeys
import com.island.pass.data.DeviceCompat
import com.island.pass.data.HookResult
import com.island.pass.data.HostStatus
import com.island.pass.data.IslandChannel
import com.island.pass.data.IslandConfig
import com.island.pass.data.ListSync
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H2 —— 实况通知 SDK 映射至系统原生超级岛（运行于 com.spark.noticeflow 进程）。
 *
 * 实证（实况通知 3.6.0 逆向）：
 *  1. 引擎路由：IslandRouter.shouldUseHyper(request) = isHyperMode && isSupported
 *     && Shizuku授权 && hasFocusPermission；isHyperMode = 引擎偏好 == "hyper"，
 *     而默认引擎偏好是 "live_update"（标准通知通道，不上岛）。
 *  2. 上岛本体：HyperIslandEngine.postIsland 走的是**系统原生通道**——
 *     FocusNotification.createV3 -> buildBundle -> Notification.Builder.addExtras
 *     -> NotificationManager.notify，由系统超级岛解析 extras 渲染。
 *  3. 非原生部分：HyperIslandBypass.postWithBypassIfReady —— 借助 Shizuku
 *     **断网绕过**（postTimedCut：断网 -> 投放 -> 保持 -> restoreNow 恢复），
 *     日志原文"无 Shizuku 授权，改为直发（无断网绕过）"。
 *
 *  本 Hook：
 *  - 强制 hyper 引擎模式（engine -> "hyper" / isHyperMode -> true）；
 *  - 强制路由到系统原生超级岛（shouldUseHyper / isHyperAvailable -> true）；
 *  - **关断绝网绕过**：postWithBypassIfReady 改为强制直发（主线程执行原
 *    postAction，语义等同应用自身的 "skip"/"no-shizuku" 分支），
 *    使任何经 SDK 生成的岛都走系统原生超级岛；
 *  - hasFocusPermission -> true（该探测走的正是系统侧 canShowFocus，
 *    与 H1 系统界面钩子互为备份）。
 *
 *  仅当实况通知已安装时本 Hooker 才会被加载（LSPosed 作用域机制）；
 *  未安装时模块设置界面亦不展示相关设置项，SDK 注入行为整体忽略。
 *
 *  同时经广播通道（[IslandChannel]）向模块设置页提供状态回报（QUERY -> STATUS）
 *  与远程重启（RESTART，kill 后由模块侧经 Launcher Intent 重新拉起）；
 *  1.0.23 起 SDK 映射开关经 LIST_PUSH 权威直推（装载时读远程偏好可能得陈旧值，
 *  推送到达后按需补装钩子，见 [applyListPush]）。
 */
internal class NoticeFlowHooker : AbsModule() {

    private val ISLAND_ENGINE_PREFS = "com.spark.noticeflow.liveupdate.IslandEnginePreferences"
    private val ISLAND_ROUTER = "com.spark.noticeflow.liveupdate.IslandRouter"
    private val HYPER_ISLAND_BYPASS = "com.spark.noticeflow.liveupdate.hyper.HyperIslandBypass"
    private val FOCUS_UTILS = "com.xzakota.hyper.notification.focus.util.FocusUtils"
    private val HYPER_MODE = "hyper"

    /** 钩子安装结果（供状态查询读取） */
    private val hookResults = mutableListOf<HookResult>()

    /** 安装过程中的错误明细 */
    private val hookErrors = mutableListOf<String>()

    /** 全部钩子仅安装一次（防止 CONFIG_RELOAD 关→开补装时重复 hook） */
    private val hooksInstalled = AtomicBoolean(false)

    /** SDK 映射开关快照（CONFIG_RELOAD 热更新；关闭时全部钩子放行） */
    @Volatile
    private var sdkMappingEnabled = true

    /** 宿主应用上下文（入口在 Application.attach 后注入；通道依赖之） */
    @Volatile
    private var hostContext: Context? = null

    /** 宿主进程包名（STATUS 广播的来源标识） */
    private val hostPkg: String get() = IslandConfig.PKG_NOTICEFLOW

    /** 模块包名（宿主进程内经包装器获取，STATUS 广播的目标包） */
    private val modulePkg: String
        get() = runCatching { ModuleData.getModulePackageName() }.getOrNull() ?: BuildConfig.APPLICATION_ID

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!DeviceCompat.isSupported) {
            IslandLog.w("unsupported device (${DeviceCompat.deviceSummary()}), skip")
            return
        }

        val settings = IslandConfig.read(prefs(IslandConfig.PREFS_NAME))
        IslandLog.verbose = settings.verboseLog
        sdkMappingEnabled = settings.sdkMapping

        // 远程偏好变更监听：框架推送到达时的兜底热更新（与 CONFIG_RELOAD 广播双保险）
        runCatching {
            prefs(IslandConfig.PREFS_NAME).registerOnSharedPreferenceChangeListener { _, key ->
                IslandLog.d("remote prefs pushed (key=$key, noticeflow), reloading mapping")
                reloadMapping()
            }
        }.onFailure { IslandLog.w("prefs listener register failed: ${it.message}") }

        if (!sdkMappingEnabled) {
            // 功能停用（五类之一：状态改变）：channel-only 装载模式必须可观测——
            // 否则用户看到"进程被注入但没有任何钩子日志"，无法区分未启用与注入失败。
            IslandLog.w("SDK mapping disabled by config, channel only")
            return
        }
        IslandLog.d("hooking noticeflow, host pkg=${param.packageName}")

        installHooks()

        // 注入成功（五类之一）：SDK 映射钩子就绪，常驻可见
        IslandLog.w("noticeflow SDK mapping hooks installed")
    }

    /**
     * 宿主上下文就绪（Application.attach 后由入口调用）：
     * 无条件注册通道——功能开关关闭时也要能收到 CONFIG_RELOAD 热更新。
     *
     * 1.0.22：注册后立即向模块发 LIST_REQUEST——宿主进程（重）启动后内存中的
     * 配置快照即丢失（且装载时读远程偏好可能得到陈旧 sdk_mapping=false），
     * 模块经 LIST_PUSH 直推权威开关（实况通知仅消费 sdkMapping 字段）。
     */
    fun attachHostContext(context: Context) {
        hostContext = context
        registerChannel()
        // 配置重同步请求（宿主重启 resync；模块侧对实况通知免校验直接推送）
        IslandChannel.send(context, modulePkg, ChannelKeys.LIST_REQUEST, from = hostPkg)
        IslandLog.d("list request sent to module (host restart resync)")
    }

    /** 安装全部 hook（幂等；CONFIG_RELOAD 关→开时补装） */
    private fun installHooks() {
        if (!hooksInstalled.compareAndSet(false, true)) return

        // ===== 1. 强制 hyper 引擎模式 =====
        hookGroup("IslandEnginePreferences (engine/isHyperMode)", "engine prefs", ISLAND_ENGINE_PREFS) { prefs ->
            hookByNameAndCount(prefs, "engine", 1) {
                setResult(HYPER_MODE)
            }
            hookByNameAndCount(prefs, "isHyperMode", 1) {
                setResult(true)
            }
        }

        // ===== 2. 强制路由到系统原生超级岛 =====
        hookGroup("IslandRouter (shouldUseHyper/isHyperAvailable)", "router", ISLAND_ROUTER) { router ->
            hookByNameAndCount(router, "shouldUseHyper", 1) {
                setResult(true)
            }
            hookByNameAndCount(router, "isHyperAvailable", null) {
                setResult(true)
            }
        }

        // ===== 3. 关断 Shizuku 断网绕过，强制直发（系统原生通道） =====
        hookGroup("HyperIslandBypass#postWithBypassIfReady", "bypass", HYPER_ISLAND_BYPASS) { bypass ->
            hookByNameAndCount(bypass, "postWithBypassIfReady", 5) {
                forceDirectNativePost()
            }
        }

        // ===== 4. 焦点通知权限探测放行（与 H1 系统侧钩子互为备份） =====
        hookGroup("FocusUtils#hasFocusPermission", "focus utils", FOCUS_UTILS) { focusUtils ->
            hookByNameAndCount(focusUtils, "hasFocusPermission", 1) {
                setResult(true)
            }
        }
    }

    /**
     * 单个钩子组的安装外壳（复刻旧版 YukiHookAPI 的分组语义）：
     *  - 目标类不存在 -> 记警告 + 组记成功（版本漂移容忍，不构成注入异常）；
     *  - 目标方法不存在 -> 组记失败 + 计入注入错误；
     *  - 安装异常 -> 组记失败 + 计入注入错误。
     *
     * @param label     组标签（注入明细展示）
     * @param errTag    错误明细前缀
     * @param className 目标类全限定名
     * @param body      类就绪后的钩子注册体
     */
    private fun hookGroup(label: String, errTag: String, className: String, body: (Class<*>) -> Unit) {
        runCatching {
            val clazz = className.findClassIfExists()
            if (clazz == null) {
                IslandLog.w("$className not found")
                return@runCatching
            }
            body(clazz)
        }.onSuccess {
            hookResults += HookResult(label, true)
        }.onFailure {
            hookErrors += "$errTag: ${it.message}"
            IslandLog.w("hook $label failed: ${it.message}")
        }
    }

    /**
     * 按方法名（+ 可选参数个数）过滤后逐个 hook，before 回调统一经 [body] 执行。
     *
     * @param paramCount 参数个数；null 表示不限制（匹配全部同名方法）
     */
    private fun hookByNameAndCount(clazz: Class<*>, name: String, paramCount: Int?, body: AbsHook.() -> Unit) {
        val methods = clazz.declaredMethods.filter {
            it.name == name && (paramCount == null || it.parameterCount == paramCount)
        }
        if (methods.isEmpty()) throw NoSuchMethodError("${clazz.name}#$name")
        val hook = object : AbsHook() {
            override fun before() {
                if (!sdkMappingEnabled) return
                body()
            }
        }
        methods.forEach { it.hook(hook) }
    }

    /**
     * 强制直发：把原方法的 postAction 抛到主线程执行，
     * 语义复刻应用自身的 runPostOnMain（成功回调 onSuccess，失败回调 onFail），
     * 但完全不进入 Shizuku 断网绕过协程。
     */
    private fun AbsHook.forceDirectNativePost() {
        val postAction = getArgOrNull(1) ?: run {
            IslandLog.d("postWithBypassIfReady without postAction, passthrough")
            return
        }
        val onSuccess = getArgOrNull(3)
        val onFail = getArgOrNull(4)
        Handler(Looper.getMainLooper()).post {
            try {
                invokeFunction0(postAction)
                onSuccess?.let { runCatching { invokeFunction0(it) } }
            } catch (t: Throwable) {
                IslandLog.e("forced native post failed: ${t.message}")
                onFail?.let { runCatching { invokeFunction0(it) } }
            }
        }
        // 取消原方法执行（void 方法置空结果即跳过）
        setResult(null)
    }

    /** 取参数（越界/异常时返回 null，替代旧版 args[i] 的可空语义） */
    private fun AbsHook.getArgOrNull(index: Int): Any? =
        runCatching { getArg(index) }.getOrNull()

    /**
     * 反射调用 Kotlin Function0（实况通知侧为混淆后的 y4.a 接口，
     * 与模块侧的 kotlin.jvm.functions.Function0 并非同一运行时类，只能反射 invoke）。
     */
    private fun invokeFunction0(fn: Any) {
        fn.javaClass.getMethod("invoke").invoke(fn)
    }

    /**
     * 注册广播通道：
     *  - 收到 QUERY 时以当前状态回报 STATUS；
     *  - 装载完成主动上报一次 STATUS（心跳）：宿主重启重新装载后模块侧即可即时感知，
     *    无需等待 QUERY-STATUS 一轮往返（应对应用退后台/进程冻结导致的探测无响应）；
     *  - 收到 CONFIG_RELOAD 时热更新日志级别（SDK 映射开关改由 LIST_PUSH 权威推送）；
     *  - 收到 LIST_PUSH 时应用模块直推的权威开关（sdkMapping；1.0.23）；
     *  - 收到 RESTART 时 kill 自身进程（实况通知非常驻，由模块侧重新拉起）。
     *
     * 注：名单判定仅在系统界面侧完成（LIST_VERIFY 不面向实况通知），
     * LIST_PUSH 载荷中的名单字段在此忽略。
     */
    private fun registerChannel() {
        val context = hostContext ?: run {
            // host context 未就绪：attachHostContext 会补注册，非错误
            IslandLog.d("channel register skipped: host context not ready")
            return
        }
        runCatching {
            IslandChannel.registerHostSide(context) { key, value ->
                when (key) {
                    ChannelKeys.QUERY ->
                        // 映射热关闭后不再回报状态（模块侧同步跳过探测）
                        if (sdkMappingEnabled) reportStatus()

                    ChannelKeys.RESTART -> {
                        IslandLog.d("restart requested by module, killing noticeflow process")
                        Handler(Looper.getMainLooper()).post { Process.killProcess(Process.myPid()) }
                    }

                    ChannelKeys.CONFIG_RELOAD -> {
                        IslandLog.d("config reload broadcast received (noticeflow), reloading mapping")
                        reloadMapping()
                    }

                    ChannelKeys.LIST_PUSH -> applyListPush(value)
                }
            }
            // 心跳：装载完成即上报，模块侧常驻监听据此刷新状态
            reportStatus()
            IslandLog.d("channel registered (query/restart/heartbeat/config/list-sync)")
        }.onFailure { IslandLog.w("channel register failed: ${it.message}") }
    }

    /**
     * 应用模块直推的权威配置（1.0.23）：仅消费 [ListSync.Payload.sdkMapping]。
     *
     * LIST_PUSH 是宿主侧权威数据源（远程偏好镜像在该设备已实证不可靠：
     * service 未绑/commit 失败时装载读到的 sdk_mapping 为陈旧 false，
     * 导致不装钩、探测无回报、误报「未激活」）。开关变更打 W 级常驻可见；
     * off→on 时补装钩子（[installHooks] 幂等），on→off 时已装钩子整体放行。
     */
    private fun applyListPush(raw: String) {
        val payload = ListSync.decode(raw) ?: run {
            IslandLog.w("list push decode failed: ${raw.take(80)}")
            return
        }
        val old = sdkMappingEnabled
        val newEnabled = payload.sdkMapping
        sdkMappingEnabled = newEnabled
        if (newEnabled != old) {
            if (newEnabled) {
                installHooks()
                IslandLog.w("list push applied: sdkMapping off->on, hooks (re)installed")
            } else {
                // 钩子保持安装但 before 回调整体放行（见 hookByNameAndCount），
                // 无需卸载；状态改变（五类之一）常驻可见
                IslandLog.w("list push applied: sdkMapping on->off, hooks bypassed")
            }
        } else {
            IslandLog.d("list push applied: sdkMapping=$newEnabled (no change)")
        }
    }

    /**
     * 热更新（1.0.23 起：sdkMapping 以 LIST_PUSH 为权威，远程偏好仅兜底 verbose）。
     *
     * 根因（1.0.22 日志实证）：远程偏好镜像链路在该设备未生效，此前每次
     * CONFIG_RELOAD 都用远程陈旧值整本替换内存开关——用户关闭 sdkMapping 后
     * 被陈旧远程 true 冲回（「关了又恢复」），装载时读到陈旧 false 则不装钩
     * （探测无回报误报「未激活」）。现所有开关写路径均已 LIST_PUSH 直推
     * （见 [ListSyncCoordinator.pushAllHosts]），内存即权威。
     *
     * onPackageReady 装载时仍从远程读一次初值（彼时尚无 LIST_PUSH）；
     * 此后 CONFIG_RELOAD 不再覆盖 sdkMapping，仅补读 verbose 日志级别，
     * 并保留一次延迟补读兜底（远程偏好由框架异步推送，[retry] 防递归重排）。
     */
    private fun reloadMapping(retry: Boolean = false) {
        val remote = IslandConfig.read(prefs(IslandConfig.PREFS_NAME))
        IslandLog.verbose = remote.verboseLog
        IslandLog.d(
            "config reloaded: sdkMapping=${sdkMappingEnabled} (memory authoritative), " +
                "verbose=${remote.verboseLog}"
        )
        if (!retry) {
            hostContext?.let { ctx ->
                Handler(Looper.getMainLooper()).postDelayed(
                    { reloadMapping(retry = true) }, CONFIG_RELOAD_RETRY_MS
                )
            }
        }
    }

    /** 以当前注入状态回报 STATUS */
    private fun reportStatus() {
        val context = hostContext ?: return
        val status = HostStatus(
            process = IslandConfig.PKG_NOTICEFLOW,
            timestamp = System.currentTimeMillis(),
            androidDesc = DeviceCompat.androidDesc,
            hyperOsDesc = DeviceCompat.hyperOsDesc,
            compat = DeviceCompat.isSupported,
            hooks = hookResults.toList(),
            errors = hookErrors.toList()
        )
        runCatching {
            IslandChannel.send(context, modulePkg, ChannelKeys.STATUS, status.toJson(), from = hostPkg)
        }.onFailure { IslandLog.w("status report failed: ${it.message}") }
    }

    private companion object {
        /** CONFIG_RELOAD 延迟补读间隔（等待框架推送远程偏好落地） */
        const val CONFIG_RELOAD_RETRY_MS = 400L
    }
}
