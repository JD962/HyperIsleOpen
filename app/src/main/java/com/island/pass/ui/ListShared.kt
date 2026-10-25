package com.island.pass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.island.pass.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 名单页共享组件（二级名单管理 / 三级名单库复用）：
 * 搜索栏、应用图标、未安装占位图标、名称输入对话框、确认对话框、手动包名对话框。
 */

/** 搜索栏（KSU 版式：圆角输入框 + 搜索/清除图标） */
@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            color = MiuixTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 45.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape)
    ) { innerTextField ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = MiuixIcons.Basic.Search,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .size(44.dp)
                    .padding(start = 14.dp, end = 6.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = hint,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                innerTextField()
            }
            if (value.isNotEmpty()) {
                Icon(
                    imageVector = MiuixIcons.Basic.SearchCleanup,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .size(44.dp)
                        .padding(start = 6.dp, end = 14.dp)
                        .clickable { onValueChange("") }
                )
            }
        }
    }
}

/** 应用图标（Drawable -> ImageBitmap） */
@Composable
internal fun AppIcon(icon: android.graphics.drawable.Drawable?) {
    val bitmap = remember(icon) {
        runCatching { icon?.toBitmap()?.asImageBitmap() }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/**
 * 未安装应用占位图标：圆形底色 + 包名首字母。
 * 用于「手机明确不存在」的手动添加/导入包名（仅显示包名与本占位图标）。
 */
@Composable
internal fun PlaceholderIcon(pkg: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = pkg.firstOrNull()?.uppercase() ?: "?",
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

/** 名称输入对话框（创建/重命名名单；必填，允许重名） */
@Composable
internal fun NameInputDialog(
    title: String,
    hint: String,
    confirmText: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf(initial) }
    val valid = remember(input) { input.isNotBlank() }
    Dialog(onDismissRequest = onDismiss) {
        Card(cornerRadius = 24.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 45.dp)
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .padding(horizontal = 16.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) {
                                Text(
                                    text = hint,
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            inner()
                        }
                    }
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(text = stringResource(R.string.applist_manual_cancel), onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(input.trim()) },
                        enabled = valid
                    ) {
                        Text(text = confirmText)
                    }
                }
            }
        }
    }
}

/** 确认对话框（删除名单等破坏性操作） */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(cornerRadius = 24.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(text = stringResource(R.string.applist_manual_cancel), onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConfirm) {
                        Text(text = confirmText)
                    }
                }
            }
        }
    }
}

/** 手动输入包名对话框 */
@Composable
internal fun ManualPkgDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val valid = remember(input) {
        input.isNotBlank() && input.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$"))
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(cornerRadius = 24.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.applist_manual_title),
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 45.dp)
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .padding(horizontal = 16.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.applist_manual_hint),
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            inner()
                        }
                    }
                )
                if (input.isNotBlank() && !valid) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.applist_manual_invalid),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(text = stringResource(R.string.applist_manual_cancel), onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(input.trim()) },
                        enabled = valid
                    ) {
                        Text(text = stringResource(R.string.applist_manual_confirm))
                    }
                }
            }
        }
    }
}
