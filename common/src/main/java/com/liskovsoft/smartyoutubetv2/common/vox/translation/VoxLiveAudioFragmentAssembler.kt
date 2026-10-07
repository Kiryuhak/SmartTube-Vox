package com.liskovsoft.smartyoutubetv2.common.vox.translation

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * Сборщик живых аудиофрагментов (Audio Fragment Assembler, Section 55).
 *
 * Принимает небольшие PCM-фреймы (~10-40 мс) из декодера ExoPlayer и объединяет их в фрагменты
 * целевой длительности (по умолчанию 2000 мс, диапазон 500-5000 мс) с сохранением меток PTS.
 *
 * Ограничения памяти и очереди (Memory Bounds):
 * - Максимальное число собранных фрагментов в очереди: 15
 * - Максимальный суммарный объем данных в очереди: 8 МБ
 * - При переполнении самый старый невычитанный фрагмент вытесняется (Drop oldest) с инкрементом счетчика.
 */
class VoxLiveAudioFragmentAssembler(
    initialTargetDurationUs: Long = 2_000_000L // 2.0 секунды
) {
    companion object {
        const val MIN_TARGET_DURATION_US = 500_000L   // 0.5 сек
        const val MAX_TARGET_DURATION_US = 5_000_000L // 5.0 сек
        const val MAX_QUEUED_FRAGMENTS = 15
        const val MAX_BUFFER_BYTES = 8 * 1024 * 1024L // 8 MB
    }

    private val lock = Any()
    private var _targetDurationUs: Long = initialTargetDurationUs
    private val readyFragments = ArrayDeque<VoxLiveAudioFragment>()

    // Накопление текущего неполного фрагмента
    private val currentDataStream = ByteArrayOutputStream()
    private var currentStartPtsUs: Long = -1L
    private var currentEndPtsUs: Long = -1L
    private var currentDurationUs: Long = 0L
    private var currentFrameCount: Int = 0
    private var currentSampleRate: Int = 48000
    private var currentChannelCount: Int = 2
    private var currentEncoding: Int = 2 // PCM 16-bit
    private var currentGeneration: Long = 1L
    private var nextSequence: Long = 0L

    // Метрики
    private var totalAssembledFragments: Long = 0L
    private var droppedFragmentCount: Int = 0
    private var currentTotalMemoryBytes: Long = 0L

    init {
        setTargetDurationUs(_targetDurationUs)
    }

    fun setTargetDurationUs(durationUs: Long) {
        synchronized(lock) {
            _targetDurationUs = durationUs.coerceIn(MIN_TARGET_DURATION_US, MAX_TARGET_DURATION_US)
        }
    }

    fun getTargetDurationUs(): Long = synchronized(lock) { _targetDurationUs }

    fun pushBuffer(buffer: VoxLiveCapturedBuffer) {
        synchronized(lock) {
            // Проверка смены поколения
            if (buffer.generation != currentGeneration) {
                resetPendingAccumulator()
                currentGeneration = buffer.generation
                nextSequence = 0L
            }

            if (currentStartPtsUs == -1L) {
                currentStartPtsUs = buffer.ptsUs
                currentSampleRate = buffer.sampleRate
                currentChannelCount = buffer.channelCount
                currentEncoding = buffer.encoding
            }

            currentDataStream.write(buffer.data)
            currentEndPtsUs = buffer.ptsUs + buffer.durationUs
            currentDurationUs += buffer.durationUs
            currentFrameCount++

            // Проверяем, достиг ли накопитель целевой длительности
            if (currentDurationUs >= _targetDurationUs) {
                flushCurrentFragment()
            }
        }
    }

    private fun flushCurrentFragment() {
        if (currentFrameCount == 0 || currentDataStream.size() == 0) return

        val fragmentData = currentDataStream.toByteArray()
        val fragment = VoxLiveAudioFragment(
            sequence = nextSequence++,
            generation = currentGeneration,
            startPtsUs = currentStartPtsUs,
            endPtsUs = currentEndPtsUs,
            durationUs = currentDurationUs,
            sampleRate = currentSampleRate,
            channelCount = currentChannelCount,
            encoding = currentEncoding,
            data = fragmentData,
            frameCount = currentFrameCount
        )

        enqueueFragment(fragment)
        resetPendingAccumulator()
    }

    private fun enqueueFragment(fragment: VoxLiveAudioFragment) {
        // Очистка при превышении лимитов очереди или памяти
        while (readyFragments.size >= MAX_QUEUED_FRAGMENTS ||
            (currentTotalMemoryBytes + fragment.data.size) > MAX_BUFFER_BYTES
        ) {
            if (readyFragments.isEmpty()) break
            val dropped = readyFragments.removeFirst()
            currentTotalMemoryBytes -= dropped.data.size
            droppedFragmentCount++
        }

        readyFragments.addLast(fragment)
        currentTotalMemoryBytes += fragment.data.size
        totalAssembledFragments++
    }

    private fun resetPendingAccumulator() {
        currentDataStream.reset()
        currentStartPtsUs = -1L
        currentEndPtsUs = -1L
        currentDurationUs = 0L
        currentFrameCount = 0
    }

    fun pollFragment(): VoxLiveAudioFragment? {
        synchronized(lock) {
            if (readyFragments.isEmpty()) return null
            val fragment = readyFragments.removeFirst()
            currentTotalMemoryBytes -= fragment.data.size
            return fragment
        }
    }

    fun peekFragment(): VoxLiveAudioFragment? {
        synchronized(lock) {
            return readyFragments.peekFirst()
        }
    }

    fun onDiscontinuity(newGeneration: Long) {
        synchronized(lock) {
            resetPendingAccumulator()
            currentGeneration = newGeneration
            nextSequence = 0L
        }
    }

    fun clear() {
        synchronized(lock) {
            resetPendingAccumulator()
            readyFragments.clear()
            currentTotalMemoryBytes = 0L
        }
    }

    fun getQueuedFragmentCount(): Int = synchronized(lock) { readyFragments.size }

    fun getCurrentMemoryBytes(): Long = synchronized(lock) { currentTotalMemoryBytes }

    fun getDroppedFragmentCount(): Int = synchronized(lock) { droppedFragmentCount }

    fun getAssembledFragmentCount(): Long = synchronized(lock) { totalAssembledFragments }

    fun getCurrentGeneration(): Long = synchronized(lock) { currentGeneration }
}
