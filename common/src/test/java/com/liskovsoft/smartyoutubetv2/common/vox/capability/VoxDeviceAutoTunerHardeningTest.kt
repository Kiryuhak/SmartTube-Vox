package com.liskovsoft.smartyoutubetv2.common.vox.capability

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxDeviceAutoTunerHardeningTest {

    @Test
    fun testRiskWarningOnlyForHeavierSettings() {
        val profile = createSampleProfile(
            displayHeight = 1080,
            hasAv1 = false,
            hasVp9 = true
        )
        val tuner = VoxDeviceAutoTuner(null)
        val recommended = tuner.tune(profile) // 1080p recommendation

        // Heavier setting: 4K (2160p) on 1080p device -> MUST SHOW RISK
        val riskyPolicy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.CUSTOM,
            maxQualityHeight = 2160,
            preferredVideoCodec = VoxVideoCodecPreference.AUTO,
            preferredAudioCodec = VoxAudioCodecPreference.AUTO,
            passthroughEnabled = false
        )
        val risk1 = VoxCompatibilityRiskEvaluator.evaluate(riskyPolicy, recommended, profile)
        assertNotNull(risk1)
        assertTrue(risk1!!.affectedSetting == "maxQuality")

        // Lower / Safer setting: 720p on 1080p device -> MUST NOT SHOW RISK
        val saferPolicy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.CUSTOM,
            maxQualityHeight = 720,
            preferredVideoCodec = VoxVideoCodecPreference.AUTO,
            preferredAudioCodec = VoxAudioCodecPreference.AUTO,
            passthroughEnabled = false
        )
        val risk2 = VoxCompatibilityRiskEvaluator.evaluate(saferPolicy, recommended, profile)
        assertNull(risk2)

        // Matching setting: 1080p -> MUST NOT SHOW RISK
        val matchingPolicy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.CUSTOM,
            maxQualityHeight = 1080,
            preferredVideoCodec = VoxVideoCodecPreference.AUTO,
            preferredAudioCodec = VoxAudioCodecPreference.AUTO,
            passthroughEnabled = false
        )
        val risk3 = VoxCompatibilityRiskEvaluator.evaluate(matchingPolicy, recommended, profile)
        assertNull(risk3)
    }

    @Test
    fun testRiskWarningForUnsupportedCodec() {
        val profile = createSampleProfile(
            displayHeight = 1080,
            hasAv1 = false,
            hasVp9 = true
        )
        val tuner = VoxDeviceAutoTuner(null)
        val recommended = tuner.tune(profile)

        // Choosing AV1 when AV1 is unsupported -> MUST SHOW RISK
        val av1Policy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.CUSTOM,
            maxQualityHeight = 1080,
            preferredVideoCodec = VoxVideoCodecPreference.AV1,
            preferredAudioCodec = VoxAudioCodecPreference.AUTO,
            passthroughEnabled = false
        )
        val riskAv1 = VoxCompatibilityRiskEvaluator.evaluate(av1Policy, recommended, profile)
        assertNotNull(riskAv1)
        assertTrue(riskAv1!!.affectedSetting == "videoCodec")

        // Choosing VP9 when VP9 is supported -> MUST NOT SHOW RISK
        val vp9Policy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.CUSTOM,
            maxQualityHeight = 1080,
            preferredVideoCodec = VoxVideoCodecPreference.VP9,
            preferredAudioCodec = VoxAudioCodecPreference.AUTO,
            passthroughEnabled = false
        )
        val riskVp9 = VoxCompatibilityRiskEvaluator.evaluate(vp9Policy, recommended, profile)
        assertNull(riskVp9)
    }

    private fun createSampleProfile(displayHeight: Int, hasAv1: Boolean, hasVp9: Boolean): VoxDeviceProfile {
        val videoCodecs = mutableMapOf<String, VideoCodecCapability>()
        videoCodecs["avc"] = VideoCodecCapability(
            codec = "avc",
            capability = TriStateCapability.SUPPORTED,
            hardwareAccelerated = true,
            maxWidth = 1920,
            maxHeight = 1080,
            maxFps = 60
        )
        videoCodecs["vp9"] = VideoCodecCapability(
            codec = "vp9",
            capability = if (hasVp9) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED,
            hardwareAccelerated = hasVp9,
            maxWidth = if (hasVp9) 1920 else 0,
            maxHeight = if (hasVp9) 1080 else 0,
            maxFps = if (hasVp9) 60 else 0
        )
        videoCodecs["av1"] = VideoCodecCapability(
            codec = "av1",
            capability = if (hasAv1) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED,
            hardwareAccelerated = hasAv1,
            maxWidth = if (hasAv1) 1920 else 0,
            maxHeight = if (hasAv1) 1080 else 0,
            maxFps = if (hasAv1) 60 else 0
        )

        val audioCodecs = mutableMapOf<String, AudioCodecCapability>()
        audioCodecs["aac"] = AudioCodecCapability("aac", TriStateCapability.SUPPORTED, TriStateCapability.UNSUPPORTED)
        audioCodecs["opus"] = AudioCodecCapability("opus", TriStateCapability.SUPPORTED, TriStateCapability.UNSUPPORTED)

        return VoxDeviceProfile(
            platform = VoxPlatform.ANDROID_TV,
            manufacturer = "TestManufacturer",
            model = "TestModel",
            osName = "Android",
            osVersion = "14",
            videoCodecs = videoCodecs,
            audioCodecs = audioCodecs,
            audioOutput = AudioOutputCapability(TriStateCapability.SUPPORTED, TriStateCapability.SUPPORTED, TriStateCapability.UNSUPPORTED),
            display = DisplayCapability(1920, displayHeight, 60, TriStateCapability.UNSUPPORTED, TriStateCapability.UNSUPPORTED, TriStateCapability.UNSUPPORTED, TriStateCapability.UNSUPPORTED)
        )
    }
}
