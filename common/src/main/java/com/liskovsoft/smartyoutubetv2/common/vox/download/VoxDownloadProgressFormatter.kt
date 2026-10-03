package com.liskovsoft.smartyoutubetv2.common.vox.download

/**
 * Formats download progress state for Player HUD and library cards.
 */
object VoxDownloadProgressFormatter {

    /**
     * Formats player HUD action label based on download state and progress percent.
     * Examples:
     * - Idle: "Скачать"
     * - In progress with percent: "Загрузка 37%"
     * - In progress indeterminate: "Загрузка…"
     * - Completed: "Скачано"
     * - Failed: "Ошибка загрузки"
     */
    @JvmStatic
    fun formatHudLabel(state: VoxDownloadState?, percent: Int?): String {
        return when (state) {
            VoxDownloadState.COMPLETED -> "Скачано"
            VoxDownloadState.FAILED -> "Ошибка загрузки"
            VoxDownloadState.CANCELLED,
            VoxDownloadState.IDLE,
            null -> "Скачать"
            VoxDownloadState.PREPARING_TRANSLATION,
            VoxDownloadState.RESOLVING_STREAMS,
            VoxDownloadState.DOWNLOADING_VIDEO,
            VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO,
            VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO,
            VoxDownloadState.READY_FOR_MUX,
            VoxDownloadState.MUXING,
            VoxDownloadState.MUXED,
            VoxDownloadState.PUBLISHING,
            VoxDownloadState.PAUSED -> {
                if (percent != null && percent in 0..100) {
                    "Загрузка $percent%"
                } else {
                    "Загрузка…"
                }
            }
        }
    }

    /**
     * Formats download card subtitle/status in library.
     * Examples:
     * - In progress with stage & percent: "Загрузка видео · 64%"
     * - Indeterminate: "Подготовка…"
     * - Error: "Ошибка сети"
     */
    @JvmStatic
    fun formatCardStatus(stage: String?, percent: Int?): String {
        val safeStage = stage?.trim().orEmpty()
        return if (percent != null && percent in 0..100) {
            if (safeStage.isNotEmpty()) "$safeStage · $percent%" else "$percent%"
        } else {
            if (safeStage.isNotEmpty()) safeStage else "Загрузка…"
        }
    }
}
