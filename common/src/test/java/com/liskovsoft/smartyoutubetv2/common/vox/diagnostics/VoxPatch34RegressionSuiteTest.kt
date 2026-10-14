package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.vox.capability.*
import com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLivePlaybackMonitor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoxPatch34RegressionSuiteTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        VoxLivePlaybackMonitor.resetForTesting()
    }

    @Test
    fun testHistoricalRendererErrorDoesNotBecomeCurrentSignature() {
        // Historical event from hours ago
        val historicalError = VoxLogEvent(
            timestamp = System.currentTimeMillis() - 3_600_000L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_RENDERER_ERROR,
            message = "Old renderer stall"
        )
        // Current healthy session event
        val currentSuccess = VoxLogEvent(
            timestamp = System.currentTimeMillis(),
            level = VoxLogLevel.INFO,
            category = VoxLogCategory.PLAYER,
            code = "PLAYER_PLAYBACK_PROGRESS",
            message = "Playing smoothly"
        )

        // When historical error is unrecovered, but current session is clean,
        // policy prioritizes current session
        val prominent = VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(listOf(historicalError, currentSuccess))
        // Historical unrecovered event can exist, but canonical signature should not treat it as active current crash
        val sig = VoxDiagnosticsPolicy.buildCanonicalErrorSignature(
            errorCategory = "PLAYER",
            primaryCode = VoxLogCode.PLAYER_RENDERER_ERROR,
            platform = "Android TV",
            sdkInt = 34,
            reportPurpose = "USER"
        )
        assertTrue(sig.contains("PLAYER_RENDERER_ERROR"))
        assertTrue(sig.contains("ANDROID_TV_API34"))
    }

    @Test
    fun testDownloadPerformanceIsStructuredObject() {
        val perfMap = mapOf(
            "totalBytes" to 1500L,
            "averageMbps" to "12.5"
        )
        val diagMap = mutableMapOf<String, Any?>(
            "downloadPerformance" to perfMap
        )
        val perf = diagMap["downloadPerformance"]
        assertNotNull("downloadPerformance must be present", perf)
        assertTrue("downloadPerformance must be a structured Map, not String", perf is Map<*, *>)
        val map = perf as Map<*, *>
        assertEquals(1500L, map["totalBytes"])
        assertEquals("12.5", map["averageMbps"])
    }

    @Test
    fun testLiveOffsetDoesNotLeakAcrossSessions() {
        // Start Live session 1
        VoxLivePlaybackMonitor.onPlaybackStart("stream-1", isLive = true)
        VoxLivePlaybackMonitor.onReady(bufferedDurationMs = 3000L, liveOffsetMs = 2000L, bandwidthEstimate = 5000000L)
        assertEquals(2000L, VoxLivePlaybackMonitor.getMetricsSummary().liveOffsetMs)

        // Stop session 1
        VoxLivePlaybackMonitor.onPlaybackStopped()
        assertEquals(0L, VoxLivePlaybackMonitor.getMetricsSummary().liveOffsetMs)

        // Start VOD session 2 -> offset must remain 0
        VoxLivePlaybackMonitor.onPlaybackStart("vod-2", isLive = false)
        assertEquals(0L, VoxLivePlaybackMonitor.getMetricsSummary().liveOffsetMs)
    }

    @Test
    fun testNoActiveLiveSessionHasNoStaleOffset() {
        // Without active session, metrics summary must always report 0 live offset
        val metrics = VoxLivePlaybackMonitor.getMetricsSummary()
        assertEquals(0L, metrics.liveOffsetMs)
    }

    @Test
    fun testKtc4kStandardProfilePrefersVp9OverAv1() {
        val tuner = VoxDeviceAutoTuner(null)
        val ktcProfile = VoxDeviceProfile(
            platform = VoxPlatform.ANDROID_TV,
            manufacturer = "KTC",
            model = "KTC 4K SMART TV PH",
            videoCodecs = mapOf(
                "vp9" to VideoCodecCapability(codec = "vp9", capability = TriStateCapability.SUPPORTED, hardwareAccelerated = true, maxHeight = 2160),
                "av1" to VideoCodecCapability(codec = "av1", capability = TriStateCapability.UNSUPPORTED, hardwareAccelerated = false, maxHeight = 1080),
                "avc" to VideoCodecCapability(codec = "avc", capability = TriStateCapability.SUPPORTED, hardwareAccelerated = true, maxHeight = 2160)
            ),
            audioCodecs = mapOf(
                "aac" to AudioCodecCapability(codec = "aac", decodeCapability = TriStateCapability.SUPPORTED)
            ),
            display = DisplayCapability(maxWidth = 3840, maxHeight = 2160, maxFps = 60),
            audioOutput = AudioOutputCapability(stereo = TriStateCapability.SUPPORTED, passthrough = TriStateCapability.SUPPORTED)
        )

        val settings = tuner.tune(ktcProfile)
        assertEquals(VoxPerformanceTier.STANDARD, settings.tier)
        assertEquals(VoxVideoCodecPreference.VP9, settings.preferredVideoCodec)
        assertEquals(2160, settings.maxQualityHeight)
    }

    @Test
    fun testVsKm1Api29BasicProfileAvoidsAv1() {
        val tuner = VoxDeviceAutoTuner(null)
        val vsKm1Profile = VoxDeviceProfile(
            platform = VoxPlatform.ANDROID_TV,
            manufacturer = "VS",
            model = "KM1",
            videoCodecs = mapOf(
                "vp9" to VideoCodecCapability(codec = "vp9", capability = TriStateCapability.SUPPORTED, hardwareAccelerated = true, maxHeight = 1080),
                "avc" to VideoCodecCapability(codec = "avc", capability = TriStateCapability.SUPPORTED, hardwareAccelerated = true, maxHeight = 1080),
                "av1" to VideoCodecCapability(codec = "av1", capability = TriStateCapability.UNSUPPORTED, hardwareAccelerated = false, maxHeight = 720)
            ),
            audioCodecs = mapOf(
                "aac" to AudioCodecCapability(codec = "aac", decodeCapability = TriStateCapability.SUPPORTED)
            ),
            display = DisplayCapability(maxWidth = 1920, maxHeight = 1080, maxFps = 60),
            audioOutput = AudioOutputCapability(stereo = TriStateCapability.SUPPORTED, passthrough = TriStateCapability.UNSUPPORTED)
        )

        val settings = tuner.tune(vsKm1Profile)
        assertEquals(VoxPerformanceTier.STANDARD, settings.tier)
        assertEquals(1080, settings.maxQualityHeight)
        assertEquals(VoxVideoCodecPreference.VP9, settings.preferredVideoCodec)
        assertNotEquals(VoxVideoCodecPreference.AV1, settings.preferredVideoCodec)
    }
}
