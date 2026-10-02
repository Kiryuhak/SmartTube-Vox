package com.liskovsoft.smartyoutubetv2.common.vox.badge

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoxBadgeHelperTest {

    @Test
    fun testQualityNormalization() {
        assertEquals("4K", VoxBadgeHelper.normalizeQuality("4K"))
        assertEquals("4K", VoxBadgeHelper.normalizeQuality("4k"))
        assertEquals("4K", VoxBadgeHelper.normalizeQuality("2160p"))
        assertEquals("4K", VoxBadgeHelper.normalizeQuality("2160p60"))
        assertEquals("4K", VoxBadgeHelper.normalizeQuality("4320p"))
        assertEquals("4K", VoxBadgeHelper.normalizeQuality("8K"))

        assertEquals("2K", VoxBadgeHelper.normalizeQuality("2K"))
        assertEquals("2K", VoxBadgeHelper.normalizeQuality("1440p"))
        assertEquals("2K", VoxBadgeHelper.normalizeQuality("1440p60 HDR"))
        assertEquals("2K", VoxBadgeHelper.normalizeQuality("QHD"))

        assertEquals("FHD", VoxBadgeHelper.normalizeQuality("1080p"))
        assertEquals("FHD", VoxBadgeHelper.normalizeQuality("1080p60"))
        assertEquals("FHD", VoxBadgeHelper.normalizeQuality("1080p50"))
        assertEquals("FHD", VoxBadgeHelper.normalizeQuality("FHD"))
        assertEquals("FHD", VoxBadgeHelper.normalizeQuality("Full HD"))

        assertEquals("HD", VoxBadgeHelper.normalizeQuality("720p"))
        assertEquals("HD", VoxBadgeHelper.normalizeQuality("720p60"))
        assertEquals("HD", VoxBadgeHelper.normalizeQuality("HD"))

        assertEquals("SD", VoxBadgeHelper.normalizeQuality("480p"))
        assertEquals("SD", VoxBadgeHelper.normalizeQuality("360p"))
        assertEquals("SD", VoxBadgeHelper.normalizeQuality("240p"))
        assertEquals("SD", VoxBadgeHelper.normalizeQuality("144p"))
        assertEquals("SD", VoxBadgeHelper.normalizeQuality("SD"))
    }

    @Test
    fun testUnknownOrInvalidQualityYieldsNull() {
        assertNull(VoxBadgeHelper.normalizeQuality(null))
        assertNull(VoxBadgeHelper.normalizeQuality(""))
        assertNull(VoxBadgeHelper.normalizeQuality("   "))
        assertNull(VoxBadgeHelper.normalizeQuality("UNKNOWN"))
        assertNull(VoxBadgeHelper.normalizeQuality("0p"))
        assertNull(VoxBadgeHelper.normalizeQuality("AUTO"))
        assertNull(VoxBadgeHelper.normalizeQuality("12:34"))
        assertNull(VoxBadgeHelper.normalizeQuality("NEW"))
        assertNull(VoxBadgeHelper.normalizeQuality("LIVE"))
    }

    @Test
    fun testDimensionsMapping() {
        assertEquals("4K", VoxBadgeHelper.getQualityBadgeFromDimensions(3840, 2160))
        assertEquals("2K", VoxBadgeHelper.getQualityBadgeFromDimensions(2560, 1440))
        assertEquals("FHD", VoxBadgeHelper.getQualityBadgeFromDimensions(1920, 1080))
        assertEquals("HD", VoxBadgeHelper.getQualityBadgeFromDimensions(1280, 720))
        assertEquals("SD", VoxBadgeHelper.getQualityBadgeFromDimensions(854, 480))
        assertNull(VoxBadgeHelper.getQualityBadgeFromDimensions(0, 0))
    }

    @Test
    fun testVideoModelBadgeExtraction() {
        val video4k = Video().apply { badge = "4K" }
        assertEquals("4K", VoxBadgeHelper.getQualityBadge(video4k))

        val videoLocal = Video().apply {
            isLocal = true
            badge = "1080p"
        }
        assertEquals("FHD", VoxBadgeHelper.getQualityBadge(videoLocal))

        val videoWithSecondTitle = Video().apply {
            secondTitle = "4K · BBC News · 1M views"
        }
        assertEquals("4K", VoxBadgeHelper.getQualityBadge(videoWithSecondTitle))

        val videoNormal = Video().apply {
            title = "Regular Video"
            secondTitle = "Channel Name · 2 days ago"
        }
        assertNull(VoxBadgeHelper.getQualityBadge(videoNormal))
    }
}
