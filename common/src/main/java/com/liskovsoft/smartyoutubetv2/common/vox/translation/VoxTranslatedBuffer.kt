package com.liskovsoft.smartyoutubetv2.common.vox.translation

import java.util.concurrent.ConcurrentSkipListMap
import kotlin.math.max

/**
 * Буфер готовых переведенных сегментов звука с адаптацией под скорость воспроизведения.
 */
class VoxTranslatedBuffer(
    var targetBufferMs: Long = 18_000L,
    var minimumPlayableBufferMs: Long = 4_000L,
    var maxBufferMs: Long = 60_000L
) {
    private val lock = Any()
    // StartMs -> VoxTranslationSegment
    private val readySegments = ConcurrentSkipListMap<Long, VoxTranslationSegment>()
    private var playbackSpeed: Float = 1.0f
    private var lastRecordedLatencyMs: Long = 0L

    fun setPlaybackSpeed(speed: Float) {
        synchronized(lock) {
            this.playbackSpeed = speed.coerceIn(0.5f, 3.0f)
        }
    }

    fun getPlaybackSpeed(): Float = playbackSpeed

    /**
     * Эффективный минимальный буфер с учётом расхода звука на повышенной скорости.
     * Например, при скорости 2.0x звук расходуется в 2 раза быстрее,
     * поэтому порог минимального буфера пропорционально масштабируется.
     */
    fun getEffectiveMinimumBufferMs(): Long {
        synchronized(lock) {
            val factor = max(1.0f, playbackSpeed)
            return (minimumPlayableBufferMs * factor).toLong()
        }
    }

    fun addReadySegment(segment: VoxTranslationSegment) {
        synchronized(lock) {
            if (segment.state == VoxSegmentState.READY) {
                readySegments[segment.sourceStartMs] = segment
                if (segment.completedAtMs > segment.requestedAtMs) {
                    lastRecordedLatencyMs = segment.completedAtMs - segment.requestedAtMs
                }
            }
        }
    }

    /**
     * Вычислить непрерывный запас готового переведённого аудио от текущей позиции воспроизведения (мс).
     */
    fun getBufferedAheadMs(currentPlaybackPosMs: Long): Long {
        synchronized(lock) {
            var continuousHorizon = currentPlaybackPosMs
            for ((start, seg) in readySegments) {
                if (seg.state != VoxSegmentState.READY) continue
                if (start <= continuousHorizon && seg.sourceEndMs > continuousHorizon) {
                    continuousHorizon = seg.sourceEndMs
                } else if (start > continuousHorizon && continuousHorizon == currentPlaybackPosMs && start <= currentPlaybackPosMs + 2000L) {
                    continuousHorizon = seg.sourceEndMs
                }
            }
            return (continuousHorizon - currentPlaybackPosMs).coerceAtLeast(0L)
        }
    }

    fun hasSufficientBuffer(currentPlaybackPosMs: Long): Boolean {
        return getBufferedAheadMs(currentPlaybackPosMs) >= getEffectiveMinimumBufferMs()
    }

    fun isBufferCritical(currentPlaybackPosMs: Long): Boolean {
        val minBuf = getEffectiveMinimumBufferMs()
        return getBufferedAheadMs(currentPlaybackPosMs) < (minBuf / 2).coerceAtLeast(1500L)
    }

    fun clear() {
        synchronized(lock) {
            readySegments.clear()
        }
    }

    fun clearBefore(currentPosMs: Long) {
        synchronized(lock) {
            val cutoff = (currentPosMs - 15_000L).coerceAtLeast(0L)
            val toRemove = readySegments.headMap(cutoff).keys.toList()
            for (key in toRemove) {
                readySegments.remove(key)
            }
        }
    }

    fun getLastLatencyMs(): Long = lastRecordedLatencyMs
    fun getReadySegmentCount(): Int = readySegments.size
}
