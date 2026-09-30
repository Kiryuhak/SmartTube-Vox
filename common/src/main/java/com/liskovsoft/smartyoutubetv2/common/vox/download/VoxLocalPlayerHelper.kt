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
            mediaUri = uri
        )
    }

    @JvmStatic
    fun playLocalVideo(context: Context, videoId: String, title: String, mediaUri: String) {
        val video = Video.from(videoId)
        video.title = title
        video.mediaUrl = mediaUri
        video.isLocal = true

        PlaybackPresenter.instance(context).openVideo(video)
    }
}
