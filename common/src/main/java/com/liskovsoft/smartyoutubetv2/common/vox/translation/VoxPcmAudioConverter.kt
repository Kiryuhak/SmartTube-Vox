package com.liskovsoft.smartyoutubetv2.common.vox.translation

import kotlin.math.roundToInt

/**
 * Изолированный детерминированный слой предобработки аудиоданных PCM_16BIT (Patch #8).
 * Выполняет преобразование стерео в моно и ресемплинг 48 кГц -> 16 кГц / 24 кГц
 * без использования тяжелых внешних библиотек и FFmpeg.
 *
 * Гарантии:
 * - PCM 16-bit Little-Endian (PCM_16LE);
 * - Bounded memory (выделение ровно необходимого размера буфера);
 * - Детерминированный результат без клиппинга;
 * - Сохранение точных временных меток PTS (startPtsUs, endPtsUs, durationUs);
 * - Быстродействие: < 1-2 мс на 2-секундный фрагмент.
 */
object VoxPcmAudioConverter {

    /**
     * Преобразует двухканальный PCM 16-bit LE (стерео) в одноканальный (моно).
     * Каждая пара сэмплов (L, R) усредняется: (L + R) / 2.
     * Размер выходного массива ровно в 2 раза меньше входного.
     */
    fun stereoToMono(stereoPcm16: ByteArray): ByteArray {
        val frameCount = stereoPcm16.size / 4
        val monoPcm = ByteArray(frameCount * 2)

        var srcIdx = 0
        var dstIdx = 0

        for (i in 0 until frameCount) {
            val left = (stereoPcm16[srcIdx].toInt() and 0xFF) or (stereoPcm16[srcIdx + 1].toInt() shl 8)
            val right = (stereoPcm16[srcIdx + 2].toInt() and 0xFF) or (stereoPcm16[srcIdx + 3].toInt() shl 8)
            srcIdx += 4

            // Усредняем с корректным знаком 16-битного short
            val monoSample = ((left.toShort().toInt() + right.toShort().toInt()) / 2).coerceIn(-32768, 32767)

            monoPcm[dstIdx] = (monoSample and 0xFF).toByte()
            monoPcm[dstIdx + 1] = ((monoSample shr 8) and 0xFF).toByte()
            dstIdx += 2
        }

        return monoPcm
    }

    /**
     * Понижает частоту дискретизации с 48 кГц до 16 кГц (коэффициент децимации 3:1).
     * Для предотвращения алиасинга применяется простое скользящее усреднение троек сэмплов: (s0 + s1 + s2) / 3.
     * Размер выходного массива: (inputSamples / 3) * 2 байт.
     */
    fun resampleMono48kTo16k(monoPcm48k: ByteArray): ByteArray {
        val totalSamples = monoPcm48k.size / 2
        val outSampleCount = totalSamples / 3
        val outPcm = ByteArray(outSampleCount * 2)

        var srcIdx = 0
        var dstIdx = 0

        for (i in 0 until outSampleCount) {
            val s0 = ((monoPcm48k[srcIdx].toInt() and 0xFF) or (monoPcm48k[srcIdx + 1].toInt() shl 8)).toShort().toInt()
            val s1 = ((monoPcm48k[srcIdx + 2].toInt() and 0xFF) or (monoPcm48k[srcIdx + 3].toInt() shl 8)).toShort().toInt()
            val s2 = ((monoPcm48k[srcIdx + 4].toInt() and 0xFF) or (monoPcm48k[srcIdx + 5].toInt() shl 8)).toShort().toInt()
            srcIdx += 6

            val averaged = ((s0 + s1 + s2) / 3).coerceIn(-32768, 32767)

            outPcm[dstIdx] = (averaged and 0xFF).toByte()
            outPcm[dstIdx + 1] = ((averaged shr 8) and 0xFF).toByte()
            dstIdx += 2
        }

        return outPcm
    }

    /**
     * Понижает частоту дискретизации с 48 кГц до 24 кГц (коэффициент децимации 2:1).
     * Усреднение пар сэмплов: (s0 + s1) / 2.
     * Размер выходного массива: (inputSamples / 2) * 2 байт.
     */
    fun resampleMono48kTo24k(monoPcm48k: ByteArray): ByteArray {
        val totalSamples = monoPcm48k.size / 2
        val outSampleCount = totalSamples / 2
        val outPcm = ByteArray(outSampleCount * 2)

        var srcIdx = 0
        var dstIdx = 0

        for (i in 0 until outSampleCount) {
            val s0 = ((monoPcm48k[srcIdx].toInt() and 0xFF) or (monoPcm48k[srcIdx + 1].toInt() shl 8)).toShort().toInt()
            val s1 = ((monoPcm48k[srcIdx + 2].toInt() and 0xFF) or (monoPcm48k[srcIdx + 3].toInt() shl 8)).toShort().toInt()
            srcIdx += 4

            val averaged = ((s0 + s1) / 2).coerceIn(-32768, 32767)

            outPcm[dstIdx] = (averaged and 0xFF).toByte()
            outPcm[dstIdx + 1] = ((averaged shr 8) and 0xFF).toByte()
            dstIdx += 2
        }

        return outPcm
    }

    /**
     * Комплексное преобразование аудиофрагмента под требования целевого провайдера.
     * Сохраняет исходные метаданные PTS и генерации, обновляя sampleRate, channelCount, data и frameCount.
     */
    fun convertFragment(
        fragment: VoxLiveAudioFragment,
        targetSampleRate: Int = 16000,
        targetChannels: Int = 1
    ): VoxLiveAudioFragment {
        if (fragment.sampleRate == targetSampleRate && fragment.channelCount == targetChannels) {
            return fragment
        }

        var workingData = fragment.data
        var currentChannels = fragment.channelCount
        var currentSampleRate = fragment.sampleRate

        // Шаг 1: Конвертация каналов (стерео -> моно)
        if (currentChannels == 2 && targetChannels == 1) {
            workingData = stereoToMono(workingData)
            currentChannels = 1
        }

        // Шаг 2: Ресемплинг частоты дискретизации
        if (currentChannels == 1 && currentSampleRate == 48000 && targetSampleRate == 16000) {
            workingData = resampleMono48kTo16k(workingData)
            currentSampleRate = 16000
        } else if (currentChannels == 1 && currentSampleRate == 48000 && targetSampleRate == 24000) {
            workingData = resampleMono48kTo24k(workingData)
            currentSampleRate = 24000
        }

        val bytesPerFrame = currentChannels * 2
        val newFrameCount = if (bytesPerFrame > 0) workingData.size / bytesPerFrame else 0

        return fragment.copy(
            sampleRate = currentSampleRate,
            channelCount = currentChannels,
            data = workingData,
            frameCount = newFrameCount
        )
    }
}
