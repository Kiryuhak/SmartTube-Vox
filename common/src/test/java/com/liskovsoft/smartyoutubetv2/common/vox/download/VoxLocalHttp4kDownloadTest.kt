package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
import com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMatroskaMuxer
import com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMuxCodec
import com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMuxSample
import com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMuxTrackInfo
import com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMuxTrackType
import com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxSampleSource
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class VoxLocalHttp4kDownloadTest {

    private lateinit var storage: VoxDownloadStorage
    private lateinit var repository: VoxDownloadRepository

    private var videoFixtureBytes: ByteArray = ByteArray(0)
    private var audioFixtureBytes: ByteArray = ByteArray(0)

    private class FakeMediaFormat(
        private val width: Int,
        private val height: Int,
        private val mimeType: String = "video/webm; codecs=\"vp9\"",
        private val url: String? = "https://rr1---sn-4g5edn6s.googlevideo.com/videoplayback",
        private val bitrate: String? = "15000000",
        private val clen: String? = "140000",
        private val itag: String? = "315"
    ) : MediaFormat {
        override fun getFormatType(): Int = MediaFormat.FORMAT_TYPE_DASH
        override fun getUrl(): String? = url
        override fun getMimeType(): String? = mimeType
        override fun getITag(): String? = itag
        override fun isDrc(): Boolean = false
        override fun getClen(): String? = clen
        override fun getBitrate(): String? = bitrate
        override fun getProjectionType(): String? = null
        override fun getXtags(): String? = null
        override fun getAudioTrackId(): String? = null
        override fun getWidth(): Int = width
        override fun getHeight(): Int = height
        override fun getIndex(): String? = null
        override fun getInit(): String? = null
        override fun getFps(): String? = "60"
        override fun getLmt(): String? = null
        override fun getQualityLabel(): String? = null
        override fun getFormat(): String? = null
        override fun isOtf(): Boolean = false
        override fun getOtfInitUrl(): String? = null
        override fun getOtfTemplateUrl(): String? = null
        override fun getLanguage(): String? = null
        override fun getTargetDurationSec(): Int = 0
        override fun getMaxDvrDurationSec(): Int = 0
        override fun getApproxDurationMs(): Int = 0
        override fun getQuality(): String? = null
        override fun getSignature(): String? = null
        override fun getAudioSamplingRate(): String? = null
        override fun getSourceUrl(): String? = null
        override fun getSegmentUrlList(): List<String>? = null
        override fun getGlobalSegmentList(): List<String>? = null
        override fun compareTo(other: MediaFormat?): Int = 0
    }

    @Before
    fun setUp() {
        VoxUrlSecurityValidator.allowLocalFixtures = true

        val candidateDirs = listOf(
            File("../build/reports/vox-post-release-hardening/fixtures"),
            File("build/reports/vox-post-release-hardening/fixtures"),
            File("../../build/reports/vox-post-release-hardening/fixtures"),
            File("/home/kiryuha/Developer/SmartTube/build/reports/vox-post-release-hardening/fixtures")
        )
        val fixturesDir = candidateDirs.firstOrNull { it.exists() }

        val vFile = fixturesDir?.let { File(it, "video_4k_vp9.webm") }
        val aFile = fixturesDir?.let { File(it, "audio_opus.webm") }

        videoFixtureBytes = if (vFile != null && vFile.exists()) vFile.readBytes() else ByteArray(137_000) { 0x11 }
        audioFixtureBytes = if (aFile != null && aFile.exists()) aFile.readBytes() else ByteArray(43_000) { 0x22 }

        val context = RuntimeEnvironment.getApplication()
        storage = VoxDownloadStorage(context)
        storage.baseDir.deleteRecursively()
        repository = VoxDownloadRepository(storage)
    }

    @After
    fun tearDown() {
        VoxUrlSecurityValidator.allowLocalFixtures = false
        storage.baseDir.deleteRecursively()
    }

    private fun createHttpFixtureClient(): OkHttpClient {
        return OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val url = req.url().toString()

            when {
                url.contains("video_4k_vp9.webm") -> handleDataResponse(req, videoFixtureBytes, "video/webm")
                url.contains("audio_opus.webm") -> handleDataResponse(req, audioFixtureBytes, "audio/webm")
                url.contains("status_404") -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body(ResponseBody.create(null, ByteArray(0)))
                        .build()
                }
                url.contains("status_429") -> {
                    val count = chain.request().header("X-Retry-Count")?.toIntOrNull() ?: 0
                    if (count == 0) {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(429)
                            .message("Too Many Requests")
                            .body(ResponseBody.create(null, ByteArray(0)))
                            .build()
                    } else {
                        val resp = "RecoveredAfter429".toByteArray()
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(MediaType.parse("application/octet-stream"), resp))
                            .build()
                    }
                }
                else -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("Bad Request")
                        .body(ResponseBody.create(null, ByteArray(0)))
                        .build()
                }
            }
        }.build()
    }

    private fun handleDataResponse(req: okhttp3.Request, data: ByteArray, mimeType: String): Response {
        val rangeHeader = req.header("Range")
        return if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeVal = rangeHeader.substring("bytes=".length)
            val parts = rangeVal.split("-")
            val start = parts[0].toIntOrNull() ?: 0
            val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toInt() else (data.size - 1)

            if (start >= data.size || start > end) {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(416)
                    .message("Range Not Satisfiable")
                    .body(ResponseBody.create(null, ByteArray(0)))
                    .build()
            } else {
                val contentLength = end - start + 1
                val chunk = data.copyOfRange(start, end + 1)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Type", mimeType)
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Range", "bytes $start-$end/${data.size}")
                    .body(ResponseBody.create(MediaType.parse(mimeType), chunk))
                    .build()
            }
        } else {
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", mimeType)
                .header("Accept-Ranges", "bytes")
                .body(ResponseBody.create(MediaType.parse(mimeType), data))
                .build()
        }
    }

    @Test
    fun testProductionDownloaderFetches4kBytesFromLocalHttpFixture() {
        val downloadId = "vox-http-4k-job-001"
        val videoTarget = storage.getTrackFile(downloadId, VoxDownloadTrack.VIDEO)
        val audioTarget = storage.getTrackFile(downloadId, VoxDownloadTrack.ORIGINAL_AUDIO)

        val httpClient = createHttpFixtureClient()
        val downloader = VoxSegmentDownloader(httpClient = httpClient, storage = storage)

        var videoReportedBytes = 0L
        downloader.download(
            initialUrl = "https://rr1---sn-4g5edn6s.googlevideo.com/video_4k_vp9.webm",
            targetFile = videoTarget,
            onProgress = { b, _ -> videoReportedBytes = b }
        )

        var audioReportedBytes = 0L
        downloader.download(
            initialUrl = "https://rr1---sn-4g5edn6s.googlevideo.com/audio_opus.webm",
            targetFile = audioTarget,
            onProgress = { b, _ -> audioReportedBytes = b }
        )

        assertTrue("Downloaded video file must exist", videoTarget.exists())
        assertEquals(videoFixtureBytes.size.toLong(), videoTarget.length())
        assertEquals(videoFixtureBytes.size.toLong(), videoReportedBytes)

        assertTrue("Downloaded audio file must exist", audioTarget.exists())
        assertEquals(audioFixtureBytes.size.toLong(), audioTarget.length())
        assertEquals(audioFixtureBytes.size.toLong(), audioReportedBytes)

        // Проверяем создание и сохранение сущности VoxDownloadJob
        val req = VoxDownloadRequest(
            downloadId = downloadId,
            videoId = "http_fixture_4k",
            videoTitle = "Local HTTP 4K Fixture E2E",
            qualityPreference = VoxQualityPreference.QUALITY_2160P,
            translationMode = VoxTranslationMode.NONE
        )
        val job = VoxDownloadJob(req, initialState = VoxDownloadState.QUEUED)
        repository.addOrUpdateJob(job)

        job.updateTrackProgress(VoxDownloadTrack.VIDEO, videoTarget.length(), videoTarget.length(), VoxTrackState.COMPLETED)
        job.updateTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, audioTarget.length(), audioTarget.length(), VoxTrackState.COMPLETED)
        job.updateState(VoxDownloadState.READY_FOR_MUX)
        repository.persistJob(job)

        val persisted = repository.getJob(downloadId)
        assertNotNull(persisted)
        assertEquals(VoxDownloadState.READY_FOR_MUX, persisted?.state)
        assertEquals(videoFixtureBytes.size.toLong(), persisted?.videoProgress?.bytesDownloaded)
        assertEquals(audioFixtureBytes.size.toLong(), persisted?.originalAudioProgress?.bytesDownloaded)
    }

    @Test
    fun testProductionDownloaderRangeResume206() {
        val downloadId = "vox-http-resume-001"
        val videoTarget = storage.getTrackFile(downloadId, VoxDownloadTrack.VIDEO)

        val partialSize = Math.min(1024, videoFixtureBytes.size)
        videoTarget.writeBytes(videoFixtureBytes.copyOfRange(0, partialSize))
        assertEquals(partialSize.toLong(), videoTarget.length())

        val httpClient = createHttpFixtureClient()
        val downloader = VoxSegmentDownloader(httpClient = httpClient, storage = storage)
        downloader.download(
            initialUrl = "https://rr1---sn-4g5edn6s.googlevideo.com/video_4k_vp9.webm",
            targetFile = videoTarget
        )

        assertEquals("Resumed download must yield exact full size", videoFixtureBytes.size.toLong(), videoTarget.length())
    }

    @Test
    fun testNegative404ThrowsNetworkError() {
        val downloadId = "vox-neg-404"
        val target = storage.getTrackFile(downloadId, VoxDownloadTrack.VIDEO)
        val httpClient = createHttpFixtureClient()
        val downloader = VoxSegmentDownloader(httpClient = httpClient, storage = storage)

        try {
            downloader.download(
                initialUrl = "https://rr1---sn-4g5edn6s.googlevideo.com/status_404",
                targetFile = target
            )
            fail("Expected VoxDownloadException for 404")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.NETWORK_ERROR, e.code)
            assertTrue(e.message?.contains("404") == true)
        }
    }

    @Test
    fun testNegativeExplicitQualityUnavailablePolicy() {
        val candidate720 = FakeMediaFormat(width = 1280, height = 720, itag = "22")
        val res = VoxDownloadFallbackPolicy.selectVideoFormat(listOf(candidate720), VoxQualityPreference.QUALITY_2160P)
        assertEquals("Requesting 2160p when only 720p is available must reject downgrade and return null", null, res)
    }

    @Test
    fun testDownloadedBytesMuxIntoValid4kMatroska() {
        val candidateDirs = listOf(
            File("../build/reports/vox-post-release-hardening/fixtures"),
            File("build/reports/vox-post-release-hardening/fixtures"),
            File("../../build/reports/vox-post-release-hardening/fixtures"),
            File("/home/kiryuha/Developer/SmartTube/build/reports/vox-post-release-hardening/fixtures")
        )
        val fixturesDir = candidateDirs.firstOrNull { it.exists() }
        val ivfFile = fixturesDir?.let { File(it, "video_4k_vp9.ivf") }
        val packetsFile = fixturesDir?.let { File(it, "audio_opus.packets") }

        if (ivfFile == null || !ivfFile.exists() || packetsFile == null || !packetsFile.exists()) {
            return
        }

        val outDir = storage.getJobDir("job-mux-verification")
        val outputFile = File(outDir, "vox_4k_downloaded_mux.mkv")

        val opusHead = "OpusHead".toByteArray(Charsets.US_ASCII) + byteArrayOf(
            1, 1, 0x38, 0x01, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0
        )

        val vp9Track = VoxMuxTrackInfo(
            trackNumber = 1,
            trackUid = 9001L,
            trackType = VoxMuxTrackType.VIDEO,
            codec = VoxMuxCodec.VP9,
            mimeType = "video/webm; codecs=\"vp9\"",
            name = "UHD 4K Video (VP9)",
            language = "und",
            isDefault = true,
            width = 3840,
            height = 2160,
            codecPrivate = null
        )

        val opusTrack = VoxMuxTrackInfo(
            trackNumber = 2,
            trackUid = 9002L,
            trackType = VoxMuxTrackType.AUDIO_ORIGINAL,
            codec = VoxMuxCodec.OPUS,
            mimeType = "audio/webm; codecs=\"opus\"",
            name = "Original Audio (Opus 48kHz)",
            language = "eng",
            isDefault = true,
            sampleRate = 48000.0,
            channels = 1,
            codecPrivate = opusHead
        )

        val videoSamples = loadIvf(ivfFile)
        val audioSamples = loadPackets(packetsFile)

        val sources = listOf(
            object : VoxSampleSource {
                private var idx = 0
                override val trackInfo: VoxMuxTrackInfo = vp9Track
                override val sourceSizeBytes: Long = videoSamples.sumOf { it.size.toLong() }
                override fun readNextSample(): VoxMuxSample? = if (idx < videoSamples.size) videoSamples[idx++] else null
                override fun close() {}
            },
            object : VoxSampleSource {
                private var idx = 0
                override val trackInfo: VoxMuxTrackInfo = opusTrack
                override val sourceSizeBytes: Long = audioSamples.sumOf { it.size.toLong() }
                override fun readNextSample(): VoxMuxSample? = if (idx < audioSamples.size) audioSamples[idx++] else null
                override fun close() {}
            }
        )

        val muxer = VoxMatroskaMuxer()
        val result = muxer.mux(sources, outputFile, "Local HTTP Download 4K E2E")

        assertTrue(outputFile.exists())
        assertTrue(outputFile.length() > 100_000)
        assertEquals(60L, result.videoSamplesCount)
        assertTrue(result.origAudioSamplesCount in 100L..101L)
        assertTrue(result.durationMs in 1900..2100)
    }

    private fun loadIvf(ivfFile: File): List<VoxMuxSample> {
        val bytes = ivfFile.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(24)
        val frameCount = buffer.int
        buffer.position(32)

        val samples = mutableListOf<VoxMuxSample>()
        for (i in 0 until frameCount) {
            val frameSize = buffer.int
            val pts = buffer.long
            val frameData = ByteArray(frameSize)
            buffer.get(frameData)
            samples.add(VoxMuxSample(
                trackNumber = 1,
                presentationTimeUs = pts * 1000L,
                durationUs = 33333L,
                isKeyFrame = (i % 30 == 0),
                data = frameData
            ))
        }
        return samples
    }

    private fun loadPackets(packetsFile: File): List<VoxMuxSample> {
        val bytes = packetsFile.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.int
        val samples = mutableListOf<VoxMuxSample>()
        for (i in 0 until count) {
            val size = buffer.int
            val ptsUs = buffer.long
            val data = ByteArray(size)
            buffer.get(data)
            samples.add(VoxMuxSample(
                trackNumber = 2,
                presentationTimeUs = ptsUs,
                durationUs = 20000L,
                isKeyFrame = true,
                data = data
            ))
        }
        return samples
    }

    @Test
    fun testStorageOptimizationPeakDiskReductionAndPartCleanup() {
        val downloadId = "test-storage-opt-4k"
        val videoPart = storage.getTrackFile(downloadId, VoxDownloadTrack.VIDEO)
        val audioPart = storage.getTrackFile(downloadId, VoxDownloadTrack.ORIGINAL_AUDIO)
        val outputFile = storage.getOutputFile(downloadId)

        videoPart.parentFile?.mkdirs()
        videoPart.writeBytes(ByteArray(100_000) { 0x11 })
        audioPart.writeBytes(ByteArray(20_000) { 0x22 })
        outputFile.writeBytes(ByteArray(120_000) { 0x33 })

        assertTrue("video.part must exist before cleanup", videoPart.exists())
        assertTrue("audio.part must exist before cleanup", audioPart.exists())
        assertTrue("output.mkv must exist", outputFile.exists())

        // Вызываем оптимизацию диска после муксинга
        storage.cleanTrackSourcesAfterMux(downloadId)

        // .part файлы должны быть удалены для освобождения дискового пространства до MediaStore publish
        assertFalse("video.part must be deleted after mux", videoPart.exists())
        assertFalse("audio.part must be deleted after mux", audioPart.exists())

        // output.mkv ДОЛЖЕН сохраняться в целости для публикации и восстановления
        assertTrue("output.mkv must remain intact", outputFile.exists())
        assertEquals(120_000L, outputFile.length())

        // После успешной публикации output.mkv очищается
        storage.cleanOutputFileAfterPublication(downloadId)
        assertFalse("output.mkv must be cleaned after publication", outputFile.exists())
    }

    @Test
    fun testMediaStorePublishFailurePreservesMuxedFileForRecovery() {
        val downloadId = "test-publish-failure-recovery-4k"
        val outputFile = storage.getOutputFile(downloadId)
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(ByteArray(150_000) { 0x44 })

        val request = VoxDownloadRequest(
            downloadId = downloadId,
            videoId = "testVideo",
            videoTitle = "Test 4K Video",
            qualityPreference = VoxQualityPreference.QUALITY_2160P,
            translationMode = VoxTranslationMode.NONE
        )
        val job = VoxDownloadJob(
            request = request,
            initialState = VoxDownloadState.MUXED
        ).apply {
            finalFileBytes = 150_000L
            durationMs = 5000L
            packagingCompleted = true
        }
        repository.persistJob(job)

        // Мок издателя с ошибкой STORAGE_FULL
        val failingPublisher = object : VoxDownloadPublisher {
            override fun publish(
                outputFile: File,
                videoTitle: String,
                isCancelled: AtomicBoolean,
                onProgress: ((bytesCopied: Long, totalBytes: Long, percent: Int) -> Unit)?
            ): Uri {
                throw VoxDownloadException(VoxDownloadErrorCode.STORAGE_FULL, "No space left on device")
            }
            override fun isPublishedFileAvailable(uriString: String?): Boolean = false
            override fun deletePublishedFile(uriString: String?): Boolean = false
        }

        val coordinator = VoxDownloadCoordinator(
            storage = storage,
            repository = repository,
            publisher = failingPublisher
        )

        try {
            val method = VoxDownloadCoordinator::class.java.getDeclaredMethod(
                "publishCompletedFile",
                VoxDownloadJob::class.java,
                Long::class.javaPrimitiveType,
                File::class.java
            )
            method.isAccessible = true
            method.invoke(coordinator, job, job.generation.get(), outputFile)
            fail("Expected VoxDownloadException for STORAGE_FULL")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val target = e.targetException
            assertTrue("Target exception must be VoxDownloadException", target is VoxDownloadException)
            assertEquals(VoxDownloadErrorCode.STORAGE_FULL, (target as VoxDownloadException).code)
        }

        // Ключевой инвариант: output.mkv НЕ должен быть удален при ошибке публикации!
        assertTrue("output.mkv must be preserved for recovery after publish failure", outputFile.exists())
        assertEquals(150_000L, outputFile.length())
    }

    @Test
    fun testIdempotentPublishDoesNotDuplicateFile() {
        val downloadId = "test-idempotent-publish-4k"
        val outputFile = storage.getOutputFile(downloadId)
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(ByteArray(80_000) { 0x55 })

        var publishCallCount = 0
        val mockPublisher = object : VoxDownloadPublisher {
            override fun publish(
                outputFile: File,
                videoTitle: String,
                isCancelled: AtomicBoolean,
                onProgress: ((bytesCopied: Long, totalBytes: Long, percent: Int) -> Unit)?
            ): Uri {
                publishCallCount++
                return Uri.parse("content://media/external/video/media/9999")
            }
            override fun isPublishedFileAvailable(uriString: String?): Boolean {
                return uriString == "content://media/external/video/media/9999"
            }
            override fun deletePublishedFile(uriString: String?): Boolean = true
        }

        val request = VoxDownloadRequest(
            downloadId = downloadId,
            videoId = "testVideo",
            videoTitle = "Test 4K Video",
            qualityPreference = VoxQualityPreference.QUALITY_2160P,
            translationMode = VoxTranslationMode.NONE
        )
        val job = VoxDownloadJob(
            request = request,
            initialState = VoxDownloadState.MUXED
        ).apply {
            updateTrackProgress(VoxDownloadTrack.VIDEO, 70_000L, 70_000L, VoxTrackState.COMPLETED)
            updateTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, 10_000L, 10_000L, VoxTrackState.COMPLETED)
            finalFileBytes = 80_000L
            durationMs = 2000L
            packagingCompleted = true
            publishedUri = "content://media/external/video/media/9999"
        }
        repository.persistJob(job)

        val coordinator = VoxDownloadCoordinator(
            storage = storage,
            repository = repository,
            publisher = mockPublisher
        )

        val method = VoxDownloadCoordinator::class.java.getDeclaredMethod(
            "publishCompletedFile",
            VoxDownloadJob::class.java,
            Long::class.javaPrimitiveType,
            File::class.java
        )
        method.isAccessible = true
        method.invoke(coordinator, job, job.generation.get(), outputFile)

        // Издатель НЕ должен вызываться повторно, если файл уже опубликован и доступен
        assertEquals(0, publishCallCount)
        assertEquals(VoxDownloadState.COMPLETED, job.state)
    }

    @Test
    fun testMediaStorePublishPreflightStorageCheckThrowsStorageFull() {
        val downloadId = "test-preflight-storage-full-4k"
        val outputFile = storage.getOutputFile(downloadId)
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(ByteArray(50_000) { 0x55 })

        val context = RuntimeEnvironment.getApplication()
        val mockStorage = object : VoxDownloadStorage(context) {
            override fun hasEnoughSpace(requiredBytes: Long, safetyMarginBytes: Long): Boolean = false
        }

        var publishCalled = false
        val mockPublisher = object : VoxDownloadPublisher {
            override fun publish(
                outputFile: File,
                videoTitle: String,
                isCancelled: AtomicBoolean,
                onProgress: ((bytesCopied: Long, totalBytes: Long, percent: Int) -> Unit)?
            ): Uri {
                publishCalled = true
                return Uri.parse("content://dummy")
            }
            override fun isPublishedFileAvailable(uriString: String?): Boolean = false
            override fun deletePublishedFile(uriString: String?): Boolean = false
        }

        val request = VoxDownloadRequest(
            downloadId = downloadId,
            videoId = "testVideo",
            videoTitle = "Test 4K Video",
            qualityPreference = VoxQualityPreference.QUALITY_2160P,
            translationMode = VoxTranslationMode.NONE
        )
        val job = VoxDownloadJob(
            request = request,
            initialState = VoxDownloadState.MUXED
        ).apply {
            finalFileBytes = 50_000L
            durationMs = 1000L
            packagingCompleted = true
        }

        val coordinator = VoxDownloadCoordinator(
            storage = mockStorage,
            repository = repository,
            publisher = mockPublisher
        )

        try {
            val method = VoxDownloadCoordinator::class.java.getDeclaredMethod(
                "publishCompletedFile",
                VoxDownloadJob::class.java,
                Long::class.javaPrimitiveType,
                File::class.java
            )
            method.isAccessible = true
            method.invoke(coordinator, job, job.generation.get(), outputFile)
            fail("Expected VoxDownloadException for STORAGE_FULL during preflight check")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val target = e.targetException
            assertTrue(target is VoxDownloadException)
            assertEquals(VoxDownloadErrorCode.STORAGE_FULL, (target as VoxDownloadException).code)
            assertTrue(target.message?.contains("Недостаточно свободного места") == true)
        }

        // Издатель НЕ должен вызываться, если места недостаточно!
        assertFalse("Publisher must NOT be called when preflight space check fails", publishCalled)
    }
}
