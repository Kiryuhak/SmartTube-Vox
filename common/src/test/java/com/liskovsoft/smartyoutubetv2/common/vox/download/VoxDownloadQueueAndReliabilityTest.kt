package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class VoxDownloadQueueAndReliabilityTest {

    private lateinit var context: Context
    private lateinit var storage: VoxDownloadStorage
    private lateinit var repository: VoxDownloadRepository
    private lateinit var coordinator: VoxDownloadCoordinator
    private lateinit var executor: java.util.concurrent.ExecutorService
    private val recordedRanges = mutableListOf<String?>()

    private var gateJobA: CountDownLatch? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = VoxDownloadStorage(context)
        storage.baseDir.deleteRecursively()
        repository = VoxDownloadRepository(storage)
        recordedRanges.clear()
        gateJobA = null

        val mockStreamResolver = object : VoxStreamResolver {
            override fun resolveVideoStream(videoId: String, qualityPreference: VoxQualityPreference): VoxResolvedStream {
                return VoxResolvedStream(
                    url = "https://rr1---sn-test.googlevideo.com/videoplayback?v=$videoId",
                    itag = 137,
                    mimeType = "video/mp4",
                    codec = "avc1",
                    contentLength = 200L
                )
            }

            override fun resolveOriginalAudio(videoId: String): VoxResolvedStream {
                return VoxResolvedStream(
                    url = "https://rr1---sn-test.googlevideo.com/audioplayback?v=$videoId",
                    itag = 140,
                    mimeType = "audio/mp4",
                    codec = "mp4a",
                    contentLength = 100L
                )
            }
        }

        val mockTranslationResolver = object : VoxTranslationResolver {
            override fun resolveTranslation(
                videoId: String,
                mode: VoxTranslationMode,
                isCancelled: () -> Boolean,
                onWaitingProgress: ((remainingSeconds: Int) -> Unit)?
            ): VoxResolvedTranslation {
                return VoxResolvedTranslation(
                    url = "https://vtrans.yandex.net/audio/trans_$videoId.mp3",
                    format = "audio/mp3"
                )
            }
        }

        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url().toString().contains("vid-A")) {
                    gateJobA?.await(3, TimeUnit.SECONDS)
                }
                val rangeHeader = request.header("Range")
                synchronized(recordedRanges) {
                    recordedRanges.add(rangeHeader)
                }

                val offset = if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val parts = rangeHeader.removePrefix("bytes=").split("-")
                    parts[0].toLongOrNull() ?: 0L
                } else 0L

                val totalSize = 200L
                val remaining = (totalSize - offset).coerceAtLeast(0L).toInt()
                val sampleData = ByteArray(remaining) { 0x33 }

                val builder = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .message("OK")

                if (offset > 0) {
                    builder.code(206)
                        .header("Content-Range", "bytes $offset-${totalSize - 1}/$totalSize")
                } else {
                    builder.code(200)
                }

                builder.body(ResponseBody.create(MediaType.parse("application/octet-stream"), sampleData))
                    .build()
            }.build()

        val mockDownloader = VoxSegmentDownloader(httpClient = fakeClient, storage = storage)

        executor = Executors.newFixedThreadPool(4)
        coordinator = VoxDownloadCoordinator(
            storage = storage,
            repository = repository,
            streamResolver = mockStreamResolver,
            translationResolver = mockTranslationResolver,
            downloader = mockDownloader,
            executor = executor
        )
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        storage.baseDir.deleteRecursively()
    }

    @Test
    fun testQueueFifoOrderAndSingleActiveJob() {
        val job1Started = CountDownLatch(1)
        val job1Done = CountDownLatch(1)

        coordinator.addListener("job-1", object : VoxDownloadListener {
            override fun onStateChanged(progress: VoxDownloadProgress) {
                if (progress.state == VoxDownloadState.DOWNLOADING_MEDIA) {
                    job1Started.countDown()
                } else if (progress.state == VoxDownloadState.READY_FOR_MUX || progress.state == VoxDownloadState.COMPLETED) {
                    job1Done.countDown()
                }
            }
            override fun onProgressUpdated(progress: VoxDownloadProgress) {}
            override fun onError(downloadId: String, errorCode: VoxDownloadErrorCode, message: String) {}
        })

        // Start job 1
        coordinator.startDownload(VoxDownloadRequest(downloadId = "job-1", videoId = "vid-1", videoTitle = "Title 1"))
        // Immediately start job 2 and job 3
        coordinator.startDownload(VoxDownloadRequest(downloadId = "job-2", videoId = "vid-2", videoTitle = "Title 2"))
        coordinator.startDownload(VoxDownloadRequest(downloadId = "job-3", videoId = "vid-3", videoTitle = "Title 3"))

        // job-2 and job-3 must be QUEUED initially
        val job2 = coordinator.getJob("job-2")
        val job3 = coordinator.getJob("job-3")
        assertNotNull(job2)
        assertNotNull(job3)
        assertEquals(VoxDownloadState.QUEUED, job2?.state)
        assertEquals(VoxDownloadState.QUEUED, job3?.state)

        // Queue length should reflect 2 pending jobs
        assertEquals(2, coordinator.getQueuedCount())

        // Wait for job 1 to start
        assertTrue(job1Started.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun testPauseActiveJobPromotesNextQueuedJob() {
        val gate = CountDownLatch(1)
        gateJobA = gate
        val jobAStarted = CountDownLatch(1)
        coordinator.addListener("job-A", object : VoxDownloadListener {
            override fun onStateChanged(progress: VoxDownloadProgress) {
                if (progress.state == VoxDownloadState.DOWNLOADING_VIDEO ||
                    progress.state == VoxDownloadState.DOWNLOADING_MEDIA) {
                    jobAStarted.countDown()
                }
            }
            override fun onProgressUpdated(progress: VoxDownloadProgress) {}
            override fun onError(downloadId: String, errorCode: VoxDownloadErrorCode, message: String) {}
        })

        try {
            val jobBStateLatch = CountDownLatch(1)
            coordinator.addListener("job-B", object : VoxDownloadListener {
                override fun onStateChanged(progress: VoxDownloadProgress) {
                    if (progress.state != VoxDownloadState.QUEUED) {
                        jobBStateLatch.countDown()
                    }
                }
                override fun onProgressUpdated(progress: VoxDownloadProgress) {}
                override fun onError(downloadId: String, errorCode: VoxDownloadErrorCode, message: String) {
                    jobBStateLatch.countDown()
                }
            })

            coordinator.startDownload(VoxDownloadRequest(downloadId = "job-A", videoId = "vid-A", videoTitle = "Title A"))
            coordinator.startDownload(VoxDownloadRequest(downloadId = "job-B", videoId = "vid-B", videoTitle = "Title B"))

            val jobB = coordinator.getJob("job-B")
            assertNotNull(jobB)
            assertEquals(VoxDownloadState.QUEUED, jobB?.state)

            // Wait until Job A is actually downloading and blocked by gate
            assertTrue(jobAStarted.await(3, TimeUnit.SECONDS))

            // Pause Job A
            coordinator.pauseDownload("job-A")

            val jobA = coordinator.getJob("job-A")
            assertNotNull(jobA)
            assertEquals(VoxDownloadState.PAUSED, jobA?.state)

            gate.countDown()

            // Wait for Job B to be promoted
            jobBStateLatch.await(3, TimeUnit.SECONDS)
            var jobBAfter: VoxDownloadJob? = null
            for (i in 0 until 40) {
                jobBAfter = coordinator.getJob("job-B")
                if (jobBAfter != null && (jobBAfter.state != VoxDownloadState.QUEUED || coordinator.getProcessingJob()?.downloadId == "job-B")) break
                Thread.sleep(50)
            }
            assertNotNull(jobBAfter)
            assertTrue(
                jobBAfter!!.state != VoxDownloadState.QUEUED ||
                coordinator.getProcessingJob()?.downloadId == "job-B"
            )
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun testRangeResumptionPreservesPartialBytesAndSendsRangeHeader() {
        val job = VoxDownloadJob(
            VoxDownloadRequest(
                downloadId = "job-resume-range",
                videoId = "vid-range",
                videoTitle = "Title Range",
                translationMode = VoxTranslationMode.NONE
            )
        )
        repository.addOrUpdateJob(job)

        // Pre-create 60 bytes of partial video download
        val videoFile = storage.getTrackFile(job.downloadId, VoxDownloadTrack.VIDEO)
        videoFile.writeBytes(ByteArray(60) { 0x11 })
        job.updateTrackProgress(VoxDownloadTrack.VIDEO, 60, 200, VoxTrackState.IN_PROGRESS)
        job.updateState(VoxDownloadState.PAUSED)
        repository.persistJob(job)

        val latch = CountDownLatch(1)
        coordinator.addListener(job.downloadId, object : VoxDownloadListener {
            override fun onStateChanged(progress: VoxDownloadProgress) {
                if (progress.state == VoxDownloadState.READY_FOR_MUX || progress.state == VoxDownloadState.COMPLETED) {
                    latch.countDown()
                }
            }
            override fun onProgressUpdated(progress: VoxDownloadProgress) {}
            override fun onError(downloadId: String, errorCode: VoxDownloadErrorCode, message: String) {}
        })

        // Resume job
        assertTrue(coordinator.resumeDownload(job.downloadId))
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // Check recorded HTTP headers: at least one Range request with bytes=60-
        synchronized(recordedRanges) {
            val hasExpectedRange = recordedRanges.any { it != null && it.startsWith("bytes=60-") }
            assertTrue("Expected Range header bytes=60- in recorded requests: $recordedRanges", hasExpectedRange)
        }
    }

    @Test
    fun testRetryPolicyAllowsNetworkRejectsPermanentErrors() {
        // 1. Transient error: NETWORK
        val jobNetwork = VoxDownloadJob(VoxDownloadRequest(downloadId = "job-net", videoId = "vid-net", videoTitle = "Net"))
        repository.addOrUpdateJob(jobNetwork)
        jobNetwork.updateState(VoxDownloadState.FAILED, VoxDownloadErrorCode.NETWORK_ERROR)
        assertTrue("Should allow retrying network error", coordinator.retryDownload(jobNetwork.downloadId))

        // Cancel job-net to free coordinator active slot
        coordinator.cancelDownload("job-net")

        // 2. Transient error: TIMEOUT
        val jobTimeout = VoxDownloadJob(VoxDownloadRequest(downloadId = "job-time", videoId = "vid-time", videoTitle = "Time"))
        repository.addOrUpdateJob(jobTimeout)
        jobTimeout.updateState(VoxDownloadState.FAILED, VoxDownloadErrorCode.TIMEOUT)
        assertTrue("Should allow retrying timeout error", coordinator.retryDownload(jobTimeout.downloadId))

        coordinator.cancelDownload("job-time")

        // 3. Permanent error: STORAGE_FULL
        val jobStorage = VoxDownloadJob(VoxDownloadRequest(downloadId = "job-store", videoId = "vid-store", videoTitle = "Store"))
        repository.addOrUpdateJob(jobStorage)
        jobStorage.updateState(VoxDownloadState.FAILED, VoxDownloadErrorCode.STORAGE_FULL)
        assertFalse("Should NOT automatically retry STORAGE_FULL", coordinator.retryDownload(jobStorage.downloadId, force = false))
        assertTrue("Should allow forced retry for STORAGE_FULL if user requests", coordinator.retryDownload(jobStorage.downloadId, force = true))

        coordinator.cancelDownload("job-store")

        // 4. Permanent error: UNSUPPORTED_FORMAT
        val jobFormat = VoxDownloadJob(VoxDownloadRequest(downloadId = "job-codec", videoId = "vid-codec", videoTitle = "Codec"))
        repository.addOrUpdateJob(jobFormat)
        jobFormat.updateState(VoxDownloadState.FAILED, VoxDownloadErrorCode.UNSUPPORTED_FORMAT)
        assertFalse("Should NOT automatically retry UNSUPPORTED_FORMAT", coordinator.retryDownload(jobFormat.downloadId, force = false))
    }

    @Test
    fun testCrashRecoveryRestoresActiveJobsToQueuedOrPaused() {
        // Put one job in DOWNLOADING_MEDIA and one in MUXING to simulate process kill
        val jobCrashed1 = VoxDownloadJob(VoxDownloadRequest(downloadId = "crash-1", videoId = "vid-c1", videoTitle = "Crash 1"))
        jobCrashed1.updateState(VoxDownloadState.DOWNLOADING_MEDIA)
        repository.addOrUpdateJob(jobCrashed1)

        val jobCrashed2 = VoxDownloadJob(VoxDownloadRequest(downloadId = "crash-2", videoId = "vid-c2", videoTitle = "Crash 2"))
        jobCrashed2.updateState(VoxDownloadState.MUXING)
        repository.addOrUpdateJob(jobCrashed2)

        // Create fresh repository instance (simulating app restart)
        val freshRepo = VoxDownloadRepository(storage)

        val restored1 = freshRepo.getJob("crash-1")
        val restored2 = freshRepo.getJob("crash-2")

        assertNotNull(restored1)
        assertNotNull(restored2)

        // Neither job should remain in DOWNLOADING_MEDIA or MUXING!
        // Downloader tracks were incomplete, so crashed job is restored as QUEUED or PAUSED
        assertTrue(restored1!!.state == VoxDownloadState.QUEUED || restored1.state == VoxDownloadState.PAUSED)
        // Muxing job without completed final file is restored to QUEUED/PAUSED or READY_FOR_MUX
        assertTrue(restored2!!.state == VoxDownloadState.QUEUED || restored2.state == VoxDownloadState.READY_FOR_MUX || restored2.state == VoxDownloadState.PAUSED)
    }

    @Test
    fun testSpeedEstimatorResetOnResume() {
        val job = VoxDownloadJob(VoxDownloadRequest(downloadId = "job-speed", videoId = "vid-speed", videoTitle = "Speed"))
        job.averageSpeedMbps = 45.5
        job.peakSpeedMbps = 80.0
        job.updateState(VoxDownloadState.PAUSED)
        repository.addOrUpdateJob(job)

        coordinator.resumeDownload("job-speed")
        val updated = coordinator.getJob("job-speed")
        assertNotNull(updated)
        // Speed estimator must be reset so stale speed doesn't corrupt new measurement
        assertEquals(0.0, updated!!.averageSpeedMbps, 0.001)
        assertEquals(0.0, updated.peakSpeedMbps, 0.001)
    }

    @Test
    fun testDiagnosticsZeroPiiAndQueueMetrics() {
        val job = VoxDownloadJob(VoxDownloadRequest(downloadId = "diag-test", videoId = "secretVideo123", videoTitle = "Top Secret Title"))
        job.averageSpeedMbps = 15.4
        job.peakSpeedMbps = 24.8
        job.rangeResumptionsCount = 2
        job.networkReconnectCount = 1
        job.stallEventsCount = 0
        job.updateState(VoxDownloadState.DOWNLOADING_MEDIA)
        repository.addOrUpdateJob(job)

        val summary = coordinator.getDiagnosticsSummary()
        assertNotNull(summary)

        // Verify summary fields
        assertTrue(summary.containsKey("queueLength"))
        assertTrue(summary.containsKey("activeJobs"))
        assertTrue(summary.containsKey("pausedJobs"))
        assertTrue(summary.containsKey("failedJobs"))
        assertTrue(summary.containsKey("downloadPerformance"))

        // Verify strict zero-PII: no video title or secret video ID in summary values
        val summaryString = summary.toString()
        assertFalse("Summary must not contain videoId", summaryString.contains("secretVideo123"))
        assertFalse("Summary must not contain videoTitle", summaryString.contains("Top Secret Title"))
    }
}
