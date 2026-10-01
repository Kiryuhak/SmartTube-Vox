package com.liskovsoft.smartyoutubetv2.common.vox.download

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Потоковый загрузчик медиа-сегментов с поддержкой HTTP Range, докачки,
 * обработки кодов ошибок 200/206/403/410/416/429/5xx и контроля дискового пространства.
 */
class VoxSegmentDownloader(
    private val httpClient: OkHttpClient = createDefaultHttpClient(),
    private val storage: VoxDownloadStorage? = null
) {

    companion object {
        private const val BUFFER_SIZE = 64 * 1024 // 64 KiB bounded buffer
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L

        private fun createDefaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }

    /**
     * Загружает медиа-поток в целевой файл с поддержкой докачки.
     *
     * @param initialUrl URL медиа-файла
     * @param targetFile Целевой файл на диске
     * @param isCancelled Предикат отмены
     * @param onProgress Коллбэк прогресса (bytesDownloaded, totalBytes)
     * @param urlProvider Провайдер обновления URL при 403/410
     */
    @Throws(VoxDownloadException::class)
    fun download(
        initialUrl: String,
        targetFile: File,
        isCancelled: () -> Boolean = { false },
        onProgress: ((bytesDownloaded: Long, totalBytes: Long?) -> Unit)? = null,
        urlProvider: (() -> String)? = null
    ) {
        var currentUrl = initialUrl
        var attempt = 0
        val retryPolicy = VoxYouTubeRetryPolicy()

        while (true) {
            if (isCancelled()) {
                throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Download cancelled")
            }

            // Проверяем валидность URL
            VoxUrlSecurityValidator.validateUrl(currentUrl)

            val existingBytes = if (targetFile.exists() && targetFile.isFile) targetFile.length() else 0L

            try {
                downloadInternal(
                    url = currentUrl,
                    targetFile = targetFile,
                    existingOffset = existingBytes,
                    isCancelled = isCancelled,
                    onProgress = onProgress
                )
                // Успешно завершено
                return
            } catch (e: Exception) {
                if (isCancelled()) {
                    throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Download cancelled", e)
                }

                val category = retryPolicy.classifyError(e)
                val decision = retryPolicy.evaluate(
                    category = category,
                    currentAttempt = attempt,
                    hasUrlRefreshProvider = (urlProvider != null)
                )

                when (decision) {
                    is VoxRetryDecision.RefreshUrlAndRetry -> {
                        attempt++
                        currentUrl = urlProvider!!.invoke()
                        continue
                    }
                    is VoxRetryDecision.Retry -> {
                        attempt = decision.attempt
                        sleepWithCancel(decision.delayMs, isCancelled)
                        continue
                    }
                    is VoxRetryDecision.Fatal -> {
                        if (e is VoxDownloadException) {
                            throw e
                        } else {
                            throw VoxDownloadException(
                                VoxDownloadErrorCode.NETWORK_ERROR,
                                "Download failed ($category): ${e.message}",
                                e
                            )
                        }
                    }
                }
            }
        }
    }

    @Throws(VoxDownloadException::class)
    private fun downloadInternal(
        url: String,
        targetFile: File,
        existingOffset: Long,
        isCancelled: () -> Boolean,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long?) -> Unit)?
    ) {
        val requestBuilder = Request.Builder().url(url)

        if (existingOffset > 0) {
            requestBuilder.header("Range", "bytes=$existingOffset-")
        }

        val request = requestBuilder.build()
        val call = httpClient.newCall(request)

        var response: Response? = null
        try {
            response = call.execute()
            val code = response.code()

            when (code) {
                200 -> {
                    // Сервер отдал полный контент
                    val contentLength = response.body()?.contentLength()
                    val totalBytes = if (contentLength != null && contentLength > 0) contentLength else null

                    // Проверка свободного места
                    checkStorageSpace(totalBytes ?: 0L)

                    // Если мы запрашивали Range, но сервер вернул 200 OK — перезаписываем файл с начала
                    val append = false
                    if (existingOffset > 0 && targetFile.exists()) {
                        targetFile.delete()
                    }

                    streamToFile(
                        response = response,
                        targetFile = targetFile,
                        startOffset = 0L,
                        append = append,
                        totalExpectedBytes = totalBytes,
                        isCancelled = isCancelled,
                        onProgress = onProgress
                    )
                }
                206 -> {
                    // Сервер отдал частичный контент
                    val contentRange = response.header("Content-Range")
                    val totalBytes = parseTotalFromContentRange(contentRange)
                    val bodyLength = response.body()?.contentLength()

                    val remainingBytes = if (bodyLength != null && bodyLength > 0) {
                        bodyLength
                    } else if (totalBytes != null && totalBytes > existingOffset) {
                        totalBytes - existingOffset
                    } else {
                        0L
                    }

                    checkStorageSpace(remainingBytes)

                    streamToFile(
                        response = response,
                        targetFile = targetFile,
                        startOffset = existingOffset,
                        append = true,
                        totalExpectedBytes = totalBytes,
                        isCancelled = isCancelled,
                        onProgress = onProgress
                    )
                }
                403, 410 -> {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.URL_EXPIRED,
                        "HTTP $code received: signed URL expired or access forbidden"
                    )
                }
                416 -> {
                    // Range Not Satisfiable: проверяем Content-Range для определения общего размера
                    val contentRange = response.header("Content-Range")
                    val total = parseTotalFromContentRange(contentRange)
                    if (total != null && existingOffset >= total) {
                        // Файл уже скачан полностью
                        onProgress?.invoke(existingOffset, total)
                        return
                    }
                    // Иначе сбрасываем и качаем заново
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.NETWORK_ERROR,
                        "HTTP 416 Range Not Satisfiable, restarting stream"
                    )
                }
                429 -> {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.NETWORK_ERROR,
                        "HTTP 429 Too Many Requests: rate limited"
                    )
                }
                in 500..599 -> {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.NETWORK_ERROR,
                        "HTTP $code Server Error"
                    )
                }
                else -> {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.NETWORK_ERROR,
                        "Unexpected HTTP status $code"
                    )
                }
            }
        } catch (e: VoxDownloadException) {
            throw e
        } catch (e: Exception) {
            if (isCancelled()) {
                throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Download cancelled", e)
            }
            throw VoxDownloadException(
                VoxDownloadErrorCode.NETWORK_ERROR,
                "Connection failed: ${e.message}",
                e
            )
        } finally {
            try {
                response?.close()
            } catch (ignored: Exception) {}
        }
    }

    @Throws(VoxDownloadException::class)
    private fun streamToFile(
        response: Response,
        targetFile: File,
        startOffset: Long,
        append: Boolean,
        totalExpectedBytes: Long?,
        isCancelled: () -> Boolean,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long?) -> Unit)?
    ) {
        val body = response.body()
            ?: throw VoxDownloadException(VoxDownloadErrorCode.NETWORK_ERROR, "Empty response body")

        var bytesWritten = startOffset
        val buffer = ByteArray(BUFFER_SIZE)

        // Родительская директория
        targetFile.parentFile?.mkdirs()

        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var randomAccessFile: RandomAccessFile? = null

        try {
            inputStream = body.byteStream()

            if (append && startOffset > 0) {
                randomAccessFile = RandomAccessFile(targetFile, "rw")
                randomAccessFile.seek(startOffset)
            } else {
                outputStream = FileOutputStream(targetFile, false)
            }

            onProgress?.invoke(bytesWritten, totalExpectedBytes)

            var read: Int
            var spaceCheckCounter = 0

            while (inputStream.read(buffer).also { read = it } != -1) {
                if (isCancelled()) {
                    throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Download cancelled during read")
                }

                if (randomAccessFile != null) {
                    randomAccessFile.write(buffer, 0, read)
                } else if (outputStream != null) {
                    outputStream.write(buffer, 0, read)
                }

                bytesWritten += read
                onProgress?.invoke(bytesWritten, totalExpectedBytes)

                // Периодическая проверка дискового пространства каждые ~1 МБ
                spaceCheckCounter += read
                if (spaceCheckCounter >= 1024 * 1024) {
                    spaceCheckCounter = 0
                    checkStorageSpace(10 * 1024 * 1024L)
                }
            }

            // Валидация полноты, если totalExpectedBytes был известен
            if (totalExpectedBytes != null && totalExpectedBytes > 0 && bytesWritten < totalExpectedBytes) {
                throw VoxDownloadException(
                    VoxDownloadErrorCode.NETWORK_ERROR,
                    "Premature stream EOF: read $bytesWritten of expected $totalExpectedBytes bytes"
                )
            }
        } finally {
            try { inputStream?.close() } catch (ignored: Exception) {}
            try { outputStream?.flush(); outputStream?.close() } catch (ignored: Exception) {}
            try { randomAccessFile?.close() } catch (ignored: Exception) {}
        }
    }

    private fun parseTotalFromContentRange(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val slashIdx = contentRange.lastIndexOf('/')
        if (slashIdx >= 0 && slashIdx < contentRange.length - 1) {
            val totalStr = contentRange.substring(slashIdx + 1).trim()
            return totalStr.toLongOrNull()
        }
        return null
    }

    private fun checkStorageSpace(requiredBytes: Long) {
        if (storage != null && !storage.hasEnoughSpace(requiredBytes)) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.INSUFFICIENT_STORAGE,
                "Insufficient disk space for download"
            )
        }
    }

    private fun sleepWithCancel(millis: Long, isCancelled: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < millis) {
            if (isCancelled()) {
                throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Cancelled during retry delay")
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Interrupted during retry delay")
            }
        }
    }
}
