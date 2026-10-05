package com.liskovsoft.smartyoutubetv2.common.vox.badge

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoxBadgeRecycleTest {

    @Test
    fun testBadgeClearingOnUnknownCardRebind() {
        // Card A: Video with full 4K and 12+ metadata
        val cardA = Video()
        cardA.badge = "4K · 2160p"
        cardA.ageRating = "12+"

        assertEquals("4K · 2160p", VoxBadgeHelper.getQualityBadge(cardA))
        assertEquals("12+", VoxBadgeHelper.getAgeBadge(cardA))

        // Card B: Video without quality or age metadata
        val cardB = Video()
        cardB.badge = null
        cardB.ageRating = null

        assertNull(VoxBadgeHelper.getQualityBadge(cardB))
        assertNull(VoxBadgeHelper.getAgeBadge(cardB))
    }

    @Test
    fun testBadgeClearingWhenRawTextHasNoQualityOrAge() {
        val video = Video()
        video.badge = "Премьера" // Text badge, but not a quality badge
        video.ageRating = null

        assertNull(VoxBadgeHelper.getQualityBadge(video))
        assertNull(VoxBadgeHelper.getAgeBadge(video))
    }
}
