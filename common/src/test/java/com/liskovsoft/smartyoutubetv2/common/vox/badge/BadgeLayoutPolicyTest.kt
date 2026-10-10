package com.liskovsoft.smartyoutubetv2.common.vox.badge

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.*
import org.junit.Test

class BadgeLayoutPolicyTest {

    @Test
    fun durationBadgeIsIndependentFromQualityBadge() {
        val video = Video().apply {
            videoId = "v1"
            title = "Nature in 4K"
            durationMs = 754_000L // 12:34
            badge = "4K"
        }

        val quality = VoxBadgeHelper.getQualityBadge(video)
        val duration = VoxBadgeHelper.getDurationBadge(video)

        assertEquals("4K", quality)
        assertEquals("12:34", duration)
        assertNotEquals(quality, duration)
    }

    @Test
    fun durationFormatsHoursAndMinutesCorrectly() {
        val videoWithHours = Video().apply {
            videoId = "v2"
            durationMs = 4_992_000L // 1:23:12
        }
        assertEquals("1:23:12", VoxBadgeHelper.getDurationBadge(videoWithHours))

        val videoWithMinutes = Video().apply {
            videoId = "v3"
            durationMs = 185_000L // 3:05
        }
        assertEquals("3:05", VoxBadgeHelper.getDurationBadge(videoWithMinutes))
    }

    @Test
    fun qualityBadgeNeverContainsDurationString() {
        val videoWithTimeBadge = Video().apply {
            videoId = "v4"
            badge = "15:42"
        }

        // The badge field contains a duration string, so getQualityBadge must NOT mistake it for quality
        assertNull(VoxBadgeHelper.getQualityBadge(videoWithTimeBadge))
        assertEquals("15:42", VoxBadgeHelper.getDurationBadge(videoWithTimeBadge))
    }

    @Test
    fun handlesShortsAndLiveStreamsBySuppressingDuration() {
        val liveVideo = Video().apply {
            videoId = "v5"
            isLive = true
            durationMs = 10_000_000L
        }
        assertNull(VoxBadgeHelper.getDurationBadge(liveVideo))

        val shortsVideo = Video().apply {
            videoId = "v6"
            isShorts = true
            durationMs = 45_000L
        }
        assertNull(VoxBadgeHelper.getDurationBadge(shortsVideo))
    }
}
