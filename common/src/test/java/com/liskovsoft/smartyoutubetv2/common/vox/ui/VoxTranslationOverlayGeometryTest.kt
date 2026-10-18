package com.liskovsoft.smartyoutubetv2.common.vox.ui

import com.liskovsoft.smartyoutubetv2.common.vot.VotProgressOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates strict conformance of VOX translation HUD geometry,
 * dimensions, anchors, timing, and state machine transitions
 * to the final design specification mockup.
 */
class VoxTranslationOverlayGeometryTest {

    @Test
    fun testFixedRightAnchorConformance() {
        // Fixed right-side margin must be exactly 24dp across all states
        assertEquals(24, VoxTranslationOverlayLayoutPolicy.fixedVotEndMarginDp())
        assertEquals(24, VoxTranslationOverlayLayoutPolicy.VOT_FIXED_END_MARGIN_DP)

        // Fixed vertical position must be invariant (256dp)
        assertEquals(256, VoxTranslationOverlayLayoutPolicy.fixedVotBottomMarginDp())
        assertEquals(256, VoxTranslationOverlayLayoutPolicy.VOT_FIXED_BOTTOM_MARGIN_DP)
    }

    @Test
    fun testDimensionsConformance() {
        // Height: 56dp
        assertEquals(56, VoxTranslationOverlayLayoutPolicy.overlayHeightDp())

        // Circle: 56dp x 56dp
        assertEquals(56, VoxTranslationOverlayLayoutPolicy.overlayCircleDp())

        // Corner radius: 28dp (half of height = perfect capsule / circle)
        assertEquals(28, VoxTranslationOverlayLayoutPolicy.overlayCornerRadiusDp())
        assertEquals(VoxTranslationOverlayLayoutPolicy.overlayHeightDp() / 2, VoxTranslationOverlayLayoutPolicy.overlayCornerRadiusDp())

        // Wide width range: 240..320dp, default 280dp
        assertEquals(280, VoxTranslationOverlayLayoutPolicy.overlayDefaultWidthDp())
        assertEquals(240, VoxTranslationOverlayLayoutPolicy.overlayMinWidthDp())
        assertEquals(320, VoxTranslationOverlayLayoutPolicy.overlayMaxWidthDp())
        assertTrue(VoxTranslationOverlayLayoutPolicy.overlayDefaultWidthDp() in 240..320)

        // Internal padding: 16dp
        assertEquals(16, VoxTranslationOverlayLayoutPolicy.overlayInnerPaddingDp())

        // Icon area: 24dp x 24dp
        assertEquals(24, VoxTranslationOverlayLayoutPolicy.overlayIconSizeDp())

        // Gap between icon and text: 16dp
        assertEquals(16, VoxTranslationOverlayLayoutPolicy.overlayGapDp())
    }

    @Test
    fun testTimingConstantsConformance() {
        // 100% hold: 200..400ms (300ms)
        assertTrue(VotProgressOverlay.SHOW_100_DURATION_MS in 200L..400L)
        assertEquals(300L, VotProgressOverlay.SHOW_100_DURATION_MS)

        // Ready auto-dismiss: 1..2s (2000ms)
        assertTrue(VotProgressOverlay.BUBBLE_READY_AUTO_HIDE_DELAY_MS in 1000L..2000L)
        assertEquals(2000L, VotProgressOverlay.BUBBLE_READY_AUTO_HIDE_DELAY_MS)

        // Shape morph animation: ~260ms
        assertEquals(260L, VotProgressOverlay.SHAPE_ANIMATION_MS)
    }

    @Test
    fun testMotionStateEnumCompleteCoverage() {
        val states = VotProgressOverlay.MotionState.values().map { it.name }
        assertTrue(states.contains("HIDDEN"))
        assertTrue(states.contains("PREPARING"))
        assertTrue(states.contains("COLLAPSING"))
        assertTrue(states.contains("PROGRESS"))
        assertTrue(states.contains("FINALIZING_99"))
        assertTrue(states.contains("SHOWING_100"))
        assertTrue(states.contains("EXPANDING_READY"))
        assertTrue(states.contains("READY"))
        assertTrue(states.contains("SWITCHING"))
        assertTrue(states.contains("ERROR"))
        assertTrue(states.contains("DISMISSING"))
    }
}
