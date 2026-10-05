package com.liskovsoft.smartyoutubetv2.common.vox.badge

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxBadgeRecycleTest {

    @Test
    fun testBadgeClearingOnUnknownCardRebind() {
        // Card A: Video with full 4K, 12+, downloaded translated metadata
        val cardA = Video()
        cardA.badge = "4K · 2160p"
        cardA.ageRating = "12+"
        cardA.isLocal = true
        cardA.translationState = "DOWNLOADED_TRANSLATED"

        assertEquals("4K", VoxBadgeHelper.getQualityBadge(cardA))
        assertEquals("12+", VoxBadgeHelper.getAgeBadge(cardA))
        assertTrue(cardA.isLocal)
        assertTrue(cardA.isDownloadedTranslated())

        // Card B: Video without quality or age metadata, online standard video
        val cardB = Video()
        cardB.badge = null
        cardB.ageRating = null
        cardB.isLocal = false
        cardB.translationState = "NONE"

        assertNull(VoxBadgeHelper.getQualityBadge(cardB))
        assertNull(VoxBadgeHelper.getAgeBadge(cardB))
        assertFalse(cardB.isLocal)
        assertFalse(cardB.isDownloadedTranslated())
    }

    @Test
    fun testBadgeClearingWhenRawTextHasNoQualityOrAge() {
        val video = Video()
        video.badge = "Премьера" // Text badge, but not a quality badge
        video.ageRating = null

        assertNull(VoxBadgeHelper.getQualityBadge(video))
        assertNull(VoxBadgeHelper.getAgeBadge(video))
    }

    @Test
    fun testDownloadedUntranslatedClearing() {
        val localUntranslated = Video()
        localUntranslated.isLocal = true
        localUntranslated.translationState = "NONE"

        assertTrue(localUntranslated.isLocal)
        assertFalse(localUntranslated.isDownloadedTranslated())

        val onlineVideo = Video()
        onlineVideo.isLocal = false
        onlineVideo.translationState = "NONE"

        assertFalse(onlineVideo.isLocal)
        assertFalse(onlineVideo.isDownloadedTranslated())
    }
}
