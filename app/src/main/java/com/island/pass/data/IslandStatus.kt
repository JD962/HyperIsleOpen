package com.island.pass.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据通讯桥（YukiHookDataChannel）协议键。
 *
 * 方向约定：
 *  - QUERY：模块 -> 宿主，请求注入状态（无值监听）；
 *  - STATUS：宿主 -> 模块，回报 [HostStatus] JSON；
 *  - RESTART：模块 -> 宿主，请求重启自身进程（无值监听）；
 *  - CONFIG_RELOAD：模块 -> 宿主，配置已变更（黑白名单/功能开关），
 *    宿主重读偏好快照实现热更新，无需重启作用域应用；
 *  - LIST_REQUEST：宿主 -> 模块，宿主进程（重）启动后请求生效名单（1.0.22）；
 *  - LIST_PUSH：模块 -> 宿主，直推生效名单载荷（见 [ListSync]）；
 *  - LIST_VERIFY：模块 -> 宿主，携带本地名单指纹，请求宿主比对；
 *  - LIST_VERIFY_REPLY：宿主 -> 模块，回报宿主内存名单指纹。
 *
 * 名单同步协议（1.0.22，对应用户明确的「重启后重推 + 一致性校验」设计）：
 * 宿主重启后内存名单清空 → 发 LIST_REQUEST → 模块先发 LIST_VERIFY 比对指纹 →
 * 一致则同步收敛（停止），不一致（或 3s 无回报）则 LIST_PUSH 直推覆盖；
 * 模块名单变更时 refresh 主动 LIST_PUSH；模块启动/UI 前台时主动 LIST_VERIFY 自愈。
 */
object ChannelKeys {
    const val QUERY = "island_query"
    const val STATUS = "island_status"
    const val RESTART = "island_restart"
    const val CONFIG_RELOAD = "island_config_reload"
    const val LIST_REQUEST = "island_list_request"
    const val LIST_PUSH = "island_list_push"
    const val LIST_VERIFY = "island_list_verify"
    const val LIST_VERIFY_REPLY = "island_list_verify_reply"
}

/** 单个钩子的安装结果 */
data class HookResult(val name: String, val ok: Boolean, val detail: String = "")

/**
 * 宿主进程的注入状态快照（由 Hooker 侧构建，模块侧解析）。
 */
data class HostStatus(
    val process: String,
    val timestamp: Long,
    val androidDesc: String,
    val hyperOsDesc: String,
    val compat: Boolean,
    val hooks: List<HookResult>,
    val errors: List<String>
) {
    /** 全部钩子安装成功且无错误 */
    val allOk: Boolean get() = errors.isEmpty() && hooks.isNotEmpty() && hooks.all { it.ok }

    /** 失败/错误的钩子数量 */
    val failedCount: Int get() = hooks.count { !it.ok } + errors.size

    fun toJson(): String = JSONObject().apply {
        put("process", process)
        put("ts", timestamp)
        put("android", androidDesc)
        put("hyper", hyperOsDesc)
        put("compat", compat)
        put("hooks", JSONArray().apply {
            hooks.forEach { h ->
                put(JSONObject().apply {
                    put("name", h.name)
                    put("ok", h.ok)
                    put("detail", h.detail)
                })
            }
        })
        put("errors", JSONArray().apply { errors.forEach { put(it) } })
    }.toString()

    companion object {
        fun fromJson(json: String): HostStatus? = runCatching {
            val o = JSONObject(json)
            val hooksArr = o.getJSONArray("hooks")
            val hooks = (0 until hooksArr.length()).map { i ->
                val h = hooksArr.getJSONObject(i)
                HookResult(h.getString("name"), h.getBoolean("ok"), h.optString("detail"))
            }
            val errArr = o.getJSONArray("errors")
            val errors = (0 until errArr.length()).map { errArr.getString(it) }
            HostStatus(
                process = o.getString("process"),
                timestamp = o.optLong("ts"),
                androidDesc = o.optString("android"),
                hyperOsDesc = o.optString("hyper"),
                compat = o.optBoolean("compat"),
                hooks = hooks,
                errors = errors
            )
        }.getOrNull()
    }
}
