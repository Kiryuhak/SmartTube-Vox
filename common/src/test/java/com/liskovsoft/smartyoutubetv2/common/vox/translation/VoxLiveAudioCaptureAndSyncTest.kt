package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
class VoxLiveAudioCaptureAndSyncTest {

    @Before
    fun setUp() {
        VoxLiveFeatureFlags.resetToDefaults()
        VoxLiveAudioTap.reset()
    }

    @After
    fun tearDown() {
        VoxLiveFeatureFlags.resetToDefaults()
        VoxLiveAudioTap.reset()
    }

    @Test
    fun test1_tapDisabledByDefaultZeroOverhead() {
        assertFalse("Audio tap must be disabled by default", VoxLiveAudioTap.isEnabled())
        assertFalse("Capture flag must be false by default", VoxLiveFeatureFlags.VOX_LIVE_AUDIO_CAPTURE_EXPERIMENTAL)

        val buffer = ByteBuffer.allocate(1024)
        buffer.put(ByteArray(1024) { 1 })
        buffer.flip()

        VoxLiveAudioTap.onAudioOutputBuffer(buffer, 1_000_000L, 48000, 2, 2)

        val diag = VoxLiveAudioTap.getDiagnostics()
        assertEquals("No bytes should be captured when disabled", 0L, diag.totalCapturedBytes)
        assertEquals("Buffer position must remain unchanged", 0, buffer.position())
    }

    @Test
    fun test2_tapCapturesBufferWhenEnabled() {
        VoxLiveFeatureFlags.VOX_LIVE_AUDIO_CAPTURE_EXPERIMENTAL = true
        VoxLiveAudioTap.setEnabled(true)
        assertTrue(VoxLiveAudioTap.isEnabled())

        var capturedCallbackBuffer: VoxLiveCapturedBuffer? = null
        VoxLiveAudioTap.setListener(object : VoxLiveAudioTapListener {
            override fun onCapturedBuffer(buffer: VoxLiveCapturedBuffer) {
                capturedCallbackBuffer = buffer
            }

            override fun onDiscontinuity(oldGeneration: Long, newGeneration: Long, newBasePtsUs: Long) {}
        })

        // 48000 Hz, 2 channels, 16-bit PCM = 4 bytes per frame. 1000 frames = 4000 bytes = 20.833 ms
        val testBytes = ByteArray(4000) { (it % 128).toByte() }
        val buffer = ByteBuffer.wrap(testBytes)

        VoxLiveAudioTap.onAudioOutputBuffer(buffer, 5_000_000L, 48000, 2, 2)

        assertNotNull("Buffer should have been captured", capturedCallbackBuffer)
        assertEquals("First normalized PTS must start at 0", 0L, capturedCallbackBuffer?.ptsUs)
        assertEquals("Channel count match", 2, capturedCallbackBuffer?.channelCount)
        assertEquals("Sample rate match", 48000, capturedCallbackBuffer?.sampleRate)
        assertEquals("Buffer position unchanged", 0, buffer.position())

        val diag = VoxLiveAudioTap.getDiagnostics()
        assertEquals(4000L, diag.totalCapturedBytes)
        assertTrue(diag.captureActive)
    }

    @Test
    fun test3_ptsNormalizerLinearAndDiscontinuity() {
        val normalizer = VoxLiveAudioPtsNormalizer()

        // 1. First sample anchors timeline at 0
        val res1 = normalizer.normalize(10_000_000L)
        assertEquals(0L, res1.normalizedPtsUs)
        assertEquals(1L, res1.generation)
        assertFalse(res1.isDiscontinuity)

        // 2. Next sample advances linearly
        val res2 = normalizer.normalize(10_020_000L) // +20 ms
        assertEquals(20_000L, res2.normalizedPtsUs)
        assertEquals(1L, res2.generation)
        assertFalse(res2.isDiscontinuity)

        // 3. Forward jump > 10 seconds -> Discontinuity!
        val res3 = normalizer.normalize(25_000_000L) // +14.98 sec jump
        assertTrue("Forward jump > 10s must trigger discontinuity", res3.isDiscontinuity)
        assertEquals(2L, res3.generation)
        assertEquals("New generation PTS resets to 0", 0L, res3.normalizedPtsUs)
        assertEquals(1L, res3.oldGeneration)

        // 4. Backward seek > 500 ms -> Discontinuity!
        val res4 = normalizer.normalize(24_000_000L) // -1.0 sec backward
        assertTrue("Backward seek > 500ms must trigger discontinuity", res4.isDiscontinuity)
        assertEquals(3L, res4.generation)
        assertEquals(0L, res4.normalizedPtsUs)
        assertEquals(2L, res4.oldGeneration)
    }

