package com.liskovsoft.smartyoutubetv2.common.vox.translation

import kotlin.math.abs
import kotlin.math.max

/**
 * Действия контроллера синхронизации воспроизведения переведённой аудиодорожки.
 */
enum class VoxSyncAction {
    PLAY,
    PAUSE,
    SEEK_TRANSLATED,
    RATE_CORRECTION,
    FALLBACK_ORIGINAL
}

/**
 * Решение контроллера синхронизации.
 */
data class VoxSyncDecision(
    val action: VoxSyncAction,
    val targetPositionMs: Long = 0L,
    val rateAdjustment: Float = 1.0f,
    val reasonRu: String
)

/**
 * Контроллер синхронизации воспроизведения (VOD + future Live).
 * Обобщает логику сведения двух аудио-потоков:
 * - Учитывает шкалу скорости (1.0x, 1.25x, 1.5x, 1.75x, 2.0x);
 * - Защищает от рассинхрона поколений (generationId token protection);
 * - Применяет плавную коррекцию скорости (rate correction) при небольшом дрейфе;
 * - Выполняет принудительный seek при значительном рассинхроне;
 * - Выполняет fallback на оригинал при критическом истощении буфера.
 */
class VoxTranslationSyncEngine(
    val maxAllowedDriftMs: Long = 250L,
    val smoothCorrectionThresholdMs: Long = 50L,
    val criticalBufferFloorMs: Long = 1500L
) {
    /**
     * Оценка синхронизации и принятие решения о действии плеера перевода.
     */
    fun evaluate(
        sourcePositionMs: Long,
        translatedPositionMs: Long,
        playbackSpeed: Float,
        bufferedTranslatedMs: Long,
        generationId: Long,
        activeGenerationId: Long
    ): VoxSyncDecision {
        // 1. Проверка поколения сегментов
        if (generationId != activeGenerationId) {
            return VoxSyncDecision(
                action = VoxSyncAction.FALLBACK_ORIGINAL,
                targetPositionMs = sourcePositionMs,
                reasonRu = "Сегмент устарел (generationId=$generationId != active=$activeGenerationId)"
            )
        }

        // 2. Проверка истощения буфера с учётом скорости воспроизведения
        val speedFactor = max(1.0f, playbackSpeed)
        val scaledCriticalBuffer = (criticalBufferFloorMs * speedFactor).toLong()

        if (bufferedTranslatedMs < scaledCriticalBuffer) {
            return VoxSyncDecision(
                action = VoxSyncAction.FALLBACK_ORIGINAL,
                targetPositionMs = sourcePositionMs,
                reasonRu = "Критическое истощение буфера перевода ($bufferedTranslatedMs мс < $scaledCriticalBuffer мс)"
            )
        }

        // 3. Вычисление величины рассинхрона
        // driftMs > 0: переведённый звук забегает вперёд
        // driftMs < 0: переведённый звук отстаёт от видео
        val driftMs = translatedPositionMs - sourcePositionMs
        val absDriftMs = abs(driftMs)

        return when {
            // В пределах зоны идеальной синхронизации
            absDriftMs <= smoothCorrectionThresholdMs -> {
                VoxSyncDecision(
                    action = VoxSyncAction.PLAY,
                    targetPositionMs = translatedPositionMs,
                    rateAdjustment = 1.0f,
                    reasonRu = "Синхронизация в норме (дрейф: ${driftMs} мс)"
                )
            }

            // Умеренный рассинхрон: плавная коррекция скорости без слышимых скачков
            absDriftMs <= maxAllowedDriftMs -> {
                val adjustment = if (driftMs > 0) 0.96f else 1.04f
                VoxSyncDecision(
                    action = VoxSyncAction.RATE_CORRECTION,
                    targetPositionMs = translatedPositionMs,
                    rateAdjustment = adjustment,
                    reasonRu = "Плавная подстройка скорости перевода (дрейф: ${driftMs} мс, коэф: $adjustment)"
                )
            }

            // Значительный рассинхрон: принудительное позиционирование
            else -> {
                VoxSyncDecision(
                    action = VoxSyncAction.SEEK_TRANSLATED,
                    targetPositionMs = sourcePositionMs,
                    rateAdjustment = 1.0f,
                    reasonRu = "Слишком большой рассинхрон (${driftMs} мс), переход к позиции источника"
                )
            }
        }
    }
}
