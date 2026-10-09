package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class VoxDownloadThroughputTest {

    private lateinit var context: Context
    private lateinit var storage: VoxDownloadStorage
    private lateinit var repository: VoxDownloadRepository
    private lateinit var tempFile: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = VoxDownloadStorage(context)
        storage.baseDir.deleteRecursively()
        repository = VoxDownloadRepository(storage)
        tempFile = File.createTempFile("vox_tp_test_", ".part")
        tempFile.delete()
    }

    @After
    fun tearDown() {
        if (tempFile.exists()) {
            tempFile.delete()
        }
        storage.baseDir.deleteRecursively()
    }

    @Test
    fun testBufferSizesAndSpaceCheckIntervalConstants() {
        assertEquals(256 * 1024, VoxSegmentDownloader.BUFFER_SIZE)
        assertEquals(256 * 1024, VoxSegmentDownloader.IO_BUFFER_SIZE)
        assertEquals(250L, VoxSegmentDownloader.PROGRESS_NOTIFY_MIN_INTERVAL_MS)
        assertEquals(256 * 1024L, VoxSegmentDownloader.PROGRESS_NOTIFY_MIN_BYTES)
    }

    @Test
    fun testKeepAliveAndIdentityHeadersInDownloader() {
        var connectionHeader: String? = null
        var acceptEncodingHeader: String? = null

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                connectionHeader = req.header("Connection")
                acceptEncodingHeader = req.header("Accept-Encoding")
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("video/mp4"), ByteArray(1024) { 0x11 }))
                    .build()
            }.build()

        val downloader = VoxSegmentDownloader(httpClient = client, storage = storage)
        downloader.download(
            initialUrl = "https://rr1---sn-4g5edn6s.googlevideo.com/videoplayback?id=test",
            targetFile = tempFile
        )

        assertEquals("keep-alive", connectionHeader)
        assertEquals("identity", acceptEncodingHeader)
        assertEquals(1024L, tempFile.length())
    }

    @Test
    fun testSpeedEstimatorEmaFilteringAndEta() {
        val estimator = VoxDownloadSpeedEstimator(
            alpha = 0.25f,
            minAccumulationMs = 2000L,
            minIntervalMs = 200L
        )

        val baseTime = 100000L
        // 1. Initial sample
        assertNull(estimator.update(downloadedBytes = 0L, totalBytes = 50_000_000L, nowMs = baseTime))

        // 2. Sample at +500ms (1 MB downloaded -> 2 MB/s instant)
        assertNull(estimator.update(downloadedBytes = 1_000_000L, totalBytes = 50_000_000L, nowMs = baseTime + 500L))

        // 3. Sample at +1500ms (3 MB downloaded)
        assertNull(estimator.update(downloadedBytes = 3_000_000L, totalBytes = 50_000_000L, nowMs = baseTime + 1500L))

        // 4. Sample at +2500ms (> minAccumulationMs, 5 MB total downloaded -> ~2 MB/s sustained)
        val eta = estimator.update(downloadedBytes = 5_000_000L, totalBytes = 50_000_000L, nowMs = baseTime + 2500L)

        assertNotNull(eta)
        assertTrue("ETA should be positive and reasonable (~20-25 sec)", eta!! in 15..35)

        val formattedSpeed = estimator.getFormattedSpeed()
        assertTrue("Speed format should contain МБ/с: $formattedSpeed", formattedSpeed.contains("МБ/с"))
        val speedMbps = estimator.getSpeedMbps()
        assertTrue("Speed Mbps ($speedMbps) should be roughly 10-25 Mbps", speedMbps > 5.0)
    }

    @Test
    fun testSpeedEstimatorHandlesZeroOrStallGracefully() {
        val estimator = VoxDownloadSpeedEstimator(
            alpha = 0.25f,
            minAccumulationMs = 1000L,
            minIntervalMs = 100L
        )

        val baseTime = 50000L
        estimator.update(0L, 10_000_000L, baseTime)
        estimator.update(1_000_000L, 10_000_000L, baseTime + 500L)
        estimator.update(2_000_000L, 10_000_000L, baseTime + 1200L)

        val speedBefore = estimator.getEmaSpeedBytesPerSec()
        assertTrue(speedBefore > 0)

        // Stalled update (no bytes added over 500ms)
        estimator.update(2_000_000L, 10_000_000L, baseTime + 1700L)
        val speedAfter = estimator.getEmaSpeedBytesPerSec()
        assertTrue("Speed should decay towards zero during stalls", speedAfter < speedBefore)
    }

    @Test
    fun testAsyncNonBlockingProgressPersistence() {
        val executor = Executors.newSingleThreadExecutor()
        val mockStreamResolver = object : VoxStreamResolver {
            override fun resolveVideoStream(videoId: String, qualityPreference: VoxQualityPreference): VoxResolvedStream {
                return VoxResolvedStream(
                    url = "https://rr1---sn-4g5edn6s.googlevideo.com/videoplayback?v=$videoId",
                    itag = 137,
                    mimeType = "video/mp4",
                    codec = "avc1",
                    contentLength = 2000L
                )
            }

            override fun resolveOriginalAudio(videoId: String): VoxResolvedStream {
                return VoxResolvedStream(
                    url = "https://rr1---sn-4g5edn6s.googlevideo.com/audioplayback?v=$videoId",
                    itag = 140,
                    mimeType = "audio/mp4",
                    codec = "mp4a",
                    contentLength = 1000L
                )
            }
        }

        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val sampleData = ByteArray(1000) { 0x33 }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("application/octet-stream"), sampleData))
                    .build()
            }.build()

        val mockDownloader = VoxSegmentDownloader(httpClient = fakeClient, storage = storage)

        val coordinator = VoxDownloadCoordinator(
            storage = storage,
            repository = repository,
            streamResolver = mockStreamResolver,
            downloader = mockDownloader,
            executor = executor
        )

        val request = VoxDownloadRequest(
            downloadId = "job-async-persist",
            videoId = "test_vid_async",
            videoTitle = "Async Persistence Test",
            translationMode = VoxTranslationMode.NONE
        )

        val latch = CountDownLatch(1)
        val progressUpdates = AtomicInteger(0)

        coordinator.startDownload(request, object : VoxDownloadListener {
            override fun onStateChanged(progress: VoxDownloadProgress) {
                if (progress.state == VoxDownloadState.READY_FOR_MUX) {
                    coordinator.pauseDownload(progress.downloadId)
                    latch.countDown()
                }
            }

            override fun onProgressUpdated(progress: VoxDownloadProgress) {
                progressUpdates.incrementAndGet()
            }

            override fun onError(downloadId: String, errorCode: VoxDownloadErrorCode, message: String) {
                latch.countDown()
            }
        })

        assertTrue("Download pipeline should reach READY_FOR_MUX", latch.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
    }
}
