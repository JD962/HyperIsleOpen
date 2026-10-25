package com.island.pass.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.island.pass.hook.IslandLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 名单类型：白名单与黑名单相互独立、可同时生效（1.0.9 起）。
 * 持久化值为文件名/JSON 内的 type 字段。
 */
enum class ListType(val value: String) {
    /** 白名单：仅名单内应用可上岛（留空 = 不限制） */
    WHITELIST("whitelist"),

    /** 黑名单：名单内应用不可上岛（优先于白名单） */
    BLACKLIST("blacklist");

    companion object {
        fun fromValue(value: String?): ListType? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 命名（个性）名单：落盘为 JSON 文件（见 [IslandListFiles]），id 即文件名去扩展名。
 *
 *  - 保存后默认不勾选启用（[IslandListManager.setEnabled]）；
 *  - 已启用的个性名单与全局名单叠加（并集）生效；
 *  - 允许创建重名/重复内容的名单（id 由时间戳保证唯一）。
 */
data class NamedList(
    val id: String,
    val name: String,
    val type: ListType,
    val packages: List<String>,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        /** 反序列化；结构不符/类型缺失时返回 null（导入校验用） */
        fun fromJson(json: String): NamedList? = runCatching {
            @Suppress("UNCHECKED_CAST")
            val map = MiniJson.parse(json) as? Map<String, Any?> ?: return null
            val type = ListType.fromValue(map["type"] as? String) ?: return null
            val id = map["id"] as? String ?: return null
            val pkgs = (map["packages"] as? List<*>)?.mapNotNull { it as? String }
                ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            NamedList(
                id = id,
                name = (map["name"] as? String)?.trim() ?: "",
                type = type,
                packages = pkgs.distinct(),
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L,
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L
            )
        }.getOrNull()
    }

    /** 序列化为 JSON（UTF-8 文本，控制字符转义） */
    fun toJson(): String = MiniJson.buildObject(
        listOf(
            "id" to id,
            "name" to name,
            "type" to type.value,
            "packages" to packages,
            "createdAt" to createdAt,
            "updatedAt" to updatedAt
        )
    )
}

/**
 * 名单文件名规范：软件名称 + 时间刻 + 名单类型。
 * 例：HyperIsleOpen_20261024_210944_whitelist（.json 后缀由存储层追加）。
 */
object IslandListNames {
    const val APP_LABEL = "HyperIsleOpen"

    fun baseName(type: ListType, timeMillis: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "${APP_LABEL}_${fmt.format(Date(timeMillis))}_${type.value}"
    }
}

/**
 * 极简 JSON 编解码（object / array / string / number / true / false / null）。
 *
 * 自实现而非 org.json 的原因：
 *  - JVM 单测可直接覆盖序列化 round-trip（org.json 在单元测试中为抛桩实现）；
 *  - 名单文件结构固定，无需完整 JSON 引擎。
 */
internal object MiniJson {

    class JsonError(message: String) : Exception(message)

    fun escape(s: String): String = buildString(s.length + 8) {
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    fun buildObject(pairs: List<Pair<String, Any?>>): String = buildString {
        append('{')
        pairs.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append('"').append(escape(k)).append("\":")
            appendValue(v)
        }
        append('}')
    }

    private fun StringBuilder.appendValue(v: Any?) {
        when (v) {
            null -> append("null")
            is String -> append('"').append(escape(v)).append('"')
            is Number -> append(v.toString())
            is Boolean -> append(if (v) "true" else "false")
            is List<*> -> {
                append('[')
                v.forEachIndexed { i, item -> if (i > 0) append(','); appendValue(item) }
                append(']')
            }
            else -> append('"').append(escape(v.toString())).append('"')
        }
    }

    fun parse(text: String): Any? = Parser(text).parseTop()

    private class Parser(val text: String) {
        private var pos = 0

