package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
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
    val storage: VoxDownloadStorage,
    private val repository: VoxDownloadRepository,
    private val streamResolver: VoxStreamResolver = DefaultVoxStreamResolver(),
    private val translationResolver: VoxTranslationResolver = DefaultVoxTranslationResolver(),
    private val downloader: VoxSegmentDownloader = VoxSegmentDownloader(
        httpClient = com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.createDirectMediaDownloadClient(),
        storage = storage
    ),
    private val translationDownloader: VoxSegmentDownloader = downloader,
    private val publisher: VoxDownloadPublisher? = null,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) {

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<VoxDownloadListener>>()
    private val globalListeners = CopyOnWriteArrayList<VoxDownloadListener>()
    private val scheduledJobs = ConcurrentHashMap.newKeySet<String>()
    private val pendingQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val progressLock = Any()
    private val trackExecutor: ExecutorService = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "VoxTrackDownloader").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private var lastProgressPersistedAt = 0L

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
                    val pub = VoxMediaStorePublisher(appContext)
                    val directDownloader = VoxSegmentDownloader(
                        httpClient = com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.createDirectMediaDownloadClient(),
                        storage = stor
                    )
                    val transDownloader = VoxSegmentDownloader(
                        httpClient = com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.createTranslationDownloadClient(appContext),
                        storage = stor
                    )
                    val transResolver = DefaultVoxTranslationResolver(
                        oauthTokenProvider = { com.liskovsoft.smartyoutubetv2.common.prefs.VotData.instance(appContext).getOAuthToken() }
                    )
                    val streamResolver = DefaultVoxStreamResolver()
                    val downloadExecutor = Executors.newSingleThreadExecutor { runnable ->
                        Thread(runnable, "VoxDownloadWorker").apply {
                            priority = Thread.MIN_PRIORITY
                        }
                    }
                    VoxDownloadCoordinator(
                        storage = stor,
                        repository = repo,
                        streamResolver = streamResolver,
                        translationResolver = transResolver,
                        downloader = directDownloader,
                        translationDownloader = transDownloader,
                        publisher = pub,
                        executor = downloadExecutor
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
    @JvmOverloads
    fun startDownload(request: VoxDownloadRequest, listener: VoxDownloadListener? = null): String {
        if (listener != null) {
            addListener(request.downloadId, listener)
        }

        val existing = repository.getJob(request.downloadId)
        if (existing?.state == VoxDownloadState.COMPLETED) return request.downloadId
        val job = if (existing != null) {
            existing.bumpGeneration()
            existing.isCancelledFlag.set(false)
            existing.isPausedFlag.set(false)
            existing.lastOperation = "QUEUED"
            existing
        } else {
            val newJob = VoxDownloadJob(request, initialState = VoxDownloadState.QUEUED)
            newJob.lastOperation = "QUEUED"
            repository.addOrUpdateJob(newJob)
            newJob
        }

        VoxSafeLogger.i(VoxLogCategory.DOWNLOAD, VoxLogCode.DOWNLOAD_QUEUED, "Задание скачивания добавлено в очередь", mapOf("downloadId" to request.downloadId))

        if (activeJobId == null || activeJobId == request.downloadId) {
            activeJobId = request.downloadId
            scheduleJobExecution(job)
        } else {
            job.updateState(VoxDownloadState.QUEUED)
            repository.persistJob(job)
            notifyStateChange(job)
            if (!pendingQueue.contains(request.downloadId)) {
                pendingQueue.offer(request.downloadId)
            }
        }
        return request.downloadId
    }

    /**
     * Возобновляет приостановленное или прерванное задание.
     */
    @Synchronized
    @JvmOverloads
    fun resumeDownload(downloadId: String, listener: VoxDownloadListener? = null): Boolean {
        if (scheduledJobs.contains(downloadId)) return false
        if (listener != null) {
            addListener(downloadId, listener)
        }

        val job = repository.getJob(downloadId) ?: return false
        if (job.state == VoxDownloadState.COMPLETED) return false

        job.bumpGeneration()
        job.isCancelledFlag.set(false)
        job.isPausedFlag.set(false)
        job.retryCount++
        job.lastOperation = "RESUME"
        job.averageSpeedMbps = 0.0
        job.peakSpeedMbps = 0.0

        VoxSafeLogger.i(
            VoxLogCategory.DOWNLOAD,
            VoxLogCode.DOWNLOAD_RESUMED,
            "Возобновление скачивания",
            mapOf("downloadId" to downloadId, "retryCount" to job.retryCount.toString())
        )

        if (activeJobId == null || activeJobId == downloadId) {
            activeJobId = downloadId
            scheduleJobExecution(job)
        } else {
            job.updateState(VoxDownloadState.QUEUED)
            repository.persistJob(job)
            notifyStateChange(job)
            if (!pendingQueue.contains(downloadId)) {
                pendingQueue.offer(downloadId)
            }
        }
        return true
    }

    /**
     * Запускает процесс мультиплексирования для задания в состоянии READY_FOR_MUX.
     */
    @Synchronized
    fun muxDownload(downloadId: String, listener: VoxDownloadListener? = null): Boolean {
        if (listener != null) {
            addListener(downloadId, listener)
        }
        val job = repository.getJob(downloadId) ?: return false
        if (job.state == VoxDownloadState.MUXED) {
            notifyProgress(job)
            return true
        }
        if (job.state != VoxDownloadState.READY_FOR_MUX) {
            return false
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
        if (job.state == VoxDownloadState.COMPLETED) return
        job.isPausedFlag.set(true)
        job.bumpGeneration()
        job.updateState(VoxDownloadState.PAUSED)
        repository.persistJob(job)
        notifyStateChange(job)
        pendingQueue.remove(downloadId)
        var wasActive = false
        synchronized(this) {
            if (activeJobId == downloadId) {
                activeJobId = null
                wasActive = true
            }
        }
        if (wasActive) {
            processNextQueuedJob()
        }
    }

    /**
     * Отменяет задание и удаляет неполные временные файлы.
     */
    fun cancelDownload(downloadId: String) {
        val job = repository.getJob(downloadId) ?: return
        job.isCancelledFlag.set(true)
        job.bumpGeneration()
        job.lastOperation = "CANCEL"
        job.updateState(VoxDownloadState.CANCELLED)
        VoxSafeLogger.i(VoxLogCategory.DOWNLOAD, VoxLogCode.DOWNLOAD_CANCELLED, "Загрузка видео отменена", mapOf("downloadId" to downloadId))
        repository.persistJob(job)
        notifyStateChange(job)
        pendingQueue.remove(downloadId)
        var wasActive = false
        synchronized(this) {
            if (activeJobId == downloadId) {
                activeJobId = null
                wasActive = true
            }
        }

        // Удаляем незавершенные файлы треков
        for (track in VoxDownloadTrack.values()) {
            val file = storage.getTrackFile(downloadId, track)
            val progress = job.getSnapshot().let {
                when (track) {
                    VoxDownloadTrack.VIDEO -> it.video
                    VoxDownloadTrack.ORIGINAL_AUDIO -> it.originalAudio
                    VoxDownloadTrack.TRANSLATED_AUDIO -> it.translatedAudio
                }
            }
            if (file.exists() && progress.state != VoxTrackState.COMPLETED) {
                file.delete()
            }
        }

        // Удаляем временный файл мультиплексирования
        val tmpMkv = storage.getTmpOutputFile(downloadId)
        if (tmpMkv.exists()) {
            tmpMkv.delete()
        }

        if (wasActive) {
            processNextQueuedJob()
        }
    }

    /**
     * Полностью удаляет задание, его директорию и опубликованный файл (если есть).
     */
    fun deleteDownload(downloadId: String): Boolean {
        val job = repository.getJob(downloadId) ?: return false
        if (isJobActive(downloadId) || VoxDownloadServicePolicy.isActiveState(job.state)) return false
        if (!job.publishedUri.isNullOrBlank()) {
            val removed = try { publisher?.deletePublishedFile(job.publishedUri) ?: false }
                catch (ignored: Exception) { false }
            if (!removed && isPublishedFileAvailable(job)) return false
        }
        cancelDownload(downloadId)
        listeners.remove(downloadId)
        return repository.deleteJob(downloadId)
    }

    fun getJob(downloadId: String): VoxDownloadJob? = repository.getJob(downloadId)

    fun getAllJobs(): List<VoxDownloadJob> = repository.getAllJobs()

    fun findCompletedJob(videoId: String): VoxDownloadJob? = repository.findCompletedJobByVideoId(videoId)

    fun findJobByVideoId(videoId: String): VoxDownloadJob? = repository.findJobByVideoId(videoId)

    fun findActiveJob(videoId: String): VoxDownloadJob? = repository.findActiveJobByVideoId(videoId)

    fun findPausedJob(videoId: String): VoxDownloadJob? = repository.findPausedJobByVideoId(videoId)

    fun findFailedJob(videoId: String): VoxDownloadJob? = repository.findFailedJobByVideoId(videoId)

    fun isPublishedFileAvailable(job: VoxDownloadJob): Boolean {
        return publisher?.isPublishedFileAvailable(job.publishedUri) ?: false
    }

    fun isJobActive(downloadId: String): Boolean = scheduledJobs.contains(downloadId)

    fun getProcessingJob(): VoxDownloadJob? = activeJobId?.let { repository.getJob(it) }

    fun getQueuedCount(): Int = repository.getAllJobs().count {
        it.downloadId != activeJobId && (it.state == VoxDownloadState.QUEUED || it.state == VoxDownloadState.IDLE) && !it.isCancelled() && !it.isPaused()
    }

    /** Повторная обработка сохраняет проверенные исходники и тот же downloadId. */
    @Synchronized
    fun retryDownload(downloadId: String, force: Boolean = false): Boolean {
        if (scheduledJobs.contains(downloadId)) return false
        val job = repository.getJob(downloadId) ?: return false
        if (job.state != VoxDownloadState.FAILED && job.state != VoxDownloadState.CANCELLED) return false

        if (!force) {
            when (job.errorCode) {
                VoxDownloadErrorCode.STORAGE_FULL,
                VoxDownloadErrorCode.INSUFFICIENT_STORAGE,
                VoxDownloadErrorCode.UNSUPPORTED_CODEC,
                VoxDownloadErrorCode.UNSUPPORTED_FORMAT -> {
                    VoxLog.w(TAG, "Cannot retry download $downloadId due to permanent error: ${job.errorCode}")
                    return false
                }
                else -> { /* allowed */ }
            }
        }

        for (track in VoxDownloadTrack.values()) {
            val p = when (track) {
                VoxDownloadTrack.VIDEO -> job.videoProgress
                VoxDownloadTrack.ORIGINAL_AUDIO -> job.originalAudioProgress
                VoxDownloadTrack.TRANSLATED_AUDIO -> job.translatedAudioProgress
            }
            val file = storage.getTrackFile(downloadId, track)
            if (!file.isFile || file.length() <= 0 || file.length() != p.bytesDownloaded || p.state != VoxTrackState.COMPLETED) {
                job.updateTrackProgress(track, if (file.exists()) file.length() else 0L, p.totalBytes, VoxTrackState.PENDING)
            }
        }
        job.updateState(VoxDownloadState.QUEUED)
        repository.persistJob(job)
        return resumeDownload(downloadId)
    }

    @Synchronized
    fun processNextQueuedJob() {
        if (activeJobId != null) return
        var nextId: String? = null
        while (pendingQueue.isNotEmpty()) {
            val candidateId = pendingQueue.poll() ?: break
            val candidateJob = repository.getJob(candidateId)
            if (candidateJob != null &&
                (candidateJob.state == VoxDownloadState.QUEUED || candidateJob.state == VoxDownloadState.IDLE) &&
                !candidateJob.isCancelled() && !candidateJob.isPaused()
            ) {
                nextId = candidateId
                break
            }
        }
        if (nextId == null) {
            val candidate = repository.getAllJobs()
                .filter { (it.state == VoxDownloadState.QUEUED || it.state == VoxDownloadState.IDLE) && !it.isCancelled() && !it.isPaused() }
                .minByOrNull { it.request.createdAt }
            nextId = candidate?.downloadId
        }
        if (nextId != null) {
            val job = repository.getJob(nextId) ?: return
            activeJobId = nextId
            scheduleJobExecution(job)
        }
    }

    private fun scheduleJobExecution(job: VoxDownloadJob) {
        val added = scheduledJobs.add(job.downloadId)
        val generation = job.generation.get()
        if (!added) return
        executor.submit {
            try {
                val stale = isStale(job, generation)
                if (!stale) executeJob(job)
            }
            finally { scheduledJobs.remove(job.downloadId) }
        }
    }

    private fun executeJob(job: VoxDownloadJob) {
        val downloadId = job.downloadId
        val expectedGen = job.generation.get()

        synchronized(this) {
            if (activeJobId != null && activeJobId != downloadId) {
                VoxLog.d(TAG, "Another download is already active ($activeJobId), queueing $downloadId")
                job.updateState(VoxDownloadState.QUEUED)
                repository.persistJob(job)
                notifyStateChange(job)
                if (!pendingQueue.contains(downloadId)) {
                    pendingQueue.offer(downloadId)
                }
                return
            }
            activeJobId = downloadId
        }

        try {
            if (isStale(job, expectedGen)) return
            job.lastOperation = "START"
            VoxSafeLogger.i(
                VoxLogCategory.DOWNLOAD,
                VoxLogCode.DOWNLOAD_STARTED,
                "Старт выполнения задания скачивания",
                mapOf("downloadId" to downloadId)
            )

            if (job.state == VoxDownloadState.MUXED && job.finalFileBytes > 0) {
                val output = storage.getOutputFile(downloadId)
                validateOutputFile(output, job.request.translationMode != VoxTranslationMode.NONE)
                if (output.length() != job.finalFileBytes) {
                    throw VoxDownloadException(VoxDownloadErrorCode.STORAGE_ERROR, "Final file size changed")
                }
                publishCompletedFile(job, expectedGen, output)
                return
            }

            if (job.state == VoxDownloadState.READY_FOR_MUX) {
                executeMuxing(job, expectedGen)
                return
            }

            // 1. Этап подготовки перевода
            var resolvedTranslation: VoxResolvedTranslation? = null
            if (job.request.translationMode != VoxTranslationMode.NONE &&
                job.translatedAudioProgress.state != VoxTrackState.COMPLETED) {
                job.updateState(VoxDownloadState.PREPARING_TRANSLATION)
                job.lastOperation = "PREPARING_TRANSLATION"
                VoxSafeLogger.i(
                    VoxLogCategory.DOWNLOAD,
                    VoxLogCode.DOWNLOAD_TRANSLATION_STARTED,
                    "Загрузка перевода начата",
                    mapOf("downloadId" to downloadId)
                )
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
            job.lastOperation = "RESOLVING"
            repository.persistJob(job)
            notifyStateChange(job)

            val videoStream = if (job.videoProgress.state != VoxTrackState.COMPLETED) {
                streamResolver.resolveVideoStream(job.request.videoId, job.request.qualityPreference)
            } else null
            if (videoStream != null && videoStream.height > 0) {
                job.actualVideoHeight = videoStream.height
                job.actualQuality = "${videoStream.height}p"
                if (job.request.qualityPreference != VoxQualityPreference.QUALITY_AUTO &&
                    videoStream.height < job.request.qualityPreference.maxResolution) {
                    job.fallbackReason = "Качество снижено до ${videoStream.height}p (запрошено ${job.request.qualityPreference.label})"
                }
                repository.persistJob(job)
            }

            val originalAudioStream = if (job.originalAudioProgress.state != VoxTrackState.COMPLETED) {
                streamResolver.resolveOriginalAudio(job.request.videoId)
            } else null

            if (isStale(job, expectedGen)) return

            // Storage space pre-check before downloading streams (Worst-case estimate: Prompt #37-#39)
            val videoBytes = videoStream?.contentLength ?: 0L
            val audioBytes = originalAudioStream?.contentLength ?: 0L
            val transBytes = if (resolvedTranslation != null) 10 * 1024 * 1024L else 0L
            val totalTrackBytes = videoBytes + audioBytes + transBytes
            if (totalTrackBytes > 0L) {
                val worstCaseRequiredBytes = (totalTrackBytes * 2) + 10 * 1024 * 1024L
                if (!storage.hasEnoughSpace(worstCaseRequiredBytes)) {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.INSUFFICIENT_STORAGE,
                        "Insufficient storage space: required $worstCaseRequiredBytes bytes, available ${storage.getAvailableBytes()}"
                    )
                }
            }

            if (isStale(job, expectedGen)) return

            // 3. Параллельная загрузка медиа-потоков (видео, оригинальный звук, перевод)
            val needVideo = job.videoProgress.state != VoxTrackState.COMPLETED && videoStream != null
            val needAudio = job.originalAudioProgress.state != VoxTrackState.COMPLETED && originalAudioStream != null
            val needTranslation = job.request.translationMode != VoxTranslationMode.NONE &&
                job.translatedAudioProgress.state != VoxTrackState.COMPLETED

            if (needVideo || needAudio || needTranslation) {
                // Оповещаем о начале стадий загрузки (для слушателей UI и совместимости тестов)
                if (needVideo) {
                    job.updateState(VoxDownloadState.DOWNLOADING_VIDEO)
                    job.lastOperation = "DOWNLOAD_VIDEO"
                    job.updateTrackProgress(
                        VoxDownloadTrack.VIDEO,
                        storage.getTrackFileSize(downloadId, VoxDownloadTrack.VIDEO),
                        videoStream?.contentLength,
                        VoxTrackState.IN_PROGRESS
                    )
                    notifyStateChange(job)
                }
                if (needAudio) {
                    job.updateState(VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO)
                    job.lastOperation = "DOWNLOAD_AUDIO"
                    job.updateTrackProgress(
                        VoxDownloadTrack.ORIGINAL_AUDIO,
                        storage.getTrackFileSize(downloadId, VoxDownloadTrack.ORIGINAL_AUDIO),
                        originalAudioStream?.contentLength,
                        VoxTrackState.IN_PROGRESS
                    )
                    notifyStateChange(job)
                }
                if (needTranslation) {
                    job.updateState(VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO)
                    job.lastOperation = "DOWNLOAD_TRANSLATION"
                    job.updateTrackProgress(
                        VoxDownloadTrack.TRANSLATED_AUDIO,
                        storage.getTrackFileSize(downloadId, VoxDownloadTrack.TRANSLATED_AUDIO),
                        null,
                        VoxTrackState.IN_PROGRESS
                    )
                    notifyStateChange(job)
                }

                if (isStale(job, expectedGen)) return

                // Устанавливаем общее состояние параллельной загрузки медиа
                job.updateState(VoxDownloadState.DOWNLOADING_MEDIA)
                job.lastOperation = "DOWNLOAD_MEDIA_PARALLEL"
                repository.persistJob(job)
                notifyStateChange(job)

                val downloadStartMs = System.currentTimeMillis()
                job.parallelStreamsCount = listOf(needVideo, needAudio, needTranslation).count { it }

                VoxSafeLogger.i(
                    VoxLogCategory.DOWNLOAD,
                    VoxLogCode.DOWNLOAD_STARTED,
                    "Параллельная загрузка дорожек начата",
                    mapOf("downloadId" to downloadId, "streams" to job.parallelStreamsCount.toString())
                )

                val videoFuture = if (needVideo && videoStream != null) {
                    trackExecutor.submit {
                        val t0 = System.currentTimeMillis()
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
                            },
                            onRangeResumed = { resumedOffset ->
                                job.rangeResumptionsCount++
                                job.bytesResumed += resumedOffset
                            },
                            onStallDetected = {
                                job.stallEventsCount++
                                job.networkReconnectCount++
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
                        job.videoElapsedMs = System.currentTimeMillis() - t0
                        VoxSafeLogger.i(
                            VoxLogCategory.DOWNLOAD,
                            VoxLogCode.DOWNLOAD_VIDEO_COMPLETED,
                            "Загрузка видео завершена",
                            mapOf("downloadId" to downloadId, "bytes" to targetFile.length().toString(), "elapsedMs" to job.videoElapsedMs.toString())
                        )
                    }
                } else null

                val audioFuture = if (needAudio && originalAudioStream != null) {
                    trackExecutor.submit {
                        val t0 = System.currentTimeMillis()
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
                            },
                            onRangeResumed = { resumedOffset ->
                                job.rangeResumptionsCount++
                                job.bytesResumed += resumedOffset
                            },
                            onStallDetected = {
                                job.stallEventsCount++
                                job.networkReconnectCount++
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
                        job.audioElapsedMs = System.currentTimeMillis() - t0
                        VoxSafeLogger.i(
                            VoxLogCategory.DOWNLOAD,
                            VoxLogCode.DOWNLOAD_AUDIO_COMPLETED,
                            "Загрузка аудио завершена",
                            mapOf("downloadId" to downloadId, "bytes" to targetFile.length().toString(), "elapsedMs" to job.audioElapsedMs.toString())
                        )
                    }
                } else null

                val transFuture = if (needTranslation) {
                    trackExecutor.submit {
                        val t0 = System.currentTimeMillis()
                        val translationUrl = resolvedTranslation?.url
                            ?: translationResolver.resolveTranslation(
                                videoId = job.request.videoId,
                                mode = job.request.translationMode,
                                isCancelled = { isStale(job, expectedGen) }
                            ).url

                        val targetFile = storage.getTrackFile(downloadId, VoxDownloadTrack.TRANSLATED_AUDIO)
                        translationDownloader.download(
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
                            },
                            onRangeResumed = { resumedOffset ->
                                job.rangeResumptionsCount++
                                job.bytesResumed += resumedOffset
                            },
                            onStallDetected = {
                                job.stallEventsCount++
                                job.networkReconnectCount++
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
                        job.hasTranslatedAudio = true
                        job.translationElapsedMs = System.currentTimeMillis() - t0
                        VoxSafeLogger.i(
                            VoxLogCategory.DOWNLOAD,
                            VoxLogCode.DOWNLOAD_TRANSLATION_COMPLETED,
                            "Загрузка перевода завершена",
                            mapOf("downloadId" to downloadId, "bytes" to targetFile.length().toString(), "elapsedMs" to job.translationElapsedMs.toString())
                        )
                    }
                } else null

                val futures = listOfNotNull(videoFuture, audioFuture, transFuture)
                try {
                    for (f in futures) {
                        f.get()
                    }
                } catch (e: Exception) {
                    futures.forEach { it.cancel(true) }
                    val cause = if (e is java.util.concurrent.ExecutionException) e.cause ?: e else e
                    if (cause is Exception) throw cause else throw Exception(cause)
                }

                job.downloadElapsedMs = System.currentTimeMillis() - downloadStartMs
                val totalBytes = job.videoProgress.bytesDownloaded + job.originalAudioProgress.bytesDownloaded + job.translatedAudioProgress.bytesDownloaded
                if (job.downloadElapsedMs > 0) {
                    val avgSpeed = (totalBytes * 8.0) / (job.downloadElapsedMs * 1000.0)
                    job.averageSpeedMbps = avgSpeed
                    if (avgSpeed > job.peakSpeedMbps) {
                        job.peakSpeedMbps = avgSpeed
                    }
                }
                repository.persistJob(job)
            }

            if (isStale(job, expectedGen)) return

            // 6. Проверка инварианта: все 3 файла должны существовать и иметь ненулевой размер
            val videoFile = storage.getTrackFile(downloadId, VoxDownloadTrack.VIDEO)
            val origAudioFile = storage.getTrackFile(downloadId, VoxDownloadTrack.ORIGINAL_AUDIO)
            val transAudioFile = storage.getTrackFile(downloadId, VoxDownloadTrack.TRANSLATED_AUDIO)

            if (!videoFile.exists() || videoFile.length() <= 0L ||
                !origAudioFile.exists() || origAudioFile.length() <= 0L ||
                (job.request.translationMode != VoxTranslationMode.NONE &&
                    (!transAudioFile.exists() || transAudioFile.length() <= 0L))
            ) {
                throw VoxDownloadException(
                    VoxDownloadErrorCode.STORAGE_ERROR,
                    "Incomplete tracks detected before READY_FOR_MUX (video=${videoFile.length()}B, origAudio=${origAudioFile.length()}B, transAudio=${transAudioFile.length()}B)"
                )
            }

            // Финальное техническое состояние загрузки: READY_FOR_MUX
            job.updateState(VoxDownloadState.READY_FOR_MUX)
            repository.persistJob(job)
            notifyStateChange(job)
            VoxLog.d(TAG, "Download job $downloadId finished backend downloads successfully (READY_FOR_MUX)")

            // Автоматический переход к мультиплексированию MKV
            executeMuxing(job, expectedGen)

        } catch (t: Throwable) {
            if (job.generation.get() == expectedGen) {
                val failedStage = job.state.stageName
                val (category, safeReason) = VoxDownloadFailureClassifier.classifyWithReason(t)
                val rootCause = VoxDownloadFailureClassifier.extractRootCause(t) ?: t
                val safeRootCauseName = rootCause.javaClass.simpleName

                val errorCode = if (t is VoxDownloadException) {
                    t.code
                } else {
                    when (category) {
                        VoxDownloadFailureCategory.STORAGE_FULL -> VoxDownloadErrorCode.STORAGE_FULL
                        VoxDownloadFailureCategory.STORAGE_PERMISSION -> VoxDownloadErrorCode.STORAGE_PERMISSION
                        VoxDownloadFailureCategory.TEMP_FILE_FAILED -> VoxDownloadErrorCode.TEMP_FILE_FAILED
                        VoxDownloadFailureCategory.OUTPUT_MOVE_FAILED -> VoxDownloadErrorCode.OUTPUT_MOVE_FAILED
                        VoxDownloadFailureCategory.FINALIZE_FAILED -> VoxDownloadErrorCode.FINALIZE_FAILED
                        VoxDownloadFailureCategory.NETWORK -> VoxDownloadErrorCode.NETWORK
                        VoxDownloadFailureCategory.TIMEOUT -> VoxDownloadErrorCode.TIMEOUT
                        VoxDownloadFailureCategory.RESOLVE_FAILED -> VoxDownloadErrorCode.RESOLVE_FAILED
                        VoxDownloadFailureCategory.VIDEO_DOWNLOAD_FAILED -> VoxDownloadErrorCode.VIDEO_DOWNLOAD_FAILED
                        VoxDownloadFailureCategory.AUDIO_DOWNLOAD_FAILED -> VoxDownloadErrorCode.AUDIO_DOWNLOAD_FAILED
                        VoxDownloadFailureCategory.TRANSLATION_FAILED -> VoxDownloadErrorCode.TRANSLATION_FAILED
                        VoxDownloadFailureCategory.PACKAGING_FAILED -> VoxDownloadErrorCode.PACKAGING_FAILED
                        VoxDownloadFailureCategory.MUX_FAILED -> VoxDownloadErrorCode.MUX_FAILED
                        VoxDownloadFailureCategory.INVALID_MEDIA -> VoxDownloadErrorCode.INVALID_MEDIA
                        VoxDownloadFailureCategory.UNSUPPORTED_FORMAT -> VoxDownloadErrorCode.UNSUPPORTED_FORMAT
                        VoxDownloadFailureCategory.CHECKSUM_FAILED -> VoxDownloadErrorCode.CHECKSUM_FAILED
                        VoxDownloadFailureCategory.CANCELLED -> VoxDownloadErrorCode.CANCELLED
                        else -> VoxDownloadErrorCode.UNKNOWN
                    }
                }

                job.lastFailedStage = failedStage
                job.lastErrorCategory = category.name
                job.safeRootCause = "$safeRootCauseName:$safeReason"

                val userMessage = VoxDownloadFailureClassifier.getUserMessage(category)

                if (errorCode == VoxDownloadErrorCode.CANCELLED) {
                    job.updateState(VoxDownloadState.CANCELLED)
                } else {
                    job.updateState(VoxDownloadState.FAILED, errorCode, userMessage)
                    notifyError(
                        job = job,
                        code = errorCode,
                        message = userMessage,
                        failedStage = failedStage,
                        category = category,
                        safeReason = safeReason
                    )
                }
                repository.persistJob(job)
                notifyStateChange(job)
            }
        } finally {
            var wasActive = false
            synchronized(this) {
                if (activeJobId == downloadId) {
                    activeJobId = null
                    wasActive = true
                }
            }
            if (wasActive) {
                processNextQueuedJob()
            }
        }
    }

    private fun executeMuxing(job: VoxDownloadJob, expectedGen: Long) {
        val downloadId = job.downloadId
        if (isStale(job, expectedGen)) return

        val videoFile = storage.getTrackFile(downloadId, VoxDownloadTrack.VIDEO)
        val origAudioFile = storage.getTrackFile(downloadId, VoxDownloadTrack.ORIGINAL_AUDIO)
        val transAudioFile = storage.getTrackFile(downloadId, VoxDownloadTrack.TRANSLATED_AUDIO)
        val outputFile = storage.getOutputFile(downloadId)

        val translated = job.request.translationMode != VoxTranslationMode.NONE
        val requiredBytes = videoFile.length() + origAudioFile.length() +
            (if (translated) transAudioFile.length() else 0L) + 5 * 1024 * 1024L
        if (!storage.hasEnoughSpace(requiredBytes)) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.INSUFFICIENT_STORAGE,
                "Not enough disk space for MKV muxing (required: ${requiredBytes / (1024 * 1024)} MB)"
            )
        }

        job.updateState(VoxDownloadState.MUXING)
        job.lastOperation = "PACKAGING"
        job.packagingStarted = true
        VoxSafeLogger.i(
            VoxLogCategory.DOWNLOAD,
            VoxLogCode.DOWNLOAD_PACKAGING_STARTED,
            "Упаковка медиафайла начата",
            mapOf("downloadId" to downloadId)
        )
        repository.persistJob(job)
        notifyStateChange(job)
        VoxLog.d(TAG, "Download job $downloadId starting Matroska muxing")

        val sources = mutableListOf<com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxSampleSource>()
        val processingStart = System.nanoTime()
        try {
            val vUid = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)
            val oUid = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)
            val tUid = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)

            val vSource = com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMediaExtractorSource(
                mediaFile = videoFile,
                assignedTrackNumber = 1,
                assignedTrackUid = vUid,
                trackType = com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMuxTrackType.VIDEO,
                trackName = "Видео",
                language = "und",
                isDefaultTrack = true
            )
            sources.add(vSource)

            val oSource = com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMediaExtractorSource(
                mediaFile = origAudioFile,
                assignedTrackNumber = 2,
                assignedTrackUid = oUid,
                trackType = com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMuxTrackType.AUDIO_ORIGINAL,
                trackName = "Оригинал",
                language = "und",
                isDefaultTrack = !translated
            )
            sources.add(oSource)

            if (translated) {
                val tSource = com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMediaExtractorSource(
                    mediaFile = transAudioFile,
                    assignedTrackNumber = 3,
                    assignedTrackUid = tUid,
                    trackType = com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMuxTrackType.AUDIO_TRANSLATED,
                    trackName = "Перевод",
                    language = "rus",
                    isDefaultTrack = true
                )
                sources.add(tSource)
            }

            val muxer = com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMatroskaMuxer()
            val result = muxer.mux(
                sources = sources,
                outputFile = outputFile,
                videoTitle = job.request.videoTitle,
                isCancelled = job.isCancelledFlag,
                progressListener = { muxProgress ->
                    if (!isStale(job, expectedGen)) {
                        job.updateMuxProgress(muxProgress.bytesProcessed, muxProgress.totalInputBytes, muxProgress.percent)
                        job.processingSamples = muxProgress.processedSamples
                        job.processingTimeMs = muxProgress.elapsedMs
                        job.processingSourceBytes = muxProgress.totalInputBytes
                        job.lastProgressAt = System.currentTimeMillis()
                        notifyProgress(job)
                    }
                },
                shouldStop = { isStale(job, expectedGen) },
                onFinalizing = {
                    job.updateState(VoxDownloadState.FINALIZING)
                    job.lastOperation = "FINALIZE"
                    job.packagingCompleted = true
                    VoxSafeLogger.i(
                        VoxLogCategory.DOWNLOAD,
                        VoxLogCode.DOWNLOAD_PACKAGING_COMPLETED,
                        "Упаковка завершена, финализация",
                        mapOf("downloadId" to downloadId)
                    )
                    VoxSafeLogger.i(
                        VoxLogCategory.DOWNLOAD,
                        VoxLogCode.DOWNLOAD_FINALIZE_STARTED,
                        "Финализация контейнера начата",
                        mapOf("downloadId" to downloadId)
                    )
                    repository.persistJob(job)
                    notifyStateChange(job)
                },
                onStalled = {
                    if (!isStale(job, expectedGen)) {
                        job.updateState(VoxDownloadState.FAILED, VoxDownloadErrorCode.PROCESSING_STALLED,
                            "Обработка остановилась. Повторите упаковку файла.")
                        repository.persistJob(job)
                        notifyStateChange(job)
                    }
                }
            )

            if (isStale(job, expectedGen)) return

            // Валидация созданного MKV перед присвоением MUXED
            validateOutputFile(outputFile, translated)
            if (result.videoSamplesCount <= 0 || result.origAudioSamplesCount <= 0 ||
                (translated && result.transAudioSamplesCount <= 0)) {
                throw VoxDownloadException(VoxDownloadErrorCode.MEDIA_PARSE_ERROR, "Empty output track")
            }
            job.hasTranslatedAudio = translated
            job.durationMs = result.durationMs
            job.finalFileBytes = result.totalBytesWritten
            job.processingTimeMs = (System.nanoTime() - processingStart) / 1_000_000L
            job.packagingElapsedMs = job.processingTimeMs
            job.lastProgressAt = System.currentTimeMillis()

            job.updateMuxProgress(result.totalBytesWritten, result.totalBytesWritten, 100)
            job.updateState(VoxDownloadState.MUXED)
            repository.persistJob(job)
            notifyStateChange(job)
            VoxLog.d(TAG, "Download job $downloadId successfully multiplexed into MKV: ${result.outputFile.absolutePath} (${result.totalBytesWritten} bytes, duration=${result.durationMs}ms)")

            publishCompletedFile(job, expectedGen, outputFile)
        } catch (e: com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxProcessingStalledException) {
            throw VoxDownloadException(VoxDownloadErrorCode.PROCESSING_STALLED, "Обработка остановилась. Повторите упаковку файла.", e)
        } catch (e: InterruptedException) {
            if (job.isCancelled()) {
                job.updateState(VoxDownloadState.CANCELLED)
                repository.persistJob(job)
                notifyStateChange(job)
            }
        } finally {
            sources.forEach { try { it.close() } catch (ignored: Exception) {} }
        }
    }

    private fun publishCompletedFile(job: VoxDownloadJob, expectedGen: Long, outputFile: File) {
        val downloadId = job.downloadId
        val finalizeStart = System.currentTimeMillis()
        job.lastOperation = "PUBLISH"
        job.finalizeCompleted = true
        // Публикация в MediaStore
        if (publisher != null) {
            if (isStale(job, expectedGen)) return
            job.updateState(VoxDownloadState.PUBLISHING)
            repository.persistJob(job)
            notifyStateChange(job)
            VoxLog.d(TAG, "Download job $downloadId starting MediaStore publication")

            val pubUri = publisher.publish(
                outputFile = outputFile,
                videoTitle = job.request.videoTitle,
                isCancelled = job.isCancelledFlag,
                onProgress = { bytesCopied, totalBytes, percent ->
                    if (!isStale(job, expectedGen)) {
                        job.updatePublishProgress(bytesCopied, totalBytes, percent)
                        notifyProgress(job)
                    }
                }
            )

            if (isStale(job, expectedGen)) return

            job.finalizeElapsedMs = System.currentTimeMillis() - finalizeStart
            job.publishedUri = pubUri.toString()
                if (!publisher.isPublishedFileAvailable(job.publishedUri) || storage.getPublishedFileSize(job.publishedUri) != job.finalFileBytes) {
                    throw VoxDownloadException(VoxDownloadErrorCode.STORAGE_ERROR, "Published file validation failed")
                }
                // Сначала фиксируем метаданные; UI не увидит COMPLETED до успешной записи.
                VoxDownloadCompletionValidator.check(job, storage.getPublishedFileSize(job.publishedUri))
                repository.persistJob(job, VoxDownloadState.COMPLETED)
                job.updateState(VoxDownloadState.COMPLETED)
                VoxSafeLogger.i(VoxLogCategory.DOWNLOAD, VoxLogCode.DOWNLOAD_COMPLETED, "Загрузка и сохранение видео успешно завершены", mapOf("downloadId" to downloadId))

                // Очищаем внутренние временные .part файлы и копию MKV для экономии диска
                storage.cleanInternalSourcesAfterPublication(downloadId)

                notifyStateChange(job)
                VoxLog.d(TAG, "Download job $downloadId published successfully to MediaStore: $pubUri")
            } else {
                job.publishedUri = android.net.Uri.fromFile(outputFile).toString()
                job.publishedFilePath = outputFile.absolutePath
                VoxDownloadCompletionValidator.check(job, storage.getPublishedFileSize(job.publishedUri))
                repository.persistJob(job, VoxDownloadState.COMPLETED)
                job.updateState(VoxDownloadState.COMPLETED)
                VoxSafeLogger.i(VoxLogCategory.DOWNLOAD, VoxLogCode.DOWNLOAD_COMPLETED, "Загрузка видео успешно завершена", mapOf("downloadId" to downloadId))
                notifyStateChange(job)
            }
    }

    private fun validateOutputFile(outputFile: File, translated: Boolean) {
        if (!outputFile.exists() || outputFile.length() <= 0L) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.STORAGE_ERROR,
                "Output MKV file does not exist or is empty: ${outputFile.absolutePath}"
            )
        }
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(outputFile.absolutePath)
            val numTracks = extractor.trackCount
            val requiredTracks = if (translated) 3 else 2
            if (numTracks < requiredTracks) {
                throw VoxDownloadException(
                    VoxDownloadErrorCode.MEDIA_PARSE_ERROR,
                    "Output MKV has only $numTracks tracks, expected at least $requiredTracks"
                )
            }
            var hasVideo = false
            var audioCount = 0
            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) audioCount++
            }
            if (!hasVideo || audioCount < (if (translated) 2 else 1)) {
                throw VoxDownloadException(
                    VoxDownloadErrorCode.MEDIA_PARSE_ERROR,
                    "Output MKV missing required streams (hasVideo=$hasVideo, audioTracks=$audioCount)"
                )
            }
        } catch (e: VoxDownloadException) {
            throw e
        } catch (e: Exception) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.MEDIA_PARSE_ERROR,
                "Failed to validate output MKV file: ${e.message}"
            )
        } finally {
            try { extractor.release() } catch (ignored: Exception) {}
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
        synchronized(progressLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastProgressPersistedAt >= 1_000L) {
                repository.persistJob(job)
                lastProgressPersistedAt = now
            }
            val snapshot = job.getSnapshot()
            listeners[job.downloadId]?.forEach {
                try { it.onProgressUpdated(snapshot) } catch (ignored: Exception) {}
            }
            globalListeners.forEach {
                try { it.onProgressUpdated(snapshot) } catch (ignored: Exception) {}
            }
        }
    }

    private fun notifyError(
        job: VoxDownloadJob,
        code: VoxDownloadErrorCode,
        message: String,
        failedStage: String = job.lastFailedStage ?: job.state.stageName,
        category: VoxDownloadFailureCategory? = null,
        safeReason: String? = null
    ) {
        val safeCategory = category ?: when (code) {
            VoxDownloadErrorCode.INSUFFICIENT_STORAGE,
            VoxDownloadErrorCode.STORAGE_FULL -> VoxDownloadFailureCategory.STORAGE_FULL
            VoxDownloadErrorCode.STORAGE_PERMISSION -> VoxDownloadFailureCategory.STORAGE_PERMISSION
            VoxDownloadErrorCode.PACKAGING_FAILED -> VoxDownloadFailureCategory.PACKAGING_FAILED
            VoxDownloadErrorCode.MUX_FAILED -> VoxDownloadFailureCategory.MUX_FAILED
            VoxDownloadErrorCode.URL_EXPIRED -> VoxDownloadFailureCategory.URL_EXPIRED
            VoxDownloadErrorCode.STREAM_UNAVAILABLE,
            VoxDownloadErrorCode.RESOLVE_FAILED -> VoxDownloadFailureCategory.RESOLVE_FAILED
            VoxDownloadErrorCode.AUTH_REQUIRED -> VoxDownloadFailureCategory.HTTP_FORBIDDEN
            VoxDownloadErrorCode.NETWORK_ERROR,
            VoxDownloadErrorCode.NETWORK -> VoxDownloadFailureCategory.NETWORK
            VoxDownloadErrorCode.TIMEOUT -> VoxDownloadFailureCategory.TIMEOUT
            else -> VoxDownloadFailureCategory.UNKNOWN
        }

        val safeLogCode = when (safeCategory) {
            VoxDownloadFailureCategory.URL_EXPIRED -> VoxLogCode.DOWNLOAD_URL_EXPIRED
            VoxDownloadFailureCategory.STORAGE_FULL -> VoxLogCode.DOWNLOAD_STORAGE_FULL
            VoxDownloadFailureCategory.RESOLVE_FAILED,
            VoxDownloadFailureCategory.STREAM_UNAVAILABLE,
            VoxDownloadFailureCategory.VIDEO_UNAVAILABLE -> VoxLogCode.DOWNLOAD_FORMAT_UNAVAILABLE
            VoxDownloadFailureCategory.HTTP_FORBIDDEN -> VoxLogCode.DOWNLOAD_HTTP_403
            else -> VoxLogCode.DOWNLOAD_FAILED
        }

        val freeMb = try { storage.getAvailableBytes() / (1024 * 1024) } catch (ignored: Exception) { -1L }
        val tempCount = try { storage.getTemporaryFileCount(job.downloadId) } catch (ignored: Exception) { 0 }

        val contextMap = mapOf(
            "stage" to failedStage,
            "errorCategory" to safeCategory.name,
            "reason" to (safeReason ?: code.name),
            "operation" to (job.lastOperation ?: "UNKNOWN"),
            "retryCount" to job.retryCount.toString(),
            "videoBytesDownloaded" to job.videoProgress.bytesDownloaded.toString(),
            "audioBytesDownloaded" to job.originalAudioProgress.bytesDownloaded.toString(),
            "translationBytesDownloaded" to job.translatedAudioProgress.bytesDownloaded.toString(),
            "freeStorageMb" to freeMb.toString(),
            "outputContainer" to "mkv",
            "temporaryFileCount" to tempCount.toString()
        )

        VoxSafeLogger.e(
            VoxLogCategory.DOWNLOAD,
            safeLogCode,
            message,
            contextMap
        )

        listeners[job.downloadId]?.forEach {
            try { it.onError(job.downloadId, code, message) } catch (ignored: Exception) {}
        }
        globalListeners.forEach {
            try { it.onError(job.downloadId, code, message) } catch (ignored: Exception) {}
        }
    }

    fun getDiagnosticsSummary(): Map<String, Any> {
        val allJobs = repository.getAllJobs()
        val lastJob = allJobs.maxByOrNull { it.request.createdAt }
        val freeMb = try { storage.getAvailableBytes() / (1024 * 1024) } catch (ignored: Exception) { -1L }
        val queueLength = getQueuedCount()
        val activeJobs = if (activeJobId != null) 1 else 0
        val pausedJobs = allJobs.count { it.state == VoxDownloadState.PAUSED }
        val failedJobs = allJobs.count { it.state == VoxDownloadState.FAILED }

        if (lastJob == null) {
            return mapOf(
                "hasJobs" to false,
                "queueLength" to queueLength,
                "activeJobs" to activeJobs,
                "pausedJobs" to pausedJobs,
                "failedJobs" to failedJobs,
                "freeStorageMb" to freeMb
            )
        }
        val totalBytes = lastJob.videoProgress.bytesDownloaded + lastJob.originalAudioProgress.bytesDownloaded + lastJob.translatedAudioProgress.bytesDownloaded
        val avgBytesPerSec = if (lastJob.downloadElapsedMs > 0) (totalBytes * 1000L) / lastJob.downloadElapsedMs else 0L
        return mapOf(
            "queueLength" to queueLength,
            "activeJobs" to activeJobs,
            "pausedJobs" to pausedJobs,
            "failedJobs" to failedJobs,
            "lastStage" to (lastJob.lastFailedStage ?: lastJob.state.stageName),
            "lastErrorCategory" to (lastJob.lastErrorCategory ?: lastJob.errorCode?.name ?: "NONE"),
            "lastOperation" to (lastJob.lastOperation ?: "NONE"),
            "retryCount" to lastJob.retryCount,
            "completedVideoBytes" to lastJob.videoProgress.bytesDownloaded,
            "completedAudioBytes" to lastJob.originalAudioProgress.bytesDownloaded,
            "translatedAudioPresent" to lastJob.hasTranslatedAudio,
            "packagingStarted" to lastJob.packagingStarted,
            "packagingCompleted" to lastJob.packagingCompleted,
            "finalizeCompleted" to lastJob.finalizeCompleted,
            "freeStorageMb" to freeMb,
            "downloadPerformance" to mapOf(
                "totalBytes" to totalBytes,
                "totalElapsedMs" to lastJob.downloadElapsedMs,
                "averageBytesPerSecond" to avgBytesPerSec,
                "averageMbps" to String.format(java.util.Locale.US, "%.2f", lastJob.averageSpeedMbps),
                "peakMbps" to String.format(java.util.Locale.US, "%.2f", lastJob.peakSpeedMbps),
                "videoElapsedMs" to lastJob.videoElapsedMs,
                "audioElapsedMs" to lastJob.audioElapsedMs,
                "translationElapsedMs" to lastJob.translationElapsedMs,
                "packagingElapsedMs" to lastJob.packagingElapsedMs,
                "finalizeElapsedMs" to lastJob.finalizeElapsedMs,
                "stallCount" to lastJob.stallEventsCount,
                "resumeCount" to lastJob.rangeResumptionsCount,
                "rangeResumeCount" to lastJob.rangeResumptionsCount,
                "networkReconnectCount" to lastJob.networkReconnectCount,
                "retryCount" to lastJob.retryCount,
                "parallelConnections" to lastJob.parallelStreamsCount
            )
        )
    }
}
