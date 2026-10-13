package com.liskovsoft.smartyoutubetv2.common.vox.translation

import android.content.Context
import android.content.SharedPreferences
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VoxPatch46SuiteTest {

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
    fun testColdProcessCacheSourceSemantics_firstIsPersistedSecondIsMemory() {
        val videoId = "coldSemantics1"
        val expectedAudio = "https://vtrans.s3-private.mds.yandex.net/tts/prod/cold1.mp3"

        // 1. Store translation
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = videoId,
            rawUrl = "https://www.youtube.com/watch?v=$videoId",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = expectedAudio,
            durationSec = 120.0,
            useLively = false
        )

        // Immediate lookup in current process: MEMORY_CACHE
        val res1 = VoxTranslationSessionCache.lookup(videoId, null, "en", "ru", false)
        assertTrue(res1 is VoxTranslationSessionCache.LookupResult.Hit)
        assertEquals("MEMORY_CACHE", (res1 as VoxTranslationSessionCache.LookupResult.Hit).source)

        // 2. Clear memory cache only (simulating app process death and cold restart)
        VoxTranslationSessionCache.clearMemory()

        // 3. First lookup in newly started process: MUST be PERSISTED_CACHE
        val res2 = VoxTranslationSessionCache.lookup(videoId, null, "en", "ru", false)
        assertTrue(res2 is VoxTranslationSessionCache.LookupResult.Hit)
        assertEquals("PERSISTED_CACHE", (res2 as VoxTranslationSessionCache.LookupResult.Hit).source)

        // 4. Second lookup in the same process: MUST be MEMORY_CACHE (warmed!)
        val res3 = VoxTranslationSessionCache.lookup(videoId, null, "en", "ru", false)
        assertTrue(res3 is VoxTranslationSessionCache.LookupResult.Hit)
        assertEquals("MEMORY_CACHE", (res3 as VoxTranslationSessionCache.LookupResult.Hit).source)
    }

    @Test
    fun testTrueLruEvictionByAccessTime_accessedItemSurvives() {
        // Fill memory cache up to 50 entries
        for (i in 0 until 50) {
            VoxTranslationSessionCache.storeReadyTranslation(
                videoId = "vid_$i",
                rawUrl = "https://www.youtube.com/watch?v=vid_$i",
                sourceLang = "en",
                targetLang = "ru",
                audioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/track_$i.mp3",
                durationSec = 100.0,
                useLively = false
            )
            Thread.sleep(2)
        }

        // Now touch entry 0 (least recently created) so its lastAccessedMs becomes the most recent!
        Thread.sleep(10)
        val hit0 = VoxTranslationSessionCache.lookup("vid_0", null, "en", "ru", false)
        assertTrue(hit0 is VoxTranslationSessionCache.LookupResult.Hit)

        // Insert entry 51
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "vid_51",
            rawUrl = "https://www.youtube.com/watch?v=vid_51",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/track_51.mp3",
            durationSec = 100.0,
            useLively = false
        )

        // Verify entry 0 was NOT evicted because it was accessed!
        val check0 = VoxTranslationSessionCache.lookup("vid_0", null, "en", "ru", false)
        assertTrue(check0 is VoxTranslationSessionCache.LookupResult.Hit)

        // Verify entry 1 (which was never accessed after creation) WAS evicted from memory cache!
        // To check memory eviction without persisted fallback:
        val key1 = VoxTranslationReusePolicy.buildCacheKey("vid_1", "en", "ru", false)
        // Memory cache size must remain exactly bounded at 50
        val memHits = (0..51).count { id ->
            val r = VoxTranslationSessionCache.lookup("vid_$id", null, "en", "ru", false)
            r is VoxTranslationSessionCache.LookupResult.Hit && r.source == "MEMORY_CACHE"
        }
        assertEquals(50, memHits)
    }

    @Test
    fun testUrlExplicitExpiryTimestamp_rejectsExpiredUrl() {
        val nowSec = System.currentTimeMillis() / 1000L
        val expiredUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/sample.mp3?Expires=${nowSec - 100}&token=abc"

        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "expiredByUrl",
            rawUrl = "https://www.youtube.com/watch?v=expiredByUrl",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = expiredUrl,
            durationSec = 60.0,
            useLively = false
        )

        val res = VoxTranslationSessionCache.lookup("expiredByUrl", null, "en", "ru", false)
        // Because the URL's signed expiry timestamp has passed, lookup MUST return Miss!
        assertTrue(res is VoxTranslationSessionCache.LookupResult.Miss)
    }

    @Test
    fun testUrlExplicitExpiryTimestamp_acceptsFutureUrl() {
        val nowSec = System.currentTimeMillis() / 1000L
        val futureUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/sample.mp3?Expires=${nowSec + 3600}&token=abc"

        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "validByUrl",
            rawUrl = "https://www.youtube.com/watch?v=validByUrl",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = futureUrl,
            durationSec = 60.0,
            useLively = false
        )

        val res = VoxTranslationSessionCache.lookup("validByUrl", null, "en", "ru", false)
        assertTrue(res is VoxTranslationSessionCache.LookupResult.Hit)
    }

    @Test
    fun testPersistedLimitsEnforcedAt100Entries() {
        for (i in 0 until 120) {
            VoxTranslationSessionCache.storeReadyTranslation(
                videoId = "batch_$i",
                rawUrl = "https://www.youtube.com/watch?v=batch_$i",
                sourceLang = "en",
                targetLang = "ru",
                audioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/batch_$i.mp3",
                durationSec = 50.0,
                useLively = false
            )
        }

        // Count persisted entries starting with rec_
        val persistedCount = prefs.all.count { it.key.startsWith("rec_") }
        assertTrue("Expected persisted count <= 100, was: $persistedCount", persistedCount <= 100)
    }
}
