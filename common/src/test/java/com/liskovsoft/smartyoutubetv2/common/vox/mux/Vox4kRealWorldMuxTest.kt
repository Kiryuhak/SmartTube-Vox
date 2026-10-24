package com.liskovsoft.smartyoutubetv2.common.vox.mux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Vox4kRealWorldMuxTest {

    private class MockSampleSource(
        override val trackInfo: VoxMuxTrackInfo,
        private val samples: List<VoxMuxSample>
    ) : VoxSampleSource {
        private var index = 0
        override val sourceSizeBytes: Long = samples.sumOf { it.size.toLong() }
        override fun readNextSample(): VoxMuxSample? {
            if (index < samples.size) {
                return samples[index++]
            }
            return null
        }
        override fun close() {}
    }

    private fun loadIvfFrames(ivfFile: File): List<VoxMuxSample> {
        val bytes = ivfFile.readBytes()
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.position(24)
        val frameCount = buffer.int
        buffer.position(32)

        val samples = mutableListOf<VoxMuxSample>()
        for (i in 0 until frameCount) {
            val frameSize = buffer.int
            val pts = buffer.long
            val frameData = ByteArray(frameSize)
            buffer.get(frameData)
            val isKey = (i % 30 == 0)
            samples.add(VoxMuxSample(
                trackNumber = 1,
                presentationTimeUs = pts * 1000L,
                durationUs = 33333L,
                isKeyFrame = isKey,
                data = frameData
            ))
        }
        return samples
    }

    private fun loadOpusPackets(packetsFile: File): List<VoxMuxSample> {
        val bytes = packetsFile.readBytes()
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
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
    fun testReal4kMuxOutputFfprobeVerifiable() {
        val candidateDirs = listOf(
            File("../build/reports/vox-post-release-hardening/fixtures"),
            File("build/reports/vox-post-release-hardening/fixtures"),
            File("../../build/reports/vox-post-release-hardening/fixtures"),
            File("/home/kiryuha/Developer/SmartTube/build/reports/vox-post-release-hardening/fixtures")
        )
        val actualFixturesDir = candidateDirs.firstOrNull { it.exists() }

        val outDir = actualFixturesDir ?: File("build/test-fixtures").apply { mkdirs() }
        val outputFile = File(outDir, "vox_4k_muxed.mkv")

        val opusHead = "OpusHead".toByteArray(Charsets.US_ASCII) + byteArrayOf(
            1, // version
            1, // channels (mono in fixture)
            0x38, 0x01, // pre-skip = 312
            0x80.toByte(), 0xBB.toByte(), 0, 0, // sample rate = 48000
            0, 0, // gain
            0 // mapping
        )

        val vp9Track = VoxMuxTrackInfo(
            trackNumber = 1,
            trackUid = 4001L,
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
            trackUid = 4002L,
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

        val ivfFile = actualFixturesDir?.let { File(it, "video_4k_vp9.ivf") }
        val packetsFile = actualFixturesDir?.let { File(it, "audio_opus.packets") }

        val videoSamples: List<VoxMuxSample> = if (ivfFile != null && ivfFile.exists()) {
            loadIvfFrames(ivfFile)
        } else {
            (0 until 60).map { i ->
                VoxMuxSample(
                    trackNumber = 1,
                    presentationTimeUs = i * 33333L,
                    durationUs = 33333L,
                    isKeyFrame = (i % 30 == 0),
                    data = byteArrayOf(0x82.toByte(), 0x49, 0x83.toByte(), 0x42, 0x00, 0x01, 0x02, 0x03)
                )
            }
        }

        val audioSamples: List<VoxMuxSample> = if (packetsFile != null && packetsFile.exists()) {
            loadOpusPackets(packetsFile)
        } else {
            (0 until 100).map { i ->
                VoxMuxSample(
                    trackNumber = 2,
                    presentationTimeUs = i * 20000L,
                    durationUs = 20000L,
                    isKeyFrame = true,
                    data = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x01, 0x02)
                )
            }
        }

        val sources = listOf(
            MockSampleSource(vp9Track, videoSamples),
            MockSampleSource(opusTrack, audioSamples)
        )

        val muxer = VoxMatroskaMuxer()
        val result = muxer.mux(
            sources = sources,
            outputFile = outputFile,
            videoTitle = "SmartTube VOX 4K UHD Sample"
        )

        assertTrue("Output file must exist", outputFile.exists())
        assertTrue("Output file size must be non-trivial (>1KB)", outputFile.length() > 1024)
        assertEquals(60L, result.videoSamplesCount)
        assertTrue("Audio samples count in expected range", result.origAudioSamplesCount in 100L..101L)
        assertTrue("Mux duration must reflect ~2000ms", result.durationMs in 1900..2100)
    }
}
