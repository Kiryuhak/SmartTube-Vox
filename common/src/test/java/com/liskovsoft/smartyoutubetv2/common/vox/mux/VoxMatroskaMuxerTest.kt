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
}
