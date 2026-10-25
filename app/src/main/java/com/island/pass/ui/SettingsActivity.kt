package com.island.pass.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.island.pass.R
import com.island.pass.data.ChannelKeys
import com.island.pass.data.DeviceCompat
import com.island.pass.data.HostStatus
import com.island.pass.data.IslandChannel
import com.island.pass.data.IslandConfig
import com.island.pass.data.IslandFramework
import com.island.pass.data.ListSyncCoordinator
import com.island.pass.data.ListType
import com.island.pass.data.RootUtil
import com.island.pass.hook.IslandLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 底部导航页 */
private enum class NavPage { HOME, CONFIG, SETTINGS }

/** 主题模式常量（与 IslandConfig.KEY_THEME_MODE 对应） */
private object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

/**
 * HyperIsleOpen 设置界面（KSU 管理器版式：三页底部导航）。
 *
 *  - 首页：总体状态 + 工作状态 + 作用域勾选 + 设备兼容；
 *  - 配置：功能开关 + 上岛黑白名单 + 重启/保存；
 *  - 设置：主题 + 详细日志 + 调试模式 + 关于。
 *
 * 自检架构（一箭双雕的统一探针）：
 *  - 模块经数据通讯桥向目标宿主广播 QUERY，宿主回调回报 STATUS（注入钩子明细 + 错误列表）；
 *  - 有响应 ⇒ 模块已激活、作用域已勾选、宿主已装载 Hook；
 *  - 无响应（超时）⇒ 模块未激活 / 作用域未勾选 / 宿主进程未启动，三者在此探针下不可区分，
 *    故「工作状态自检」与「作用域勾选检查」共用同一探测结果，UI 上分别呈现两个视角。
 */
class SettingsActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    /** 安装状态（每次 ON_START 刷新） */
    private var noticeflowStatus by mutableStateOf<String?>(null)
    private var pluginStatus by mutableStateOf<String?>(null)

    /** 探测状态 */
    private var checking by mutableStateOf(false)
    private var checkedOnce by mutableStateOf(false)
    private var sysuiProbe by mutableStateOf(ProbeState.CHECKING)
    private var noticeflowProbe by mutableStateOf(ProbeState.CHECKING)
    private var sysuiHostStatus by mutableStateOf<HostStatus?>(null)
    private var noticeflowHostStatus by mutableStateOf<HostStatus?>(null)

    /** 多选重启对话框 */
    private var showRestartDialog by mutableStateOf(false)

    /** Root 授权状态（重启作用域应用需要；模块整体不强制 Root） */
    private var rootState by mutableStateOf(RootUtil.State.CHECKING)

    /** 尚未收敛的探针计数，归零时结束整体「检查中」态 */
    private var pendingProbes = 0

    // ===== 配置状态（持久化于偏好桥） =====
    private var bypassWhitelist by mutableStateOf(true)
    private var sdkMapping by mutableStateOf(true)

    /** 生效白名单/黑名单规模（配置页摘要；名单页勾选即时热更新，ON_START 重读） */
    private var whitelistCount by mutableStateOf(0)
    private var blacklistCount by mutableStateOf(0)

    /** 白/黑名单启用开关（停用的名单不参与判定；两个都关 = 名单功能整体不生效） */
    private var whitelistEnabled by mutableStateOf(true)
    private var blacklistEnabled by mutableStateOf(true)
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)

    /** Monet 动态取色（跟随壁纸，需 Android 12+，默认关） */
    private var monetColors by mutableStateOf(false)

    /** 悬浮底栏（Apple 风格圆角悬浮导航栏，默认关） */
    private var floatingBar by mutableStateOf(false)

    /** 底部导航仅显示图标（默认关，即图标+文字） */
    private var navIconOnly by mutableStateOf(false)

    /** 界面缩放（0.85~1.15，默认 1.0） */
    private var uiScale by mutableStateOf(1.0f)

    /** 页面切换动画（默认开） */
    private var pageAnim by mutableStateOf(true)
    private var verboseLog by mutableStateOf(false)
    private var debugMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        loadPrefs()
        refreshInstallStatuses()
        registerStatusListeners()
        checkRoot()
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                refreshInstallStatuses()
                // 全量重读：从主题二级页/名单页返回时，外观六键与名单需即时反映最新偏好
                loadPrefs()
                // 1.0.22：UI 前台自愈——模块存活期间的名单同步 gap（如 Greezer 拦截
                // CONFIG_RELOAD）经一次指纹校验回收，不一致即补推
                ListSyncCoordinator.initiateVerify(this)
                if (!checkedOnce) runCheck(warmLaunch = false)
            }
        })
        setContent {
            val dark = themeMode == ThemeMode.DARK ||
                (themeMode == ThemeMode.SYSTEM && isSystemInDarkTheme())

            // 系统栏图标明暗随主题
            val view = LocalView.current
            DisposableEffect(dark) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
                onDispose { }
            }

            // Monet 动态取色：开关开启且系统支持（Android 12+）时映射到库内 Monet 模式，
            // 取色由 Miuix ThemeController 经平台动态色自行处理
            val monetActive = monetColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val themeController = remember(themeMode, monetActive) {
                ThemeController(
                    colorSchemeMode = when (themeMode) {
                        ThemeMode.LIGHT ->
                            if (monetActive) ColorSchemeMode.MonetLight else ColorSchemeMode.Light

                        ThemeMode.DARK ->
                            if (monetActive) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark

                        else ->
                            if (monetActive) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
                    }
                )
            }

            MiuixTheme(controller = themeController) {
                var page by rememberSaveable { mutableStateOf(NavPage.HOME) }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = stringResource(
                                when (page) {
                                    NavPage.HOME -> R.string.app_name
                                    NavPage.CONFIG -> R.string.nav_config
                                    NavPage.SETTINGS -> R.string.nav_settings
                                }
                            )
                        )
                    },
                    bottomBar = {
                        if (floatingBar) {
                            // 悬浮底栏（Apple 风格圆角悬浮导航栏，仅图标）
                            FloatingNavigationBar {
                                NavPage.entries.forEach { p ->
                                    FloatingNavigationBarItem(
                                        selected = page == p,
                                        onClick = { page = p },
                                        icon = navIconOf(p),
                                        label = navLabelOf(p)
                                    )
                                }
                            }
                        } else {
                            NavigationBar(
                                mode = if (navIconOnly) NavigationBarDisplayMode.IconOnly
                                else NavigationBarDisplayMode.IconAndText
                            ) {
                                NavPage.entries.forEach { p ->
                                    NavigationBarItem(
                                        modifier = Modifier.weight(1f),
                                        selected = page == p,
                                        onClick = { page = p },
                                        icon = navIconOf(p),
                                        label = navLabelOf(p)
                                    )
                                }
                            }
                        }
                    }
                ) { padding ->
                    // 界面缩放：覆盖 LocalDensity（fontScale 同步缩放，文字不裁切）
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(
                            density = density.density * uiScale,
                            fontScale = density.fontScale * uiScale
                        )
                    ) {
                        Crossfade(
                            targetState = page,
                            modifier = Modifier.padding(padding),
                            animationSpec = if (pageAnim) tween() else snap(),
                            label = "nav-page"
                        ) { p ->
                        when (p) {
                            NavPage.HOME -> HomeScreen(
                                modifier = Modifier.fillMaxSize(),
                                checking = checking,
                                sysuiProbe = sysuiProbe,
                                noticeflowProbe = noticeflowProbe,
                                sysuiHostStatus = sysuiHostStatus,
                                noticeflowHostStatus = noticeflowHostStatus,
                                pluginStatus = pluginStatus,
                                noticeflowVersion = noticeflowStatus,
                                debugMode = debugMode,
                                onRecheck = { runCheck(warmLaunch = true) },
                                onOpenNoticeflow = { warmLaunch(IslandConfig.PKG_NOTICEFLOW) }
                            )

                            NavPage.CONFIG -> ConfigScreen(
                                modifier = Modifier.fillMaxSize(),
                                noticeflowInstalled = noticeflowStatus != null,
                                noticeflowVersion = noticeflowStatus,
                                pluginVersion = pluginStatus,
                                bypassWhitelist = bypassWhitelist,
                                sdkMapping = sdkMapping,
                                whitelistEnabled = whitelistEnabled,
                                whitelistCount = whitelistCount,
                                blacklistEnabled = blacklistEnabled,
                                blacklistCount = blacklistCount,
                                restartEnabled = restartEnabled(),
                                rootState = rootState,
                                showRestartDialog = showRestartDialog,
                                onBypassChange = { bypassWhitelist = it; putBooleanPref(IslandConfig.KEY_BYPASS_WHITELIST, it) },
                                onSdkChange = { sdkMapping = it; putBooleanPref(IslandConfig.KEY_SDK_MAPPING, it) },
                                onWhitelistEnabledChange = { enabled ->
                                    whitelistEnabled = enabled
                                    putBooleanPref(IslandConfig.KEY_WHITELIST_ENABLED, enabled)
                                },
                                onBlacklistEnabledChange = { enabled ->
                                    blacklistEnabled = enabled
                                    putBooleanPref(IslandConfig.KEY_BLACKLIST_ENABLED, enabled)
                                },
                                onManageWhitelist = { openListManage(ListType.WHITELIST) },
                                onManageBlacklist = { openListManage(ListType.BLACKLIST) },
                                onRestartClick = { showRestartDialog = true },
                                onRestartDismiss = { showRestartDialog = false },
                                onRestartRetryRoot = { checkRoot() },
                                onRestartConfirm = { targets -> restartTargets(targets) }
                            )

                            NavPage.SETTINGS -> SettingsPageScreen(
                                modifier = Modifier.fillMaxSize(),
                                verboseLog = verboseLog,
                                debugMode = debugMode,
                                onOpenThemeSettings = {
                                    startActivity(Intent(this@SettingsActivity, ThemeSettingsActivity::class.java))
                                },
                                onVerboseChange = { verboseLog = it; saveTogglePrefs() },
                                onDebugChange = { debugMode = it; saveTogglePrefs() }
                            )
                        }
                    }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ===== 配置读写 =====

    private fun loadPrefs() {
        runCatching {
            val prefs = IslandFramework.prefs(this, IslandConfig.PREFS_NAME)
            bypassWhitelist = prefs.getBoolean(IslandConfig.KEY_BYPASS_WHITELIST, true)
            sdkMapping = prefs.getBoolean(IslandConfig.KEY_SDK_MAPPING, true)
            themeMode = prefs.getInt(IslandConfig.KEY_THEME_MODE, ThemeMode.SYSTEM)
            monetColors = prefs.getBoolean(IslandConfig.KEY_MONET, false)
            floatingBar = prefs.getBoolean(IslandConfig.KEY_FLOATING_BAR, false)
            navIconOnly = prefs.getBoolean(IslandConfig.KEY_NAV_ICON_ONLY, false)
            uiScale = prefs.getFloat(IslandConfig.KEY_UI_SCALE, 1.0f).coerceIn(0.85f, 1.15f)
            pageAnim = prefs.getBoolean(IslandConfig.KEY_PAGE_ANIM, true)
            verboseLog = prefs.getBoolean(IslandConfig.KEY_VERBOSE_LOG, false)
            debugMode = prefs.getBoolean(IslandConfig.KEY_DEBUG_MODE, false)
            whitelistCount = IslandConfig.decodeList(
                prefs.getString(IslandConfig.KEY_WHITELIST_PKGS, "")
            ).size
            blacklistCount = IslandConfig.decodeList(
                prefs.getString(IslandConfig.KEY_BLACKLIST_PKGS, "")
            ).size
            whitelistEnabled = prefs.getBoolean(IslandConfig.KEY_WHITELIST_ENABLED, true)
            blacklistEnabled = prefs.getBoolean(IslandConfig.KEY_BLACKLIST_ENABLED, true)
        }.onFailure { IslandLog.e("load prefs failed: ${it.message}") }
    }

    /** 单键即时写入（开关切换即存，无需点保存按钮） */
    private fun putBooleanPref(key: String, value: Boolean) {
        runCatching {
            IslandFramework.prefs(this, IslandConfig.PREFS_NAME)
                .edit()
                .putBoolean(key, value)
                .apply()
            // 本地为唯一事实源，写后补镜远程（hooker 侧数据源）
            IslandFramework.mirrorToRemote(this, IslandConfig.PREFS_NAME)
        }.onFailure { IslandLog.e("putBooleanPref($key) failed: ${it.message}") }
        // verbose/debug 开关同样影响 hooker 侧日志级别，一并广播热更新
        notifyConfigChanged()
        // 1.0.23：开关状态经 LIST_PUSH 权威直推两个宿主（载荷含全部功能字段）——
        // 远程偏好镜像在本机已实证不可靠（service 未绑/commit 失败无可见日志），
        // 依赖镜像 + CONFIG_RELOAD 会导致「改了不生效、关了又恢复」
        ListSyncCoordinator.pushAllHosts(this)
    }

    /** 单键浮点写入（界面缩放） */
    private fun putFloatPref(key: String, value: Float) {
        runCatching {
            IslandFramework.prefs(this, IslandConfig.PREFS_NAME)
                .edit()
                .putFloat(key, value)
                .apply()
            IslandFramework.mirrorToRemote(this, IslandConfig.PREFS_NAME)
        }.onFailure { IslandLog.e("putFloatPref($key) failed: ${it.message}") }
    }

    private fun saveTogglePrefs() {
        runCatching {
            IslandFramework.prefs(this, IslandConfig.PREFS_NAME)
                .edit()
                .putBoolean(IslandConfig.KEY_VERBOSE_LOG, verboseLog)
                .putBoolean(IslandConfig.KEY_DEBUG_MODE, debugMode)
                .apply()
            IslandFramework.mirrorToRemote(this, IslandConfig.PREFS_NAME)
        }.onFailure { IslandLog.e("save toggle prefs failed: ${it.message}") }
        notifyConfigChanged()
        // 1.0.23：与开关切换同路径，LIST_PUSH 权威直推（verbose/debug 虽为纯
        // 日志字段不参与判定，统一推送保持宿主快照完整）
        ListSyncCoordinator.pushAllHosts(this)
    }

    /**
     * 通知两个宿主配置已变更（热更新闭环）。
     *
     * 远程偏好由框架异步推送，宿主侧另有远程偏好变更监听 + 400ms 延迟补读兜底；
     * 此广播负责即时触发 reload（含功能开关关→开时的补装路径）。
     */
    private fun notifyConfigChanged() {
        IslandChannel.send(applicationContext, IslandConfig.PKG_SYSTEMUI, ChannelKeys.CONFIG_RELOAD)
        IslandChannel.send(applicationContext, IslandConfig.PKG_NOTICEFLOW, ChannelKeys.CONFIG_RELOAD)
    }

    /** 刷新安装状态：实况通知版本号 / 系统界面超级岛插件组件版本号 */
    private fun refreshInstallStatuses() {
        noticeflowStatus = try {
            packageManager.getPackageInfo(IslandConfig.PKG_NOTICEFLOW, 0).versionName ?: "未知版本"
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        pluginStatus = try {
            packageManager.getPackageInfo(IslandConfig.PKG_ISLAND_PLUGIN, 0).versionName ?: "未知版本"
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (noticeflowStatus == null && noticeflowProbe != ProbeState.CHECKING) {
            noticeflowProbe = ProbeState.NOT_INSTALLED
        }
    }

    // ===== 探测 =====

    /**
     * 常驻监听宿主主动上报的 STATUS（心跳）。
     *
     * Hooker 装载完成、或宿主被重启后重新装载时，会主动向模块发送 STATUS；
     * 模块据此即时刷新状态，无需等待 QUERY-STATUS 一轮往返。
     *
     * 功能开关已关闭时忽略心跳：宿主侧同步放行不再回报，模块侧显示 DISABLED。
     */
    private fun registerStatusListeners() {
        listenStatus(IslandConfig.PKG_SYSTEMUI) { status ->
            if (!bypassWhitelist) return@listenStatus
            sysuiHostStatus = status
            sysuiProbe = ProbeState.OK
            if (checking) settleProbe()
        }
        listenStatus(IslandConfig.PKG_NOTICEFLOW) { status ->
            if (!sdkMapping) return@listenStatus
            noticeflowHostStatus = status
            noticeflowProbe = ProbeState.OK
            if (checking) settleProbe()
        }
    }

    private fun listenStatus(pkg: String, onStatus: (HostStatus?) -> Unit) {
        IslandChannel.listenStatus(pkg) { json ->
            val status = HostStatus.fromJson(json)
            handler.post { onStatus(status) }
        }
    }

    /**
     * 执行一轮探测。
     *
     * @param warmLaunch 实况通知首轮无响应时是否先拉起其进程再探一轮
     *                   （自动首检不拉起，避免打断用户；手动「重新检查」拉起）。
     *
     * 探测只跑一遍：ON_START 不重复触发，重启后不自动重检，仅手动「重新检查」重跑。
     *
     * 功能开关关闭（bypass / sdkMapping）时对应宿主标记为 DISABLED 并**跳过探测**，
     * 不发送 QUERY、不计入待收敛探针——关闭的功能不应产生误报。
     *
     * 实况通知自动首检（warmLaunch=false）首轮超时仅得 SUSPECT（进程存活 + 模块已加载、
     * 仅回报被延迟，如 MIUI Greezer 拦截 cached 接收者）时，静默补探一轮长窗口再收敛，
     * 不把"延迟"误报成"疑似异常"（1.0.21）。
     */
    private fun runCheck(warmLaunch: Boolean) {
        checking = true
        sysuiHostStatus = null
        noticeflowHostStatus = null

        sysuiProbe = if (bypassWhitelist) ProbeState.CHECKING else ProbeState.DISABLED
        noticeflowProbe = when {
            noticeflowStatus == null -> ProbeState.NOT_INSTALLED
            sdkMapping -> ProbeState.CHECKING
            else -> ProbeState.DISABLED
        }

        pendingProbes = 0
        if (sysuiProbe == ProbeState.CHECKING) pendingProbes++
        if (noticeflowProbe == ProbeState.CHECKING) pendingProbes++
        if (pendingProbes == 0) {
            checking = false
            checkedOnce = true
            return
        }

        // 系统界面为常驻进程，直接探测
        if (sysuiProbe == ProbeState.CHECKING) {
            queryHost(IslandConfig.PKG_SYSTEMUI, TIMEOUT_SYSUS, isPending = { sysuiProbe == ProbeState.CHECKING }) { state ->
                sysuiProbe = state
                settleProbe()
            }
        }

        if (noticeflowProbe == ProbeState.CHECKING) {
            queryHost(
                IslandConfig.PKG_NOTICEFLOW, TIMEOUT_NOTICEFLOW_FAST,
                isPending = { noticeflowProbe == ProbeState.CHECKING }
            ) { state ->
                if (warmLaunch && state != ProbeState.OK) {
                    // 首轮无响应：拉起进程后给予更长的装载窗口再探一轮
                    noticeflowProbe = ProbeState.CHECKING
                    warmLaunch(IslandConfig.PKG_NOTICEFLOW)
                    queryHost(
                        IslandConfig.PKG_NOTICEFLOW, TIMEOUT_NOTICEFLOW_WARM,
                        isPending = { noticeflowProbe == ProbeState.CHECKING }
                    ) { state2 ->
                        noticeflowProbe = state2
                        settleProbe()
                    }
                } else if (!warmLaunch && state == ProbeState.SUSPECT) {
                    // 1.0.21：自动首检首轮超时判 SUSPECT（宿主进程存活且模块已加载，仅回报被
                    // 延迟——如 MIUI Greezer 对 cached 接收者的广播拦截）时，不拉起进程，
                    // 静默补探一轮长窗口再收敛，避免把"延迟"误报成"疑似注入异常"。
                    noticeflowProbe = ProbeState.CHECKING
                    queryHost(
                        IslandConfig.PKG_NOTICEFLOW, TIMEOUT_NOTICEFLOW_WARM,
                        isPending = { noticeflowProbe == ProbeState.CHECKING }
                    ) { state2 ->
                        noticeflowProbe = state2
                        settleProbe()
                    }
                } else {
                    noticeflowProbe = state
                    settleProbe()
                }
            }
        }
    }

    /** 探针收敛计数 */
    private fun settleProbe() {
        if (pendingProbes <= 0) return
        pendingProbes--
        if (pendingProbes <= 0) {
            checking = false
            checkedOnce = true
        }
    }

    /**
     * 向指定宿主发送 QUERY；超时仍未收到响应时做分级判定。
     *
     * 使用 Application 上下文发送定向广播：通道接收器注册于 Application 上下文
     * （[com.island.pass.IslandChannel]），Activity 上下文不影响广播可达性。
     */
    private fun queryHost(
        pkg: String,
        timeoutMs: Long,
        isPending: () -> Boolean,
        onTimeout: (ProbeState) -> Unit
    ) {
        IslandChannel.send(applicationContext, pkg, ChannelKeys.QUERY)
        handler.postDelayed({
            if (!isPending()) return@postDelayed
            analyzeTimeout(pkg) { state -> onTimeout(state) }
        }, timeoutMs)
    }

    /**
     * 超时无响应时的分级判定：
     *  - 已加载且宿主进程存活 -> SUSPECT（橙色感叹号，可能被系统冻结/后台限制）；
     *  - 已加载但宿主进程未运行 -> FAILED；
     *  - 未加载（isLoadedIn 明确 false）-> FAILED（红叉）；
     *  - isLoadedIn 为 null（service 未绑定，无法判定加载态）且进程存活 -> SUSPECT
     *    （1.0.23：不再把"判定不了"误报成"未激活"——service 未绑时
     *    getRunningTargets 不可用，此时即使目标应用在前台也会误报 FAILED）。
     */
    private fun analyzeTimeout(pkg: String, onResult: (ProbeState) -> Unit) {
        lifecycleScope.launch {
            val moduleActive = IslandFramework.isLoadedIn(pkg)
            val procAlive = isProcessAlive(pkg)
            val state = when {
                moduleActive == false -> ProbeState.FAILED
                procAlive -> ProbeState.SUSPECT
                else -> ProbeState.FAILED
            }
            onResult(state)
        }
    }

    /** 目标进程是否存活（pidof，无需 Root；失败时按存活处理以避免误报） */
    private fun isProcessAlive(pkg: String): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("pidof", pkg))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.trim().isNotEmpty()
    }.getOrDefault(true)

    /** 向勾选的宿主发送 RESTART；实况通知 kill 后由模块侧重拉 */
    private fun restartTargets(targets: List<String>) {
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.toast_restart_none, Toast.LENGTH_SHORT).show()
            return
        }
        if (rootState != RootUtil.State.GRANTED) {
            Toast.makeText(this, R.string.toast_root_required, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            var rootAllOk = true
            targets.forEach { pkg ->
                // 双通道：hooker 进程内 kill（精准）+ Root am force-stop（兜底）
                IslandChannel.send(applicationContext, pkg, ChannelKeys.RESTART)
                if (!RootUtil.forceStop(pkg)) rootAllOk = false
            }
            withContext(Dispatchers.Main) {
                if (!rootAllOk) {
                    Toast.makeText(this@SettingsActivity, R.string.toast_restart_partial, Toast.LENGTH_SHORT).show()
                }
                if (targets.contains(IslandConfig.PKG_NOTICEFLOW)) {
                    handler.postDelayed({ warmLaunch(IslandConfig.PKG_NOTICEFLOW) }, 1_500L)
                }
                if (rootAllOk) {
                    Toast.makeText(this@SettingsActivity, R.string.toast_restart_sent, Toast.LENGTH_SHORT).show()
                }
                // 状态检查只跑一遍：重启后不自动重检，宿主重新装载时会经心跳主动上报
            }
        }
    }

    /** 检测 Root 授权（IO 线程，可能触发 su 授权弹窗） */
    private fun checkRoot() {
        rootState = RootUtil.State.CHECKING
        lifecycleScope.launch {
            val granted = RootUtil.check()
            rootState = if (granted) RootUtil.State.GRANTED else RootUtil.State.DENIED
        }
    }

    /** 以前台 Activity 方式拉起目标应用（绕过后台启动限制） */
    private fun warmLaunch(pkg: String) {
        runCatching {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }.onFailure { IslandLog.w("warmLaunch($pkg) failed: ${it.message}") }
    }

    /** 打开名单管理二级页（白/黑名单各自独立） */
    private fun openListManage(type: ListType) {
        runCatching {
            startActivity(
                Intent(this, ListManageActivity::class.java)
                    .putExtra(ListManageActivity.EXTRA_TYPE, type.value)
            )
        }.onFailure { IslandLog.e("openListManage failed: ${it.message}") }
    }

    /**
     * 重启门禁：需要 Root 授权（重启作用域应用强依赖），且当前不在检查中。
     * 保存配置已改为实时写入，不受探测状态限制。
     */
    private fun restartEnabled(): Boolean =
        !checking && rootState == RootUtil.State.GRANTED
}

/** 探针超时时长 */
private const val TIMEOUT_SYSUS = 2_500L
private const val TIMEOUT_NOTICEFLOW_FAST = 2_000L
private const val TIMEOUT_NOTICEFLOW_WARM = 6_500L

/** 底部导航页图标（普通/悬浮底栏共用） */
@Composable
private fun navIconOf(page: NavPage): ImageVector = when (page) {
    NavPage.HOME -> MiuixIcons.Home
    NavPage.CONFIG -> MiuixIcons.Tune
    NavPage.SETTINGS -> MiuixIcons.Settings
}

/** 底部导航页标签（悬浮底栏仅图标，标签保留供无障碍/预览） */
@Composable
private fun navLabelOf(page: NavPage): String = stringResource(
    when (page) {
        NavPage.HOME -> R.string.nav_home
        NavPage.CONFIG -> R.string.nav_config
        NavPage.SETTINGS -> R.string.nav_settings
    }
)
