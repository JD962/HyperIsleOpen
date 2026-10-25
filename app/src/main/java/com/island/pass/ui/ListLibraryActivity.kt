package com.island.pass.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.island.pass.R
import com.island.pass.data.IslandFramework
import com.island.pass.data.IslandConfig
import com.island.pass.data.IslandListFiles
import com.island.pass.data.IslandListManager
import com.island.pass.data.IslandListNames
import com.island.pass.data.ListType
import com.island.pass.data.NamedList
import com.island.pass.hook.IslandLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CheckboxDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 名单库三级页（1.0.9 新增）。
 *
 *  - 创建名单：必须命名；允许重名/重复内容（id 由时间戳保证唯一）；
 *  - 保存后默认**不勾选启用**；勾选的个性名单与全局名单叠加（并集）生效；
 *  - 支持导入（SAF）/ 导出（SAF，文件名：软件名称 + 时间刻 + 名单类型）；
 *  - 每名单可重命名、导出、删除；点击进入名单内容编辑（[ListManageActivity]）。
 */
class ListLibraryActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TYPE = "extra_type"
    }

    private var type by mutableStateOf(ListType.WHITELIST)
    private var lists by mutableStateOf<List<NamedList>>(emptyList())
    private var enabled by mutableStateOf<Set<String>>(emptySet())

    private var showCreateDialog by mutableStateOf(false)
    private var renameTarget by mutableStateOf<NamedList?>(null)
    private var deleteTarget by mutableStateOf<NamedList?>(null)
    private var menuTarget by mutableStateOf<NamedList?>(null)

    /** 导出回调桥（CreateDocument 为异步回调，需暂存目标名单） */
    private var pendingExport: NamedList? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val list = pendingExport
        pendingExport = null
        if (uri == null || list == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                IslandListFiles.exportToUri(this@ListLibraryActivity, list, uri)
            }
            Toast.makeText(
                this@ListLibraryActivity,
                if (ok) R.string.toast_list_exported else R.string.toast_list_export_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                IslandListFiles.importFromUri(this@ListLibraryActivity, uri)
            }
            when {
                imported == null -> Toast.makeText(
                    this@ListLibraryActivity,
                    R.string.toast_list_import_failed,
                    Toast.LENGTH_SHORT
                ).show()

                imported.type != type -> {
                    // 类型不符：撤销导入，避免污染当前名单库
                    IslandListFiles.delete(this@ListLibraryActivity, imported.id)
                    Toast.makeText(
                        this@ListLibraryActivity,
                        R.string.toast_list_import_type_mismatch,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                else -> {
                    Toast.makeText(
                        this@ListLibraryActivity,
                        getString(R.string.toast_list_imported, imported.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    reload()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        type = ListType.fromValue(intent.getStringExtra(EXTRA_TYPE)) ?: ListType.WHITELIST

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) reload()
        })

        setContent {
            val darkPref = runCatching {
                IslandFramework.prefs(this, IslandConfig.PREFS_NAME)
                    .getInt(IslandConfig.KEY_THEME_MODE, 0)
            }.getOrDefault(0)
            val dark = darkPref == 2 || (darkPref == 0 && isSystemInDarkTheme())
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                ListLibraryScreen(
                    type = type,
                    lists = lists,
                    enabled = enabled,
                    onCreate = { showCreateDialog = true },
                    onImport = { importLauncher.launch(arrayOf("application/json")) },
                    onEdit = { list -> openEditor(list) },
                    onToggleEnabled = { list -> toggleEnabled(list) },
                    onMenu = { list -> menuTarget = list },
                    onBack = { finish() }
                )
                Dialogs()
            }
        }
    }

    // ===== 数据操作 =====

    private fun reload() {
        lists = IslandListManager.personalLists(this, type)
        enabled = IslandListManager.enabledIds(this)
    }

    /** 启用/停用个性名单（勾选即与全局名单叠加生效；默认不勾选） */
    private fun toggleEnabled(list: NamedList) {
        val on = list.id !in enabled
        IslandListManager.setEnabled(this, list.id, on)
        enabled = IslandListManager.enabledIds(this)
        IslandListManager.refresh(this)
    }

    private fun createList(name: String) {
        val list = IslandListFiles.create(this, name, type, emptyList())
        Toast.makeText(this, getString(R.string.toast_list_created, list.name), Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun renameList(id: String, newName: String) {
        val renamed = IslandListFiles.rename(this, id, newName)
        if (renamed != null) {
            Toast.makeText(
                this,
                getString(R.string.toast_list_renamed, renamed.name),
                Toast.LENGTH_SHORT
            ).show()
        }
        reload()
    }

    private fun deleteList(list: NamedList) {
        IslandListFiles.delete(this, list.id)
        if (list.id in IslandListManager.enabledIds(this)) {
            IslandListManager.setEnabled(this, list.id, false)
        }
        IslandListManager.refresh(this)
        Toast.makeText(this, getString(R.string.toast_list_deleted, list.name), Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun exportList(list: NamedList) {
        pendingExport = list
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val stamp = fmt.format(Date())
        exportLauncher.launch("${IslandListNames.APP_LABEL}_${stamp}_${type.value}.json")
    }

    private fun openEditor(list: NamedList) {
        runCatching {
            startActivity(
                Intent(this, ListManageActivity::class.java)
                    .putExtra(ListManageActivity.EXTRA_TYPE, list.type.value)
                    .putExtra(ListManageActivity.EXTRA_LIST_ID, list.id)
            )
        }.onFailure { IslandLog.e("openEditor failed: ${it.message}") }
    }

    // ===== 对话框 =====

    @Composable
    private fun Dialogs() {
        if (showCreateDialog) {
            NameInputDialog(
                title = stringResource(R.string.dialog_create_list_title),
                hint = stringResource(R.string.lib_name_hint),
                confirmText = stringResource(R.string.lib_create),
                onDismiss = { showCreateDialog = false },
                onConfirm = { name ->
                    showCreateDialog = false
                    createList(name)
                }
            )
        }
        renameTarget?.let { target ->
            NameInputDialog(
                title = stringResource(R.string.dialog_rename_list_title),
                hint = stringResource(R.string.lib_name_hint),
                confirmText = stringResource(R.string.lib_menu_rename),
                initial = target.name,
                onDismiss = { renameTarget = null },
                onConfirm = { name ->
                    renameTarget = null
                    renameList(target.id, name)
                }
            )
        }
        deleteTarget?.let { target ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_delete_list_title),
                message = stringResource(R.string.dialog_delete_list_message, target.name),
                confirmText = stringResource(R.string.lib_menu_delete),
                onDismiss = { deleteTarget = null },
                onConfirm = {
                    deleteTarget = null
                    deleteList(target)
                }
            )
        }
        menuTarget?.let { target ->
            ListMenuDialog(
                list = target,
                onDismiss = { menuTarget = null },
                onEdit = {
                    menuTarget = null
                    openEditor(target)
                },
                onRename = {
                    menuTarget = null
                    renameTarget = target
                },
                onExport = {
                    menuTarget = null
                    exportList(target)
                },
                onDelete = {
                    menuTarget = null
                    deleteTarget = target
                }
            )
        }
    }
}

/**
 * 名单库主体。
 */
@Composable
private fun ListLibraryScreen(
    type: ListType,
    lists: List<NamedList>,
    enabled: Set<String>,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onEdit: (NamedList) -> Unit,
    onToggleEnabled: (NamedList) -> Unit,
    onMenu: (NamedList) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(
                    if (type == ListType.WHITELIST) R.string.lib_title_white
                    else R.string.lib_title_black
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 创建 / 导入
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.lib_overlay_desc),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = onCreate,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Add,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Text(text = stringResource(R.string.lib_create))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onImport,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Import,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Text(text = stringResource(R.string.lib_import))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 名单列表
            if (lists.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                    Text(
                        text = stringResource(R.string.lib_empty),
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                    )
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                    lists.forEach { list ->
                        LibraryRow(
                            list = list,
                            enabled = list.id in enabled,
                            onClick = { onEdit(list) },
                            onToggleEnabled = { onToggleEnabled(list) },
                            onMenu = { onMenu(list) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 名单库行：名称 + 元信息 + 启用勾选 + 更多菜单 */
@Composable
private fun LibraryRow(
    list: NamedList,
    enabled: Boolean,
    onClick: () -> Unit,
    onToggleEnabled: () -> Unit,
    onMenu: () -> Unit
) {
    val formatted = remember(list) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(list.updatedAt))
    }
    val meta = stringResource(R.string.lib_item_meta, list.packages.size, formatted)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = MiuixIcons.Playlist,
            contentDescription = null,
            tint = if (enabled) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = list.name,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = meta,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1
            )
            if (enabled) {
                Text(
                    text = stringResource(R.string.lib_enabled_badge),
                    style = MiuixTheme.textStyles.footnote2,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
            }
        }
        Checkbox(
            state = ToggleableState(enabled),
            onClick = onToggleEnabled,
            colors = CheckboxDefaults.checkboxColors()
        )
        IconButton(onClick = onMenu) {
            Icon(
                imageVector = MiuixIcons.More,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

/** 名单操作菜单（编辑内容 / 重命名 / 导出 / 删除） */
@Composable
private fun ListMenuDialog(
    list: NamedList,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val items = listOf(
        stringResource(R.string.lib_edit_content) to MiuixIcons.Edit,
        stringResource(R.string.lib_menu_rename) to MiuixIcons.Rename,
        stringResource(R.string.lib_menu_export) to MiuixIcons.Share,
        stringResource(R.string.lib_menu_delete) to MiuixIcons.Delete
    )
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(cornerRadius = 24.dp) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = list.name,
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                items.forEachIndexed { index, (label, icon) ->
                    val onClick = when (index) {
                        0 -> onEdit
                        1 -> onRename
                        2 -> onExport
                        else -> onDelete
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClick() }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (index == 3) MiuixTheme.colorScheme.error
                            else MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(
                            text = label,
                            style = MiuixTheme.textStyles.body1,
                            color = if (index == 3) MiuixTheme.colorScheme.error
                            else MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
