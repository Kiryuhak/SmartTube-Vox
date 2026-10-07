package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Интерфейс публикации готового MKV-файла в пользовательское хранилище (MediaStore).
 */
interface VoxDownloadPublisher {
    /**
     * Публикует локальный MKV файл в хранилище MediaStore.
     *
     * @param outputFile Исходный внутренний файл MKV
     * @param videoTitle Название видео для генерации имени файла
     * @param isCancelled Флаг отмены операции
     * @return Uri опубликованного файла в MediaStore (content://...)
     */
    fun publish(
        outputFile: File,
        videoTitle: String,
        isCancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: ((bytesCopied: Long, totalBytes: Long, percent: Int) -> Unit)? = null
    ): Uri

    fun publish(
        outputFile: File,
        videoTitle: String,
        isCancelled: AtomicBoolean
    ): Uri = publish(outputFile, videoTitle, isCancelled, null)

    /**
     * Проверяет, доступен ли и существует ли ранее опубликованный файл по Uri.
     */
    fun isPublishedFileAvailable(uriString: String?): Boolean

    /**
     * Удаляет опубликованный файл из MediaStore.
     */
    fun deletePublishedFile(uriString: String?): Boolean
}

/**
 * Реализация публикации через Android MediaStore (`Movies/SmartTube VOX/`).
 */
