package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxPcmAudioConverterTest {

    @Test
    fun testStereoToMonoAveragesChannelsCorrectly() {
        // 2 фрейма стерео PCM 16-bit:
        // Фрейм 0: L = 1000, R = 3000 -> Mono = 2000
        // Фрейм 1: L = -4000, R = -2000 -> Mono = -3000
        val stereo = ByteArray(8)
        // L0 = 1000 (0x03E8)
        stereo[0] = 0xE8.toByte()
        stereo[1] = 0x03.toByte()
        // R0 = 3000 (0x0BB8)
        stereo[2] = 0xB8.toByte()
        stereo[3] = 0x0B.toByte()
        // L1 = -4000 (-4000 = 0xF060)
        stereo[4] = 0x60.toByte()
        stereo[5] = 0xF0.toByte()
        // R1 = -2000 (-2000 = 0xF830)
        stereo[6] = 0x30.toByte()
        stereo[7] = 0xF8.toByte()

        val mono = VoxPcmAudioConverter.stereoToMono(stereo)
        assertEquals(4, mono.size)

        val m0 = ((mono[0].toInt() and 0xFF) or (mono[1].toInt() shl 8)).toShort().toInt()
        val m1 = ((mono[2].toInt() and 0xFF) or (mono[3].toInt() shl 8)).toShort().toInt()

        assertEquals(2000, m0)
        assertEquals(-3000, m1)
    }

    @Test
    fun testResample48kTo16kDecimatesByThree() {
        // 6 сэмплов моно на 48кГц (12 байт):
        // Сэмплы 100, 200, 300 -> среднее 200
        // Сэмплы 600, 700, 800 -> среднее 700
        val mono48k = ByteArray(12)
        val samples = intArrayOf(100, 200, 300, 600, 700, 800)
        for (i in samples.indices) {
            val s = samples[i]
            mono48k[i * 2] = (s and 0xFF).toByte()
            mono48k[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }

        val mono16k = VoxPcmAudioConverter.resampleMono48kTo16k(mono48k)
        assertEquals(4, mono16k.size)

        val out0 = ((mono16k[0].toInt() and 0xFF) or (mono16k[1].toInt() shl 8)).toShort().toInt()
        val out1 = ((mono16k[2].toInt() and 0xFF) or (mono16k[3].toInt() shl 8)).toShort().toInt()

        assertEquals(200, out0)
        assertEquals(700, out1)
    }

    @Test
    fun testConvertFragmentPreservesDurationAndPts() {
        // Симулируем 2-секундный фрагмент: 48000 * 2 = 96000 фреймов стерео PCM 16-bit
        // 96000 * 4 = 384000 байт
        val sampleCount = 48000 * 2
        val rawData = ByteArray(sampleCount * 4) { i -> (i % 256).toByte() }

        val originalFragment = VoxLiveAudioFragment(
            sequence = 42L,
            generation = 7L,
            startPtsUs = 10_000_000L,
            endPtsUs = 12_000_000L,
            durationUs = 2_000_000L,
            sampleRate = 48000,
            channelCount = 2,
            encoding = 2, // AudioFormat.ENCODING_PCM_16BIT
            data = rawData,
            frameCount = sampleCount
        )

        val startTime = System.nanoTime()
        val converted = VoxPcmAudioConverter.convertFragment(
            originalFragment,
            targetSampleRate = 16000,
            targetChannels = 1
        )
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        assertNotNull(converted)
        assertEquals(42L, converted.sequence)
        assertEquals(7L, converted.generation)
        assertEquals(10_000_000L, converted.startPtsUs)
        assertEquals(12_000_000L, converted.endPtsUs)
        assertEquals(2_000_000L, converted.durationUs)

        assertEquals(16000, converted.sampleRate)
        assertEquals(1, converted.channelCount)

        // Для 2 секунд при 16 кГц моно должно получиться ровно 32000 сэмплов по 2 байта = 64000 байт
        assertEquals(32000, converted.frameCount)
        assertEquals(64000, converted.data.size)

        // Проверяем, что конвертация выполняется быстро (< 50 мс для полного 2-секундного буфера)
        assertTrue("Conversion took too long: ${elapsedMs}ms", elapsedMs < 50.0)
    }

    @Test
    fun testDeterministicOutput() {
        val rawData = ByteArray(1200) { (it * 7).toByte() }
        val f1 = VoxLiveAudioFragment(1, 1, 0, 1000, 1000, 48000, 2, 2, rawData, 300)

        val res1 = VoxPcmAudioConverter.convertFragment(f1, 16000, 1)
        val res2 = VoxPcmAudioConverter.convertFragment(f1, 16000, 1)

        assertTrue(res1.data.contentEquals(res2.data))
    }
}
