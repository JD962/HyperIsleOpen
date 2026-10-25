package com.island.pass.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.island.pass.BuildConfig
import com.island.pass.R
import com.island.pass.data.DeviceCompat
import com.island.pass.data.HostStatus
import com.island.pass.data.IslandConfig
import com.island.pass.data.RootUtil
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CheckboxDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 宿主进程探测状态 */
internal enum class ProbeState {
    /** 检查中 */
    CHECKING,

    /** 通信正常，注入已确认生效 */
    OK,

    /**
     * 待确认（橙色感叹号）：模块已激活且宿主进程存活，但广播无响应。
     * 可能因宿主进程被系统冻结/后台限制导致，属于可能误报的中间态。
     */
    SUSPECT,

    /** 模块未激活，或宿主进程未运行 */
    FAILED,

    /** 宿主未安装 */
    NOT_INSTALLED,

    /** 功能开关已关闭：跳过探测，不产生误报 */
    DISABLED
}

/** 语义色（状态点/图标，浅深色主题通用） */
private val StatusGreen = Color(0xFF34C759)
private val StatusRed = Color(0xFFFF3B30)
private val StatusAmber = Color(0xFFFF9500)
private val StatusGray = Color(0xFF8E8E93)

/** 签名证书 SHA-256 摘要（截断展示，关于页） */
internal fun signatureDigestOf(context: android.content.Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
    )
    val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching "未知"
    val md = java.security.MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
    md.take(16).joinToString(":") { "%02X".format(it) }
}.getOrDefault("未知")

/**
 * 首页：总体状态横幅 + 工作状态 + 作用域勾选 + 设备兼容（调试明细可选）。
 */
