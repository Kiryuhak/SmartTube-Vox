package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import android.os.Build
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Безопасное хранилище временных файлов и состояния заданий скачивания VOX 5.
 */
class VoxDownloadStorage(val context: Context) {

    companion object {
        private const val DOWNLOADS_DIR = "vox-downloads"
        private const val JOB_METADATA_FILE = "job.json"
        private const val OUTPUT_MEDIA_FILE = "output.mkv"
        private const val TMP_OUTPUT_MEDIA_FILE = "output.mkv.tmp"
        private const val MIN_SAFETY_MARGIN_BYTES = 25 * 1024 * 1024L // 25 MB safety margin
    }

    /**
     * Корневая директория для всех скачиваний.
     * Отдает приоритет externalFilesDir (если смонтирован), иначе filesDir.
     */
    val baseDir: File by lazy {
        val ext = try {
            context.getExternalFilesDir(null)
        } catch (e: Exception) {
            null
        }
        val target = if (ext != null && ext.canWrite()) {
            File(ext, DOWNLOADS_DIR)
        } else {
            File(context.filesDir, DOWNLOADS_DIR)
        }
        if (!target.exists()) {
            target.mkdirs()
        }
        target
    }

    /**
     * Валидирует downloadId на отсутствие недопустимых символов и path traversal последовательностей.
     */
    fun requireValidDownloadId(downloadId: String) {
        if (downloadId.isBlank() ||
            downloadId.contains("..") ||
            downloadId.contains("/") ||
            downloadId.contains("\\") ||
            downloadId.contains(":") ||
            downloadId.contains("\u0000") ||
            !downloadId.matches(Regex("^[a-zA-Z0-9_\\-\\.]+$"))
        ) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.STORAGE_ERROR,
                "Invalid or unsafe downloadId: $downloadId"
            )
        }
    }

    /**
     * Возвращает изолированную директорию конкретного задания.
     */
    fun getJobDir(downloadId: String): File {
        requireValidDownloadId(downloadId)
        val dir = File(baseDir, downloadId).canonicalFile
        val baseCanonical = baseDir.canonicalFile
        if (!dir.path.startsWith(baseCanonical.path)) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.STORAGE_ERROR,
                "Path traversal detected in downloadId: $downloadId"
            )
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Возвращает дескриптор файла для конкретного трека.
     */
    fun getTrackFile(downloadId: String, track: VoxDownloadTrack): File {
        return File(getJobDir(downloadId), track.fileName)
    }

    /**
     * Возвращает дескриптор итогового файла MKV.
     */
    fun getOutputFile(downloadId: String): File {
        return File(getJobDir(downloadId), OUTPUT_MEDIA_FILE)
    }

    /**
     * Возвращает дескриптор временного файла MKV в процессе мультиплексирования.
     */
    fun getTmpOutputFile(downloadId: String): File {
        return File(getJobDir(downloadId), TMP_OUTPUT_MEDIA_FILE)
    }

    /**
     * Возвращает текущий размер частично скачанного файла трека.
     */
    fun getTrackFileSize(downloadId: String, track: VoxDownloadTrack): Long {
        val file = getTrackFile(downloadId, track)
        return if (file.exists() && file.isFile) file.length() else 0L
    }

    /**
     * Сохраняет снимок состояния и метаданных задания в `job.json`.
     * Внимание: сохраняются только логические идентификаторы, ни при каких условиях
     * не сохраняются подписанные URL, токены или заголовки авторизации.
     */
    @Synchronized
    fun saveJobMetadata(
        request: VoxDownloadRequest,
        state: VoxDownloadState,
        videoProgress: VoxTrackProgress,
        originalAudioProgress: VoxTrackProgress,
        translatedAudioProgress: VoxTrackProgress,
        errorCode: VoxDownloadErrorCode? = null,
        errorMessage: String? = null,
        publishedUri: String? = null,
        publishedFilePath: String? = null,
        actualVideoHeight: Int = 0,
        requestedQuality: String? = request.qualityPreference.label,
        actualQuality: String? = if (actualVideoHeight > 0) "${actualVideoHeight}p" else null,
        fallbackReason: String? = null,
        translationState: VoxDownloadTranslationState = VoxDownloadTranslationState.DOWNLOADED_TRANSLATED,
        durationMs: Long = 0L,
        ageRating: String? = null,
        hasTranslatedAudio: Boolean = false,
        finalFileBytes: Long = 0L,
        processingTimeMs: Long = 0L,
        processingSourceBytes: Long = 0L,
        processingSamples: Long = 0L,
        lastProgressAt: Long = 0L
    ) {
        val jobDir = getJobDir(request.downloadId)
        val file = File(jobDir, JOB_METADATA_FILE)

        val json = JSONObject().apply {
            put("downloadId", request.downloadId)
            put("videoId", request.videoId)
            put("videoTitle", request.videoTitle)
            put("qualityPreference", request.qualityPreference.name)
            put("translationMode", request.translationMode.name)
            put("translationState", translationState.name)
            put("createdAt", request.createdAt)
            put("state", state.name)
            put("hasTranslatedAudio", hasTranslatedAudio)
            put("finalFileBytes", finalFileBytes)
            put("processingTimeMs", processingTimeMs)
            put("processingSourceBytes", processingSourceBytes)
            put("processingSamples", processingSamples)
            put("lastProgressAt", lastProgressAt)
            if (durationMs > 0) put("durationMs", durationMs)
            if (!ageRating.isNullOrBlank()) put("ageRating", ageRating)
            if (actualVideoHeight > 0) put("actualVideoHeight", actualVideoHeight)
            if (requestedQuality != null) put("requestedQuality", requestedQuality)
            if (actualQuality != null) put("actualQuality", actualQuality)
            if (fallbackReason != null) put("fallbackReason", fallbackReason)
            if (errorCode != null) {
                put("errorCode", errorCode.name)
            }
            if (errorMessage != null) {
                put("errorMessage", errorMessage)
            }
            if (publishedUri != null) {
                put("publishedUri", publishedUri)
            }
            if (publishedFilePath != null) {
                put("publishedFilePath", publishedFilePath)
            }

            put("video", JSONObject().apply {
                put("bytes", videoProgress.bytesDownloaded)
                if (videoProgress.totalBytes != null) put("total", videoProgress.totalBytes)
                put("state", videoProgress.state.name)
            })

            put("originalAudio", JSONObject().apply {
                put("bytes", originalAudioProgress.bytesDownloaded)
                if (originalAudioProgress.totalBytes != null) put("total", originalAudioProgress.totalBytes)
                put("state", originalAudioProgress.state.name)
            })

            put("translatedAudio", JSONObject().apply {
                put("bytes", translatedAudioProgress.bytesDownloaded)
                if (translatedAudioProgress.totalBytes != null) put("total", translatedAudioProgress.totalBytes)
                put("state", translatedAudioProgress.state.name)
            })
        }

        // Атомарная запись через временный файл
        val tempFile = File(jobDir, "$JOB_METADATA_FILE.tmp")
        FileOutputStream(tempFile).use { out ->
            out.write(json.toString(2).toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
        // На Android rename атомарно заменяет существующий файл. Файловые системы,
        // запрещающие замену, используют резервную копию с восстановлением при сбое.
        if (!tempFile.renameTo(file)) {
            val backup = File(jobDir, "$JOB_METADATA_FILE.bak")
            if (!file.isFile || !file.renameTo(backup)) throw java.io.IOException("Cannot back up download metadata")
            if (!tempFile.renameTo(file)) {
                backup.renameTo(file)
                throw java.io.IOException("Cannot commit download metadata")
            }
            backup.delete()
        }
    }

    /**
     * Считывает снимок задания из `job.json`.
     */
    @Synchronized
    fun loadJobMetadata(downloadId: String): StoredJobData? {
        val jobDir = File(baseDir, downloadId)
        val file = File(jobDir, JOB_METADATA_FILE)
        val backup = File(jobDir, "$JOB_METADATA_FILE.bak")
        if (!file.exists() && backup.isFile) backup.renameTo(file)
        if (file.isFile && backup.isFile) backup.delete()
        if (!file.exists() || !file.isFile) return null

        return try {
            val content = file.readText(StandardCharsets.UTF_8)
            val json = JSONObject(content)

            val req = VoxDownloadRequest(
                downloadId = json.getString("downloadId"),
                videoId = json.getString("videoId"),
                videoTitle = json.optString("videoTitle", "Unknown Video"),
                qualityPreference = try {
                    VoxQualityPreference.valueOf(json.optString("qualityPreference", VoxQualityPreference.QUALITY_AUTO.name))
                } catch (e: Exception) {
                    VoxQualityPreference.QUALITY_AUTO
                },
                translationMode = try {
                    VoxTranslationMode.valueOf(json.optString("translationMode", VoxTranslationMode.STANDARD.name))
                } catch (e: Exception) {
                    VoxTranslationMode.STANDARD
                },
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )

            val rawState = json.optString("state", VoxDownloadState.PAUSED.name)
            val state = try {
                VoxDownloadState.valueOf(rawState)
            } catch (e: Exception) {
                VoxDownloadState.PAUSED
            }

            val rawErrorCode = if (json.has("errorCode")) json.getString("errorCode") else null
            val errorCode = if (!rawErrorCode.isNullOrEmpty()) {
                try { VoxDownloadErrorCode.valueOf(rawErrorCode) } catch (e: Exception) { null }
            } else null
            val errorMessage = if (json.has("errorMessage")) json.getString("errorMessage") else null

            val videoObj = json.optJSONObject("video")
            val vBytes = videoObj?.optLong("bytes", 0L) ?: getTrackFileSize(downloadId, VoxDownloadTrack.VIDEO)
            val vTotal = if (videoObj != null && videoObj.has("total")) videoObj.getLong("total") else null
            val vState = parseTrackState(videoObj?.optString("state"), vBytes, vTotal)

            val origObj = json.optJSONObject("originalAudio")
            val oBytes = origObj?.optLong("bytes", 0L) ?: getTrackFileSize(downloadId, VoxDownloadTrack.ORIGINAL_AUDIO)
            val oTotal = if (origObj != null && origObj.has("total")) origObj.getLong("total") else null
            val oState = parseTrackState(origObj?.optString("state"), oBytes, oTotal)

            val transObj = json.optJSONObject("translatedAudio")
            val tBytes = transObj?.optLong("bytes", 0L) ?: getTrackFileSize(downloadId, VoxDownloadTrack.TRANSLATED_AUDIO)
            val tTotal = if (transObj != null && transObj.has("total")) transObj.getLong("total") else null
            val tState = parseTrackState(transObj?.optString("state"), tBytes, tTotal)

            val rawPublishedUri = if (json.has("publishedUri")) json.getString("publishedUri") else null
            val rawPublishedFilePath = if (json.has("publishedFilePath")) json.getString("publishedFilePath") else null
            val rawTranslationState = if (json.has("translationState")) json.getString("translationState") else null
            val translationState = VoxDownloadTranslationState.fromString(rawTranslationState)
            val requestedQuality = if (json.has("requestedQuality")) json.getString("requestedQuality") else req.qualityPreference.label
            val actualQuality = if (json.has("actualQuality")) json.getString("actualQuality") else null
            val fallbackReason = if (json.has("fallbackReason")) json.getString("fallbackReason") else null
            val durationMs = json.optLong("durationMs", 0L)
            val ageRating = if (json.has("ageRating")) json.getString("ageRating") else null

            StoredJobData(
                request = req,
                state = state,
                errorCode = errorCode,
                errorMessage = errorMessage,
                publishedUri = rawPublishedUri,
                publishedFilePath = rawPublishedFilePath,
                actualVideoHeight = json.optInt("actualVideoHeight", 0),
                requestedQuality = requestedQuality,
                actualQuality = actualQuality,
                fallbackReason = fallbackReason,
                translationState = translationState,
                durationMs = durationMs,
                hasTranslatedAudio = json.optBoolean("hasTranslatedAudio", false),
                finalFileBytes = json.optLong("finalFileBytes", 0L),
                processingTimeMs = json.optLong("processingTimeMs", 0L),
                processingSourceBytes = json.optLong("processingSourceBytes", 0L),
                processingSamples = json.optLong("processingSamples", 0L),
                lastProgressAt = json.optLong("lastProgressAt", 0L),
                ageRating = ageRating,
                videoProgress = VoxTrackProgress(VoxDownloadTrack.VIDEO, vBytes, vTotal, vState),
                originalAudioProgress = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, oBytes, oTotal, oState),
                translatedAudioProgress = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO, tBytes, tTotal, tState)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTrackState(raw: String?, bytes: Long, total: Long?): VoxTrackState {
        if (raw != null) {
            try { return VoxTrackState.valueOf(raw) } catch (e: Exception) {}
        }
        return if (total != null && bytes >= total && total > 0) {
            VoxTrackState.COMPLETED
        } else if (bytes > 0) {
            VoxTrackState.IN_PROGRESS
        } else {
            VoxTrackState.PENDING
        }
    }

    /**
     * Очищает внутренние временные .part файлы и локальную копию output.mkv после успешной публикации.
     * Сохраняет job.json с метаданными и publishedUri.
     */
    fun cleanInternalSourcesAfterPublication(downloadId: String) {
        try {
            for (track in VoxDownloadTrack.values()) {
                val file = getTrackFile(downloadId, track)
                if (file.exists()) {
                    file.delete()
                }
            }
            val outputMkv = getOutputFile(downloadId)
            if (outputMkv.exists()) {
                outputMkv.delete()
            }
            val tmpMkv = getTmpOutputFile(downloadId)
            if (tmpMkv.exists()) {
                tmpMkv.delete()
            }
        } catch (e: Exception) {
            // Игнорируем ошибки очистки
        }
    }

    /**
     * Возвращает список идентификаторов всех существующих заданий на диске.
     */
    fun listJobIds(): List<String> {
        val files = baseDir.listFiles() ?: return emptyList()
        return files.filter { it.isDirectory }.map { it.name }
    }

    /**
     * Проверяет наличие свободного места на носителе.
     *
     * @param requiredBytes Ожидаемый объем загружаемых данных
     * @param safetyMarginBytes Запас свободного места (по умолчанию 25 МБ)
     * @return true если места достаточно
     */
    fun hasEnoughSpace(requiredBytes: Long, safetyMarginBytes: Long = MIN_SAFETY_MARGIN_BYTES): Boolean {
        val available = getAvailableBytes()
        if (available <= 0L) {
            val usable = try { baseDir.usableSpace } catch (e: Exception) { 0L }
            if (usable <= 0L) return true
            return usable >= (requiredBytes + safetyMarginBytes)
        }
        return available >= (requiredBytes + safetyMarginBytes)
    }

    /**
     * Возвращает доступный объем свободного места в байтах.
     */
    fun getAvailableBytes(): Long {
        return try {
            val stat = StatFs(baseDir.absolutePath)
            val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                stat.availableBlocksLong * stat.blockSizeLong
            } else {
                @Suppress("DEPRECATION")
                stat.availableBlocks.toLong() * stat.blockSize.toLong()
            }
            if (bytes > 0L) bytes else baseDir.usableSpace
        } catch (e: Exception) {
            try { baseDir.usableSpace } catch (ex: Exception) { 0L }
        }
    }

    /**
     * Возвращает размер опубликованного файла в байтах.
     */
    fun getPublishedFileSize(uriString: String?): Long {
        if (uriString.isNullOrBlank()) return 0L
        return try {
            val uri = android.net.Uri.parse(uriString)
            if ("file".equals(uri.scheme, ignoreCase = true)) {
                val file = uri.path?.let { File(it) }
                if (file != null && file.exists()) file.length() else 0L
            } else {
                var size = try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val stat = pfd.statSize
                        if (stat > 0L) {
                            stat
                        } else {
                            try {
                                java.io.FileInputStream(pfd.fileDescriptor).channel.size().coerceAtLeast(0L)
                            } catch (e: Exception) {
                                0L
                            }
                        }
                    } ?: 0L
                } catch (e: Exception) {
                    0L
                }
                if (size <= 0L) {
                    try {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (sizeIndex != -1 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                                size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                            }
                        }
                    } catch (ignored: Exception) {}
                }
                size
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Возвращает количество временных файлов (.part, .tmp) задания.
     */
    fun getTemporaryFileCount(downloadId: String): Int {
        val dir = File(baseDir, downloadId)
        if (!dir.exists() || !dir.isDirectory) return 0
        return dir.listFiles()?.count { it.isFile && (it.name.endsWith(".part") || it.name.endsWith(".tmp")) } ?: 0
    }

    /**
     * Удаляет изолированную директорию задания и все ее файлы.
     */
    fun deleteJobDir(downloadId: String): Boolean {
        val dir = File(baseDir, downloadId)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }
}

/**
 * Данные восстановленного с диска задания.
 */
data class StoredJobData(
    val request: VoxDownloadRequest,
    val state: VoxDownloadState,
    val errorCode: VoxDownloadErrorCode?,
    val errorMessage: String?,
    val publishedUri: String? = null,
    val publishedFilePath: String? = null,
    val actualVideoHeight: Int = 0,
    val requestedQuality: String? = null,
    val actualQuality: String? = null,
    val fallbackReason: String? = null,
    val translationState: VoxDownloadTranslationState = VoxDownloadTranslationState.DOWNLOADED_TRANSLATED,
    val durationMs: Long = 0L,
    val ageRating: String? = null,
    val hasTranslatedAudio: Boolean = false,
    val finalFileBytes: Long = 0L,
    val processingTimeMs: Long = 0L,
    val processingSourceBytes: Long = 0L,
    val processingSamples: Long = 0L,
    val lastProgressAt: Long = 0L,
    val videoProgress: VoxTrackProgress,
    val originalAudioProgress: VoxTrackProgress,
    val translatedAudioProgress: VoxTrackProgress
)
