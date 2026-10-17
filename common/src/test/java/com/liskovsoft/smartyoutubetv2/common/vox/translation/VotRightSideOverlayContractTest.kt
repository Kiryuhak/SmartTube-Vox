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
        assertEquals(10, progress.onProcessingHeartbeat(60))
        // An identical heartbeat does not synthesize another percent.
        assertEquals(10, progress.onProcessingHeartbeat(60))
    }

    @Test
    fun progressIsMonotonicForRegressingEta() {
        val progress = VotBackendProgressEstimator()
        progress.onProcessingHeartbeat(60)
        val advanced = progress.onProcessingHeartbeat(30)
        assertTrue(advanced > 10)
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
}
