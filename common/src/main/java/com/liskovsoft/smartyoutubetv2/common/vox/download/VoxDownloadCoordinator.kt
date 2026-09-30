package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.vox.external.VoxLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Java-совместимый слушатель прогресса и изменения состояния скачивания.
 */
interface VoxDownloadListener {
    fun onStateChanged(progress: VoxDownloadProgress)
    fun onProgressUpdated(progress: VoxDownloadProgress)
    fun onError(downloadId: String, errorCode: VoxDownloadErrorCode, message: String)
}

/**
 * Главный координатор процесса скачивания видео с переводом VOX 5.
 */
class VoxDownloadCoordinator(
    private val storage: VoxDownloadStorage,
    private val repository: VoxDownloadRepository,
    private val streamResolver: VoxStreamResolver = DefaultVoxStreamResolver(),
    private val translationResolver: VoxTranslationResolver = DefaultVoxTranslationResolver(),
    private val downloader: VoxSegmentDownloader = VoxSegmentDownloader(storage = storage),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) {

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<VoxDownloadListener>>()
    private val globalListeners = CopyOnWriteArrayList<VoxDownloadListener>()

    @Volatile
    private var activeJobId: String? = null

    companion object {
        private const val TAG = "VoxDownloadCoordinator"

        @Volatile
        private var instance: VoxDownloadCoordinator? = null

        @JvmStatic
        fun instance(context: Context): VoxDownloadCoordinator {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val stor = VoxDownloadStorage(appContext)
                    val repo = VoxDownloadRepository(stor)
                    VoxDownloadCoordinator(
                        storage = stor,
                        repository = repo
                    ).also { instance = it }
                }
            }
        }
    }

    /**
     * Регистрирует слушателя для конкретного задания.
     */
    fun addListener(downloadId: String, listener: VoxDownloadListener) {
        listeners.computeIfAbsent(downloadId) { CopyOnWriteArrayList() }.add(listener)
    }

    /**
     * Удаляет слушателя конкретного задания.
     */
    fun removeListener(downloadId: String, listener: VoxDownloadListener) {
        listeners[downloadId]?.remove(listener)
    }

    /**
     * Регистрирует глобального слушателя всех заданий.
     */
    fun addGlobalListener(listener: VoxDownloadListener) {
        globalListeners.add(listener)
    }

    /**
     * Удаляет глобального слушателя.
     */
    fun removeGlobalListener(listener: VoxDownloadListener) {
        globalListeners.remove(listener)
    }

    /**
     * Запускает новое задание скачивания.
     */
    @Synchronized
    fun startDownload(request: VoxDownloadRequest, listener: VoxDownloadListener? = null): String {
        if (listener != null) {
            addListener(request.downloadId, listener)
        }

        val existing = repository.getJob(request.downloadId)
        val job = if (existing != null) {
            existing.bumpGeneration()
            existing.isCancelledFlag.set(false)
            existing.isPausedFlag.set(false)
            existing
        } else {
            val newJob = VoxDownloadJob(request)
            repository.addOrUpdateJob(newJob)
            newJob
        }

        scheduleJobExecution(job)
        return request.downloadId
    }

    /**
     * Возобновляет приостановленное или прерванное задание.
     */
    @Synchronized
    fun resumeDownload(downloadId: String, listener: VoxDownloadListener? = null): Boolean {
        if (listener != null) {
            addListener(downloadId, listener)
        }

        val job = repository.getJob(downloadId) ?: return false
        if (job.state == VoxDownloadState.READY_FOR_MUX) {
            notifyProgress(job)
            return true
        }

        job.bumpGeneration()
        job.isCancelledFlag.set(false)
        job.isPausedFlag.set(false)
        scheduleJobExecution(job)
        return true
    }

    /**
     * Приостанавливает активное задание.
     */
    fun pauseDownload(downloadId: String) {
        val job = repository.getJob(downloadId) ?: return
        job.isPausedFlag.set(true)
        job.bumpGeneration()
        job.updateState(VoxDownloadState.PAUSED)
        repository.persistJob(job)
        notifyStateChange(job)
        if (activeJobId == downloadId) {
            activeJobId = null
        }
    }

    /**
     * Отменяет задание и удаляет неполные временные файлы.
     */
    fun cancelDownload(downloadId: String) {
        val job = repository.getJob(downloadId) ?: return
        job.isCancelledFlag.set(true)
        job.bumpGeneration()
        job.updateState(VoxDownloadState.CANCELLED)
        repository.persistJob(job)
        notifyStateChange(job)
        if (activeJobId == downloadId) {
            activeJobId = null
        }

        // Удаляем незавершенные файлы треков
        for (track in VoxDownloadTrack.values()) {
            val file = storage.getTrackFile(downloadId, track)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    /**
     * Полностью удаляет задание и его директорию.
     */
    fun deleteDownload(downloadId: String): Boolean {
        cancelDownload(downloadId)
        listeners.remove(downloadId)
        return repository.deleteJob(downloadId)
    }

    fun getJob(downloadId: String): VoxDownloadJob? = repository.getJob(downloadId)

    fun getAllJobs(): List<VoxDownloadJob> = repository.getAllJobs()

    fun isJobActive(downloadId: String): Boolean = activeJobId == downloadId

    private fun scheduleJobExecution(job: VoxDownloadJob) {
        executor.submit {
            executeJob(job)
        }
    }

    private fun executeJob(job: VoxDownloadJob) {
        val downloadId = job.downloadId
        val expectedGen = job.generation.get()

        synchronized(this) {
            if (activeJobId != null && activeJobId != downloadId) {
                // В MVP выполняется одно активное задание за раз
                VoxLog.d(TAG, "Another download is already active ($activeJobId), queueing $downloadId")
                job.updateState(VoxDownloadState.PAUSED)
                repository.persistJob(job)
                notifyStateChange(job)
                return
            }
            activeJobId = downloadId
        }

        try {
            if (isStale(job, expectedGen)) return

            // 1. Этап подготовки перевода
            var resolvedTranslation: VoxResolvedTranslation? = null
            if (job.translatedAudioProgress.state != VoxTrackState.COMPLETED) {
                job.updateState(VoxDownloadState.PREPARING_TRANSLATION)
                repository.persistJob(job)
                notifyStateChange(job)

                resolvedTranslation = translationResolver.resolveTranslation(
                    videoId = job.request.videoId,
                    mode = job.request.translationMode,
                    isCancelled = { isStale(job, expectedGen) },
                    onWaitingProgress = { _ ->
                        if (!isStale(job, expectedGen)) {
                            notifyProgress(job)
                        }
                    }
                )
            }

            if (isStale(job, expectedGen)) return

            // 2. Этап резолвинга медиа-потоков видео и оригинального аудио
            job.updateState(VoxDownloadState.RESOLVING_STREAMS)
            repository.persistJob(job)
            notifyStateChange(job)

            val videoStream = if (job.videoProgress.state != VoxTrackState.COMPLETED) {
                streamResolver.resolveVideoStream(job.request.videoId, job.request.qualityPreference)
            } else null

            val originalAudioStream = if (job.originalAudioProgress.state != VoxTrackState.COMPLETED) {
                streamResolver.resolveOriginalAudio(job.request.videoId)
            } else null

            if (isStale(job, expectedGen)) return

            // 3. Загрузка видео-потока
            if (job.videoProgress.state != VoxTrackState.COMPLETED && videoStream != null) {
                job.updateState(VoxDownloadState.DOWNLOADING_VIDEO)
                job.updateTrackProgress(
                    VoxDownloadTrack.VIDEO,
                    storage.getTrackFileSize(downloadId, VoxDownloadTrack.VIDEO),
                    videoStream.contentLength,
                    VoxTrackState.IN_PROGRESS
                )
                repository.persistJob(job)
                notifyStateChange(job)

                val targetFile = storage.getTrackFile(downloadId, VoxDownloadTrack.VIDEO)
                downloader.download(
                    initialUrl = videoStream.url,
                    targetFile = targetFile,
                    isCancelled = { isStale(job, expectedGen) },
                    onProgress = { bytes, total ->
                        if (!isStale(job, expectedGen)) {
                            job.updateTrackProgress(VoxDownloadTrack.VIDEO, bytes, total, VoxTrackState.IN_PROGRESS)
                            notifyProgress(job)
                        }
                    },
                    urlProvider = {
                        streamResolver.resolveVideoStream(job.request.videoId, job.request.qualityPreference).url
                    }
                )

                if (!targetFile.exists() || targetFile.length() <= 0L) {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.STORAGE_ERROR,
                        "Video track file is missing or empty after download"
                    )
                }

                job.updateTrackProgress(
                    VoxDownloadTrack.VIDEO,
                    targetFile.length(),
                    videoStream.contentLength ?: targetFile.length(),
                    VoxTrackState.COMPLETED
                )
                repository.persistJob(job)
            }

            if (isStale(job, expectedGen)) return

            // 4. Загрузка оригинального аудио
            if (job.originalAudioProgress.state != VoxTrackState.COMPLETED && originalAudioStream != null) {
                job.updateState(VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO)
                job.updateTrackProgress(
                    VoxDownloadTrack.ORIGINAL_AUDIO,
                    storage.getTrackFileSize(downloadId, VoxDownloadTrack.ORIGINAL_AUDIO),
                    originalAudioStream.contentLength,
                    VoxTrackState.IN_PROGRESS
                )
                repository.persistJob(job)
                notifyStateChange(job)

                val targetFile = storage.getTrackFile(downloadId, VoxDownloadTrack.ORIGINAL_AUDIO)
                downloader.download(
                    initialUrl = originalAudioStream.url,
                    targetFile = targetFile,
                    isCancelled = { isStale(job, expectedGen) },
                    onProgress = { bytes, total ->
                        if (!isStale(job, expectedGen)) {
                            job.updateTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, bytes, total, VoxTrackState.IN_PROGRESS)
                            notifyProgress(job)
                        }
                    },
                    urlProvider = {
                        streamResolver.resolveOriginalAudio(job.request.videoId).url
                    }
                )

                if (!targetFile.exists() || targetFile.length() <= 0L) {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.STORAGE_ERROR,
                        "Original audio track file is missing or empty after download"
                    )
                }

                job.updateTrackProgress(
                    VoxDownloadTrack.ORIGINAL_AUDIO,
                    targetFile.length(),
                    originalAudioStream.contentLength ?: targetFile.length(),
                    VoxTrackState.COMPLETED
                )
                repository.persistJob(job)
            }

            if (isStale(job, expectedGen)) return

            // 5. Загрузка перевода
            if (job.translatedAudioProgress.state != VoxTrackState.COMPLETED) {
                job.updateState(VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO)
                job.updateTrackProgress(
                    VoxDownloadTrack.TRANSLATED_AUDIO,
                    storage.getTrackFileSize(downloadId, VoxDownloadTrack.TRANSLATED_AUDIO),
                    null,
                    VoxTrackState.IN_PROGRESS
                )
                repository.persistJob(job)
                notifyStateChange(job)

                val translationUrl = resolvedTranslation?.url
                    ?: translationResolver.resolveTranslation(
                        videoId = job.request.videoId,
                        mode = job.request.translationMode,
                        isCancelled = { isStale(job, expectedGen) }
                    ).url

                val targetFile = storage.getTrackFile(downloadId, VoxDownloadTrack.TRANSLATED_AUDIO)
                downloader.download(
                    initialUrl = translationUrl,
                    targetFile = targetFile,
                    isCancelled = { isStale(job, expectedGen) },
                    onProgress = { bytes, total ->
                        if (!isStale(job, expectedGen)) {
                            job.updateTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO, bytes, total, VoxTrackState.IN_PROGRESS)
                            notifyProgress(job)
                        }
                    },
                    urlProvider = {
                        translationResolver.resolveTranslation(
                            videoId = job.request.videoId,
                            mode = job.request.translationMode,
                            isCancelled = { isStale(job, expectedGen) }
                        ).url
                    }
                )

                if (!targetFile.exists() || targetFile.length() <= 0L) {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.STORAGE_ERROR,
                        "Translated audio track file is missing or empty after download"
                    )
                }

                job.updateTrackProgress(
                    VoxDownloadTrack.TRANSLATED_AUDIO,
                    targetFile.length(),
                    targetFile.length(),
                    VoxTrackState.COMPLETED
                )
                repository.persistJob(job)
            }

            if (isStale(job, expectedGen)) return

            // 6. Проверка инварианта: все 3 файла должны существовать и иметь ненулевой размер
            val videoFile = storage.getTrackFile(downloadId, VoxDownloadTrack.VIDEO)
            val origAudioFile = storage.getTrackFile(downloadId, VoxDownloadTrack.ORIGINAL_AUDIO)
            val transAudioFile = storage.getTrackFile(downloadId, VoxDownloadTrack.TRANSLATED_AUDIO)

            if (!videoFile.exists() || videoFile.length() <= 0L ||
                !origAudioFile.exists() || origAudioFile.length() <= 0L ||
                !transAudioFile.exists() || transAudioFile.length() <= 0L
            ) {
                throw VoxDownloadException(
                    VoxDownloadErrorCode.STORAGE_ERROR,
                    "Incomplete tracks detected before READY_FOR_MUX (video=${videoFile.length()}B, origAudio=${origAudioFile.length()}B, transAudio=${transAudioFile.length()}B)"
                )
            }

            // Финальное техническое состояние Patch #4: READY_FOR_MUX
            job.updateState(VoxDownloadState.READY_FOR_MUX)
            repository.persistJob(job)
            notifyStateChange(job)
            VoxLog.d(TAG, "Download job $downloadId finished backend downloads successfully (READY_FOR_MUX)")

        } catch (e: VoxDownloadException) {
            if (job.generation.get() == expectedGen) {
                if (e.code == VoxDownloadErrorCode.CANCELLED) {
                    job.updateState(VoxDownloadState.CANCELLED)
                } else {
                    job.updateState(VoxDownloadState.FAILED, e.code, e.message)
                    notifyError(job, e.code, e.message ?: "Download failed")
                }
                repository.persistJob(job)
                notifyStateChange(job)
            }
        } catch (e: Exception) {
            if (job.generation.get() == expectedGen) {
                job.updateState(VoxDownloadState.FAILED, VoxDownloadErrorCode.UNKNOWN, e.message)
                notifyError(job, VoxDownloadErrorCode.UNKNOWN, e.message ?: "Unknown error")
                repository.persistJob(job)
                notifyStateChange(job)
            }
        } finally {
            synchronized(this) {
                if (activeJobId == downloadId) {
                    activeJobId = null
                }
            }
        }
    }

    private fun isStale(job: VoxDownloadJob, expectedGen: Long): Boolean {
        return job.generation.get() != expectedGen || job.isCancelled() || job.isPaused()
    }

    private fun notifyStateChange(job: VoxDownloadJob) {
        val snapshot = job.getSnapshot()
        listeners[job.downloadId]?.forEach {
            try { it.onStateChanged(snapshot) } catch (ignored: Exception) {}
        }
        globalListeners.forEach {
            try { it.onStateChanged(snapshot) } catch (ignored: Exception) {}
        }
    }

    private fun notifyProgress(job: VoxDownloadJob) {
        val snapshot = job.getSnapshot()
        listeners[job.downloadId]?.forEach {
            try { it.onProgressUpdated(snapshot) } catch (ignored: Exception) {}
        }
        globalListeners.forEach {
            try { it.onProgressUpdated(snapshot) } catch (ignored: Exception) {}
        }
    }

    private fun notifyError(job: VoxDownloadJob, code: VoxDownloadErrorCode, message: String) {
        listeners[job.downloadId]?.forEach {
            try { it.onError(job.downloadId, code, message) } catch (ignored: Exception) {}
        }
        globalListeners.forEach {
            try { it.onError(job.downloadId, code, message) } catch (ignored: Exception) {}
        }
    }
}