    @Test
    fun test4_fragmentAssemblerTargetDurationAndQueue() {
        val assembler = VoxLiveAudioFragmentAssembler(initialTargetDurationUs = 500_000L) // 500 ms (min allowed duration)

        // 5 frames: 100 ms duration each, 1000 bytes each = 500 ms total
        for (i in 0 until 5) {
            val frame = VoxLiveCapturedBuffer(
                data = ByteArray(1000) { i.toByte() },
                ptsUs = i * 100_000L,
                durationUs = 100_000L,
                sampleRate = 48000,
                channelCount = 2,
                encoding = 2,
                generation = 1L
            )
            assembler.pushBuffer(frame)
        }

        // At 500 ms total, a fragment should be ready
        assertEquals(1, assembler.getQueuedFragmentCount())
        val fragment = assembler.pollFragment()
        assertNotNull(fragment)
        assertEquals(0L, fragment?.sequence)
        assertEquals(1L, fragment?.generation)
        assertEquals(0L, fragment?.startPtsUs)
        assertEquals(500_000L, fragment?.endPtsUs)
        assertEquals(500_000L, fragment?.durationUs)
        assertEquals(5000, fragment?.data?.size)
        assertEquals(5, fragment?.frameCount)

        // Queue should now be empty
        assertNull(assembler.pollFragment())
    }

    @Test
    fun test5_fragmentAssemblerMemoryBoundsDropOldest() {
        val assembler = VoxLiveAudioFragmentAssembler(initialTargetDurationUs = 500_000L)

        // Push 20 fragments (each 500 ms) to exceed MAX_QUEUED_FRAGMENTS (15)
        for (i in 0 until 20) {
            val frame = VoxLiveCapturedBuffer(
                data = ByteArray(500),
                ptsUs = i * 500_000L,
                durationUs = 500_000L,
                sampleRate = 48000,
                channelCount = 2,
                encoding = 2,
                generation = 1L
            )
            assembler.pushBuffer(frame)
        }

        assertEquals("Queue should be capped at 15", 15, assembler.getQueuedFragmentCount())
        assertEquals("5 oldest fragments should have been dropped", 5, assembler.getDroppedFragmentCount())
    }

    @Test
    fun test6_ptsSyncControllerDecisions() {
        val syncController = VoxLivePtsSyncController(initialTargetDelayUs = 0L)

        // 1. In sync (|drift| <= 150ms) -> PLAY
        val d1 = syncController.evaluate(masterPositionUs = 5_000_000L, secondaryPtsUs = 5_050_000L)
        assertEquals(VoxLivePtsSyncAction.PLAY, d1.action)
        assertEquals(50L, d1.driftMs)

        // 2. Secondary ahead (drift > 150ms) -> WAIT
        val d2 = syncController.evaluate(masterPositionUs = 5_000_000L, secondaryPtsUs = 5_250_000L)
        assertEquals(VoxLivePtsSyncAction.WAIT, d2.action)
        assertEquals(250L, d2.driftMs)

        // 3. Secondary lagging (-500ms <= drift < -150ms) -> DROP
        val d3 = syncController.evaluate(masterPositionUs = 5_000_000L, secondaryPtsUs = 4_750_000L)
        assertEquals(VoxLivePtsSyncAction.DROP, d3.action)
        assertEquals(-250L, d3.driftMs)

        // 4. Critical desync (|drift| > 500ms) -> REANCHOR
        val d4 = syncController.evaluate(masterPositionUs = 5_000_000L, secondaryPtsUs = 6_000_000L)
        assertEquals(VoxLivePtsSyncAction.REANCHOR, d4.action)
        assertEquals(1000L, d4.driftMs)

        // 5. Underrun / empty buffer -> FALLBACK
        val d5 = syncController.evaluate(masterPositionUs = 5_000_000L, secondaryPtsUs = 5_000_000L, isSecondaryBufferEmpty = true)
        assertEquals(VoxLivePtsSyncAction.FALLBACK, d5.action)
        assertTrue(syncController.getDiagnostics().fallbackActive)
    }

