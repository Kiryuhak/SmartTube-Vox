package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoxLiveTranslationSyncControllerTest {

    private lateinit var syncController: VoxLiveTranslationSyncController

    @Before
    fun setUp() {
        syncController = VoxLiveTranslationSyncController(VoxLiveDelayMode.AUTO)
    }

    @Test
    fun testStartPreparationAndInitialBufferThreshold() {
        syncController.startPreparation(liveEdgeMs = 50_000L, currentPosMs = 30_000L)
        var state = syncController.getBufferState()
        assertEquals(VoxLivePlaybackPhase.HOLDING_INITIAL_BUFFER, state.phase)
        assertEquals(0L, state.bufferAheadMs)

        // Add first 5-second chunk (30s..35s) -> buffer is 5s < 10s resume threshold
        syncController.addTranslatedChunk(
            VoxLiveTranslationChunk(
                sequenceNumber = 1L,
                sourceStartMs = 30_000L,
                sourceEndMs = 35_000L,
                status = VoxLiveChunkStatus.READY
            )
        )
        state = syncController.getBufferState()
        assertEquals(VoxLivePlaybackPhase.HOLDING_INITIAL_BUFFER, state.phase)

        // Add second 6-second chunk (35s..41s) -> buffer is 11s >= 10s resume threshold
        syncController.addTranslatedChunk(
            VoxLiveTranslationChunk(
                sequenceNumber = 2L,
                sourceStartMs = 35_000L,
                sourceEndMs = 41_000L,
                status = VoxLiveChunkStatus.READY
            )
        )
        state = syncController.getBufferState()
        assertEquals(VoxLivePlaybackPhase.PLAYING_TRANSLATED, state.phase)
        assertEquals(11_000L, state.bufferAheadMs)
    }

    @Test
    fun testPlaybackProgressAndRebufferTrigger() {
        syncController.startPreparation(liveEdgeMs = 60_000L, currentPosMs = 30_000L)
        syncController.addTranslatedChunk(
            VoxLiveTranslationChunk(
                sequenceNumber = 1L,
                sourceStartMs = 30_000L,
                sourceEndMs = 42_000L,
                status = VoxLiveChunkStatus.READY
            )
        )
        assertEquals(VoxLivePlaybackPhase.PLAYING_TRANSLATED, syncController.getBufferState().phase)

        // Advance playback to 39s -> remaining buffer is 42s - 39s = 3s (< 4s rebuffer threshold)
        val phase = syncController.onPlaybackPositionUpdate(39_000L)
        assertEquals(VoxLivePlaybackPhase.REBUFFERING, phase)
        assertEquals(1, syncController.getBufferState().consecutiveUnderruns)

        // Add chunk 42s..52s -> buffer becomes 13s >= 10s -> resume
        syncController.addTranslatedChunk(
            VoxLiveTranslationChunk(
                sequenceNumber = 2L,
                sourceStartMs = 42_000L,
                sourceEndMs = 52_000L,
                status = VoxLiveChunkStatus.READY
            )
        )
        assertEquals(VoxLivePlaybackPhase.PLAYING_TRANSLATED, syncController.getBufferState().phase)
    }

    @Test
    fun testSeekInvalidation() {
        syncController.startPreparation(liveEdgeMs = 100_000L, currentPosMs = 50_000L)
        syncController.addTranslatedChunk(
            VoxLiveTranslationChunk(1L, 50_000L, 62_000L, status = VoxLiveChunkStatus.READY)
        )
        assertEquals(VoxLivePlaybackPhase.PLAYING_TRANSLATED, syncController.getBufferState().phase)

        // User seeks to 20_000L (backward in DVR)
        syncController.handleSeek(20_000L)
        val state = syncController.getBufferState()
        assertEquals(VoxLivePlaybackPhase.HOLDING_INITIAL_BUFFER, state.phase)
        assertEquals(0L, state.bufferAheadMs)
    }

    @Test
    fun testBoundedMemoryRetention() {
        syncController.startPreparation(liveEdgeMs = 200_000L, currentPosMs = 0L)

        // Add chunks from 0 to 100s
        for (i in 0 until 20) {
            syncController.addTranslatedChunk(
                VoxLiveTranslationChunk(
                    sequenceNumber = i.toLong(),
                    sourceStartMs = i * 5_000L,
                    sourceEndMs = (i + 1) * 5_000L,
                    status = VoxLiveChunkStatus.READY
                )
            )
        }

        // Advance playback to 80_000L
        syncController.onPlaybackPositionUpdate(80_000L)

        // Chunks older than 80s - 30s = 50s should be discarded
        val state = syncController.getBufferState()
        assertTrue(syncController.getActiveChunkCount() <= 12)
    }
}
