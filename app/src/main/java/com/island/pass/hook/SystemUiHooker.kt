package com.island.pass.hook

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.hchen.hooktool.AbsModule
import com.hchen.hooktool.ModuleData
import com.hchen.hooktool.core.CoreTool.Companion.findClass
import com.hchen.hooktool.core.CoreTool.Companion.findClassIfExists
import com.hchen.hooktool.core.CoreTool.Companion.findAllMethod
import com.hchen.hooktool.core.CoreTool.Companion.findMethodIfExists
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
import com.island.pass.data.IslandSettings
import com.island.pass.data.ListSync
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * H1 —— 小米超级岛白名单/签名限制剔除（运行于 com.android.systemui 进程）。
 *
 * 实证（APK 逆向，系统界面组件 17.1.4.22.1 / 系统界面 16.03.251211.r）：
 *  超级岛（DynamicIsland）渲染实现位于插件 miui.systemui.plugin，其包内
 *  `miui.systemui.notification.NotificationSettingsManager` 持有全部门禁，
 *  反汇编确认均为**纯包名 List 判定**（无签名校验，白名单由云端下发）：
 *
 *    canCustomFocus(String)                        -> mAllowCustomFocusPkgs.contains(pkg) || DEBUG
 *    canShowFocus(Context, String)                 -> mAllowFocusPkgs.contains(pkg) || DEBUG
 *    canPassXMSPermission(String)                  -> mPassXMSPkgs.contains(pkg)
 *    isDynamicIslandLiveUpdateBlackList(String)    -> mDynamicIslandLiveUpdateBlackList.contains(pkg)
 *    isDynamicIslandMiniWindowBlackList(String)    -> mDynamicIslandMiniWindowActivityBlackList.contains(pkg)
 *    isFocusNotificationDeletedBlackList(String)   -> mFocusNotificationDeletedBlackList.contains(pkg)
 *
 * 插件 ClassLoader 获取链（四路兜底，逐路独立、互不依赖）：
 *   路径 1  PluginInstance$Factory#create：扫描全部参数，记录目标插件的
 *          ApplicationInfo 与任何 ClassLoader 实参；
 *   路径 2  Factory 内 ExternalSyntheticLambdaN#get()：返回值即插件 ClassLoader；
 *   路径 3  PluginInstance#loadPlugin 之后：遍历实例**全部**字段收集候选 loader；
 *   路径 4  ClassLoader#loadClass(String, boolean) 全局观察：记录**所有**类加载器
 *          （插件入口类必经 loader.loadClass，其 loader 由此入库），类名简名以
 *          NotificationSettingsManager 开头者直接命中；命中后自卸载。
 *
 * 1.0.16 诊断增强（W 级、无需开启详细日志即可在 LSPosed 日志页可见）：
 *  门禁安装失败不再静默——loadPlugin 时一次性 dump 实例字段/候选 loader/dex 路径，
 *  并枚举插件 APK 的类名（Notification/Island/Focus 等关键字），用于定位剩余
 *  两种未知：插件内类名漂移，或真实插件 loader 不在候选集中。
 *
 * 1.0.17 收尾（基于 1.0.16 诊断数据的修复）：
 *  - 路径 5（终极兜底）：门禁类自动发现——精确类名 findClass 失败时，枚举插件
 *    APK 候选类名并经确认持有插件 APK 的 loader 逐一 loadClass 验证
 *    （initialize=false 不触发静态初始化），取声明了 >=2 个门禁方法的类
 *    作为 NSM 替代，容忍类名漂移/裁剪（仅执行一次，见 [discoverNsmClass]）；
 *  - findClass 失败记录**真实异常类型**（此前 runCatching 吞掉一切，无法区分
 *    ClassNotFoundException「类不存在」与 NoClassDefFoundError「依赖解析失败」）；
 *  - 兜底诊断调度修复：onPackageReady 早于 attachHostContext，此前直接判空
 *    导致 8s 诊断从未运行；现经 [currentContext]（ActivityThread 反射兜底）
 *    延迟取 Context，且 APK 枚举本身不依赖 Context（pendingAppInfo.sourceDir）；
 *  - 新增插件 versionName 与 loader 父子链日志，补全最后两项定位数据。
 */
internal class SystemUiHooker : AbsModule() {

    private val PLUGIN_INSTANCE_FACTORY = "com.android.systemui.shared.plugins.PluginInstance\$Factory"
    private val PLUGIN_INSTANCE = "com.android.systemui.shared.plugins.PluginInstance"
    private val NSM = "miui.systemui.notification.NotificationSettingsManager"

    /** create() 捕获的待观察 ApplicationInfo（跨方法传递，可能来自任意线程） */
    @Volatile
    private var pendingAppInfo: ApplicationInfo? = null

    /** NSM 钩子仅允许安装一次 */
    private val nsmHooksInstalled = AtomicBoolean(false)

    /** 全部观察链仅执行一次（防止 CONFIG_RELOAD 关→开补装时重复 hook） */
    private val hooksInstalled = AtomicBoolean(false)

    /** loadClass 观察仅允许安装一次 */
    private val loadWatchInstalled = AtomicBoolean(false)

    /** create() 调用计数（诊断日志有界输出） */
    private val createCallCount = AtomicInteger(0)

    /** loadPlugin 诊断 dump 计数（有界输出） */
    private val pluginDumpCount = AtomicInteger(0)

    /** 插件 APK 类枚举仅执行一次 */
    private val apkDumpDone = AtomicBoolean(false)

    /** loadClass 观察触发次数（诊断：验证观察是否存活） */
    private val watchFireCount = AtomicInteger(0)

    /** NSM 查找失败的日志/错误仅记录首次（候选 loader 众多时防洪水） */
    private val nsmSearchLogged = AtomicBoolean(false)

    /** findClass 真实失败已记录的 loader（按身份去重，防洪水；诊断） */
    private val findClassFailedLoaders = CopyOnWriteArrayList<ClassLoader>()

    /** loadClass 观察中见过的 NotificationSettings* 类名次数（诊断：类名漂移判定） */
    private val notificationSettingsSeen = AtomicInteger(0)

    /** 门禁类自动发现仅执行一次（路径 5 终极兜底） */
    private val nsmDiscoveryDone = AtomicBoolean(false)

    /** 兜底诊断仅调度一次（幂等；onPackageReady 与 attachHostContext 双入口） */
    private val fallbackDiagDone = AtomicBoolean(false)

