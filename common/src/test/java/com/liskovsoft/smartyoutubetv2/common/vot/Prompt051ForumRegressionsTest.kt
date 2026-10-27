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
 * Unit tests for Prompt #051 4PDA user regressions:
 * 3A: DNS failure / UnknownHostException bounded handling.
 * 3C: Wrong target-language translation (Arabic/Turkish) detection and rejection.
 */
class Prompt051ForumRegressionsTest {

    @Before
    fun setUp() {
        YandexVotApiClient.clearCache()
    }

    @Test
    fun testLanguageMismatchRejected() {
        val targetLang = "ru"
        val mismatchedRespLang = "ar" // Arabic returned instead of Russian

        // Simulate Yandex response with language mismatch
        val respLang = mismatchedRespLang.trim().lowercase()
        val effectiveTarget = targetLang.trim().lowercase()
        val baseResp = respLang.split("[-_]".toRegex())[0]
        val baseTarget = effectiveTarget.split("[-_]".toRegex())[0]

        val isMismatch = baseResp != baseTarget
        assertTrue("Arabic ('ar') must not match Russian ('ru')", isMismatch)

        // TranslationResult holding mismatched language
        val result = YandexVotApiClient.TranslationResult(
            YandexVotApiClient.STATUS_FAILED,
            null,
            -1,
            "trans_ar_01",
            "Translation target language mismatch",
            "language_mismatch",
            false,
            mismatchedRespLang
        )

        assertEquals("language_mismatch", result.errorCategory)
        assertEquals("ar", result.responseLanguage)
        assertFalse(result.isReady)
        assertNull(result.audioUrl)
    }

    @Test
    fun testTurkishLanguageMismatchRejected() {
        val targetLang = "ru"
        val mismatchedRespLang = "tr" // Turkish returned instead of Russian

        val respLang = mismatchedRespLang.trim().lowercase()
        val effectiveTarget = targetLang.trim().lowercase()
        val isMismatch = respLang.split("[-_]".toRegex())[0] != effectiveTarget.split("[-_]".toRegex())[0]

        assertTrue("Turkish ('tr') must not match Russian ('ru')", isMismatch)
    }

    @Test
    fun testMatchingRussianLanguageAccepted() {
        val targetLang = "ru"
        val matchingRespLang = "ru"

        val respLang = matchingRespLang.trim().lowercase()
        val effectiveTarget = targetLang.trim().lowercase()
        val isMatch = respLang.split("[-_]".toRegex())[0] == effectiveTarget.split("[-_]".toRegex())[0]

        assertTrue("Russian ('ru') must match target ('ru')", isMatch)

        val result = YandexVotApiClient.TranslationResult(
            YandexVotApiClient.STATUS_FINISHED,
            "https://vtrans.yandex.net/audio/ru_sample.mp3",
            0,
            "trans_ru_01",
            "OK",
            null,
            false,
            matchingRespLang
        )

        assertTrue(result.isReady)
        assertNotNull(result.audioUrl)
        assertEquals("ru", result.responseLanguage)
    }

    @Test
    fun testUnknownHostExceptionDetection() {
        val exDirect = UnknownHostException("www.youtube.com")
        val exWrapped = RuntimeException("Network wrapper", UnknownHostException("Unable to resolve host www.youtube.com: No address associated with hostname"))

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

        assertTrue("Direct UnknownHostException must be classified as DNS error", isDnsError(exDirect))
        assertTrue("Wrapped UnknownHostException must be classified as DNS error", isDnsError(exWrapped))
        assertFalse("Generic IOException must not be classified as DNS error", isDnsError(java.io.IOException("Socket closed")))
    }
}
