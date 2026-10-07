package com.liskovsoft.smartyoutubetv2.common.vox.download

/**
 * Оценщик скорости скачивания и оставшегося времени (EMA ETA) для оверлея VOX.
 *
 * Особенности:
 * - Сглаживание скорости по формуле экспоненциального скользящего среднего (EMA).
 * - Минимальный период прогрева (3 секунды) перед показом первого значения ETA для предотвращения скачков.
 * - Защита от деления на ноль, отрицательных дельт и выбросов.
 */
class VoxDownloadSpeedEstimator(
    private val alpha: Float = 0.25f,
    private val minAccumulationMs: Long = 3000L,
    private val minIntervalMs: Long = 250L
) {
    private var startTimeMs: Long = 0L
    private var lastTimeMs: Long = 0L
    private var lastBytes: Long = 0L
    private var emaSpeedBytesPerSec: Double = 0.0
    private var hasStarted: Boolean = false

    fun reset() {
        startTimeMs = 0L
        lastTimeMs = 0L
        lastBytes = 0L
        emaSpeedBytesPerSec = 0.0
        hasStarted = false
    }

    /**
     * Обновляет счетчик байт и возвращает сглаженное оставшееся время в секундах,
     * либо null, если данных для оценки пока недостаточно.
     */
    fun update(
        downloadedBytes: Long,
        totalBytes: Long?,
        nowMs: Long = System.currentTimeMillis()
    ): Long? {
        if (totalBytes == null || totalBytes <= 0L) {
            return null
        }

        if (downloadedBytes >= totalBytes) {
            return 0L
        }

        if (!hasStarted || downloadedBytes < lastBytes) {
            startTimeMs = nowMs
            lastTimeMs = nowMs
            lastBytes = downloadedBytes
            emaSpeedBytesPerSec = 0.0
            hasStarted = true
            return null
        }

        val elapsedSinceLast = nowMs - lastTimeMs
        if (elapsedSinceLast >= minIntervalMs) {
            val bytesDelta = downloadedBytes - lastBytes
            if (bytesDelta >= 0L) {
                val instantSpeed = (bytesDelta * 1000.0) / elapsedSinceLast
                emaSpeedBytesPerSec = if (emaSpeedBytesPerSec <= 0.0) {
                    instantSpeed
                } else {
                    alpha * instantSpeed + (1.0f - alpha) * emaSpeedBytesPerSec
                }
                lastTimeMs = nowMs
                lastBytes = downloadedBytes
            }
        }

        val totalElapsed = nowMs - startTimeMs
        if (totalElapsed < minAccumulationMs) {
            return null
        }

        if (emaSpeedBytesPerSec > 1024.0) { // Минимум 1 КБ/с
            val remainingBytes = (totalBytes - downloadedBytes).coerceAtLeast(0L)
            val etaSec = (remainingBytes / emaSpeedBytesPerSec).toLong()
            return etaSec.coerceAtLeast(1L)
        }

        return null
    }

    fun getEmaSpeedBytesPerSec(): Double = emaSpeedBytesPerSec

    fun getSpeedMbps(): Double {
        return (emaSpeedBytesPerSec * 8.0) / (1000.0 * 1000.0)
    }

    fun getFormattedSpeed(): String {
        if (emaSpeedBytesPerSec <= 0.0) return ""
        val mbPerSec = emaSpeedBytesPerSec / (1024.0 * 1024.0)
        return if (mbPerSec >= 1.0) {
            String.format(java.util.Locale.US, "%.1f МБ/с", mbPerSec)
        } else {
            val kbPerSec = emaSpeedBytesPerSec / 1024.0
            String.format(java.util.Locale.US, "%.0f КБ/с", kbPerSec)
        }
    }
}
