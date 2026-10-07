package com.liskovsoft.smartyoutubetv2.common.vox.capability

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VoxAutoSetup2Test {

    private lateinit var tracker: VoxPlaybackHealthTracker

    private fun createDuneHdProfile(): VoxDeviceProfile {
        return VoxDeviceProfile(
            platform = VoxPlatform.ANDROID_TV,
            manufacturer = "DuneHD",
            model = "Pro Vision 4K",
            osName = "Android",
            osVersion = "11",
            videoCodecs = mapOf(
                "vp9" to VideoCodecCapability(
                    codec = "vp9",
                    capability = TriStateCapability.SUPPORTED,
                    hardwareAccelerated = true,
                    maxWidth = 3840,
                    maxHeight = 2160,
                    maxFps = 60
                ),
                "av1" to VideoCodecCapability(
                    codec = "av1",
                    capability = TriStateCapability.SUPPORTED,
                    hardwareAccelerated = true,
                    maxWidth = 3840,
                    maxHeight = 2160,
                    maxFps = 60
                ),
                "avc" to VideoCodecCapability(
                    codec = "avc",
                    capability = TriStateCapability.SUPPORTED,
                    hardwareAccelerated = true,
                    maxWidth = 3840,
                    maxHeight = 2160,
                    maxFps = 60
                )
            ),
            audioCodecs = mapOf(
                "ac3" to AudioCodecCapability("ac3", TriStateCapability.SUPPORTED, TriStateCapability.SUPPORTED)
            ),
            display = DisplayCapability(
                maxWidth = 1920,
                maxHeight = 1080,
                maxFps = 60
            ),
            audioOutput = AudioOutputCapability(TriStateCapability.SUPPORTED)
        )
    }

    private fun create4kSmartTvProfile(): VoxDeviceProfile {
        return VoxDeviceProfile(
            platform = VoxPlatform.ANDROID_TV,
            manufacturer = "Sony",
            model = "Bravia 4K",
            osName = "Android",
            osVersion = "12",
            videoCodecs = mapOf(
                "vp9" to VideoCodecCapability("vp9", TriStateCapability.SUPPORTED, true, 3840, 2160, 60),
                "av1" to VideoCodecCapability("av1", TriStateCapability.SUPPORTED, true, 3840, 2160, 60)
            ),
            display = DisplayCapability(maxWidth = 3840, maxHeight = 2160, maxFps = 60)
        )
    }

    @Before
    fun setUp() {
        tracker = VoxPlaybackHealthTracker(null)
    }

    @Test
    fun testInsufficientSamples_ReturnsUnknownAndLowConfidence() {
        // Only 1 short session (60 seconds)
        tracker.recordSession(
            VoxPlaybackSessionObservation(
                sessionId = "s1",
                selectedHeight = 1080,
                selectedCodec = "vp9",
                playbackDurationMs = 60_000L
            )
        )

        val profile = createDuneHdProfile()
        val policy = VoxCodecPolicy(mode = VoxCodecPolicyMode.AUTO, maxQualityHeight = 1080)
        val rec = tracker.getRecommendation(profile, policy, isManualOverride = false)

        assertEquals("Health should be UNKNOWN when samples < 5 min", VoxPlaybackHealth.UNKNOWN, rec.health)
        assertEquals("Confidence should be LOW", VoxPlaybackHealthConfidence.LOW, rec.confidence)
        assertTrue("Reason codes must include INSUFFICIENT_SAMPLES", rec.reasonCodes.contains(VoxAutoSetupReasonCode.INSUFFICIENT_SAMPLES))
    }

    @Test
    fun testStable1080p_ReturnsExcellentAndHighConfidence() {
        // 4 sessions of 3 minutes each (12 minutes total > 5 min, count > 3)
        for (i in 1..4) {
            tracker.recordSession(
                VoxPlaybackSessionObservation(
                    sessionId = "s$i",
                    selectedHeight = 1080,
                    selectedCodec = "vp9",
                    playbackDurationMs = 180_000L,
                    rebufferCount = 0,
                    droppedFrames = 5,
                    decoderInitFailures = 0
                )
            )
        }

        val profile = createDuneHdProfile()
        val policy = VoxCodecPolicy(mode = VoxCodecPolicyMode.AUTO, maxQualityHeight = 1080)
        val rec = tracker.getRecommendation(profile, policy, isManualOverride = false)

        assertEquals(VoxPlaybackHealth.EXCELLENT, rec.health)
        assertEquals(VoxPlaybackIssueType.NONE, rec.issueType)
        assertEquals(1080, rec.recommendedResolution)
    }

    @Test
    fun testStable4k_On4kDisplay_Returns4kStable() {
        for (i in 1..5) {
            tracker.recordSession(
                VoxPlaybackSessionObservation(
                    sessionId = "s$i",
                    selectedHeight = 2160,
                    selectedCodec = "vp9",
                    playbackDurationMs = 200_000L,
                    rebufferCount = 0,
                    droppedFrames = 10
                )
            )
        }

        val profile = create4kSmartTvProfile()
        val policy = VoxCodecPolicy(mode = VoxCodecPolicyMode.AUTO, maxQualityHeight = 2160)
        val rec = tracker.getRecommendation(profile, policy, isManualOverride = false)

        assertEquals(VoxPlaybackHealth.EXCELLENT, rec.health)
        assertEquals(2160, rec.recommendedResolution)
        assertTrue(rec.reasonCodes.contains(VoxAutoSetupReasonCode.RUNTIME_4K_STABLE))
    }

    @Test
    fun testDuneHdCase_Manual4k_Stable_PreservesUserManualChoice() {
        val profile = createDuneHdProfile() // 1080p screen, 4K decoder
        val manual4kPolicy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.MAX_QUALITY,
            maxQualityHeight = 2160
        )

        // 4 sessions of 4K with zero rebuffers
        for (i in 1..4) {
            tracker.recordSession(
                VoxPlaybackSessionObservation(
                    sessionId = "dune_$i",
                    selectedHeight = 2160,
                    selectedCodec = "vp9",
                    playbackDurationMs = 150_000L,
                    rebufferCount = 0,
                    droppedFrames = 2
                )
            )
        }

        val rec = tracker.getRecommendation(profile, manual4kPolicy, isManualOverride = true)

        assertEquals("Manual 4K choice must be preserved when playback is stable", 2160, rec.recommendedResolution)
        assertEquals(VoxPlaybackHealth.EXCELLENT, rec.health)
        assertTrue(rec.isManualOverrideActive)
        assertTrue(rec.reasonCodes.contains(VoxAutoSetupReasonCode.RUNTIME_4K_STABLE))
        assertTrue(rec.reasonCodes.contains(VoxAutoSetupReasonCode.MANUAL_OVERRIDE))
    }

    @Test
    fun testDuneHdCase_Manual4k_RepeatedRebuffers_Recommends1080pSoftly() {
        val profile = createDuneHdProfile()
        val manual4kPolicy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.MAX_QUALITY,
            maxQualityHeight = 2160
        )

        // Sessions with repeated rebuffers at 4K
        for (i in 1..4) {
            tracker.recordSession(
                VoxPlaybackSessionObservation(
                    sessionId = "dune_rebuf_$i",
                    selectedHeight = 2160,
                    selectedCodec = "vp9",
                    playbackDurationMs = 100_000L,
                    rebufferCount = 3,
                    totalRebufferMs = 8_000L,
                    droppedFrames = 4
                )
            )
        }

        val rec = tracker.getRecommendation(profile, manual4kPolicy, isManualOverride = true)

        assertEquals("Recommendation should suggest 1080p due to repeated rebuffers", 1080, rec.recommendedResolution)
        assertTrue(rec.reasonCodes.contains(VoxAutoSetupReasonCode.RUNTIME_4K_REBUFFER))
        assertTrue(rec.reasonCodes.contains(VoxAutoSetupReasonCode.MANUAL_OVERRIDE))
        assertTrue("User explanation should explain 4K rebuffering", rec.userExplanationRu.contains("2160p были обнаружены повторные буферизации"))
    }

    @Test
    fun testDecoderFailure_MarksDecoderLimitedAndRecommendsAlternativeCodec() {
        val profile = createDuneHdProfile()
        val policy = VoxCodecPolicy(mode = VoxCodecPolicyMode.AUTO, maxQualityHeight = 1080, preferredVideoCodec = VoxVideoCodecPreference.AV1)

        // AV1 decoder fails to initialize
        for (i in 1..4) {
            tracker.recordSession(
                VoxPlaybackSessionObservation(
                    sessionId = "dec_fail_$i",
                    selectedHeight = 1080,
                    selectedCodec = "av1",
                    playbackDurationMs = 120_000L,
                    rebufferCount = 0,
                    decoderInitFailures = 1
                )
            )
        }

        val rec = tracker.getRecommendation(profile, policy, isManualOverride = false)

        assertEquals(VoxPlaybackIssueType.DECODER_LIMITED, rec.issueType)
        assertTrue(rec.reasonCodes.contains(VoxAutoSetupReasonCode.AV1_DECODER_ERROR))
        assertEquals("Should recommend fallback to VP9 when AV1 decoder fails", VoxVideoCodecPreference.VP9, rec.recommendedCodec)
    }

    @Test
    fun testNetworkLimited_DoesNotDisqualifyCodec() {
        val profile = createDuneHdProfile()
        val policy = VoxCodecPolicy(mode = VoxCodecPolicyMode.AUTO, maxQualityHeight = 1080, preferredVideoCodec = VoxVideoCodecPreference.VP9)

        // Multiple rebuffers, BUT 0 decoder failures and very low dropped frames
        for (i in 1..4) {
            tracker.recordSession(
                VoxPlaybackSessionObservation(
                    sessionId = "net_slow_$i",
                    selectedHeight = 1080,
                    selectedCodec = "vp9",
                    playbackDurationMs = 120_000L,
                    rebufferCount = 2,
                    totalRebufferMs = 4_000L,
                    droppedFrames = 1,
                    decoderInitFailures = 0
                )
            )
        }

        val rec = tracker.getRecommendation(profile, policy, isManualOverride = false)

        assertEquals("Issue type must be NETWORK_LIMITED", VoxPlaybackIssueType.NETWORK_LIMITED, rec.issueType)
        assertTrue(rec.reasonCodes.contains(VoxAutoSetupReasonCode.NETWORK_LIMITED))
        assertFalse("Must NOT mark as DECODER_LIMITED", rec.reasonCodes.contains(VoxAutoSetupReasonCode.DECODER_LIMITED))
        assertEquals("Codec should remain VP9, not blamed for network stalls", VoxVideoCodecPreference.VP9, rec.recommendedCodec)
    }

    @Test
    fun testLiveVsVodSeparation_LiveInstabilityDoesNotPenalizeVod() {
        val profile = createDuneHdProfile()
        val policy = VoxCodecPolicy(mode = VoxCodecPolicyMode.AUTO, maxQualityHeight = 1080, preferredVideoCodec = VoxVideoCodecPreference.VP9)

        // 3 stable VOD sessions
        for (i in 1..3) {
            tracker.recordSession(
                VoxPlaybackSessionObservation(
                    sessionId = "vod_$i",
                    selectedHeight = 1080,
                    selectedCodec = "vp9",
                    isLive = false,
                    playbackDurationMs = 120_000L,
                    rebufferCount = 0,
                    droppedFrames = 2
                )
            )
        }

        // 2 unstable LIVE sessions
        for (i in 1..2) {
            tracker.recordSession(
                VoxPlaybackSessionObservation(
                    sessionId = "live_$i",
                    selectedHeight = 1080,
                    selectedCodec = "vp9",
                    isLive = true,
                    playbackDurationMs = 60_000L,
                    rebufferCount = 4,
                    totalRebufferMs = 10_000L
                )
            )
        }

        val summary = tracker.getHealthSummary()
        assertEquals(5, summary.sampleCount)
        assertEquals(3, summary.vodSampleCount)
        assertEquals(2, summary.liveSampleCount)

        val rec = tracker.getRecommendation(profile, policy, isManualOverride = false)
        assertEquals("VOD health should remain EXCELLENT despite live stalls", VoxPlaybackHealth.EXCELLENT, rec.health)
    }

    @Test
    fun testRollingWindow_RetainsMax20Sessions() {
        for (i in 1..30) {
            tracker.recordSession(
                VoxPlaybackSessionObservation(
                    sessionId = "session_$i",
                    selectedHeight = 1080,
                    playbackDurationMs = 20_000L
                )
            )
        }

        assertEquals("Tracker should strictly bound to 20 observations", 20, tracker.getObservationCount())
    }

    @Test
    fun testReset_ClearsAllData() {
        for (i in 1..5) {
            tracker.recordSession(
                VoxPlaybackSessionObservation(
                    sessionId = "s$i",
                    selectedHeight = 1080,
                    playbackDurationMs = 100_000L
                )
            )
        }
        assertEquals(5, tracker.getObservationCount())

        tracker.reset()
        assertEquals(0, tracker.getObservationCount())
        assertEquals(0, tracker.getHealthSummary().sampleCount)
    }
}
