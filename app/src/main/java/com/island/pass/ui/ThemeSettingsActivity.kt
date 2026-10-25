package com.island.pass.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import com.island.pass.R
import com.island.pass.data.IslandConfig
import com.island.pass.data.IslandFramework
import com.island.pass.hook.IslandLog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 主题设置二级页（KSU 风格扩展页）。
 *
 *  - 主题预览卡（当前取色方案实时预览）；
 *  - 明暗模式三选一（TabRow 露在页面底色，不置于卡片内）；
 *  - Monet 动态取色（跟随壁纸，需 Android 12+，不支持时置灰提示）；
 *  - 悬浮底栏 / 底部导航仅图标；
 *  - 界面缩放（0.85~1.15，弹窗 + Slider）；
 *  - 页面切换动画。
 *
 * 全部经 [IslandFramework] 偏好桥即时写入，返回上一页即生效。
 */
class ThemeSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThemeSettingsScreen(window = window, onBack = { finish() })
        }
    }
}

/** 主题模式常量（与 [IslandConfig.KEY_THEME_MODE] 对应） */
private object ThemePageMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

/** 界面缩放范围与步进（0.85 ~ 1.15，每 5% 一档） */
private val UI_SCALE_RANGE = 0.85f..1.15f
private const val UI_SCALE_STEPS = 5

@Composable
private fun ThemeSettingsScreen(window: android.view.Window, onBack: () -> Unit) {
    val context = LocalContext.current

    var themeMode by remember {
        mutableIntStateOf(
            runCatching {
                IslandFramework.prefs(context, IslandConfig.PREFS_NAME)
                    .getInt(IslandConfig.KEY_THEME_MODE, ThemePageMode.SYSTEM)
            }.getOrDefault(ThemePageMode.SYSTEM).coerceIn(0, 2)
        )
    }
    var monet by remember {
        mutableStateOf(
            runCatching {
                IslandFramework.prefs(context, IslandConfig.PREFS_NAME)
                    .getBoolean(IslandConfig.KEY_MONET, false)
            }.getOrDefault(false)
        )
    }
    var floatingBar by remember {
        mutableStateOf(
            runCatching {
                IslandFramework.prefs(context, IslandConfig.PREFS_NAME)
                    .getBoolean(IslandConfig.KEY_FLOATING_BAR, false)
            }.getOrDefault(false)
        )
    }
    var navIconOnly by remember {
        mutableStateOf(
            runCatching {
                IslandFramework.prefs(context, IslandConfig.PREFS_NAME)
                    .getBoolean(IslandConfig.KEY_NAV_ICON_ONLY, false)
            }.getOrDefault(false)
        )
    }
    var uiScale by remember {
        mutableFloatStateOf(
            runCatching {
                IslandFramework.prefs(context, IslandConfig.PREFS_NAME)
                    .getFloat(IslandConfig.KEY_UI_SCALE, 1f)
            }.getOrDefault(1f).coerceIn(UI_SCALE_RANGE.start, UI_SCALE_RANGE.endInclusive)
        )
    }
    var pageAnim by remember {
        mutableStateOf(
            runCatching {
                IslandFramework.prefs(context, IslandConfig.PREFS_NAME)
                    .getBoolean(IslandConfig.KEY_PAGE_ANIM, true)
            }.getOrDefault(true)
        )
    }
    var showScaleDialog by remember { mutableStateOf(false) }

    // Monet 生效判定：开关开启且系统支持动态取色（Android 12+）
    val monetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val monetActive = monet && monetSupported
    val dark = themeMode == ThemePageMode.DARK ||
        (themeMode == ThemePageMode.SYSTEM && isSystemInDarkTheme())
    val themeController = remember(themeMode, monetActive) {
        ThemeController(
            colorSchemeMode = when (themeMode) {
                ThemePageMode.LIGHT ->
                    if (monetActive) ColorSchemeMode.MonetLight else ColorSchemeMode.Light

                ThemePageMode.DARK ->
                    if (monetActive) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark

                else ->
                    if (monetActive) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
            }
        )
    }

    // 系统栏图标明暗随主题
    val view = LocalView.current
    DisposableEffect(dark) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        onDispose { }
    }

    MiuixTheme(controller = themeController) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = stringResource(R.string.theme_settings_title),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.btn_back),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                // ===== 主题预览 =====
                ThemePreviewCard()

                Spacer(Modifier.height(8.dp))

                // ===== 明暗模式（TabRow 露在页面底色） =====
                SmallTitle(text = stringResource(R.string.section_theme_mode))
                TabRow(
                    tabs = listOf(
                        stringResource(R.string.theme_system),
                        stringResource(R.string.theme_light),
                        stringResource(R.string.theme_dark)
                    ),
                    selectedTabIndex = themeMode,
                    onTabSelected = { index ->
                        themeMode = index
                        putInt(context, IslandConfig.KEY_THEME_MODE, index)
                    }
                )
                Spacer(Modifier.height(8.dp))

                // ===== 取色与版式 =====
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                    SwitchPreference(
                        checked = monet,
                        onCheckedChange = {
                            monet = it
                            putBoolean(context, IslandConfig.KEY_MONET, it)
                        },
                        title = stringResource(R.string.settings_monet),
                        summary = if (monetSupported) {
                            stringResource(R.string.settings_monet_desc)
                        } else {
                            stringResource(R.string.settings_monet_unsupported)
                        },
                        enabled = monetSupported
                    )
                    SwitchPreference(
                        checked = floatingBar,
                        onCheckedChange = {
                            floatingBar = it
                            putBoolean(context, IslandConfig.KEY_FLOATING_BAR, it)
                        },
                        title = stringResource(R.string.settings_floating_bar),
                        summary = stringResource(R.string.settings_floating_bar_desc)
                    )
                    SwitchPreference(
                        checked = navIconOnly,
                        onCheckedChange = {
                            navIconOnly = it
                            putBoolean(context, IslandConfig.KEY_NAV_ICON_ONLY, it)
                        },
                        title = stringResource(R.string.settings_nav_icon_only),
                        summary = stringResource(R.string.settings_nav_icon_only_desc)
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_ui_scale),
                        summary = stringResource(
                            R.string.settings_ui_scale_value,
                            (uiScale * 100).toInt()
                        ),
                        onClick = { showScaleDialog = true }
                    )
                    SwitchPreference(
                        checked = pageAnim,
                        onCheckedChange = {
                            pageAnim = it
                            putBoolean(context, IslandConfig.KEY_PAGE_ANIM, it)
                        },
                        title = stringResource(R.string.settings_page_anim),
                        summary = stringResource(R.string.settings_page_anim_desc)
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showScaleDialog) {
        UiScaleDialog(
            current = uiScale,
            onConfirm = { value ->
                uiScale = value
                putFloat(context, IslandConfig.KEY_UI_SCALE, value)
                showScaleDialog = false
            },
            onDismiss = { showScaleDialog = false }
        )
    }
}

