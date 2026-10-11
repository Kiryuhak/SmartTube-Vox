package com.liskovsoft.smartyoutubetv2.common.vox.ui

/**
 * Layout and subtitle-safety policy for translation status overlays.
 *
 * Guarantees that the translation status overlay is NEVER placed on top of user subtitles.
 * Computes subtitle-safe vertical offsets and HUD-aware positioning for TV resolutions (1080p, 4K).
 */
object VoxTranslationOverlayLayoutPolicy {

    /** Standard TV overscan margin (safe area 5% on 960x540dp TV layout = 48dp). */
    const val DEFAULT_OVERSCAN_MARGIN_DP = 48

    /**
     * Vertical offset when HUD is hidden.
     * Placed strictly ABOVE the subtitle zone (which occupies the bottom ~140-160dp).
     */
    const val SUBTITLE_SAFE_BOTTOM_MARGIN_DP = 192

    /**
     * Vertical offset when player controls (HUD) are visible.
     * Placed strictly ABOVE the Leanback controls dock (which occupies the bottom ~200-240dp).
     */
    const val HUD_SAFE_BOTTOM_MARGIN_DP = 256

    /** Default bottom margin when outside playback activity (e.g. browsing/settings). */
    const val NON_PLAYBACK_BOTTOM_MARGIN_DP = 72

    /** Minimum subtitle safe clearance in dp. */
    const val MIN_SUBTITLE_CLEARANCE_DP = 180

    /**
     * Computes the bottom margin in DP based on playback state and HUD visibility.
     */
    @JvmStatic
    fun calculateBottomMarginDp(isPlayback: Boolean, isControlsShown: Boolean): Int {
        if (!isPlayback) return NON_PLAYBACK_BOTTOM_MARGIN_DP
        return if (isControlsShown) HUD_SAFE_BOTTOM_MARGIN_DP else SUBTITLE_SAFE_BOTTOM_MARGIN_DP
    }

    /**
     * Responsive bottom margin calculation respecting screen height (e.g. 1080p vs 4K or constrained heights).
     */
    @JvmStatic
    fun calculateResponsiveBottomMarginDp(
        isPlayback: Boolean,
        isControlsShown: Boolean,
        screenHeightDp: Int
    ): Int {
        if (!isPlayback) return NON_PLAYBACK_BOTTOM_MARGIN_DP
        val baseDp = calculateBottomMarginDp(isPlayback, isControlsShown)
        if (screenHeightDp <= 0) return baseDp

        // In 4K / large dp heights or small screens, ensure proportion stays safe:
        // Subtitles generally sit in bottom 28% of screen. Overlay must be >= 32% (or >= 42% when HUD open).
        val minRatio = if (isControlsShown) 0.42f else 0.32f
        val ratioDp = (screenHeightDp * minRatio).toInt()
        return Math.max(baseDp, ratioDp)
    }

    /**
     * Validates whether a given bottom margin is strictly above the subtitle zone.
     */
    @JvmStatic
    fun isSubtitleSafe(bottomMarginDp: Int, isPlayback: Boolean): Boolean {
        if (!isPlayback) return true
        return bottomMarginDp >= MIN_SUBTITLE_CLEARANCE_DP
    }
}
