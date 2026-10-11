package com.liskovsoft.smartyoutubetv2.common.vox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxTranslationOverlayLayoutPolicyTest {

    @Test
    fun testNonPlaybackMargin() {
        val margin = VoxTranslationOverlayLayoutPolicy.calculateBottomMarginDp(
            isPlayback = false,
            isControlsShown = false
        )
        assertEquals(VoxTranslationOverlayLayoutPolicy.NON_PLAYBACK_BOTTOM_MARGIN_DP, margin)
        assertEquals(72, margin)
        assertTrue(VoxTranslationOverlayLayoutPolicy.isSubtitleSafe(margin, isPlayback = false))
    }

    @Test
    fun testPlaybackHudHiddenMargin() {
        val margin = VoxTranslationOverlayLayoutPolicy.calculateBottomMarginDp(
            isPlayback = true,
            isControlsShown = false
        )
        assertEquals(VoxTranslationOverlayLayoutPolicy.SUBTITLE_SAFE_BOTTOM_MARGIN_DP, margin)
        assertEquals(192, margin)
        assertTrue(VoxTranslationOverlayLayoutPolicy.isSubtitleSafe(margin, isPlayback = true))
    }

    @Test
    fun testPlaybackHudVisibleMargin() {
        val margin = VoxTranslationOverlayLayoutPolicy.calculateBottomMarginDp(
            isPlayback = true,
            isControlsShown = true
        )
        assertEquals(VoxTranslationOverlayLayoutPolicy.HUD_SAFE_BOTTOM_MARGIN_DP, margin)
        assertEquals(256, margin)
        assertTrue(margin > VoxTranslationOverlayLayoutPolicy.SUBTITLE_SAFE_BOTTOM_MARGIN_DP)
        assertTrue(VoxTranslationOverlayLayoutPolicy.isSubtitleSafe(margin, isPlayback = true))
    }

    @Test
    fun testResponsiveCalculationRespectsScreenHeight() {
        // Standard TV 1080p in dp (approx 540dp height in Leanback density)
        val margin540 = VoxTranslationOverlayLayoutPolicy.calculateResponsiveBottomMarginDp(
            isPlayback = true,
            isControlsShown = false,
            screenHeightDp = 540
        )
        assertEquals(192, margin540)

        // 4K TV / Large density height (e.g. 1080dp height)
        val margin1080 = VoxTranslationOverlayLayoutPolicy.calculateResponsiveBottomMarginDp(
            isPlayback = true,
            isControlsShown = false,
            screenHeightDp = 1080
        )
        // 1080 * 0.32 = 345dp
        assertTrue(margin1080 >= 345)

        // 1080dp height with HUD open: 1080 * 0.42 = 453dp
        val margin1080Hud = VoxTranslationOverlayLayoutPolicy.calculateResponsiveBottomMarginDp(
            isPlayback = true,
            isControlsShown = true,
            screenHeightDp = 1080
        )
        assertTrue(margin1080Hud >= 453)
    }

    @Test
    fun testSubtitleSafeValidation() {
        assertFalse(VoxTranslationOverlayLayoutPolicy.isSubtitleSafe(120, isPlayback = true))
        assertFalse(VoxTranslationOverlayLayoutPolicy.isSubtitleSafe(179, isPlayback = true))
        assertTrue(VoxTranslationOverlayLayoutPolicy.isSubtitleSafe(180, isPlayback = true))
        assertTrue(VoxTranslationOverlayLayoutPolicy.isSubtitleSafe(192, isPlayback = true))
        assertTrue(VoxTranslationOverlayLayoutPolicy.isSubtitleSafe(256, isPlayback = true))
    }
}
