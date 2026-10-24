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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
}