@Composable
internal fun HomeScreen(
    modifier: Modifier = Modifier,
    checking: Boolean,
    sysuiProbe: ProbeState,
    noticeflowProbe: ProbeState,
    sysuiHostStatus: HostStatus?,
    noticeflowHostStatus: HostStatus?,
    pluginStatus: String?,
    noticeflowVersion: String?,
    debugMode: Boolean,
    onRecheck: () -> Unit,
    onOpenNoticeflow: () -> Unit
) {
    val noticeflowInstalled = noticeflowVersion != null
    val anyFailed = sysuiProbe == ProbeState.FAILED ||
        noticeflowProbe == ProbeState.FAILED ||
        (sysuiHostStatus?.allOk == false) ||
        (noticeflowHostStatus?.allOk == false)
    val anySuspect = sysuiProbe == ProbeState.SUSPECT || noticeflowProbe == ProbeState.SUSPECT
    /** 功能开关关闭：对应宿主不参与判定，整体状态不应因此变红 */
    val anyDisabled = sysuiProbe == ProbeState.DISABLED || noticeflowProbe == ProbeState.DISABLED
    val overallOk = (sysuiProbe == ProbeState.OK || sysuiProbe == ProbeState.DISABLED) &&
        (noticeflowProbe == ProbeState.OK || noticeflowProbe == ProbeState.NOT_INSTALLED ||
            noticeflowProbe == ProbeState.DISABLED) &&
        !anyFailed && !anySuspect
    /** 待确认：无失败项，但至少一端无响应（模块已激活 + 宿主进程存活） */
    val overallSuspect = !anyFailed && anySuspect

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.subtitle),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
        Text(
            text = stringResource(R.string.version_limit),
            style = MiuixTheme.textStyles.footnote1,
            color = if (DeviceCompat.isSupported) MiuixTheme.colorScheme.onSurfaceVariantSummary
            else MiuixTheme.colorScheme.error
        )
        Spacer(Modifier.height(8.dp))

        // ===== 总体状态横幅 =====
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    checking -> StatusGray
                                    overallOk -> StatusGreen
                                    overallSuspect -> StatusAmber
                                    else -> StatusRed
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            checking -> Spacer(Modifier.size(24.dp))
                            overallOk -> Icon(
                                imageVector = MiuixIcons.Basic.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                            overallSuspect -> Icon(
                                imageVector = MiuixIcons.Info,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                            else -> Icon(
                                imageVector = MiuixIcons.Basic.Close,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                when {
                                    checking -> R.string.home_overall_checking
                                    overallOk -> R.string.home_overall_ok
                                    overallSuspect -> R.string.home_overall_suspect
                                    else -> R.string.home_overall_failed
                                }
                            ),
                            style = MiuixTheme.textStyles.title3,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(
                                when {
                                    checking -> R.string.home_overall_desc_checking
                                    overallOk -> R.string.home_overall_desc_ok
                                    overallSuspect -> R.string.home_overall_desc_suspect
                                    else -> R.string.home_overall_desc_failed
                                }
                            ),
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (overallSuspect) StatusAmber
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(text = stringResource(R.string.btn_recheck), onClick = onRecheck, enabled = !checking)
                }
                // 功能已关闭提示（有功能关闭时展示，不影响整体 OK 判定）
                if (anyDisabled) {
                    Text(
                        text = stringResource(R.string.home_overall_disabled),
                        style = MiuixTheme.textStyles.footnote1,
                        color = StatusGray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ===== 模块信息（KSU 版式：图标 + 粗体标题 + 灰色值，同类信息合并一卡） =====
        SmallTitle(text = stringResource(R.string.section_module_info))
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            InfoRow(
                icon = MiuixIcons.Update,
                title = stringResource(R.string.label_module_version),
                value = stringResource(
                    R.string.value_module_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                )
            )
            InfoRow(
                icon = MiuixIcons.Layers,
                title = stringResource(R.string.label_sysui_plugin),
                value = pluginStatus ?: stringResource(R.string.value_not_detected),
                valueColor = if (pluginStatus != null) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else StatusAmber
            )
            InfoRow(
                icon = MiuixIcons.Messages,
                title = stringResource(R.string.label_noticeflow),
                value = noticeflowVersion ?: stringResource(R.string.value_not_installed)
            )
            InfoRow(
                icon = MiuixIcons.Phone,
                title = stringResource(R.string.label_device),
                value = remember {
                    listOf(Build.BRAND, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
                }
            )
            InfoRow(
                icon = MiuixIcons.Settings,
                title = stringResource(R.string.label_system),
                value = DeviceCompat.deviceSummary()
            )
        }

        Spacer(Modifier.height(8.dp))

        // ===== 工作状态 =====
        SmallTitle(text = stringResource(R.string.section_status))
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            InfoRow(
                icon = MiuixIcons.Basic.Check,
                title = stringResource(R.string.status_module),
                value = stringResource(R.string.state_module_ok),
                iconTint = StatusGreen,
                valueColor = StatusGreen
            )
            InfoRow(
                icon = probeIcon(sysuiProbe),
                title = stringResource(R.string.status_sysui_inject),
                value = describeHost(sysuiProbe, sysuiHostStatus),
                iconTint = probeColor(sysuiProbe),
                valueColor = probeColor(sysuiProbe)
            )
            InfoRow(
                icon = probeIcon(noticeflowProbe),
                title = stringResource(R.string.status_noticeflow_inject),
                value = describeNoticeflow(noticeflowProbe, noticeflowHostStatus, noticeflowInstalled),
                iconTint = probeColor(noticeflowProbe),
                valueColor = probeColor(noticeflowProbe),
                onClick = if (noticeflowInstalled &&
                    (noticeflowProbe == ProbeState.FAILED || noticeflowProbe == ProbeState.SUSPECT)
                ) onOpenNoticeflow else null
            )
        }

        Spacer(Modifier.height(8.dp))

        // ===== 作用域勾选 =====
        SmallTitle(text = stringResource(R.string.section_scope))
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            InfoRow(
                icon = MiuixIcons.Tune,
                title = stringResource(R.string.scope_sysui_title),
                value = when (sysuiProbe) {
                    ProbeState.OK -> stringResource(R.string.scope_confirmed)
                    ProbeState.CHECKING -> stringResource(R.string.state_checking)
                    ProbeState.SUSPECT -> stringResource(R.string.scope_suspect)
                    ProbeState.DISABLED -> stringResource(R.string.scope_disabled)
                    else -> stringResource(R.string.scope_missing)
                },
                valueColor = probeColor(sysuiProbe)
            )
            InfoRow(
                icon = MiuixIcons.Tune,
                title = stringResource(R.string.scope_noticeflow_title),
                value = when (noticeflowProbe) {
                    ProbeState.OK -> stringResource(R.string.scope_confirmed)
                    ProbeState.NOT_INSTALLED -> stringResource(R.string.scope_not_installed)
                    ProbeState.CHECKING -> stringResource(R.string.state_checking)
                    ProbeState.SUSPECT -> stringResource(R.string.scope_suspect)
                    ProbeState.DISABLED -> stringResource(R.string.scope_disabled)
                    else -> stringResource(R.string.scope_missing)
                },
                valueColor = probeColor(noticeflowProbe)
            )
            val scopeAllOk = (sysuiProbe == ProbeState.OK || sysuiProbe == ProbeState.DISABLED) &&
                (noticeflowProbe == ProbeState.OK || noticeflowProbe == ProbeState.NOT_INSTALLED ||
                    noticeflowProbe == ProbeState.DISABLED)
            if (!scopeAllOk) {
                val scopeFailed = sysuiProbe == ProbeState.FAILED || noticeflowProbe == ProbeState.FAILED
                val warnColor = if (scopeFailed) StatusRed else StatusAmber
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                        Icon(
                            imageVector = if (scopeFailed) MiuixIcons.Basic.Close else MiuixIcons.Info,
                            contentDescription = null,
                            tint = warnColor,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            if (scopeFailed) {
                                Text(
                                    text = stringResource(R.string.scope_warning_main),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.error
                                )
                                if (noticeflowInstalled && noticeflowProbe == ProbeState.FAILED) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.scope_warning_partial),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.error
                                    )
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.scope_warning_suspect),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = StatusAmber
                                )
                            }
                        }
                    }
                }
        }

        // ===== 调试明细（调试模式开启时可见） =====
        if (debugMode) {
            Spacer(Modifier.height(8.dp))
            SmallTitle(text = stringResource(R.string.home_detail_title))
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    HostDetailBlock("systemui", sysuiHostStatus)
                    Spacer(Modifier.height(8.dp))
                    HostDetailBlock("noticeflow", noticeflowHostStatus)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 设备兼容提示（仅不兼容时醒目展示） =====
        if (!DeviceCompat.isSupported) {
            Text(
                text = stringResource(R.string.compat_fail),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 信息行（KSU 版式）：左侧图标 + 粗体标题 + 下方灰色/语义色值。
 * 同类信息合并为一卡，卡内多行以此组件排布。
 *
 * 水平内边距由行自持（16dp，与 Miuix BasicComponent 的 insideMargin 一致），
 * 卡片容器不再附加水平内边距——按下覆盖层随行宽直达卡片边缘。
 */
@Composable
private fun InfoRow(
    icon: ImageVector,
    title: String,
    value: String,
    iconTint: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    valueColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface
            )
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    style = MiuixTheme.textStyles.footnote1,
                    color = valueColor
                )
            }
        }
    }
}

