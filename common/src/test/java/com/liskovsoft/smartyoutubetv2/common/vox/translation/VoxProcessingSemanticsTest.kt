package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApi
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotOrchestrator
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Hardening Test Suite: VOT PROCESSING Semantics & Error Resilience.
 *
 * Covers:
 * - attach/poll existing job without creating duplicate jobs
 * - restart/rejoin
 * - progress heartbeat
 * - stalled job detection
 * - error semantics: network/5xx/auth/timeout != NOT_FOUND
 * - invalid READY artifact refreshes metadata
 */
class VoxProcessingSemanticsTest {

    private lateinit var pipeline: VoxTranslationServerFirstPipeline

    private class ScheduledTaskEntry(
        private val command: Runnable,
        val delay: Long,
        val unit: TimeUnit
    ) : ScheduledFuture<Any?> {
        private var cancelled = false
        private var done = false

        fun run() {
            if (!cancelled) {
                done = true
                command.run()
            }
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelled = true
            return true
        }

        override fun isCancelled(): Boolean = cancelled
        override fun isDone(): Boolean = done
        override fun getDelay(targetUnit: TimeUnit): Long = targetUnit.convert(delay, unit)
        override fun compareTo(other: Delayed?): Int = 0
        override fun get(): Any? = null
        override fun get(timeout: Long, unit: TimeUnit): Any? = null
    }

    private class TestScheduledExecutor : ScheduledExecutorService {
        val scheduledTasks = mutableListOf<ScheduledTaskEntry>()

        fun runAllScheduled() {
            val copy = ArrayList(scheduledTasks)
            scheduledTasks.clear()
            for (entry in copy) {
                entry.run()
            }
        }

        override fun execute(command: Runnable) {
            command.run()
        }

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            val entry = ScheduledTaskEntry(command, delay, unit)
            scheduledTasks.add(entry)
            return entry
        }