    /** loadClass 观察钩子实例（门禁安装成功后主动卸载，收敛热路径开销） */
    @Volatile
    private var classWatchHook: AbsHook? = null

    /**
     * 各路径观察到的 ClassLoader 候选（CopyOnWrite：回调来自任意线程）。
     * loadClass 观察会记录**所有**类加载器——插件入口类必经 loader.loadClass，
     * 其 loader 由此入库，不依赖 PluginInstance 字段结构。
     */
    private val recordedLoaders = CopyOnWriteArrayList<ClassLoader>()

    /** 生效配置快照（CONFIG_RELOAD 热更新，重启后重读） */
    @Volatile
    private var islandSettings: IslandSettings = IslandConfig.defaults()

    /** 钩子安装结果（供状态查询读取；NSM 门禁为异步安装，故用可变列表） */
    private val hookResults = mutableListOf<HookResult>()

    /** 安装过程中的错误明细 */
    private val hookErrors = mutableListOf<String>()

    /** 宿主应用上下文（入口在 Application.attach 后注入；通道依赖之） */
    @Volatile
    private var hostContext: Context? = null

    /** 宿主进程包名（STATUS 广播的来源标识） */
    private val hostPkg: String get() = IslandConfig.PKG_SYSTEMUI

    /** 模块包名（宿主进程内经包装器获取，STATUS 广播的目标包） */
    private val modulePkg: String
        get() = runCatching { ModuleData.getModulePackageName() }.getOrNull() ?: BuildConfig.APPLICATION_ID

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!DeviceCompat.isSupported) {
            IslandLog.w("unsupported device (${DeviceCompat.deviceSummary()}), skip")
            return
        }

        islandSettings = IslandConfig.read(prefs(IslandConfig.PREFS_NAME))
        IslandLog.verbose = islandSettings.verboseLog
        // 初始配置快照（D 级：verbose-only 诊断基线，避免常驻日志过详细）
        IslandLog.d(
            "initial config: bypass=${islandSettings.bypassWhitelist}, " +
                "white=${islandSettings.whitelist.size} [${islandSettings.whitelist.joinToString(",")}], " +
                "black=${islandSettings.blacklist.size} [${islandSettings.blacklist.joinToString(",")}]"
        )

        // 远程偏好变更监听：框架推送到达时的兜底热更新（与 CONFIG_RELOAD 广播双保险）
        runCatching {
            prefs(IslandConfig.PREFS_NAME).registerOnSharedPreferenceChangeListener { _, key ->
                IslandLog.d("remote prefs pushed (key=$key), reloading settings")
                reloadSettings()
            }
        }.onFailure { IslandLog.w("prefs listener register failed: ${it.message}") }

        // 无论 bypass 开关均武装观察链：关闭时只记录 loader 不装门禁，
        // 用户稍后开启功能时可立即经已记录 loader 补装（无需重启系统界面）。
        installHooks()

        if (!islandSettings.bypassWhitelist) {
            // 功能停用（五类之一：状态改变）：观察链已武装但门禁延后，
            // 常驻可见以区分"未启用"与"注入失败"。
            IslandLog.w("whitelist bypass disabled by config: observers armed, gates deferred")
            scheduleFallbackDiag()
            return
        }

        // bypass 开启：立即尝试已记录的 loader（系统界面启动早期通常为空，
        // 等待观察链在插件加载时命中）
        tryRecordedLoaders()

        IslandLog.w("systemui hook chain installed")

        // 兜底诊断：若干秒后门禁仍未安装（插件未加载 / 类名漂移 / 加载异常），
        // 打印插件版本号、loader 父子链并枚举插件 APK 类名。
        // 注意：必须无条件调度——onPackageReady 早于 attachHostContext（1.0.16
        // 在此判空导致诊断从未运行），Context 改为触发时经 currentContext() 获取，
        // 且 APK 枚举本身不依赖 Context。
        scheduleFallbackDiag()
    }

    /**
     * 宿主上下文就绪（Application.attach 后由入口调用）：
     * 无条件注册通道——功能开关关闭时也要能收到 CONFIG_RELOAD 热更新。
     *
     * 1.0.22：注册后立即向模块发 LIST_REQUEST——宿主进程（重）启动后内存中的
     * 名单快照已丢失，模块经「指纹校验 → 不一致直推」闭环重新灌入并存入内存。
     */
    fun attachHostContext(context: Context) {
        hostContext = context
        registerChannel()
        // Context 就绪时补一次兜底诊断调度（幂等）：若 onPackageReady 时的调度
        // 已消耗则此处为空操作；否则可获得含插件版本号的完整诊断
        scheduleFallbackDiag()
        // 名单重同步请求（宿主重启 resync）
        IslandChannel.send(context, modulePkg, ChannelKeys.LIST_REQUEST, from = hostPkg)
        IslandLog.d("list request sent to module (host restart resync)")
    }

    /** 安装全部观察链（幂等；bypass 关闭时仅武装、不安装门禁） */
    private fun installHooks() {
        if (!hooksInstalled.compareAndSet(false, true)) return

        IslandLog.d(
            "hooking systemui ${IslandConfig.PKG_SYSTEMUI}, bypass=${islandSettings.bypassWhitelist}, " +
                "white=${islandSettings.whitelist.size}, black=${islandSettings.blacklist.size}"
        )

        // ===== 路径 1：Factory.create 记录目标插件的 ApplicationInfo / ClassLoader =====
        hookFactoryCreate()

        // ===== 路径 2：ExternalSyntheticLambdaN.get() 拿插件 ClassLoader =====
        hookPluginClassLoaderLambdas()

        // ===== 路径 3（兜底）：PluginInstance.loadPlugin 后全字段扫描 loader =====
        hookPluginLoad()

        // ===== 路径 4（版本无关兜底）：ClassLoader#loadClass 全局观察 =====
        installClassLoadWatch()
    }

    // ==================== 路径 1：Factory.create ====================

    /**
     * 扫描 PluginInstance$Factory#create 的全部参数：
     *  - ApplicationInfo：记录目标插件的 ApplicationInfo（供路径 2 兜底比对）；
     *  - ClassLoader：直接作为候选 loader。
     * 不再假设参数个数与下标（不同 ROM 的 Factory.create 重载签名可能不同）。
     */
    private fun hookFactoryCreate() {
        runCatching {
            val factoryClass = PLUGIN_INSTANCE_FACTORY.findClass()
            val methods = factoryClass.declaredMethods.filter { it.name == "create" }
            if (methods.isEmpty()) throw NoSuchMethodError("PluginInstance\$Factory#create")
            methods.forEach { m ->
                m.hook(object : AbsHook() {
                    override fun before() {
                        val args = runCatching { getArgs() }.getOrNull() ?: return
                        val appInfo = args.firstOrNull { it is ApplicationInfo } as? ApplicationInfo
                        val isTarget = appInfo?.packageName == IslandConfig.PKG_ISLAND_PLUGIN
                        // 诊断：前数次调用打印插件包名与 APK 路径（有界输出，D 级）
                        if (createCallCount.getAndIncrement() < 3) {
                            IslandLog.d(
                                "Factory.create: pkg=${appInfo?.packageName}, target=$isTarget, " +
                                    "apk=${appInfo?.sourceDir}, args=[${args.joinToString { it?.javaClass?.simpleName ?: "null" }}]"
                            )
                        }
                        if (isTarget) {
                            pendingAppInfo = appInfo
                            IslandLog.d("plugin create observed for ${appInfo?.packageName}")
                        }
                        args.forEachIndexed { i, arg ->
                            if (arg is ClassLoader) armOrInstall(arg, null, "create-arg#$i")
                        }
                    }
                })
            }
        }.onSuccess {
            hookResults += HookResult("PluginInstance\$Factory#create", true)
        }.onFailure {
            hookErrors += "Factory#create: ${it.message}"
            IslandLog.w("hook Factory.create failed: ${it.message}")
        }
    }

    // ==================== 路径 2：ExternalSyntheticLambda ====================

    /** 遍历 Factory 的所有合成 Lambda，hook 其无参 get() */
    private fun hookPluginClassLoaderLambdas() {
        val factoryClass = runCatching { PLUGIN_INSTANCE_FACTORY.findClass() }.getOrNull() ?: run {
            hookErrors += "PluginInstance\$Factory not found"
            return
        }
        val lambdas = factoryClass.declaredClasses.filter {
            it.name.contains("ExternalSyntheticLambda")
        }
        if (lambdas.isEmpty()) {
            // 路径 2 无合成 Lambda 属正常路径选择（由路径 3 兜底），不计入错误汇总
            IslandLog.d("no ExternalSyntheticLambda found on Factory, fallback to loadPlugin scan")
            return
        }
        lambdas.forEach { lambdaClass ->
            runCatching {
                val methods = lambdaClass.declaredMethods.filter { it.name == "get" }
                if (methods.isEmpty()) throw NoSuchMethodError("${lambdaClass.name}#get")
                methods.forEach { m ->
                    m.hook(object : AbsHook() {
                        override fun after() {
                            val loader = getResult() as? ClassLoader ?: return
                            // appInfo 优先从 lambda 实例反射（构造器捕获），其次用 create() 记录值
                            val appInfo = reflectAppInfo(thisObject) ?: pendingAppInfo ?: return
                            if (appInfo.packageName != IslandConfig.PKG_ISLAND_PLUGIN) return
                            armOrInstall(loader, null, "lambda-get")
                        }
                    })
                }
            }.onSuccess {
                hookResults += HookResult("${lambdaClass.simpleName}#get", true)
            }.onFailure {
                hookErrors += "${lambdaClass.name}#get: ${it.message}"
                IslandLog.w("hook ${lambdaClass.name}#get failed: ${it.message}")
            }
        }
    }

    // ==================== 路径 3：loadPlugin 全字段扫描 ====================

    private fun hookPluginLoad() {
        runCatching {
            val clazz = PLUGIN_INSTANCE.findClassIfExists() ?: throw ClassNotFoundException(PLUGIN_INSTANCE)
            val methods = clazz.declaredMethods.filter { it.name == "loadPlugin" }
            if (methods.isEmpty()) throw NoSuchMethodError("PluginInstance#loadPlugin")
            methods.forEach { m ->
                m.hook(object : AbsHook() {
                    override fun after() {
                        // 诊断 dump（有界）：实例字段结构 + 候选 loader + dex 路径
                        if (pluginDumpCount.getAndIncrement() < PLUGIN_DUMP_LIMIT) {
                            dumpPluginLoadInfo(thisObject)
                        }
                        collectPluginLoaders(thisObject).forEach { loader ->
                            if (nsmHooksInstalled.get()) return
                            armOrInstall(loader, null, "loadPlugin-fallback")
                        }
                        // loadClass 观察可能已记录插件入口类的 loader，统一尝试
                        tryRecordedLoaders()
                        // 1.0.17：门禁仍未安装时立即枚举插件 APK 类名
                        // （此时 recordedLoaders 已收录本次扫描到的插件 loader，
                        // 无需等待 8s 兜底、也不依赖 hostContext）
                        if (!nsmHooksInstalled.get()) dumpPluginApkClasses(null)
                    }
                })
            }
        }.onSuccess {
            hookResults += HookResult("PluginInstance#loadPlugin", true)
        }.onFailure {
            hookErrors += "PluginInstance#loadPlugin: ${it.message}"
            IslandLog.w("hook PluginInstance.loadPlugin failed: ${it.message}")
        }
    }

    /**
     * 从 PluginInstance 实例收集全部可能的插件 ClassLoader：
     *  - ClassLoader 类型字段（插件框架可能直接持有）；
     *  - Context 子类字段（PluginContextWrapper 等，取其 classLoader）；
     *  - Class 类型字段（如 mPluginClass，类本身即由插件 loader 加载）；
     *  - 其他任意字段值取其运行时类的 loader（覆盖 mPlugin 这类
     *    接口类型字段实际持有插件实现类的场景——其类加载器正是插件 loader）。
     * 逐级上溯父类；单字段读取失败不影响其余字段。
     */
    private fun collectPluginLoaders(instance: Any?): List<ClassLoader> {
        instance ?: return emptyList()
        val out = LinkedHashSet<ClassLoader>()
        runCatching {
            var c: Class<*>? = instance.javaClass
            while (c != null && c.name != "java.lang.Object") {
                c.declaredFields.forEach { f ->
                    runCatching {
                        f.isAccessible = true
                        when (val v = f.get(instance)) {
                            is ClassLoader -> out += v
                            is Class<*> -> v.classLoader?.let { out += it }
                            is Context -> v.classLoader?.let { out += it }
                            else -> v?.javaClass?.classLoader?.let { out += it }
                        }
                    }.onFailure {
                        IslandLog.d("field scan skipped ${c.name}.${f.name}: ${it.message}")
                    }
                }
                c = c.superclass
            }
        }.onFailure { IslandLog.d("plugin loader scan failed: ${it.message}") }
        return out.toList()
    }

    // ==================== 路径 4：ClassLoader#loadClass 全局观察 ====================

    /**
     * 版本无关的兜底路径：hook 基类 [ClassLoader]#loadClass(String, boolean)，
     * 记录**所有**类加载器（插件入口类必经此方法，其 loader 由此入库），
     * 并在类名简名以 NotificationSettingsManager 开头时直接命中插件 NSM 类
     * （含 $1 内部类 / Impl 变体等漂移形态）。
     *
     * 性能：仅在门禁尚未安装时工作（一次 volatile 读 + 身份去重 + 字符串前缀
     * 判断，无分配）；命中后自卸载，门禁经其他路径安装成功后亦主动卸载，
     * 不常驻热路径。
     */
    private fun installClassLoadWatch() {
        if (!loadWatchInstalled.compareAndSet(false, true)) return
        runCatching {
            val method = ClassLoader::class.java.declaredMethods.firstOrNull {
                it.name == "loadClass" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            } ?: throw NoSuchMethodError("ClassLoader#loadClass(String, boolean)")
            method.isAccessible = true
            val watch = object : AbsHook() {
                override fun after() {
                    watchFireCount.incrementAndGet()
                    // 热路径守卫：门禁已安装即整体退出并自卸载
                    if (nsmHooksInstalled.get()) {
                        unhookSelf()
                        return
                    }
                    val clazz = runCatching { getResult() as? Class<*> }.getOrNull() ?: return
                    val name = clazz.name ?: return
                    // 记录全部类加载器（有界去重）：插件入口类的 loader 由此入库
                    recordLoader(clazz.classLoader)
                    // 诊断（verbose）：暴露插件内 NotificationSettings* 类名的版本漂移
                    if (name.contains("NotificationSettings")) {
                        notificationSettingsSeen.incrementAndGet()
                        IslandLog.d("observed plugin class: $name")
                    }
                    if (!name.isNsmClass()) return
                    armOrInstall(clazz.classLoader, clazz, "loadClass-watch")
                }
            }
            classWatchHook = watch
            method.hook(watch)
            hookResults += HookResult("ClassLoader#loadClass watch", true)
            IslandLog.d("class load watch armed on ClassLoader#loadClass")
        }.onFailure {
            classWatchHook = null
            loadWatchInstalled.set(false) // 允许后续补装重试
            hookErrors += "ClassLoader#loadClass watch: ${it.message}"
            IslandLog.d("class load watch install failed: ${it.message} (covered by plugin loader paths)")
        }
    }

    // ==================== NSM 门禁安装 ====================

    /**
     * 记录候选 loader（按身份去重，有界）；bypass 开启则立即尝试安装门禁，
     * 关闭时仅记录（用户稍后开启功能时经 [tryRecordedLoaders] 补装）。
     */
    private fun armOrInstall(loader: ClassLoader, observed: Class<*>?, source: String) {
        recordLoader(loader)
        if (!islandSettings.bypassWhitelist) return
        tryInstallNsmHooks(loader, observed, source)
    }

    private fun recordLoader(loader: ClassLoader?) {
        loader ?: return
        if (recordedLoaders.size >= LOADER_RECORD_CAP) return
        if (recordedLoaders.none { it === loader }) recordedLoaders += loader
    }

    /** 立即尝试所有已记录的 loader（bypass 关→开 / 启动时插件已加载的场景） */
    private fun tryRecordedLoaders() {
        recordedLoaders.toList().forEach { loader ->
            if (nsmHooksInstalled.get()) return
            tryInstallNsmHooks(loader, null, "recorded-loader")
        }
    }

    /**
     * 定位 NSM 类并安装六道门禁钩子（仅一次）。
     * 定位顺序：经 loader 精确类名 -> 已观察到的候选类（容忍类名漂移）。
     */
    private fun tryInstallNsmHooks(loader: ClassLoader?, observed: Class<*>?, source: String) {
        if (!nsmHooksInstalled.compareAndSet(false, true)) return
        var nsm = resolveNsm(loader, observed)
        if (nsm == null && loader != null && isPluginLoader(loader)) {
            // 路径 5（终极兜底）：精确类名失败且 loader 确认持有插件 APK 时，
            // 枚举插件 APK 候选类名逐一探测，容忍类名漂移/裁剪
            nsm = discoverNsmClass(loader)
        }
        if (nsm == null) {
            // 候选 loader 众多时仅首次记 E（可见），其余 D（防日志洪水）；
            // 错误条目同步只记首次，成功路径统一清理
            if (nsmSearchLogged.compareAndSet(false, true)) {
                IslandLog.e("$NSM not found in pluginLoader ($source, observed=${observed?.name})")
                hookErrors += "$NSM not found ($source)"
            } else {
                IslandLog.d("$NSM not found ($source)")
            }
            nsmHooksInstalled.set(false) // 允许后续路径重试
            return
        }
        IslandLog.d("NSM hooks installing via $source, class=${nsm.name}, loader=$loader")

        // 白名单/权限门禁 -> 剔除（名单外应用按名单模式判定是否放行）
        hookGate(nsm, "canCustomFocus", listOf("java.lang.String"), GateKind.ALLOW, 0)
        hookGate(nsm, "canShowFocus", listOf("android.content.Context", "java.lang.String"), GateKind.ALLOW, 1)
        hookGate(nsm, "canPassXMSPermission", listOf("java.lang.String"), GateKind.ALLOW, 0)
        // 黑名单门禁 -> 关闭（黑名单模式下名单内应用重新命中）
        hookGate(nsm, "isDynamicIslandLiveUpdateBlackList", listOf("java.lang.String"), GateKind.DENY, 0)
        hookGate(nsm, "isDynamicIslandMiniWindowBlackList", listOf("java.lang.String"), GateKind.DENY, 0)
        hookGate(nsm, "isFocusNotificationDeletedBlackList", listOf("java.lang.String"), GateKind.DENY, 0)

        // 门禁安装成功：loadClass 观察使命完成，主动卸载收敛热路径开销
        runCatching { classWatchHook?.unhookSelf() }
            .onFailure { IslandLog.d("class watch unhook skipped: ${it.message}") }

        // 六道门禁安装成功：清理路径探索期的临时失败（ClassLoader 获取 / NSM 定位），
        // 这些失败均已被当前成功路径取代，不应计入注入异常（否则 allOk 恒假、UI 误报）
        hookErrors.removeAll { err ->
            err.contains("ExternalSyntheticLambda") ||
                err.contains("NotificationSettingsManager not found") ||
                err == "PluginInstance\$Factory not found"
        }
        hookResults += HookResult("NSM 六道门禁", true, "via $source · 白:${islandSettings.whitelist.size} 黑:${islandSettings.blacklist.size}")
        // 装载完毕，推送最终态：模块侧可能还停留在 registerChannel 心跳发出的中间态快照
        // （彼时 NSM 尚未定位），此处主动上报确保 UI 反映最终注入结果
        reportStatus()
        // 注入成功（五类之一）：六道门禁就绪，常驻可见
        IslandLog.w("NSM gates hooked via $source (allow gates=bypass, deny gates=off, white=${islandSettings.whitelist.size}, black=${islandSettings.blacklist.size})")
    }

    /**
     * NSM 类定位：loader 精确类名优先，其次已观察到的候选类（类名漂移容错）。
     * 1.0.17：findClass 失败不再被静默吞掉——[noteFindClassFailure] 记录真实
     * 异常类型（ClassNotFoundException=类不存在 / NoClassDefFoundError=依赖
     * 解析失败 / VerifyError=校验失败），这是定位剩余故障的决定性数据。
     */
    private fun resolveNsm(loader: ClassLoader?, observed: Class<*>?): Class<*>? {
        if (loader != null) {
            try {
                return NSM.findClass(loader)
            } catch (t: Throwable) {
                noteFindClassFailure(loader, t)
            }
        }
        if (observed != null && observed.name.isNsmClass()) return observed
        return null
    }

    /** 记录某 loader 上 findClass 的真实失败（按 loader 身份去重，E 级可见） */
    private fun noteFindClassFailure(loader: ClassLoader, t: Throwable) {
        if (findClassFailedLoaders.none { it === loader }) {
            findClassFailedLoaders += loader
            IslandLog.e(
                "findClass $NSM failed: ${t.javaClass.name}: ${t.message} " +
                    "(loader=${loader.javaClass.name}, dex=${dexPathsOf(loader)})"
            )
        }
    }

    /**
     * 路径 5：门禁类自动发现（版本无关终极兜底）。
     * 枚举插件 APK 候选类名（Notification/Island/Focus 等关键字），逐个经目标
     * loader `loadClass`（initialize=false，不触发静态初始化，无副作用），
     * 取声明了 >= [DISCOVERY_MIN_GATE_HITS] 个门禁方法的类作为 NSM 替代。
     * 仅执行一次（[nsmDiscoveryDone]）。
     */
    private fun discoverNsmClass(loader: ClassLoader): Class<*>? {
        if (!nsmDiscoveryDone.compareAndSet(false, true)) return null
        val candidates = collectPluginApkClassNames()
        IslandLog.d("nsm auto-discovery: probing ${candidates.size} candidates via ${loader.javaClass.name}")
        for (name in candidates) {
            val clazz = runCatching { loader.loadClass(name) }.getOrNull() ?: continue
            val hits = GATE_METHOD_NAMES.count { m -> clazz.declaredMethods.any { it.name == m } }
            if (hits >= DISCOVERY_MIN_GATE_HITS) {
                IslandLog.d("nsm auto-discovery hit: $name (gateMethods=$hits)")
                return clazz
            }
        }
        IslandLog.d("nsm auto-discovery: no gate class found in plugin apk")
        return null
    }

    /** loader 是否持有目标插件 APK（与 pendingAppInfo 的路径精确比对） */
    private fun isPluginLoader(loader: ClassLoader): Boolean {
        val target = pendingAppInfo ?: return false
        val paths = dexPathsOf(loader)
        return paths.any { it == target.sourceDir || it == target.publicSourceDir }
    }

    /** 类名简名是否以 NotificationSettingsManager 开头（含内部类 / 变体后缀） */
    private fun String.isNsmClass(): Boolean =
        substringAfterLast('.').startsWith("NotificationSettingsManager")

    /** 门禁类型：ALLOW=放行类（白名单/权限），DENY=拒绝类（黑名单） */
    private enum class GateKind { ALLOW, DENY }

    /**
     * 对布尔门禁方法按名单模式替换返回值。
     * 先按精确签名匹配；失败则退化为仅按方法名匹配（兼容版本间签名变化）。
     *
     * 判定统一公式（[IslandSettings.allowsIsland]）：
     *  ALLOW 门禁 -> result = allowsIsland(pkg)；
     *  DENY  门禁 -> result = !allowsIsland(pkg)。
     * 黑白名单均为空时 allowsIsland 恒真，即「全部放行」行为。
     *
     * 热更新：bypass 关闭时门禁全部放行（恢复原厂行为），仅多一次布尔检查；
     * 名单变更经 CONFIG_RELOAD 刷新 [islandSettings] 快照后即时生效
     * （黑白名单独立叠加：黑名单优先拦截，白名单留空不限制）。
     */
    private fun hookGate(
        clazz: Class<*>,
        methodName: String,
        paramTypes: List<String>,
        kind: GateKind,
        pkgIndex: Int
    ) {
        // 精确签名优先（参数类型传字符串，由 CoreTool 按目标类加载器解析）
        val exact = runCatching {
            val method = clazz.findMethodIfExists(methodName, *paramTypes.toTypedArray())
                ?: throw NoSuchMethodError("$methodName(${paramTypes.joinToString()})")
            method.hook(gateHook(methodName, kind, pkgIndex))
        }
        if (exact.isSuccess) {
            IslandLog.d("gate $methodName -> $kind (exact)")
            return
        }
        // 退化：仅按方法名匹配（hook 全部同名重载，容忍签名漂移）
        runCatching {
            val methods = clazz.findAllMethod(methodName)
            if (methods.isEmpty()) throw NoSuchMethodError(methodName)
            methods.forEach { it.hook(gateHook(methodName, kind, pkgIndex)) }
        }.onSuccess {
            IslandLog.d("gate $methodName -> $kind (name-only fallback)")
        }.onFailure {
            hookResults += HookResult("NSM.$methodName", false, it.message ?: "hook failed")
            IslandLog.w("gate $methodName hook failed: ${it.message}")
        }
    }

    /**
     * 门禁钩子体（bypass 关闭时整体放行；开启时按名单模式判定）。
     *
     * 判定日志一律 D 级（verbose-only，1.0.20 起）：诊断「名单动态不生效」时，
     * 开启详细日志后按以下方法判读：
     *  - 改名单后此处仍按旧名单判定 -> 热更新链路断裂
     *    （远程偏好推送 / CONFIG_RELOAD 广播 / reloadSettings 任一环节）；
     *  - 已按新名单判定但超级岛仍放行 -> 该门禁不在关键路径，需换拦截点。
     */
    private fun gateHook(methodName: String, kind: GateKind, pkgIndex: Int) = object : AbsHook() {
        override fun before() {
            if (!islandSettings.bypassWhitelist) return
            val pkg = runCatching { getArg(pkgIndex) as? String }.getOrNull() ?: ""
            val allow = islandSettings.allowsIsland(pkg)
            val result = if (kind == GateKind.ALLOW) allow else !allow
            setResult(result)
            IslandLog.d("gate $methodName($pkg): allow=$allow, result=$result")
        }
    }

    /** 从合成 Lambda 实例反射其捕获的 ApplicationInfo（不依赖字段名 f$1） */
    private fun reflectAppInfo(lambda: Any?): ApplicationInfo? {
        lambda ?: return null
        return runCatching {
            lambda.javaClass.declaredFields
                .firstOrNull { ApplicationInfo::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
                ?.get(lambda) as? ApplicationInfo
        }.getOrNull()
    }

    // ==================== 诊断（verbose-only，D 级） ====================

    /** loadPlugin 一次性 dump：实例字段结构 + 候选 loader + dex 路径 + 观察存活计数 */
    private fun dumpPluginLoadInfo(instance: Any?) {
        IslandLog.d(
            "plugin load dump: instance=${instance?.javaClass?.name}, " +
                "watchFired=${watchFireCount.get()}, pendingAppInfo=${pendingAppInfo?.sourceDir}, " +
                "recordedLoaders=${recordedLoaders.size}"
        )
        describeInstance(instance).take(40).forEach { line ->
            IslandLog.d("  field $line")
        }
        collectPluginLoaders(instance).forEachIndexed { i, loader ->
            IslandLog.d("  loader#$i ${loader.javaClass.name} dex=${dexPathsOf(loader)} chain=${loaderChainOf(loader)}")
        }
    }

    /** 枚举实例全部字段（含父类），输出「字段: 声明类型 = 值运行时类」（只读类型，不泄露值） */
    private fun describeInstance(obj: Any?): List<String> {
        obj ?: return emptyList()
        val lines = mutableListOf<String>()
        runCatching {
            var c: Class<*>? = obj.javaClass
            while (c != null && c.name != "java.lang.Object" && lines.size < 40) {
                c.declaredFields.forEach { f ->
                    if (lines.size >= 40) return@forEach
                    val value = runCatching {
                        f.isAccessible = true
                        f.get(obj)
                    }.getOrNull()
                    lines += "${c.simpleName}.${f.name}: ${f.type.simpleName} = ${value?.javaClass?.name ?: "null"}"
                }
                c = c.superclass
            }
        }
        return lines
    }

    /** 反射 BaseDexClassLoader 的 dex 路径列表（诊断用；失败返回空） */
    private fun dexPathsOf(loader: ClassLoader): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val pathListField = findFieldInHierarchy(loader.javaClass, "pathList") ?: return@runCatching
            val pathList = pathListField.get(loader) ?: return@runCatching
            val elementsField = findFieldInHierarchy(pathList.javaClass, "dexElements") ?: return@runCatching
            val elements = elementsField.get(pathList) as? Array<*> ?: return@runCatching
            elements.forEach { element ->
                element ?: return@forEach
                val path = runCatching {
                    val pf = findFieldInHierarchy(element.javaClass, "path") ?: return@runCatching null
                    pf.get(element) as? String
                }.getOrNull()
                out += path ?: element.toString()
            }
        }
        return out
    }

    private fun findFieldInHierarchy(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = clazz
        while (c != null && c.name != "java.lang.Object") {
            val f = runCatching { c!!.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (f != null) return f
            c = c.superclass
        }
        return null
    }

    /** loader 父子链描述（诊断：确认插件 loader 的父链形态） */
    private fun loaderChainOf(loader: ClassLoader): String {
        val sb = StringBuilder(loader.javaClass.simpleName)
        var p = loader.parent
        var depth = 0
        while (p != null && depth < LOADER_CHAIN_DEPTH) {
            sb.append(" <- ").append(p.javaClass.simpleName)
            p = p.parent
            depth++
        }
        return sb.toString()
    }

    /**
     * 宿主上下文获取：hostContext 优先（Application.attach 后注入）；
     * onPackageReady/loadPlugin 早于注入时经 ActivityThread 反射兜底
     * （1.0.16 兜底诊断因直接判空而从未运行）。
     */
    private fun currentContext(): Context? {
        hostContext?.let { return it }
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
    }

    /**
     * 兜底诊断调度（幂等，1.0.17）：
     * 门禁未安装时延迟 [FALLBACK_DIAG_MS] 打印插件版本号、观察链计数、
     * loader 父子链，并枚举插件 APK 类名。onPackageReady 与 attachHostContext
     * 双入口均可调用（CAS 去重，仅首次生效）。
     */
    private fun scheduleFallbackDiag() {
        if (!fallbackDiagDone.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (nsmHooksInstalled.get()) return@postDelayed
            val ctx = currentContext()
            val version = runCatching {
                ctx?.packageManager?.getPackageInfo(IslandConfig.PKG_ISLAND_PLUGIN, 0)?.versionName
            }.getOrNull()
            IslandLog.d(
                "diagnostics: gates not installed, watchFired=${watchFireCount.get()}, " +
                    "nsmNameSeen=${notificationSettingsSeen.get()}, pluginVersion=$version, " +
                    "pluginApk=${pendingAppInfo?.sourceDir}, recordedLoaders=${recordedLoaders.size}"
            )
            recordedLoaders.forEachIndexed { i, l ->
                IslandLog.d("  recorded loader#$i chain=${loaderChainOf(l)} dex=${dexPathsOf(l)}")
            }
            dumpPluginApkClasses(ctx)
        }, FALLBACK_DIAG_MS)
    }

    /**
     * 枚举插件 APK 类名（Notification/Island/Focus 等关键字），定位真实门禁类名。
     * Context 可空：APK 路径取自 pendingAppInfo 与已记录 loader 的 dex 路径，
     * PackageManager 查询仅为补充（Context 为空时跳过）。
     */
    private fun dumpPluginApkClasses(context: Context?) {
        if (!apkDumpDone.compareAndSet(false, true)) return
        val apks = linkedSetOf<String>()
        pendingAppInfo?.let { ai -> apks += (ai.publicSourceDir ?: ai.sourceDir ?: "") }
        context?.let { ctx ->
            runCatching {
                val ai = ctx.packageManager.getApplicationInfo(IslandConfig.PKG_ISLAND_PLUGIN, 0)
                apks += (ai.publicSourceDir ?: ai.sourceDir ?: "")
            }.onFailure {
                IslandLog.d("diagnostics: plugin package ${IslandConfig.PKG_ISLAND_PLUGIN} unresolvable: ${it.message}")
            }
        }
        recordedLoaders.forEach { apks += dexPathsOf(it) }
        val paths = apks.filter { it.isNotBlank() && it.endsWith(".apk") }
        if (paths.isEmpty()) {
            IslandLog.d(
                "apk dump skipped: no candidate apk path " +
                    "(pendingAppInfo=${pendingAppInfo != null}, recordedLoaders=${recordedLoaders.size})"
            )
            return
        }
        paths.take(APK_DUMP_LIMIT).forEach { path ->
            val size = runCatching { File(path).length() }.getOrDefault(0L)
            if (size > APK_SIZE_SKIP_BYTES) {
                IslandLog.d("apk dump skipped (too large): $path")
                return@forEach
            }
            runCatching {
                val dex = dalvik.system.DexFile(path)
                try {
                    val entries = dex.entries()
                    var total = 0
                    var shown = 0
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement() ?: continue
                        total++
                        if (shown < APK_CLASS_LOG_LIMIT && APK_KEYWORDS.any { name.contains(it, true) }) {
                            IslandLog.d("apk class: $name")
                            shown++
                        }
                    }
                    IslandLog.d("apk dump done: $path total=$total matched=$shown")
                } finally {
                    runCatching { dex.close() }
                }
            }.onFailure { IslandLog.d("apk dump failed [$path]: ${it.message}") }
        }
    }

    /**
     * 收集插件 APK 候选类名（关键字过滤，有界），供 [discoverNsmClass] 探测。
     * APK 来源同 [dumpPluginApkClasses]：pendingAppInfo + 已记录 loader 的 dex 路径。
     */
    private fun collectPluginApkClassNames(): List<String> {
        val apks = linkedSetOf<String>()
        pendingAppInfo?.let { ai -> apks += (ai.publicSourceDir ?: ai.sourceDir ?: "") }
        recordedLoaders.forEach { apks += dexPathsOf(it) }
        val names = linkedSetOf<String>()
        apks.filter { it.isNotBlank() && it.endsWith(".apk") }.take(APK_DUMP_LIMIT).forEach { path ->
            val size = runCatching { File(path).length() }.getOrDefault(0L)
            if (size > APK_SIZE_SKIP_BYTES) return@forEach
            runCatching {
                val dex = dalvik.system.DexFile(path)
                try {
                    val entries = dex.entries()
                    while (names.size < APK_CLASS_LOG_LIMIT && entries.hasMoreElements()) {
                        val name = entries.nextElement() ?: continue
                        if (APK_KEYWORDS.any { name.contains(it, true) }) names += name
                    }
                } finally {
                    runCatching { dex.close() }
                }
            }
        }
        return names.toList()
    }

    /**
     * 注册广播通道：
     *  - 收到 QUERY 时以当前状态回报 STATUS；
     *  - 装载完成主动上报一次 STATUS（心跳）：宿主重启重新装载后模块侧即可即时感知，
     *    无需等待 QUERY-STATUS 一轮往返（应对应用退后台/进程冻结导致的探测无响应）；
     *  - 收到 CONFIG_RELOAD 时热更新配置快照（1.0.23：远程偏好降级为兜底，
     *    名单/开关以 LIST_PUSH 为权威，见 [reloadSettings]）；
     *  - 收到 LIST_PUSH 时以模块直推的生效配置覆盖内存快照（1.0.22 名单；
     *    1.0.23 扩展 sdkMapping/bypass 开关，见 [applyListPush]）；
     *  - 收到 LIST_VERIFY 时回报当前名单指纹，供模块比对决定是否补推（1.0.22）；
     *  - 收到 RESTART 时重启自身进程（系统界面为常驻进程，kill 后由 AMS 自动拉起）。
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
                        // bypass 热关闭后不再回报状态（模块侧同步跳过探测）
                        if (islandSettings.bypassWhitelist) reportStatus()

                    ChannelKeys.RESTART -> {
                        IslandLog.d("restart requested by module, killing systemui process")
                        Handler(Looper.getMainLooper()).post { Process.killProcess(Process.myPid()) }
                    }

                    ChannelKeys.CONFIG_RELOAD -> {
                        IslandLog.d("config reload broadcast received, reloading settings")
                        reloadSettings()
                    }

                    ChannelKeys.LIST_PUSH -> applyListPush(value)

                    ChannelKeys.LIST_VERIFY -> {
                        // 回报内存名单指纹：模块比对不一致会补推 LIST_PUSH
                        val fp = ListSync.fingerprintOf(islandSettings)
                        IslandChannel.send(context, modulePkg, ChannelKeys.LIST_VERIFY_REPLY, fp, from = hostPkg)
                        IslandLog.d("list verify replied (fp=$fp)")
                    }
                }
            }
            // 心跳：装载完成即上报，模块侧常驻监听据此刷新状态
            reportStatus()
            IslandLog.d("channel registered (query/restart/heartbeat/config/list-sync)")
        }.onFailure { IslandLog.w("channel register failed: ${it.message}") }
    }

    /**
     * 应用模块直推的生效配置（1.0.22 名单；1.0.23 扩展为全部功能字段）。
     *
     * LIST_PUSH 是宿主侧**权威数据源**（1.0.23）：载荷含名单判定字段 +
     * sdkMapping / bypass 开关，推送后整体覆盖内存快照（verbose/debug 等
     * 纯日志字段保留，见 [ListSync.applyTo]）。
     *
     * 日志策略：字段实际变化属「状态改变」打 W 级常驻可见；无变化打 D 级
     * （重复推送/自愈校验命中时的正常收敛）。
     */
    private fun applyListPush(raw: String) {
        val payload = ListSync.decode(raw) ?: run {
            IslandLog.w("list push decode failed: ${raw.take(80)}")
            return
        }
        val old = islandSettings
        val merged = ListSync.applyTo(old, payload)
        islandSettings = merged
        val listsChanged =
            old.whitelist != merged.whitelist || old.blacklist != merged.blacklist ||
                old.whitelistEnabled != merged.whitelistEnabled || old.blacklistEnabled != merged.blacklistEnabled
        val flagsChanged =
            old.sdkMapping != merged.sdkMapping || old.bypassWhitelist != merged.bypassWhitelist
        if (listsChanged || flagsChanged) {
            IslandLog.w(
                "list push applied: white=${old.whitelist.size}->${merged.whitelist.size} " +
                    "[${merged.whitelist.joinToString(",")}], " +
                    "black=${old.blacklist.size}->${merged.blacklist.size} " +
                    "[${merged.blacklist.joinToString(",")}], " +
                    "bypass=${old.bypassWhitelist}->${merged.bypassWhitelist}, " +
                    "sdkMapping=${old.sdkMapping}->${merged.sdkMapping} (module push)"
            )
            // bypass 关→开（1.0.23：开关改由 LIST_PUSH 权威推送）：观察链早已武装，
            // 此处直接尝试已记录 loader 补装门禁，无需重启系统界面
            if (flagsChanged && merged.bypassWhitelist && !old.bypassWhitelist) {
                installHooks()
                tryRecordedLoaders()
            }
        } else {
            IslandLog.d("list push applied (no change, fp=${ListSync.fingerprint(payload)})")
        }
    }

    /**
     * 热更新配置（1.0.23 起：远程偏好降级为兜底，LIST_PUSH 为权威数据源）。
     *
     * 根因（1.0.22 日志实证）：远程偏好镜像链路在该设备未生效（service 未绑 /
     * commit 失败），此前整本替换会把 LIST_PUSH 灌入的值冲回陈旧/空远程值——
     * systemui 九次 push 均为 white=0->N（推完即被重置）、开关「必须进名单
     * 编辑页才刷新、关了又恢复」均源于此。
     *
     * 合并策略：
     *  - 功能开关（bypass / sdkMapping）与名单开关（we/be）：**保留内存值**。
     *    所有写路径（开关切换 / 名单变更）均已 LIST_PUSH 直推（发送带
     *    FLAG_RECEIVER_FOREGROUND 抗 Greezer + 3s 超时兜底），内存即权威；
     *    远程陈旧值不再参与替换（bypass 的补装改由 [applyListPush] 负责）。
     *  - 名单：远程非空时允许覆盖（模块 App 从未推送的场景下远程可能是唯一
     *    新值）；远程为空而内存非空 = 镜像断特征，保留内存防冲空。
     *  - verbose/debug：纯日志字段不影响判定，远程为准。
     */
    private fun reloadSettings(retry: Boolean = false) {
        val old = islandSettings
        val remote = IslandConfig.read(prefs(IslandConfig.PREFS_NAME))
        val remoteListsUsable = remote.whitelist.isNotEmpty() || remote.blacklist.isNotEmpty()
        val merged = old.copy(
            whitelist = if (remoteListsUsable) remote.whitelist else old.whitelist,
            blacklist = if (remoteListsUsable) remote.blacklist else old.blacklist,
            verboseLog = remote.verboseLog,
            debugMode = remote.debugMode
        )
        islandSettings = merged
        IslandLog.verbose = merged.verboseLog
        val listsChanged =
            old.whitelist != merged.whitelist || old.blacklist != merged.blacklist
        IslandLog.d(
            "config reloaded${if (listsChanged) " (lists changed)" else ""}: " +
                "bypass=${merged.bypassWhitelist} (memory), " +
                "white=${old.whitelist.size}->${merged.whitelist.size} " +
                "[${merged.whitelist.joinToString(",")}], " +
                "black=${old.blacklist.size}->${merged.blacklist.size} " +
                "[${merged.blacklist.joinToString(",")}]"
        )
        if (!retry) {
            hostContext?.let { ctx ->
                Handler(Looper.getMainLooper()).postDelayed(
                    { reloadSettings(retry = true) }, CONFIG_RELOAD_RETRY_MS
                )
            }
        }
    }

    /** 以当前注入状态回报 STATUS */
    private fun reportStatus() {
        val context = hostContext ?: return
        val status = HostStatus(
            process = IslandConfig.PKG_SYSTEMUI,
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

        /** 兜底诊断延迟（门禁未安装时枚举插件 APK 类名） */
        const val FALLBACK_DIAG_MS = 8000L

        /** loadPlugin 诊断 dump 次数上限 */
        const val PLUGIN_DUMP_LIMIT = 3

        /** loader 记录容量上限（防异常 ROM 的海量 loader） */
        const val LOADER_RECORD_CAP = 128

        /** APK 枚举个数上限 */
        const val APK_DUMP_LIMIT = 3

        /** 单个 APK 打印的匹配类名上限 */
        const val APK_CLASS_LOG_LIMIT = 80

        /** 超过此大小的 APK 跳过枚举（防系统界面自身巨型 APK 的 IO 抖动） */
        const val APK_SIZE_SKIP_BYTES = 400L * 1024 * 1024

        /** APK 类名过滤关键字（定位门禁类漂移） */
        val APK_KEYWORDS = listOf("Notification", "Island", "Focus", "BlackList", "WhiteList", "AllowList")

        /** 六道门禁方法名（自动发现判定：候选类至少声明其中 N 个才认定为 NSM） */
        val GATE_METHOD_NAMES = listOf(
            "canCustomFocus",
            "canShowFocus",
            "canPassXMSPermission",
            "isDynamicIslandLiveUpdateBlackList",
            "isDynamicIslandMiniWindowBlackList",
            "isFocusNotificationDeletedBlackList"
        )

        /** 自动发现认定的最少门禁方法命中数（防误判） */
        const val DISCOVERY_MIN_GATE_HITS = 2

        /** loader 父子链打印深度上限 */
        const val LOADER_CHAIN_DEPTH = 8
    }
}
