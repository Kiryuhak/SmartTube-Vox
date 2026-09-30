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

            // Если задание не завершилось (не READY_FOR_MUX) и не было отменено,
            // восстанавливаем его в состоянии PAUSED
            val restoredState = if (stored.state == VoxDownloadState.READY_FOR_MUX ||
                stored.state == VoxDownloadState.CANCELLED ||
                stored.state == VoxDownloadState.FAILED
            ) {
                stored.state
            } else {
                VoxDownloadState.PAUSED
            }

            val job = VoxDownloadJob(
                request = stored.request,
                initialState = restoredState,
                initialVideo = stored.videoProgress,
                initialOriginalAudio = stored.originalAudioProgress,
                initialTranslatedAudio = stored.translatedAudioProgress,
                initialErrorCode = stored.errorCode,
                initialErrorMessage = stored.errorMessage
            )
            jobs[id] = job
        }
    }

    fun getJob(downloadId: String): VoxDownloadJob? = jobs[downloadId]

    fun getAllJobs(): List<VoxDownloadJob> = jobs.values.toList().sortedByDescending { it.request.createdAt }

    fun addOrUpdateJob(job: VoxDownloadJob) {
        jobs[job.downloadId] = job
        persistJob(job)
    }

    fun persistJob(job: VoxDownloadJob) {
        storage.saveJobMetadata(
            request = job.request,
            state = job.state,
            videoProgress = job.videoProgress,
            originalAudioProgress = job.originalAudioProgress,
            translatedAudioProgress = job.translatedAudioProgress,
            errorCode = job.errorCode,
            errorMessage = job.errorMessage
        )
    }

    fun deleteJob(downloadId: String): Boolean {
        jobs.remove(downloadId)
        return storage.deleteJobDir(downloadId)
    }
}
