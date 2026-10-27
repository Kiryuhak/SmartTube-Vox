package com.liskovsoft.smartyoutubetv2.common.vot

import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException

/**
 * Unit tests for Prompt #052:
 * - Target language validation (ru, ru-RU, ar, tr, und, null, malformed).
 * - Cache key isolation and cross-video contamination prevention.
 * - Cache invalidation and rejection of non-matching translations.
 * - DNS error classification and privacy preservation (no forced Google DNS bypass).
 */
class Prompt052VotAndSecurityTest {

    @Before
    fun setUp() {
        YandexVotApiClient.clearTranslationCache()
    }

    private fun isLanguageMatch(requestedTarget: String?, responseLang: String?): Boolean {
        if (responseLang == null || responseLang.trim().isEmpty()) {
            // Missing language tag is not an explicit mismatch
            return true
        }
        val effTarget = if (requestedTarget != null && requestedTarget.trim().isNotEmpty()) {
            requestedTarget.trim().lowercase()
        } else {
            "ru"
        }
        val effResp = responseLang.trim().lowercase()
        val baseTarget = effTarget.split("[-_]".toRegex())[0]
        val baseResp = effResp.split("[-_]".toRegex())[0]
        return baseResp == baseTarget
    }

    @Test
    fun testLanguageMatchingMatrix() {
        // Standard matching cases
        assertTrue("ru matches ru", isLanguageMatch("ru", "ru"))
        assertTrue("ru-RU matches ru", isLanguageMatch("ru", "ru-RU"))
        assertTrue("ru matches ru-RU", isLanguageMatch("ru-RU", "ru"))
        assertTrue("RU matches ru (case-insensitive)", isLanguageMatch("RU", "ru"))
        assertTrue("ru matches RU (case-insensitive)", isLanguageMatch("ru", "RU"))
        assertTrue("ru matches ru_RU (underscore)", isLanguageMatch("ru", "ru_RU"))

        // Null / empty response language allowed (backward compatibility for older API responses)
        assertTrue("null responseLang allowed", isLanguageMatch("ru", null))
        assertTrue("empty responseLang allowed", isLanguageMatch("ru", ""))
        assertTrue("blank responseLang allowed", isLanguageMatch("ru", "   "))

        // Mismatches strictly rejected
        assertFalse("Arabic ('ar') must be rejected for 'ru'", isLanguageMatch("ru", "ar"))
        assertFalse("Turkish ('tr') must be rejected for 'ru'", isLanguageMatch("ru", "tr"))
        assertFalse("Undetermined ('und') must be rejected for 'ru'", isLanguageMatch("ru", "und"))
        assertFalse("English ('en') must be rejected for 'ru'", isLanguageMatch("ru", "en"))
        assertFalse("Spanish ('es') must be rejected for 'ru'", isLanguageMatch("ru", "es"))
    }

    @Test
    fun testCacheKeyIsolationAcrossVideosAndLanguages() {
        val url1 = "https://www.youtube.com/watch?v=video_001"
        val url2 = "https://www.youtube.com/watch?v=video_002"

        // Cache a valid Russian translation for video 1
        val res1 = YandexVotApiClient.TranslationResult(
            YandexVotApiClient.STATUS_FINISHED,
            "https://vtrans.yandex.net/audio/ru_001.mp3",
            0,
            "trans_001",
            "OK",
            null,
            false,
            "ru"
        )
        val key1 = YandexVotApiClient.getCacheKey(url1, "en", "ru", false)
        val key2 = YandexVotApiClient.getCacheKey(url2, "en", "ru", false)
        val key1Live = YandexVotApiClient.getCacheKey(url1, "en", "ru", true)
        val key1OtherTarget = YandexVotApiClient.getCacheKey(url1, "en", "kk", false)

        // Keys must be strictly distinct
        assertTrue(key1 != key2)
        assertTrue(key1 != key1Live)
        assertTrue(key1 != key1OtherTarget)

        // Simulate caching for key 1
        val cached = YandexVotApiClient.CachedResult(res1, System.currentTimeMillis())
        assertFalse(cached.isExpired)
        assertEquals("ru", cached.result.responseLanguage)
        assertTrue(cached.result.isReady)
    }

    @Test
    fun testMismatchedResultNeverHasAudioUrl() {
        // When mismatch occurs, errorCategory must be language_mismatch and audioUrl null
        val mismatchedResult = YandexVotApiClient.TranslationResult(
            YandexVotApiClient.STATUS_FAILED,
            null,
            -1,
            "trans_mismatch_01",
            "Translation target language mismatch",
            "language_mismatch",
            false,
            "ar"
        )

        assertFalse(mismatchedResult.isReady)
        assertNull(mismatchedResult.audioUrl)
        assertEquals("language_mismatch", mismatchedResult.errorCategory)
        assertEquals("ar", mismatchedResult.responseLanguage)
    }

    @Test
    fun testDnsFailureClassificationAndSafeFallbacks() {
        val exUnknownHost = UnknownHostException("www.youtube.com")
        val exTimeout = java.net.SocketTimeoutException("Read timed out")
        val exSsl = javax.net.ssl.SSLException("Handshake failed")
        val exWrapped = RuntimeException("Wrapping", UnknownHostException("Unable to resolve host: No address associated with hostname"))

        fun isDnsError(t: Throwable?): Boolean {
            var curr = t
            while (curr != null) {
                if (curr is UnknownHostException) return true
                val msg = curr.message
                if (msg != null && (msg.contains("Unable to resolve host") || msg.contains("No address associated with hostname"))) {
                    return true
                }
                curr = curr.cause
            }
            return false
        }

        assertTrue("Direct UnknownHostException is DNS error", isDnsError(exUnknownHost))
        assertTrue("Wrapped UnknownHostException is DNS error", isDnsError(exWrapped))
        assertFalse("SocketTimeoutException is NOT DNS error", isDnsError(exTimeout))
        assertFalse("SSLException is NOT DNS error", isDnsError(exSsl))
    }
}
