package com.liskovsoft.smartyoutubetv2.common.vox.capability

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxCompatibilityRiskEvaluatorTest {

    private val profile = VoxDeviceProfile(
        platform = VoxPlatform.ANDROID_TV,
        videoCodecs = mapOf(
            "avc" to VideoCodecCapability(codec = "avc", capability = TriStateCapability.SUPPORTED, hardwareAccelerated = true, maxHeight = 1080),
            "vp9" to VideoCodecCapability(codec = "vp9", capability = TriStateCapability.SUPPORTED, hardwareAccelerated = true, maxHeight = 1080),
            "av1" to VideoCodecCapability(codec = "av1", capability = TriStateCapability.UNSUPPORTED, hardwareAccelerated = false)
        ),
        audioCodecs = mapOf(
            "aac" to AudioCodecCapability(codec = "aac", decodeCapability = TriStateCapability.SUPPORTED),
            "ac3" to AudioCodecCapability(codec = "ac3", decodeCapability = TriStateCapability.SUPPORTED, passthroughCapability = TriStateCapability.SUPPORTED),
            "eac3" to AudioCodecCapability(codec = "eac3", decodeCapability = TriStateCapability.UNSUPPORTED, passthroughCapability = TriStateCapability.UNSUPPORTED)
        ),
        display = DisplayCapability(maxWidth = 1920, maxHeight = 1080),
        audioOutput = AudioOutputCapability(stereo = TriStateCapability.SUPPORTED, passthrough = TriStateCapability.SUPPORTED)
    )

    private val recommended = VoxRecommendedSettings(
        tier = VoxPerformanceTier.STANDARD,
        policyMode = VoxCodecPolicyMode.AUTO,
        maxQualityHeight = 1080,
        preferredVideoCodec = VoxVideoCodecPreference.VP9,
        preferredAudioCodec = VoxAudioCodecPreference.AC3,
        passthroughEnabled = true
    )

    @Test
    fun testNoRiskWhenUsingRecommended() {
        val policy = recommended.toCodecPolicy()
        val risk = VoxCompatibilityRiskEvaluator.evaluate(policy, recommended, profile)
        assertNull(risk)
    }

    @Test
    fun testRiskWhenResolutionAboveRecommended() {
        val policy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.CUSTOM,
            maxQualityHeight = 2160,
            preferredVideoCodec = VoxVideoCodecPreference.VP9,
            preferredAudioCodec = VoxAudioCodecPreference.AC3,
            passthroughEnabled = true
        )
        val risk = VoxCompatibilityRiskEvaluator.evaluate(policy, recommended, profile)
        assertNotNull(risk)
        assertTrue(risk!!.messageRu.contains("2160p"))
    }

    @Test
    fun testRiskWhenUnsupportedVideoCodecSelected() {
        val policy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.CUSTOM,
            maxQualityHeight = 1080,
            preferredVideoCodec = VoxVideoCodecPreference.AV1,
            preferredAudioCodec = VoxAudioCodecPreference.AC3,
            passthroughEnabled = true
        )
        val risk = VoxCompatibilityRiskEvaluator.evaluate(policy, recommended, profile)
        assertNotNull(risk)
        assertTrue(risk!!.messageRu.contains("AV1"))
    }

    @Test
    fun testRiskWhenUnsupportedAudioCodecSelected() {
        val policy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.CUSTOM,
            maxQualityHeight = 1080,
            preferredVideoCodec = VoxVideoCodecPreference.VP9,
            preferredAudioCodec = VoxAudioCodecPreference.EAC3,
            passthroughEnabled = true
        )
        val risk = VoxCompatibilityRiskEvaluator.evaluate(policy, recommended, profile)
        assertNotNull(risk)
        assertTrue(risk!!.messageRu.contains("EAC3") || risk.messageRu.contains("E-AC-3"))
    }
}
