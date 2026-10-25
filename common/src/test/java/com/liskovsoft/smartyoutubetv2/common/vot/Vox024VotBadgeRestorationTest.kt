package com.liskovsoft.smartyoutubetv2.common.vot

import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTranslationOverlayLayoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Vox024VotBadgeRestorationTest {

    @Test
    fun testMotionStateAllRequiredStatesPresent() {
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

    @Test
    fun testVotProgressOverlayGeometryInvariants() {
        // Mockup exact geometry invariants:
        // Right anchor invariant across all states
        assertEquals(24, VoxTranslationOverlayLayoutPolicy.fixedVotEndMarginDp())
        assertEquals(256, VoxTranslationOverlayLayoutPolicy.fixedVotBottomMarginDp())

        // Dimensions: 56dp height, 56x56 circle, 280dp wide (240-320dp range)
        assertEquals(56, VoxTranslationOverlayLayoutPolicy.overlayHeightDp())
        assertEquals(56, VoxTranslationOverlayLayoutPolicy.overlayCircleDp())
        assertEquals(28, VoxTranslationOverlayLayoutPolicy.overlayCornerRadiusDp())
        assertEquals(280, VoxTranslationOverlayLayoutPolicy.overlayDefaultWidthDp())
        assertTrue(VoxTranslationOverlayLayoutPolicy.overlayDefaultWidthDp() in 240..320)

        // Padding and gaps
        assertEquals(16, VoxTranslationOverlayLayoutPolicy.overlayInnerPaddingDp())
        assertEquals(24, VoxTranslationOverlayLayoutPolicy.overlayIconSizeDp())
        assertEquals(16, VoxTranslationOverlayLayoutPolicy.overlayGapDp())
    }

    @Test
    fun testTimingConstantsStrictContract() {
        // 100% hold duration: 200..400ms (300ms)
        assertTrue(VotProgressOverlay.SHOW_100_DURATION_MS in 200L..400L)
        assertEquals(300L, VotProgressOverlay.SHOW_100_DURATION_MS)

        // Shape morph animation: ~260ms
        assertEquals(260L, VotProgressOverlay.SHAPE_ANIMATION_MS)

        // Ready auto-dismiss: 1..2s (2000ms)
        assertTrue(VotProgressOverlay.BUBBLE_READY_AUTO_HIDE_DELAY_MS in 1000L..2000L)
    }

    @Test
    fun testStringResourcesNonEmptyAndLocalized() {
        val context = RuntimeEnvironment.getApplication()
        assertNotNull(context)

        // Verify resource IDs exist
        assertNotEquals(0, R.string.vot_progress_preparing)
        assertNotEquals(0, R.string.vot_progress_ready)
        assertNotEquals(0, R.string.vot_progress_switching)
        assertNotEquals(0, R.string.vot_progress_error)
        assertNotEquals(0, R.string.vot_progress_timeout)

        // Verify string values are not empty
        val prep = context.getString(R.string.vot_progress_preparing)
        val ready = context.getString(R.string.vot_progress_ready)
        val switching = context.getString(R.string.vot_progress_switching)
        val error = context.getString(R.string.vot_progress_error)

        assertTrue("vot_progress_preparing must not be empty", prep.isNotEmpty())
        assertTrue("vot_progress_ready must not be empty", ready.isNotEmpty())
        assertTrue("vot_progress_switching must not be empty", switching.isNotEmpty())
        assertTrue("vot_progress_error must not be empty", error.isNotEmpty())
    }

    @Test
    fun testOverlayInstantiationAndInitialState() {
        val context = RuntimeEnvironment.getApplication()
        val overlay = VotProgressOverlay(context)

        assertEquals(VotProgressOverlay.STATE_IDLE, overlay.state)
        assertEquals(VotProgressOverlay.MotionState.HIDDEN, overlay.motionState)
        assertEquals(-1, overlay.realProgressPercent)
        assertTrue(!overlay.isShown)
    }

    @Test
    fun testRealProgressClampingAndMonotonicity() {
        val context = RuntimeEnvironment.getApplication()
        val overlay = VotProgressOverlay(context)

        overlay.updateRealProgress(37)
        assertEquals(37, overlay.realProgressPercent)

        // Progress cannot regress backwards
        overlay.updateRealProgress(20)
        assertEquals(37, overlay.realProgressPercent)

        overlay.updateRealProgress(99)
        assertEquals(99, overlay.realProgressPercent)

        // Values above 99 require audio activation via showActivated()
        overlay.updateRealProgress(100)
        assertEquals(99, overlay.realProgressPercent)
    }
}