        fun parseTop(): Any? {
            skipWs()
            val v = parseValue()
            return v
        }

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= text.length) throw JsonError("unexpected end")
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun expect(literal: String) {
            if (!text.startsWith(literal, pos)) throw JsonError("expected $literal at $pos")
            pos += literal.length
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // consume '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                if (pos >= text.length || text[pos] != ':') throw JsonError("expected ':' at $pos")
                pos++
                map[key] = parseValue()
                skipWs()
                if (pos >= text.length) throw JsonError("unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw JsonError("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // consume '['
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (pos >= text.length) throw JsonError("unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw JsonError("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            if (pos >= text.length || text[pos] != '"') throw JsonError("expected string at $pos")
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                when (val c = text[pos]) {
                    '"' -> {
                        pos++
                        return sb.toString()
                    }
                    '\\' -> {
                        pos++
                        if (pos >= text.length) throw JsonError("dangling escape")
                        when (val e = text[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                if (pos + 4 >= text.length) throw JsonError("short unicode escape")
                                val hex = text.substring(pos + 1, pos + 5)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonError("bad escape \\$e")
                        }
                        pos++
                    }
                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
            }
            throw JsonError("unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (pos < text.length && (text[pos] == '-' || text[pos] == '+')) pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            if (pos == start) throw JsonError("invalid value at $pos")
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw JsonError("bad number '$raw'")
        }
    }
}

/**
 * 个性名单文件存储（模块 filesDir/lists/）。
 *
 *  - 文件名：软件名称 + 创建时间刻 + 名单类型（见 [IslandListNames]）；
 *  - 内容：[NamedList] JSON；
 *  - 创建时允许重名/重复内容（id 由时间戳 + 冲突计数保证唯一）。
 */
object IslandListFiles {

    private const val DIR_NAME = "lists"
    private const val EXT = ".json"

    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { runCatching { mkdirs() } }

    private fun fileFor(context: Context, id: String): File = File(dir(context), "$id$EXT")

    /** 全部个性名单（按创建时间倒序） */
    fun loadAll(context: Context): List<NamedList> = runCatching {
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(EXT) }
            ?.mapNotNull { f -> runCatching { f.readText() }.getOrNull()?.let { NamedList.fromJson(it) } }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }.getOrElse { emptyList() }

    fun load(context: Context, id: String): NamedList? = runCatching {
        fileFor(context, id).takeIf { it.isFile }?.readText()?.let { NamedList.fromJson(it) }
    }.getOrNull()

    /**
     * 创建名单文件并落盘。
     * @param name    用户命名（空则占位「未命名名单」）
     * @param packages 包名列表（去空、去重、保序）
     */
    fun create(
        context: Context,
        name: String,
        type: ListType,
        packages: List<String>
    ): NamedList {
        val now = System.currentTimeMillis()
        val base = IslandListNames.baseName(type, now)
        var id = base
        var suffix = 1
        while (fileFor(context, id).exists()) {
            suffix++
            id = "${base}_$suffix"
        }
        val list = NamedList(
            id = id,
            name = name.trim().ifBlank { "未命名名单" },
            type = type,
            packages = packages.filter { it.isNotBlank() }.distinct(),
            createdAt = now,
            updatedAt = now
        )
        write(context, list)
        return list
    }

    fun rename(context: Context, id: String, newName: String): NamedList? {
        val list = load(context, id) ?: return null
        val renamed = list.copy(
            name = newName.trim().ifBlank { list.name },
            updatedAt = System.currentTimeMillis()
        )
        write(context, renamed)
        return renamed
    }

    fun setPackages(context: Context, id: String, packages: List<String>): NamedList? {
        val list = load(context, id) ?: return null
        val updated = list.copy(
            packages = packages.filter { it.isNotBlank() }.distinct(),
            updatedAt = System.currentTimeMillis()
        )
        write(context, updated)
        return updated
    }

    fun delete(context: Context, id: String): Boolean =
        fileFor(context, id).takeIf { it.isFile }?.delete() ?: false

    private fun write(context: Context, list: NamedList) {
        runCatching { fileFor(context, list.id).writeText(list.toJson()) }
            .onFailure { IslandLog.w("list file write failed (${list.id}): ${it.message}") }
    }

    /** 导出到用户所选 uri（SAF 创建文档） */
    fun exportToUri(context: Context, list: NamedList, uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)
            ?.use { it.write(list.toJson().toByteArray()) } != null
    }.getOrElse {
        IslandLog.w("list export failed: ${it.message}")
        false
    }

    /** 从用户所选 uri 导入（重新生成 id/时间戳；类型与内容取文件自身） */
    fun importFromUri(context: Context, uri: Uri): NamedList? = runCatching {
        val text = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: return null
        val parsed = NamedList.fromJson(text) ?: return null
        create(
            context = context,
            name = parsed.name.ifBlank { "导入名单" },
            type = parsed.type,
            packages = parsed.packages
        )
    }.getOrElse {
        IslandLog.w("list import failed: ${it.message}")
        null
    }
}

/**
 * 名单管理器（模块 App 侧唯一写入口）：
 *
 *  - 全局名单：本地偏好（换行分隔保序），用户在二级页默认编辑的对象；
 *  - 个性名单：JSON 文件（[IslandListFiles]），保存后默认不勾选启用；
 *  - 生效名单：全局 ∪ 已启用个性名单，汇总写入本地偏好并镜像至远程偏好
 *    （[IslandFramework.mirrorToRemote]），同时广播 CONFIG_RELOAD 热更新至两个宿主
 *    （hooker 侧重读快照即时生效，无需重启作用域应用）。
 */
object IslandListManager {

    private const val LOCAL_PREFS = "island_pass_local"
    private const val KEY_GLOBAL_PREFIX = "global_"

    /** Application.onCreate 调用：旧名单迁移 + service 绑定补刷 + 首次汇总 */
    fun init(context: Context) {
        val app = context.applicationContext
        // service 绑定后补刷一次，规避「Application.onCreate 时远程偏好尚未就绪」竞态
        IslandFramework.onServiceBound = { refresh(app) }
        runCatching { migrateLegacy(app) }
            .onFailure { IslandLog.w("legacy list migration failed: ${it.message}") }
        refresh(app)
    }

