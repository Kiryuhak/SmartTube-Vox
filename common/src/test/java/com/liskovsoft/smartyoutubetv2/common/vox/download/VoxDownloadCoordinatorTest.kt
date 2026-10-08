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
class VoxDownloadCoordinatorTest {

    private lateinit var context: Context
    private lateinit var storage: VoxDownloadStorage
    private lateinit var repository: VoxDownloadRepository
    private lateinit var coordinator: VoxDownloadCoordinator
    private lateinit var executor: java.util.concurrent.ExecutorService

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = VoxDownloadStorage(context)
        storage.baseDir.deleteRecursively()
        repository = VoxDownloadRepository(storage)

        val mockStreamResolver = object : VoxStreamResolver {
            override fun resolveVideoStream(videoId: String, qualityPreference: VoxQualityPreference): VoxResolvedStream {
                return VoxResolvedStream(
                    url = "https://rr1---sn-4g5edn6s.googlevideo.com/videoplayback?v=$videoId",
                    itag = 137,
                    mimeType = "video/mp4",
                    codec = "avc1",
                    contentLength = 100L
                )
            }

            override fun resolveOriginalAudio(videoId: String): VoxResolvedStream {
                return VoxResolvedStream(
                    url = "https://rr1---sn-4g5edn6s.googlevideo.com/audioplayback?v=$videoId",
                    itag = 140,
                    mimeType = "audio/mp4",
                    codec = "mp4a",
                    contentLength = 50L
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
                val sampleData = ByteArray(100) { 0x42 }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("application/octet-stream"), sampleData))
                    .build()
            }.build()

        val mockDownloader = VoxSegmentDownloader(httpClient = fakeClient, storage = storage)

