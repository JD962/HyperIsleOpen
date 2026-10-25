package com.island.pass.data

/**
 * 生效配置同步载荷与指纹（1.0.22 建立协议，1.0.23 权威化）。
 *
 * 背景（用户明确的同步设计）：宿主进程重启后内存中的名单快照即丢失，
 * 远程偏好文件虽可兜底，但广播热更新存在被 MIUI Greezer 拦截/延迟、
 * 模块 App 进程死亡期间无推送方等 gap。为此在 CHANNEL 广播之上建立显式
 * 名单同步协议：
 *  - 宿主重启（attachHostContext）发 LIST_REQUEST；
 *  - 模块 LIST_VERIFY 携带本地指纹，宿主回报自身指纹，一致即收敛；
 *  - 不一致（或超时无回报）模块 LIST_PUSH 直推本载荷，宿主覆盖内存快照。
 *
 * 1.0.23 权威化（根因修复）：该设备上远程偏好镜像链路未生效
 * （service 未绑 / commit 失败时无可见日志），宿主 reloadSettings 用
 * 陈旧或空的远程快照**整本替换**内存配置，把 LIST_PUSH 灌入的值冲回
 * 陈旧态——实证表现：systemui 每次 push 均为 white=0->N（推完又被
 * 重置为 0）、noticeflow 读陈旧 sdk_mapping=false 不装钩（探测无回报
 * 误报"未激活"）、开关"必须进名单编辑页才刷新、关了又恢复"。
 *
 * 修复：LIST_PUSH 成为宿主侧**权威数据源**——
 *  - 载荷从纯名单字段扩展为「名单判定字段 + 功能开关」（sdkMapping /
 *    bypass），开关切换（putBooleanPref）同样即时直推，不再依赖镜像；
 *  - 宿主侧 CONFIG_RELOAD 退化为「reload 触发器 + verbose/debug 兜底」，
 *    不再用远程快照整本替换名单/开关字段（见 SystemUiHooker.reloadSettings
 *    与 NoticeFlowHooker.reloadMapping）；
 *  - 发送侧带 FLAG_RECEIVER_FOREGROUND（Greezer 抗拦截）+ 3s 超时兜底直推，
 *    可达性已被 1.0.22 日志实证（同设备九次 push 全部到达）。
 *
 * 判定职责划分：**放行判定完全在宿主进程完成**（[IslandSettings.allowsIsland]
 * 由 SystemUiHooker 的门禁在 hook 调用链上实时执行）；模块 App 侧只做
 * 「同类名单合并」（全局 ∪ 已启用个性，见 [IslandListManager.refresh]）后
 * 经本协议分发。实况通知宿主仅消费 [Payload.sdkMapping]（不参与名单判定），
 * 名单字段对其无害忽略。
 */
object ListSync {

    /**
     * 生效配置载荷（与 [IslandSettings] 的判定字段 + 功能开关一一对应）。
     *
     * 缺省值即 [IslandConfig.defaults] 口径（decode 对缺失键容错）。
     */
    data class Payload(
        val whitelist: Set<String> = emptySet(),
        val blacklist: Set<String> = emptySet(),
        val whitelistEnabled: Boolean = true,
        val blacklistEnabled: Boolean = true,
        val sdkMapping: Boolean = true,
        val bypass: Boolean = true
    )

    /** 序列化为 JSON（排序保证 wire 格式确定，便于日志比对） */
    fun encode(p: Payload): String = MiniJson.buildObject(
        listOf(
            "white" to p.whitelist.sorted(),
            "black" to p.blacklist.sorted(),
            "we" to p.whitelistEnabled,
            "be" to p.blacklistEnabled,
            "sm" to p.sdkMapping,
            "bp" to p.bypass
        )
    )

    /** 反序列化；结构不符（缺名单字段/类型错误）返回 null（调用方记 W 级通讯错误，绝不静默当空名单） */
    fun decode(raw: String?): Payload? {
        if (raw.isNullOrBlank()) return null
        val map = runCatching { MiniJson.parse(raw) as? Map<String, Any?> }.getOrNull() ?: return null
        val whiteRaw = map["white"] ?: return null
        val blackRaw = map["black"] ?: return null
        if (whiteRaw !is List<*> || blackRaw !is List<*>) return null
        return Payload(
            whitelist = toStringSet(whiteRaw),
            blacklist = toStringSet(blackRaw),
            whitelistEnabled = map["we"] as? Boolean ?: true,
            blacklistEnabled = map["be"] as? Boolean ?: true,
            sdkMapping = map["sm"] as? Boolean ?: true,
            bypass = map["bp"] as? Boolean ?: true
        )
    }

    private fun toStringSet(v: Any?): Set<String> =
        (v as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    /**
     * 配置指纹：FNV-1a 64 位，输入为排序规范化串（顺序无关、跨进程稳定）。
     *
     * 覆盖名单判定字段与功能开关（1.0.23 起与 [Payload] 全字段一致）：
     * 指纹不一致必须可检出——LIST_VERIFY 闭环依赖它决定是否补推。
     */
    fun fingerprint(p: Payload): String {
        val canonical = buildString {
            append(p.whitelist.sorted().joinToString(","))
            append('|')
            append(p.blacklist.sorted().joinToString(","))
            append('|')
            append(if (p.whitelistEnabled) '1' else '0')
            append(if (p.blacklistEnabled) '1' else '0')
            append('|')
            append(if (p.sdkMapping) '1' else '0')
            append(if (p.bypass) '1' else '0')
        }
        // FNV-1a 64：offset basis 14695981039346656037，prime 1099511628211
        var h = -3750763034362895579L
        for (c in canonical) {
            h = h xor c.code.toLong()
            h *= 1099511628211L
        }
        return java.lang.Long.toHexString(h)
    }

    /** 由宿主内存配置快照计算指纹（LIST_VERIFY 回报用；系统界面侧全字段在快照内） */
    fun fingerprintOf(settings: IslandSettings): String = fingerprint(
        Payload(
            whitelist = settings.whitelist,
            blacklist = settings.blacklist,
            whitelistEnabled = settings.whitelistEnabled,
            blacklistEnabled = settings.blacklistEnabled,
            sdkMapping = settings.sdkMapping,
            bypass = settings.bypassWhitelist
        )
    )

    /**
     * 将推送载荷应用到配置快照：替换名单判定字段与功能开关（sdkMapping / bypass），
     * 保留 verbose/debug 等纯日志字段（1.0.23 起开关亦以本通道为权威，
     * 不再走远程偏好镜像——见类 KDoc 根因说明）。
     */
    fun applyTo(settings: IslandSettings, p: Payload): IslandSettings = settings.copy(
        whitelist = p.whitelist,
        whitelistEnabled = p.whitelistEnabled,
        blacklist = p.blacklist,
        blacklistEnabled = p.blacklistEnabled,
        sdkMapping = p.sdkMapping,
        bypassWhitelist = p.bypass
    )
}