/** 探测状态对应的语义色 */
@Composable
private fun probeColor(state: ProbeState): Color = when (state) {
    ProbeState.OK -> StatusGreen
    ProbeState.SUSPECT -> StatusAmber
    ProbeState.FAILED -> StatusRed
    ProbeState.DISABLED -> StatusGray
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

/** 探测状态对应的图标 */
@Composable
private fun probeIcon(state: ProbeState): ImageVector = when (state) {
    ProbeState.OK -> MiuixIcons.Basic.Check
    ProbeState.SUSPECT -> MiuixIcons.Info
    ProbeState.FAILED -> MiuixIcons.Basic.Close
    ProbeState.DISABLED -> MiuixIcons.Basic.Close
    else -> MiuixIcons.Info
}

/** 调试模式：单个宿主的注入明细 */
@Composable
private fun HostDetailBlock(label: String, status: HostStatus?) {
    Column {
        Text(
            text = "■ $label",
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (status == null) {
            Text(
                text = stringResource(R.string.detail_no_data),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            return@Column
        }
        Text(
            text = "${status.androidDesc} · ${status.hyperOsDesc}",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        status.hooks.forEach { h ->
            InfoRow(
                icon = if (h.ok) MiuixIcons.Basic.Check else MiuixIcons.Basic.Close,
                title = h.name,
                value = h.detail.ifBlank {
                    if (h.ok) stringResource(R.string.detail_hook_installed)
                    else stringResource(R.string.detail_hook_failed)
                },
                iconTint = if (h.ok) StatusGreen else StatusRed,
                valueColor = if (h.ok) MiuixTheme.colorScheme.onSurfaceVariantSummary else StatusRed
            )
        }
        status.errors.forEach { e ->
            InfoRow(
                icon = MiuixIcons.Basic.Close,
                title = stringResource(R.string.detail_error_title),
                value = e,
                iconTint = StatusRed,
                valueColor = StatusRed
            )
        }
    }
}

/**
 * 配置页：功能开关 + 上岛黑白名单（独立双名单、可单独启停）+ 重启。
 *
 * 配置改动即时保存并热更新至宿主（CONFIG_RELOAD），无保存按钮。
 */
@Composable
internal fun ConfigScreen(
    modifier: Modifier = Modifier,
    noticeflowInstalled: Boolean,
    noticeflowVersion: String?,
    pluginVersion: String?,
    bypassWhitelist: Boolean,
    sdkMapping: Boolean,
    whitelistEnabled: Boolean,
    whitelistCount: Int,
    blacklistEnabled: Boolean,
    blacklistCount: Int,
    restartEnabled: Boolean,
    rootState: RootUtil.State,
    showRestartDialog: Boolean,
    onBypassChange: (Boolean) -> Unit,
    onSdkChange: (Boolean) -> Unit,
    onWhitelistEnabledChange: (Boolean) -> Unit,
    onBlacklistEnabledChange: (Boolean) -> Unit,
    onManageWhitelist: () -> Unit,
    onManageBlacklist: () -> Unit,
    onRestartClick: () -> Unit,
    onRestartDismiss: () -> Unit,
    onRestartRetryRoot: () -> Unit,
    onRestartConfirm: (List<String>) -> Unit
) {
    val rootGranted = rootState == RootUtil.State.GRANTED
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // ===== 功能开关 =====
        SmallTitle(text = stringResource(R.string.section_features))
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            SwitchPreference(
                checked = bypassWhitelist,
                onCheckedChange = onBypassChange,
                title = stringResource(R.string.label_bypass),
                summary = stringResource(R.string.desc_bypass)
            )
            Text(
                text = stringResource(R.string.status_sysui_plugin, pluginVersion ?: "未检测到"),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
            )
            if (noticeflowInstalled) {
                SwitchPreference(
                    checked = sdkMapping,
                    onCheckedChange = onSdkChange,
                    title = stringResource(R.string.label_sdk_mapping),
                    summary = stringResource(R.string.desc_sdk_mapping)
                )
                Text(
                    text = stringResource(R.string.status_noticeflow_version, noticeflowVersion ?: "未知版本"),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
                )
            } else {
                Text(
                    text = stringResource(R.string.noticeflow_absent),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ===== 上岛名单（白/黑名单独立，可单独启停） =====
        SmallTitle(text = stringResource(R.string.section_list))
        Text(
            text = stringResource(R.string.list_overlay_desc),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            // 启用开关：停用的名单不参与判定（两个都关 = 不限制上岛）
            SwitchPreference(
                checked = whitelistEnabled,
                onCheckedChange = onWhitelistEnabledChange,
                title = stringResource(R.string.list_enable_white),
                summary = when {
                    !whitelistEnabled -> stringResource(R.string.list_state_disabled)
                    whitelistCount > 0 ->
                        stringResource(R.string.list_state_white_on, whitelistCount)

                    else -> stringResource(R.string.list_state_white_empty)
                }
            )
            SwitchPreference(
                checked = blacklistEnabled,
                onCheckedChange = onBlacklistEnabledChange,
                title = stringResource(R.string.list_enable_black),
                summary = when {
                    !blacklistEnabled -> stringResource(R.string.list_state_disabled)
                    blacklistCount > 0 ->
                        stringResource(R.string.list_state_black_on, blacklistCount)

                    else -> stringResource(R.string.list_state_black_empty)
                }
            )
            // 管理入口：编辑全局名单 / 进入名单库（多名单叠加）
            ArrowPreference(
                title = stringResource(R.string.list_white_title),
                summary = stringResource(R.string.list_manage_entry_desc_white),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Basic.Check,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                },
                onClick = onManageWhitelist
            )
            ArrowPreference(
                title = stringResource(R.string.list_black_title),
                summary = stringResource(R.string.list_manage_entry_desc_black),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Blocklist,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                },
                onClick = onManageBlacklist
            )
            Text(
                text = stringResource(R.string.list_library_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // ===== 操作 =====
        SmallTitle(text = stringResource(R.string.section_actions))
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            ArrowPreference(
                title = stringResource(R.string.btn_restart),
                summary = if (rootGranted) stringResource(R.string.desc_restart)
                else stringResource(R.string.root_needed_desc),
                onClick = onRestartClick,
                enabled = restartEnabled
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showRestartDialog) {
        RestartDialog(
            noticeflowAvailable = noticeflowInstalled,
            rootState = rootState,
            onDismiss = onRestartDismiss,
            onRetryRoot = onRestartRetryRoot,
            onConfirm = onRestartConfirm
        )
    }
}

/**
 * 设置页：主题入口 + 排障开关 + 关于。
 *
 * 主题（明暗模式 / Monet 动态取色 / 悬浮底栏 / 仅图标 / 界面缩放 / 页面动画）
 * 已迁入二级页 [com.island.pass.ui.ThemeSettingsActivity]，此处仅保留入口。
 */
@Composable
internal fun SettingsPageScreen(
    modifier: Modifier = Modifier,
    verboseLog: Boolean,
    debugMode: Boolean,
    onOpenThemeSettings: () -> Unit,
    onVerboseChange: (Boolean) -> Unit,
    onDebugChange: (Boolean) -> Unit
) {
    var showAbout by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // ===== 外观 =====
        SmallTitle(text = stringResource(R.string.section_appearance))
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            ArrowPreference(
                title = stringResource(R.string.settings_theme),
                onClick = onOpenThemeSettings
            )
        }

        Spacer(Modifier.height(8.dp))

        // ===== 排障 =====
        SmallTitle(text = stringResource(R.string.section_troubleshoot))
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            SwitchPreference(
                checked = verboseLog,
                onCheckedChange = onVerboseChange,
                title = stringResource(R.string.settings_verbose),
                summary = stringResource(R.string.settings_verbose_desc)
            )
            SwitchPreference(
                checked = debugMode,
                onCheckedChange = onDebugChange,
                title = stringResource(R.string.settings_debug),
                summary = stringResource(R.string.settings_debug_desc)
            )
        }

        Spacer(Modifier.height(8.dp))

        // ===== 关于 =====
        SmallTitle(text = stringResource(R.string.section_about))
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            ArrowPreference(
                title = stringResource(R.string.settings_about),
                onClick = { showAbout = true }
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

/** 关于对话框：版本 / 签名证书 / 开源许可 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val signature = remember(context) { signatureDigestOf(context) }
    Dialog(onDismissRequest = onDismiss) {
        Card(cornerRadius = 24.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.settings_about),
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.about_desc),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(12.dp))
                AboutRow(label = stringResource(R.string.about_version), value = BuildConfig.VERSION_NAME)
                AboutRow(label = stringResource(R.string.about_signature), value = signature)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.about_license),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.about_license_text),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss) {
                        Text(text = stringResource(R.string.about_dialog_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(72.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 多选重启对话框 */
@Composable
internal fun RestartDialog(
    noticeflowAvailable: Boolean,
    rootState: RootUtil.State,
    onDismiss: () -> Unit,
    onRetryRoot: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val selected = remember {
        mutableStateMapOf(IslandConfig.PKG_SYSTEMUI to true)
    }
    val rootGranted = rootState == RootUtil.State.GRANTED
    Dialog(onDismissRequest = onDismiss) {
        Card(cornerRadius = 24.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.dialog_restart_title),
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dialog_restart_desc),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(12.dp))
                DialogRootRow(state = rootState, onRetry = onRetryRoot)
                Spacer(Modifier.height(4.dp))
                DialogCheckRow(
                    label = stringResource(R.string.dialog_restart_sysui),
                    sub = stringResource(R.string.dialog_restart_sysui_sub),
                    checked = selected[IslandConfig.PKG_SYSTEMUI] == true,
                    onToggle = { selected[IslandConfig.PKG_SYSTEMUI] = it }
                )
                if (noticeflowAvailable) {
                    Spacer(Modifier.height(8.dp))
                    DialogCheckRow(
                        label = stringResource(R.string.dialog_restart_noticeflow),
                        sub = stringResource(R.string.dialog_restart_noticeflow_sub),
                        checked = selected[IslandConfig.PKG_NOTICEFLOW] == true,
                        onToggle = { selected[IslandConfig.PKG_NOTICEFLOW] = it }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(text = stringResource(R.string.dialog_restart_cancel), onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(selected.filterValues { it }.keys.toList()) },
                        enabled = rootGranted && selected.any { it.value }
                    ) {
                        Text(text = stringResource(R.string.dialog_restart_confirm))
                    }
                }
            }
        }
    }
}

/** 对话框内的 Root 状态行 */
@Composable
private fun DialogRootRow(state: RootUtil.State, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    when (state) {
                        RootUtil.State.GRANTED -> StatusGreen
                        RootUtil.State.DENIED -> StatusRed
                        RootUtil.State.CHECKING -> StatusGray
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                RootUtil.State.GRANTED -> Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
                RootUtil.State.DENIED -> Icon(
                    imageVector = MiuixIcons.Basic.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
                RootUtil.State.CHECKING -> Unit
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dialog_root_title),
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    when (state) {
                        RootUtil.State.GRANTED -> R.string.root_state_granted
                        RootUtil.State.DENIED -> R.string.root_state_denied
                        RootUtil.State.CHECKING -> R.string.root_state_checking
                    }
                ),
                style = MiuixTheme.textStyles.footnote2,
                color = when (state) {
                    RootUtil.State.GRANTED -> StatusGreen
                    RootUtil.State.DENIED -> StatusRed
                    RootUtil.State.CHECKING -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                }
            )
        }
        if (state == RootUtil.State.DENIED) {
            TextButton(text = stringResource(R.string.root_retry), onClick = onRetry)
        }
    }
}

