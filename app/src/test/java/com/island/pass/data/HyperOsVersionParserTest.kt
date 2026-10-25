package com.island.pass.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 澎湃OS 版本解析实证测试。
 *
 * 覆盖 Redmi Note 14 Pro+ 实测样本：
 *  - `ro.mi.os.version.name` = "OS3.0"（简称，缺补丁段，单独判定必须失败）；
 *  - 指纹 / INCREMENTAL = "OS3.0.303.0.WOPCNXM"（完整串，必须兜底成功）。
 */
class HyperOsVersionParserTest {

    @Test
    fun `完整式 3-0-3xx 匹配`() {
        assertTrue(HyperOsVersionParser.matchesHyperOs300("OS3.0.303.0.WOPCNXM"))
        assertTrue(HyperOsVersionParser.matchesHyperOs300("OS3.0.300.10.WPCCNXM"))
        assertTrue(HyperOsVersionParser.matchesHyperOs300("OS3.0.306.0.WPCCNXM"))
    }

    @Test
    fun `简称式 3-3xx 匹配`() {
        assertTrue(HyperOsVersionParser.matchesHyperOs300("V3.300.10.WPCCNXM"))
        assertTrue(HyperOsVersionParser.matchesHyperOs300("OS3.301.0"))
    }

    @Test
    fun `单独简称 OS3-0 不可判定`() {
        assertFalse(HyperOsVersionParser.matchesHyperOs300("OS3.0"))
        assertFalse(HyperOsVersionParser.matchesHyperOs300("OS3.0.299.0.WOPCNXM"))
    }

    @Test
    fun `其他大版本与非 HyperOS 拒绝`() {
        assertFalse(HyperOsVersionParser.matchesHyperOs300("OS2.0.303.0.WOPCNXM"))
        assertFalse(HyperOsVersionParser.matchesHyperOs300("OS4.0.100.0.WOPCNXM"))
        assertFalse(HyperOsVersionParser.matchesHyperOs300("V16.03.251211.r"))
        assertFalse(HyperOsVersionParser.matchesHyperOs300(""))
    }

    @Test
    fun `指纹提取完整版本段`() {
        assertEquals(
            "OS3.0.303.0",
            HyperOsVersionParser.extractFromFingerprint(
                "Redmi/methyst/amethyst:16/BP2A.250605.031.A3/OS3.0.303.0.WOPCNXM:user/release-keys"
            )
        )
        assertEquals(
            "OS3.0.300.10",
            HyperOsVersionParser.extractFromFingerprint(
                "Redmi/.../OS3.0.300.10.WPCCNXM:user/release-keys"
            )
        )
        assertEquals("", HyperOsVersionParser.extractFromFingerprint(""))
    }

    @Test
    fun `多源合并_简称属性加指纹完整串应判定支持`() {
        val candidates = listOf(
            "OS3.0",                                  // ro.mi.os.version.name 实测值
            "OS3.0.303.0.WOPCNXM",                    // INCREMENTAL / 指纹提取
        )
        val picked = HyperOsVersionParser.pickVersion(candidates)
        assertEquals("OS3.0.303.0.WOPCNXM", picked)
        assertTrue(candidates.any { HyperOsVersionParser.matchesHyperOs300(it) })
    }

    @Test
    fun `多源合并_仅有简称时展示不误判为支持`() {
        val candidates = listOf("OS3.0", "V16.03.251211.r")
        val picked = HyperOsVersionParser.pickVersion(candidates)
        assertEquals("OS3.0", picked)
        assertFalse(candidates.any { HyperOsVersionParser.matchesHyperOs300(it) })
    }

    @Test
    fun `多源合并_OS2 简称与 OS2 完整串均拒绝`() {
        assertFalse(
            listOf("OS2.0", "OS2.0.303.0.WOPCNXM")
                .any { HyperOsVersionParser.matchesHyperOs300(it) }
        )
    }
}
