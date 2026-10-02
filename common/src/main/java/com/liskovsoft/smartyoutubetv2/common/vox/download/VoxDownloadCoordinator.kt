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
                    VoxDownloadCoordinator(
                        storage = stor,
                        repository = repo,
                        downloader = directDownloader,
                        translationDownloader = transDownloader,
                        publisher = pub
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
        if (job.state == VoxDownloadState.MUXED) {
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

        // Удаляем временный файл мультиплексирования
        val tmpMkv = storage.getTmpOutputFile(downloadId)
        if (tmpMkv.exists()) {
            tmpMkv.delete()
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

    fun findActiveJob(videoId: String): VoxDownloadJob? = repository.findActiveJobByVideoId(videoId)

    fun isPublishedFileAvailable(job: VoxDownloadJob): Boolean {
        return publisher?.isPublishedFileAvailable(job.publishedUri) ?: false
    }

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

            if (job.state == VoxDownloadState.READY_FOR_MUX) {
                executeMuxing(job, expectedGen)
                return
            }

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
            if (videoStream != null && videoStream.height > 0) {
                job.actualVideoHeight = videoStream.height
                repository.persistJob(job)
            }

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

            // Финальное техническое состояние загрузки: READY_FOR_MUX
            job.updateState(VoxDownloadState.READY_FOR_MUX)
            repository.persistJob(job)
            notifyStateChange(job)
            VoxLog.d(TAG, "Download job $downloadId finished backend downloads successfully (READY_FOR_MUX)")

            // Автоматический переход к мультиплексированию MKV
            executeMuxing(job, expectedGen)

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

    private fun executeMuxing(job: VoxDownloadJob, expectedGen: Long) {
        val downloadId = job.downloadId
        if (isStale(job, expectedGen)) return

        val videoFile = storage.getTrackFile(downloadId, VoxDownloadTrack.VIDEO)
        val origAudioFile = storage.getTrackFile(downloadId, VoxDownloadTrack.ORIGINAL_AUDIO)
        val transAudioFile = storage.getTrackFile(downloadId, VoxDownloadTrack.TRANSLATED_AUDIO)
        val outputFile = storage.getOutputFile(downloadId)

        val requiredBytes = videoFile.length() + origAudioFile.length() + transAudioFile.length() + 5 * 1024 * 1024L
        if (!storage.hasEnoughSpace(requiredBytes)) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.INSUFFICIENT_STORAGE,
                "Not enough disk space for MKV muxing (required: ${requiredBytes / (1024 * 1024)} MB)"
            )
        }

        job.updateState(VoxDownloadState.MUXING)
        repository.persistJob(job)
        notifyStateChange(job)
        VoxLog.d(TAG, "Download job $downloadId starting Matroska muxing")

        val sources = mutableListOf<com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxSampleSource>()
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
                isDefaultTrack = false
            )
            sources.add(oSource)

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

            val muxer = com.liskovsoft.smartyoutubetv2.common.vox.mux.VoxMatroskaMuxer()
            val result = muxer.mux(
                sources = sources,
                outputFile = outputFile,
                videoTitle = job.request.videoTitle,
                isCancelled = job.isCancelledFlag,
                progressListener = { muxProgress ->
                    if (!isStale(job, expectedGen)) {
                        job.updateMuxProgress(muxProgress.bytesProcessed, muxProgress.totalInputBytes, muxProgress.percent)
                        notifyProgress(job)
                    }
                }
            )

            if (isStale(job, expectedGen)) return

            // Валидация созданного MKV перед присвоением MUXED
            validateOutputFile(outputFile)

            job.updateMuxProgress(result.totalBytesWritten, result.totalBytesWritten, 100)
            job.updateState(VoxDownloadState.MUXED)
            repository.persistJob(job)
            notifyStateChange(job)
            VoxLog.d(TAG, "Download job $downloadId successfully multiplexed into MKV: ${result.outputFile.absolutePath} (${result.totalBytesWritten} bytes, duration=${result.durationMs}ms)")

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

                job.publishedUri = pubUri.toString()
                job.updateState(VoxDownloadState.COMPLETED)

                // Очищаем внутренние временные .part файлы и копию MKV для экономии диска
                storage.cleanInternalSourcesAfterPublication(downloadId)

                repository.persistJob(job)
                notifyStateChange(job)
                VoxLog.d(TAG, "Download job $downloadId published successfully to MediaStore: $pubUri")
            } else {
                job.updateState(VoxDownloadState.COMPLETED)
                repository.persistJob(job)
                notifyStateChange(job)
            }
        } catch (e: InterruptedException) {
            if (job.isCancelled()) {
                job.updateState(VoxDownloadState.CANCELLED)
                repository.persistJob(job)
                notifyStateChange(job)
            }
        }
    }

    private fun validateOutputFile(outputFile: File) {
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
            if (numTracks < 3) {
                throw VoxDownloadException(
                    VoxDownloadErrorCode.MEDIA_PARSE_ERROR,
                    "Output MKV has only $numTracks tracks, expected at least 3"
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
            if (!hasVideo || audioCount < 2) {
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
