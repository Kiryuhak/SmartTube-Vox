package com.liskovsoft.smartyoutubetv2.common.vox.badge

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoxBadgeHelperTest {

    @Test fun durationIsIndependentFromQualityAndExcludedForNonVod() {
        val video = Video().apply { videoId = "test"; badge = "1080p"; durationMs = 243_000 }
        assertEquals("4:03", VoxBadgeHelper.getDurationBadge(video))
        assertEquals("FHD · 1080p", VoxBadgeHelper.getQualityBadge(video))
        video.isLive = true
        assertNull(VoxBadgeHelper.getDurationBadge(video))
        video.isLive = false
        video.isShorts = true
        assertNull(VoxBadgeHelper.getDurationBadge(video))
        video.isShorts = false
        video.videoId = null
        assertNull(VoxBadgeHelper.getDurationBadge(video))
        video.videoId = "test"
        video.durationMs = 0
        video.badge = "12:34"
        assertEquals("12:34", VoxBadgeHelper.getDurationBadge(video))
    }

    @Test
    fun testQualityNormalization() {
        assertEquals("4K · 2160p", VoxBadgeHelper.normalizeQuality("4K"))
        assertEquals("4K · 2160p", VoxBadgeHelper.normalizeQuality("4k"))
        assertEquals("4K · 2160p", VoxBadgeHelper.normalizeQuality("2160p"))
        assertEquals("4K · 2160p", VoxBadgeHelper.normalizeQuality("2160p60"))
        assertEquals("4K · 2160p", VoxBadgeHelper.normalizeQuality("4320p"))
        assertEquals("4K · 2160p", VoxBadgeHelper.normalizeQuality("8K"))

        assertEquals("2K · 1440p", VoxBadgeHelper.normalizeQuality("2K"))
        assertEquals("2K · 1440p", VoxBadgeHelper.normalizeQuality("1440p"))
        assertEquals("2K · 1440p · HDR", VoxBadgeHelper.normalizeQuality("1440p60 HDR"))
        assertEquals("2K · 1440p", VoxBadgeHelper.normalizeQuality("QHD"))

        assertEquals("FHD · 1080p", VoxBadgeHelper.normalizeQuality("1080p"))
        assertEquals("FHD · 1080p", VoxBadgeHelper.normalizeQuality("1080p60"))
        assertEquals("FHD · 1080p", VoxBadgeHelper.normalizeQuality("1080p50"))
        assertEquals("FHD · 1080p", VoxBadgeHelper.normalizeQuality("FHD"))
        assertEquals("FHD · 1080p", VoxBadgeHelper.normalizeQuality("Full HD"))

        assertEquals("HD · 720p", VoxBadgeHelper.normalizeQuality("720p"))
        assertEquals("HD · 720p", VoxBadgeHelper.normalizeQuality("720p60"))
        assertEquals("HD · 720p", VoxBadgeHelper.normalizeQuality("HD"))

        assertEquals("SD · 480p", VoxBadgeHelper.normalizeQuality("480p"))
        assertEquals("SD · 360p", VoxBadgeHelper.normalizeQuality("360p"))
        assertEquals("SD · 240p", VoxBadgeHelper.normalizeQuality("240p"))
        assertEquals("SD · 144p", VoxBadgeHelper.normalizeQuality("144p"))
        assertEquals("SD · 480p", VoxBadgeHelper.normalizeQuality("SD"))
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
        assertEquals("4K · 2160p", VoxBadgeHelper.getQualityBadgeFromDimensions(3840, 2160))
        assertEquals("2K · 1440p", VoxBadgeHelper.getQualityBadgeFromDimensions(2560, 1440))
        assertEquals("FHD · 1080p", VoxBadgeHelper.getQualityBadgeFromDimensions(1920, 1080))
        assertEquals("HD · 720p", VoxBadgeHelper.getQualityBadgeFromDimensions(1280, 720))
        assertEquals("SD · 480p", VoxBadgeHelper.getQualityBadgeFromDimensions(854, 480))
        assertNull(VoxBadgeHelper.getQualityBadgeFromDimensions(0, 0))
    }

    @Test
    fun testVideoModelBadgeExtraction() {
        val video4k = Video().apply { badge = "4K" }
        assertEquals("4K · 2160p", VoxBadgeHelper.getQualityBadge(video4k))

        val videoLocal = Video().apply {
            isLocal = true
            badge = "1080p"
        }
        assertEquals("FHD · 1080p", VoxBadgeHelper.getQualityBadge(videoLocal))

        val videoWithSecondTitle = Video().apply {
            secondTitle = "4K · BBC News · 1M views"
        }
        assertEquals("4K · 2160p", VoxBadgeHelper.getQualityBadge(videoWithSecondTitle))

        val videoNormal = Video().apply {
            title = "Regular Video"
            secondTitle = "Channel Name · 2 days ago"
        }
        assertNull(VoxBadgeHelper.getQualityBadge(videoNormal))
    }

    @Test
    fun testAgeBadgeExtraction() {
        val videoWithField = Video().apply { ageRating = "18+" }
        assertEquals("18+", VoxBadgeHelper.getAgeBadge(videoWithField))

        val videoWithBadge = Video().apply { badge = "12+" }
        assertEquals("12+", VoxBadgeHelper.getAgeBadge(videoWithBadge))

        val videoWithSecondTitle = Video().apply {
            secondTitle = "Movie · 16+ · 2024"
        }
        assertEquals("16+", VoxBadgeHelper.getAgeBadge(videoWithSecondTitle))

        val videoNormal = Video().apply {
            title = "Family Vlog"
            secondTitle = "Channel Name · 3 weeks ago"
        }
        assertNull(VoxBadgeHelper.getAgeBadge(videoNormal))
    }
}
