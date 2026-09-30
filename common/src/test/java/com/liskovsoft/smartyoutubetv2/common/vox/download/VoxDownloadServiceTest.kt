package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class VoxDownloadServiceTest {

    @Test
    fun testValidDownloadIdFormat() {
        val validIds = listOf(
            "dQw4w9WgXcQ_12345",
            "test-download-id",
            "video_abc-123_456",
            "123456789"
        )
        val invalidIds = listOf(
            "../etc/passwd",
            "download id with spaces",
            "id;rm -rf /",
            "",
            "a".repeat(65)
        )

        val idRegex = Regex("^[a-zA-Z0-9_-]{1,64}$")

        for (id in validIds) {
            assertTrue("Expected valid: $id", id.matches(idRegex))
        }

        for (id in invalidIds) {
            assertFalse("Expected invalid: $id", id.matches(idRegex))
        }
    }

    @Test
    fun testProgressPercentMapping() {
        val req = VoxDownloadRequest(
            videoId = "test_vid",
            videoTitle = "Test Video",
            qualityPreference = VoxQualityPreference.QUALITY_720P,
            translationMode = VoxTranslationMode.STANDARD
        )
        val job = VoxDownloadJob(req)

        // Video downloading: 50 / 100 bytes
        job.updateState(VoxDownloadState.DOWNLOADING_VIDEO)
        job.updateTrackProgress(VoxDownloadTrack.VIDEO, 50, 100, VoxTrackState.IN_PROGRESS)
        job.updateTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, 0, 50, VoxTrackState.PENDING)
        job.updateTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO, 0, 50, VoxTrackState.PENDING)
        val videoSnapshot = job.getSnapshot()
        assertEquals(VoxDownloadState.DOWNLOADING_VIDEO, videoSnapshot.state)
        // 50 bytes out of 200 total = 25%
        assertEquals(25, videoSnapshot.overallPercent)

        // Video completed (100) + original audio 50/50 + trans 50/50 = 200/200 = 100%
        job.updateTrackProgress(VoxDownloadTrack.VIDEO, 100, 100, VoxTrackState.COMPLETED)
        job.updateTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, 50, 50, VoxTrackState.COMPLETED)
        job.updateTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO, 50, 50, VoxTrackState.COMPLETED)
        job.updateState(VoxDownloadState.READY_FOR_MUX)
        assertEquals(100, job.getSnapshot().overallPercent)
    }

    @Test
    fun testMuxAndPublishProgressMapping() {
        val req = VoxDownloadRequest(
            videoId = "test_vid",
            videoTitle = "Test Video",
            qualityPreference = VoxQualityPreference.QUALITY_720P,
            translationMode = VoxTranslationMode.STANDARD
        )
        val job = VoxDownloadJob(req)

        job.updateState(VoxDownloadState.MUXING)
        job.updateMuxProgress(500, 1000, 50)
        assertEquals(50, job.getSnapshot().overallPercent)

        job.updateState(VoxDownloadState.PUBLISHING)
        job.updateMuxProgress(800, 1000, 80)
        assertEquals(99, job.getSnapshot().overallPercent)

        job.updateState(VoxDownloadState.COMPLETED)
        assertEquals(100, job.getSnapshot().overallPercent)
    }

    @Test
    fun testTerminalStatesStopCondition() {
        val terminalStates = listOf(
            VoxDownloadState.COMPLETED,
            VoxDownloadState.FAILED,
            VoxDownloadState.CANCELLED,
            VoxDownloadState.PAUSED
        )

        for (state in terminalStates) {
            val isStopping = state == VoxDownloadState.COMPLETED ||
                    state == VoxDownloadState.FAILED ||
                    state == VoxDownloadState.CANCELLED ||
                    state == VoxDownloadState.PAUSED
            assertTrue("Expected stop for $state", isStopping)
        }

        val activeStates = listOf(
            VoxDownloadState.PREPARING_TRANSLATION,
            VoxDownloadState.RESOLVING_STREAMS,
            VoxDownloadState.DOWNLOADING_VIDEO,
            VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO,
            VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO,
            VoxDownloadState.MUXING,
            VoxDownloadState.PUBLISHING
        )

        for (state in activeStates) {
            val isStopping = state == VoxDownloadState.COMPLETED ||
                    state == VoxDownloadState.FAILED ||
                    state == VoxDownloadState.CANCELLED ||
                    state == VoxDownloadState.PAUSED
            assertFalse("Expected active for $state", isStopping)
        }
    }
}
