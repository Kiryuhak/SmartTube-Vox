package com.liskovsoft.smartyoutubetv2.common.vox.download

import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VoxDownloadPerformanceTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = File.createTempFile("vox_perf_test_", ".part")
        tempFile.delete()
    }

    @After
    fun tearDown() {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    @Test
    fun testProgressThrottlingReducesUiNotificationPressure() {
        // Create 2 MB payload (2048 KB)
        val totalBytes = 2 * 1024 * 1024
        val dummyData = ByteArray(totalBytes) { 0x55.toByte() }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("video/mp4"), dummyData))
                    .build()
            }.build()

        val downloader = VoxSegmentDownloader(httpClient = client)
        val progressCallCount = AtomicInteger(0)

        downloader.download(
            initialUrl = "https://rr1---sn.googlevideo.com/videoplayback",
            targetFile = tempFile,
            onProgress = { _, _ ->
                progressCallCount.incrementAndGet()
            }
        )

        // Without throttling, reading 2MB in chunks or small bursts would produce dozens or hundreds of callbacks.
        // With PROGRESS_NOTIFY_MIN_BYTES = 256KB, 2MB should produce roughly 8 notifications (+/- 4).
        val calls = progressCallCount.get()
        assertTrue("Callback count ($calls) should be strongly throttled (< 20)", calls in 1..20)
        assertEquals(totalBytes.toLong(), tempFile.length())
    }

    @Test
    fun testRangeResumptionCallbackAndHeader() {
        // Pre-create file with 500 KB existing data
        val preExistingBytes = 500 * 1024
        tempFile.writeBytes(ByteArray(preExistingBytes) { 0x01.toByte() })

        val remainingBytes = 500 * 1024
        val remainingData = ByteArray(remainingBytes) { 0x02.toByte() }

        var rangeHeaderSent: String? = null
        val onResumedCalled = AtomicBoolean(false)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                rangeHeaderSent = req.header("Range")
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes $preExistingBytes-${preExistingBytes + remainingBytes - 1}/${preExistingBytes + remainingBytes}")
                    .body(ResponseBody.create(MediaType.parse("video/mp4"), remainingData))
                    .build()
            }.build()

        val downloader = VoxSegmentDownloader(httpClient = client)

        downloader.download(
            initialUrl = "https://rr1---sn.googlevideo.com/videoplayback",
            targetFile = tempFile,
            onRangeResumed = { resumedOffset ->
                onResumedCalled.set(true)
                assertEquals(preExistingBytes.toLong(), resumedOffset)
            }
        )

        assertEquals("bytes=$preExistingBytes-", rangeHeaderSent)
        assertTrue(onResumedCalled.get())
        assertEquals((preExistingBytes + remainingBytes).toLong(), tempFile.length())
    }

    @Test
    fun testSpeedEstimatorFormattingAndETA() {
        val estimator = VoxDownloadSpeedEstimator(
            alpha = 0.5f,
            minAccumulationMs = 1000L,
            minIntervalMs = 100L
        )

        // Initial state before warm-up
        assertEquals(0.0, estimator.getEmaSpeedBytesPerSec(), 0.001)
        assertEquals(0.0, estimator.getSpeedMbps(), 0.001)
        assertEquals("", estimator.getFormattedSpeed())

        val startTime = 10000L
        // First sample (start initialization)
        estimator.update(downloadedBytes = 0L, totalBytes = 10_000_000L, nowMs = startTime)

        // Second sample at +500ms (1 MB downloaded)
        estimator.update(downloadedBytes = 1_000_000L, totalBytes = 10_000_000L, nowMs = startTime + 500L)

        // Third sample at +1200ms (> minAccumulationMs, 2.4 MB total downloaded -> ~2 MB/s)
        val eta = estimator.update(downloadedBytes = 2_400_000L, totalBytes = 10_000_000L, nowMs = startTime + 1200L)

        val speedBytes = estimator.getEmaSpeedBytesPerSec()
        assertTrue("Speed bytes ($speedBytes) should be > 1 MB/s", speedBytes > 1_000_000.0)

        val formattedSpeed = estimator.getFormattedSpeed()
        assertTrue("Formatted speed should contain МБ/с: $formattedSpeed", formattedSpeed.contains("МБ/с"))

        assertNotNull("ETA should be calculated after warm-up", eta)
        assertTrue("ETA ($eta) should be roughly 3-6 seconds", eta!! in 2..8)
    }

    @Test
    fun testJobPerformanceMetricsTracking() {
        val request = VoxDownloadRequest(
            videoId = "test_vid_perf",
            videoTitle = "Test Performance Video"
        )
        val job = VoxDownloadJob(request = request)

        assertEquals(0L, job.downloadElapsedMs)
        assertEquals(0, job.rangeResumptionsCount)
        assertEquals(0L, job.bytesResumed)
        assertEquals(0, job.stallEventsCount)
        assertEquals(0.0, job.averageSpeedMbps, 0.001)

        job.parallelStreamsCount = 2
        job.downloadElapsedMs = 12000L
        job.videoElapsedMs = 11500L
        job.audioElapsedMs = 8000L
        job.rangeResumptionsCount = 1
        job.bytesResumed = 1048576L
        job.stallEventsCount = 1
        job.averageSpeedMbps = 15.4

        assertEquals(2, job.parallelStreamsCount)
        assertEquals(12000L, job.downloadElapsedMs)
        assertEquals(1, job.rangeResumptionsCount)
        assertEquals(1048576L, job.bytesResumed)
        assertEquals(1, job.stallEventsCount)
        assertEquals(15.4, job.averageSpeedMbps, 0.001)
    }

    @Test
    fun testDownloadProgressComputationWithNullTranslation() {
        val progress = VoxDownloadProgress(
            downloadId = "test_progress_id",
            state = VoxDownloadState.DOWNLOADING_MEDIA,
            video = VoxTrackProgress(
                track = VoxDownloadTrack.VIDEO,
                bytesDownloaded = 10_000_000L,
                totalBytes = 20_000_000L
            ),
            originalAudio = VoxTrackProgress(
                track = VoxDownloadTrack.ORIGINAL_AUDIO,
                bytesDownloaded = 2_000_000L,
                totalBytes = 4_000_000L
            ),
            translatedAudio = VoxTrackProgress(
                track = VoxDownloadTrack.TRANSLATED_AUDIO,
                bytesDownloaded = 0L,
                totalBytes = null
            ),
            translationState = VoxDownloadTranslationState.NONE
        )

        assertEquals(12_000_000L, progress.totalBytesDownloaded)
        assertEquals(24_000_000L, progress.totalBytesExpected)
        assertEquals(50, progress.overallPercent)
    }
}
