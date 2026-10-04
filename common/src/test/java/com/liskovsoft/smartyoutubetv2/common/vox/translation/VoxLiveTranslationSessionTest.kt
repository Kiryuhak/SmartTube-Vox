package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxLiveTranslationSessionTest {

    @Test
    fun testLiveTranslationSessionReportsVodOnlyTruthfully() {
        val session = VoxLiveTranslationSession(
            sessionId = "live_session_1",
            videoId = "live_video_123",
            isLive = true
        )

        val eligibility = session.checkEligibility(
            isDvrAvailable = true,
            isSeekable = true,
            audioTrackAvailable = true,
            sourceLanguage = "en"
        )

        assertEquals(VoxLiveEligibilityStatus.UNSUPPORTED, eligibility.status)
        assertTrue(eligibility.reasonRu.contains("сервер перевода поддерживает только готовые видео"))

        val state = session.getState()
        assertEquals("live_session_1", state.sessionId)
        assertEquals("live_video_123", state.videoId)
        assertTrue(state.isLive)
        assertFalse(state.isActive)
        assertEquals(VoxLivePlaybackPhase.IDLE, state.bufferState.phase)
    }

    @Test
    fun testLiveTranslationBufferPolicyBounds() {
        val autoDelay = VoxLiveTranslationBufferPolicy.resolveTargetDelayMs(VoxLiveDelayMode.AUTO)
        assertEquals(18_000L, autoDelay)

        val lowLatencyDelay = VoxLiveTranslationBufferPolicy.resolveTargetDelayMs(VoxLiveDelayMode.LOW_LATENCY)
        assertEquals(12_000L, lowLatencyDelay)

        val stableDelay = VoxLiveTranslationBufferPolicy.resolveTargetDelayMs(VoxLiveDelayMode.STABLE)
        assertEquals(30_000L, stableDelay)

        // Thresholds
        assertTrue(VoxLiveTranslationBufferPolicy.shouldRebuffer(2_000L, VoxLiveDelayMode.AUTO))
        assertFalse(VoxLiveTranslationBufferPolicy.shouldRebuffer(5_000L, VoxLiveDelayMode.AUTO))

        assertTrue(VoxLiveTranslationBufferPolicy.canResume(11_000L, VoxLiveDelayMode.AUTO))
        assertFalse(VoxLiveTranslationBufferPolicy.canResume(8_000L, VoxLiveDelayMode.AUTO))
    }
}
