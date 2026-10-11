package com.liskovsoft.smartyoutubetv2.common.vox.download

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Потоковый загрузчик медиа-сегментов с поддержкой HTTP Range, докачки,
 * обработки кодов ошибок 200/206/403/410/416/429/5xx, защиты от зависаний (stall detection)
 * и оптимизированным троттлингом прогресса и буферизованным вводом-выводом.
 */
class VoxSegmentDownloader(
    private val httpClient: OkHttpClient = createDefaultHttpClient(),
    private val storage: VoxDownloadStorage? = null
) {

    companion object {
        const val BUFFER_SIZE = 256 * 1024 // 256 KiB memory read buffer
        const val IO_BUFFER_SIZE = 256 * 1024 // 256 KiB disk write buffer
        const val PROGRESS_NOTIFY_MIN_INTERVAL_MS = 250L // Throttle UI notifications to max 4/sec
        const val PROGRESS_NOTIFY_MIN_BYTES = 256 * 1024L // Or at least 256 KiB written
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val SPACE_CHECK_INTERVAL_BYTES = 32 * 1024 * 1024L // 32 MiB space check interval

        private fun createDefaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS) // 25s timeout for fast stall detection without jitter drops
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private data class StreamResult(
        val bytesStreamed: Long,
        val peakMbps: Double
    )

    private class SegmentDownloadEventListener : okhttp3.EventListener() {
        var dnsStartMs = 0L
        var dnsElapsedMs = 0L
        var connectStartMs = 0L
        var connectElapsedMs = 0L
        var tlsStartMs = 0L
        var tlsElapsedMs = 0L
        var reqHeadersEndMs = 0L
        var ttfbMs = 0L

        override fun dnsStart(call: okhttp3.Call, domainName: String) {
            dnsStartMs = System.currentTimeMillis()
        }

        override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
            if (dnsStartMs > 0) {
                dnsElapsedMs = Math.max(1L, System.currentTimeMillis() - dnsStartMs)
            }
        }

        override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            connectStartMs = System.currentTimeMillis()
        }

        override fun connectEnd(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) {
            if (connectStartMs > 0) {
                connectElapsedMs = Math.max(1L, System.currentTimeMillis() - connectStartMs)
            }
        }

        override fun secureConnectStart(call: okhttp3.Call) {
            tlsStartMs = System.currentTimeMillis()
        }

        override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) {
            if (tlsStartMs > 0) {
                tlsElapsedMs = Math.max(1L, System.currentTimeMillis() - tlsStartMs)
            }
        }

        override fun requestHeadersEnd(call: okhttp3.Call, request: okhttp3.Request) {
            reqHeadersEndMs = System.currentTimeMillis()
        }

        override fun responseHeadersStart(call: okhttp3.Call) {
            if (reqHeadersEndMs > 0) {
                ttfbMs = Math.max(1L, System.currentTimeMillis() - reqHeadersEndMs)
            }
        }
    }

    /**
     * Загружает медиа-поток в целевой файл с поддержкой докачки и защиты от зависаний.
     *
     * @param initialUrl URL медиа-файла
     * @param targetFile Целевой файл на диске
     * @param isCancelled Предикат отмены
     * @param onProgress Коллбэк прогресса (bytesDownloaded, totalBytes)
     * @param urlProvider Провайдер обновления URL при 403/410
     * @param onRangeResumed Коллбэк возобновления докачки с сохраненной позиции
     * @param onStallDetected Коллбэк обнаружения сетевого зависания (stall)
     * @param onForensicsCollected Коллбэк передачи криминалистических сетевых метрик
     * @param resourceType Тип медиа-ресурса (VIDEO, AUDIO, TRANSLATED_AUDIO)
     */
    @Throws(VoxDownloadException::class)
    fun download(
        initialUrl: String,
        targetFile: File,
        isCancelled: () -> Boolean = { false },
        onProgress: ((bytesDownloaded: Long, totalBytes: Long?) -> Unit)? = null,
        urlProvider: (() -> String)? = null,
        onRangeResumed: ((resumedFromOffset: Long) -> Unit)? = null,
        onStallDetected: (() -> Unit)? = null,
        onForensicsCollected: ((VoxDownloadNetworkForensics) -> Unit)? = null,
        resourceType: String = "MEDIA"
    ) {
        var currentUrl = initialUrl
        var attempt = 0
        var totalStallCount = 0
        val retryPolicy = VoxYouTubeRetryPolicy()

        while (true) {
            if (isCancelled()) {
                throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Download cancelled")
            }

            // Проверяем валидность URL
            VoxUrlSecurityValidator.validateUrl(currentUrl)

            val existingBytes = if (targetFile.exists() && targetFile.isFile) targetFile.length() else 0L
            if (existingBytes > 0L) {
                onRangeResumed?.invoke(existingBytes)
            }

            try {
                downloadInternal(
                    url = currentUrl,
                    targetFile = targetFile,
                    existingOffset = existingBytes,
                    isCancelled = isCancelled,
                    onProgress = onProgress,
                    onForensicsCollected = onForensicsCollected,
                    resourceType = resourceType,
                    attempt = attempt,
                    stallCount = totalStallCount
                )
                // Успешно завершено
                return
            } catch (e: Exception) {
                if (isCancelled()) {
                    throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Download cancelled", e)
                }

                if (e is SocketTimeoutException || e.cause is SocketTimeoutException) {
                    totalStallCount++
                    onStallDetected?.invoke()
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
        onProgress: ((bytesDownloaded: Long, totalBytes: Long?) -> Unit)?,
        onForensicsCollected: ((VoxDownloadNetworkForensics) -> Unit)? = null,
        resourceType: String = "MEDIA",
        attempt: Int = 0,
        stallCount: Int = 0
    ) {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Connection", "keep-alive")
            .header("Accept-Encoding", "identity")

        if (existingOffset > 0) {
            requestBuilder.header("Range", "bytes=$existingOffset-")
        }

        val request = requestBuilder.build()
        val eventListener = SegmentDownloadEventListener()
        val clientWithListener = try {
            httpClient.newBuilder().eventListener(eventListener).build()
        } catch (ignored: Exception) {
            httpClient
        }
        val call = clientWithListener.newCall(request)

        var response: Response? = null
        val streamStartMs = System.currentTimeMillis()
        try {
            response = call.execute()
            val code = response.code()

            var streamResult: StreamResult? = null
            var totalExpectedBytes: Long? = null

            when (code) {
                200 -> {
                    // Сервер отдал полный контент
                    val contentLength = response.body()?.contentLength()
                    totalExpectedBytes = if (contentLength != null && contentLength > 0) contentLength else null

                    // Проверка свободного места
                    checkStorageSpace(totalExpectedBytes ?: 0L)

                    // Если мы запрашивали Range, но сервер вернул 200 OK — перезаписываем файл с начала
                    val append = false
                    if (existingOffset > 0 && targetFile.exists()) {
                        targetFile.delete()
                    }

                    streamResult = streamToFile(
                        response = response,
                        targetFile = targetFile,
                        startOffset = 0L,
                        append = append,
                        totalExpectedBytes = totalExpectedBytes,
                        isCancelled = isCancelled,
                        onProgress = onProgress
                    )
                }
                206 -> {
                    // Сервер отдал частичный контент
                    val contentRange = response.header("Content-Range")
                    totalExpectedBytes = parseTotalFromContentRange(contentRange)
                    val bodyLength = response.body()?.contentLength()

                    val remainingBytes = if (bodyLength != null && bodyLength > 0) {
                        bodyLength
                    } else if (totalExpectedBytes != null && totalExpectedBytes > existingOffset) {
                        totalExpectedBytes - existingOffset
                    } else {
                        0L
                    }

                    checkStorageSpace(remainingBytes)

                    streamResult = streamToFile(
                        response = response,
                        targetFile = targetFile,
                        startOffset = existingOffset,
                        append = true,
                        totalExpectedBytes = totalExpectedBytes,
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

            if (streamResult != null && onForensicsCollected != null) {
                val streamElapsedMs = Math.max(1L, System.currentTimeMillis() - streamStartMs)
                val avgMbps = (streamResult.bytesStreamed * 8.0) / (streamElapsedMs * 1000.0)
                val peakMbps = Math.max(avgMbps, streamResult.peakMbps)
                val safeHost = VoxHostSanitizer.sanitizeHost(url)
                val hostFamily = VoxHostFamily.fromHost(safeHost)
                val rangeSupported = (code == 206 || response.header("Accept-Ranges") == "bytes")
                val rangeUsed = (existingOffset > 0L)
                val parallelConnections = VoxBoundedParallelismPolicy.getParallelConnectionsCount(rangeSupported, totalExpectedBytes)
                val serverThrottling = (streamResult.bytesStreamed > 10_000_000L && avgMbps in 0.01..2.0 &&
                        eventListener.ttfbMs in 1..400L && eventListener.connectElapsedMs in 1..300L)

                val forensics = VoxDownloadNetworkForensics(
                    resourceType = resourceType,
                    networkEngine = "OKHTTP",
                    safeHost = safeHost,
                    hostFamily = hostFamily,
                    routeHint = "DIRECT",
                    dnsMs = eventListener.dnsElapsedMs,
                    connectMs = eventListener.connectElapsedMs,
                    tlsMs = eventListener.tlsElapsedMs,
                    ttfbMs = eventListener.ttfbMs,
                    bytesDownloaded = streamResult.bytesStreamed,
                    elapsedMs = streamElapsedMs,
                    avgMbps = avgMbps,
                    peakMbps = peakMbps,
                    stallCount = stallCount,
                    retryCount = attempt,
                    httpStatusClass = "${code / 100}XX_${if (code in 200..299) "SUCCESS" else "ERROR"}",
                    rangeSupported = rangeSupported,
                    rangeUsed = rangeUsed,
                    parallelConnections = parallelConnections,
                    contentLength = totalExpectedBytes,
                    serverThrottlingSuspicion = serverThrottling
                )
                onForensicsCollected.invoke(forensics)
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
    ): StreamResult {
        val body = response.body()
            ?: throw VoxDownloadException(VoxDownloadErrorCode.NETWORK_ERROR, "Empty response body")

        var bytesWritten = startOffset
        val buffer = ByteArray(BUFFER_SIZE)

        // Родительская директория
        targetFile.parentFile?.mkdirs()

        var inputStream: InputStream? = null
        var outputStream: BufferedOutputStream? = null

        var peakMbps = 0.0
        var windowStartBytes = bytesWritten
        var windowStartTimeMs = System.currentTimeMillis()

        try {
            inputStream = body.byteStream()

            if (append && startOffset > 0) {
                // Если файл уже больше startOffset, усекаем его до точки докачки
                if (targetFile.exists() && targetFile.length() > startOffset) {
                    val raf = RandomAccessFile(targetFile, "rw")
                    try {
                        raf.setLength(startOffset)
                    } finally {
                        raf.close()
                    }
                }
                outputStream = BufferedOutputStream(FileOutputStream(targetFile, true), IO_BUFFER_SIZE)
            } else {
                outputStream = BufferedOutputStream(FileOutputStream(targetFile, false), IO_BUFFER_SIZE)
            }

            var lastNotifiedBytes = bytesWritten
            var lastNotifiedTimeMs = System.currentTimeMillis()

            onProgress?.invoke(bytesWritten, totalExpectedBytes)

            var read: Int
            var spaceCheckCounter = 0

            while (inputStream.read(buffer).also { read = it } != -1) {
                if (isCancelled()) {
                    throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Download cancelled during read")
                }

                outputStream.write(buffer, 0, read)
                bytesWritten += read

                val now = System.currentTimeMillis()
                val windowElapsedMs = now - windowStartTimeMs
                val windowBytes = bytesWritten - windowStartBytes
                if (windowElapsedMs >= 250L && windowBytes > 0L) {
                    val instantaneousMbps = (windowBytes * 8.0) / (windowElapsedMs * 1000.0)
                    if (instantaneousMbps > peakMbps) {
                        peakMbps = instantaneousMbps
                    }
                    windowStartBytes = bytesWritten
                    windowStartTimeMs = now
                }

                if (bytesWritten - lastNotifiedBytes >= PROGRESS_NOTIFY_MIN_BYTES ||
                    now - lastNotifiedTimeMs >= PROGRESS_NOTIFY_MIN_INTERVAL_MS) {
                    onProgress?.invoke(bytesWritten, totalExpectedBytes)
                    lastNotifiedBytes = bytesWritten
                    lastNotifiedTimeMs = now
                }

                // Периодическая проверка дискового пространства каждые ~32 МБ (минимизация системных вызовов statfs)
                spaceCheckCounter += read
                if (spaceCheckCounter >= SPACE_CHECK_INTERVAL_BYTES) {
                    spaceCheckCounter = 0
                    checkStorageSpace(10 * 1024 * 1024L)
                }
            }

            outputStream.flush()

            // Гарантированное финальное обновление 100% трека
            onProgress?.invoke(bytesWritten, totalExpectedBytes)

            // Валидация полноты, если totalExpectedBytes был известен
            if (totalExpectedBytes != null && totalExpectedBytes > 0 && bytesWritten < totalExpectedBytes) {
                throw VoxDownloadException(
                    VoxDownloadErrorCode.NETWORK_ERROR,
                    "Premature stream EOF: read $bytesWritten of expected $totalExpectedBytes bytes"
                )
            }

            return StreamResult(
                bytesStreamed = bytesWritten - startOffset,
                peakMbps = peakMbps
            )
        } finally {
            try { inputStream?.close() } catch (ignored: Exception) {}
            try { outputStream?.flush(); outputStream?.close() } catch (ignored: Exception) {}
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
