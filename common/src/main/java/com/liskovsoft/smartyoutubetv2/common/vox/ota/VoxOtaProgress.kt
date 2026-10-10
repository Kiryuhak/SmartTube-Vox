package com.liskovsoft.smartyoutubetv2.common.vox.ota

import java.util.Locale

/**
 * Состояния жизненного цикла обновления OTA SmartTube VOX.
 */
enum class VoxOtaState(val titleRu: String) {
    CHECKING("Проверка обновлений…"),
    UPDATE_AVAILABLE("Доступно обновление"),
    DOWNLOADING("Скачивание обновления"),
    VERIFYING("Проверка файла обновления…"),
    READY_TO_INSTALL("Готово к установке"),
    INSTALLING("Установка обновления…"),
    NO_UPDATE("Установлена последняя версия"),
    FAILED("Ошибка обновления");
}

/**
 * Снимок состояния прогресса обновления OTA.
 */
data class VoxOtaProgress(
    val state: VoxOtaState,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val percent: Int? = null,
    val speedBytesPerSec: Double = 0.0,
    val etaSec: Long? = null,
    val errorMessage: String? = null
)

/**
 * Оценщик и форматтер прогресса OTA (скорость, EMA ETA, статус).
 */
class VoxOtaProgressEstimator(
    private val alpha: Float = 0.3f,
    private val minAccumulationMs: Long = 1000L
) {
    private var startTimeMs: Long = 0L
    private var lastTimeMs: Long = 0L
    private var lastBytes: Long = 0L
    private var emaSpeed: Double = 0.0
    private var started = false

    fun reset() {
        startTimeMs = 0L
        lastTimeMs = 0L
        lastBytes = 0L
        emaSpeed = 0.0
        started = false
    }

    fun update(bytesDownloaded: Long, totalBytes: Long?, nowMs: Long = System.currentTimeMillis()): VoxOtaProgress {
        if (!started || bytesDownloaded < lastBytes) {
            startTimeMs = nowMs
            lastTimeMs = nowMs
            lastBytes = bytesDownloaded
            emaSpeed = 0.0
            started = true
        } else {
            val deltaMs = nowMs - lastTimeMs
            if (deltaMs >= 200L) {
                val deltaBytes = bytesDownloaded - lastBytes
                if (deltaBytes >= 0L) {
                    val instantSpeed = (deltaBytes * 1000.0) / deltaMs
                    emaSpeed = if (emaSpeed <= 0.0) instantSpeed else (alpha * instantSpeed + (1.0f - alpha) * emaSpeed)
                    lastTimeMs = nowMs
                    lastBytes = bytesDownloaded
                }
            }
        }

        val percent = if (totalBytes != null && totalBytes > 0L) {
            ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else null

        val eta = if (emaSpeed > 1024.0 && totalBytes != null && totalBytes > bytesDownloaded && (nowMs - startTimeMs) >= minAccumulationMs) {
            val remaining = totalBytes - bytesDownloaded
            (remaining / emaSpeed).toLong().coerceAtLeast(1L)
        } else null

        return VoxOtaProgress(
            state = VoxOtaState.DOWNLOADING,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            percent = percent,
            speedBytesPerSec = emaSpeed,
            etaSec = eta
        )
    }

    companion object {
        @JvmStatic
        fun formatTitle(progress: VoxOtaProgress): String {
            return when (progress.state) {
                VoxOtaState.DOWNLOADING -> {
                    if (progress.percent != null) {
                        "Скачивание обновления       ${progress.percent}%"
                    } else {
                        "Скачивание обновления…"
                    }
                }
                else -> progress.state.titleRu
            }
        }

        @JvmStatic
        fun formatBytesProgress(progress: VoxOtaProgress): String {
            val downloadedStr = formatBytes(progress.bytesDownloaded)
            val total = progress.totalBytes
            return if (total != null && total > 0L) {
                "$downloadedStr / ${formatBytes(total)}"
            } else {
                downloadedStr
            }
        }

        @JvmStatic
        fun formatSpeedAndEta(progress: VoxOtaProgress): String {
            val speedStr = formatSpeed(progress.speedBytesPerSec)
            val etaSec = progress.etaSec
            return if (etaSec != null && etaSec > 0L) {
                val etaStr = if (etaSec >= 60L) {
                    "~${(etaSec + 30L) / 60L} мин"
                } else {
                    "~$etaSec сек"
                }
                if (speedStr.isNotEmpty()) "$speedStr • Осталось $etaStr" else "Осталось $etaStr"
            } else {
                speedStr
            }
        }

        @JvmStatic
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 Б"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.1f МБ", mb).replace('.', ',')
            } else {
                val kb = bytes / 1024.0
                String.format(Locale.US, "%.0f КБ", kb)
            }
        }

        @JvmStatic
        fun formatSpeed(bytesPerSec: Double): String {
            if (bytesPerSec <= 0.0) return ""
            val mb = bytesPerSec / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.1f МБ/с", mb).replace('.', ',')
            } else {
                val kb = bytesPerSec / 1024.0
                String.format(Locale.US, "%.0f КБ/с", kb)
            }
        }
    }
}
