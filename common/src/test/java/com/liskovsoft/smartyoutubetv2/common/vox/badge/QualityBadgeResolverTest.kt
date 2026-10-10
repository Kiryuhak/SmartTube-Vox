package com.liskovsoft.smartyoutubetv2.common.vox.badge

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.*
import org.junit.Test

class QualityBadgeResolverTest {

    @Test
    fun resolvesMetadataQualityWithHighConfidence() {
        val video = Video().apply {
            videoId = "v1"
            badge = "2160p"
        }
        val (badge, confidence) = VoxBadgeHelper.resolveQualityBadgeWithConfidence(video)
        assertEquals("4K", badge)
        assertEquals(QualityConfidence.METADATA, confidence)
    }

    @Test
    fun resolvesDownloadConfirmedQuality() {
        val localVideo = Video().apply {
            videoId = "v2"
            isLocal = true
            width = 1920
            height = 1080
        }
        val (badge, confidence) = VoxBadgeHelper.resolveQualityBadgeWithConfidence(localVideo)
        assertEquals("1080p", badge)
        assertEquals(QualityConfidence.DOWNLOAD_CONFIRMED, confidence)
    }

    @Test
    fun standaloneGenericHdReturnsNullToAvoidFalse720p() {
        // Standalone generic "HD" badge from feed must NOT map to 720p
        val parsed = VoxQualityBadgeFormatter.parse("HD")
        assertNull(parsed)

        val normalized = VoxBadgeHelper.normalizeQuality("HD")
        assertNull(normalized)

        val videoWithGenericHd = Video().apply {
            videoId = "v3"
            badge = "HD"
        }
        val (badge, confidence) = VoxBadgeHelper.resolveQualityBadgeWithConfidence(videoWithGenericHd)
        assertNull(badge)
        assertEquals(QualityConfidence.UNKNOWN, confidence)
    }

    @Test
    fun resolvesExplicitQualityBadgesCorrectly() {
        assertEquals("4K", VoxBadgeHelper.normalizeQuality("4K"))
        assertEquals("4K", VoxBadgeHelper.normalizeQuality("2160p"))
        assertEquals("1440p", VoxBadgeHelper.normalizeQuality("1440p"))
        assertEquals("1440p", VoxBadgeHelper.normalizeQuality("2K"))
        assertEquals("1080p", VoxBadgeHelper.normalizeQuality("1080p"))
        assertEquals("1080p", VoxBadgeHelper.normalizeQuality("FHD"))
        assertEquals("720p", VoxBadgeHelper.normalizeQuality("720p"))
        assertEquals("480p", VoxBadgeHelper.normalizeQuality("480p"))
        assertEquals("360p", VoxBadgeHelper.normalizeQuality("360p"))
    }

    @Test
    fun resolvesVideoHeightToCanonicalBadge() {
        assertEquals("4K", VoxQualityBadgeFormatter.formatFromHeight(4320))
        assertEquals("4K", VoxQualityBadgeFormatter.formatFromHeight(2160))
        assertEquals("1440p", VoxQualityBadgeFormatter.formatFromHeight(1440))
        assertEquals("1080p", VoxQualityBadgeFormatter.formatFromHeight(1080))
        assertEquals("720p", VoxQualityBadgeFormatter.formatFromHeight(720))
        assertEquals("480p", VoxQualityBadgeFormatter.formatFromHeight(480))
        assertNull(VoxQualityBadgeFormatter.formatFromHeight(0))
    }
}
