package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Section 34 & Phase B Verification Suite:
 * Video Translation Server-First Pipeline Architecture Contract.
 */
class VoxTranslationServerFirstSuiteTest {

    private lateinit var pipeline: VoxTranslationServerFirstPipeline

    // Test In-Memory Cache Provider
    private class MockLocalCache : VoxTranslationServerFirstPipeline.LocalCacheProvider {
        val storage = mutableMapOf<String, VoxTranslationSessionCache.TranslationRecord>()
        var lookupCount = 0
        var storeCount = 0

        override fun lookup(request: VoxTranslationServerFirstPipeline.TranslationRequest): VoxTranslationSessionCache.LookupResult {
            lookupCount++
            val record = storage[request.cacheKey]
            return if (record != null) {
                VoxTranslationSessionCache.LookupResult.Hit(record, "MOCK_MEMORY")
            } else {
                VoxTranslationSessionCache.LookupResult.Miss
            }
        }

        override fun store(
            request: VoxTranslationServerFirstPipeline.TranslationRequest,
            audioUrl: String,
            durationSec: Double
        ) {
            storeCount++
            storage[request.cacheKey] = VoxTranslationSessionCache.TranslationRecord(
                videoId = request.videoId,
                canonicalUrl = "https://www.youtube.com/watch?v=${request.videoId}",
                sourceLang = request.sourceLang,
                targetLang = request.targetLang,
                audioUrl = audioUrl,
                durationSec = durationSec,
                useLivelyVoice = request.useLiveVoices,
                createdAtMs = System.currentTimeMillis()
            )
        }
    }

    // Test Server Database Service
    private class MockServerDatabase : VoxTranslationServerFirstPipeline.ServerDatabaseService {
        var lookupCount = 0
        var createCount = 0
        var refreshCount = 0

        var nextLookupResponse: VoxTranslationServerFirstPipeline.ServerLookupResponse =
            VoxTranslationServerFirstPipeline.ServerLookupResponse.NotFound
        var nextCreateResponse = VoxTranslationServerFirstPipeline.ServerCreateResponse("job-new-123", "PROCESSING")
        var nextRefreshResponse = VoxTranslationServerFirstPipeline.ServerRefreshResponse(true, "https://storage.yandex.net/refreshed.mp3")

        override fun queryExistingTranslation(
            videoId: String,
            sourceLang: String,
            targetLang: String
        ): VoxTranslationServerFirstPipeline.ServerLookupResponse {
            lookupCount++
            return nextLookupResponse
        }

        override fun createTranslationJob(
            videoId: String,
            sourceLang: String,
            targetLang: String
        ): VoxTranslationServerFirstPipeline.ServerCreateResponse {
            createCount++
            return nextCreateResponse
        }

        override fun refreshSignedUrl(
            videoId: String,
            expiredAudioUrl: String
        ): VoxTranslationServerFirstPipeline.ServerRefreshResponse {
            refreshCount++
            return nextRefreshResponse
        }
    }

    private lateinit var localCache: MockLocalCache
    private lateinit var serverDb: MockServerDatabase

    @Before
    fun setUp() {
        pipeline = VoxTranslationServerFirstPipeline(maxRetryAttempts = 3, baseRetryDelayMs = 1000L)
        localCache = MockLocalCache()
        serverDb = MockServerDatabase()
    }

