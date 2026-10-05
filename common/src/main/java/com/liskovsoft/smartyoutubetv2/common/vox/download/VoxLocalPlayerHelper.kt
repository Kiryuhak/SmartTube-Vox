package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter

/**
 * Вспомогательный класс для запуска воспроизведения локально скачанных MKV-файлов в плеере SmartTube.
 */
object VoxLocalPlayerHelper {

    @JvmStatic
    fun playJob(context: Context, job: VoxDownloadJob) {
        val uri = job.publishedUri ?: return
        playLocalVideo(
            context = context,
            videoId = job.request.videoId,
            title = job.request.videoTitle,
            mediaUri = uri,
            translationState = job.translationState.name,
            badge = job.actualQuality ?: job.requestedQuality
        )
    }

    @JvmStatic
    @JvmOverloads
    fun playLocalVideo(
        context: Context,
        videoId: String,
        title: String,
        mediaUri: String,
        translationState: String? = VoxDownloadTranslationState.DOWNLOADED_TRANSLATED.name,
        badge: String? = null,
        durationMs: Long = 0L,
        ageRating: String? = null
    ) {
        val video = Video.from(videoId)
        video.title = title
        video.cardImageUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        video.mediaUrl = mediaUri
        video.isLocal = true
        video.translationState = translationState ?: VoxDownloadTranslationState.DOWNLOADED_TRANSLATED.name
        if (badge != null) {
            video.badge = badge
        }
        if (durationMs > 0) {
            video.setDurationMs(durationMs)
        }
        if (ageRating != null) {
            video.ageRating = ageRating
        }

        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.info(
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.PLAYER,
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.OFFLINE_PLAYBACK_OPENED,
            "Playing offline local video: $videoId (state=$translationState)"
        )

        PlaybackPresenter.instance(context).openVideo(video)
    }
}
