package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApi
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class VoxTranslationResolverHeartbeatTest {

    @Test
    fun translatedDownloadReadyUsesExistingTranslation() {
        val fakeApi = object : YandexVotApi {
            override fun requestTranslation(
                videoUrl: String,
                duration: Double,
                sourceLang: String?,
                targetLang: String,
                videoTitle: String?,
                useLiveVoices: Boolean,
                oauthToken: String?,
                firstRequest: Boolean
            ): YandexVotApiClient.TranslationResult? {
                return YandexVotApiClient.TranslationResult(
                    YandexVotApiClient.STATUS_FINISHED,
                    "https://vtrans.yandex.net/trans_ready.mp3",
                    0,
                    "trans_123",
                    null
                )
            }
        }

        val resolver = DefaultVoxTranslationResolver(
            apiClient = fakeApi,
            oauthTokenProvider = { null },
            sleeper = {}
        )

        val resolved = resolver.resolveTranslation("ready_vid", VoxTranslationMode.STANDARD)
        assertNotNull(resolved)
        assertEquals("https://vtrans.yandex.net/trans_ready.mp3", resolved.url)
    }

    @Test
    fun translatedDownloadProcessingContinuesWithHeartbeatExceedingLegacy60Limit() {
        val callCount = AtomicInteger(0)
        val fakeApi = object : YandexVotApi {
            override fun requestTranslation(
                videoUrl: String,
                duration: Double,
                sourceLang: String?,
                targetLang: String,
                videoTitle: String?,
                useLiveVoices: Boolean,
                oauthToken: String?,
                firstRequest: Boolean
            ): YandexVotApiClient.TranslationResult? {
                val current = callCount.incrementAndGet()
                return if (current <= 65) {
                    // Simulate waiting with heartbeat (0s delay in unit test)
                    YandexVotApiClient.TranslationResult(
                        YandexVotApiClient.STATUS_WAITING,
                        null,
                        0,
                        "trans_456",
                        null
                    )
                } else {
                    YandexVotApiClient.TranslationResult(
                        YandexVotApiClient.STATUS_FINISHED,
                        "https://vtrans.yandex.net/trans_heartbeat.mp3",
                        0,
                        "trans_456",
                        null
                    )
                }
            }
        }

        val resolver = DefaultVoxTranslationResolver(
            apiClient = fakeApi,
            oauthTokenProvider = { null },
            sleeper = {}
        )

        val resolved = resolver.resolveTranslation("long_vid", VoxTranslationMode.STANDARD)
        assertNotNull(resolved)
        assertEquals("https://vtrans.yandex.net/trans_heartbeat.mp3", resolved.url)
        assertTrue("Must have polled > 60 times, actual=${callCount.get()}", callCount.get() > 60)
    }

    @Test
    fun translationNetworkRetrySurvivesTransientFailures() {
        val attempts = AtomicInteger(0)
        val fakeApi = object : YandexVotApi {
            override fun requestTranslation(
                videoUrl: String,
                duration: Double,
                sourceLang: String?,
                targetLang: String,
                videoTitle: String?,
                useLiveVoices: Boolean,
                oauthToken: String?,
                firstRequest: Boolean
            ): YandexVotApiClient.TranslationResult? {
                val att = attempts.incrementAndGet()
                if (att <= 2) {
                    throw java.io.IOException("Transient socket timeout")
                }
                return YandexVotApiClient.TranslationResult(
                    YandexVotApiClient.STATUS_FINISHED,
                    "https://vtrans.yandex.net/trans_recovered.mp3",
                    0,
                    "trans_retry",
                    null
                )
            }
        }

        val resolver = DefaultVoxTranslationResolver(
            apiClient = fakeApi,
            oauthTokenProvider = { null },
            sleeper = {}
        )

        val resolved = resolver.resolveTranslation("retry_vid", VoxTranslationMode.STANDARD)
        assertNotNull(resolved)
        assertEquals("https://vtrans.yandex.net/trans_recovered.mp3", resolved.url)
        assertEquals(3, attempts.get())
    }

    @Test
    fun translationExceedingMaxNetworkRetriesFailsWithNetworkError() {
        val fakeApi = object : YandexVotApi {
            override fun requestTranslation(
                videoUrl: String,
                duration: Double,
                sourceLang: String?,
                targetLang: String,
                videoTitle: String?,
                useLiveVoices: Boolean,
                oauthToken: String?,
                firstRequest: Boolean
            ): YandexVotApiClient.TranslationResult? {
                throw java.io.IOException("Persistent network outage")
            }
        }

        val resolver = DefaultVoxTranslationResolver(
            apiClient = fakeApi,
            oauthTokenProvider = { null },
            sleeper = {}
        )

        try {
            resolver.resolveTranslation("fail_vid", VoxTranslationMode.STANDARD)
            fail("Expected network exception after exceeding max retries")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.NETWORK_ERROR, e.code)
            assertTrue(e.message?.contains("after 6 attempts") == true || e.message?.contains("attempts") == true)
        }
    }
}
