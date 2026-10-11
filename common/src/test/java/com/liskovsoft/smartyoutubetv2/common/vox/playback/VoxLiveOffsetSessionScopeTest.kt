package com.liskovsoft.smartyoutubetv2.common.vox.playback

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class VoxLiveOffsetSessionScopeTest {

    @Before
    fun setUp() {
        VoxLivePlaybackMonitor.resetForTesting()
    }

    @Test
    fun testLiveOffsetInitialZero() {
        val metrics = VoxLivePlaybackMonitor.getMetricsSummary()
        assertEquals(0L, metrics.liveOffsetMs)
    }

    @Test
    fun testLiveOffsetScopedToLivePlaybackAndResetsOnStop() {
        // Start live session
        VoxLivePlaybackMonitor.onPlaybackStart("test-stream-marker", isLive = true)
        VoxLivePlaybackMonitor.onReady(bufferedDurationMs = 2000L, liveOffsetMs = 1500L, bandwidthEstimate = 5000000L)
        val metricsActive = VoxLivePlaybackMonitor.getMetricsSummary()
        assertEquals(1500L, metricsActive.liveOffsetMs)

        // Stop playback
        VoxLivePlaybackMonitor.onPlaybackStopped()
        val metricsStopped = VoxLivePlaybackMonitor.getMetricsSummary()
        assertEquals(0L, metricsStopped.liveOffsetMs)
    }

    @Test
    fun testLiveOffsetIgnoredForVodPlayback() {
        // VOD playback session (isLive = false)
        VoxLivePlaybackMonitor.onPlaybackStart("test-vod-marker", isLive = false)
        VoxLivePlaybackMonitor.onReady(bufferedDurationMs = 5000L, liveOffsetMs = 2500L, bandwidthEstimate = 10000000L)
        // Offset must stay 0 for VOD
        val metrics = VoxLivePlaybackMonitor.getMetricsSummary()
        assertEquals(0L, metrics.liveOffsetMs)
    }

    @Test
    fun testSanitizeExcessiveOffset() {
        VoxLivePlaybackMonitor.onPlaybackStart("test-live-marker", isLive = true)
        // Offsets greater than 12 hours (43_200_000 ms) are rejected as stale/invalid (such as the 169432254 ms bug)
        VoxLivePlaybackMonitor.onReady(bufferedDurationMs = 2000L, liveOffsetMs = 169432254L, bandwidthEstimate = 5000000L)
        val metrics = VoxLivePlaybackMonitor.getMetricsSummary()
        assertEquals(0L, metrics.liveOffsetMs)

        // Valid offset is accepted
        VoxLivePlaybackMonitor.onReady(bufferedDurationMs = 2000L, liveOffsetMs = 4500L, bandwidthEstimate = 5000000L)
        val metricsValid = VoxLivePlaybackMonitor.getMetricsSummary()
        assertEquals(4500L, metricsValid.liveOffsetMs)
    }
}
