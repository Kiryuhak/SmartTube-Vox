package com.liskovsoft.smartyoutubetv2.common.vox.translation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Ограниченная очередь сегментов перевода (Bounded Translation Queue).
 * Гарантирует:
 * - Сохранение последовательности сегментов (sequence);
 * - Дедупликацию (один и тот же сегмент не обрабатывается дважды);
 * - Инвалидацию при перемотке (seek) и смене поколения (generationId);
 * - Очистку от устаревших сегментов для защиты от утечек памяти;
 * - Корректную отмену активных задач без висячих процессов (orphan jobs).
 */
class VoxTranslationQueue(
    val maxCapacity: Int = 50
) {
    private val lock = Any()
    // Sequence -> Segment
    private val sequenceMap = ConcurrentSkipListMap<Long, VoxTranslationSegment>()
    // SegmentId -> Sequence
    private val idIndex = ConcurrentHashMap<String, Long>()

    /**
     * Поставить сегмент в очередь.
     * @return true, если сегмент успешно добавлен; false, если он уже существует или очередь переполнена.
     */
    fun enqueue(segment: VoxTranslationSegment): Boolean {
        synchronized(lock) {
            // Дедупликация по segmentId и sequence
            if (idIndex.containsKey(segment.segmentId) || sequenceMap.containsKey(segment.sequence)) {
                return false
            }

            // Если достигнута максимальная емкость, вытесняем самые старые завершенные сегменты
            if (sequenceMap.size >= maxCapacity) {
                purgeOldestCompletedLocked()
            }

            if (sequenceMap.size >= maxCapacity) {
                return false // Емкость строго ограничена
            }

            segment.state = VoxSegmentState.QUEUED
            sequenceMap[segment.sequence] = segment
            idIndex[segment.segmentId] = segment.sequence
            return true
        }
    }

    /**
     * Извлечь следующий сегмент, ожидающий обработки (в порядке sequence).
     */
    fun pollNextPending(): VoxTranslationSegment? {
        synchronized(lock) {
            for ((_, segment) in sequenceMap) {
                if (segment.state == VoxSegmentState.QUEUED || segment.state == VoxSegmentState.PENDING) {
                    segment.state = VoxSegmentState.TRANSLATING
                    return segment
                }
            }
            return null
        }
    }

    /**
     * Обновить статус сегмента по его идентификатору.
     */
    fun updateSegmentState(segmentId: String, newState: VoxSegmentState, translatedRef: String? = null, error: VoxTranslationError? = null) {
        synchronized(lock) {
            val seq = idIndex[segmentId] ?: return
            val seg = sequenceMap[seq] ?: return
            seg.state = newState
            if (translatedRef != null) {
                seg.translatedDataRef = translatedRef
            }
            if (error != null) {
                seg.error = error
            }
            if (newState == VoxSegmentState.READY) {
                seg.completedAtMs = System.currentTimeMillis()
            }
        }
    }

    /**
     * Инвалидация при перемотке (Seek):
     * Помечает устаревшими все сегменты предыдущих поколений или сегменты позади новой позиции.
     */
    fun invalidateStale(currentGenerationId: Long, currentPosMs: Long): Int {
        synchronized(lock) {
            var invalidatedCount = 0
            val iterator = sequenceMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val seg = entry.value
                if (seg.generationId != currentGenerationId || seg.sourceEndMs < currentPosMs) {
                    seg.state = VoxSegmentState.DISCARDED
                    idIndex.remove(seg.segmentId)
                    iterator.remove()
                    invalidatedCount++
                }
            }
            return invalidatedCount
        }
    }

    /**
     * Полная отмена очереди (при смене видео или остановке сессии).
     */
    fun cancelAll(): List<VoxTranslationSegment> {
        synchronized(lock) {
            val cancelled = mutableListOf<VoxTranslationSegment>()
            for ((_, seg) in sequenceMap) {
                if (seg.state == VoxSegmentState.QUEUED || seg.state == VoxSegmentState.TRANSLATING) {
                    seg.state = VoxSegmentState.DISCARDED
                    cancelled.add(seg)
                }
            }
            sequenceMap.clear()
            idIndex.clear()
            return cancelled
        }
    }

    fun size(): Int = sequenceMap.size

    fun getPendingCount(): Int {
        synchronized(lock) {
            return sequenceMap.values.count { it.state == VoxSegmentState.QUEUED || it.state == VoxSegmentState.PENDING }
        }
    }

    fun getReadyCount(): Int {
        synchronized(lock) {
            return sequenceMap.values.count { it.state == VoxSegmentState.READY }
        }
    }

    fun containsSegment(segmentId: String): Boolean = idIndex.containsKey(segmentId)

    fun getDepth(): Int = sequenceMap.size

    fun getQueuedDurationMs(): Long {
        synchronized(lock) {
            return sequenceMap.values
                .filter { it.state == VoxSegmentState.QUEUED || it.state == VoxSegmentState.TRANSLATING || it.state == VoxSegmentState.PENDING }
                .sumOf { it.durationMs }
        }
    }

    fun getSegment(sequence: Long): VoxTranslationSegment? = sequenceMap[sequence]

    fun remove(sequence: Long): VoxTranslationSegment? {
        synchronized(lock) {
            val seg = sequenceMap.remove(sequence)
            if (seg != null) {
                idIndex.remove(seg.segmentId)
            }
            return seg
        }
    }

    private fun purgeOldestCompletedLocked() {
        val iterator = sequenceMap.entries.iterator()
        while (iterator.hasNext() && sequenceMap.size >= maxCapacity) {
            val entry = iterator.next()
            if (entry.value.state == VoxSegmentState.PLAYED || entry.value.state == VoxSegmentState.DISCARDED || entry.value.state == VoxSegmentState.FAILED) {
                idIndex.remove(entry.value.segmentId)
                iterator.remove()
            }
        }
    }
}
