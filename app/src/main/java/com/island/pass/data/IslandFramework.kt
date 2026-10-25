package com.island.pass.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.island.pass.hook.IslandLog
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 侧框架设施（libxposed service 102）。
 *
 *  - [prefs]：配置读写桥。**本地偏好为唯一事实源**（1.0.12 起，任何 service
 *    状态下读写一致，杜绝「修改不保存」的路径分裂回退）；
 *  - [mirrorToRemote]：本地 → 远程偏好同步镜像（commit 语义，写后远程必定落地，
 *    再发广播热更新，消除 IPC 异步竞态）；
 *  - [remotePrefsOrNull]：远程偏好实例（仅 service 在线；升级迁移用，见 [ConfigMirror]）；
 *  - [isLoadedIn]：模块是否已注入指定宿主进程（替代 YukiHookAPI.Status.isModuleActive，
 *    且为进程级精确判定）。
 *
 * 生命周期注记（实证 libxposed service 102 源码）：
 *  - XposedServiceHelper 支持 binder 重投（框架经 XposedProvider.call("SendBinder")
 *    再次投递，如 LSPosed 管理器进程重启后）：onServiceBind 会再次触发，
 *    下方主线程回调重新执行 迁移 → 业务钩子 → 兜底镜像，**断线可自愈**；
 *  - RemotePreferences.Editor.apply() 为异步 IPC（单线程 executor），
 *    故镜像一律用同步 commit()，失败返回 false 并记 W 日志（不再静默）。
 *
 * 注册时机：XposedProvider（service AAR 清单自动合并）的 onCreate 早于
 * Application.onCreate，故在 Application.onCreate 中 registerListener 即可收到缓存 binder。
 */
object IslandFramework {

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var initialized = false

    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * service 绑定成功回调（模块 App 侧设施就绪钩子）。
     * IslandListManager 借此在远程偏好就绪后补刷生效名单，规避初始化竞态。
     */
    @Volatile
    var onServiceBound: (() -> Unit)? = null

