package com.liskovsoft.smartyoutubetv2.common.vox.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StartupStageClassifierTest {
    @Test
    fun testStartupStageClassification() {
        VoxStartupProfiler.onUserOpen(sessionId = 12345L, engine = "CRONET")
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_REQUEST, 120L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_MANIFEST_FETCH, 1500L) // Slowest stage
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_TRACK_SELECT, 50L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_DECODER_INIT, 200L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_FIRST_FRAME, 100L)

        val summary = VoxStartupProfiler.getProfileSummary()
        assertEquals(VoxStartupProfiler.ROOT_CAUSE_MANIFEST, summary["rootCause"])
        assertEquals(VoxStartupProfiler.STAGE_MANIFEST_FETCH, summary["slowestStage"])
    }
}

class RebufferLowBitrateClassifierTest {
    @Test
    fun testLowBitrateWithNoMeasuredNetworkIsUnknownNotNetworkLimited() {
        val cause = VoxPlaybackBufferPolicy.classifyRebuffer(
            bitrate = 3_430_000L, // 3.43 Mbps 1080p VP9
            bufferAheadMs = 0L,
            measuredBandwidthBps = 0L, // Bandwidth unknown
            droppedFrames = 0,
            is4K = false
        )
        // Must NOT falsely blame network bandwidth!
        assertEquals(VoxBufferRootCause.UNKNOWN, cause)
    }
}

class RebufferHighBitrateClassifierTest {
    @Test
    fun testHighBitrateExceedingNetworkIsBitrateTooHigh() {
        val cause = VoxPlaybackBufferPolicy.classifyRebuffer(
            bitrate = 45_000_000L, // 45 Mbps 2160p
            bufferAheadMs = 0L,
            measuredBandwidthBps = 48_000_000L, // Bandwidth barely matches bitrate
            droppedFrames = 0,
            is4K = true
        )
        assertEquals(VoxBufferRootCause.BITRATE_TOO_HIGH, cause)
    }
}

class NetworkLimitedFalsePositiveTest {
    @Test
    fun testAbundantBandwidthDoesNotClassifyAsNetworkLimited() {
        val cause = VoxPlaybackBufferPolicy.classifyRebuffer(
            bitrate = 3_430_000L, // 3.43 Mbps
            bufferAheadMs = 0L,
            measuredBandwidthBps = 30_000_000L, // 30 Mbps >> 3.43 Mbps (10x faster)
            droppedFrames = 0,
            is4K = false
        )
        assertNotEquals(VoxBufferRootCause.NETWORK_LIMITED, cause)
        assertEquals(VoxBufferRootCause.UNKNOWN, cause)
    }
}
