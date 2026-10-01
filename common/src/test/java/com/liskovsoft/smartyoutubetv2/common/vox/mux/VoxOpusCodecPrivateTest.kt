package com.liskovsoft.smartyoutubetv2.common.vox.mux

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VoxOpusCodecPrivateTest {
    @Test
    fun testBuildOpusHead() {
        val channels = 2
        val preSkip = 312
        val sampleRate = 48000
        val expected = byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
            1, // version
            2, // channels
            0x38, 0x01, // pre-skip = 312 (little endian)
            0x80.toByte(), 0xBB.toByte(), 0x00, 0x00, // sample rate = 48000 (little endian)
            0x00, 0x00, // output gain
            0x00 // channel mapping family
        )

        val actual = VoxMediaExtractorSource.buildOpusHead(channels, preSkip, sampleRate)
        assertArrayEquals(expected, actual)
    }
}