    // ---------------------------------------------------------------------
    // Scenario 1: L1 local memory cache hit -> start playback immediately without network lookup
    // ---------------------------------------------------------------------
    @Test
    fun test01_L1LocalCacheHitReturnsImmediatelyWithoutNetwork() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid_l1_hit",
            sourceLang = "en",
            targetLang = "ru"
        )
        localCache.store(req, "https://storage.yandex.net/audio_l1.mp3", 120.0)

        val result = pipeline.execute(req, localCache, serverDb)

        assertTrue(result is VoxTranslationServerFirstPipeline.PipelineResult.ImmediatePlayback)
        val playback = result as VoxTranslationServerFirstPipeline.PipelineResult.ImmediatePlayback
        assertEquals("https://storage.yandex.net/audio_l1.mp3", playback.audioUrl)
        assertEquals("L1_LOCAL_CACHE", playback.source)
        assertEquals(0, serverDb.lookupCount) // ZERO network queries
        assertEquals(0, serverDb.createCount) // ZERO new jobs
    }

    // ---------------------------------------------------------------------
    // Scenario 2: L2 service database lookup returns READY -> start playback immediately without new job creation
    // ---------------------------------------------------------------------
    @Test
    fun test02_L2ServerLookupReadyReturnsImmediatelyWithoutCreatingJob() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid_l2_ready",
            sourceLang = "en",
            targetLang = "ru"
        )
        serverDb.nextLookupResponse = VoxTranslationServerFirstPipeline.ServerLookupResponse.Ready(
            audioUrl = "https://storage.yandex.net/audio_server_ready.mp3",
            durationSec = 250.0,
            isExpired = false
        )

        val result = pipeline.execute(req, localCache, serverDb)

        assertTrue(result is VoxTranslationServerFirstPipeline.PipelineResult.ImmediatePlayback)
        val playback = result as VoxTranslationServerFirstPipeline.PipelineResult.ImmediatePlayback
        assertEquals("https://storage.yandex.net/audio_server_ready.mp3", playback.audioUrl)
        assertEquals("L2_SERVER_DB", playback.source)
        assertEquals(1, serverDb.lookupCount)
        assertEquals(0, serverDb.createCount) // NO job created
        assertEquals(1, localCache.storeCount) // Cached into L1 for subsequent requests
    }

    // ---------------------------------------------------------------------
    // Scenario 3: Concurrent requests for same video + lang -> attach to existing jobId (dedup / no duplicate job)
    // ---------------------------------------------------------------------
    @Test
    fun test03_ConcurrentRequestsAttachToExistingJobWithoutDuplicate() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid_concurrent",
            sourceLang = "en",
            targetLang = "ru"
        )
        serverDb.nextLookupResponse = VoxTranslationServerFirstPipeline.ServerLookupResponse.Processing(
            jobId = "job-active-999",
            remainingSec = 15,
            verifiedPercent = 45
        )

        val result = pipeline.execute(req, localCache, serverDb)

        assertTrue(result is VoxTranslationServerFirstPipeline.PipelineResult.AttachToJob)
        val attached = result as VoxTranslationServerFirstPipeline.PipelineResult.AttachToJob
        assertEquals("job-active-999", attached.jobId)
        assertEquals(15, attached.remainingSec)
        assertEquals(45, attached.verifiedProgressPercent)
        assertEquals(0, serverDb.createCount) // ZERO duplicate job creation
    }

    // ---------------------------------------------------------------------
    // Scenario 4: L2 returns NOT_FOUND -> atomic get-or-create initiates translation task
    // ---------------------------------------------------------------------
    @Test
    fun test04_L2NotFoundInitiatesAtomicNewJob() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid_fresh_miss",
            sourceLang = "en",
            targetLang = "ru"
        )
        serverDb.nextLookupResponse = VoxTranslationServerFirstPipeline.ServerLookupResponse.NotFound
        serverDb.nextCreateResponse = VoxTranslationServerFirstPipeline.ServerCreateResponse("job-fresh-777", "PROCESSING")

        val result = pipeline.execute(req, localCache, serverDb)

        assertTrue(result is VoxTranslationServerFirstPipeline.PipelineResult.InitiatedNewJob)
        val created = result as VoxTranslationServerFirstPipeline.PipelineResult.InitiatedNewJob
        assertEquals("job-fresh-777", created.jobId)
        assertEquals(1, serverDb.createCount)
    }

    // ---------------------------------------------------------------------
    // Scenario 5: Russian source audio (source == ru and target == ru) -> handled gracefully without redundant translation job
    // ---------------------------------------------------------------------
    @Test
    fun test05_RussianSourceAudioHandledGracefullyWithoutRedundantJob() {
        val reqRu = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid_russian",
            sourceLang = "ru",
            targetLang = "ru"
        )

        val result = pipeline.execute(reqRu, localCache, serverDb)

        assertTrue(result is VoxTranslationServerFirstPipeline.PipelineResult.SkippedSameLanguage)
        val skipped = result as VoxTranslationServerFirstPipeline.PipelineResult.SkippedSameLanguage
        assertTrue(skipped.reason.contains("русском языке"))
        assertEquals(0, localCache.lookupCount)
        assertEquals(0, serverDb.lookupCount)
        assertEquals(0, serverDb.createCount)
    }

    // ---------------------------------------------------------------------
    // Scenario 6: L2 returns PROCESSING -> client polls / attaches to existing job with real status
    // ---------------------------------------------------------------------
    @Test
    fun test06_L2ProcessingReturnsVerifiedRemainingSecAndProgress() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid_processing",
            sourceLang = "de",
            targetLang = "ru"
        )
        serverDb.nextLookupResponse = VoxTranslationServerFirstPipeline.ServerLookupResponse.Processing(
            jobId = "job-de-456",
            remainingSec = 8,
            verifiedPercent = 70
        )

        val result = pipeline.execute(req, localCache, serverDb)

        assertTrue(result is VoxTranslationServerFirstPipeline.PipelineResult.AttachToJob)
        val attached = result as VoxTranslationServerFirstPipeline.PipelineResult.AttachToJob
        assertEquals("job-de-456", attached.jobId)
        assertEquals(8, attached.remainingSec)
        assertEquals(70, attached.verifiedProgressPercent)
    }

    // ---------------------------------------------------------------------
    // Scenario 7: L2 returns FAILED -> clear error state and apply retry policy
    // ---------------------------------------------------------------------
    @Test
    fun test07_L2FailedAppliesBoundedRetryPolicy() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid_err",
            sourceLang = "es",
            targetLang = "ru"
        )
        serverDb.nextLookupResponse = VoxTranslationServerFirstPipeline.ServerLookupResponse.Failed("Yandex rate limited", retryable = true)

        // Attempt 0 -> schedules Retry 1 with 1000ms delay
        val res0 = pipeline.execute(req, localCache, serverDb, retryAttempt = 0)
        assertTrue(res0 is VoxTranslationServerFirstPipeline.PipelineResult.RetryScheduled)
        val retry0 = res0 as VoxTranslationServerFirstPipeline.PipelineResult.RetryScheduled
        assertEquals(1, retry0.attempt)
        assertEquals(1000L, retry0.delayMs)

        // Attempt 2 -> schedules Retry 3 with 3000ms delay
        val res2 = pipeline.execute(req, localCache, serverDb, retryAttempt = 2)
        assertTrue(res2 is VoxTranslationServerFirstPipeline.PipelineResult.RetryScheduled)
        val retry2 = res2 as VoxTranslationServerFirstPipeline.PipelineResult.RetryScheduled
        assertEquals(3, retry2.attempt)
        assertEquals(3000L, retry2.delayMs)

        // Attempt 3 (maxReached) -> returns Failed
        val resMax = pipeline.execute(req, localCache, serverDb, retryAttempt = 3)
        assertTrue(resMax is VoxTranslationServerFirstPipeline.PipelineResult.Failed)
    }

    // ---------------------------------------------------------------------
    // Scenario 8: L2 returns EXPIRED -> refresh signed URL / artifact
    // ---------------------------------------------------------------------
    @Test
    fun test08_L2ExpiredRefreshesSignedUrl() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid_expired",
            sourceLang = "en",
            targetLang = "ru"
        )
        serverDb.nextLookupResponse = VoxTranslationServerFirstPipeline.ServerLookupResponse.Ready(
            audioUrl = "https://storage.yandex.net/stale_expired.mp3",
            durationSec = 180.0,
            isExpired = true
        )
        serverDb.nextRefreshResponse = VoxTranslationServerFirstPipeline.ServerRefreshResponse(
            ok = true,
            refreshedAudioUrl = "https://storage.yandex.net/fresh_token_123.mp3"
        )

        val result = pipeline.execute(req, localCache, serverDb)

        assertTrue(result is VoxTranslationServerFirstPipeline.PipelineResult.ImmediatePlayback)
        val playback = result as VoxTranslationServerFirstPipeline.PipelineResult.ImmediatePlayback
        assertEquals("https://storage.yandex.net/fresh_token_123.mp3", playback.audioUrl)
        assertEquals("L2_SERVER_REFRESHED", playback.source)
        assertEquals(1, serverDb.refreshCount)
        assertEquals(1, localCache.storeCount)
    }

    // ---------------------------------------------------------------------
    // Scenario 9: No fake arbitrary percentages: stages displayed with real descriptive strings
    // ---------------------------------------------------------------------
    @Test
    fun test09_StagesUseDescriptiveRussianStringsWithoutFakePercentages() {
        val stages = VoxTranslationServerFirstPipeline.Stage.values()
        for (stage in stages) {
            assertTrue("Description for $stage must not be blank", stage.descriptionRu.isNotBlank())
            assertFalse("Description for $stage must NOT contain arbitrary percent signs", stage.descriptionRu.contains("%"))
        }

        assertEquals("Поиск готового перевода на сервере", VoxTranslationServerFirstPipeline.Stage.LOOKING_UP_SERVER.descriptionRu)
        assertEquals("Подключение к активному переводу", VoxTranslationServerFirstPipeline.Stage.ATTACHED_TO_EXISTING_JOB.descriptionRu)
        assertEquals("Перевод готов", VoxTranslationServerFirstPipeline.Stage.READY_FOR_PLAYBACK.descriptionRu)
        assertEquals("Видео уже на языке перевода", VoxTranslationServerFirstPipeline.Stage.SKIPPED_SAME_LANGUAGE.descriptionRu)
    }

    // ---------------------------------------------------------------------
    // Scenario 10: Cache key consistency (videoId + sourceLang + targetLang normalization)
    // ---------------------------------------------------------------------
    @Test
    fun test10_CacheKeyConsistencyWithVariousUrlFormats() {
        val req1 = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "dQw4w9WgXcQ",
            rawUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
            sourceLang = "EN",
            targetLang = "RU",
            useLiveVoices = false
        )
        val req2 = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "dQw4w9WgXcQ",
            rawUrl = "https://youtu.be/dQw4w9WgXcQ?si=abcdef",
            sourceLang = "en-US",
            targetLang = "ru",
            useLiveVoices = false
        )

        assertEquals(req1.cacheKey, req2.cacheKey)

        // Live voice toggle alters cache key
        val reqLive = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "dQw4w9WgXcQ",
            rawUrl = "dQw4w9WgXcQ",
            sourceLang = "en",
            targetLang = "ru",
            useLiveVoices = true
        )
        assertNotEquals(req1.cacheKey, reqLive.cacheKey)
    }
}
