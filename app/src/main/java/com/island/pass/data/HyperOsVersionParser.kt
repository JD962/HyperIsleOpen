package com.island.pass.data

/**
 * 澎湃OS 版本串解析（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 实证样本（Redmi Note 14 Pro+，HyperOS 3.0.303.0.WOPCNXM）：
 *  - `ro.mi.os.version.name` 官方属性实测仅返回简称 "OS3.0"（缺补丁段，单独无法判定 3.3xx）；
 *  - 完整版本串存在于 `Build.VERSION.INCREMENTAL` 与系统指纹
 *    `Redmi/methyst/amethyst:16/BP2A.250605.031.A3/OS3.0.303.0.WOPCNXM:user/release-keys`。
 *
 * 双形态匹配规则（"3.300 及以上"）：
 *  - 简称式 `OS3.300.x`  → 数字段 [3, 300, ...]，第二段 ≥ 300；
 *  - 完整式 `OS3.0.303.x` → 数字段 [3, 0, 303, ...]，第二段为 0 且第三段 ≥ 300。
 */
object HyperOsVersionParser {

    /** 从系统指纹中提取 OS 版本段（如 "...:16/BP2A.../OS3.0.303.0.WOPCNXM:user" → "OS3.0.303.0"） */
    fun extractFromFingerprint(fingerprint: String): String {
        if (fingerprint.isBlank()) return ""
        return Regex("(?i)\\bOS(\\d+(?:\\.\\d+)+)").find(fingerprint)?.value ?: ""
    }

    /** 判断单个候选串是否满足 HyperOS 3.300 及以上 */
    fun matchesHyperOs300(raw: String): Boolean {
        if (raw.isBlank()) return false
        val nums = Regex("\\d+").findAll(raw)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
        if (nums.size < 2 || nums[0] != 3) return false
        if (nums[1] >= 300) return true
        if (nums[1] == 0 && nums.size >= 3 && nums[2] >= 300) return true
        return false
    }

    /**
     * 多源候选合并判定：
     *  - 任一候选匹配即支持（解决简称属性缺段、被其他源补齐的场景）；
     *  - 返回最完整的匹配串用于展示；无匹配时返回最具 HyperOS 特征的候选或空串。
     */
    fun pickVersion(candidates: List<String>): String? {
        val cleaned = candidates.filter { it.isNotBlank() }.distinct()
        val matched = cleaned.filter { matchesHyperOs300(it) }
        if (matched.isNotEmpty()) {
            return matched.maxByOrNull { it.count { c -> c == '.' || c.isDigit() } }
        }
        return cleaned.firstOrNull { raw ->
            raw.startsWith("OS", ignoreCase = true) || raw.startsWith("V", ignoreCase = true)
        } ?: cleaned.firstOrNull()
    }
}
