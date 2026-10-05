package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.vox.badge.VoxAgeRating
import com.liskovsoft.smartyoutubetv2.common.vox.badge.VoxAgeRatingFormatter

/**
 * Единая фабрика создания объектов Video для скачанных медиафайлов.
 * Гарантирует одинаковую структуру метаданных как для навигации в UI (Sidebar),
 * так и для прямого воспроизведения через VoxLocalPlayerHelper.
 */
object VoxDownloadedVideoFactory {

    /**
     * Создает модель Video из объекта активной/завершенной загрузки VoxDownloadJob.
     */
    @JvmStatic
    fun createVideo(job: VoxDownloadJob): Video {
        val video = Video()
        val videoId = job.request.videoId
        video.id = if (videoId.isNotBlank()) videoId.hashCode() else job.downloadId.hashCode()
        video.videoId = videoId
        video.title = job.request.videoTitle
        video.author = "Локальный файл"
        video.mediaUrl = job.publishedUri ?: job.publishedFilePath
        video.isLocal = true
        video.translationState = job.translationState.name
        video.cardImageUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        video.badge = job.actualQuality ?: job.requestedQuality ?: "1080p"
        video.category = "Скачанные видео"
        return video
    }

    /**
     * Создает модель Video из сохраненных персистентных данных StoredJobData.
     */
    @JvmStatic
    fun createVideo(data: StoredJobData): Video {
        val video = Video()
        val videoId = data.request.videoId
        video.id = if (videoId.isNotBlank()) videoId.hashCode() else data.request.downloadId.hashCode()
        video.videoId = videoId
        video.title = data.request.videoTitle
        video.author = "Локальный файл"
        video.mediaUrl = data.publishedUri ?: data.publishedFilePath
        video.isLocal = true
        video.translationState = data.translationState.name
        video.cardImageUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        video.badge = data.actualQuality ?: data.requestedQuality ?: "1080p"
        if (data.durationMs > 0) {
            video.setDurationMs(data.durationMs)
        }

        val parsedAge = VoxAgeRatingFormatter.parse(data.ageRating)
        video.ageRating = parsedAge?.label

        video.category = "Скачанные видео"
        return video
    }
}