        executor = Executors.newSingleThreadExecutor()
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
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        storage.baseDir.deleteRecursively()
    }

    @Test
    fun testFullDownloadPipelineReachesReadyForMux() {
        val request = VoxDownloadRequest(
            downloadId = "job-ready-for-mux",
            videoId = "UF8uR6Z6KLc",
            videoTitle = "Test Full Pipeline"
        )

        val latch = CountDownLatch(1)
        val recordedStates = mutableListOf<VoxDownloadState>()

        coordinator.startDownload(request, object : VoxDownloadListener {
            override fun onStateChanged(progress: VoxDownloadProgress) {
                recordedStates.add(progress.state)
                if (progress.state == VoxDownloadState.READY_FOR_MUX) {
                    // HTTP fixtures не являются media. Останавливаемся ровно на границе mux.
                    coordinator.pauseDownload(progress.downloadId)
                    latch.countDown()
                }
            }

            override fun onProgressUpdated(progress: VoxDownloadProgress) {}

            override fun onError(downloadId: String, errorCode: VoxDownloadErrorCode, message: String) {
                latch.countDown()
            }
        })

        assertTrue("Timeout waiting for job completion", latch.await(5, TimeUnit.SECONDS))

        val finalJob = coordinator.getJob("job-ready-for-mux")
        assertNotNull(finalJob)
        assertEquals("Job failed with error=${finalJob?.errorCode}: ${finalJob?.errorMessage}", VoxDownloadState.PAUSED, finalJob!!.state)

        // Проверяем наличие всех трех файлов треков
        val vFile = storage.getTrackFile("job-ready-for-mux", VoxDownloadTrack.VIDEO)
        val oFile = storage.getTrackFile("job-ready-for-mux", VoxDownloadTrack.ORIGINAL_AUDIO)
        val tFile = storage.getTrackFile("job-ready-for-mux", VoxDownloadTrack.TRANSLATED_AUDIO)

        assertTrue(vFile.exists())
        assertTrue(oFile.exists())
        assertTrue(tFile.exists())
        assertTrue(vFile.length() > 0)
        assertTrue(oFile.length() > 0)
        assertTrue(tFile.length() > 0)

        // Проверяем порядок этапов
        assertTrue(recordedStates.contains(VoxDownloadState.PREPARING_TRANSLATION))
        assertTrue(recordedStates.contains(VoxDownloadState.RESOLVING_STREAMS))
        assertTrue(recordedStates.contains(VoxDownloadState.DOWNLOADING_VIDEO))
        assertTrue(recordedStates.contains(VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO))
        assertTrue(recordedStates.contains(VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO))
        assertTrue(recordedStates.contains(VoxDownloadState.READY_FOR_MUX))
    }

    @Test
    fun ordinaryDownloadSkipsTranslationAndReachesMuxBoundary() {
        val request = VoxDownloadRequest(
            downloadId = "job-ordinary", videoId = "UF8uR6Z6KLc", videoTitle = "Ordinary",
            translationMode = VoxTranslationMode.NONE
        )
        val latch = CountDownLatch(1)
        val states = mutableListOf<VoxDownloadState>()
        coordinator.startDownload(request, object : VoxDownloadListener {
            override fun onStateChanged(progress: VoxDownloadProgress) {
                states.add(progress.state)
                if (progress.state == VoxDownloadState.READY_FOR_MUX) {
                    coordinator.pauseDownload(progress.downloadId)
                    latch.countDown()
                }
            }
            override fun onProgressUpdated(progress: VoxDownloadProgress) {}
            override fun onError(downloadId: String, errorCode: VoxDownloadErrorCode, message: String) {
                latch.countDown()
            }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(VoxDownloadState.PAUSED, coordinator.getJob(request.downloadId)?.state)
        assertFalse(states.contains(VoxDownloadState.PREPARING_TRANSLATION))
        assertFalse(states.contains(VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO))
        assertTrue(states.contains(VoxDownloadState.READY_FOR_MUX))
        assertFalse(storage.getTrackFile(request.downloadId, VoxDownloadTrack.TRANSLATED_AUDIO).exists())
    }

    @Test
    fun testRestoringUnfinishedJobsFromStorageAsPaused() {
        val req = VoxDownloadRequest(
            downloadId = "job-interrupted",
            videoId = "kJQP7kiw5Fk",
            videoTitle = "Interrupted Job"
        )

        storage.saveJobMetadata(
            request = req,
            state = VoxDownloadState.DOWNLOADING_VIDEO,
            videoProgress = VoxTrackProgress(VoxDownloadTrack.VIDEO, bytesDownloaded = 50, totalBytes = 100, state = VoxTrackState.IN_PROGRESS),
            originalAudioProgress = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO),
            translatedAudioProgress = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO)
        )

        // Симулируем перезапуск приложения (создание нового репозитория)
        val newRepo = VoxDownloadRepository(storage)
        val restoredJob = newRepo.getJob("job-interrupted")

        assertNotNull(restoredJob)
        assertEquals(VoxDownloadState.PAUSED, restoredJob!!.state)
        assertEquals(50L, restoredJob.videoProgress.bytesDownloaded)
    }

    @Test
    fun testCancelCleansIncompletePartFiles() {
        val req = VoxDownloadRequest(
            downloadId = "job-to-cancel",
            videoId = "UF8uR6Z6KLc",
            videoTitle = "Cancel Test"
        )
        val job = VoxDownloadJob(req)
        repository.addOrUpdateJob(job)

        val vFile = storage.getTrackFile("job-to-cancel", VoxDownloadTrack.VIDEO)
        vFile.writeText("partial video")
        assertTrue(vFile.exists())

        coordinator.cancelDownload("job-to-cancel")
        assertEquals(VoxDownloadState.CANCELLED, job.state)
        assertFalse("Partial track file must be cleaned on cancel", vFile.exists())
    }

    @Test
    fun testSingleActiveJobQueue() {
        val reqA = VoxDownloadRequest(downloadId = "job-A", videoId = "videoA", videoTitle = "Title A")
        val reqB = VoxDownloadRequest(downloadId = "job-B", videoId = "videoB", videoTitle = "Title B")

        coordinator.startDownload(reqA)
        coordinator.startDownload(reqB)

        val jobB = coordinator.getJob("job-B")
        assertNotNull(jobB)
        // jobB не должно стартовать параллельно, пока jobA выполняется
        assertTrue(jobB!!.state == VoxDownloadState.QUEUED || jobB.state == VoxDownloadState.PAUSED || jobB.state == VoxDownloadState.IDLE)
    }

    @Test
    fun testLivelyRequiresAuth() {
        val unauthResolver = DefaultVoxTranslationResolver(
            oauthTokenProvider = { null },
            isLivelyAuthorized = { false }
        )

        try {
            unauthResolver.resolveTranslation(
                videoId = "testVideo",
                mode = VoxTranslationMode.LIVELY
            )
            org.junit.Assert.fail("Expected AUTH_REQUIRED exception")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.AUTH_REQUIRED, e.code)
        }
    }

    @Test fun retryRetainsValidCompletedSourcesAndRejectsDuplicateWorker() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit { entered.countDown(); release.await() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val job = VoxDownloadJob(VoxDownloadRequest(downloadId = "retry-stalled", videoId = "retry", videoTitle = "Retry"))
        repository.addOrUpdateJob(job)
        for (track in VoxDownloadTrack.values()) {
            storage.getTrackFile(job.downloadId, track).writeBytes(ByteArray(100))
            job.updateTrackProgress(track, 100, 100, VoxTrackState.COMPLETED)
        }
        job.updateState(VoxDownloadState.FAILED, VoxDownloadErrorCode.PROCESSING_STALLED)
        assertTrue(coordinator.retryDownload(job.downloadId))
        assertFalse(coordinator.retryDownload(job.downloadId))
        assertFalse(coordinator.resumeDownload(job.downloadId))
        assertEquals(VoxTrackState.COMPLETED, job.videoProgress.state)
        assertEquals(VoxTrackState.COMPLETED, job.translatedAudioProgress.state)
        coordinator.cancelDownload(job.downloadId)
        // Отмена тоже не уничтожает проверенные входные файлы для повторной упаковки.
        assertEquals(100L, storage.getTrackFile(job.downloadId, VoxDownloadTrack.VIDEO).length())
        release.countDown()
    }

    @Test fun completedMetadataIsCommittedBeforeInMemoryStateAndRestored() {
        val job = VoxDownloadJob(VoxDownloadRequest(downloadId = "commit", videoId = "commit", videoTitle = "Commit"))
        repository.addOrUpdateJob(job)
        job.updateState(VoxDownloadState.MUXING)
        repository.persistJob(job)
        job.updateState(VoxDownloadState.FINALIZING)
        repository.persistJob(job)
        assertEquals(VoxDownloadState.FINALIZING, storage.loadJobMetadata(job.downloadId)!!.state)
        job.publishedUri = "content://media/external/video/media/321"
        job.hasTranslatedAudio = true
        job.durationMs = 240_000
        job.finalFileBytes = 1024
        repository.persistJob(job, VoxDownloadState.COMPLETED)
        assertEquals(VoxDownloadState.FINALIZING, job.state)
        val restored = VoxDownloadRepository(storage).getJob(job.downloadId)!!
        assertEquals(VoxDownloadState.COMPLETED, restored.state)
        assertTrue(restored.hasTranslatedAudio)
        assertEquals(240_000L, restored.durationMs)
        assertEquals(1024L, restored.finalFileBytes)
        // Ошибка записи не должна менять состояние в памяти или старый job.json.
        val temp = File(storage.getJobDir(job.downloadId), "job.json.tmp")
        assertTrue(temp.mkdir())
        try { repository.persistJob(job); org.junit.Assert.fail("Expected write failure") }
        catch (expected: java.io.IOException) {}
        assertEquals(VoxDownloadState.FINALIZING, job.state)
        assertEquals(VoxDownloadState.COMPLETED, storage.loadJobMetadata(job.downloadId)!!.state)
    }
}

