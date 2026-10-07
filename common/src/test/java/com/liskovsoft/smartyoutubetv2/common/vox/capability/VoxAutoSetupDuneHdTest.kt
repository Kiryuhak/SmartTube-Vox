package com.liskovsoft.smartyoutubetv2.common.vox.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxAutoSetupDuneHdTest {

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
                maxFps = 59
            ),
            audioOutput = AudioOutputCapability(TriStateCapability.SUPPORTED)
        )
    }

    @Test
    fun testDuneHd4kHardwareDecodeDetection() {
        val profile = createDuneHdProfile()
        assertTrue("DuneHD should have 4K HW decode capability", profile.has4kHardwareDecode())
    }

    @Test
    fun testCase1_Display1080p_4kHwDecoder_NoPlaybackProblems_DoesNotHardBan4k() {
        val profile = createDuneHdProfile()
        val tuner = VoxDeviceAutoTuner()
        val recommended = tuner.tune(profile)

        val manual4kPolicy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.MAX_QUALITY,
            maxQualityHeight = 2160,
            preferredVideoCodec = VoxVideoCodecPreference.VP9,
            preferredAudioCodec = VoxAudioCodecPreference.AC3,
            passthroughEnabled = true
        )

        // No playback problems (runtimeRebuffers = 0, slowStartup = false)
        val risk = VoxCompatibilityRiskEvaluator.evaluate(
            proposedPolicy = manual4kPolicy,
            recommended = recommended,
            profile = profile,
            runtimeRebuffers = 0,
            slowStartup = false
        )

        assertNull("4K should not be banned when device has HW 4K decoder and no playback errors", risk)
    }

    @Test
    fun testCase2_Display1080p_4kSelected_MultipleRebuffers_RecommendsAuto() {
        val profile = createDuneHdProfile()
        val tuner = VoxDeviceAutoTuner()
        val recommended = tuner.tune(profile)

        val manual4kPolicy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.MAX_QUALITY,
            maxQualityHeight = 2160
        )

        // Multiple rebuffers observed
        val risk = VoxCompatibilityRiskEvaluator.evaluate(
            proposedPolicy = manual4kPolicy,
            recommended = recommended,
            profile = profile,
            runtimeRebuffers = 2,
            slowStartup = true
        )

        assertNotNull("Risk/Recommendation should be emitted when repeated rebuffers occur", risk)
        assertEquals("Повторные буферизации", risk!!.titleRu)
        assertTrue(
            "Recommendation message should suggest Auto",
            risk.messageRu.contains("рекомендуется профиль «Автоматически»")
        )
    }

    @Test
    fun testCase3_ManualMaxQuality_RecommendationDoesNotMutateUserPolicy() {
        val profile = createDuneHdProfile()
        val manual4kPolicy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.MAX_QUALITY,
            maxQualityHeight = 2160
        )
        val tuner = VoxDeviceAutoTuner()
        val recommended = tuner.tune(profile)

        val risk = VoxCompatibilityRiskEvaluator.evaluate(
            proposedPolicy = manual4kPolicy,
            recommended = recommended,
            profile = profile,
            runtimeRebuffers = 3
        )

        assertNotNull(risk)
        // Ensure proposedPolicy was NOT silently mutated
        assertEquals(VoxCodecPolicyMode.MAX_QUALITY, manual4kPolicy.mode)
        assertEquals(2160, manual4kPolicy.maxQualityHeight)
    }
}
