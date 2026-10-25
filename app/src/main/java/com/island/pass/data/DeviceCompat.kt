package com.island.pass.data

import android.os.Build

/**
 * 设备兼容性判定。
 *
 * 支持范围：Android 15（API 35）/ Android 16（API 36）+ 澎湃OS 3.300 及以上。
 *
 * 澎湃OS 版本多源探测（实证：Redmi Note 14 Pro+，OS3.0.303.0.WOPCNXM）：
 *  - `ro.mi.os.version.name` 官方属性实测仅返回简称 "OS3.0"，缺补丁段，单独无法判定 3.3xx；
 *  - 完整版本串由 `Build.VERSION.INCREMENTAL`、`ro.*.build.version.incremental`
 *    及系统指纹 `Build.FINGERPRINT`（".../OS3.0.303.0.WOPCNXM:user/release-keys"）提供。
 *
 * 判定策略：汇总全部候选串，任一匹配即支持；展示取最完整的匹配串。
 * 解析规则见 [HyperOsVersionParser]（简称式第二段 ≥300 / 完整式第三段 ≥300）。
 */
object DeviceCompat {

    val isSupportedAndroid: Boolean get() = Build.VERSION.SDK_INT in 35..36

    val androidDesc: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /** 全部候选版本串（多源、去重、去空白） */
    private val versionCandidates: List<String> by lazy {
        buildList {
            add(readSystemProperty("ro.mi.os.version.name"))
            add(readSystemProperty("ro.miui.ui.version.name"))
            add(readSystemProperty("ro.mi.os.version.incremental"))
            add(readSystemProperty("ro.mi.os.version.code"))
            add(readSystemProperty("ro.build.version.incremental"))
            add(readSystemProperty("ro.system.build.version.incremental"))
            add(readSystemProperty("ro.vendor.build.version.incremental"))
            add(Build.VERSION.INCREMENTAL.orEmpty())
            add(Build.DISPLAY.orEmpty())
            add(HyperOsVersionParser.extractFromFingerprint(Build.FINGERPRINT.orEmpty()))
        }.filter { it.isNotBlank() }.distinct()
    }

    /** 生效的澎湃OS 版本串（最完整的匹配串） */
    val hyperOsVersion: String by lazy {
        HyperOsVersionParser.pickVersion(versionCandidates).orEmpty()
    }

    /** 澎湃OS 描述，如 "澎湃OS OS3.0.303.0.WOPCNXM" */
    val hyperOsDesc: String
        get() = if (hyperOsVersion.isBlank()) "未检测到澎湃OS" else "澎湃OS $hyperOsVersion"

    /** 是否为受支持的澎湃OS 3.300 系列 */
    val isSupportedHyperOs: Boolean
        get() = versionCandidates.any { HyperOsVersionParser.matchesHyperOs300(it) }

    /** 综合判定：Android 15/16 且澎湃OS 3.300 */
    val isSupported: Boolean
        get() = isSupportedAndroid && isSupportedHyperOs

    /** 设备摘要（日志/界面用） */
    fun deviceSummary(): String = "$androidDesc · $hyperOsDesc"

    private fun readSystemProperty(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as? String ?: ""
    }.getOrNull() ?: ""
}
