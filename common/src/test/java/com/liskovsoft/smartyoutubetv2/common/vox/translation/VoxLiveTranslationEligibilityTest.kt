package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxLiveTranslationEligibilityTest {

    @Test
    fun testNonLiveStreamRejected() {
        val result = VoxLiveTranslationEligibility.checkEligibility(
            isLive = false,
            isDvrAvailable = true,
            isSeekable = true,
            audioTrackAvailable = true,
            sourceLanguage = "en",
            backendCapability = VoxLiveBackendCapability.LIVE_CHUNK_SUPPORTED
        )
        assertEquals(VoxLiveEligibilityStatus.UNSUPPORTED, result.status)
        assertTrue(result.technicalDetails.contains("isLive=false"))
    }

    @Test
    fun testNoAudioTrackRejected() {
        val result = VoxLiveTranslationEligibility.checkEligibility(
            isLive = true,
            isDvrAvailable = true,
            isSeekable = true,
            audioTrackAvailable = false,
            sourceLanguage = "en",
            backendCapability = VoxLiveBackendCapability.LIVE_CHUNK_SUPPORTED
        )
        assertEquals(VoxLiveEligibilityStatus.UNSUPPORTED, result.status)
        assertTrue(result.technicalDetails.contains("audioTrackAvailable=false"))
    }

    @Test
    fun testRussianOriginalRejected() {
        val result = VoxLiveTranslationEligibility.checkEligibility(
            isLive = true,
            isDvrAvailable = true,
            isSeekable = true,
            audioTrackAvailable = true,
            sourceLanguage = "ru",
            backendCapability = VoxLiveBackendCapability.LIVE_CHUNK_SUPPORTED
        )
        assertEquals(VoxLiveEligibilityStatus.UNSUPPORTED, result.status)
        assertTrue(result.reasonRu.contains("русском"))
    }

    @Test
    fun testVodOnlyBackendRejectedTruthfully() {
        val result = VoxLiveTranslationEligibility.checkEligibility(
            isLive = true,
            isDvrAvailable = true,
            isSeekable = true,
            audioTrackAvailable = true,
            sourceLanguage = "en",
            backendCapability = VoxLiveBackendCapability.VOD_ONLY
        )
        assertEquals(VoxLiveEligibilityStatus.UNSUPPORTED, result.status)
        assertTrue(result.technicalDetails.contains("VOD_ONLY"))
    }

    @Test
    fun testSupportedLiveStreamWithDvr() {
        val result = VoxLiveTranslationEligibility.checkEligibility(
            isLive = true,
            isDvrAvailable = true,
            isSeekable = true,
            audioTrackAvailable = true,
            sourceLanguage = "en",
            backendCapability = VoxLiveBackendCapability.LIVE_CHUNK_SUPPORTED
        )
        assertEquals(VoxLiveEligibilityStatus.SUPPORTED, result.status)
    }

    @Test
    fun testSupportedWithLimitsWhenNoDvr() {
        val result = VoxLiveTranslationEligibility.checkEligibility(
            isLive = true,
            isDvrAvailable = false,
            isSeekable = false,
            audioTrackAvailable = true,
            sourceLanguage = "en",
            backendCapability = VoxLiveBackendCapability.LIVE_CHUNK_SUPPORTED
        )
        assertEquals(VoxLiveEligibilityStatus.SUPPORTED_WITH_LIMITS, result.status)
    }
}
