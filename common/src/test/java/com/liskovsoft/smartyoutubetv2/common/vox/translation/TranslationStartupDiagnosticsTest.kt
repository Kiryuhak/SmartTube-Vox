package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationStartupDiagnosticsTest {

    @Test
    fun testIsStartupSlowThresholds() {
        // Standard threshold = 5,000ms
        assertFalse(VoxTranslationReusePolicy.isStartupSlow(4_999L, isLiveVoice = false))
        assertTrue(VoxTranslationReusePolicy.isStartupSlow(5_001L, isLiveVoice = false))

        // Live voice threshold = 8,000ms (models take longer to synthesize)
        assertFalse(VoxTranslationReusePolicy.isStartupSlow(6_500L, isLiveVoice = true))
        assertFalse(VoxTranslationReusePolicy.isStartupSlow(7_999L, isLiveVoice = true))
        assertTrue(VoxTranslationReusePolicy.isStartupSlow(8_001L, isLiveVoice = true))
    }

    @Test
    fun testCreateSafeDiagnosticsContextZeroPii() {
        val ctx = VoxTranslationReusePolicy.createSafeDiagnosticsContext(
            elapsedMs = 450L,
            source = "cache",
            sameVideo = true,
            mode = "live_voice"
        )

        assertEquals("450", ctx["elapsedMs"])
        assertEquals("cache", ctx["source"])
        assertEquals("true", ctx["sameVideo"])
        assertEquals("live_voice", ctx["mode"])

        // Ensure no title, videoId or raw tokens leaked into context
        assertFalse(ctx.containsKey("title"))
        assertFalse(ctx.containsKey("url"))
        assertFalse(ctx.containsKey("videoId"))
        assertFalse(ctx.containsKey("token"))
    }
}
