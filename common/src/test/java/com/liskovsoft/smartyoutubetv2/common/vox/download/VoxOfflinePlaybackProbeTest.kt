package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxOfflinePlaybackProbeTest {

    @Test
    fun testBeforeOpenUriSchemeAndContainer() {
        val probe = VoxOfflinePlaybackProbe()
        val video = Video()
        video.isLocal = true
        video.translationState = "DOWNLOADED_TRANSLATED"
        video.badge = "4K · 2160p"
        video.ageRating = "12+"
        video.setDurationMs(5000000L)

        probe.onBeforeOpen(video, "content://media/external/video/media/100.mkv")

        assertTrue(probe.isLocal)
        assertTrue(probe.hasTranslatedAudio)
        assertEquals("content", probe.sourceScheme)
        assertEquals("MATROSKA", probe.containerCategory)
        assertEquals("4K · 2160p", probe.actualQuality)
        assertTrue(probe.ageMetadataPresent)
        assertEquals(5000000L, probe.storedDurationMs)
    }

    @Test
    fun testTracksDiscoveryAndMimeCategorization() {
        val probe = VoxOfflinePlaybackProbe()
        val video = Video()
        video.isLocal = true
        probe.onBeforeOpen(video, "file:///storage/video.mkv")

        probe.onTracksDiscovered(
            videoTracks = 1,
            audioTracks = 2,
            videoMime = "video/x-vnd.on2.vp9",
            audioMime = "audio/opus",
            hasTranslated = true,
            isTranslatedSelected = true
        )

        assertEquals(1, probe.videoTrackCount)
        assertEquals(2, probe.audioTrackCount)
        assertEquals("VP9", probe.videoMimeCategory)
        assertEquals("OPUS", probe.audioMimeCategory)
        assertTrue(probe.translatedTrackPresent)
        assertTrue(probe.translatedTrackSelected)
        assertTrue(probe.videoRendererEnabled)
    }

    @Test
    fun testFirstFrameAndBlackScreenDetection() {
        val probe = VoxOfflinePlaybackProbe()
        val video = Video()
        video.isLocal = true
        probe.onBeforeOpen(video, "content://media/1")
        probe.onTracksDiscovered(1, 1, "video/avc", "audio/mp4a-latm", false, false)

        // Before first frame, short duration -> not black screen yet
        assertFalse(probe.checkBlackScreenCondition(isPlaying = true, timeSinceStartMs = 1500L))

        // After 4000ms playing with video track but no first frame -> possible black screen
        assertTrue(probe.checkBlackScreenCondition(isPlaying = true, timeSinceStartMs = 4000L))
        assertTrue(probe.possibleBlackScreen)

        // Once first frame is rendered -> black screen flag cleared
        probe.onFirstFrameRendered()
        assertTrue(probe.firstFrameRendered)
        assertFalse(probe.possibleBlackScreen)
    }

    @Test
    fun testTimelineReadyAndFallbackDuration() {
        val probe = VoxOfflinePlaybackProbe()
        val video = Video()
        video.isLocal = true
        video.setDurationMs(7200000L)
        probe.onBeforeOpen(video, "file:///video.mkv")

        // Player timeline is 0 initially, fallback to stored duration
        probe.onTimelineChanged(timelineDurationMs = 0L, seekable = true, dynamic = false)

        assertTrue(probe.timelineReady)
        assertTrue(probe.durationKnown)
        assertEquals(7200000L, probe.durationMs)
        assertTrue(probe.isSeekable)
        assertFalse(probe.isDynamic)
        assertFalse(probe.timelineFailure)
    }

    @Test
    fun testPositionAdvancement() {
        val probe = VoxOfflinePlaybackProbe()
        val video = Video()
        video.isLocal = true
        probe.onBeforeOpen(video, "file:///video.mkv")

        val baseTime = 1000000L
        probe.onPositionSample(currentPosMs = 10000L, isPlaying = true, nowMs = baseTime)
        assertFalse(probe.positionAdvancing)

        // Sample 1200ms later with 1000ms position advance -> advancing is true
        probe.onPositionSample(currentPosMs = 11000L, isPlaying = true, nowMs = baseTime + 1200L)
        assertTrue(probe.positionAdvancing)
    }

    @Test
    fun testToSafeDiagnosticMap() {
        val probe = VoxOfflinePlaybackProbe()
        val video = Video()
        video.isLocal = true
        video.translationState = "DOWNLOADED_TRANSLATED"
        probe.onBeforeOpen(video, "content://media/2.mp4")
        probe.onTracksDiscovered(1, 1, "video/av01", "audio/eac3", true, true)
        probe.onFirstFrameRendered()
        probe.onTimelineChanged(100000L, true, false)

        val map = probe.toSafeDiagnosticMap()
        assertEquals(true, map["isLocal"])
        assertEquals(true, map["hasTranslatedAudio"])
        assertEquals("content", map["sourceScheme"])
        assertEquals("MP4", map["containerCategory"])
        assertEquals("AV1", map["videoMimeCategory"])
        assertEquals("EAC3", map["audioMimeCategory"])
        assertEquals(true, map["firstFrameRendered"])
        assertEquals(true, map["timelineReady"])
        assertEquals(false, map["possibleBlackScreen"])
    }
}
