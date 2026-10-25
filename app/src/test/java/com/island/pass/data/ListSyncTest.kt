package com.island.pass.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 名单同步协议单测（1.0.22 建立，1.0.23 扩展为全功能字段；纯 Kotlin，无 Android 依赖）。
 *
 * 覆盖：
 *  - 载荷编解码 round-trip 与脏数据兜底；
 *  - 指纹确定性 / 排序无关性 / 字段敏感性（不一致必须可检出，含 1.0.23 新字段）；
 *  - applyTo 替换名单判定字段与功能开关（sdkMapping/bypass），保留 verbose 等纯日志字段；
 *  - fingerprintOf 与 fingerprint 口径一致（宿主回报与模块本地同源可比）。
 */
class ListSyncTest {

    private val sample = ListSync.Payload(
        whitelist = setOf("com.tencent.mm", "com.android.systemui"),
        blacklist = setOf("com.spark.noticeflow"),
        whitelistEnabled = true,
        blacklistEnabled = false,
        sdkMapping = true,
        bypass = false
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val decoded = ListSync.decode(ListSync.encode(sample))
        assertNotNull(decoded)
        assertEquals(sample, decoded)
    }

    @Test
    fun encodeIsOrderIndependentOnWire() {
        val a = ListSync.encode(sample)
        val b = ListSync.encode(
            sample.copy(whitelist = sample.whitelist.reversed().toSet())
        )
        assertEquals(a, b)
    }

    @Test
    fun decodeRejectsGarbage() {
        assertNull(ListSync.decode(null))
        assertNull(ListSync.decode(""))
        assertNull(ListSync.decode("not-json"))
        assertNull(ListSync.decode("{\"white\":123}"))
        // 缺名单字段一律拒绝（绝不静默当空名单——畸形推送会清空宿主名单）
        assertNull(ListSync.decode("{\"sm\":true,\"bp\":true}"))
    }

    @Test
    fun decodeToleratesMissingFlags() {
        // 缺省 we/be/sm/bp 视为启用/开启（与 IslandConfig 默认值一致）
        val p = ListSync.decode("{\"white\":[\"a.b\"],\"black\":[]}")
        assertNotNull(p)
        assertTrue(p!!.whitelistEnabled)
        assertTrue(p.blacklistEnabled)
        assertTrue(p.sdkMapping)
        assertTrue(p.bypass)
        assertEquals(setOf("a.b"), p.whitelist)
    }

    @Test
    fun fingerprintIsDeterministic() {
        assertEquals(ListSync.fingerprint(sample), ListSync.fingerprint(sample))
    }

    @Test
    fun fingerprintIsOrderIndependent() {
        val shuffled = sample.copy(whitelist = sample.whitelist.reversed().toSet())
        assertEquals(ListSync.fingerprint(sample), ListSync.fingerprint(shuffled))
    }

    @Test
    fun fingerprintDetectsWhitelistChange() {
        val changed = sample.copy(whitelist = sample.whitelist + "com.extra.app")
        assertFalse(ListSync.fingerprint(sample) == ListSync.fingerprint(changed))
    }

    @Test
    fun fingerprintDetectsBlacklistChange() {
        val changed = sample.copy(blacklist = emptySet())
        assertFalse(ListSync.fingerprint(sample) == ListSync.fingerprint(changed))
    }

    @Test
    fun fingerprintDetectsFlagChange() {
        val changed = sample.copy(whitelistEnabled = false)
        assertFalse(ListSync.fingerprint(sample) == ListSync.fingerprint(changed))
        val changed2 = sample.copy(blacklistEnabled = true)
        assertFalse(ListSync.fingerprint(sample) == ListSync.fingerprint(changed2))
    }

    @Test
    fun fingerprintDetectsSdkMappingChange() {
        // 1.0.23：开关字段必须参与指纹——LIST_VERIFY 闭环依赖它检出开关陈旧
        val changed = sample.copy(sdkMapping = false)
        assertFalse(ListSync.fingerprint(sample) == ListSync.fingerprint(changed))
    }

    @Test
    fun fingerprintDetectsBypassChange() {
        val changed = sample.copy(bypass = true)
        assertFalse(ListSync.fingerprint(sample) == ListSync.fingerprint(changed))
    }

    @Test
    fun fingerprintOfMatchesPayloadFingerprint() {
        val settings = IslandSettings(
            bypassWhitelist = sample.bypass,
            sdkMapping = sample.sdkMapping,
            whitelist = sample.whitelist,
            blacklist = sample.blacklist,
            whitelistEnabled = sample.whitelistEnabled,
            blacklistEnabled = sample.blacklistEnabled
        )
        assertEquals(ListSync.fingerprint(sample), ListSync.fingerprintOf(settings))
    }

    @Test
    fun applyToReplacesListAndFlagFields() {
        val settings = IslandSettings(
            bypassWhitelist = true,
            sdkMapping = false,
            verboseLog = true,
            debugMode = true,
            whitelist = setOf("stale.a"),
            blacklist = setOf("stale.b"),
            whitelistEnabled = false,
            blacklistEnabled = false
        )
        val merged = ListSync.applyTo(settings, sample)
        // 名单与开关字段被推送载荷覆盖（1.0.23：开关亦以本通道为权威）
        assertEquals(sample.whitelist, merged.whitelist)
        assertEquals(sample.blacklist, merged.blacklist)
        assertEquals(sample.whitelistEnabled, merged.whitelistEnabled)
        assertEquals(sample.blacklistEnabled, merged.blacklistEnabled)
        assertEquals(sample.sdkMapping, merged.sdkMapping)
        assertEquals(sample.bypass, merged.bypassWhitelist)
        // 纯日志字段原样保留（不参与判定，走 CONFIG_RELOAD 兜底）
        assertTrue(merged.verboseLog)
        assertTrue(merged.debugMode)
    }

    @Test
    fun appliedPayloadDrivesGateDecision() {
        // 白名单非空 = 仅名单内放行；推送后宿主门禁按新名单判定
        // （sample 的 be=false，黑名单不拦截；noticeflow 未命中白名单故被拒）
        val merged = ListSync.applyTo(IslandSettings(), sample)
        assertTrue(merged.allowsIsland("com.tencent.mm"))
        assertFalse(merged.allowsIsland("com.spark.noticeflow"))
    }

    @Test
    fun appliedPayloadBlacklistEnabledDenies() {
        val payload = sample.copy(blacklistEnabled = true)
        val merged = ListSync.applyTo(IslandSettings(), payload)
        assertFalse(merged.allowsIsland("com.spark.noticeflow"))
        assertTrue(merged.allowsIsland("com.tencent.mm"))
        assertFalse(merged.allowsIsland("com.unknown.app")) // 白名单非空且未命中
    }
}
