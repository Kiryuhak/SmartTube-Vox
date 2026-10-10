package com.liskovsoft.smartyoutubetv2.common.vox.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PlayerClockFormatterTest {

    @Test
    fun testMidnightRolloverHasLeadingZero() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        // Эмуляция старта в 23:52 + 17 минут -> 00:09 следующего дня
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 52)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val targetMs = cal.timeInMillis + 17 * 60 * 1000L // +17 минут
        val result = PlayerClockFormatter.formatEndingTime(targetMs, is24Hour = true)
        assertEquals("00:09", result)
    }

    @Test
    fun testBoundaryTimes() {
        val cal = Calendar.getInstance(TimeZone.getDefault())

        // 00:00
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        assertEquals("00:00", PlayerClockFormatter.formatEndingTime(cal.timeInMillis, is24Hour = true))

        // 00:05
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 5)
        assertEquals("00:05", PlayerClockFormatter.formatEndingTime(cal.timeInMillis, is24Hour = true))

        // 09:05
        cal.set(Calendar.HOUR_OF_DAY, 9)
        cal.set(Calendar.MINUTE, 5)
        assertEquals("09:05", PlayerClockFormatter.formatEndingTime(cal.timeInMillis, is24Hour = true))

        // 12:00
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        assertEquals("12:00", PlayerClockFormatter.formatEndingTime(cal.timeInMillis, is24Hour = true))

        // 23:59
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        assertEquals("23:59", PlayerClockFormatter.formatEndingTime(cal.timeInMillis, is24Hour = true))
    }

    @Test
    fun testNormalizeTimeString() {
        assertEquals("00:09", PlayerClockFormatter.normalizeTimeString("0:09"))
        assertEquals("00:00", PlayerClockFormatter.normalizeTimeString("0:00"))
        assertEquals("09:05", PlayerClockFormatter.normalizeTimeString("9:05"))
        assertEquals("01:23", PlayerClockFormatter.normalizeTimeString("1:23"))
        assertEquals("12:00", PlayerClockFormatter.normalizeTimeString("12:00"))
        assertEquals("23:59", PlayerClockFormatter.normalizeTimeString("23:59"))
        assertEquals("00:09", PlayerClockFormatter.normalizeTimeString("00:09"))
        assertNull(PlayerClockFormatter.normalizeTimeString(null))
        assertNull(PlayerClockFormatter.normalizeTimeString(""))
        assertNull(PlayerClockFormatter.normalizeTimeString("   "))
    }
}
