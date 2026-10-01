package com.liskovsoft.smartyoutubetv2.common.vox.mux

import android.media.MediaFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class VoxOpusCodecPrivateTest {

    @Test
    fun testBuildOpusHead_ValidStereo() {
        val actual = VoxMediaExtractorSource.buildOpusHead(2, 312, 48000)
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
        assertArrayEquals(expected, actual)
    }

    @Test
    fun testBuildOpusHead_ValidMono() {
        val actual = VoxMediaExtractorSource.buildOpusHead(1, 100, 24000)
        val expected = byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
            1, // version
            1, // channels
            0x64, 0x00, // pre-skip = 100
            0xC0.toByte(), 0x5D.toByte(), 0x00, 0x00, // sample rate = 24000
            0x00, 0x00, 
            0x00 
        )
        assertArrayEquals(expected, actual)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBuildOpusHead_RejectsChannelsGreaterThan2() {
        VoxMediaExtractorSource.buildOpusHead(6, 312, 48000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBuildOpusHead_RejectsInvalidSampleRate() {
        VoxMediaExtractorSource.buildOpusHead(2, 312, -48000)
    }

    @Test
    fun testBuildOpusHead_ClampsPreSkipLowerBoundary() {
        val actual = VoxMediaExtractorSource.buildOpusHead(2, -100, 48000)
        assertEquals(0.toByte(), actual[10])
        assertEquals(0.toByte(), actual[11])
    }

    @Test
    fun testBuildOpusHead_ClampsPreSkipUpperBoundary() {
        val actual = VoxMediaExtractorSource.buildOpusHead(2, 100000, 48000)
        // 48000 = 0xBB80
        assertEquals(0x80.toByte(), actual[10])
        assertEquals(0xBB.toByte(), actual[11])
    }
    
    @Test
    fun testBuildCodecPrivate_PassthroughValidCsd0() {
        val format = MediaFormat()
        val validOpusHead = ByteArray(19) { it.toByte() }
        System.arraycopy("OpusHead".toByteArray(Charsets.US_ASCII), 0, validOpusHead, 0, 8)
        
        format.setByteBuffer("csd-0", ByteBuffer.wrap(validOpusHead))
        
        val actual = VoxMediaExtractorSource.buildCodecPrivate(VoxMuxCodec.OPUS, format)
        assertArrayEquals(validOpusHead, actual)
    }
    
    @Test
    fun testBuildCodecPrivate_SynthesizesFromCsd1Delay() {
        val format = MediaFormat()
        
        // 6,500,000 ns delay
        val delayNs = 6500000L
        val csd1Bytes = ByteArray(8)
        ByteBuffer.wrap(csd1Bytes).order(ByteOrder.nativeOrder()).putLong(delayNs)
        
        format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1Bytes))
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, 48000)
        
        val actual = VoxMediaExtractorSource.buildCodecPrivate(VoxMuxCodec.OPUS, format)
        
        // preSkip should be 312 (6.5ms * 48000Hz)
        assertEquals(0x38.toByte(), actual!![10])
        assertEquals(0x01.toByte(), actual[11])
    }
}