        override fun <V : Any?> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
            throw UnsupportedOperationException()
        }

        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> {
            throw UnsupportedOperationException()
        }

        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            throw UnsupportedOperationException()
        }

        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<java.util.concurrent.Future<T>> = throw UnsupportedOperationException()
        override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): MutableList<java.util.concurrent.Future<T>> = throw UnsupportedOperationException()
        override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T = throw UnsupportedOperationException()
        override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): T = throw UnsupportedOperationException()
        override fun <T : Any?> submit(task: Callable<T>): java.util.concurrent.Future<T> = throw UnsupportedOperationException()
        override fun <T : Any?> submit(task: Runnable, result: T): java.util.concurrent.Future<T> = throw UnsupportedOperationException()
        override fun submit(task: Runnable): java.util.concurrent.Future<*> {
            task.run()
            return ScheduledTaskEntry(task, 0, TimeUnit.MILLISECONDS)
        }
    }

    private class MockLocalCache : VoxTranslationServerFirstPipeline.LocalCacheProvider {
        val storage = mutableMapOf<String, VoxTranslationSessionCache.TranslationRecord>()
        var pendingJob: VoxTranslationSessionCache.ActiveJob? = null
        var lookupCount = 0
        var storeCount = 0

        override fun lookup(request: VoxTranslationServerFirstPipeline.TranslationRequest): VoxTranslationSessionCache.LookupResult {
            lookupCount++
            val job = pendingJob
            if (job != null && job.key == request.cacheKey) {
                return VoxTranslationSessionCache.LookupResult.PendingJob(job)
            }
            val record = storage[request.cacheKey]
            return if (record != null) {
                VoxTranslationSessionCache.LookupResult.Hit(record, "MOCK_CACHE")
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

    private class MockServerDatabase : VoxTranslationServerFirstPipeline.ServerDatabaseService {
        var lookupCount = 0
        var createCount = 0
        var refreshCount = 0

        var lookupResponse: VoxTranslationServerFirstPipeline.ServerLookupResponse =
            VoxTranslationServerFirstPipeline.ServerLookupResponse.NotFound
        var createResponse = VoxTranslationServerFirstPipeline.ServerCreateResponse("job-fresh-999", "PROCESSING")
        var refreshResponse = VoxTranslationServerFirstPipeline.ServerRefreshResponse(true, "https://storage.yandex.net/refreshed-valid.mp3")

        override fun queryExistingTranslation(
            videoId: String,
            sourceLang: String,
            targetLang: String
        ): VoxTranslationServerFirstPipeline.ServerLookupResponse {
            lookupCount++
            return lookupResponse
        }

        override fun createTranslationJob(
            videoId: String,
            sourceLang: String,
            targetLang: String
        ): VoxTranslationServerFirstPipeline.ServerCreateResponse {
            createCount++
            return createResponse
        }

        override fun refreshSignedUrl(
            videoId: String,
            expiredAudioUrl: String
        ): VoxTranslationServerFirstPipeline.ServerRefreshResponse {
            refreshCount++
            return refreshResponse
        }
    }

    private lateinit var localCache: MockLocalCache
    private lateinit var serverDb: MockServerDatabase

    @Before
    fun setUp() {
        pipeline = VoxTranslationServerFirstPipeline(maxRetryAttempts = 2, baseRetryDelayMs = 100L)
        localCache = MockLocalCache()
        serverDb = MockServerDatabase()
    }

    @Test
    fun test01_processingAttachesExistingJob() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid-proc-1",
            rawUrl = "https://www.youtube.com/watch?v=vid-proc-1",
            sourceLang = "en",
            targetLang = "ru"
        )
        serverDb.lookupResponse = VoxTranslationServerFirstPipeline.ServerLookupResponse.Processing(
            jobId = "existing-job-12345",
            remainingSec = 25,
            verifiedPercent = 40
        )

        val result = pipeline.execute(req, localCache, serverDb)

        assertTrue("Must yield AttachToJob", result is VoxTranslationServerFirstPipeline.PipelineResult.AttachToJob)
        val attach = result as VoxTranslationServerFirstPipeline.PipelineResult.AttachToJob
        assertEquals("existing-job-12345", attach.jobId)
        assertEquals(25, attach.remainingSec)
        assertEquals(40, attach.verifiedProgressPercent)
        assertEquals("PROCESSING", attach.status)
        assertEquals(0, serverDb.createCount)
    }

    @Test
    fun test02_processingDoesNotCreateJob() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid-proc-2",
            rawUrl = "https://www.youtube.com/watch?v=vid-proc-2",
            sourceLang = "en",
            targetLang = "ru"
        )
        serverDb.lookupResponse = VoxTranslationServerFirstPipeline.ServerLookupResponse.Processing(
            jobId = "existing-job-777",
            remainingSec = 10
        )

        pipeline.execute(req, localCache, serverDb)

        assertEquals("Server database lookup must be called once", 1, serverDb.lookupCount)
        assertEquals("createTranslationJob MUST NOT be called when job is PROCESSING", 0, serverDb.createCount)
    }

    @Test
    fun test03_processingRestartRejoins() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid-rejoin",
            rawUrl = "https://www.youtube.com/watch?v=vid-rejoin",
            sourceLang = "en",
            targetLang = "ru"
        )
        localCache.pendingJob = VoxTranslationSessionCache.ActiveJob(
            key = req.cacheKey,
            videoId = req.videoId,
            status = VoxTranslationSessionCache.JobStatus.PENDING,
            startedAtMs = System.currentTimeMillis() - 5000L
        )

        val result = pipeline.execute(req, localCache, serverDb)

        assertTrue("Rejoin must attach to local pending job", result is VoxTranslationServerFirstPipeline.PipelineResult.AttachToJob)
        val attach = result as VoxTranslationServerFirstPipeline.PipelineResult.AttachToJob
        assertEquals(req.cacheKey, attach.jobId)
        assertEquals("PROCESSING", attach.status)
        assertEquals("Server lookup skipped on local pending job hit", 0, serverDb.lookupCount)
        assertEquals("No new job created on rejoin", 0, serverDb.createCount)
    }

    @Test
    fun test04_processingEventuallyReady() {
        val scheduler = TestScheduledExecutor()
        val recordedStates = mutableListOf<YandexVotState>()

        var pollCount = 0
        val mockApi = YandexVotApi { _, _, _, _, _, _, _, _ ->
            pollCount++
            if (pollCount == 1) {
                YandexVotApiClient.TranslationResult(
                    YandexVotApiClient.STATUS_WAITING,
                    null,
                    15,
                    "trans-poll-1",
                    "Processing"
                )
            } else {
                YandexVotApiClient.TranslationResult(
                    YandexVotApiClient.STATUS_FINISHED,
                    "https://storage.yandex.net/ready-audio.mp3",
                    0,
                    "trans-poll-1",
                    "Done"
                )
            }
        }

        val orchestrator = YandexVotOrchestrator(mockApi, scheduler)
        orchestrator.setListener { state ->
            recordedStates.add(state)
        }

        val params = YandexVotOrchestrator.RequestParams(
            "vid-eventual",
            "https://www.youtube.com/watch?v=vid-eventual",
            120.0,
            "en",
            "ru",
            "Title",
            false,
            null
        )

        orchestrator.attachToExistingJob(params, "trans-poll-1", 15)

        // Initial attach produces waiting state and schedules poll
        assertEquals(1, scheduler.scheduledTasks.size)
        // Run first scheduled poll -> WAITING, schedules next poll
        scheduler.runAllScheduled()
        assertEquals(1, scheduler.scheduledTasks.size)
        // Run second scheduled poll -> FINISHED -> READY!
        scheduler.runAllScheduled()

        val readyState = orchestrator.currentState
        assertEquals("Orchestrator must reach READY", YandexVotState.Status.READY, readyState.status)
        assertEquals("https://storage.yandex.net/ready-audio.mp3", readyState.audioUrl)
    }

    @Test
    fun test05_processingWithHeartbeatNotTimedOut() {
        val scheduler = TestScheduledExecutor()
        var currentRemaining = 30

        val mockApi = YandexVotApi { _, _, _, _, _, _, _, _ ->
            currentRemaining = (currentRemaining - 5).coerceAtLeast(1)
            YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_WAITING,
                null,
                currentRemaining,
                "trans-heartbeat",
                "Working"
            )
        }

        val orchestrator = YandexVotOrchestrator(mockApi, scheduler)
        val params = YandexVotOrchestrator.RequestParams(
            "vid-hb",
            "https://www.youtube.com/watch?v=vid-hb",
            60.0,
            "en",
            "ru",
            "Title",
            false,
            null
        )

        orchestrator.attachToExistingJob(params, "trans-heartbeat", 30)

        // Poll multiple times with advancing heartbeat (countdown decreases)
        for (i in 1..4) {
            scheduler.runAllScheduled()
            assertEquals("Consecutive stalled polls must reset to 0 on heartbeat progress", 0, orchestrator.consecutiveStalledPolls)
            assertFalse("Job must not be in error when progress is advancing", orchestrator.currentState.isError)
        }
    }

    @Test
    fun test06_stalledProcessingBecomesJobStalled() {
        val scheduler = TestScheduledExecutor()
        val errorStates = mutableListOf<YandexVotState>()

        // Mock API returns identical remaining time (20) on every poll without progress
        val mockApi = YandexVotApi { _, _, _, _, _, _, _, _ ->
            YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_WAITING,
                null,
                20,
                "trans-stalled",
                "Stalled"
            )
        }

        val orchestrator = YandexVotOrchestrator(mockApi, scheduler)
        orchestrator.setListener { state ->
            if (state.status == YandexVotState.Status.ERROR) {
                errorStates.add(state)
            }
        }

        val params = YandexVotOrchestrator.RequestParams(
            "vid-stalled",
            "https://www.youtube.com/watch?v=vid-stalled",
            60.0,
            "en",
            "ru",
            "Title",
            false,
            null
        )

        orchestrator.attachToExistingJob(params, "trans-stalled", 20)

        // Run polls up to threshold
        for (i in 0..YandexVotOrchestrator.MAX_CONSECUTIVE_STALLED_POLLS + 2) {
            scheduler.runAllScheduled()
            if (errorStates.any { it.errorCategory == "job_stalled" }) break
        }

        assertTrue("Stalled processing must be detected and transition to job_stalled error",
            errorStates.any { it.errorCategory == "job_stalled" }
        )
        assertEquals("job_stalled", orchestrator.currentState.errorCategory)
    }

    @Test
    fun test07_lookupTimeoutDoesNotCreateJob() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid-timeout",
            rawUrl = "https://www.youtube.com/watch?v=vid-timeout",
            sourceLang = "en",
            targetLang = "ru"
        )
        serverDb.lookupResponse = VoxTranslationServerFirstPipeline.ServerLookupResponse.timeout("Socket timeout connecting to server")

        val result = pipeline.execute(req, localCache, serverDb)

        assertTrue("Timeout must schedule retry, not create job", result is VoxTranslationServerFirstPipeline.PipelineResult.RetryScheduled)
        assertEquals("createTranslationJob MUST NOT be called on lookup timeout", 0, serverDb.createCount)
    }

    @Test
    fun test08_server500DoesNotCreateJob() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid-500",
            rawUrl = "https://www.youtube.com/watch?v=vid-500",
            sourceLang = "en",
            targetLang = "ru"
        )
        serverDb.lookupResponse = VoxTranslationServerFirstPipeline.ServerLookupResponse.fromHttpStatus(502, "Bad Gateway")

        val result = pipeline.execute(req, localCache, serverDb)

        assertTrue("Server 5xx error must schedule retry, not create job", result is VoxTranslationServerFirstPipeline.PipelineResult.RetryScheduled)
        assertEquals("createTranslationJob MUST NOT be called on server 5xx error", 0, serverDb.createCount)
    }

    @Test
    fun test09_authErrorDoesNotCreateJob() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid-auth",
            rawUrl = "https://www.youtube.com/watch?v=vid-auth",
            sourceLang = "en",
            targetLang = "ru"
        )
        serverDb.lookupResponse = VoxTranslationServerFirstPipeline.ServerLookupResponse.fromHttpStatus(401, "Token expired")

        val result = pipeline.execute(req, localCache, serverDb)

        assertTrue("Auth error must fail gracefully without retrying or creating job", result is VoxTranslationServerFirstPipeline.PipelineResult.Failed)
        val failed = result as VoxTranslationServerFirstPipeline.PipelineResult.Failed
        assertFalse("Auth error is not retryable", failed.canRetry)
        assertEquals("createTranslationJob MUST NOT be called on auth failure", 0, serverDb.createCount)
    }

    @Test
    fun test10_invalidReadyArtifactRefreshesMetadata() {
        val req = VoxTranslationServerFirstPipeline.TranslationRequest(
            videoId = "vid-invalid-ready",
            rawUrl = "https://www.youtube.com/watch?v=vid-invalid-ready",
            sourceLang = "en",
            targetLang = "ru"
        )
        serverDb.lookupResponse = VoxTranslationServerFirstPipeline.ServerLookupResponse.Ready(
            audioUrl = "not-a-valid-http-url",
            durationSec = 60.0,
            isExpired = false
        )
        serverDb.refreshResponse = VoxTranslationServerFirstPipeline.ServerRefreshResponse(
            ok = true,
            refreshedAudioUrl = "https://storage.yandex.net/refreshed-valid.mp3"
        )

        val result = pipeline.execute(req, localCache, serverDb)

        assertTrue("Must yield ImmediatePlayback with refreshed audio URL", result is VoxTranslationServerFirstPipeline.PipelineResult.ImmediatePlayback)
        val playback = result as VoxTranslationServerFirstPipeline.PipelineResult.ImmediatePlayback
        assertEquals("https://storage.yandex.net/refreshed-valid.mp3", playback.audioUrl)
        assertEquals("L2_SERVER_REFRESHED", playback.source)
        assertEquals("Must invoke refreshSignedUrl to replace invalid URL", 1, serverDb.refreshCount)
        assertEquals("Must not create new job when refresh succeeds", 0, serverDb.createCount)
    }
}