/** 对话框内的多选行 */
@Composable
private fun DialogCheckRow(
    label: String,
    sub: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            state = ToggleableState(checked),
            onClick = { onToggle(!checked) },
            colors = CheckboxDefaults.checkboxColors()
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface
            )
            Text(
                text = sub,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

/** 系统界面宿主状态描述 */
@Composable
internal fun describeHost(state: ProbeState, status: HostStatus?): String {
    val base = when {
        state == ProbeState.DISABLED -> stringResource(R.string.status_disabled)
        state == ProbeState.CHECKING -> stringResource(R.string.state_checking)
        state == ProbeState.FAILED -> stringResource(R.string.status_host_offline)
        state == ProbeState.SUSPECT -> stringResource(R.string.status_suspect)
        status == null -> stringResource(R.string.state_failed)
        status.allOk -> stringResource(R.string.status_hooks_ok, status.hooks.size)
        else -> stringResource(R.string.status_hooks_failed, status.failedCount)
    }
    return base
}

/** 实况通知宿主状态描述 */
@Composable
internal fun describeNoticeflow(state: ProbeState, status: HostStatus?, installed: Boolean): String = when {
    state == ProbeState.NOT_INSTALLED -> stringResource(R.string.status_noticeflow_absent)
    state == ProbeState.DISABLED -> stringResource(R.string.status_disabled)
    state == ProbeState.CHECKING -> stringResource(R.string.state_checking)
    state == ProbeState.FAILED -> stringResource(R.string.status_host_offline)
    state == ProbeState.SUSPECT -> stringResource(R.string.status_suspect)
    status == null -> stringResource(R.string.state_failed)
    status.allOk -> stringResource(R.string.status_hooks_ok, status.hooks.size)
    else -> stringResource(R.string.status_hooks_failed, status.failedCount)
}
