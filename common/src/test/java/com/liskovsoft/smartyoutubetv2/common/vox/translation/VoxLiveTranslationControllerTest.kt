package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoxLiveTranslationControllerTest {

    private lateinit var controller: VoxLiveTranslationController
    private var holdRequested = false
    private var resumeAllowed = false
    private var fallbackRequested = false
    private var lastErrorReason: String? = null

    @Before
    fun setUp() {
        holdRequested = false
        resumeAllowed = false
        fallbackRequested = false
        lastErrorReason = null

        controller = VoxLiveTranslationController(
            syncController = VoxLiveTranslationSyncController(VoxLiveDelayMode.AUTO),
            backendCapability = VoxLiveBackendCapability.LIVE_CHUNK_SUPPORTED
        )

        controller.setCallback(object : VoxLiveTranslationController.Callback {
            override fun onPlaybackHoldRequired() {
                holdRequested = true
                resumeAllowed = false
            }

            override fun onPlaybackResumeAllowed() {
                resumeAllowed = true
                holdRequested = false
            }

            override fun onBufferStateUpdated(state: VoxLiveTranslationBufferState) {}

            override fun onLiveTranslationError(reasonRu: String, fatal: Boolean) {
                lastErrorReason = reasonRu
            }

            override fun onFallbackToOriginalRequested() {
                fallbackRequested = true
            }
        })
    }

    @Test
    fun testStartLiveTranslationFlow() {
        val started = controller.startLiveTranslation(
            isLive = true,
            isDvrAvailable = true,
            isSeekable = true,
            audioTrackAvailable = true,
            sourceLanguage = "en",
            currentPosMs = 10_000L,
            liveEdgeMs = 30_000L
        )

        assertTrue(started)
        assertTrue(controller.isLiveTranslationActive())
        assertTrue(holdRequested)
        assertFalse(resumeAllowed)

        // Deliver initial buffer chunk
        controller.onLiveSegmentTranslated(
            VoxLiveTranslationChunk(
                sequenceNumber = 1L,
                sourceStartMs = 10_000L,
                sourceEndMs = 22_000L,
                status = VoxLiveChunkStatus.READY
            )
        )

        assertTrue(resumeAllowed)
        assertEquals(VoxLivePlaybackPhase.PLAYING_TRANSLATED, controller.getBufferState().phase)
    }

    @Test
    fun testFallbackToOriginal() {
        controller.startLiveTranslation(
            isLive = true,
            isDvrAvailable = true,
            isSeekable = true,
            audioTrackAvailable = true,
            sourceLanguage = "en",
            currentPosMs = 10_000L,
            liveEdgeMs = 30_000L
        )

        controller.fallbackToOriginal()
        assertFalse(controller.isLiveTranslationActive())
        assertTrue(fallbackRequested)
        assertEquals(VoxLivePlaybackPhase.FALLBACK_ORIGINAL, controller.getBufferState().phase)
    }

    @Test
    fun testVodOnlyBackendRejection() {
        val vodController = VoxLiveTranslationController(
            backendCapability = VoxLiveBackendCapability.VOD_ONLY
        )
        vodController.setCallback(object : VoxLiveTranslationController.Callback {
            override fun onPlaybackHoldRequired() {}
            override fun onPlaybackResumeAllowed() {}
            override fun onBufferStateUpdated(state: VoxLiveTranslationBufferState) {}
            override fun onLiveTranslationError(reasonRu: String, fatal: Boolean) {
                lastErrorReason = reasonRu
            }
            override fun onFallbackToOriginalRequested() {}
        })

        val started = vodController.startLiveTranslation(
            isLive = true,
            isDvrAvailable = true,
            isSeekable = true,
            audioTrackAvailable = true,
            sourceLanguage = "en",
            currentPosMs = 10_000L,
            liveEdgeMs = 30_000L
        )

        assertFalse(started)
        assertTrue(lastErrorReason?.contains("VOD") == true)
    }
}
