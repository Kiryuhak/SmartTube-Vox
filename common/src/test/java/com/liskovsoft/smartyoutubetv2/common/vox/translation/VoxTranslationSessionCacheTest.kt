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
}
