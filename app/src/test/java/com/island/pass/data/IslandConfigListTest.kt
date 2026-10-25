package com.island.pass.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上岛名单序列化与准入判定单测（纯 Kotlin，无 Android 依赖）。
 *
 * 1.0.9 起白名单与黑名单相互独立、可叠加：
 *  - 黑名单优先拦截；
 *  - 白名单留空 = 不限制；
 *  - 白名单非空 = 仅名单内放行。
 *
 * 1.0.10 起黑白名单可单独启停：停用的名单不参与判定（两个都停用 = 不限制）。
 */
class IslandConfigListTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val pkgs = setOf("com.tencent.mm", "com.android.systemui", "com.spark.noticeflow")
        val encoded = IslandConfig.encodeList(pkgs)
        assertEquals(pkgs, IslandConfig.decodeList(encoded))
    }

    @Test
    fun decodeHandlesBlankAndNull() {
        assertEquals(emptySet<String>(), IslandConfig.decodeList(null))
        assertEquals(emptySet<String>(), IslandConfig.decodeList(""))
        assertEquals(emptySet<String>(), IslandConfig.decodeList("\n\n"))
        assertEquals(setOf("a.b"), IslandConfig.decodeList(" a.b \n"))
    }

    @Test
    fun encodeSkipsBlank() {
        assertEquals("a.b", IslandConfig.encodeList(setOf("a.b", "", "  ")))
    }

    @Test
    fun emptyListsAllowEverything() {
        val s = IslandSettings()
        assertTrue(s.allowsIsland("a.b"))
        assertTrue(s.allowsIsland("x.y"))
    }

    @Test
    fun blacklistDeniesListed() {
        val s = IslandSettings(blacklist = setOf("a.b"))
        assertFalse(s.allowsIsland("a.b"))
        assertTrue(s.allowsIsland("x.y"))
    }

    @Test
    fun whitelistAllowsOnlyListed() {
        val s = IslandSettings(whitelist = setOf("a.b"))
        assertTrue(s.allowsIsland("a.b"))
        assertFalse(s.allowsIsland("x.y"))
    }

    @Test
    fun emptyWhitelistMeansNoRestriction() {
        // 白名单留空 = 不限制（与旧版「白名单+空名单=全部关闭」语义不同）
        val s = IslandSettings(whitelist = emptySet(), blacklist = emptySet())
        assertTrue(s.allowsIsland("any.pkg"))
    }

    @Test
    fun blacklistTakesPrecedenceOverWhitelist() {
        // 同一包名同时出现在黑白名单：黑名单优先，一律拒绝
        val s = IslandSettings(whitelist = setOf("a.b"), blacklist = setOf("a.b"))
        assertFalse(s.allowsIsland("a.b"))
    }

    @Test
    fun listsOverlayIndependently() {
        // 白/黑名单独立叠加：黑名单拦截优先，其余按白名单放行
        val s = IslandSettings(
            whitelist = setOf("a.b", "c.d"),
            blacklist = setOf("c.d")
        )
        assertTrue(s.allowsIsland("a.b"))
        assertFalse(s.allowsIsland("c.d"))
        assertFalse(s.allowsIsland("e.f"))
    }

    @Test
    fun bothListsEnabledByDefault() {
        // 默认双启用：存量用户升级后语义不变
        val s = IslandSettings()
        assertTrue(s.whitelistEnabled)
        assertTrue(s.blacklistEnabled)
    }

    @Test
    fun disabledBlacklistDoesNotDeny() {
        // 选择性停用：黑名单停用后，名单内应用不再被拦截
        val s = IslandSettings(blacklist = setOf("a.b"), blacklistEnabled = false)
        assertTrue(s.allowsIsland("a.b"))
    }

    @Test
    fun disabledWhitelistDoesNotRestrict() {
        // 选择性停用：白名单停用后，名单外应用不再被限制
        val s = IslandSettings(whitelist = setOf("a.b"), whitelistEnabled = false)
        assertTrue(s.allowsIsland("x.y"))
    }

    @Test
    fun disabledWhitelistStillDeniedByActiveBlacklist() {
        // 白名单停用不影响黑名单：黑名单仍可拦截
        val s = IslandSettings(
            whitelist = setOf("a.b"),
            whitelistEnabled = false,
            blacklist = setOf("a.b")
        )
        assertFalse(s.allowsIsland("a.b"))
    }

    @Test
    fun bothDisabledAllowsEverything() {
        // 两个开关都关闭 = 名单功能整体不生效，一律放行
        val s = IslandSettings(
            whitelist = setOf("a.b"),
            whitelistEnabled = false,
            blacklist = setOf("a.b"),
            blacklistEnabled = false
        )
        assertTrue(s.allowsIsland("a.b"))
        assertTrue(s.allowsIsland("x.y"))
    }
}
