package com.island.pass

import android.content.Context
import com.hchen.hooktool.ModuleConfig
import com.hchen.hooktool.ModuleData
import com.hchen.hooktool.ModuleEntrance
import com.island.pass.data.IslandConfig
import com.island.pass.hook.IslandLog
import com.island.pass.hook.NoticeFlowHooker
import com.island.pass.hook.SystemUiHooker
import io.github.libxposed.api.XposedModuleInterface

/**
 * 超级岛通行证 —— libxposed（现代 API，API 102）模块入口。
 *
 * 双作用域（与旧版一致，见 scope.list / 管理器勾选）：
 *  1. com.android.systemui：剔除小米超级岛（DynamicIsland 插件）对焦点通知的
 *     白名单/签名限制（NotificationSettingsManager 六道门禁）；
 *  2. com.spark.noticeflow：将实况通知内嵌 HyperNotification SDK 的岛强制映射到
 *     系统原生超级岛——关掉 Shizuku 断网绕过，强制走原生通知通道。
 *
 * 迁移说明（自 YukiHookAPI 1.3.1 / rovo89 legacy API 82）：
 *  - 入口经 META-INF/xposed/java_init.list 注册，取代 assets/xposed_init；
 *  - 模块配置经 META-INF/xposed/module.prop（minApiVersion=101 / targetApiVersion=102）；
 *  - 配置跨进程共享经 XposedInterface 远程偏好（hooker 侧只读 + 框架推送），
 *    模块 App 侧经 XposedService 远程偏好可写（见 [com.island.pass.data.IslandFramework]）；
 *  - 宿主动态经广播通道（见 [com.island.pass.data.IslandChannel]）。
 */
class IslandModuleEntry : ModuleEntrance() {

    private var sysuiHooker: SystemUiHooker? = null
    private var noticeflowHooker: NoticeFlowHooker? = null

    override fun initModuleConfig() {
        ModuleConfig.setLogTag("IslandPass")
        ModuleConfig.setLogLevel(ModuleConfig.LOG_I)
        ModuleConfig.setPrefsName(IslandConfig.PREFS_NAME)
    }

    override fun handleModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        IslandLog.d(
            "module loaded: api=$apiVersion framework=$frameworkName($frameworkVersionCode) " +
                "process=${param.processName}"
        )
    }

    override fun handlePackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // libxposed 会为进程内加载的每个包触发回调（含共享 UID / createPackageContext），
        // 必须按包名过滤，仅处理声明的作用域主包。
        when (param.packageName) {
            IslandConfig.PKG_SYSTEMUI -> {
                ModuleData.setClassLoader(param.classLoader)
                val hooker = sysuiHooker ?: SystemUiHooker().also { sysuiHooker = it }
                hooker.handlePackageReady(param)
            }

            IslandConfig.PKG_NOTICEFLOW -> {
                ModuleData.setClassLoader(param.classLoader)
                val hooker = noticeflowHooker ?: NoticeFlowHooker().also { noticeflowHooker = it }
                hooker.handlePackageReady(param)
            }

            else -> IslandLog.d("ignore package ${param.packageName}")
        }
    }

    override fun handleApplicationCreated(context: Context) {
        // ModuleEntrance 已在 onPackageReady 挂接宿主 Application.attach；
        // 宿主上下文就绪后注入 hooker：注册广播通道接收器并发出装载心跳。
        when (context.packageName) {
            IslandConfig.PKG_SYSTEMUI -> sysuiHooker?.attachHostContext(context)
            IslandConfig.PKG_NOTICEFLOW -> noticeflowHooker?.attachHostContext(context)
        }
    }

    /**
     * 拒绝 libxposed 热重载（API 102）。
     *
     * 模块不持有任何"仅存于内存"的状态：配置全部来自远程偏好 + 广播通道热更新，
     * 进程重启即为完整生效路径（与「重启作用域应用」的产品流程一致）。
     * 拒绝后旧钩子保持安装，框架不重放包生命周期回调，无副作用。
     */
    override fun isHotReloadingAllowed(packageName: String): Boolean = false
}
