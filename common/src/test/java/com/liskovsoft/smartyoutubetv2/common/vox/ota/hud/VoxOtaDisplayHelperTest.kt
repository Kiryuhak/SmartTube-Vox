package com.liskovsoft.smartyoutubetv2.common.vox.ota.hud

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VoxOtaDisplayHelperTest {

    @Test
    fun testFormatFileSizeDoesNotReturn368MbOnZeroOrNegative() {
        val zeroStr = VoxOtaDisplayHelper.formatFileSize(0L)
        val negStr = VoxOtaDisplayHelper.formatFileSize(-1L)

        assertFalse("formatFileSize(0) should never return 368 МБ", zeroStr.contains("368"))
        assertFalse("formatFileSize(-1) should never return 368 МБ", negStr.contains("368"))
        assertEquals("—", zeroStr)
        assertEquals("—", negStr)

        val sixtySixMb = VoxOtaDisplayHelper.formatFileSize(69_206_016L) // ~66 MB
        assertEquals("66 МБ", sixtySixMb)
    }

    @Test
    fun testFormatProgressSizeStrictlyRespectsTotalBytes() {
        // Known total (e.g. 15 MB of 66 MB)
        val progressKnown = VoxOtaDisplayHelper.formatProgressSize(15L * 1024 * 1024, 66L * 1024 * 1024)
        assertEquals("15 МБ / 66 МБ", progressKnown)

        // Unknown / zero total (e.g. chunked streaming without Content-Length)
        val progressZeroTotal = VoxOtaDisplayHelper.formatProgressSize(15L * 1024 * 1024, 0L)
        assertFalse("Unknown total progress must not show 368 МБ", progressZeroTotal.contains("368"))
        assertFalse("Unknown total progress must not have slash delimiter", progressZeroTotal.contains("/"))
        assertEquals("15 МБ", progressZeroTotal)

        val progressNegTotal = VoxOtaDisplayHelper.formatProgressSize(20L * 1024 * 1024, -1L)
        assertFalse("Negative total progress must not show 368 МБ", progressNegTotal.contains("368"))
        assertEquals("20 МБ", progressNegTotal)
    }

    @Test
    fun testFormatSpeed() {
        val speedZero = VoxOtaDisplayHelper.formatSpeed(0.0)
        assertEquals("0,0 МБ/с", speedZero)

        val speedKb = VoxOtaDisplayHelper.formatSpeed(50.0 * 1024)
        assertEquals("50 КБ/с", speedKb)

        val speedMb = VoxOtaDisplayHelper.formatSpeed(6.8 * 1024 * 1024)
        assertTrue(speedMb.contains("6,8") || speedMb.contains("6.8"))
        assertTrue(speedMb.contains("МБ/с"))
    }
}
