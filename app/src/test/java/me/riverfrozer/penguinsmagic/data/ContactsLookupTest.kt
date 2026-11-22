package me.riverfrozer.penguinsmagic.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 通讯录白名单能不能认出来，全看这些候选写法够不够；查询本身交给系统提供程序，不在单测里模拟。 */
class ContactsLookupTest {

    @Test
    fun `keeps the raw caller id first so the provider normalizer gets a chance`() {
        assertEquals(
            listOf("+8613000001234", "13000001234"),
            ContactsLookup.lookupCandidates("+8613000001234"),
        )
    }

    @Test
    fun `adds the international form for a domestic number`() {
        assertEquals(
            listOf("13000001234", "+8613000001234"),
            ContactsLookup.lookupCandidates("13000001234"),
        )
    }

    @Test
    fun `strips separators when building variants but keeps the raw form`() {
        assertEquals(
            listOf("130-0000-1234", "13000001234", "+8613000001234"),
            ContactsLookup.lookupCandidates("130-0000-1234"),
        )
    }

    @Test
    fun `trims the caller id and drops a blank one`() {
        assertEquals(listOf("+862112345678"), ContactsLookup.lookupCandidates("  +862112345678 "))
        assertEquals(emptyList<String>(), ContactsLookup.lookupCandidates("   "))
    }

    @Test
    fun `falls back to the raw form when the number cannot be normalized into variants`() {
        // 0086 国际前缀写法归一化后凑不上 +86 规则，只查原样；含字母的号码同理，交给提供程序自己判断
        assertEquals(listOf("008613000001234"), ContactsLookup.lookupCandidates("008613000001234"))
        assertEquals(listOf("13000call"), ContactsLookup.lookupCandidates("13000call"))
    }
}