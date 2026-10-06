package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxTranslation2ArchitectureTest {

    @Test
    fun testSessionLifecycleTransitions() {
        val session = VoxTranslationSession(sessionId = "test_sess_1")
        assertEquals(VoxTranslationSessionState.IDLE, session.getState())

        session.start("vid_101", 0L)
        // Transitions from STARTING to BUFFERING on start
        assertEquals(VoxTranslationSessionState.BUFFERING, session.getState())

        session.onTranslating()
        assertEquals(VoxTranslationSessionState.TRANSLATING, session.getState())

        session.onPlaying()
        assertEquals(VoxTranslationSessionState.PLAYING, session.getState())

        session.pause()
        assertEquals(VoxTranslationSessionState.PAUSED, session.getState())

        session.resume()
        assertEquals(VoxTranslationSessionState.PLAYING, session.getState())

        session.stop()
        assertEquals(VoxTranslationSessionState.STOPPED, session.getState())
    }

    @Test
    fun testBoundedQueueCapacityAndPurge() {
        val queue = VoxTranslationQueue(maxCapacity = 3)
        val seg1 = VoxTranslationSegment("seg_1", 1L, 0L, 5000L, "src_1", 1L)
        val seg2 = VoxTranslationSegment("seg_2", 2L, 5000L, 10000L, "src_2", 1L)
        val seg3 = VoxTranslationSegment("seg_3", 3L, 10000L, 15000L, "src_3", 1L)
        val seg4 = VoxTranslationSegment("seg_4", 4L, 15000L, 20000L, "src_4", 1L)

        assertTrue(queue.enqueue(seg1))
        assertTrue(queue.enqueue(seg2))
        assertTrue(queue.enqueue(seg3))
        assertEquals(3, queue.size())

        // Without played/discarded segments, inserting a 4th exceeds maxCapacity
        assertFalse(queue.enqueue(seg4))

        // Mark seg1 as PLAYED; now enqueue can purge it to make room
        queue.updateSegmentState("seg_1", VoxSegmentState.PLAYED)
        assertTrue(queue.enqueue(seg4))
        assertEquals(3, queue.size())
        assertFalse(queue.containsSegment("seg_1"))
        assertTrue(queue.containsSegment("seg_4"))
    }

    @Test
    fun testDuplicateSegmentRejection() {
        val queue = VoxTranslationQueue(maxCapacity = 10)
        val seg1 = VoxTranslationSegment("seg_100", 10L, 0L, 5000L, "src_100", 1L)
        val dupId = VoxTranslationSegment("seg_100", 11L, 5000L, 10000L, "src_100", 1L)
        val dupSeq = VoxTranslationSegment("seg_101", 10L, 5000L, 10000L, "src_101", 1L)

        assertTrue(queue.enqueue(seg1))
        assertFalse("Duplicate ID must be rejected", queue.enqueue(dupId))
        assertFalse("Duplicate sequence must be rejected", queue.enqueue(dupSeq))
        assertEquals(1, queue.size())
    }

    @Test
    fun testOrderedDelivery() {
        val queue = VoxTranslationQueue(maxCapacity = 10)
        // Enqueue out of order
        val seg2 = VoxTranslationSegment("seg_2", 2L, 5000L, 10000L, "src_2", 1L)
        val seg1 = VoxTranslationSegment("seg_1", 1L, 0L, 5000L, "src_1", 1L)
        val seg3 = VoxTranslationSegment("seg_3", 3L, 10000L, 15000L, "src_3", 1L)

        assertTrue(queue.enqueue(seg2))
        assertTrue(queue.enqueue(seg1))
        assertTrue(queue.enqueue(seg3))

        val first = queue.pollNextPending()
        assertNotNull(first)
        assertEquals(1L, first!!.sequence)

        val second = queue.pollNextPending()
        assertNotNull(second)
        assertEquals(2L, second!!.sequence)

        val third = queue.pollNextPending()
        assertNotNull(third)
        assertEquals(3L, third!!.sequence)

        assertNull(queue.pollNextPending())
    }

    @Test
    fun testSeekInvalidatesOldSegments() {
        val session = VoxTranslationSession("sess_seek")
        session.start("vid_seek", 0L)
        val initialGen = session.getGenerationId()

        val seg1 = VoxTranslationSegment("seg_1", 1L, 0L, 5000L, "src_1", initialGen)
        val seg2 = VoxTranslationSegment("seg_2", 2L, 5000L, 10000L, "src_2", initialGen)
        session.queue.enqueue(seg1)
        session.queue.enqueue(seg2)
        assertEquals(2, session.queue.size())

        // Seek forward to 30000ms
        session.onSeek(30000L)
        val newGen = session.getGenerationId()
        assertNotEquals(initialGen, newGen)
        assertEquals(0, session.queue.size())
        assertEquals(VoxTranslationSessionState.BUFFERING, session.getState())
    }

    @Test
    fun testVideoChangeCancelsOldSession() {
        val session = VoxTranslationSession("sess_vid")
        session.start("vid_A", 0L)
        val genA = session.getGenerationId()

        val segA = VoxTranslationSegment("seg_A", 1L, 0L, 5000L, "src_A", genA)
        session.queue.enqueue(segA)
        session.buffer.addReadySegment(VoxTranslationSegment("seg_ready", 2L, 0L, 5000L, "src", genA, VoxSegmentState.READY))

        assertEquals(1, session.queue.size())
        assertEquals(1, session.buffer.getReadySegmentCount())

        // Switch to video B
        session.onVideoChanged("vid_B")
        assertEquals(VoxTranslationSessionState.IDLE, session.getState())
        assertEquals("vid_B", session.getCurrentVideoId())
        assertEquals(0, session.queue.size())
        assertEquals(0, session.buffer.getReadySegmentCount())
        assertFalse(session.isFallbackActive())
        assertTrue(session.getGenerationId() > genA)
    }

    @Test
    fun testRetryPolicyBoundsAndNonRetryable() {
        val policy = VoxTranslationRetryPolicy(maxRetries = 3, baseBackoffMs = 1000L, maxBackoffMs = 8000L)

        val netError = VoxTranslationError(VoxTranslationErrorType.NETWORK, "Connection drop")
        assertTrue(policy.shouldRetry(netError, 0))
        assertTrue(policy.shouldRetry(netError, 1))
        assertTrue(policy.shouldRetry(netError, 2))
        assertFalse(policy.shouldRetry(netError, 3)) // Exceeded maxRetries

        val authError = VoxTranslationError(VoxTranslationErrorType.AUTH, "Invalid token")
        assertFalse("Auth error must never be retried indefinitely", policy.shouldRetry(authError, 0))

        val unsuppError = VoxTranslationError(VoxTranslationErrorType.UNSUPPORTED, "Live not supported")
        assertFalse("Unsupported format must never be retried", policy.shouldRetry(unsuppError, 0))

        // Backoff exponential formula
        assertEquals(1000L, policy.calculateBackoffMs(0))
        assertEquals(2000L, policy.calculateBackoffMs(1))
        assertEquals(4000L, policy.calculateBackoffMs(2))
        assertEquals(8000L, policy.calculateBackoffMs(3)) // Cap at maxBackoffMs (8000)
    }

    @Test
    fun testFallbackModel() {
        val session = VoxTranslationSession("sess_fb")
        session.start("vid_fb", 0L)
        session.onPlaying()
        assertFalse(session.isFallbackActive())

        session.fallbackToOriginal("Буфер истощён")
        assertTrue(session.isFallbackActive())
        assertEquals(VoxTranslationSessionState.DEGRADED, session.getState())

        session.restoreTranslation()
        assertFalse(session.isFallbackActive())
        assertEquals(VoxTranslationSessionState.PLAYING, session.getState())

        // Failure leads to fallback
        val err = VoxTranslationError(VoxTranslationErrorType.BACKEND_UNAVAILABLE, "Сервер недоступен")
        session.fail(err)
        assertTrue(session.isFallbackActive())
        assertEquals(VoxTranslationSessionState.FAILED, session.getState())
        assertEquals(err, session.getLastError())
    }

    @Test
    fun testPlaybackSpeedScaling() {
        val buffer = VoxTranslatedBuffer(minimumPlayableBufferMs = 4000L)

        // 1.0x
        buffer.setPlaybackSpeed(1.0f)
        assertEquals(4000L, buffer.getEffectiveMinimumBufferMs())

        // 1.25x
        buffer.setPlaybackSpeed(1.25f)
        assertEquals(5000L, buffer.getEffectiveMinimumBufferMs())

        // 1.5x
        buffer.setPlaybackSpeed(1.5f)
        assertEquals(6000L, buffer.getEffectiveMinimumBufferMs())

        // 1.75x
        buffer.setPlaybackSpeed(1.75f)
        assertEquals(7000L, buffer.getEffectiveMinimumBufferMs())

        // 2.0x
        buffer.setPlaybackSpeed(2.0f)
        assertEquals(8000L, buffer.getEffectiveMinimumBufferMs())
    }

    @Test
    fun testBufferUnderrunDetection() {
        val buffer = VoxTranslatedBuffer(minimumPlayableBufferMs = 4000L)
        buffer.setPlaybackSpeed(1.0f)

        // Empty buffer is critical
        assertTrue(buffer.isBufferCritical(0L))

        // Add 5000ms segment (0..5000)
        buffer.addReadySegment(VoxTranslationSegment("s1", 1L, 0L, 5000L, "src", 1L, VoxSegmentState.READY))
        assertEquals(5000L, buffer.getBufferedAheadMs(0L))
        assertTrue(buffer.hasSufficientBuffer(0L))
        assertFalse(buffer.isBufferCritical(0L))

        // Playback moves to 4200ms -> remaining buffer is 800ms (< 2000ms threshold)
        assertEquals(800L, buffer.getBufferedAheadMs(4200L))
        assertTrue(buffer.isBufferCritical(4200L))
    }

    @Test
    fun testHonestBackendVodOnlyCapability() {
        val caps = YandexVotBackendCapabilities
        assertTrue("VOD must be supported", caps.supportsVod)
        assertFalse("Live segments must NOT be claimed as supported", caps.supportsLiveSegments)
        assertEquals(TranslationCapabilityState.SUPPORTED, caps.vodCapability)
        assertEquals(TranslationCapabilityState.UNSUPPORTED, caps.liveCapability)
        assertFalse(caps.liveCapability.isSupported)
    }

    @Test
    fun testUnknownNotEqualsSupported() {
        val unknown = TranslationCapabilityState.UNKNOWN
        val supported = TranslationCapabilityState.SUPPORTED
        assertNotEquals(unknown, supported)
        assertFalse("UNKNOWN must NOT be treated as supported", unknown.isSupported)
    }

    @Test
    fun testNoStaleSegmentAfterGenerationChange() {
        val syncEngine = VoxTranslationSyncEngine(criticalBufferFloorMs = 1500L)

        // Same generation -> normal evaluation
        val normalDecision = syncEngine.evaluate(
            sourcePositionMs = 10000L,
            translatedPositionMs = 10020L,
            playbackSpeed = 1.0f,
            bufferedTranslatedMs = 8000L,
            generationId = 2L,
            activeGenerationId = 2L
        )
        assertEquals(VoxSyncAction.PLAY, normalDecision.action)

        // Old generation (generationId=1 while active=2) -> must fallback to original
        val staleDecision = syncEngine.evaluate(
            sourcePositionMs = 10000L,
            translatedPositionMs = 10020L,
            playbackSpeed = 1.0f,
            bufferedTranslatedMs = 8000L,
            generationId = 1L,
            activeGenerationId = 2L
        )
        assertEquals(VoxSyncAction.FALLBACK_ORIGINAL, staleDecision.action)
        assertTrue(staleDecision.reasonRu.contains("устарел"))
    }

    @Test
    fun testDiagnosticsZeroTelemetrySnapshot() {
        val session = VoxTranslationSession("diag_sess")
        session.start("vid_xyz", 0L)
        session.onPlaying()

        val snap = session.getDiagnosticsSnapshot(0L)
        assertEquals("auto", snap.mode)
        assertEquals("VOD_ONLY", snap.backendCapability)
        assertEquals("PLAYING", snap.sessionState)
        assertFalse(snap.fallbackActive)
        assertNull(snap.lastErrorCategory)

        val map = snap.toMap()
        assertEquals("VOD_ONLY", map["backendCapability"])
        assertFalse(map.containsKey("sourceUrl"))
        assertFalse(map.containsKey("token"))
        assertFalse(map.containsKey("title"))

        val jsonStr = snap.toJsonString()
        assertTrue(jsonStr.contains("\"backendCapability\":\"VOD_ONLY\""))
        assertFalse(jsonStr.contains("sourceUrl"))
        assertFalse(jsonStr.contains("token"))
        assertFalse(jsonStr.contains("title"))
    }
}
