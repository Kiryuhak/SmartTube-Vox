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
class VoxDownloadStorage(private val context: Context) {

    companion object {
        private const val DOWNLOADS_DIR = "vox-downloads"
        private const val JOB_METADATA_FILE = "job.json"
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
     * Возвращает изолированную директорию конкретного задания.
     */
    fun getJobDir(downloadId: String): File {
        val dir = File(baseDir, downloadId)
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
    fun saveJobMetadata(
        request: VoxDownloadRequest,
        state: VoxDownloadState,
        videoProgress: VoxTrackProgress,
        originalAudioProgress: VoxTrackProgress,
        translatedAudioProgress: VoxTrackProgress,
        errorCode: VoxDownloadErrorCode? = null,
        errorMessage: String? = null
    ) {
        val jobDir = getJobDir(request.downloadId)
        val file = File(jobDir, JOB_METADATA_FILE)

        val json = JSONObject().apply {
            put("downloadId", request.downloadId)
            put("videoId", request.videoId)
            put("videoTitle", request.videoTitle)
            put("qualityPreference", request.qualityPreference.name)
            put("translationMode", request.translationMode.name)
            put("createdAt", request.createdAt)
            put("state", state.name)
            if (errorCode != null) {
                put("errorCode", errorCode.name)
            }
            if (errorMessage != null) {
                put("errorMessage", errorMessage)
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
        if (tempFile.exists()) {
            if (file.exists()) {
                file.delete()
            }
            tempFile.renameTo(file)
        }
    }

    /**
     * Считывает снимок задания из `job.json`.
     */
    fun loadJobMetadata(downloadId: String): StoredJobData? {
        val jobDir = File(baseDir, downloadId)
        val file = File(jobDir, JOB_METADATA_FILE)
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

            StoredJobData(
                request = req,
                state = state,
                errorCode = errorCode,
                errorMessage = errorMessage,
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
    val videoProgress: VoxTrackProgress,
    val originalAudioProgress: VoxTrackProgress,
    val translatedAudioProgress: VoxTrackProgress
)
