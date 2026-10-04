package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxLiveTranslationBufferStateTest {

    @Test
    fun testBufferMetricsCalculations() {
        val state = VoxLiveTranslationBufferState(
            phase = VoxLivePlaybackPhase.PLAYING_TRANSLATED,
            liveEdgePositionMs = 100_000L,
            playbackPositionMs = 80_000L,
            translatedAudioReadyUntilMs = 95_000L,
            mode = VoxLiveDelayMode.AUTO
        )

        assertEquals(15_000L, state.bufferAheadMs)
        assertEquals(20_000L, state.currentDelayMs)
        assertTrue(state.hasSufficientBuffer())
        assertFalse(state.isBufferCritical())
        assertEquals("Перевод активен · задержка 20 с", state.formatHudStatus())
    }

    @Test
    fun testCriticalBufferState() {
        val criticalState = VoxLiveTranslationBufferState(
            phase = VoxLivePlaybackPhase.REBUFFERING,
            liveEdgePositionMs = 100_000L,
            playbackPositionMs = 80_000L,
            translatedAudioReadyUntilMs = 82_000L, // buffer ahead is 2s < 4s
            mode = VoxLiveDelayMode.AUTO
        )

        assertEquals(2_000L, criticalState.bufferAheadMs)
        assertTrue(criticalState.isBufferCritical())
        assertFalse(criticalState.hasSufficientBuffer())
        assertEquals("Буферизация перевода…", criticalState.formatHudStatus())
    }

    @Test
    fun testHudStatusFormatting() {
        val idleState = VoxLiveTranslationBufferState(phase = VoxLivePlaybackPhase.IDLE)
        assertEquals("Перевести", idleState.formatHudStatus())

        val prepState = VoxLiveTranslationBufferState(phase = VoxLivePlaybackPhase.HOLDING_INITIAL_BUFFER)
        assertEquals("Подготовка перевода…", prepState.formatHudStatus())

        val fallbackState = VoxLiveTranslationBufferState(phase = VoxLivePlaybackPhase.FALLBACK_ORIGINAL)
        assertEquals("Оригинальный звук", fallbackState.formatHudStatus())
    }
}
