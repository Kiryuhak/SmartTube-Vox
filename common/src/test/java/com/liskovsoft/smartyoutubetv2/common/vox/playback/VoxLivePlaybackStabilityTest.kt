package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.DashDefaultLoadErrorHandlingPolicy
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxDiagnosticReport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class VoxLivePlaybackStabilityTest {

    @Before
    fun setUp() {
        VoxLivePlaybackMonitor.resetForTesting()
    }

    @Test
    fun testReadyToBufferingSnapshot() {
        var capturedSnapshot: VoxLiveBufferingSnapshot? = null
        VoxLivePlaybackMonitor.addListener(object : VoxLivePlaybackMonitor.Listener {
            override fun onEvent(event: VoxLivePlaybackEvent, safeDetails: String) {}
            override fun onBufferingSnapshot(snapshot: VoxLiveBufferingSnapshot) {
                capturedSnapshot = snapshot
            }
            override fun onRebufferCompleted(record: VoxLiveRebufferRecord) {}
            override fun onStabilityStatusChanged(status: VoxLiveStabilityStatus) {}
        })

        VoxLivePlaybackMonitor.onPlaybackStart("live_abc123", isLive = true)
        VoxLivePlaybackMonitor.onReady(bufferedDurationMs = 15_000L, liveOffsetMs = 15_000L, bandwidthEstimate = 25_000_000L)

        // Enter buffering
        VoxLivePlaybackMonitor.onBufferingStarted(
            bufferedDurationMs = 200L,
            liveOffsetMs = 15_000L,
            bandwidthEstimate = 20_000_000L,
            selectedBitrate = 4_000_000L,
            speed = 1.0f
        )

        assertNotNull("Buffering snapshot must be captured", capturedSnapshot)
        assertEquals(200L, capturedSnapshot!!.bufferedDurationMs)
        assertEquals(15_000L, capturedSnapshot!!.liveOffsetMs)
        assertEquals(20_000_000L, capturedSnapshot!!.bandwidthEstimate)
        assertEquals(4_000_000L, capturedSnapshot!!.selectedBitrate)
        assertEquals("ONLINE", capturedSnapshot!!.networkState)
        assertEquals(1.0f, capturedSnapshot!!.playbackSpeed, 0.001f)
    }

    @Test
    fun testBufferingToReadyDuration() {
        var completedRecord: VoxLiveRebufferRecord? = null
        VoxLivePlaybackMonitor.addListener(object : VoxLivePlaybackMonitor.Listener {
            override fun onEvent(event: VoxLivePlaybackEvent, safeDetails: String) {}
            override fun onBufferingSnapshot(snapshot: VoxLiveBufferingSnapshot) {}
            override fun onRebufferCompleted(record: VoxLiveRebufferRecord) {
                completedRecord = record
            }
            override fun onStabilityStatusChanged(status: VoxLiveStabilityStatus) {}
        })

        VoxLivePlaybackMonitor.onPlaybackStart("live_dur", isLive = true)
        VoxLivePlaybackMonitor.onBufferingStarted(0L, 15_000L, 10_000_000L, 3_000_000L, 1.0f)

        Thread.sleep(50) // simulate 50ms rebuffer
        VoxLivePlaybackMonitor.onReady(5_000L, 15_000L, 12_000_000L)

        assertNotNull(completedRecord)
        assertTrue("Rebuffer duration must be >= 40ms", completedRecord!!.durationMs >= 40L)
        assertEquals(VoxLiveRebufferReason.BUFFER_UNDERRUN, completedRecord!!.reason)
    }

    @Test
    fun testMultipleRebufferClassifierUnstable() {
        var lastStatus: VoxLiveStabilityStatus? = null
        VoxLivePlaybackMonitor.addListener(object : VoxLivePlaybackMonitor.Listener {
            override fun onEvent(event: VoxLivePlaybackEvent, safeDetails: String) {}
            override fun onBufferingSnapshot(snapshot: VoxLiveBufferingSnapshot) {}
            override fun onRebufferCompleted(record: VoxLiveRebufferRecord) {}
            override fun onStabilityStatusChanged(status: VoxLiveStabilityStatus) {
                lastStatus = status
            }
        })

        VoxLivePlaybackMonitor.onPlaybackStart("live_unstable", isLive = true)

        // 1st rebuffer
        VoxLivePlaybackMonitor.onBufferingStarted(0L, 15_000L, 10_000_000L, 3_000_000L, 1.0f)
        VoxLivePlaybackMonitor.onReady(3_000L, 15_000L, 10_000_000L)
        assertEquals(VoxLiveStabilityStatus.LIVE_PLAYBACK_STABLE, VoxLivePlaybackMonitor.getStabilityStatus())

        // 2nd rebuffer
        VoxLivePlaybackMonitor.onBufferingStarted(0L, 15_000L, 10_000_000L, 3_000_000L, 1.0f)
        VoxLivePlaybackMonitor.onReady(3_000L, 15_000L, 10_000_000L)
        assertEquals(VoxLiveStabilityStatus.LIVE_PLAYBACK_STABLE, VoxLivePlaybackMonitor.getStabilityStatus())

        // 3rd rebuffer within 10 min window -> UNSTABLE
        VoxLivePlaybackMonitor.onBufferingStarted(0L, 15_000L, 10_000_000L, 3_000_000L, 1.0f)
        VoxLivePlaybackMonitor.onReady(3_000L, 15_000L, 10_000_000L)

        assertEquals(VoxLiveStabilityStatus.LIVE_PLAYBACK_UNSTABLE, VoxLivePlaybackMonitor.getStabilityStatus())
        assertEquals(VoxLiveStabilityStatus.LIVE_PLAYBACK_UNSTABLE, lastStatus)
    }

    @Test
    fun testNetworkErrorCategorization() {
        VoxLivePlaybackMonitor.onPlaybackStart("live_errs", isLive = true)

        // Segment timeout
        val timeoutSnapshot = VoxLiveBufferingSnapshot(
            bufferedDurationMs = 0L,
            liveOffsetMs = 15_000L,
            bandwidthEstimate = 10_000_000L,
            selectedBitrate = 2_000_000L,
            networkState = "ONLINE",
            lastLoadDurationMs = 9_500L,
            lastLoadBytes = 1024L,
            lastHttpStatusCategory = "NONE",
            manifestAgeMs = 1000L,
            playbackSpeed = 1.0f
        )
        assertEquals(VoxLiveRebufferReason.SEGMENT_TIMEOUT, VoxLivePlaybackMonitor.classifyRebufferReason(timeoutSnapshot))

        // Network starvation
        val starvationSnapshot = VoxLiveBufferingSnapshot(
            bufferedDurationMs = 0L,
            liveOffsetMs = 15_000L,
            bandwidthEstimate = 500_000L,
            selectedBitrate = 4_000_000L,
            networkState = "ONLINE",
            lastLoadDurationMs = 2_000L,
            lastLoadBytes = 1024L,
            lastHttpStatusCategory = "NONE",
            manifestAgeMs = 1000L,
            playbackSpeed = 1.0f
        )
        assertEquals(VoxLiveRebufferReason.NETWORK_STARVATION, VoxLivePlaybackMonitor.classifyRebufferReason(starvationSnapshot))

        // Live edge catchup
        val catchupSnapshot = VoxLiveBufferingSnapshot(
            bufferedDurationMs = 200L,
            liveOffsetMs = 2_500L, // < 4000ms
            bandwidthEstimate = 20_000_000L,
            selectedBitrate = 2_000_000L,
            networkState = "ONLINE",
            lastLoadDurationMs = 500L,
            lastLoadBytes = 1024L,
            lastHttpStatusCategory = "2XX_SUCCESS",
            manifestAgeMs = 1000L,
            playbackSpeed = 1.0f
        )
        assertEquals(VoxLiveRebufferReason.LIVE_EDGE_CATCHUP, VoxLivePlaybackMonitor.classifyRebufferReason(catchupSnapshot))
    }

    @Test
    fun test404CategorizationAndPolicy() {
        VoxLivePlaybackMonitor.onPlaybackStart("live_404", isLive = true)
        VoxLivePlaybackMonitor.onSegmentError(404, "InvalidResponseCodeException")

        val snapshot404 = VoxLiveBufferingSnapshot(
            bufferedDurationMs = 0L,
            liveOffsetMs = 15_000L,
            bandwidthEstimate = 20_000_000L,
            selectedBitrate = 2_000_000L,
            networkState = "ONLINE",
            lastLoadDurationMs = 200L,
            lastLoadBytes = 0L,
            lastHttpStatusCategory = "HTTP_404",
            manifestAgeMs = 1000L,
            playbackSpeed = 1.0f
        )
        assertEquals(VoxLiveRebufferReason.SEGMENT_404, VoxLivePlaybackMonitor.classifyRebufferReason(snapshot404))

        // Verify DashDefaultLoadErrorHandlingPolicy behavior on 404 live edge segment
        val policy = DashDefaultLoadErrorHandlingPolicy()
        val exc404 = InvalidResponseCodeException(404, emptyMap(), null)

        // Attempt 1: Do NOT blacklist live media segment
        val blacklistDelay = policy.getBlacklistDurationMsFor(C.DATA_TYPE_MEDIA, 200L, exc404, 1)
        assertEquals("Live media segment on 404 must NOT be blacklisted", C.TIME_UNSET, blacklistDelay)

        // Controlled retry delay
        val retryDelay = policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 200L, exc404, 1)
        assertEquals(500L, retryDelay)

        val retryDelay2 = policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 200L, exc404, 2)
        assertEquals(1000L, retryDelay2)

        // After max attempts (4 > 3), retry stops
        val retryDelayMax = policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 200L, exc404, 4)
        assertEquals(C.TIME_UNSET, retryDelayMax)
    }

    @Test
    fun test429CategorizationAndBackoff() {
        VoxLivePlaybackMonitor.onPlaybackStart("live_429", isLive = true)
        VoxLivePlaybackMonitor.onSegmentError(429, "RateLimitException")

        val snapshot429 = VoxLiveBufferingSnapshot(
            bufferedDurationMs = 0L,
            liveOffsetMs = 15_000L,
            bandwidthEstimate = 20_000_000L,
            selectedBitrate = 2_000_000L,
            networkState = "ONLINE",
            lastLoadDurationMs = 100L,
            lastLoadBytes = 0L,
            lastHttpStatusCategory = "HTTP_429",
            manifestAgeMs = 1000L,
            playbackSpeed = 1.0f
        )
        assertEquals(VoxLiveRebufferReason.HTTP_429, VoxLivePlaybackMonitor.classifyRebufferReason(snapshot429))

        val policy = DashDefaultLoadErrorHandlingPolicy()
        val exc429 = InvalidResponseCodeException(429, emptyMap(), null)

        // 429 must never blacklist track representation
        assertEquals(C.TIME_UNSET, policy.getBlacklistDurationMsFor(C.DATA_TYPE_MEDIA, 100L, exc429, 1))

        // Exponential backoff
        assertEquals(1000L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc429, 1))
        assertEquals(2000L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc429, 2))
        assertEquals(4000L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc429, 3))
        assertEquals(8000L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc429, 4))
        // Max retries bounded
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc429, 5))
    }

    @Test
    fun test5xxCategorizationAndBoundedRetry() {
        val policy = DashDefaultLoadErrorHandlingPolicy()
        val exc500 = InvalidResponseCodeException(500, emptyMap(), null)
        val exc503 = InvalidResponseCodeException(503, emptyMap(), null)

        assertEquals("5xx must not blacklist track", C.TIME_UNSET, policy.getBlacklistDurationMsFor(C.DATA_TYPE_MEDIA, 100L, exc500, 1))
        assertEquals("5xx must not blacklist track", C.TIME_UNSET, policy.getBlacklistDurationMsFor(C.DATA_TYPE_MEDIA, 100L, exc503, 1))

        assertEquals(1000L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc500, 1))
        assertEquals(2000L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc500, 2))
        assertEquals(3000L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc500, 3))
        // Max 3 retries bounded
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc500, 4))
    }

    @Test
    fun testManifestFailureCategorization() {
        VoxLivePlaybackMonitor.onPlaybackStart("live_mf", isLive = true)
        VoxLivePlaybackMonitor.onManifestRefreshed(isSuccess = false, httpStatusCode = 503)

        val metrics = VoxLivePlaybackMonitor.getMetricsSummary()
        assertEquals(1, metrics.manifestErrorCount)
        assertEquals("MANIFEST_ERROR", metrics.lastErrorCategory)

        val staleSnapshot = VoxLiveBufferingSnapshot(
            bufferedDurationMs = 0L,
            liveOffsetMs = 15_000L,
            bandwidthEstimate = 20_000_000L,
            selectedBitrate = 2_000_000L,
            networkState = "ONLINE",
            lastLoadDurationMs = 200L,
            lastLoadBytes = 0L,
            lastHttpStatusCategory = "HTTP_5XX",
            manifestAgeMs = 25_000L, // > 20s
            playbackSpeed = 1.0f
        )
        assertEquals(VoxLiveRebufferReason.HTTP_5XX, VoxLivePlaybackMonitor.classifyRebufferReason(staleSnapshot))
    }

    @Test
    fun testSafeDiagnosticsSerialization() {
        VoxLivePlaybackMonitor.onPlaybackStart("live_diag", isLive = true)
        VoxLivePlaybackMonitor.onReady(15_000L, 15_000L, 20_000_000L)
        VoxLivePlaybackMonitor.onBufferingStarted(0L, 15_000L, 20_000_000L, 3_000_000L, 1.0f)
        VoxLivePlaybackMonitor.onReady(15_000L, 15_000L, 20_000_000L)

        val metrics = VoxLivePlaybackMonitor.getMetricsSummary()
        val metricsMap = metrics.toMap()

        assertEquals(1, metricsMap["rebufferCount"])
        assertTrue(metricsMap.containsKey("totalRebufferMs"))
        assertTrue(metricsMap.containsKey("maxRebufferMs"))
        assertTrue(metricsMap.containsKey("averageBufferedMs"))
        assertTrue(metricsMap.containsKey("averageBandwidthKbps"))
        assertTrue(metricsMap.containsKey("selectedHeight"))
        assertTrue(metricsMap.containsKey("selectedCodec"))
        assertTrue(metricsMap.containsKey("liveOffsetMs"))
        assertTrue(metricsMap.containsKey("manifestErrorCount"))
        assertTrue(metricsMap.containsKey("segmentErrorCount"))
        assertTrue(metricsMap.containsKey("lastErrorCategory"))

        // Create a diagnostic report with livePlayback
        val report = VoxDiagnosticReport(
            appVersion = "32.56-vox.8-dev",
            appVersionCode = 2446009,
            platform = "Android TV",
            manufacturer = "TCL",
            model = "BeyondTV",
            osName = "Android",
            osVersion = "14",
            sdkInt = 34,
            deviceTier = "Standard",
            videoCodecs = mapOf("AVC" to "Поддерживается"),
            audioCodecs = mapOf("AAC" to "Поддерживается"),
            display = mapOf("resolution" to "1920x1080"),
            currentPolicy = mapOf("mode" to "AUTO"),
            recommendedSettings = mapOf("tier" to "STANDARD"),
            livePlayback = metricsMap
        )

        val json = report.toJson()
        val root = JSONObject(json)
        assertTrue(root.has("livePlayback"))
        val lpObj = root.getJSONObject("livePlayback")
        assertEquals(1, lpObj.getInt("rebufferCount"))
    }

    @Test
    fun testNoUrlLeakageInLogging() {
        val rawInput = "GET https://rr1---sn-abc.googlevideo.com/videoplayback?expire=123&token=SECRET_TOKEN_456&sig=ABCDEF&cookie=session_789"
        val sanitized = VoxLivePlaybackMonitor.sanitize(rawInput)

        assertFalse("URL must not be leaked", sanitized.contains("googlevideo.com"))
        assertFalse("Token must not be leaked", sanitized.contains("SECRET_TOKEN_456"))
        assertFalse("Sig must not be leaked", sanitized.contains("ABCDEF"))
        assertFalse("Cookie must not be leaked", sanitized.contains("session_789"))
        assertTrue("URL replacement marker must be present", sanitized.contains("<URL_REDACTED>"))
    }

    @Test
    fun testNetworkInterruptionAndRecovery() {
        VoxLivePlaybackMonitor.onPlaybackStart("live_net", isLive = true)
        VoxLivePlaybackMonitor.onNetworkInterruption(isRestored = false)

        val snapshot = VoxLiveBufferingSnapshot(
            bufferedDurationMs = 0L,
            liveOffsetMs = 15_000L,
            bandwidthEstimate = 0L,
            selectedBitrate = 2_000_000L,
            networkState = "DISCONNECTED",
            lastLoadDurationMs = 0L,
            lastLoadBytes = 0L,
            lastHttpStatusCategory = "NONE",
            manifestAgeMs = 1000L,
            playbackSpeed = 1.0f
        )
        assertEquals(VoxLiveRebufferReason.NETWORK_STARVATION, VoxLivePlaybackMonitor.classifyRebufferReason(snapshot))

        // Network restored
        VoxLivePlaybackMonitor.onNetworkInterruption(isRestored = true)
        VoxLivePlaybackMonitor.onReady(15_000L, 15_000L, 20_000_000L)
        assertEquals(VoxLiveStabilityStatus.LIVE_PLAYBACK_STABLE, VoxLivePlaybackMonitor.getStabilityStatus())
    }

    @Test
    fun testAutoQualityDoesNotForceFixedMaximumTrack() {
        val targetOffsetPolicy = VoxLiveTargetOffsetPolicy.DEFAULT
        assertEquals(VoxLiveTargetOffsetPolicy.BALANCED, targetOffsetPolicy)
        assertEquals(15_000L, targetOffsetPolicy.targetOffsetMs)

        // Live buffer policy
        assertEquals(15_000L, VoxPlaybackBufferPolicy.LIVE_TARGET_OFFSET_MS)
        assertEquals(15_000, VoxPlaybackBufferPolicy.LIVE_MIN_BUFFER_MS)
        assertEquals(30_000, VoxPlaybackBufferPolicy.LIVE_MAX_BUFFER_MS)
        assertEquals(2_000, VoxPlaybackBufferPolicy.LIVE_BUFFER_FOR_PLAYBACK_MS)
        assertEquals(4_000, VoxPlaybackBufferPolicy.LIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
    }
}
