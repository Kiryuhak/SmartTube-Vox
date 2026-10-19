package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoxTranslationSessionCacheTest {

    @Before
    fun setUp() {
        VoxTranslationSessionCache.clear()
    }

    @Test
    fun testLookupEmptyReturnsMiss() {
        val result = VoxTranslationSessionCache.lookup(
            videoId = "dQw4w9WgXcQ",
            rawUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            sourceLang = "en",
            targetLang = "ru",
            useLively = false
        )
        assertTrue(result is VoxTranslationSessionCache.LookupResult.Miss)
    }

    @Test
    fun testMemoryCacheHit() {
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "testVideo123",
            rawUrl = "https://youtube.com/watch?v=testVideo123",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = "https://vot.example.com/audio/testVideo123.mp3",
            durationSec = 120.5,
            useLively = false
        )

        val result = VoxTranslationSessionCache.lookup(
            videoId = "testVideo123",
            rawUrl = "https://youtube.com/watch?v=testVideo123",
            sourceLang = "en",
            targetLang = "ru",
            useLively = false
        )

        assertTrue(result is VoxTranslationSessionCache.LookupResult.Hit)
        val hit = result as VoxTranslationSessionCache.LookupResult.Hit
        assertEquals("MEMORY_CACHE", hit.source)
        assertEquals("https://vot.example.com/audio/testVideo123.mp3", hit.record.audioUrl)
        assertEquals("testVideo123", hit.record.videoId)
    }

    @Test
    fun testActivePendingJobRegistry() {
        val job = VoxTranslationSessionCache.registerPendingJob(
            videoId = "pendingVideo456",
            rawUrl = "https://youtube.com/watch?v=pendingVideo456",
            sourceLang = "uk",
            targetLang = "ru",
            useLively = true
        )
        assertNotNull(job)
        assertEquals(VoxTranslationSessionCache.JobStatus.PENDING, job.status)

        val lookupResult = VoxTranslationSessionCache.lookup(
            videoId = "pendingVideo456",
            rawUrl = "https://youtube.com/watch?v=pendingVideo456",
            sourceLang = "uk",
            targetLang = "ru",
            useLively = true
        )
        assertTrue(lookupResult is VoxTranslationSessionCache.LookupResult.PendingJob)

        // Now store ready translation -> should transition to ready
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "pendingVideo456",
            rawUrl = "https://youtube.com/watch?v=pendingVideo456",
            sourceLang = "uk",
            targetLang = "ru",
            audioUrl = "https://vot.example.com/audio/pendingVideo456.mp3",
            durationSec = 60.0,
            useLively = true
        )

        val afterReadyResult = VoxTranslationSessionCache.lookup(
            videoId = "pendingVideo456",
            rawUrl = "https://youtube.com/watch?v=pendingVideo456",
            sourceLang = "uk",
            targetLang = "ru",
            useLively = true
        )
        assertTrue(afterReadyResult is VoxTranslationSessionCache.LookupResult.Hit)
    }

    @Test
    fun testFallbackKeyMatchWhenSourceLangEmptyOrAuto() {
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "videoFallback789",
            rawUrl = "https://youtube.com/watch?v=videoFallback789",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = "https://vot.example.com/audio/videoFallback789.mp3",
            durationSec = 200.0,
            useLively = false
        )

        // Lookup with empty sourceLang should hit fallback
        val lookupEmpty = VoxTranslationSessionCache.lookup(
            videoId = "videoFallback789",
            rawUrl = "https://youtube.com/watch?v=videoFallback789",
            sourceLang = "",
            targetLang = "ru",
            useLively = false
        )
        assertTrue(lookupEmpty is VoxTranslationSessionCache.LookupResult.Hit)

        // Lookup with 'auto' sourceLang should also hit
        val lookupAuto = VoxTranslationSessionCache.lookup(
            videoId = "videoFallback789",
            rawUrl = "https://youtube.com/watch?v=videoFallback789",
            sourceLang = "auto",
            targetLang = "ru",
            useLively = false
        )
        assertTrue(lookupAuto is VoxTranslationSessionCache.LookupResult.Hit)
    }

    @Test
    fun testInvalidationRemovesRecordFromCache() {
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "videoToInvalidate",
            rawUrl = "https://youtube.com/watch?v=videoToInvalidate",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = "https://vot.example.com/audio/invalid.mp3",
            durationSec = 150.0,
            useLively = false
        )

        val before = VoxTranslationSessionCache.lookup(
            videoId = "videoToInvalidate",
            rawUrl = "https://youtube.com/watch?v=videoToInvalidate",
            sourceLang = "en",
            targetLang = "ru",
            useLively = false
        )
        assertTrue(before is VoxTranslationSessionCache.LookupResult.Hit)

        // Invalidate
        VoxTranslationSessionCache.invalidate("videoToInvalidate", "https://youtube.com/watch?v=videoToInvalidate")

        val after = VoxTranslationSessionCache.lookup(
            videoId = "videoToInvalidate",
            rawUrl = "https://youtube.com/watch?v=videoToInvalidate",
            sourceLang = "en",
            targetLang = "ru",
            useLively = false
        )
        assertTrue(after is VoxTranslationSessionCache.LookupResult.Miss)
    }

    @Test
    fun testExpiredAmzSigV4UrlReturnsMiss() {
        // Expired URL from earlier date/time
        val expiredAudioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/test.mp3?X-Amz-Date=20200101T000000Z&X-Amz-Expires=3600"
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "videoExpiredSigV4",
            rawUrl = "https://youtube.com/watch?v=videoExpiredSigV4",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = expiredAudioUrl,
            durationSec = 100.0,
            useLively = false
        )

        val result = VoxTranslationSessionCache.lookup(
            videoId = "videoExpiredSigV4",
            rawUrl = "https://youtube.com/watch?v=videoExpiredSigV4",
            sourceLang = "en",
            targetLang = "ru",
            useLively = false
        )
        assertTrue(result is VoxTranslationSessionCache.LookupResult.Miss)
    }

    @Test
    fun testValidAmzSigV4UrlReturnsHit() {
        val nowUtc = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(System.currentTimeMillis()))
        val validAudioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/test.mp3?X-Amz-Date=$nowUtc&X-Amz-Expires=7200"
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "videoValidSigV4",
            rawUrl = "https://youtube.com/watch?v=videoValidSigV4",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = validAudioUrl,
            durationSec = 100.0,
            useLively = false
        )

        val result = VoxTranslationSessionCache.lookup(
            videoId = "videoValidSigV4",
            rawUrl = "https://youtube.com/watch?v=videoValidSigV4",
            sourceLang = "en",
            targetLang = "ru",
            useLively = false
        )
        assertTrue(result is VoxTranslationSessionCache.LookupResult.Hit)
    }
}
