package com.liskovsoft.smartyoutubetv2.common.vox.download

import java.util.concurrent.ConcurrentHashMap

/**
 * Репозиторий заданий скачивания, синхронизирующий состояние в памяти и на диске.
 */
class VoxDownloadRepository(
    private val storage: VoxDownloadStorage
) {
    private val jobs = ConcurrentHashMap<String, VoxDownloadJob>()

    init {
        restoreJobsFromStorage()
    }

    /**
     * Восстанавливает все сохраненные задания с диска при инициализации.
     * Не завершенные задания переводятся в состояние PAUSED без автоматического старта сети.
     */
    @Synchronized
    fun restoreJobsFromStorage() {
        val jobIds = storage.listJobIds()
        for (id in jobIds) {
            val stored = storage.loadJobMetadata(id) ?: continue

            // Если задание не завершилось (не COMPLETED/READY_FOR_MUX) и не было отменено,
            // восстанавливаем его в состоянии PAUSED
            val restoredState = when (stored.state) {
                VoxDownloadState.COMPLETED -> {
                    if (stored.publishedUri != null) {
                        VoxDownloadState.COMPLETED
                    } else if (storage.getOutputFile(id).exists()) {
                        VoxDownloadState.MUXED
                    } else {
                        VoxDownloadState.PAUSED
                    }
                }
                VoxDownloadState.PUBLISHING -> {
                    if (storage.getOutputFile(id).exists()) {
                        VoxDownloadState.MUXED
                    } else {
                        VoxDownloadState.READY_FOR_MUX
                    }
                }
                VoxDownloadState.MUXED -> {
                    if (storage.getOutputFile(id).exists()) {
                        VoxDownloadState.MUXED
                    } else {
                        VoxDownloadState.READY_FOR_MUX
                    }
                }
                VoxDownloadState.FINALIZING, VoxDownloadState.MUXING -> {
                    // Удаляем недописанный временный файл мультиплексирования
                    val tmpMkv = storage.getTmpOutputFile(id)
                    if (tmpMkv.exists()) {
                        tmpMkv.delete()
                    }
                    val v = storage.getTrackFile(id, VoxDownloadTrack.VIDEO)
                    val o = storage.getTrackFile(id, VoxDownloadTrack.ORIGINAL_AUDIO)
                    val t = storage.getTrackFile(id, VoxDownloadTrack.TRANSLATED_AUDIO)
                    if (v.exists() && v.length() > 0 && o.exists() && o.length() > 0 &&
                        (stored.request.translationMode == VoxTranslationMode.NONE || (t.exists() && t.length() > 0))) {
                        VoxDownloadState.READY_FOR_MUX
                    } else {
                        VoxDownloadState.PAUSED
                    }
                }
                VoxDownloadState.READY_FOR_MUX,
                VoxDownloadState.CANCELLED,
                VoxDownloadState.FAILED -> stored.state
                else -> VoxDownloadState.PAUSED
            }

            val job = VoxDownloadJob(
                request = stored.request,
                initialState = restoredState,
                initialVideo = stored.videoProgress,
                initialOriginalAudio = stored.originalAudioProgress,
                initialTranslatedAudio = stored.translatedAudioProgress,
                initialErrorCode = stored.errorCode,
                initialErrorMessage = stored.errorMessage,
                initialPublishedUri = stored.publishedUri,
                initialPublishedFilePath = stored.publishedFilePath,
                initialActualVideoHeight = stored.actualVideoHeight,
                requestedQuality = stored.requestedQuality ?: stored.request.qualityPreference.label,
                actualQuality = stored.actualQuality ?: (if (stored.actualVideoHeight > 0) "${stored.actualVideoHeight}p" else null),
                fallbackReason = stored.fallbackReason,
                translationState = stored.translationState
            )
            job.durationMs = stored.durationMs
            job.hasTranslatedAudio = stored.hasTranslatedAudio
            job.finalFileBytes = stored.finalFileBytes
            job.processingTimeMs = stored.processingTimeMs
            job.processingSourceBytes = stored.processingSourceBytes
            job.processingSamples = stored.processingSamples
            job.lastProgressAt = stored.lastProgressAt
            jobs[id] = job
        }
    }

    fun getJob(downloadId: String): VoxDownloadJob? = jobs[downloadId]

    fun getAllJobs(): List<VoxDownloadJob> = jobs.values.toList().sortedByDescending { it.request.createdAt }

    fun findJobByVideoId(videoId: String): VoxDownloadJob? {
        return jobs.values.firstOrNull { it.request.videoId == videoId }
    }

    fun findCompletedJobByVideoId(videoId: String): VoxDownloadJob? {
        return jobs.values.firstOrNull { it.request.videoId == videoId && it.state == VoxDownloadState.COMPLETED && !it.publishedUri.isNullOrBlank() }
    }

    fun findActiveJobByVideoId(videoId: String): VoxDownloadJob? {
        return jobs.values.firstOrNull {
            it.request.videoId == videoId &&
            it.state != VoxDownloadState.COMPLETED &&
            it.state != VoxDownloadState.FAILED &&
            it.state != VoxDownloadState.PAUSED &&
            it.state != VoxDownloadState.CANCELLED
        }
    }

    fun findPausedJobByVideoId(videoId: String): VoxDownloadJob? {
        return jobs.values.firstOrNull { it.request.videoId == videoId && it.state == VoxDownloadState.PAUSED }
    }

    fun findFailedJobByVideoId(videoId: String): VoxDownloadJob? {
        return jobs.values.firstOrNull { it.request.videoId == videoId && (it.state == VoxDownloadState.FAILED || it.state == VoxDownloadState.CANCELLED) }
    }

    fun addOrUpdateJob(job: VoxDownloadJob) {
        jobs[job.downloadId] = job
        persistJob(job)
    }

    @Synchronized
    @JvmOverloads
    fun persistJob(job: VoxDownloadJob, state: VoxDownloadState = job.state) {
        storage.saveJobMetadata(
            request = job.request,
            state = state,
            videoProgress = job.videoProgress,
            originalAudioProgress = job.originalAudioProgress,
            translatedAudioProgress = job.translatedAudioProgress,
            errorCode = job.errorCode,
            errorMessage = job.errorMessage,
            publishedUri = job.publishedUri,
            publishedFilePath = job.publishedFilePath,
            actualVideoHeight = job.actualVideoHeight,
            requestedQuality = job.requestedQuality,
            actualQuality = job.actualQuality,
            fallbackReason = job.fallbackReason,
            translationState = job.translationState,
            durationMs = job.durationMs,
            hasTranslatedAudio = job.hasTranslatedAudio,
            finalFileBytes = job.finalFileBytes,
            processingTimeMs = job.processingTimeMs,
            processingSourceBytes = job.processingSourceBytes,
            processingSamples = job.processingSamples,
            lastProgressAt = job.lastProgressAt
        )
    }

    fun deleteJob(downloadId: String): Boolean {
        jobs.remove(downloadId)
        return storage.deleteJobDir(downloadId)
    }
}
