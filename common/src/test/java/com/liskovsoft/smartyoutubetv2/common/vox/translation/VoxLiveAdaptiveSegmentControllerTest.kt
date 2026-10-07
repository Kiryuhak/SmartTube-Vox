package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoxLiveAdaptiveSegmentControllerTest {

    private lateinit var controller: VoxLiveAdaptiveSegmentController
    private lateinit var mockTransport: FakeGatewayTransport

    class FakeGatewayTransport : VoxLiveGatewayTransport {
        var createSessionCalled = false
        var closeSessionCalled = false
        var sendFailures = 0
        var sentSegments = mutableListOf<VoxTranslationSegment>()
        var simulateBackpressure = false
        var currentSessionId = "fake_session_123"

        override fun createSession(options: Map<String, Any>): Result<VoxLiveGatewaySessionInfo> {
            createSessionCalled = true
            return Result.success(VoxLiveGatewaySessionInfo(
                sessionId = currentSessionId,
                generation = 1,
                state = "ACTIVE"
            ))
        }

        override fun sendSegment(segment: VoxTranslationSegment, payload: ByteArray?): Result<VoxLiveSegmentSentResult> {
            sentSegments.add(segment)
            if (simulateBackpressure) {
                return Result.success(VoxLiveSegmentSentResult(
                    sequence = segment.sequence,
                    generation = segment.generationId,
                    status = "BACKPRESSURE"
                ))
            }
            if (sendFailures > 0) {
                sendFailures--
                return Result.failure(RuntimeException("Network failure"))
            }
            return Result.success(VoxLiveSegmentSentResult(
                sequence = segment.sequence,
                generation = segment.generationId,
                status = "QUEUED",
                estimatedReadyMs = 500L
            ))
        }

        override fun pollResult(sequence: Long, generation: Long): Result<VoxLiveSegmentResult?> {
            return Result.success(VoxLiveSegmentResult(
                sequence = sequence,
                generation = generation,
                sourceStartMs = sequence * 2000L,
                sourceEndMs = (sequence + 1) * 2000L,
                durationMs = 2000L,
                status = "READY",
                text = "Перевод $sequence"
            ))
        }

        override fun closeSession(): Result<Boolean> {
            closeSessionCalled = true
            return Result.success(true)
        }
    }

    @Before
    fun setUp() {
        mockTransport = FakeGatewayTransport()
        controller = VoxLiveAdaptiveSegmentController(transport = mockTransport)
    }

    @Test
    fun testControllerStartAndStop() {
        assertFalse(controller.isStarted())
        controller.start("live_vid_1")
        assertTrue(controller.isStarted())
        assertTrue(mockTransport.createSessionCalled)
        assertEquals("ACTIVE", controller.getGatewaySessionState())

        controller.stop()
        assertFalse(controller.isStarted())
        assertTrue(mockTransport.closeSessionCalled)
        assertEquals("STOPPED", controller.getGatewaySessionState())
    }

    @Test
    fun testSequenceAndGenerationTracking() {
        controller.start("live_vid_1")
        val initialGen = controller.getGenerationId()

        val d1 = controller.ingestLiveSegment(0L, 2000L, "data0".toByteArray())
        assertEquals(VoxAdaptiveIngestDecision.SEND, d1)
        assertEquals(1L, controller.getSequence())

        val d2 = controller.ingestLiveSegment(2000L, 4000L, "data1".toByteArray())
        assertEquals(VoxAdaptiveIngestDecision.SEND, d2)
        assertEquals(2L, controller.getSequence())

        // Seek triggers generation bump and resets queue
        controller.onSeek(10_000L)
        assertTrue(controller.getGenerationId() > initialGen)
    }

    @Test
    fun testBoundedQueueAndBackpressure() {
        controller.start("live_vid_1")

        // Ingest segments until queue reaches backpressure threshold
        for (i in 0 until 15) {
            controller.ingestLiveSegment(i * 2000L, (i + 1) * 2000L, "data".toByteArray())
        }

        val decisionOverflow = controller.ingestLiveSegment(30000L, 32000L, "data".toByteArray())
        assertEquals(VoxAdaptiveIngestDecision.WAIT, decisionOverflow)

        val diag = controller.getDiagnostics()
        assertTrue(diag.queueDepth <= 30)
    }

    @Test
    fun testDeduplication() {
        controller.start("live_vid_1")

        // First ingest
        val d1 = controller.ingestLiveSegment(0L, 2000L, "data".toByteArray())
        assertEquals(VoxAdaptiveIngestDecision.SEND, d1)

        // Duplicate same segment sequence
        val segment = controller.queue.getSegment(0L)
        assertNotNull(segment)
        val duplicateEnqueued = controller.queue.enqueue(segment!!)
        assertFalse("Duplicate segment must not be enqueued twice", duplicateEnqueued)
    }

    @Test
    fun testSlowProviderAndAdaptiveBufferPolicy() {
        controller.start("live_vid_1")

        val policy = VoxLiveBufferPolicy.BALANCED
        assertEquals(4000L, policy.minimumTranslatedBufferMs)
        assertEquals(8000L, policy.targetTranslatedBufferMs)

        // With high RTT (e.g. 1500ms), buffer adapts upwards
        val adapted = policy.adaptWithLatency(1500L)
        assertTrue(adapted.targetTranslatedBufferMs > policy.targetTranslatedBufferMs)
        assertTrue(adapted.targetTranslatedBufferMs <= adapted.maximumTranslatedBufferMs)
    }

    @Test
    fun testFallbackToOriginalAudioOnBufferUnderrun() {
        controller.start("live_vid_1")

        // Initially no translated audio buffer is ready -> fallback must be active
        val diagInitial = controller.getDiagnostics()
        assertTrue("Fallback must be active when buffer is empty", diagInitial.fallbackActive)

        // Feed ready segments to satisfy minimum buffer (e.g. 6 seconds of audio)
        val seg0 = VoxTranslationSegment("seg_0", 0L, 0L, 2000L, "live", controller.getGenerationId())
        seg0.state = VoxSegmentState.READY
        controller.buffer.addReadySegment(seg0)

        val seg1 = VoxTranslationSegment("seg_1", 1L, 2000L, 4000L, "live", controller.getGenerationId())
        seg1.state = VoxSegmentState.READY
        controller.buffer.addReadySegment(seg1)

        val seg2 = VoxTranslationSegment("seg_2", 2L, 4000L, 6000L, "live", controller.getGenerationId())
        seg2.state = VoxSegmentState.READY
        controller.buffer.addReadySegment(seg2)

        // Result received updates buffer state
        controller.onSegmentResultReceived(VoxLiveSegmentResult(
            sequence = 2L,
            generation = controller.getGenerationId(),
            sourceStartMs = 4000L,
            sourceEndMs = 6000L,
            durationMs = 2000L,
            status = "READY"
        ), currentPlaybackPosMs = 0L)

        val diagReady = controller.getDiagnostics(currentPlaybackPosMs = 0L)
        assertFalse("Fallback should deactivate once buffer reaches safe threshold", diagReady.fallbackActive)
    }

    @Test
    fun testNetworkDisconnectAndRestore() {
        controller.start("live_vid_1")

        // Disconnect
        controller.onNetworkInterrupted()
        assertEquals("DEGRADED", controller.getGatewaySessionState())
        assertTrue(controller.isFallbackActive())

        val decisionNoNet = controller.ingestLiveSegment(0L, 2000L)
        assertEquals(VoxAdaptiveIngestDecision.FALLBACK, decisionNoNet)

        // Restore
        controller.onNetworkRestored()
        assertEquals("ACTIVE", controller.getGatewaySessionState())
    }

    @Test
    fun testStaleResultsIgnoredAcrossGenerations() {
        controller.start("live_vid_1")
        val oldGen = controller.getGenerationId()

        // Jump / seek to bump generation
        controller.onSeek(50_000L)
        val currentGen = controller.getGenerationId()
        assertTrue(currentGen > oldGen)

        // Late arriving result from old generation
        controller.onSegmentResultReceived(VoxLiveSegmentResult(
            sequence = 5L,
            generation = oldGen,
            sourceStartMs = 10000L,
            sourceEndMs = 12000L,
            durationMs = 2000L,
            status = "READY"
        ))

        val diag = controller.getDiagnostics()
        assertEquals("Stale results must be counted as dropped", 1, diag.droppedStaleSegments)
    }

    @Test
    fun testVideoChangeResetsController() {
        controller.start("live_vid_1")
        controller.ingestLiveSegment(0L, 2000L, "d1".toByteArray())
        val gen1 = controller.getGenerationId()

        // Video changes
        controller.onVideoChanged("live_vid_2")
        val gen2 = controller.getGenerationId()
        assertTrue("New video must have incremented generation", gen2 > gen1)
        assertEquals("Queue must be reset on video change", 0, controller.queue.getDepth())
    }

    @Test
    fun testPlaybackSpeeds() {
        controller.start("live_vid_1")

        val speeds = floatArrayOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val policy = controller.bufferPolicy

        for (spd in speeds) {
            controller.setPlaybackSpeed(spd)
            assertEquals(spd, controller.getPlaybackSpeed(), 0.001f)
            val effectiveMin = policy.getEffectiveMinimumBufferMs(spd)
            assertTrue(
                "Effective min buffer at ${spd}x must scale or equal base minimum",
                effectiveMin >= policy.minimumTranslatedBufferMs
            )
        }
    }

    @Test
    fun testSafeDiagnosticsSerialization() {
        controller.start("live_vid_1")
        controller.ingestLiveSegment(0L, 2000L, "payload".toByteArray())

        val diag = controller.getDiagnostics()
        val map = diag.toMap()
        assertEquals("ACTIVE", map["gatewaySessionState"])
        assertEquals(1L, map["segmentSequence"])
        assertEquals(1L, map["generation"])

        val json = diag.toJson()
        assertTrue(json.has("gatewaySessionState"))
        assertTrue(json.has("segmentSequence"))
        assertFalse("JSON must not contain audio payload", json.has("payload"))
        assertFalse("JSON must not contain URLs", json.has("url"))
    }
}
