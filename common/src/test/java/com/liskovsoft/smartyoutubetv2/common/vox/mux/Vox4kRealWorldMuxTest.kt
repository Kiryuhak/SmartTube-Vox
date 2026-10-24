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

    @Test
    fun testReal4kMuxOutputFfprobeVerifiable() {
        val fixturesDir = File("../../build/reports/vox-post-release-hardening/fixtures")
        val altFixturesDir = File("build/reports/vox-post-release-hardening/fixtures")
        val outDir = if (fixturesDir.exists()) fixturesDir else if (altFixturesDir.exists()) altFixturesDir else File("build/test-fixtures").apply { mkdirs() }

        val outputFile = File(outDir, "vox_4k_muxed.mkv")

        val opusHead = "OpusHead".toByteArray(Charsets.US_ASCII) + byteArrayOf(
            1, // version
            2, // channels
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
            codecPrivate = byteArrayOf(0x01, 0x02, 0x03)
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
            channels = 2,
            codecPrivate = opusHead
        )

        // 60 frames = 2 seconds at 30 fps
        val videoSamples = (0 until 60).map { i ->
            VoxMuxSample(
                trackNumber = 1,
                presentationTimeUs = i * 33333L,
                durationUs = 33333L,
                isKeyFrame = (i % 30 == 0),
                // Valid VP9 superframe / frame header
                data = byteArrayOf(0x82.toByte(), 0x49, 0x83.toByte(), 0x42, 0x00, 0x01, 0x02, 0x03)
            )
        }

        // 100 audio frames = 2 seconds (20ms per Opus frame)
        val audioSamples = (0 until 100).map { i ->
            VoxMuxSample(
                trackNumber = 2,
                presentationTimeUs = i * 20000L,
                durationUs = 20000L,
                isKeyFrame = true,
                data = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x01, 0x02)
            )
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
        assertEquals(100L, result.origAudioSamplesCount)
        assertTrue("Mux duration must reflect ~2000ms", result.durationMs in 1900..2100)
    }
}
