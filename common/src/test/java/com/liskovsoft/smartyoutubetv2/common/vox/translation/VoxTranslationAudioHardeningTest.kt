/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException
import com.liskovsoft.smartyoutubetv2.common.vot.VoxTranslationLoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VoxTranslationAudioHardeningTest {

    @Test
    fun testDirectS3UrlDetection() {
        // Standard VOT S3 URLs
        assertTrue(VoxAudioProxyFallback.isDirectS3("https://vtrans.s3-private.mds.yandex.net/tts/prod/audio.mp3"))
        assertTrue(VoxAudioProxyFallback.isDirectS3("https://vtrans.s3-private.mds.yandex.net/tts/prod/audio.mp3?X-Amz-Signature=abcd"))
        assertTrue(VoxAudioProxyFallback.isDirectS3("https://s3-private.mds.yandex.net/tts/prod/uuid.mp3"))
        assertTrue(VoxAudioProxyFallback.isDirectS3("https://s3.mds.yandex.net/tts/uuid.mp3"))
        assertTrue(VoxAudioProxyFallback.isDirectS3("http://s3.yandex.net/tts/sample.mp3"))

        // Non-S3 or already proxified URLs
        assertFalse(VoxAudioProxyFallback.isDirectS3("https://vot-worker.eu.cc/video-translation/audio-proxy/audio.mp3"))
        assertFalse(VoxAudioProxyFallback.isDirectS3("https://example.com/audio.mp3"))
        assertFalse(VoxAudioProxyFallback.isDirectS3(null))
        assertFalse(VoxAudioProxyFallback.isDirectS3(""))
    }

    @Test
    fun testProxifyAudioPreservesQueryParams() {
        val original = "https://vtrans.s3-private.mds.yandex.net/tts/prod/12345/track.mp3?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=test1234"
        val proxied = VoxAudioProxyFallback.proxifyAudio(original)

        assertEquals(
            "https://vot-worker.eu.cc/video-translation/audio-proxy/12345/track.mp3?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=test1234",
            proxied
        )
        assertTrue(VoxAudioProxyFallback.isProxified(proxied))
        assertFalse(VoxAudioProxyFallback.isDirectS3(proxied))

        // Custom proxy host
        val customProxied = VoxAudioProxyFallback.proxifyAudio(original, "custom-proxy.example.com")
        assertEquals(
            "https://custom-proxy.example.com/video-translation/audio-proxy/12345/track.mp3?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=test1234",
            customProxied
        )
    }

    @Test
    fun testUnproxifyAudioPreservesQueryParams() {
        val proxied = "https://vot-worker.eu.cc/video-translation/audio-proxy/abcde/voice.mp3?token=xyz"
        val unproxied = VoxAudioProxyFallback.unproxifyAudio(proxied)

        assertEquals(
            "https://vtrans.s3-private.mds.yandex.net/tts/prod/abcde/voice.mp3?token=xyz",
            unproxied
        )
        assertTrue(VoxAudioProxyFallback.isDirectS3(unproxied))
        assertFalse(VoxAudioProxyFallback.isProxified(unproxied))
    }

    @Test
    fun testBoundedRouteTrackerSingleStepFallback() {
        val initialS3 = "https://vtrans.s3-private.mds.yandex.net/tts/prod/sample.mp3?token=123"
        val tracker = VoxAudioProxyFallback.BoundedRouteTracker(initialS3)

        assertEquals(VoxAudioProxyFallback.BoundedRouteTracker.Stage.PRIMARY, tracker.stage)
        assertEquals(0, tracker.fallbackAttemptCount)
        assertTrue(tracker.canAttemptFallback())

        // 1st step: switch to proxy fallback
        val fallbackUrl = tracker.trySwitchToFallback()
        assertNotNull(fallbackUrl)
        assertEquals("https://vot-worker.eu.cc/video-translation/audio-proxy/sample.mp3?token=123", fallbackUrl)
        assertEquals(VoxAudioProxyFallback.BoundedRouteTracker.Stage.FALLBACK, tracker.stage)
        assertEquals(1, tracker.fallbackAttemptCount)

        // 2nd step: cannot fallback again (bounded to 1 attempt to avoid endless flipping)
        assertFalse(tracker.canAttemptFallback())
        val exhaustedUrl = tracker.trySwitchToFallback()
        assertNull(exhaustedUrl)
        assertEquals(VoxAudioProxyFallback.BoundedRouteTracker.Stage.EXHAUSTED, tracker.stage)
        assertEquals(1, tracker.fallbackAttemptCount)
    }

    @Test
    fun testLoadErrorPolicyFastFailsOn401And403() {
        val policy = VoxTranslationLoadErrorHandlingPolicy()

        val exc401 = InvalidResponseCodeException(401, emptyMap(), null)
        val exc403 = InvalidResponseCodeException(403, emptyMap(), null)

        // 401 & 403 must fail immediately (return TIME_UNSET) on the very first try
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc401, 1))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc403, 1))
    }

    @Test
    fun testLoadErrorPolicyBoundedRetryOn404() {
        val policy = VoxTranslationLoadErrorHandlingPolicy()

        val exc404 = InvalidResponseCodeException(404, emptyMap(), null)

        // 1st retry: allow fast 500ms delay for CDN propagation
        assertEquals(500L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc404, 1))

        // 2nd retry: fail fast to let route fallback or URL refresh occur
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc404, 2))
    }

    @Test
    fun testLoadErrorPolicyBoundedRetryOnServerErrors() {
        val policy = VoxTranslationLoadErrorHandlingPolicy()

        val exc500 = InvalidResponseCodeException(500, emptyMap(), null)
        val exc429 = InvalidResponseCodeException(429, emptyMap(), null)

        // Retry 1 & 2 allowed
        assertEquals(1000L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc500, 1))
        assertEquals(1000L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc500, 2))
        assertEquals(1000L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc429, 2))

        // Retry 3 exceeds max retries
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc500, 3))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, exc429, 3))
    }

    @Test
    fun testLoadErrorPolicyGeneralIoException() {
        val policy = VoxTranslationLoadErrorHandlingPolicy()
        val networkError = IOException("Connection reset by peer")

        assertEquals(1000L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, networkError, 1))
        assertEquals(2000L, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, networkError, 2))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 100L, networkError, 3))
    }

    @Test
    fun testAudioValidatorUrlValidation() {
        val invalidResult = VoxAudioValidator.probeAudioUrl("")
        assertEquals(VoxAudioValidator.ProbeStatus.INVALID_URL, invalidResult.status)

        val nullResult = VoxAudioValidator.probeAudioUrl(null)
        assertEquals(VoxAudioValidator.ProbeStatus.INVALID_URL, nullResult.status)

        val badScheme = VoxAudioValidator.probeAudioUrl("ftp://example.com/audio.mp3")
        assertEquals(VoxAudioValidator.ProbeStatus.INVALID_URL, badScheme.status)
    }
}
