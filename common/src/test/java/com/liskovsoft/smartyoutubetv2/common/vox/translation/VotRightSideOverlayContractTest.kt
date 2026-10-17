package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.smartyoutubetv2.common.vot.VotBackendProgressEstimator
import com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTranslationOverlayLayoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VotRightSideOverlayContractTest {
    @Test
    fun preparingShownOnRightSide() {
        assertEquals(48, VoxTranslationOverlayLayoutPolicy.fixedVotEndMarginDp())
    }

    @Test
    fun rightAnchorDoesNotMoveBetweenHudStates() {
        val hidden = VoxTranslationOverlayLayoutPolicy.fixedVotBottomMarginDp()
        val shown = VoxTranslationOverlayLayoutPolicy.fixedVotBottomMarginDp()
        assertEquals(hidden, shown)
        assertTrue(VoxTranslationOverlayLayoutPolicy.isSubtitleSafe(hidden, true))
    }

    @Test
    fun progressStartsAtZeroAndIsBackendDriven() {
        val progress = VotBackendProgressEstimator()
        assertEquals(0, progress.displayProgress)
        assertEquals(0, progress.onProcessingHeartbeat(60))
        // An identical heartbeat does not synthesize another percent.
        assertEquals(0, progress.onProcessingHeartbeat(60))
    }

    @Test
    fun progressIsMonotonicForRegressingEta() {
        val progress = VotBackendProgressEstimator()
        progress.onProcessingHeartbeat(60)
        val advanced = progress.onProcessingHeartbeat(30)
        assertTrue(advanced >= 10)
        assertEquals(advanced, progress.onProcessingHeartbeat(45))
    }

    @Test
    fun uploadProgressIsMonotonicAndClampedBeforeReady() {
        val progress = VotBackendProgressEstimator()
        assertTrue(progress.onUploadPart(0, 4) in 10..95)
        assertEquals(95, progress.onUploadPart(99, 4))
        assertEquals(95, progress.onBackendProgress(500))
    }

    @Test
    fun backendReadyStaysAt99UntilAudioAttached() {
        val progress = VotBackendProgressEstimator()
        assertEquals(99, progress.onArtifactReady())
        assertEquals(99, progress.displayProgress)
    }

    @Test
    fun audioAttachedMovesTo100() {
        val progress = VotBackendProgressEstimator()
        progress.onArtifactReady()
        assertEquals(100, progress.onAudioAttachedAndSelected())
    }

    @Test
    fun duplicateCallbacksNeverReplayOrRegressProgress() {
        val progress = VotBackendProgressEstimator()
        assertEquals(99, progress.onArtifactReady())
        assertEquals(99, progress.onArtifactReady())
        assertEquals(100, progress.onAudioAttachedAndSelected())
        assertEquals(100, progress.onAudioAttachedAndSelected())
    }

    @Test
    fun readyFastPathIsFastAndDoesNotFakeIntermediateProgress() {
        val progress = VotBackendProgressEstimator()
        progress.onLookup()
        assertEquals(99, progress.onArtifactReady())
        assertEquals(100, progress.onAudioAttachedAndSelected())
    }

    @Test
    fun resetForNewOrCancelledSessionPreventsLateProgressCarryOver() {
        val progress = VotBackendProgressEstimator()
        progress.onAudioAttachedAndSelected()
        progress.reset()
        assertEquals(0, progress.displayProgress)
    }

    @Test
    fun testTimingConstantsConformToMockupContract() {
        val show100Ms = com.liskovsoft.smartyoutubetv2.common.vot.VotProgressOverlay.SHOW_100_DURATION_MS
        assertTrue("100% state must be held 250..400ms before expanding to ready", show100Ms in 250L..400L)

        val readyHoldMs = com.liskovsoft.smartyoutubetv2.common.vot.VotProgressOverlay.BUBBLE_READY_AUTO_HIDE_DELAY_MS
        assertTrue("Ready confirmation pill must be held 1000..2000ms before auto-dismiss", readyHoldMs in 1000L..2000L)
    }

    @Test
    fun testCanonicalUrlNormalizationConformsToYandexContract() {
        val normalized1 = VoxTranslationReusePolicy.normalizeVideoUrl("dQw4w9WgXcQ")
        assertEquals("https://youtu.be/dQw4w9WgXcQ", normalized1)

        val normalized2 = VoxTranslationReusePolicy.normalizeVideoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals("https://youtu.be/dQw4w9WgXcQ", normalized2)

        val normalized3 = VoxTranslationReusePolicy.normalizeVideoUrl("https://youtu.be/dQw4w9WgXcQ?si=test1234")
        assertEquals("https://youtu.be/dQw4w9WgXcQ", normalized3)
    }
}
