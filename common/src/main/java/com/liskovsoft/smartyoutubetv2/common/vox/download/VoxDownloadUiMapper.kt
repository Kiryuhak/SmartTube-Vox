package com.liskovsoft.smartyoutubetv2.common.vox.download

/** Небольшая модель списка: источником состояния остаётся VoxDownloadRepository. */
data class VoxDownloadListItem(
    val downloadId: String,
    val videoId: String,
    val title: String,
    val state: VoxDownloadState,
    val stage: String,
    val percent: Int?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
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
        VoxDownloadState.IDLE -> "В очереди"
        VoxDownloadState.PREPARING_TRANSLATION -> "Подготовка перевода"
        VoxDownloadState.RESOLVING_STREAMS -> "Подготовка загрузки"
        VoxDownloadState.DOWNLOADING_VIDEO -> "Загрузка видео"
        VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO -> "Загрузка оригинального звука"
        VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO -> "Загрузка перевода"
        VoxDownloadState.READY_FOR_MUX,
        VoxDownloadState.MUXING -> "Обработка…"
        VoxDownloadState.MUXED,
        VoxDownloadState.PUBLISHING -> "Сохранение…"
        VoxDownloadState.COMPLETED -> "Скачано"
        VoxDownloadState.PAUSED -> "Приостановлено"
        VoxDownloadState.FAILED -> "Ошибка загрузки"
        VoxDownloadState.CANCELLED -> "Отменено"
    }

    fun error(code: VoxDownloadErrorCode?): String {
        return VoxDownloadFailureClassifier.getUserMessage(code)
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
        return VoxDownloadSizeFormatter.formatBytes(bytes)
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
        return VoxDownloadListItem(
            downloadId = job.downloadId,
            videoId = job.request.videoId,
            title = job.request.videoTitle,
            state = job.state,
            stage = stage(job.state),
            percent = realPercent,
            downloadedBytes = snapshot.totalBytesDownloaded,
            totalBytes = snapshot.totalBytesExpected,
            actualQuality = job.actualVideoHeight.takeIf { it > 0 }?.let { "${it}p" },
            translationMode = mode(job.request.translationMode),
            publishedUri = job.publishedUri,
            createdAt = job.request.createdAt,
            missingFile = missingFile
        )
    }
}
