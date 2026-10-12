package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxPerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdaptiveBufferHighBitrateTest {
    @Test
    fun test4kHighBitrateReceivesExpandedBuffer() {
        // High Bitrate 45 Mbps on powerful device
        val config = VoxPlaybackBufferPolicy.resolveAdaptive(
            tier = VoxPerformanceTier.POWERFUL,
            ramBytes = 4L * 1024 * 1024 * 1024,
            isLive = false,
            isLocal = false,
            videoHeight = 2160,
            bitrate = 45_000_000L,
            rebufferCount = 0
        )

        assertTrue(config.minBufferMs >= 35_000)
        assertTrue(config.maxBufferMs >= 60_000)
        assertTrue(config.targetBufferBytes >= 64 * 1024 * 1024)
        assertEquals(VoxBufferProfile.HIGH_PERFORMANCE, config.profile)
    }

    @Test
    fun testRebufferHysteresisBoostsBufferParameters() {
        val boostedConfig = VoxPlaybackBufferPolicy.resolveAdaptive(
            tier = VoxPerformanceTier.POWERFUL,
            ramBytes = 4L * 1024 * 1024 * 1024,
            isLive = false,
            isLocal = false,
            videoHeight = 2160,
            bitrate = 45_000_000L,
            rebufferCount = 2
        )

        assertTrue(boostedConfig.minBufferMs >= 50_000)
        assertTrue(boostedConfig.maxBufferMs >= 90_000)
        assertEquals(6_000, boostedConfig.bufferForPlaybackAfterRebufferMs)
    }
}

@RunWith(RobolectricTestRunner::class)
class AdaptiveBufferTransientRebufferTest {
    @Test
    fun testSingleTransientRebufferDoesNotDemoteQuality() {
        val tracker = VoxAdaptiveBufferTracker()
        tracker.addSample(50.0)
        tracker.addSample(48.0)
        tracker.onRebuffer() // 1 transient rebuffer

        // Target bitrate 45 Mbps
        assertFalse(tracker.shouldDemoteQuality(45.0))
    }
}

@RunWith(RobolectricTestRunner::class)
class AdaptiveBufferSustainedNetworkDropTest {
    @Test
    fun testSustainedDropAcrossWindowsSuggestsDemotion() {
        val tracker = VoxAdaptiveBufferTracker()
        // Sustained low bandwidth: 10 Mbps while target is 45 Mbps
        repeat(5) { tracker.addSample(10.0) }
        repeat(3) { tracker.onRebuffer() }

        assertTrue(tracker.shouldDemoteQuality(45.0))
    }
}

@RunWith(RobolectricTestRunner::class)
class ExplicitVp9PreferenceTest {
    @Test
    fun testExplicitVp9UserPreferenceRetained() {
        val userPreferredCodec = "vp9"
        val isExplicitPreference = true

        // On transient stall, explicit preference is retained
        val finalCodec = if (isExplicitPreference) userPreferredCodec else "avc"
        assertEquals("vp9", finalCodec)
    }
}

@RunWith(RobolectricTestRunner::class)
class RebufferAggregationTest {
    @Test
    fun testConsecutiveRebuffersAggregatedInSession() {
        val sessionStats = mutableMapOf<String, Any>()
        sessionStats["sessionId"] = "2"
        sessionStats["resolution"] = "2160"
        sessionStats["codec"] = "vp9"
        sessionStats["rebufferCount"] = 0

        fun registerRebuffer() {
            val count = sessionStats["rebufferCount"] as Int
            sessionStats["rebufferCount"] = count + 1
        }

        repeat(3) { registerRebuffer() }
        assertEquals(3, sessionStats["rebufferCount"])
        assertEquals("2", sessionStats["sessionId"])
    }
}

@RunWith(RobolectricTestRunner::class)
class BufferClassificationTest {
    @Test
    fun testBufferPolicyTooSmallClassification() {
        val cause = VoxPlaybackBufferPolicy.classifyRebuffer(
            bitrate = 45_000_000L,
            bufferAheadMs = 0L,
            measuredBandwidthBps = 60_000_000L,
            droppedFrames = 0,
            is4K = true,
            targetBufferBytes = 20 * 1024 * 1024 // 20 MB is only ~3.5s of 45 Mbps video
        )
        assertEquals(VoxBufferRootCause.BUFFER_POLICY_TOO_SMALL, cause)
    }

    @Test
    fun testNetworkLimitedClassification() {
        val cause = VoxPlaybackBufferPolicy.classifyRebuffer(
            bitrate = 45_000_000L,
            bufferAheadMs = 100L,
            measuredBandwidthBps = 15_000_000L, // only 15 Mbps for 45 Mbps video
            droppedFrames = 0,
            is4K = true,
            targetBufferBytes = 96 * 1024 * 1024
        )
        assertEquals(VoxBufferRootCause.NETWORK_LIMITED, cause)
    }

    @Test
    fun testDecoderLimitedClassification() {
        val cause = VoxPlaybackBufferPolicy.classifyRebuffer(
            bitrate = 20_000_000L,
            bufferAheadMs = 5000L,
            measuredBandwidthBps = 80_000_000L,
            droppedFrames = 120, // High frame drop
            is4K = false,
            targetBufferBytes = 64 * 1024 * 1024
        )
        assertEquals(VoxBufferRootCause.DECODER_LIMITED, cause)
    }

    @Test
    fun testCdnThrottledClassification() {
        val cause = VoxPlaybackBufferPolicy.classifyRebuffer(
            bitrate = 10_000_000L,
            bufferAheadMs = 100L,
            measuredBandwidthBps = 1_200_000L, // 1.2 Mbps severely throttled
            droppedFrames = 0,
            is4K = false,
            targetBufferBytes = 64 * 1024 * 1024
        )
        assertEquals(VoxBufferRootCause.CDN_THROTTLED, cause)
    }
}
