package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoxLiveProtocolInvestigationTest {

    @Before
    fun setUp() {
        VoxLiveFeatureFlags.resetToDefaults()
        VoxLiveProtocolLogger.clearListeners()
    }

    @Test
    fun testVodOnlyProtocolMapping() {
        val proto = VoxLiveProtocolCapability.mapFromCapability(VoxLiveBackendCapability.VOD_ONLY)
        assertEquals(VoxLiveBackendCapability.VOD_ONLY, proto.capability)
        assertEquals(VoxLiveBackendTransport.HTTP_POLLING, proto.transport)
        assertFalse("VOD_ONLY does not support sequential segments", proto.supportsSequentialSegments)
        assertFalse("VOD_ONLY does not support session continuation", proto.supportsSessionContinuation)
        assertFalse("VOD_ONLY does not support incremental audio", proto.supportsIncrementalAudio)
        assertTrue(proto.supportsCancellation)
        assertEquals(VoxLiveTranslationBlocker.BACKEND_REQUIRES_COMPLETE_MEDIA, proto.blocker)
    }

    @Test
    fun testUnknownTransportMapping() {
        val proto = VoxLiveProtocolCapability.mapFromCapability(VoxLiveBackendCapability.UNKNOWN)
        assertEquals(VoxLiveBackendCapability.UNKNOWN, proto.capability)
        assertEquals(VoxLiveBackendTransport.UNKNOWN, proto.transport)
        assertFalse(proto.supportsSequentialSegments)
        assertFalse(proto.supportsSessionContinuation)
        assertFalse(proto.supportsIncrementalAudio)
        assertEquals(VoxLiveTranslationBlocker.NO_STREAMING_ENDPOINT, proto.blocker)
    }

    @Test
    fun testLiveChunkSupportedMapping() {
        val proto = VoxLiveProtocolCapability.mapFromCapability(VoxLiveBackendCapability.LIVE_CHUNK_SUPPORTED)
        assertEquals(VoxLiveBackendCapability.LIVE_CHUNK_SUPPORTED, proto.capability)
        assertEquals(VoxLiveBackendTransport.HTTP_CHUNKED, proto.transport)
        assertTrue(proto.supportsSequentialSegments)
        assertTrue(proto.supportsSessionContinuation)
        assertTrue(proto.supportsIncrementalAudio)
        assertEquals(VoxLiveTranslationBlocker.NONE, proto.blocker)
    }

    @Test
    fun testFeatureFlagStrictlyOffByDefault() {
        assertFalse(
            "VOX_LIVE_TRANSLATION_EXPERIMENTAL must be strictly false by default",
            VoxLiveFeatureFlags.VOX_LIVE_TRANSLATION_EXPERIMENTAL
        )

        // Can be toggled for experiments
        VoxLiveFeatureFlags.VOX_LIVE_TRANSLATION_EXPERIMENTAL = true
        assertTrue(VoxLiveFeatureFlags.VOX_LIVE_TRANSLATION_EXPERIMENTAL)

        // Reset
        VoxLiveFeatureFlags.resetToDefaults()
        assertFalse(VoxLiveFeatureFlags.VOX_LIVE_TRANSLATION_EXPERIMENTAL)
    }

    @Test
    fun testSessionContinuationFalseWhenUnsupported() {
        val vodBackend = VoxLiveProtocolCapability.CURRENT_VOT_BACKEND
        assertFalse(vodBackend.supportsSessionContinuation)
        assertFalse(vodBackend.supportsSequentialSegments)
        assertEquals(VoxLiveBackendTransport.HTTP_POLLING, vodBackend.transport)
    }

    @Test
    fun testQueueWithRealLikeLiveSegmentSequence() {
        val queue = VoxTranslationQueue(maxCapacity = 20)
        val genId = 100L

        // Simulate 4 successive live stream chunks of 4.0s each
        val chunk1 = VoxTranslationSegment("seg_live_1", 1L, 0L, 4000L, "dash_audio_sq1", genId)
        val chunk2 = VoxTranslationSegment("seg_live_2", 2L, 4000L, 8000L, "dash_audio_sq2", genId)
        val chunk3 = VoxTranslationSegment("seg_live_3", 3L, 8000L, 12000L, "dash_audio_sq3", genId)
        val chunk4 = VoxTranslationSegment("seg_live_4", 4L, 12000L, 16000L, "dash_audio_sq4", genId)

        // Enqueue out of order (e.g. chunk 2 arrives before chunk 1)
        assertTrue(queue.enqueue(chunk2))
        assertTrue(queue.enqueue(chunk1))
        assertTrue(queue.enqueue(chunk4))
        assertTrue(queue.enqueue(chunk3))
        assertEquals(4, queue.size())

        // Ensure strictly ordered polling by sequence
        val first = queue.pollNextPending()
        assertNotNull(first)
        assertEquals(1L, first!!.sequence)
        assertEquals(0L, first.sourceStartMs)

        val second = queue.pollNextPending()
        assertNotNull(second)
        assertEquals(2L, second!!.sequence)
        assertEquals(4000L, second.sourceStartMs)

        val third = queue.pollNextPending()
        assertNotNull(third)
        assertEquals(3L, third!!.sequence)

        val fourth = queue.pollNextPending()
        assertNotNull(fourth)
        assertEquals(4L, fourth!!.sequence)

        assertNull(queue.pollNextPending())
    }

    @Test
    fun testErrorMapping401And403Auth() {
        val err401 = VoxTranslationError.fromHttpStatus(401, "Unauthorized")
        assertEquals(VoxTranslationErrorType.AUTH, err401.type)
        assertFalse("Auth errors must not be retryable", err401.isRetryable)

        val err403 = VoxTranslationError.fromHttpStatus(403, "Forbidden")
        assertEquals(VoxTranslationErrorType.AUTH, err403.type)
        assertFalse(err403.isRetryable)
    }

    @Test
    fun testErrorMapping429RateLimited() {
        val err429 = VoxTranslationError.fromHttpStatus(429, "Too Many Requests")
        assertEquals(VoxTranslationErrorType.RATE_LIMITED, err429.type)
        assertTrue("Rate limit errors are retryable with exponential backoff", err429.isRetryable)
    }

    @Test
    fun testErrorMapping5xxBackendUnavailable() {
        val err500 = VoxTranslationError.fromHttpStatus(500, "Internal Server Error")
        assertEquals(VoxTranslationErrorType.BACKEND_UNAVAILABLE, err500.type)
        assertTrue(err500.isRetryable)

        val err502 = VoxTranslationError.fromHttpStatus(502, "Bad Gateway")
        assertEquals(VoxTranslationErrorType.BACKEND_UNAVAILABLE, err502.type)

        val err503 = VoxTranslationError.fromHttpStatus(503, "Service Unavailable")
        assertEquals(VoxTranslationErrorType.BACKEND_UNAVAILABLE, err503.type)
    }

    @Test
    fun testCancellationLifecycle() {
        val session = VoxTranslationSession("cancel_sess")
        session.start("live_stream_vid", 0L)
        session.onTranslating()

        val seg = VoxTranslationSegment("seg_c", 1L, 0L, 5000L, "src_c", session.getGenerationId())
        session.queue.enqueue(seg)
        assertEquals(1, session.queue.size())

        // Stop cancels active tasks and discards segments
        session.stop()
        assertEquals(VoxTranslationSessionState.STOPPED, session.getState())
        assertEquals(0, session.queue.size())
        assertEquals(VoxSegmentState.DISCARDED, seg.state)
    }

    @Test
    fun testSanitizedProtocolLoggerDiagnostics() {
        val capturedEvents = mutableListOf<Pair<VoxLiveProtocolEvent, String>>()
        VoxLiveProtocolLogger.addListener(object : VoxLiveProtocolLogger.Listener {
            override fun onProtocolEvent(event: VoxLiveProtocolEvent, safeDetails: String) {
                capturedEvents.add(event to safeDetails)
            }
        })

        // Test logging with sensitive tokens and urls
        VoxLiveProtocolLogger.log(
            VoxLiveProtocolEvent.VOX_LIVE_PROTO_REQUEST_START,
            "Request to https://api.browser.yandex.ru/video-translation/translate with token=SECRET_TOKEN_123 and bearer MY_AUTH_BEARER"
        )

        assertEquals(1, capturedEvents.size)
        val details = capturedEvents[0].second

        assertFalse("URL must be redacted", details.contains("api.browser.yandex.ru"))
        assertFalse("Token must be redacted", details.contains("SECRET_TOKEN_123"))
        assertFalse("Bearer must be redacted", details.contains("MY_AUTH_BEARER"))
        assertTrue("Redacted marker must be present", details.contains("<URL_REDACTED>"))
        assertTrue(details.contains("token=<REDACTED>"))
    }
}