    private fun migrateLegacy(context: Context) {
        if (IslandConfig.migrateLegacy(IslandFramework.prefs(context, IslandConfig.PREFS_NAME))) {
            IslandLog.d("legacy island list migrated to independent white/black lists")
        }
    }

    fun localPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)

    fun globalKey(type: ListType): String = KEY_GLOBAL_PREFIX + type.value

    /** 全局名单（保序） */
    fun globalList(context: Context, type: ListType): List<String> =
        IslandConfig.decodeList(localPrefs(context).getString(globalKey(type), "")).toList()

    fun setGlobalList(context: Context, type: ListType, pkgs: List<String>) {
        localPrefs(context).edit()
            .putString(globalKey(type), IslandConfig.encodeList(pkgs.filter { it.isNotBlank() }.toSet()))
            .apply()
    }

    /** 已启用个性名单 id 集合（本地偏好，模块 App 侧唯一事实源；hooker 不读） */
    fun enabledIds(context: Context): Set<String> =
        IslandFramework.prefs(context, IslandConfig.PREFS_NAME)
            .getStringSet(IslandConfig.KEY_ENABLED_LISTS, emptySet()) ?: emptySet()

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val ids = enabledIds(context).toMutableSet()
        if (enabled) ids.add(id) else ids.remove(id)
        IslandFramework.prefs(context, IslandConfig.PREFS_NAME).edit()
            .putStringSet(IslandConfig.KEY_ENABLED_LISTS, ids)
            .apply()
        // 本地写后补镜远程（hooker 侧不读该键，但保持镜像完整）
        IslandFramework.mirrorToRemote(context, IslandConfig.PREFS_NAME)
    }

    fun personalLists(context: Context, type: ListType): List<NamedList> =
        IslandListFiles.loadAll(context).filter { it.type == type }

    /** 全局 ∪ 已启用个性名单（保序去重；纯函数便于单测） */
    fun unionLists(global: List<String>, personal: List<NamedList>): List<String> {
        val out = LinkedHashSet<String>()
        global.forEach { if (it.isNotBlank()) out.add(it) }
        personal.forEach { l -> l.packages.forEach { if (it.isNotBlank()) out.add(it) } }
        return out.toList()
    }

    fun effectiveList(context: Context, type: ListType): List<String> {
        val enabled = enabledIds(context)
        val personal = personalLists(context, type).filter { it.id in enabled }
        return unionLists(globalList(context, type), personal)
    }

    /**
     * 汇总生效名单写入本地偏好并镜像远程，再广播 CONFIG_RELOAD 热更新两个宿主。
     *
     * 任何名单变更（全局勾选 / 个性启用或停用 / 名单内容编辑 / 导入）后调用；
     * 远程偏好由框架异步推送，宿主侧另有变更监听 + 延迟补读兜底。
     *
     * 注：[IslandConfig.KEY_ENABLED_LISTS] 由 [setEnabled] 单独持久化，此处不重复写
     * （避免升级迁移前本地被写入空集合而掩盖远程旧值，见 [ConfigMirror]）。
     */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val white = effectiveList(app, ListType.WHITELIST)
        val black = effectiveList(app, ListType.BLACKLIST)
        runCatching {
            IslandFramework.prefs(app, IslandConfig.PREFS_NAME).edit()
                .putString(IslandConfig.KEY_WHITELIST_PKGS, IslandConfig.encodeList(white.toSet()))
                .putString(IslandConfig.KEY_BLACKLIST_PKGS, IslandConfig.encodeList(black.toSet()))
                .apply()
            IslandFramework.mirrorToRemote(app, IslandConfig.PREFS_NAME)
        }.onFailure { IslandLog.w("effective lists write failed: ${it.message}") }
        IslandChannel.send(app, IslandConfig.PKG_SYSTEMUI, ChannelKeys.CONFIG_RELOAD)
        IslandChannel.send(app, IslandConfig.PKG_NOTICEFLOW, ChannelKeys.CONFIG_RELOAD)
        // 1.0.22：名单变更显式直推系统界面（广播之外的载荷通道；宿主重启后
        // 经 LIST_REQUEST 校验-推送闭环回收，广播被 Greezer 拦截亦可自愈）
        ListSyncCoordinator.pushToHost(app)
        // D 级（verbose-only，1.0.20 起）：保存链路终点诊断，与宿主侧
        // "config reloaded" 行配对验证动态生效链路（开启详细日志后可见）。
        IslandLog.d(
            "effective lists refreshed: white=${white.size} [${white.joinToString(",")}], " +
                "black=${black.size} [${black.joinToString(",")}] -> mirror + broadcast(systemui/noticeflow)"
        )
    }

    /** 保存为个性名单（创建文件，不勾选启用） */
    fun saveAsPersonal(
        context: Context,
        name: String,
        type: ListType,
        pkgs: List<String>
    ): NamedList = IslandListFiles.create(context, name, type, pkgs)
}
