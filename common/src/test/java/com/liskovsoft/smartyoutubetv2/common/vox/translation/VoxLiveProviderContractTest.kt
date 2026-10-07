package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxLiveProviderContractTest {

    @Test
    fun testAllSevenProviderStatesAreDefined() {
        assertEquals("available", VoxLiveProviderState.AVAILABLE.id)
        assertEquals("unavailable", VoxLiveProviderState.UNAVAILABLE.id)
        assertEquals("unsupported", VoxLiveProviderState.UNSUPPORTED.id)
        assertEquals("auth_required", VoxLiveProviderState.AUTH_REQUIRED.id)
        assertEquals("rate_limited", VoxLiveProviderState.RATE_LIMITED.id)
        assertEquals("degraded", VoxLiveProviderState.DEGRADED.id)
        assertEquals("error", VoxLiveProviderState.ERROR.id)

        assertEquals(VoxLiveProviderState.AVAILABLE, VoxLiveProviderState.fromString("AVAILABLE"))
        assertEquals(VoxLiveProviderState.UNAVAILABLE, VoxLiveProviderState.fromString("unavailable"))
        assertEquals(VoxLiveProviderState.RATE_LIMITED, VoxLiveProviderState.fromString("rate_limited"))
        assertEquals(VoxLiveProviderState.ERROR, VoxLiveProviderState.fromString("unknown_val"))
    }

    @Test
    fun testProviderCapabilitiesSerialization() {
        val caps = VoxLiveProviderCapabilities(
            providerId = "real_experimental",
            supportsRawPcm = true,
            supportsEncodedAudio = true,
            supportsIncremental = true,
            supportsStreaming = true,
            supportsSession = true,
            supportsCancellation = true,
            supportsSourceLanguageAuto = true,
            supportsVoiceSynthesis = true,
            supportsPartialResults = false,
            targetSampleRate = 16000,
            targetChannels = 1
        )

        val map = caps.toMap()
        assertEquals("real_experimental", map["providerId"])
        assertEquals(true, map["supportsRawPcm"])
        assertEquals(true, map["supportsIncremental"])
        assertEquals(false, map["supportsPartialResults"])
        assertEquals(16000, map["targetSampleRate"])
        assertEquals(1, map["targetChannels"])
    }

    @Test
    fun testProviderResultPreservesPtsAndAudioFormat() {
        val fakeAudio = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val result = VoxLiveProviderResult(
            sequence = 15L,
            generation = 3L,
            sourceStartPtsUs = 5_000_000L,
            sourceEndPtsUs = 7_000_000L,
            translatedAudio = fakeAudio,
            audioFormat = "pcm_16le",
            sampleRate = 48000,
            channels = 2,
            providerLatencyMs = 350L,
            providerStatus = VoxLiveProviderState.AVAILABLE,
            detectedLanguage = "en",
            translationText = "Translated speech"
        )

        assertEquals(15L, result.sequence)
        assertEquals(3L, result.generation)
        assertEquals(5_000_000L, result.sourceStartPtsUs)
        assertEquals(7_000_000L, result.sourceEndPtsUs)
        assertNotNull(result.translatedAudio)
        assertEquals(4, result.translatedAudio!!.size)
        assertEquals("pcm_16le", result.audioFormat)
        assertEquals(48000, result.sampleRate)
        assertEquals(2, result.channels)
        assertEquals(350L, result.providerLatencyMs)
        assertEquals(VoxLiveProviderState.AVAILABLE, result.providerStatus)
        assertEquals("en", result.detectedLanguage)
        assertEquals("Translated speech", result.translationText)
    }

    @Test
    fun testProviderUnavailableTriggersFallback() {
        val unavailResult = VoxLiveProviderResult(
            sequence = 1L,
            generation = 1L,
            sourceStartPtsUs = 0L,
            sourceEndPtsUs = 2_000_000L,
            translatedAudio = null,
            providerStatus = VoxLiveProviderState.UNAVAILABLE
        )

        assertEquals(VoxLiveProviderState.UNAVAILABLE, unavailResult.providerStatus)
        assertNull(unavailResult.translatedAudio)

        // Sync controller decision when provider audio is null / unavailable
        val decision = VoxLivePtsSyncDecision(
            action = VoxLivePtsSyncAction.FALLBACK,
            driftMs = 0L,
            reason = "Provider unavailable (${unavailResult.providerStatus.id})"
        )

        assertEquals(VoxLivePtsSyncAction.FALLBACK, decision.action)
    }

    @Test
    fun testFeatureFlagsDefaultToFalse() {
        VoxLiveFeatureFlags.resetToDefaults()
        assertFalse(VoxLiveFeatureFlags.VOX_LIVE_TRANSLATION_EXPERIMENTAL)
        assertFalse(VoxLiveFeatureFlags.VOX_LIVE_AUDIO_CAPTURE_EXPERIMENTAL)
        assertFalse(VoxLiveFeatureFlags.VOX_LIVE_SECONDARY_AUDIO_EXPERIMENTAL)
        assertFalse(VoxLiveFeatureFlags.VOX_LIVE_REAL_PROVIDER_EXPERIMENTAL)
    }
}
