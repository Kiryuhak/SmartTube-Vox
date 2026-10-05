package com.liskovsoft.smartyoutubetv2.common.vox.download

/** Проверяется перед сохранением COMPLETED и перед уведомлением библиотеки. */
internal object VoxDownloadCompletionValidator {
    fun check(job: VoxDownloadJob, readableBytes: Long) {
        val tracks = listOf(job.videoProgress, job.originalAudioProgress, job.translatedAudioProgress)
        if (tracks.any { it.state != VoxTrackState.COMPLETED || it.bytesDownloaded <= 0 } ||
            !job.hasTranslatedAudio || job.durationMs <= 0 || job.publishedUri.isNullOrBlank() ||
            job.finalFileBytes <= 0 || readableBytes != job.finalFileBytes) {
            throw VoxDownloadException(VoxDownloadErrorCode.STORAGE_ERROR, "Final download validation failed")
        }
    }
}
