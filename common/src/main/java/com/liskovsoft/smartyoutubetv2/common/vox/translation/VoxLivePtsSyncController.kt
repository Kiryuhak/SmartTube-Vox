package com.liskovsoft.smartyoutubetv2.common.vox.translation

import kotlin.math.abs

/**
 * Контроллер синхронизации воспроизведения по меткам времени PTS (PTS Sync Controller, Section 58).
 *
 * Отслеживает расхождение (drift) между положением воспроизведения мастер-плеера (видеопоток YouTube)
 * и положением вторичного аудиоплеера (перевод или тестовый passthrough).
 *
 * Пороги и реакции (Sync Rules):
 * 1. |drift| <= 150 мс: норма, PLAY (воспроизведение в синхроне).
 * 2. drift > +150 мс: вторичный звук опережает видео, WAIT (приостановка вторичного плеера).
 * 3. -500 мс <= drift < -150 мс: вторичный звук отстает, DROP (пропуск отстающих фреймов).
 * 4. |drift| > 500 мс: критический рассинхрон, REANCHOR (жесткий сброс привязки таймлайна).
 * 5. Буфер пуст (underrun): FALLBACK (немедленный бесшовный возврат на основной звук YouTube).
 */
class VoxLivePtsSyncController(
    initialTargetDelayUs: Long = 0L // Сдвиг для компенсации задержки перевода (0 мс в режиме Passthrough)
) {
    companion object {
        const val DRIFT_SYNC_TOLERANCE_MS = 150L   // 150 мс порог нормального синхрона
        const val DRIFT_DESYNC_MAX_MS = 500L       // 500 мс порог критического рассинхрона
    }

    private val lock = Any()
    private var _targetDelayUs: Long = initialTargetDelayUs
    private var _playbackSpeed: Float = 1.0f

    // Метрики
    private var currentDriftMs: Long = 0L
    private var maxDriftMs: Long = 0L
    private var reanchorCount: Int = 0
    private var underrunCount: Int = 0
    private var fallbackActive: Boolean = false

    fun setTargetDelayUs(delayUs: Long) {
        synchronized(lock) {
            _targetDelayUs = delayUs.coerceAtLeast(0L)
        }
    }

    fun getTargetDelayUs(): Long = synchronized(lock) { _targetDelayUs }

    fun onPlaybackSpeedChanged(speed: Float) {
        synchronized(lock) {
            if (speed in 0.25f..2.5f) {
                _playbackSpeed = speed
            }
        }
    }

    fun getPlaybackSpeed(): Float = synchronized(lock) { _playbackSpeed }

    /**
     * Оценка расхождения и выработка управляющего решения.
     *
     * @param masterPositionUs Текущая позиция воспроизведения основного видеоплеера (мкс)
     * @param secondaryPtsUs Текущая метка времени вторичного аудиопотока (мкс)
     * @param isSecondaryBufferEmpty Флаг отсутствия данных во вторичном буфере (underrun)
     */
    fun evaluate(
        masterPositionUs: Long,
        secondaryPtsUs: Long,
        isSecondaryBufferEmpty: Boolean = false
    ): VoxLivePtsSyncDecision {
        synchronized(lock) {
            if (isSecondaryBufferEmpty) {
                underrunCount++
                fallbackActive = true
                return VoxLivePtsSyncDecision(
                    action = VoxLivePtsSyncAction.FALLBACK,
                    driftMs = 0L,
                    reason = "Secondary buffer underrun: seamless fallback to original YouTube audio"
                )
            }

            // Целевая позиция вторичного звука относительно основного плеера с учетом задержки
            val targetUs = masterPositionUs - _targetDelayUs
            val driftUs = secondaryPtsUs - targetUs
            val driftMs = driftUs / 1000L

            currentDriftMs = driftMs
            val absDrift = abs(driftMs)
            if (absDrift > maxDriftMs) {
                maxDriftMs = absDrift
            }

            return when {
                absDrift <= DRIFT_SYNC_TOLERANCE_MS -> {
                    fallbackActive = false
                    VoxLivePtsSyncDecision(
                        action = VoxLivePtsSyncAction.PLAY,
                        driftMs = driftMs,
                        reason = "In sync: drift $driftMs ms within +/-${DRIFT_SYNC_TOLERANCE_MS}ms tolerance"
                    )
                }
                driftMs > DRIFT_SYNC_TOLERANCE_MS && driftMs <= DRIFT_DESYNC_MAX_MS -> {
                    VoxLivePtsSyncDecision(
                        action = VoxLivePtsSyncAction.WAIT,
                        driftMs = driftMs,
                        reason = "Secondary ahead: drift +$driftMs ms, waiting for master video"
                    )
                }
                driftMs < -DRIFT_SYNC_TOLERANCE_MS && driftMs >= -DRIFT_DESYNC_MAX_MS -> {
                    VoxLivePtsSyncDecision(
                        action = VoxLivePtsSyncAction.DROP,
                        driftMs = driftMs,
                        reason = "Secondary lagging: drift $driftMs ms, dropping delayed frames"
                    )
                }
                else -> {
                    // |driftMs| > 500 ms -> Re-anchor
                    reanchorCount++
                    VoxLivePtsSyncDecision(
                        action = VoxLivePtsSyncAction.REANCHOR,
                        driftMs = driftMs,
                        reason = "Desync exceeds $DRIFT_DESYNC_MAX_MS ms: forcing timeline re-anchor"
                    )
                }
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            currentDriftMs = 0L
            maxDriftMs = 0L
            reanchorCount = 0
            underrunCount = 0
            fallbackActive = false
            _playbackSpeed = 1.0f
        }
    }

    fun getDiagnostics(routingMode: VoxAudioRoutingMode = VoxAudioRoutingMode.ORIGINAL_ONLY): VoxLiveAudioSyncDiagnostics {
        synchronized(lock) {
            return VoxLiveAudioSyncDiagnostics(
                captureActive = VoxLiveAudioTap.isEnabled(),
                routingMode = routingMode,
                generation = VoxLiveAudioTap.getNormalizer().getCurrentGeneration(),
                lastPtsUs = VoxLiveAudioTap.getDiagnostics().lastPtsUs,
                currentDriftMs = currentDriftMs,
                maxDriftMs = maxDriftMs,
                underrunCount = underrunCount,
                reanchorCount = reanchorCount,
                droppedFrames = VoxLiveAudioTap.getAssembler().getDroppedFragmentCount(),
                totalCapturedFragments = VoxLiveAudioTap.getAssembler().getAssembledFragmentCount(),
                totalCapturedBytes = VoxLiveAudioTap.getDiagnostics().totalCapturedBytes,
                fallbackActive = fallbackActive
            )
        }
    }
}
