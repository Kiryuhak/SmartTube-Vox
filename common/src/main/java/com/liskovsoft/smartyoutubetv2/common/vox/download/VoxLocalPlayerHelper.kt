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
            translationState = job.translationState.name
        )
    }

    @JvmStatic
    fun playLocalVideo(
        context: Context,
        videoId: String,
        title: String,
        mediaUri: String,
        translationState: String? = VoxDownloadTranslationState.DOWNLOADED_TRANSLATED.name
    ) {
        val video = Video.from(videoId)
        video.title = title
        video.mediaUrl = mediaUri
        video.isLocal = true
        video.translationState = translationState ?: VoxDownloadTranslationState.DOWNLOADED_TRANSLATED.name

        PlaybackPresenter.instance(context).openVideo(video)
    }
}
