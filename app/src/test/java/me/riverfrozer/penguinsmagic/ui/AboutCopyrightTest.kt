package me.riverfrozer.penguinsmagic.ui

import java.util.Calendar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutCopyrightTest {

    @Test
    fun `the easter egg shows on october fifth`() {
        assertTrue(isBirthday(Calendar.OCTOBER, 5))
        assertTrue(isBirthday(9, 5))
    }

    @Test
    fun `the easter egg stays hidden on every other day`() {
        assertFalse(isBirthday(Calendar.OCTOBER, 4))
        assertFalse(isBirthday(Calendar.OCTOBER, 6))
        assertFalse(isBirthday(Calendar.NOVEMBER, 5))
        // Calendar.MONTH 是 0 起的：把 10 当成十月就会在 11 月 5 日误弹
        assertFalse(isBirthday(10, 5))
        assertFalse(isBirthday(Calendar.JANUARY, 1))
    }
}