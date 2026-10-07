package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Результат нормализации сырого аппаратного PTS.
 */
data class NormalizedPtsResult(
    val normalizedPtsUs: Long,
    val generation: Long,
    val isDiscontinuity: Boolean,
    val oldGeneration: Long
)

/**
 * Нормализатор аппаратных меток времени декодера (PTS Normalizer, Section 54).
 *
 * Преобразует сырые аппаратные PTS декодера ExoPlayer в сессионно-относительные таймстемпы,
 * стартующие с 0 для каждой непрерывной эпохи (generation).
 *
 * Автоматически отслеживает разрывы непрерывности (discontinuity):
 * 1. Прыжок вперед более чем на 10 секунд (live jump / catchup).
 * 2. Перемотка назад более чем на 500 миллисекунд (user seek back).
 *
 * При обнаружении разрыва инкрементирует generation, защищая пайплайн от артефактов и рассинхронизации.
 */
class VoxLiveAudioPtsNormalizer {

    companion object {
        const val DISCONTINUITY_FORWARD_THRESHOLD_US = 10_000_000L // 10 секунд вперед
        const val DISCONTINUITY_BACKWARD_THRESHOLD_US = -500_000L  // 500 мс назад (seek)
    }

    private var _basePtsUs: Long = -1L
    private var _lastRawPtsUs: Long = -1L
    private var _currentGeneration: Long = 1L

    @Synchronized
    fun normalize(rawPtsUs: Long): NormalizedPtsResult {
        if (_lastRawPtsUs != -1L) {
            val delta = rawPtsUs - _lastRawPtsUs
            if (delta > DISCONTINUITY_FORWARD_THRESHOLD_US || delta < DISCONTINUITY_BACKWARD_THRESHOLD_US) {
                val oldGen = _currentGeneration
                _currentGeneration++
                _basePtsUs = rawPtsUs
                _lastRawPtsUs = rawPtsUs
                return NormalizedPtsResult(
                    normalizedPtsUs = 0L,
                    generation = _currentGeneration,
                    isDiscontinuity = true,
                    oldGeneration = oldGen
                )
            }
        }

        if (_basePtsUs == -1L) {
            _basePtsUs = rawPtsUs
        }

        _lastRawPtsUs = rawPtsUs
        var normalized = rawPtsUs - _basePtsUs
        if (normalized < 0L) {
            normalized = 0L
        }

        return NormalizedPtsResult(
            normalizedPtsUs = normalized,
            generation = _currentGeneration,
            isDiscontinuity = false,
            oldGeneration = _currentGeneration
        )
    }

    @Synchronized
    fun forceDiscontinuity(newBasePtsUs: Long): Long {
        _currentGeneration++
        _basePtsUs = newBasePtsUs
        _lastRawPtsUs = newBasePtsUs
        return _currentGeneration
    }

    @Synchronized
    fun reset() {
        _basePtsUs = -1L
        _lastRawPtsUs = -1L
        _currentGeneration = 1L
    }

    @Synchronized
    fun getCurrentGeneration(): Long = _currentGeneration

    @Synchronized
    fun getBasePtsUs(): Long = _basePtsUs

    @Synchronized
    fun getLastRawPtsUs(): Long = _lastRawPtsUs
}
