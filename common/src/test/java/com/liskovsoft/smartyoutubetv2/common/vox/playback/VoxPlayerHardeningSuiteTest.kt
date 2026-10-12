package com.liskovsoft.smartyoutubetv2.common.vox.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StartupStageTimingTest {
    @Test
    fun testStartupStageTimingAndProtocol() {
        VoxStartupProfiler.onUserOpen(sessionId = 12345L, engine = "CRONET")
        VoxStartupProfiler.updateTransportProtocol("h2")
        assertEquals("H2", VoxStartupProfiler.getTransportProtocol())

        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_REQUEST_START, 100L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_MANIFEST_FETCH, 300L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_MANIFEST_PARSE, 50L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_TRACK_SELECT, 80L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_DECODER_INIT, 250L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_BUFFER_FILL, 400L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_FIRST_FRAME, 120L)

        val summary = VoxStartupProfiler.getProfileSummary()
        assertEquals("H2", summary["transportProtocol"])
        assertEquals("CRONET", summary["engine"])
        assertEquals(VoxStartupProfiler.STAGE_BUFFER_FILL, summary["slowestStage"])
    }
}

class StartupRootCauseTest {
    @Test
    fun testStartupRootCauseManifest() {
        VoxStartupProfiler.onUserOpen(sessionId = 99999L, engine = "CRONET")
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_REQUEST, 100L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_MANIFEST_FETCH, 1800L) // Slowest stage
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_DECODER_INIT, 200L)

        val summary = VoxStartupProfiler.getProfileSummary()
        assertEquals(VoxStartupProfiler.ROOT_CAUSE_MANIFEST, summary["rootCause"])
    }
}

class LowBitrateUnknownBandwidthTest {
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

class LowBitrateHealthyBandwidthTest {
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

class LowBandwidthNetworkLimitedTest {
    @Test
    fun testDeficitBandwidthClassifiesAsNetworkLimited() {
        val cause = VoxPlaybackBufferPolicy.classifyRebuffer(
            bitrate = 15_000_000L, // 15 Mbps
            bufferAheadMs = 0L,
            measuredBandwidthBps = 5_000_000L, // 5 Mbps clearly below 15 Mbps
            droppedFrames = 0,
            is4K = false
        )
        assertEquals(VoxBufferRootCause.NETWORK_LIMITED, cause)
    }
}

class HighBitrateClassificationTest {
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
