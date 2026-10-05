package com.liskovsoft.smartyoutubetv2.common.vox.badge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxQualityBadgeFormatterTest {

    @Test
    fun testParseAndFormatCanonicalBadges() {
        val b4k = VoxQualityBadgeFormatter.parse("2160p")
        assertNotNull(b4k)
        assertEquals("4K", VoxQualityBadgeFormatter.format(b4k))

        val b2k = VoxQualityBadgeFormatter.parse("1440p60")
        assertNotNull(b2k)
        assertEquals("1440p60", VoxQualityBadgeFormatter.format(b2k))

        val bfhd = VoxQualityBadgeFormatter.parse("1080p")
        assertNotNull(bfhd)
        assertEquals("1080p", VoxQualityBadgeFormatter.format(bfhd))

        val bhd = VoxQualityBadgeFormatter.parse("720p")
        assertNotNull(bhd)
        assertEquals("720p", VoxQualityBadgeFormatter.format(bhd))

        val bsd480 = VoxQualityBadgeFormatter.parse("480p")
        assertNotNull(bsd480)
        assertEquals("480p", VoxQualityBadgeFormatter.format(bsd480))

        val bsd360 = VoxQualityBadgeFormatter.parse("360p")
        assertNotNull(bsd360)
        assertEquals("360p", VoxQualityBadgeFormatter.format(bsd360))
    }

    @Test
    fun testTierOnlyMatches() {
        val b4k = VoxQualityBadgeFormatter.parse("4K")
        assertEquals("4K", VoxQualityBadgeFormatter.format(b4k))

        val bhd = VoxQualityBadgeFormatter.parse("HD")
        assertEquals("720p", VoxQualityBadgeFormatter.format(bhd))

        val bfhd = VoxQualityBadgeFormatter.parse("Full HD")
        assertEquals("1080p", VoxQualityBadgeFormatter.format(bfhd))
    }

    @Test
    fun testIsQualityString() {
        assertTrue(VoxQualityBadgeFormatter.isQualityString("720p"))
        assertTrue(VoxQualityBadgeFormatter.isQualityString("1080p"))
        assertTrue(VoxQualityBadgeFormatter.isQualityString("4K"))
        assertTrue(VoxQualityBadgeFormatter.isQualityString("HD"))
        assertTrue(VoxQualityBadgeFormatter.isQualityString("HD · 720p"))
        assertFalse(VoxQualityBadgeFormatter.isQualityString("LIVE"))
        assertFalse(VoxQualityBadgeFormatter.isQualityString("NEW"))
        assertFalse(VoxQualityBadgeFormatter.isQualityString("12:34"))
        assertFalse(VoxQualityBadgeFormatter.isQualityString("624K views"))
        assertFalse(VoxQualityBadgeFormatter.isQualityString("1.2M views"))
        assertFalse(VoxQualityBadgeFormatter.isQualityString("3 years ago"))
        assertFalse(VoxQualityBadgeFormatter.isQualityString(null))
        assertFalse(VoxQualityBadgeFormatter.isQualityString(""))
    }

    @Test
    fun testFromHeight() {
        assertEquals("4K", VoxQualityBadgeFormatter.formatFromHeight(2160))
        assertEquals("1440p", VoxQualityBadgeFormatter.formatFromHeight(1440))
        assertEquals("1080p", VoxQualityBadgeFormatter.formatFromHeight(1080))
        assertEquals("720p", VoxQualityBadgeFormatter.formatFromHeight(720))
        assertEquals("480p", VoxQualityBadgeFormatter.formatFromHeight(480))
        assertEquals("360p", VoxQualityBadgeFormatter.formatFromHeight(360))
        assertNull(VoxQualityBadgeFormatter.formatFromHeight(0))
    }
}