class VoxMediaStorePublisher(
    private val context: Context
) : VoxDownloadPublisher {

    companion object {
        const val RELATIVE_SUBDIRECTORY = "Movies/SmartTube VOX"
        const val MIME_TYPE_MKV = "video/x-matroska"
        private const val MAX_FILENAME_LENGTH = 120
        private const val BUFFER_SIZE = 64 * 1024

        /**
         * Очищает название видео от запрещённых символов файловой системы
         * и приводит к безопасному виду.
         */
        @JvmStatic
        fun sanitizeFilename(title: String?): String {
            if (title.isNullOrBlank()) {
                return "SmartTube_VOX"
            }

            // Заменяем запрещённые символы Windows / Unix / Android: < > : " / \ | ? * и управляющие символы
            var clean = title.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_")

            // Заменяем множественные пробелы и подчеркивания
            clean = clean.replace(Regex("\\s+"), " ").trim()

            // Убираем точки и пробелы на конце
            clean = clean.trimEnd('.', ' ')

            // Проверка на зарезервированные имена Windows (CON, PRN, AUX, NUL, COM1..9, LPT1..9)
            val reservedNames = setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")
            if (reservedNames.contains(clean.uppercase())) {
                clean = "${clean}_video"
            }

            if (clean.isBlank()) {
                clean = "SmartTube_VOX"
            }

            // Ограничение длины имени
            if (clean.length > MAX_FILENAME_LENGTH) {
                clean = clean.substring(0, MAX_FILENAME_LENGTH).trimEnd('.', ' ')
            }

            return clean
        }

        /**
         * Генерирует имя файла с учётом суффикса дубликата.
         * Пример: "Название — SmartTube VOX.mkv" или "Название — SmartTube VOX (2).mkv"
         */
        @JvmStatic
        fun buildFilename(title: String?, duplicateIndex: Int = 1): String {
            val cleanTitle = sanitizeFilename(title)
            return if (duplicateIndex <= 1) {
                "$cleanTitle — SmartTube VOX.mkv"
            } else {
                "$cleanTitle — SmartTube VOX ($duplicateIndex).mkv"
            }
        }
    }

    override fun publish(
        outputFile: File,
        videoTitle: String,
        isCancelled: AtomicBoolean,
        onProgress: ((bytesCopied: Long, totalBytes: Long, percent: Int) -> Unit)?
    ): Uri {
        if (!outputFile.exists() || outputFile.length() <= 0L) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.STORAGE_ERROR,
                "Cannot publish missing or empty MKV file: ${outputFile.absolutePath}"
            )
        }

        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return publishScopedStorage(resolver, outputFile, videoTitle, isCancelled, onProgress)
        } else {
            return publishLegacyStorage(outputFile, videoTitle, isCancelled, onProgress)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun publishScopedStorage(
        resolver: ContentResolver,
        outputFile: File,
        videoTitle: String,
        isCancelled: AtomicBoolean,
        onProgress: ((bytesCopied: Long, totalBytes: Long, percent: Int) -> Unit)?
    ): Uri {
        var duplicateIndex = 1
        var candidateName = buildFilename(videoTitle, duplicateIndex)

        // Поиск уникального имени в MediaStore
        while (isMediaStoreNameTaken(resolver, candidateName)) {
            duplicateIndex++
            candidateName = buildFilename(videoTitle, duplicateIndex)
            if (duplicateIndex > 100) break
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, candidateName)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE_MKV)
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_SUBDIRECTORY)
            put(MediaStore.Video.Media.IS_PENDING, 1)
            put(MediaStore.Video.Media.TITLE, sanitizeFilename(videoTitle))
        }

        val itemUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw VoxDownloadException(
                VoxDownloadErrorCode.STORAGE_ERROR,
                "Failed to create MediaStore entry for $candidateName"
            )

        val totalBytes = outputFile.length()
        var totalCopied = 0L

        try {
            resolver.openOutputStream(itemUri, "w")?.use { outStream ->
                FileInputStream(outputFile).use { inStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (inStream.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled.get()) {
                            throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Publish cancelled by user")
                        }
                        outStream.write(buffer, 0, bytesRead)
                        totalCopied += bytesRead
                        if (totalBytes > 0L) {
                            val pct = ((totalCopied * 100) / totalBytes).toInt().coerceIn(0, 100)
                            onProgress?.invoke(totalCopied, totalBytes, pct)
                        }
                    }
                    outStream.flush()
                }
            } ?: throw VoxDownloadException(
                VoxDownloadErrorCode.STORAGE_ERROR,
                "Failed to open output stream for MediaStore Uri: $itemUri"
            )

            // Снимаем флаг IS_PENDING, делая файл общедоступным
            val completeValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            val updatedRows = resolver.update(itemUri, completeValues, null, null)
            if (updatedRows <= 0) {
                throw VoxDownloadException(
                    VoxDownloadErrorCode.STORAGE_ERROR,
                    "Failed to clear IS_PENDING on MediaStore Uri: $itemUri"
                )
            }
            return itemUri
        } catch (e: Exception) {
            // Очищаем незавершённую запись MediaStore при ошибке
            try {
                resolver.delete(itemUri, null, null)
            } catch (ignored: Exception) {}
            if (e is VoxDownloadException) throw e
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("enospc") || msg.contains("no space left") || msg.contains("disk full")) {
                throw VoxDownloadException(VoxDownloadErrorCode.STORAGE_FULL, "No space left on device during MediaStore publishing", e)
            }
            if (msg.contains("eacces") || msg.contains("permission denied") || e is SecurityException) {
                throw VoxDownloadException(VoxDownloadErrorCode.STORAGE_PERMISSION, "Storage permission denied during MediaStore publishing", e)
            }
            throw VoxDownloadException(
                VoxDownloadErrorCode.FINALIZE_FAILED,
                "Error copying MKV to MediaStore: ${e.message}",
                e
            )
        }
    }

    private fun publishLegacyStorage(
        outputFile: File,
        videoTitle: String,
        isCancelled: AtomicBoolean,
        onProgress: ((bytesCopied: Long, totalBytes: Long, percent: Int) -> Unit)?
    ): Uri {
        // API 28: запись в общую Movies требует runtime-разрешения. При его
        // отсутствии оставляем файл в доступном приложению каталоге без запроса
        // разрешения из фонового worker.
        val hasPublicWrite = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val moviesDir = if (hasPublicWrite)
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        else context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val targetDir = File(moviesDir, "SmartTube VOX")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        var duplicateIndex = 1
        var candidateFile = File(targetDir, buildFilename(videoTitle, duplicateIndex))
        while (candidateFile.exists()) {
            duplicateIndex++
            candidateFile = File(targetDir, buildFilename(videoTitle, duplicateIndex))
            if (duplicateIndex > 100) break
        }

        val tmpCandidate = File(targetDir, "${candidateFile.name}.tmp")
        val totalBytes = outputFile.length()
        var totalCopied = 0L
        try {
            FileOutputStream(tmpCandidate).use { outStream ->
                FileInputStream(outputFile).use { inStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (inStream.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled.get()) {
                            throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Publish cancelled by user")
                        }
                        outStream.write(buffer, 0, bytesRead)
                        totalCopied += bytesRead
                        if (totalBytes > 0L) {
                            val pct = ((totalCopied * 100) / totalBytes).toInt().coerceIn(0, 100)
                            onProgress?.invoke(totalCopied, totalBytes, pct)
                        }
                    }
                    outStream.flush()
                }
            }
            if (!tmpCandidate.renameTo(candidateFile)) {
                // Пытаемся скопировать, если атомарное переименование не сработало
                try {
                    tmpCandidate.inputStream().use { input ->
                        candidateFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tmpCandidate.delete()
                } catch (copyErr: Exception) {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.OUTPUT_MOVE_FAILED,
                        "Failed to rename or copy temporary file to $candidateFile: ${copyErr.message}",
                        copyErr
                    )
                }
            }
            // Сканируем файл для появления в галерее/MediaStore
            if (hasPublicWrite) {
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(candidateFile.absolutePath),
                    arrayOf(MIME_TYPE_MKV),
                    null
                )
            }
            return Uri.fromFile(candidateFile)
        } catch (e: Exception) {
            if (tmpCandidate.exists()) tmpCandidate.delete()
            if (e is VoxDownloadException) throw e
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("enospc") || msg.contains("no space left") || msg.contains("disk full")) {
                throw VoxDownloadException(VoxDownloadErrorCode.STORAGE_FULL, "No space left on device during legacy storage publishing", e)
            }
            if (msg.contains("eacces") || msg.contains("permission denied") || e is SecurityException) {
                throw VoxDownloadException(VoxDownloadErrorCode.STORAGE_PERMISSION, "Storage permission denied during legacy storage publishing", e)
            }
            throw VoxDownloadException(
                VoxDownloadErrorCode.FINALIZE_FAILED,
                "Error copying MKV to legacy storage: ${e.message}",
                e
            )
        }
    }

    private fun isMediaStoreNameTaken(resolver: ContentResolver, displayName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(displayName, "$RELATIVE_SUBDIRECTORY%")
        return try {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                cursor.count > 0
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun isPublishedFileAvailable(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            if ("file".equals(uri.scheme, ignoreCase = true)) {
                val file = uri.path?.let { File(it) }
                file != null && file.exists() && file.length() > 0L
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.read() != -1
                } ?: false
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun deletePublishedFile(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            if ("file".equals(uri.scheme, ignoreCase = true)) {
                val file = uri.path?.let { File(it) }
                file != null && file.delete()
            } else {
                val deleted = context.contentResolver.delete(uri, null, null)
                deleted > 0
            }
        } catch (e: Exception) {
            false
        }
    }
}
