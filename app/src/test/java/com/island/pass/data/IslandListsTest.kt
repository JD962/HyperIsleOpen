package com.island.pass.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 名单模型单测（纯 Kotlin，无 Android 依赖）：
 *  - NamedList JSON round-trip（含特殊字符名单名）；
 *  - 非法 JSON 的容错；
 *  - 名单文件名规范（软件名称 + 时间刻 + 名单类型）；
 *  - 生效名单并集（全局 ∪ 已启用个性名单，保序去重）。
 */
class IslandListsTest {

    private fun sample(
        name: String = "常用社交",
        packages: List<String> = listOf("com.tencent.mm", "com.tencent.mobileqq")
    ) = NamedList(
        id = "HyperIsleOpen_20261024_210944_whitelist",
        name = name,
        type = ListType.WHITELIST,
        packages = packages,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_001_000L
    )

    @Test
    fun jsonRoundTrip() {
        val list = sample()
        val restored = NamedList.fromJson(list.toJson())
        assertEquals(list, restored)
    }

    @Test
    fun jsonRoundTripWithSpecialCharsInName() {
        // 名单名可含引号/反斜杠/换行/制表符/Unicode/Emoji
        val list = sample(name = "引号\"反斜\\换\n行\t制表 😀中文")
        val restored = NamedList.fromJson(list.toJson())
        assertEquals(list.name, restored?.name)
        assertEquals(list.packages, restored?.packages)
    }

    @Test
    fun jsonRoundTripEmptyPackages() {
        val list = sample(packages = emptyList())
        assertEquals(list, NamedList.fromJson(list.toJson()))
    }

    @Test
    fun jsonRoundTripBlacklistType() {
        val list = sample().copy(type = ListType.BLACKLIST)
        assertEquals(list, NamedList.fromJson(list.toJson()))
    }

    @Test
    fun fromJsonRejectsGarbage() {
        assertNull(NamedList.fromJson(""))
        assertNull(NamedList.fromJson("not json"))
        assertNull(NamedList.fromJson("[]"))
        assertNull(NamedList.fromJson("{}"))
        assertNull(NamedList.fromJson("""{"id":"x","type":"unknown","packages":[]}"""))
        assertNull(NamedList.fromJson("""{"type":"whitelist","packages":[]}"""))
    }

    @Test
    fun fromJsonSkipsBlankPackages() {
        val json = """{"id":"a","name":"n","type":"blacklist","packages":["a.b","","  ","c.d"],"createdAt":1,"updatedAt":2}"""
        val list = NamedList.fromJson(json)
        assertEquals(listOf("a.b", "c.d"), list?.packages)
    }

    @Test
    fun baseNameFollowsAppTimestampTypeConvention() {
        val name = IslandListNames.baseName(ListType.WHITELIST, 1_700_000_000_000L)
        assertTrue(name, name.matches(Regex("^HyperIsleOpen_\\d{8}_\\d{6}_whitelist$")))
        val black = IslandListNames.baseName(ListType.BLACKLIST, 1_700_000_000_000L)
        assertTrue(black, black.endsWith("_blacklist"))
    }

    @Test
    fun unionListsMergesGlobalAndPersonalInOrder() {
        val personal = listOf(
            sample().copy(id = "l1", packages = listOf("c.d", "a.b")),
            sample().copy(id = "l2", packages = listOf("a.b", "e.f"))
        )
        val merged = IslandListManager.unionLists(listOf("a.b", "b.c"), personal)
        // 全局在前、个性按传入顺序追加；去重且保序
        assertEquals(listOf("a.b", "b.c", "c.d", "e.f"), merged)
    }

    @Test
    fun unionListsSkipsBlanks() {
        val merged = IslandListManager.unionLists(listOf("a.b", "", "  "), emptyList())
        assertEquals(listOf("a.b"), merged)
    }

    @Test
    fun listTypeFromValue() {
        assertEquals(ListType.WHITELIST, ListType.fromValue("whitelist"))
        assertEquals(ListType.BLACKLIST, ListType.fromValue("blacklist"))
        assertNull(ListType.fromValue("grey"))
        assertNull(ListType.fromValue(null))
    }
}
