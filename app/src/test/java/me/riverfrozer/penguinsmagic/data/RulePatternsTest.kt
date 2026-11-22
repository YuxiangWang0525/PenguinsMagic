package me.riverfrozer.penguinsmagic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulePatternsTest {

    private fun rule(pattern: String, kind: BlockRule.Kind) =
        BlockRule(id = 1L, pattern = pattern, kind = kind)

    @Test
    fun `input is normalized and the country code is dropped for storage`() {
        assertEquals("13000001234", RulePatterns.canonical("+86 130-0000-1234", BlockRule.Kind.NUMBER))
        assertEquals("1300000", RulePatterns.canonical("+861300000", BlockRule.Kind.PREFIX))
        assertEquals("1300000", RulePatterns.canonical("130 0000", BlockRule.Kind.PREFIX))
    }

    @Test
    fun `a prefix shorter than the minimum is refused`() {
        assertNull(RulePatterns.canonical("13000", BlockRule.Kind.PREFIX))
        assertEquals("130000", RulePatterns.canonical("130000", BlockRule.Kind.PREFIX))
        // 整号不受号段位数下限约束，短号本身就是用户要拦的那一个
        assertEquals("12345", RulePatterns.canonical("12345", BlockRule.Kind.NUMBER))
    }

    @Test
    fun `undialable input is refused for both kinds`() {
        assertNull(RulePatterns.canonical(null, BlockRule.Kind.NUMBER))
        assertNull(RulePatterns.canonical("  ", BlockRule.Kind.PREFIX))
        assertNull(RulePatterns.canonical("1300000123a", BlockRule.Kind.NUMBER))
        assertNull(RulePatterns.canonical("1234", BlockRule.Kind.NUMBER))
    }

    @Test
    fun `a number rule blocks exactly that number in either writing`() {
        val number = rule("13000001234", BlockRule.Kind.NUMBER)
        assertTrue(RulePatterns.matches(number, NumberNormalizer.queryVariants("13000001234")))
        assertTrue(RulePatterns.matches(number, NumberNormalizer.queryVariants("+8613000001234")))
        assertFalse(RulePatterns.matches(number, listOf("13000001235")))
        // 整号规则不是号段：同批次的别的号码不该被顺带拦掉
        assertFalse(RulePatterns.matches(rule("1300000", BlockRule.Kind.NUMBER), listOf("13000001234")))
    }

    @Test
    fun `a prefix rule blocks the whole purchased batch`() {
        val prefix = rule("1300000", BlockRule.Kind.PREFIX)
        assertTrue(RulePatterns.matches(prefix, NumberNormalizer.queryVariants("13000001234")))
        assertTrue(RulePatterns.matches(prefix, NumberNormalizer.queryVariants("+86 130-0000-9999")))
        assertTrue(RulePatterns.matches(prefix, listOf("1300000")))
        assertFalse(RulePatterns.matches(prefix, listOf("13000011234")))
        assertFalse(RulePatterns.matches(prefix, listOf("013000001234")))
    }

    @Test
    fun `an international number keeps its own country code`() {
        val prefix = rule("+1202555", BlockRule.Kind.PREFIX)
        assertTrue(RulePatterns.matches(prefix, listOf("+12025550123")))
        assertFalse(RulePatterns.matches(prefix, listOf("+442025550123")))
    }
}