    @Test
    fun test7_ptsSyncControllerPlaybackSpeedSupport() {
        val syncController = VoxLivePtsSyncController()
        assertEquals(1.0f, syncController.getPlaybackSpeed(), 0.001f)

        val supportedSpeeds = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        for (speed in supportedSpeeds) {
            syncController.onPlaybackSpeedChanged(speed)
            assertEquals(speed, syncController.getPlaybackSpeed(), 0.001f)
        }
    }

    @Test
    fun test8_secondaryAudioPlayerRoutingAndFallback() {
        var requestedPrimaryVolume: Float? = null
        var fallbackCalled = false

        val callback = object : VoxSecondaryAudioCallback {
            override fun onPrimaryVolumeAdjustRequested(volume: Float) {
                requestedPrimaryVolume = volume
            }

            override fun onUnderrunFallback() {
                fallbackCalled = true
            }
        }

        val player = VoxLiveSecondaryAudioPlayer(
            initialRoutingMode = VoxAudioRoutingMode.ORIGINAL_ONLY,
            initialCallback = callback
        )

        // Default ORIGINAL_ONLY
        assertEquals(VoxAudioRoutingMode.ORIGINAL_ONLY, player.getRoutingMode())

        // Switch to SECONDARY_ONLY
        player.setRoutingMode(VoxAudioRoutingMode.SECONDARY_ONLY)
        assertEquals(VoxAudioRoutingMode.SECONDARY_ONLY, player.getRoutingMode())
        assertEquals("Primary audio should be ducked to 0 in SECONDARY_ONLY", 0.0f, requestedPrimaryVolume ?: -1f, 0.001f)

        // Switch to MIX_DEBUG
        player.setRoutingMode(VoxAudioRoutingMode.MIX_DEBUG)
        assertEquals("Primary audio should be ducked to 0.2 in MIX_DEBUG", 0.2f, requestedPrimaryVolume ?: -1f, 0.001f)

        // Trigger underrun fallback -> must restore primary volume to 1.0f
        player.triggerUnderrunFallback()
        assertTrue(player.isFallbackActive())
        assertTrue(fallbackCalled)
        assertEquals("Primary audio volume must be restored to 1.0 upon fallback", 1.0f, requestedPrimaryVolume ?: -1f, 0.001f)
        assertEquals(1, player.getUnderrunCount())
    }

    @Test
    fun test9_diagnosticsSerializationZeroTelemetry() {
        val diag = VoxLiveAudioSyncDiagnostics(
            captureActive = true,
            routingMode = VoxAudioRoutingMode.MIX_DEBUG,
            generation = 2L,
            lastPtsUs = 4_500_000L,
            currentDriftMs = 45L,
            maxDriftMs = 120L,
            underrunCount = 0,
            reanchorCount = 1,
            droppedFrames = 0,
            totalCapturedFragments = 10L,
            totalCapturedBytes = 40960L,
            fallbackActive = false
        )

        val map = diag.toMap()
        assertEquals(true, map["captureActive"])
        assertEquals("mix_debug", map["routingMode"])
        assertEquals(2L, map["generation"])
        assertEquals(45L, map["currentDriftMs"])
        assertEquals(40960L, map["totalCapturedBytes"])

        val json = diag.toJson()
        assertEquals("mix_debug", json.getString("routingMode"))
        assertEquals(45, json.getInt("currentDriftMs"))
        assertFalse(json.toString().contains("http://"))
        assertFalse(json.toString().contains("videoTitle"))
    }
}
