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
    initialErrorMessage: String? = null,
    initialPublishedUri: String? = null,
    initialPublishedFilePath: String? = null,
    initialActualVideoHeight: Int = 0,
    val requestedQuality: String? = request.qualityPreference.label,
    var actualQuality: String? = if (initialActualVideoHeight > 0) "${initialActualVideoHeight}p" else null,
    var fallbackReason: String? = null,
    val translationState: VoxDownloadTranslationState = if (request.translationMode == VoxTranslationMode.NONE)
        VoxDownloadTranslationState.NONE else VoxDownloadTranslationState.DOWNLOADED_TRANSLATED
) {
    val downloadId: String get() = request.downloadId
    var durationMs: Long = 0L
    var hasTranslatedAudio: Boolean = false
    var finalFileBytes: Long = 0L
    var processingTimeMs: Long = 0L
    var processingSourceBytes: Long = 0L
    var processingSamples: Long = 0L
    var lastProgressAt: Long = 0L
    var lastFailedStage: String? = null
    var lastErrorCategory: String? = null
    var lastOperation: String? = null
    var safeRootCause: String? = null
    var retryCount: Int = 0
    var packagingStarted: Boolean = false
    var packagingCompleted: Boolean = false
    var finalizeCompleted: Boolean = false

    // Diagnostics 2.0: Downloads Performance Metrics
    var downloadElapsedMs: Long = 0L
    var videoElapsedMs: Long = 0L
    var audioElapsedMs: Long = 0L
    var translationElapsedMs: Long = 0L
    var parallelStreamsCount: Int = 1
    var rangeResumptionsCount: Int = 0
    var bytesResumed: Long = 0L
    var stallEventsCount: Int = 0
    var networkReconnectCount: Int = 0
    var packagingElapsedMs: Long = 0L
    var finalizeElapsedMs: Long = 0L
    var averageSpeedMbps: Double = 0.0
    var peakSpeedMbps: Double = 0.0

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
    var muxBytesProcessed: Long = 0L
        private set

    @Volatile
    var muxTotalBytes: Long = 0L
        private set

    @Volatile
    var muxPercent: Int = 0
        private set

    @Volatile
    var publishBytesProcessed: Long = 0L
        private set

    @Volatile
    var publishTotalBytes: Long = 0L
        private set

    @Volatile
    var publishPercent: Int = 0
        private set

    @Volatile
    var publishedUri: String? = initialPublishedUri

    @Volatile
    var publishedFilePath: String? = initialPublishedFilePath

    @Volatile
    var actualVideoHeight: Int = initialActualVideoHeight

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
        if ((isCancelled() && newState != VoxDownloadState.CANCELLED && newState != VoxDownloadState.QUEUED) ||
            (isPaused() && newState != VoxDownloadState.PAUSED && newState != VoxDownloadState.QUEUED)) {
            return
        }
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

    fun updateMuxProgress(bytesProcessed: Long, totalBytes: Long, percent: Int) {
        muxBytesProcessed = bytesProcessed
        muxTotalBytes = totalBytes
        muxPercent = percent
    }

    fun updatePublishProgress(bytesProcessed: Long, totalBytes: Long, percent: Int) {
        publishBytesProcessed = bytesProcessed
        publishTotalBytes = totalBytes
        publishPercent = percent
    }

    fun getSnapshot(): VoxDownloadProgress {
        return VoxDownloadProgress(
            downloadId = downloadId,
            state = state,
            video = videoProgress,
            originalAudio = originalAudioProgress,
            translatedAudio = translatedAudioProgress,
            muxBytesProcessed = muxBytesProcessed,
            muxTotalBytes = muxTotalBytes,
            muxPercent = muxPercent,
            publishBytesProcessed = publishBytesProcessed,
            publishTotalBytes = publishTotalBytes,
            publishPercent = publishPercent,
            translationState = translationState,
            publishedUri = publishedUri,
            publishedFilePath = publishedFilePath,
            requestedQuality = requestedQuality,
            actualQuality = actualQuality ?: (if (actualVideoHeight > 0) "${actualVideoHeight}p" else null),
            fallbackReason = fallbackReason,
            errorMessage = errorMessage,
            errorCode = errorCode
        )
    }
}
