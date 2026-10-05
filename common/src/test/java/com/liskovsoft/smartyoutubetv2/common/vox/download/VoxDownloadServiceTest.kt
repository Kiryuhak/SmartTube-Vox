package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoxDownloadServiceTest {

    @Test
    fun testValidDownloadIdFormatViaPolicy() {
        val validIds = listOf(
            "dQw4w9WgXcQ_12345",
            "test-download-id",
            "video_abc-123_456",
            "123456789",
            "a",
            "a".repeat(64)
        )
        val invalidIds = listOf(
            "../etc/passwd",
            "../bad",
            "download id with spaces",
            "id;rm -rf /",
            "",
            "   ",
            null,
            "a".repeat(65)
        )

        for (id in validIds) {
            assertTrue("Expected valid: $id", VoxDownloadServicePolicy.isValidDownloadId(id))
        }

        for (id in invalidIds) {
            assertFalse("Expected invalid: $id", VoxDownloadServicePolicy.isValidDownloadId(id))
        }
    }

    @Test
    fun testActiveAndTerminalStateClassificationViaPolicy() {
        val activeStates = listOf(
            VoxDownloadState.PREPARING_TRANSLATION,
            VoxDownloadState.RESOLVING_STREAMS,
            VoxDownloadState.DOWNLOADING_VIDEO,
            VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO,
            VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO,
            VoxDownloadState.READY_FOR_MUX,
            VoxDownloadState.MUXING,
            VoxDownloadState.FINALIZING,
            VoxDownloadState.MUXED,
            VoxDownloadState.PUBLISHING
        )

        val nonActiveStates = listOf(
            VoxDownloadState.IDLE,
            VoxDownloadState.PAUSED,
            VoxDownloadState.COMPLETED,
            VoxDownloadState.FAILED,
            VoxDownloadState.CANCELLED
        )

        for (state in activeStates) {
            assertTrue("Expected active for $state", VoxDownloadServicePolicy.isActiveState(state))
            assertFalse("Expected non-terminal for $state", VoxDownloadServicePolicy.isTerminalState(state))
        }

        for (state in nonActiveStates) {
            assertFalse("Expected inactive for $state", VoxDownloadServicePolicy.isActiveState(state))
        }

        assertTrue(VoxDownloadServicePolicy.isTerminalState(VoxDownloadState.COMPLETED))
        assertTrue(VoxDownloadServicePolicy.isTerminalState(VoxDownloadState.FAILED))
        assertTrue(VoxDownloadServicePolicy.isTerminalState(VoxDownloadState.CANCELLED))
        assertFalse(VoxDownloadServicePolicy.isTerminalState(VoxDownloadState.PAUSED))
        assertFalse(VoxDownloadServicePolicy.isTerminalState(VoxDownloadState.IDLE))
    }

    @Test
    fun testAutoResumePolicyOnGenericStart() {
        // IDLE: allowed to start
        assertTrue(VoxDownloadServicePolicy.shouldAutoResumeOnStart(VoxDownloadState.IDLE, isAlreadyActive = false))

        // In-flight states: allowed to recover if not already active
        assertTrue(VoxDownloadServicePolicy.shouldAutoResumeOnStart(VoxDownloadState.DOWNLOADING_VIDEO, isAlreadyActive = false))
        assertTrue(VoxDownloadServicePolicy.shouldAutoResumeOnStart(VoxDownloadState.MUXING, isAlreadyActive = false))

        // If already active: false (observe only, no double start)
        assertFalse(VoxDownloadServicePolicy.shouldAutoResumeOnStart(VoxDownloadState.DOWNLOADING_VIDEO, isAlreadyActive = true))

        // PAUSED: must NOT auto-resume on generic START
        assertFalse(VoxDownloadServicePolicy.shouldAutoResumeOnStart(VoxDownloadState.PAUSED, isAlreadyActive = false))

        // FAILED: must NOT auto-resume on generic START
        assertFalse(VoxDownloadServicePolicy.shouldAutoResumeOnStart(VoxDownloadState.FAILED, isAlreadyActive = false))

        // CANCELLED: must NOT auto-resume on generic START
        assertFalse(VoxDownloadServicePolicy.shouldAutoResumeOnStart(VoxDownloadState.CANCELLED, isAlreadyActive = false))

        // COMPLETED: must NOT auto-resume on generic START
        assertFalse(VoxDownloadServicePolicy.shouldAutoResumeOnStart(VoxDownloadState.COMPLETED, isAlreadyActive = false))
    }

    @Test
    fun testExplicitResumePolicy() {
        // PAUSED: allowed on explicit resume
        assertTrue(VoxDownloadServicePolicy.shouldResumeOnExplicitResume(VoxDownloadState.PAUSED, isAlreadyActive = false))

        // Inactive / IDLE: allowed
        assertTrue(VoxDownloadServicePolicy.shouldResumeOnExplicitResume(VoxDownloadState.IDLE, isAlreadyActive = false))

        // Already active: false
        assertFalse(VoxDownloadServicePolicy.shouldResumeOnExplicitResume(VoxDownloadState.PAUSED, isAlreadyActive = true))

        // Terminal states: forbidden
        assertFalse(VoxDownloadServicePolicy.shouldResumeOnExplicitResume(VoxDownloadState.FAILED, isAlreadyActive = false))
        assertFalse(VoxDownloadServicePolicy.shouldResumeOnExplicitResume(VoxDownloadState.CANCELLED, isAlreadyActive = false))
        assertFalse(VoxDownloadServicePolicy.shouldResumeOnExplicitResume(VoxDownloadState.COMPLETED, isAlreadyActive = false))
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
        job.updatePublishProgress(800, 1000, 80)
        // Публикация и финализация показывают стадию, не ложные 99% готовности.
        org.junit.Assert.assertNull(job.getSnapshot().overallPercent)
        job.updateState(VoxDownloadState.FINALIZING)
        org.junit.Assert.assertNull(job.getSnapshot().overallPercent)

        job.updateState(VoxDownloadState.COMPLETED)
        assertEquals(100, job.getSnapshot().overallPercent)
    }
}
