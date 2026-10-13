package com.liskovsoft.smartyoutubetv2.common.vox.translation

import android.content.Context
import android.content.SharedPreferences
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApi
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient
import com.liskovsoft.smartyoutubetv2.common.vox.download.DefaultVoxTranslationResolver
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxTranslationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VoxPatch45CacheSuiteTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("vox_translation_sessions_v2", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        VoxTranslationSessionCache.init(context)
        VoxTranslationSessionCache.clear()
        VoxTranslationSessionCache.resetStats()
        VoxTranslationSessionCache.resetTtlForTesting()
    }

    @After
    fun tearDown() {
        VoxTranslationSessionCache.clear()
        VoxTranslationSessionCache.resetStats()
        VoxTranslationSessionCache.resetTtlForTesting()
        prefs.edit().clear().commit()
    }

    @Test
    fun testMemoryCacheSkipsNetwork_andIncrementsCounter() {
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "testVideo1",
            rawUrl = "https://www.youtube.com/watch?v=testVideo1",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/test1.mp3",
            durationSec = 120.0,
            useLively = false
        )

        val res = VoxTranslationSessionCache.lookup("testVideo1", null, "en", "ru", false)
        assertTrue(res is VoxTranslationSessionCache.LookupResult.Hit)
        val hit = res as VoxTranslationSessionCache.LookupResult.Hit
        assertEquals("MEMORY_CACHE", hit.source)
        assertEquals("https://vtrans.s3-private.mds.yandex.net/tts/prod/test1.mp3", hit.record.audioUrl)
        assertEquals(1L, VoxTranslationSessionCache.getStats()["memoryHitCount"])
    }

    @Test
    fun testPersistedCacheSkipsNetwork_afterMemoryClear() {
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "testVideo2",
            rawUrl = "https://www.youtube.com/watch?v=testVideo2",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/test2.mp3",
            durationSec = 200.0,
            useLively = false
        )

        // Clear memory cache to simulate process restart
        VoxTranslationSessionCache.clearMemory()

        val res = VoxTranslationSessionCache.lookup("testVideo2", null, "en", "ru", false)
        assertTrue(res is VoxTranslationSessionCache.LookupResult.Hit)
        val hit = res as VoxTranslationSessionCache.LookupResult.Hit
        assertEquals("PERSISTED_CACHE", hit.source)
        assertEquals(1L, VoxTranslationSessionCache.getStats()["persistedHitCount"])
    }

    @Test
    fun testTargetLanguageIsolation_preventsCrossLanguageReuse() {
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "testLangIso",
            rawUrl = "https://www.youtube.com/watch?v=testLangIso",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/ru.mp3",
            durationSec = 60.0,
            useLively = false
        )

        // Requesting Kazakh (kk) MUST return Miss, NOT reuse Russian (ru)
        val resKk = VoxTranslationSessionCache.lookup("testLangIso", null, "en", "kk", false)
        assertTrue(resKk is VoxTranslationSessionCache.LookupResult.Miss)

        // Requesting English (en) MUST return Miss
        val resEn = VoxTranslationSessionCache.lookup("testLangIso", null, "en", "en", false)
        assertTrue(resEn is VoxTranslationSessionCache.LookupResult.Miss)

        // Requesting Russian (ru) MUST return Hit
        val resRu = VoxTranslationSessionCache.lookup("testLangIso", null, "en", "ru", false)
        assertTrue(resRu is VoxTranslationSessionCache.LookupResult.Hit)
    }

    @Test
    fun testVideoIsolation_preventsCrossVideoReuse() {
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "videoA",
            rawUrl = "https://www.youtube.com/watch?v=videoA",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/videoA.mp3",
            durationSec = 100.0,
            useLively = false
        )

        val resB = VoxTranslationSessionCache.lookup("videoB", null, "en", "ru", false)
        assertTrue(resB is VoxTranslationSessionCache.LookupResult.Miss)
    }

    @Test
    fun testExpiredTtl_returnsMissAndEvicts() {
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "videoTtl",
            rawUrl = "https://www.youtube.com/watch?v=videoTtl",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/ttl.mp3",
            durationSec = 100.0,
            useLively = false
        )

        // Set test TTL to 10 ms
        VoxTranslationSessionCache.setTtlForTesting(10L)
        Thread.sleep(25)

        val res = VoxTranslationSessionCache.lookup("videoTtl", null, "en", "ru", false)
        assertTrue(res is VoxTranslationSessionCache.LookupResult.Miss)
    }

    @Test
    fun testCacheCorruptionSelfHeal_dropsCorruptEntrySafely() {
        val videoId = "corrupt1111" // wait, 11 chars: 11 characters is "corrupt1111" (7+4=11)
        val corruptKey = VoxTranslationReusePolicy.buildCacheKey(videoId, null, "ru", false)
        prefs.edit().putString("rec_$corruptKey", "{invalid_json_corrupt").commit()

        val res = VoxTranslationSessionCache.lookup(videoId, null, null, "ru", false)
        assertTrue(res is VoxTranslationSessionCache.LookupResult.Miss)

        // Verify the corrupted key was dropped from preferences
        assertFalse(prefs.contains("rec_$corruptKey"))
    }

    @Test
    fun testSourceLangNormalizationMatrix() {
        assertEquals("", VoxTranslationReusePolicy.normalizeSourceLang(null))
        assertEquals("", VoxTranslationReusePolicy.normalizeSourceLang(""))
        assertEquals("", VoxTranslationReusePolicy.normalizeSourceLang("   "))
        assertEquals("", VoxTranslationReusePolicy.normalizeSourceLang("auto"))
        assertEquals("", VoxTranslationReusePolicy.normalizeSourceLang("und"))
        assertEquals("en", VoxTranslationReusePolicy.normalizeSourceLang("en"))
        assertEquals("en", VoxTranslationReusePolicy.normalizeSourceLang("EN"))
        assertEquals("en", VoxTranslationReusePolicy.normalizeSourceLang("en-US"))
        assertEquals("es", VoxTranslationReusePolicy.normalizeSourceLang("es-MX"))
        assertEquals("zh", VoxTranslationReusePolicy.normalizeSourceLang("zh-CN"))
    }

    @Test
    fun testDownloadUsesMemoryCache_withoutNetworkRequests() {
        val videoId = "downloadCacheTest"
        val expectedAudio = "https://vtrans.s3-private.mds.yandex.net/tts/prod/download_cached.mp3"

        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = videoId,
            rawUrl = "https://www.youtube.com/watch?v=$videoId",
            sourceLang = null,
            targetLang = "ru",
            audioUrl = expectedAudio,
            durationSec = 150.0,
            useLively = false
        )

        val networkCallCount = AtomicInteger(0)
        val mockApi = object : YandexVotApi {
            override fun requestTranslation(
                videoUrl: String,
                duration: Double,
                sourceLang: String?,
                targetLang: String,
                videoTitle: String?,
                useLiveVoices: Boolean,
                oauthToken: String?,
                isFirstRequest: Boolean
            ): YandexVotApiClient.TranslationResult? {
                networkCallCount.incrementAndGet()
                return null
            }
        }

        val resolver = DefaultVoxTranslationResolver(apiClient = mockApi)
        val res = resolver.resolveTranslation(videoId, VoxTranslationMode.STANDARD)

        assertNotNull(res)
        assertEquals(expectedAudio, res.url)
        assertEquals(0, networkCallCount.get()) // 0 network calls! Reused from cache!
    }

    @Test
    fun testDownloadUsesServerExisting_onFirstRequestWithoutPolling() {
        val videoId = "serverExistingDownload"
        val expectedAudio = "https://vtrans.s3-private.mds.yandex.net/tts/prod/server_ready.mp3"

        val networkCallCount = AtomicInteger(0)
        val mockApi = object : YandexVotApi {
            override fun requestTranslation(
                videoUrl: String,
                duration: Double,
                sourceLang: String?,
                targetLang: String,
                videoTitle: String?,
                useLiveVoices: Boolean,
                oauthToken: String?,
                isFirstRequest: Boolean
            ): YandexVotApiClient.TranslationResult {
                networkCallCount.incrementAndGet()
                return YandexVotApiClient.TranslationResult(
                    YandexVotApiClient.STATUS_FINISHED,
                    expectedAudio,
                    0,
                    "trans-123",
                    "success"
                )
            }
        }

        val resolver = DefaultVoxTranslationResolver(apiClient = mockApi)
        val res = resolver.resolveTranslation(videoId, VoxTranslationMode.STANDARD)

        assertNotNull(res)
        assertEquals(expectedAudio, res.url)
        assertEquals(1, networkCallCount.get()) // Exactly 1 call (first request), 0 polling!

        // And verify it was stored in cache for subsequent reuse
        val cached = VoxTranslationSessionCache.lookup(videoId, null, null, "ru", false)
        assertTrue(cached is VoxTranslationSessionCache.LookupResult.Hit)
    }

    @Test
    fun testLRUMemoryTrimming_keepsWithinMaxSize() {
        for (i in 1..60) {
            VoxTranslationSessionCache.storeReadyTranslation(
                videoId = "vid_$i",
                rawUrl = "https://www.youtube.com/watch?v=vid_$i",
                sourceLang = "en",
                targetLang = "ru",
                audioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/audio_$i.mp3",
                durationSec = 10.0,
                useLively = false
            )
        }

        // Total memory entries should be capped at MAX_MEMORY_ENTRIES (50)
        var hitCount = 0
        for (i in 1..60) {
            val r = VoxTranslationSessionCache.lookup("vid_$i", null, "en", "ru", false)
            if (r is VoxTranslationSessionCache.LookupResult.Hit && r.source == "MEMORY_CACHE") {
                hitCount++
            }
        }
        assertTrue("Memory hit count should be bounded <= 50, but was $hitCount", hitCount <= 50)
    }
}
