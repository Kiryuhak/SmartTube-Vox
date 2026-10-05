package com.liskovsoft.smartyoutubetv2.common.vox.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxDeviceAutoTunerTest {

    @Test
    fun testTuneLowEndProfile() {
        val profile = VoxDeviceProfile(
            platform = VoxPlatform.ANDROID_TV,
            manufacturer = "BasicBox",
            model = "Amlogic S905X",
            videoCodecs = mapOf(
                "avc" to VideoCodecCapability(codec = "avc", capability = TriStateCapability.SUPPORTED, hardwareAccelerated = true, maxHeight = 1080),
                "vp9" to VideoCodecCapability(codec = "vp9", capability = TriStateCapability.UNSUPPORTED, hardwareAccelerated = false),
                "av1" to VideoCodecCapability(codec = "av1", capability = TriStateCapability.UNSUPPORTED, hardwareAccelerated = false)
            ),
            audioCodecs = mapOf(
                "aac" to AudioCodecCapability(codec = "aac", decodeCapability = TriStateCapability.SUPPORTED),
                "ac3" to AudioCodecCapability(codec = "ac3", decodeCapability = TriStateCapability.UNSUPPORTED)
            ),
            display = DisplayCapability(maxWidth = 1920, maxHeight = 1080, maxFps = 60),
            audioOutput = AudioOutputCapability(stereo = TriStateCapability.SUPPORTED, passthrough = TriStateCapability.UNSUPPORTED)
        )

        val tuner = VoxDeviceAutoTuner(null)
        val rec = tuner.tune(profile)

        assertEquals(VoxPerformanceTier.BASIC, rec.tier)
        assertEquals(1080, rec.maxQualityHeight)
        assertEquals(VoxVideoCodecPreference.AVC, rec.preferredVideoCodec)
        assertFalse(rec.passthroughEnabled)
        assertTrue(rec.reasonCodes.contains("TIER_BASIC"))
        assertTrue(rec.reasonCodes.contains("DISPLAY_1080P"))
    }

    @Test
    fun testTuneStandardProfile() {
        val profile = VoxDeviceProfile(
            platform = VoxPlatform.ANDROID_TV,
            manufacturer = "StandardTV",
            model = "MiBox4",
            videoCodecs = mapOf(
                "avc" to VideoCodecCapability(codec = "avc", capability = TriStateCapability.SUPPORTED, hardwareAccelerated = true, maxHeight = 1080),
                "vp9" to VideoCodecCapability(codec = "vp9", capability = TriStateCapability.SUPPORTED, hardwareAccelerated = true, maxHeight = 1080),
                "av1" to VideoCodecCapability(codec = "av1", capability = TriStateCapability.UNSUPPORTED, hardwareAccelerated = false)
            ),
            audioCodecs = mapOf(
                "aac" to AudioCodecCapability(codec = "aac", decodeCapability = TriStateCapability.SUPPORTED),
                "ac3" to AudioCodecCapability(codec = "ac3", decodeCapability = TriStateCapability.SUPPORTED, passthroughCapability = TriStateCapability.SUPPORTED)
            ),
            display = DisplayCapability(maxWidth = 1920, maxHeight = 1080, maxFps = 60),
            audioOutput = AudioOutputCapability(stereo = TriStateCapability.SUPPORTED, passthrough = TriStateCapability.SUPPORTED)
        )

        val tuner = VoxDeviceAutoTuner(null)
        val rec = tuner.tune(profile)

        assertEquals(VoxPerformanceTier.STANDARD, rec.tier)
        assertEquals(1080, rec.maxQualityHeight)
        assertEquals(VoxVideoCodecPreference.VP9, rec.preferredVideoCodec)
        assertNotEquals(VoxVideoCodecPreference.AV1, rec.preferredVideoCodec)
        assertEquals(VoxAudioCodecPreference.AC3, rec.preferredAudioCodec)
        assertTrue(rec.passthroughEnabled)
        assertTrue(rec.reasonCodes.contains("TIER_STANDARD"))
        assertTrue(rec.reasonCodes.contains("HW_VP9_AVAILABLE"))
    }

    @Test
    fun testTunePowerfulProfileWithAv1() {
        val profile = VoxDeviceProfile(
            platform = VoxPlatform.ANDROID_TV,
            manufacturer = "FlagshipTV",
            model = "ShieldPro/Bravia",
            videoCodecs = mapOf(
                "avc" to VideoCodecCapability(codec = "avc", capability = TriStateCapability.SUPPORTED, hardwareAccelerated = true, maxWidth = 3840, maxHeight = 2160, maxFps = 60),
                "vp9" to VideoCodecCapability(codec = "vp9", capability = TriStateCapability.SUPPORTED, hardwareAccelerated = true, maxWidth = 3840, maxHeight = 2160, maxFps = 60),
                "av1" to VideoCodecCapability(codec = "av1", capability = TriStateCapability.SUPPORTED, hardwareAccelerated = true, maxWidth = 3840, maxHeight = 2160, maxFps = 60)
            ),
            audioCodecs = mapOf(
                "aac" to AudioCodecCapability(codec = "aac", decodeCapability = TriStateCapability.SUPPORTED),
                "eac3" to AudioCodecCapability(codec = "eac3", decodeCapability = TriStateCapability.SUPPORTED, passthroughCapability = TriStateCapability.SUPPORTED)
            ),
            display = DisplayCapability(maxWidth = 3840, maxHeight = 2160, maxFps = 60, hdr10 = TriStateCapability.SUPPORTED),
            audioOutput = AudioOutputCapability(stereo = TriStateCapability.SUPPORTED, multichannel = TriStateCapability.SUPPORTED, passthrough = TriStateCapability.SUPPORTED)
        )

        val tuner = VoxDeviceAutoTuner(null)
        val rec = tuner.tune(profile)

        // Tier will be STANDARD or POWERFUL depending on total RAM check in test environment
        assertTrue(rec.tier == VoxPerformanceTier.STANDARD || rec.tier == VoxPerformanceTier.POWERFUL)
        assertEquals(2160, rec.maxQualityHeight)
        assertTrue(rec.preferredVideoCodec == VoxVideoCodecPreference.AV1 || rec.preferredVideoCodec == VoxVideoCodecPreference.VP9)
        assertTrue(rec.passthroughEnabled)
        assertTrue(rec.reasonCodes.contains("DISPLAY_4K"))
        assertTrue(rec.reasonCodes.contains("HW_AV1_AVAILABLE"))
    }
}
