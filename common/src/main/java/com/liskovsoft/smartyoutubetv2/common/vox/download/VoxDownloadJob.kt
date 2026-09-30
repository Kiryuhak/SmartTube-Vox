package com.liskovsoft.smartyoutubetv2.common.vox.download

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Активное задание скачивания видео с переводом в оперативной памяти.
 */
class VoxDownloadJob(
    val request: VoxDownloadRequest,
    initialState: VoxDownloadState = VoxDownloadState.IDLE,
    initialVideo: VoxTrackProgress = VoxTrackProgress(VoxDownloadTrack.VIDEO),
    initialOriginalAudio: VoxTrackProgress = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO),
    initialTranslatedAudio: VoxTrackProgress = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO),
    initialErrorCode: VoxDownloadErrorCode? = null,
    initialErrorMessage: String? = null
) {
    val downloadId: String get() = request.downloadId

    val generation = AtomicLong(1L)
    val isCancelledFlag = AtomicBoolean(false)
    val isPausedFlag = AtomicBoolean(false)

    @Volatile
    var state: VoxDownloadState = initialState
        private set

    @Volatile
    var videoProgress: VoxTrackProgress = initialVideo
        private set

    @Volatile
    var originalAudioProgress: VoxTrackProgress = initialOriginalAudio
        private set

    @Volatile
    var translatedAudioProgress: VoxTrackProgress = initialTranslatedAudio
        private set

    @Volatile
    var errorCode: VoxDownloadErrorCode? = initialErrorCode
        private set

    @Volatile
    var errorMessage: String? = initialErrorMessage
        private set

    fun isCancelled(): Boolean = isCancelledFlag.get()
    fun isPaused(): Boolean = isPausedFlag.get()

    fun bumpGeneration(): Long = generation.incrementAndGet()

    fun updateState(newState: VoxDownloadState, code: VoxDownloadErrorCode? = null, message: String? = null) {
        state = newState
        errorCode = code
        errorMessage = message
    }

    fun updateTrackProgress(
        track: VoxDownloadTrack,
        bytesDownloaded: Long,
        totalBytes: Long?,
        trackState: VoxTrackState
    ) {
        val newProgress = VoxTrackProgress(track, bytesDownloaded, totalBytes, trackState)
        when (track) {
            VoxDownloadTrack.VIDEO -> videoProgress = newProgress
            VoxDownloadTrack.ORIGINAL_AUDIO -> originalAudioProgress = newProgress
            VoxDownloadTrack.TRANSLATED_AUDIO -> translatedAudioProgress = newProgress
        }
    }

    fun getSnapshot(): VoxDownloadProgress {
        return VoxDownloadProgress(
            downloadId = downloadId,
            state = state,
            video = videoProgress,
            originalAudio = originalAudioProgress,
            translatedAudio = translatedAudioProgress,
            errorMessage = errorMessage,
            errorCode = errorCode
        )
    }
}
