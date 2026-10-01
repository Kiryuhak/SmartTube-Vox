package com.liskovsoft.smartyoutubetv2.common.vox.download

import java.util.Locale

/** Небольшая модель списка: источником состояния остаётся VoxDownloadRepository. */
data class VoxDownloadListItem(
    val downloadId: String,
    val videoId: String,
    val title: String,
    val state: VoxDownloadState,
    val stage: String,
    val percent: Int?,
    val actualQuality: String?,
    val translationMode: String,
    val publishedUri: String?,
    val createdAt: Long,
    val missingFile: Boolean
)

object VoxDownloadUiMapper {
    fun group(state: VoxDownloadState): Int = when (state) {
        VoxDownloadState.PAUSED -> 1
        VoxDownloadState.FAILED -> 2
        VoxDownloadState.COMPLETED -> 3
        VoxDownloadState.CANCELLED -> 4
        else -> 0
    }

    fun sorted(jobs: List<VoxDownloadJob>): List<VoxDownloadJob> =
        jobs.sortedWith(compareBy<VoxDownloadJob> { group(it.state) }
            .thenByDescending { it.request.createdAt }
            .thenBy { it.downloadId })

    fun stage(state: VoxDownloadState): String = when (state) {
        VoxDownloadState.PREPARING_TRANSLATION -> "Подготовка перевода"
        VoxDownloadState.RESOLVING_STREAMS -> "Подготовка загрузки"
        VoxDownloadState.DOWNLOADING_VIDEO -> "Загрузка видео"
        VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO -> "Загрузка оригинального звука"
        VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO -> "Загрузка перевода"
        VoxDownloadState.READY_FOR_MUX, VoxDownloadState.MUXING -> "Упаковка файла"
        VoxDownloadState.MUXED, VoxDownloadState.PUBLISHING -> "Сохранение файла"
        VoxDownloadState.COMPLETED -> "Готово"
        VoxDownloadState.PAUSED -> "Приостановлено"
        VoxDownloadState.FAILED -> "Ошибка"
        VoxDownloadState.CANCELLED -> "Отменено"
        VoxDownloadState.IDLE -> "Ожидание загрузки"
    }

    fun error(code: VoxDownloadErrorCode?): String = when (code) {
        VoxDownloadErrorCode.AUTH_REQUIRED -> "Для видео требуется вход"
        VoxDownloadErrorCode.INSUFFICIENT_STORAGE, VoxDownloadErrorCode.STORAGE_ERROR -> "Недостаточно свободного места"
        VoxDownloadErrorCode.URL_EXPIRED, VoxDownloadErrorCode.NETWORK_ERROR -> "Ошибка сети. Попробуйте позже"
        VoxDownloadErrorCode.STREAM_UNAVAILABLE -> "Видео недоступно"
        VoxDownloadErrorCode.TRANSLATION_UNAVAILABLE -> "Перевод недоступен"
        VoxDownloadErrorCode.UNSUPPORTED_CODEC -> "Этот формат пока не поддерживается"
        VoxDownloadErrorCode.MEDIA_PARSE_ERROR -> "Не удалось подготовить видеофайл"
        else -> "Не удалось скачать видео"
    }

    fun mode(mode: VoxTranslationMode): String = when (mode) {
        VoxTranslationMode.STANDARD -> "Стандартный перевод"
        VoxTranslationMode.LIVELY -> "Живой голос"
    }

    fun actions(state: VoxDownloadState, missingFile: Boolean): List<String> = when (state) {
        VoxDownloadState.COMPLETED -> if (missingFile) listOf("Скачать заново", "Удалить запись") else listOf("Открыть", "Скачать заново", "Удалить")
        VoxDownloadState.PAUSED -> listOf("Продолжить", "Удалить")
        VoxDownloadState.FAILED, VoxDownloadState.CANCELLED -> listOf("Повторить", "Удалить")
        else -> listOf("Показать прогресс", "Отменить загрузку")
    }

    fun formatSize(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L).toDouble()
        val unit = 1024.0
        return when {
            safe >= unit * unit * unit -> String.format(Locale("ru"), "%.1f ГБ", safe / (unit * unit * unit))
            safe >= unit * unit -> String.format(Locale("ru"), "%.1f МБ", safe / (unit * unit))
            else -> String.format(Locale("ru"), "%.0f КБ", safe / unit)
        }
    }

    fun toItem(job: VoxDownloadJob, missingFile: Boolean): VoxDownloadListItem {
        val snapshot = job.getSnapshot()
        val realPercent = when (job.state) {
            VoxDownloadState.PUBLISHING -> snapshot.publishTotalBytes.takeIf { it > 0 }?.let { snapshot.publishPercent }
            VoxDownloadState.MUXING -> snapshot.muxTotalBytes.takeIf { it > 0 }?.let { snapshot.muxPercent }
            VoxDownloadState.DOWNLOADING_VIDEO, VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO,
            VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO -> snapshot.overallPercent
            else -> null
        }
        return VoxDownloadListItem(job.downloadId, job.request.videoId, job.request.videoTitle,
            job.state, stage(job.state), realPercent,
            job.actualVideoHeight.takeIf { it > 0 }?.let { "${it}p" },
            mode(job.request.translationMode), job.publishedUri, job.request.createdAt, missingFile)
    }
}
