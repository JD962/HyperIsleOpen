package com.island.pass.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
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
import com.island.pass.data.IslandListFiles
import com.island.pass.data.IslandListManager
import com.island.pass.data.IslandConfig
import com.island.pass.data.ListType
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
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 应用条目（installed=false 表示手机明确未安装，仅显示包名 + 占位图标） */
private data class AppItem(
    val label: String,
    val pkg: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val installed: Boolean
)

/**
 * 名单管理二级页（1.0.9 重构）。
 *
 * 两种编辑对象（Intent 区分）：
 *  - 默认（[EXTRA_LIST_ID] 为空）：编辑**全局名单**——用户在配置页点白/黑名单进入的形态；
 *  - [EXTRA_LIST_ID] 非空：编辑指定**个性名单**的内容（自三级名单库进入）。
 *
 * 行为约定：
 *  - 已选项置顶（最新加入在最上），未选项按名称排序；
 *  - 勾选/取消即时保存：全局名单写入本地偏好并汇总热更新宿主；
 *    个性名单写入文件，若其处于启用状态则同步热更新；
 *  - 手机明确未安装的包名（手动添加/导入带入）仅显示包名 + 占位图标，
 *    取消勾选即从名单移除（可删除）；
 *  - 全局形态底部提供「保存为个性名单」（快照当前勾选为新名单文件，不自动启用）
 *    与「名单库」三级入口；个性形态（自名单库进入）不提供名单库入口，避免套娃。
 */
class ListManageActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_LIST_ID = "extra_list_id"
    }

    private var type by mutableStateOf(ListType.WHITELIST)
    private var listId by mutableStateOf<String?>(null)
    private var listName by mutableStateOf<String?>(null)

    /** 已选名单（存储顺序：旧 → 新；展示时反转，最新加入置顶） */
    private val selected: SnapshotStateList<AppItem> = mutableListOf<AppItem>().toMutableStateList()

    private var showSystem by mutableStateOf(false)
    private var query by mutableStateOf("")
    private var allApps by mutableStateOf<List<AppItem>>(emptyList())

    /** 应用列表加载中（初次加载 / 切换「显示系统应用」重载） */
    private var appsLoading by mutableStateOf(true)

    /** 已选名单加载中（初次加载 / ON_START 返回重读） */
    private var selectedLoading by mutableStateOf(true)
    private var showManualDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        type = ListType.fromValue(intent.getStringExtra(EXTRA_TYPE)) ?: ListType.WHITELIST
        listId = intent.getStringExtra(EXTRA_LIST_ID)

        // 个性名单形态：文件必须存在，否则直接结束（已被删除）
        listId?.let { id ->
            val list = IslandListFiles.load(this, id)
            if (list == null) {
                Toast.makeText(this, R.string.applist_list_missing, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            type = list.type
            listName = list.name
        }

        loadSelected()
        loadApps()

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                // 名单可能被三级页重命名/删除，返回时重读
                reloadSelectedOrFinish()
            }
        })

        setContent {
            val darkPref = runCatching {
                IslandFramework.prefs(this, IslandConfig.PREFS_NAME)
                    .getInt(IslandConfig.KEY_THEME_MODE, 0)
            }.getOrDefault(0)
            val dark = darkPref == 2 || (darkPref == 0 && isSystemInDarkTheme())
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                ListManageScreen(
                    type = type,
                    editingGlobal = listId == null,
                    listName = listName,
                    apps = allApps,
                    selected = selected,
                    loading = selectedLoading || (appsLoading && selected.isEmpty()),
                    showSystem = showSystem,
                    query = query,
                    onQueryChange = { query = it },
                    onShowSystemChange = { enabled ->
                        if (showSystem != enabled) {
                            showSystem = enabled
                            loadApps()
                        }
                    },
                    onToggle = { pkg -> togglePkg(pkg) },
                    onManualConfirm = { pkg -> manualAdd(pkg) },
                    onSaveAsPersonal = { name -> saveAsPersonal(name) },
                    onOpenLibrary = { openLibrary() },
                    onBack = { finish() }
                )
            }
        }
    }

    // ===== 数据读写 =====

    /** 读取当前编辑对象的名单（保序）并解析应用信息 */
    private fun loadSelected() {
        selectedLoading = true
        lifecycleScope.launch {
            val pkgs = withContext(Dispatchers.IO) {
                val id = listId
                if (id != null) {
                    IslandListFiles.load(this@ListManageActivity, id)?.packages ?: emptyList()
                } else {
                    IslandListManager.globalList(this@ListManageActivity, type)
                }
            }
            val items = withContext(Dispatchers.IO) { pkgs.map { resolveAppItem(it) } }
            selected.clear()
            selected.addAll(items)
            selectedLoading = false
        }
    }

    /** ON_START 重读；个性名单已被删除时结束本页 */
    private fun reloadSelectedOrFinish() {
        val id = listId
        if (id != null && IslandListFiles.load(this, id) == null) {
            Toast.makeText(this, R.string.applist_list_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        loadSelected()
    }

    /** 解析包名对应的应用信息；手机明确未安装时返回占位条目 */
    private fun resolveAppItem(pkg: String): AppItem {
        val pm = packageManager
        return runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            AppItem(
                label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull() ?: pkg,
                pkg = pkg,
                icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                installed = true
            )
        }.getOrElse {
            AppItem(label = pkg, pkg = pkg, icon = null, isSystem = false, installed = false)
        }
    }

    /** 勾选切换并即时保存 */
    private fun togglePkg(pkg: String) {
        val existing = selected.firstOrNull { it.pkg == pkg }
        if (existing != null) {
            selected.remove(existing)
        } else {
            val item = allApps.firstOrNull { it.pkg == pkg } ?: resolveAppItem(pkg)
            selected.add(item)
        }
        saveSelected()
    }

    /** 手动添加包名（即时解析信息，未安装则占位显示，可取消勾选删除） */
    private fun manualAdd(pkg: String) {
        if (selected.any { it.pkg == pkg }) {
            Toast.makeText(this, R.string.applist_manual_dup, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) { resolveAppItem(pkg) }
            selected.add(item)
            saveSelected()
            Toast.makeText(
                this@ListManageActivity,
                getString(R.string.applist_manual_added, pkg),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 保存当前勾选：
     *  - 全局名单：写本地偏好 + 汇总生效名单热更新宿主；
     *  - 个性名单：写文件；启用中才影响生效集合（同样热更新）。
     */
    private fun saveSelected() {
        val pkgs = selected.map { it.pkg }
        val id = listId
        if (id != null) {
            IslandListFiles.setPackages(this, id, pkgs)
            if (IslandListManager.enabledIds(this).contains(id)) {
                IslandListManager.refresh(this)
            }
        } else {
            IslandListManager.setGlobalList(this, type, pkgs)
            IslandListManager.refresh(this)
        }
    }

    /** 保存为个性名单（快照当前勾选，创建后不勾选启用） */
    private fun saveAsPersonal(name: String) {
        val list = IslandListManager.saveAsPersonal(this, name, type, selected.map { it.pkg })
        Toast.makeText(
            this,
            getString(R.string.toast_list_saved_as_personal, list.name),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openLibrary() {
        runCatching {
            startActivity(
                Intent(this, ListLibraryActivity::class.java)
                    .putExtra(ListLibraryActivity.EXTRA_TYPE, type.value)
            )
        }.onFailure { IslandLog.e("openLibrary failed: ${it.message}") }
    }

    /** IO 线程加载已安装应用列表（launcher 应用 + 可选系统应用） */
    private fun loadApps() {
        appsLoading = true
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val pm = packageManager
                val items = LinkedHashMap<String, AppItem>()
                runCatching {
                    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    pm.queryIntentActivities(launcherIntent, 0).forEach { ri ->
                        val ai = ri.activityInfo?.applicationInfo ?: return@forEach
                        val pkg = ai.packageName
                        items[pkg] = AppItem(
                            label = runCatching { ri.loadLabel(pm).toString() }.getOrNull() ?: pkg,
                            pkg = pkg,
                            icon = runCatching { ri.loadIcon(pm) }.getOrNull(),
                            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                            installed = true
                        )
                    }
                }
                if (showSystem) {
                    runCatching {
                        pm.getInstalledApplications(0).forEach { ai ->
                            if (items.containsKey(ai.packageName)) return@forEach
                            items[ai.packageName] = AppItem(
                                label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull() ?: ai.packageName,
                                pkg = ai.packageName,
                                icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
                                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                installed = true
                            )
                        }
                    }
                }
                items.values.sortedBy { it.label.lowercase() }
            }
            allApps = list
            appsLoading = false
        }
    }
}

/**
 * 名单管理主体。
 *
 * 展示顺序：已选项（最新加入在最上）→ 未选项（按名称）；
 * 未安装包名仅显示包名 + 占位图标（见 [PlaceholderIcon]）。
 */
@Composable
private fun ListManageScreen(
    type: ListType,
    editingGlobal: Boolean,
    listName: String?,
    apps: List<AppItem>,
    selected: List<AppItem>,
    loading: Boolean,
    showSystem: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onShowSystemChange: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
    onManualConfirm: (String) -> Unit,
    onSaveAsPersonal: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onBack: () -> Unit
) {
    var showManualDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }

    // 直接读取而非 remember(selected){...}：remember 仅比较 key（列表实例）相等性，
    // 实例不变则 equals 恒真，勾选/手动添加后计算值陈旧、UI 不重组（1.0.9 遗留 bug）。
    // SnapshotStateList 的结构读取可被组合跟踪，直接计算即可随勾选即时刷新。
    val selectedPkgs = selected.mapTo(HashSet()) { it.pkg }

    // 搜索过滤：中文名/英文名/包名模糊匹配（忽略大小写）
    val matchesQuery: (AppItem) -> Boolean = { app ->
        query.isBlank() ||
            app.label.contains(query, ignoreCase = true) ||
            app.pkg.contains(query, ignoreCase = true)
    }

    // 已选置顶（最新加入在最上），其后未选按名称
    val selectedShown = selected.asReversed().filter(matchesQuery)
    val unselectedShown = apps.filter { it.pkg !in selectedPkgs }.filter(matchesQuery)
        .sortedBy { it.label.lowercase() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = listName ?: stringResource(
                    if (type == ListType.WHITELIST) R.string.applist_title_white
                    else R.string.applist_title_black
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showManualDialog = true }) {
                        Icon(
                            imageVector = MiuixIcons.Edit,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // 语义提示 + 已选数量 + 编辑对象
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        if (type == ListType.WHITELIST) R.string.applist_hint_white
                        else R.string.applist_hint_black
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.applist_selected_count, selected.size),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
            }
            Text(
                text = if (editingGlobal) stringResource(R.string.applist_editing_global)
                else stringResource(R.string.applist_editing_named, listName ?: ""),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            // 手动添加语义说明：出现即为勾选态，取消勾选即移除（删除入口）
            if (selected.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.applist_manual_note),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            SearchField(
                value = query,
                onValueChange = onQueryChange,
                hint = stringResource(R.string.applist_search_hint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                cornerRadius = 16.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onShowSystemChange(!showSystem) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.applist_show_system),
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Checkbox(
                        state = ToggleableState(showSystem),
                        onClick = { onShowSystemChange(!showSystem) },
                        colors = CheckboxDefaults.checkboxColors()
                    )
                }
            }

            when {
                loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    InfiniteProgressIndicator()
                }

                selectedShown.isEmpty() && unselectedShown.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.applist_empty),
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }

                else -> LazyColumn(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    items(selectedShown, key = { "sel-${it.pkg}" }) { app ->
                        AppCheckRow(
                            app = app,
                            checked = true,
                            onToggle = { onToggle(app.pkg) }
                        )
                    }
                    items(unselectedShown, key = { "uns-${it.pkg}" }) { app ->
                        AppCheckRow(
                            app = app,
                            checked = false,
                            onToggle = { onToggle(app.pkg) }
                        )
                    }
                }
            }

            // 底部固定卡：
            //  - 全局形态：「保存为个性名单」+「名单库」三级入口；
            //  - 个性形态（自名单库进入）：仅提示文本，不再提供名单库入口（避免套娃）。
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                cornerRadius = 16.dp
            ) {
                if (editingGlobal) {
                    Button(
                        onClick = { showSaveAsDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(text = stringResource(R.string.btn_save_as_personal))
                    }
                    ArrowPreference(
                        title = stringResource(R.string.entry_list_library),
                        summary = stringResource(R.string.entry_list_library_desc),
                        startAction = {
                            Icon(
                                imageVector = MiuixIcons.Playlist,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        },
                        onClick = onOpenLibrary
                    )
                } else {
                    Text(
                        text = stringResource(R.string.applist_editing_personal_hint),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }

    if (showManualDialog) {
        ManualPkgDialog(
            onDismiss = { showManualDialog = false },
            onConfirm = { pkg ->
                showManualDialog = false
                onManualConfirm(pkg)
            }
        )
    }
    if (showSaveAsDialog) {
        NameInputDialog(
            title = stringResource(R.string.dialog_save_as_personal_title),
            hint = stringResource(R.string.lib_name_hint),
            confirmText = stringResource(R.string.btn_save_as_personal),
            onDismiss = { showSaveAsDialog = false },
            onConfirm = { name ->
                showSaveAsDialog = false
                onSaveAsPersonal(name)
            }
        )
    }
}

/** 应用勾选行：已安装显示图标+名称+包名；未安装仅显示包名+占位图标 */
@Composable
private fun AppCheckRow(app: AppItem, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (app.installed) {
            AppIcon(app.icon)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = app.pkg,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1
                )
            }
        } else {
            PlaceholderIcon(app.pkg)
            Spacer(Modifier.width(12.dp))
            Text(
                text = app.pkg,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
        Checkbox(
            state = ToggleableState(checked),
            onClick = onToggle,
            colors = CheckboxDefaults.checkboxColors()
        )
    }
}