/** 主题预览卡：取色方案关键角色色块 + 示例文本 */
@Composable
private fun ThemePreviewCard() {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.theme_preview_title),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Swatch(
                    color = MiuixTheme.colorScheme.primary,
                    label = stringResource(R.string.theme_swatch_primary),
                    modifier = Modifier.weight(1f)
                )
                Swatch(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                    label = stringResource(R.string.theme_swatch_surface),
                    modifier = Modifier.weight(1f)
                )
                Swatch(
                    color = MiuixTheme.colorScheme.onSurface,
                    label = stringResource(R.string.theme_swatch_on_surface),
                    modifier = Modifier.weight(1f)
                )
                Swatch(
                    color = MiuixTheme.colorScheme.error,
                    label = stringResource(R.string.theme_swatch_error),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.theme_preview_sample),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

/** 单个色块（色板 + 名称） */
@Composable
private fun Swatch(color: Color, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

/** 界面缩放对话框：Slider + 百分比实时预览 */
@Composable
private fun UiScaleDialog(
    current: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableFloatStateOf(current) }
    Dialog(onDismissRequest = onDismiss) {
        Card(cornerRadius = 24.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.settings_ui_scale),
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_ui_scale_desc),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_ui_scale_value, (value * 100).toInt()),
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = UI_SCALE_RANGE,
                    steps = UI_SCALE_STEPS,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(text = stringResource(R.string.applist_manual_cancel), onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = stringResource(R.string.applist_manual_confirm),
                        onClick = { onConfirm(value) }
                    )
                }
            }
        }
    }
}

// ===== 偏好写入（即时保存，返回即生效；本地唯一事实源 + 远程镜像） =====

private fun putBoolean(context: android.content.Context, key: String, value: Boolean) {
    runCatching {
        IslandFramework.prefs(context, IslandConfig.PREFS_NAME)
            .edit()
            .putBoolean(key, value)
            .apply()
        IslandFramework.mirrorToRemote(context, IslandConfig.PREFS_NAME)
    }.onFailure { IslandLog.e("theme putBoolean($key) failed: ${it.message}") }
}

private fun putInt(context: android.content.Context, key: String, value: Int) {
    runCatching {
        IslandFramework.prefs(context, IslandConfig.PREFS_NAME)
            .edit()
            .putInt(key, value)
            .apply()
        IslandFramework.mirrorToRemote(context, IslandConfig.PREFS_NAME)
    }.onFailure { IslandLog.e("theme putInt($key) failed: ${it.message}") }
}

private fun putFloat(context: android.content.Context, key: String, value: Float) {
    runCatching {
        IslandFramework.prefs(context, IslandConfig.PREFS_NAME)
            .edit()
            .putFloat(key, value)
            .apply()
        IslandFramework.mirrorToRemote(context, IslandConfig.PREFS_NAME)
    }.onFailure { IslandLog.e("theme putFloat($key) failed: ${it.message}") }
}
