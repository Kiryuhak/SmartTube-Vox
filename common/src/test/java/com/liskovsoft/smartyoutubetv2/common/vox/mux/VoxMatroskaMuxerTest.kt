package com.liskovsoft.smartyoutubetv2.common.vox.mux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class VoxMatroskaMuxerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = tempFolder.newFolder("mux-test")
    }

    private class MockSampleSource(
        override val trackInfo: VoxMuxTrackInfo,
        private val samples: List<VoxMuxSample>
    ) : VoxSampleSource {
        private var index = 0
        override fun readNextSample(): VoxMuxSample? {
            if (index < samples.size) {
                return samples[index++]
            }
            return null
        }
        override fun close() {}
    }

    @Test
    fun testSynthetic3TrackMuxGeneratesValidMatroskaFile() {
        val videoTrack = VoxMuxTrackInfo(
            trackNumber = 1,
            trackUid = 1001L,
            trackType = VoxMuxTrackType.VIDEO,
            codec = VoxMuxCodec.AVC,
            mimeType = "video/avc",
            name = "Видео",
            language = "und",
            isDefault = true,
            width = 1280,
            height = 720,
            codecPrivate = byteArrayOf(1, 0x64, 0, 0x1F, 0xFF.toByte(), 0xE1.toByte(), 0, 4, 0x67, 0x64, 0, 0x1F, 1, 0, 4, 0x68, 0xEB.toByte(), 0xEC.toByte(), 0xB2.toByte())
        )

        val origAudioTrack = VoxMuxTrackInfo(
            trackNumber = 2,
            trackUid = 1002L,
            trackType = VoxMuxTrackType.AUDIO_ORIGINAL,
            codec = VoxMuxCodec.AAC,
            mimeType = "audio/mp4a-latm",
            name = "Оригинал",
            language = "und",
            isDefault = true,
            sampleRate = 44100.0,
            channels = 2,
            codecPrivate = byteArrayOf(0x12, 0x10)
        )

        val transAudioTrack = VoxMuxTrackInfo(
            trackNumber = 3,
            trackUid = 1003L,
            trackType = VoxMuxTrackType.AUDIO_TRANSLATED,
            codec = VoxMuxCodec.MP3,
            mimeType = "audio/mpeg",
            name = "Перевод",
            language = "rus",
            isDefault = false,
            sampleRate = 44100.0,
            channels = 2
        )

        val videoSamples = (0 until 30).map { i ->
            VoxMuxSample(
                trackNumber = 1,
                presentationTimeUs = i * 33333L, // 30 fps
                durationUs = 33333L,
                isKeyFrame = (i % 15 == 0),
                data = byteArrayOf(0, 0, 0, 4, 0x65, 0x88.toByte(), 0x84.toByte(), 0)
            )
        }

        val origAudioSamples = (0 until 40).map { i ->
            VoxMuxSample(
                trackNumber = 2,
                presentationTimeUs = i * 23220L,
                durationUs = 23220L,
                isKeyFrame = true,
                data = byteArrayOf(0x21, 0x00, 0x49, 0x90.toByte())
            )
        }

        val transAudioSamples = (0 until 38).map { i ->
            VoxMuxSample(
                trackNumber = 3,
                presentationTimeUs = i * 26122L,
                durationUs = 26122L,
                isKeyFrame = true,
                data = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
            )
        }

        val sources = listOf(
            MockSampleSource(videoTrack, videoSamples),
            MockSampleSource(origAudioTrack, origAudioSamples),
            MockSampleSource(transAudioTrack, transAudioSamples)
        )

        val outputFile = File(workDir, "output.mkv")
        val muxer = VoxMatroskaMuxer()
        val result = muxer.mux(
            sources = sources,
            outputFile = outputFile,
            videoTitle = "Test Mux Title"
        )

        assertTrue("Output file must exist", outputFile.exists())
        assertTrue("Output file must be non-empty", outputFile.length() > 500)
        assertEquals(30L, result.videoSamplesCount)
        assertEquals(40L, result.origAudioSamplesCount)
        assertEquals(38L, result.transAudioSamplesCount)

        // Проверяем заголовок EBML
        val bytes = ByteArray(64)
        FileInputStream(outputFile).use { it.read(bytes) }

        // 0x1A 0x45 0xDF 0xA3
        assertEquals(0x1A.toByte(), bytes[0])
        assertEquals(0x45.toByte(), bytes[1])
        assertEquals(0xDF.toByte(), bytes[2])
        assertEquals(0xA3.toByte(), bytes[3])

        // Проверяем наличие строки matroska в заголовке
        val headerStr = String(bytes, Charsets.ISO_8859_1)
        assertTrue("Header must contain 'matroska' DocType", headerStr.contains("matroska"))

        // Проверяем, что длительность была рассчитана и пропатчена (> 0)
        assertTrue("Duration must be > 0", result.durationMs > 0)
        assertEquals(966L, result.durationMs) // max timestamp 966ms
    }

    @Test
    fun testMuxProgressReaches100Percent() {
        val videoTrack = VoxMuxTrackInfo(
            trackNumber = 1,
            trackUid = 1001L,
            trackType = VoxMuxTrackType.VIDEO,
            codec = VoxMuxCodec.AVC,
            mimeType = "video/avc",
            name = "Видео",
            language = "und",
            isDefault = true,
            width = 640,
            height = 360
        )
        val videoSamples = (0 until 20).map { i ->
            VoxMuxSample(
                trackNumber = 1,
                presentationTimeUs = i * 33333L,
                durationUs = 33333L,
                isKeyFrame = (i == 0),
                data = byteArrayOf(0, 0, 0, 4, 0x65, 0x88.toByte(), 0x84.toByte(), 0)
            )
        }
        val sources = listOf(MockSampleSource(videoTrack, videoSamples))
        val outputFile = File(workDir, "progress_test.mkv")
        val muxer = VoxMatroskaMuxer()
        val recordedPercents = mutableListOf<Int>()

        muxer.mux(
            sources = sources,
            outputFile = outputFile,
            progressListener = { p ->
                recordedPercents.add(p.percent)
            }
        )

        assertTrue("Should have recorded progress events", recordedPercents.isNotEmpty())
        assertTrue("Progress should reach 100%", recordedPercents.last() == 100)
    }

    @Test
    fun testCancellationDeletesTmpFileAndAborts() {
        val videoTrack = VoxMuxTrackInfo(
            trackNumber = 1,
            trackUid = 1001L,
            trackType = VoxMuxTrackType.VIDEO,
            codec = VoxMuxCodec.AVC,
            mimeType = "video/avc",
            name = "Видео",
            language = "und",
            isDefault = true,
            width = 640,
            height = 360
        )

        val videoSamples = (0 until 100).map { i ->
            VoxMuxSample(
                trackNumber = 1,
                presentationTimeUs = i * 33333L,
                durationUs = 33333L,
                isKeyFrame = true,
                data = byteArrayOf(0, 0, 0, 2, 0x65, 0)
            )
        }

        val sources = listOf(MockSampleSource(videoTrack, videoSamples))
        val outputFile = File(workDir, "cancel_test.mkv")
        val tmpFile = File(workDir, "cancel_test.mkv.tmp")
        val isCancelled = AtomicBoolean(true) // Уже отменено
        val muxer = VoxMatroskaMuxer()

        var caught = false
        try {
            muxer.mux(
                sources = sources,
                outputFile = outputFile,
                isCancelled = isCancelled
            )
        } catch (e: InterruptedException) {
            caught = true
        }

        assertTrue("Should throw InterruptedException on cancellation", caught)
        assertFalse("Output file should not be created", outputFile.exists())
        assertFalse("Tmp file should be deleted on cancellation", tmpFile.exists())
    }

    @Test
    fun testVp9AndAv1TracksWriteCorrectMatroskaCodecIds() {
        val vp9Track = VoxMuxTrackInfo(
            trackNumber = 1,
            trackUid = 2001L,
            trackType = VoxMuxTrackType.VIDEO,
            codec = VoxMuxCodec.VP9,
            mimeType = "video/webm; codecs=\"vp9\"",
            name = "VP9 4K Video",
            language = "und",
            isDefault = true,
            width = 3840,
            height = 2160,
            codecPrivate = byteArrayOf(0x01, 0x02, 0x03)
        )

        val av1Track = VoxMuxTrackInfo(
            trackNumber = 2,
            trackUid = 2002L,
            trackType = VoxMuxTrackType.VIDEO,
            codec = VoxMuxCodec.AV1,
            mimeType = "video/mp4; codecs=\"av01.0.12M.08\"",
            name = "AV1 4K Video",
            language = "und",
            isDefault = false,
            width = 3840,
            height = 2160,
            codecPrivate = byteArrayOf(0x81.toByte(), 0x04, 0x00)
        )

        val opusTrack = VoxMuxTrackInfo(
            trackNumber = 3,
            trackUid = 2003L,
            trackType = VoxMuxTrackType.AUDIO_ORIGINAL,
            codec = VoxMuxCodec.OPUS,
            mimeType = "audio/webm; codecs=\"opus\"",
            name = "Opus Audio",
            language = "eng",
            isDefault = true,
            sampleRate = 48000.0,
            channels = 2,
            codecPrivate = "OpusHead".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 2, 0x38, 0x01, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        )

        val vp9Samples = (0 until 10).map { i ->
            VoxMuxSample(
                trackNumber = 1,
                presentationTimeUs = i * 33333L,
                durationUs = 33333L,
                isKeyFrame = (i == 0),
                data = byteArrayOf(0x82.toByte(), 0x49, 0x83.toByte(), 0x42)
            )
        }

        val av1Samples = (0 until 10).map { i ->
            VoxMuxSample(
                trackNumber = 2,
                presentationTimeUs = i * 33333L,
                durationUs = 33333L,
                isKeyFrame = (i == 0),
                data = byteArrayOf(0x12, 0x00, 0x0A, 0x0B)
            )
        }

        val opusSamples = (0 until 15).map { i ->
            VoxMuxSample(
                trackNumber = 3,
                presentationTimeUs = i * 20000L,
                durationUs = 20000L,
                isKeyFrame = true,
                data = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())
            )
        }

        val sources = listOf(
            MockSampleSource(vp9Track, vp9Samples),
            MockSampleSource(av1Track, av1Samples),
            MockSampleSource(opusTrack, opusSamples)
        )

        val outputFile = File(workDir, "vp9_av1_opus_test.mkv")
        val muxer = VoxMatroskaMuxer()
        val result = muxer.mux(
            sources = sources,
            outputFile = outputFile,
            videoTitle = "4K VP9 AV1 Opus Test"
        )

        assertTrue(outputFile.exists())
        assertTrue(result.durationMs > 0)
        assertEquals(20L, result.videoSamplesCount)
        assertEquals(15L, result.origAudioSamplesCount)

        val fileBytes = outputFile.readBytes()
        val fileString = String(fileBytes, Charsets.ISO_8859_1)

        assertTrue("Output MKV must contain V_VP9 CodecID", fileString.contains("V_VP9"))
        assertTrue("Output MKV must contain V_AV1 CodecID", fileString.contains("V_AV1"))
        assertTrue("Output MKV must contain A_OPUS CodecID", fileString.contains("A_OPUS"))
    }

    @Test
    fun testEmptySourcesThrowsIllegalArgumentException() {
        val outputFile = File(workDir, "empty_test.mkv")
        val muxer = VoxMatroskaMuxer()
        var caught = false
        try {
            muxer.mux(
                sources = emptyList(),
                outputFile = outputFile
            )
        } catch (e: IllegalArgumentException) {
            caught = true
        }
        assertTrue("Must throw IllegalArgumentException on empty sources", caught)
    }

    @Test
    fun testMuxDurationAndSeekCuesGenerated() {
        val videoTrack = VoxMuxTrackInfo(
            trackNumber = 1,
            trackUid = 3001L,
            trackType = VoxMuxTrackType.VIDEO,
            codec = VoxMuxCodec.AVC,
            mimeType = "video/avc",
            name = "Video",
            language = "und",
            isDefault = true,
            width = 1920,
            height = 1080
        )
        val videoSamples = (0 until 60).map { i ->
            VoxMuxSample(
                trackNumber = 1,
                presentationTimeUs = i * 33333L,
                durationUs = 33333L,
                isKeyFrame = (i % 30 == 0),
                data = byteArrayOf(0, 0, 0, 2, 0x65, 0x01)
            )
        }
        val outputFile = File(workDir, "cues_test.mkv")
        val muxer = VoxMatroskaMuxer()
        val result = muxer.mux(
            sources = listOf(MockSampleSource(videoTrack, videoSamples)),
            outputFile = outputFile
        )

        assertTrue(outputFile.exists())
        assertTrue("Duration must be in valid range", result.durationMs in 1900..2100)

        val fileBytes = outputFile.readBytes()
        var foundCues = false
        for (i in 0 until fileBytes.size - 4) {
            if (fileBytes[i] == 0x1C.toByte() &&
                fileBytes[i+1] == 0x53.toByte() &&
                fileBytes[i+2] == 0xBB.toByte() &&
                fileBytes[i+3] == 0x6B.toByte()) {
                foundCues = true
                break
            }
        }
        assertTrue("Matroska file must contain indexed Cues element for random-access seeking", foundCues)
    }
}
