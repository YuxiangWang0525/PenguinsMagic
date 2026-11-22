package me.riverfrozer.penguinsmagic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberNormalizerTest {

    @Test
    fun `separators are stripped`() {
        assertEquals("01088776655", NumberNormalizer.normalize("010-8877-6655"))
        assertEquals("01088776655", NumberNormalizer.normalize("010 8877 6655"))
        assertEquals("01088776655", NumberNormalizer.normalize("(010)88776655"))
        assertEquals("01088776655", NumberNormalizer.normalize(" 010.8877/6655 "))
    }

    @Test
    fun `plus is only legal as the very first character`() {
        assertEquals("+8613000001234", NumberNormalizer.normalize("+8613000001234"))
        assertNull(NumberNormalizer.normalize("130+00001234"))
        assertNull(NumberNormalizer.normalize("+86+1300000123"))
        assertNull(NumberNormalizer.normalize("(+86)13000001234"))
    }

    @Test
    fun `letters and wrong lengths are rejected`() {
        assertNull(NumberNormalizer.normalize("1300000123a"))
        assertNull(NumberNormalizer.normalize(""))
        assertNull(NumberNormalizer.normalize("   "))
        assertNull(NumberNormalizer.normalize("1234"))
        assertNull(NumberNormalizer.normalize("1".repeat(25)))
        assertEquals("12345", NumberNormalizer.normalize("12345"))
    }

    @Test
    fun `plus86 and domestic eleven digits are each other's candidate`() {
        assertEquals(
            listOf("+8613000001234", "13000001234"),
            NumberNormalizer.queryVariants("+86 130 0000 1234"),
        )
        assertEquals(
            listOf("13000001234", "+8613000001234"),
            NumberNormalizer.queryVariants("130-0000-1234"),
        )
    }

    @Test
    fun `landline and foreign numbers get a single query`() {
        assertEquals(listOf("01088776655"), NumberNormalizer.queryVariants("010-8877-6655"))
        assertEquals(listOf("+12025550123"), NumberNormalizer.queryVariants("+1 202 555 0123"))
        assertEquals(emptyList<String>(), NumberNormalizer.queryVariants("abc"))
    }

    @Test
    fun `display drops the country code`() {
        assertEquals("13000001234", NumberNormalizer.forDisplay("+8613000001234"))
        assertEquals("13000001234", NumberNormalizer.forDisplay("+86 130-0000-1234"))
        assertEquals("01088776655", NumberNormalizer.forDisplay("010-8877-6655"))
        assertEquals("abc", NumberNormalizer.forDisplay("abc"))
    }

    @Test
    fun `matching form drops the country code so whitelist and rules agree`() {
        assertEquals("13000001234", NumberNormalizer.forMatching("+8613000001234"))
        assertEquals("13000001234", NumberNormalizer.forMatching("130-0000-1234"))
        assertEquals("+12025550123", NumberNormalizer.forMatching("+1 202 555 0123"))
        assertNull(NumberNormalizer.forMatching("abc"))
        assertNull(NumberNormalizer.forMatching(null))
    }
}