    /** Application.onCreate 调用（XposedServiceHelper.registerListener 仅可调一次） */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    this@IslandFramework.service = service
                    IslandLog.d(
                        "xposed service bind: ${service.frameworkName} " +
                            "(${service.frameworkVersion}) API ${service.apiVersion}"
                    )
                    val ctx = appContext ?: return
                    // 主线程串行处理：绑定回调来自 binder 线程（或 Application.onCreate
                    // 同步投递），若直接执行会与初始化竞态。post 到主线程后按序执行：
                    //  1) 远程→本地迁移（升级无缝，先于业务钩子，保证 refresh 读到迁移后的 enabled_lists）；
                    //  2) 业务钩子（IslandListManager.refresh：重算生效名单 → 本地写 → 镜像远程 → 广播）；
                    //  3) 兜底全量镜像（业务钩子缺失/未覆盖时保证远程与本地一致）。
                    // 迁移完成前不镜像，避免把迁移前的陈旧派生名单值推到远程（瞬态覆写）。
                    mainHandler.post {
                        runCatching { ConfigMirror.migrate(ctx) }
                            .onFailure { IslandLog.w("config mirror migrate failed: ${it.message}") }
                        runCatching { onServiceBound?.invoke() }
                            .onFailure { IslandLog.w("onServiceBound hook failed: ${it.message}") }
                        runCatching { mirrorToRemote(ctx, IslandConfig.PREFS_NAME) }
                            .onFailure { IslandLog.w("mirror after bind failed: ${it.message}") }
                        // 1.0.22：模块（重）启动后主动校验宿主内存名单，
                        // 回收模块死亡期间被拦截的 CONFIG_RELOAD（Greezer）导致的陈旧
                        runCatching { ListSyncCoordinator.initiateVerify(ctx) }
                            .onFailure { IslandLog.w("list verify after bind failed: ${it.message}") }
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    this@IslandFramework.service = null
                    // 镜像通道中断：此后本地写入无法推达 hooker，直至 binder 重投自愈。
                    // 记 W 级（LSPosed 日志页可见），便于用户感知「改了没生效」的直接原因。
                    IslandLog.w("xposed service died: remote mirror offline until rebind")
                }
            })
        }.onFailure { IslandLog.w("xposed service register failed: ${it.message}") }
    }

    /**
     * 模块 App 侧偏好：唯一事实源 = 本地偏好。
     *
     * 远程偏好仅作为 hooker 侧的只读镜像（每次本地写操作后经 [mirrorToRemote] 补镜），
     * 不再参与读写路径，规避 service 绑定状态导致的读写分裂与回退。
     */
    fun prefs(context: Context, name: String): SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** 远程偏好实例（仅 service 在线时可用；供 [ConfigMirror] 升级迁移读取旧值） */
    fun remotePrefsOrNull(name: String): SharedPreferences? {
        val svc = service ?: return null
        return runCatching { svc.getRemotePreferences(name) }.getOrNull()
    }

    /**
     * 本地偏好 → 远程偏好全量镜像（hooker 侧数据源），**同步 commit 语义**。
     *
     *  - 按值类型分发 put（Boolean/String/Int/Long/Float/Set<String>）；
     *  - commit 同步完成 IPC，返回前远程必定落地——调用方可立即发广播热更新；
     *  - service 离线时记 W 日志后跳过（1.0.23 起：镜像已非权威路径，
     *    但跳过必须可见——「改了不生效」的根因定位依赖这行日志）；
     *  - 只增不删：远程残留的本地已删键不移除（框架内部键保护，且本模块键只增不减）。
     */
    fun mirrorToRemote(context: Context, name: String) {
        val remote = remotePrefsOrNull(name) ?: run {
            // 通讯错误/功能失效（五类之一，1.0.23 起升 W 级常驻可见）：
            // service 未绑定时镜像静默跳过曾导致「改了不生效」无据可查——
            // 1.0.22 日志实证该设备镜像链路整体未生效（宿主每次读到的都是
            // 陈旧/空远程值）。升至 W 级使用户在 LSPosed 日志页可直接看到
            // 镜像通道未就绪；LIST_PUSH 权威直推（1.0.23）不依赖镜像，此为兜底路径
            IslandLog.w("mirror to remote skipped ($name): service not bound")
            return
        }
        runCatching {
            val local = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = remote.edit()
            var count = 0
            local.all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is String -> editor.putString(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Float -> editor.putFloat(k, v)
                    is Set<*> -> editor.putStringSet(k, v.mapNotNull { it as? String }.toSet())
                    else -> return@forEach
                }
                count++
            }
            // 同步 commit（非异步 apply）：远程落地后才返回，消除与广播的时序竞态
            val ok = editor.commit()
            if (ok) {
                // D 级（verbose-only，1.0.20 起）：保存链路关键节点诊断
                IslandLog.d("mirror to remote committed: $name ($count keys)")
            } else {
                // 功能失效（五类之一）：远程未落地，hooker 侧将维持旧配置
                IslandLog.w("mirror to remote commit returned false: $name ($count keys)")
            }
        }.onFailure { IslandLog.w("mirror to remote failed ($name): ${it.message}") }
    }

    /** service 是否已绑定（远程镜像可用性判定） */
    fun isServiceBound(): Boolean = service != null

    /**
     * 模块是否已加载进指定宿主进程（hooker 已装载的精确判定）。
     *
     * 三态语义（1.0.23）：
     *  - true：已加载；
     *  - false：明确未加载；
     *  - null：**无法判定**（service 未绑定，getRunningTargets 不可用）。
     *
     * 此前未绑定时返回 false，探测方会把"判定不了"误报成"未激活"
     * （FAILED）——service 未绑时即使目标应用在前台打开也误报。调用方
     * 须区分 null 与 false（见 SettingsActivity.analyzeTimeout）。
     */
    fun isLoadedIn(pkg: String): Boolean? {
        val svc = service ?: return null
        return runCatching {
            svc.getRunningTargets().any { it.processName == pkg }
        }.getOrNull()
    }
}
