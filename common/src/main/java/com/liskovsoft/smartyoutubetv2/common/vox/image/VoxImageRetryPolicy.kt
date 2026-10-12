package com.liskovsoft.smartyoutubetv2.common.vox.image

import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Ограниченная политика повторов загрузки изображений и превью (Bounded Image Retry Policy).
 * Предотвращает циклические сетевые запросы, классифицирует сбои (DNS/Таймаут/Сеть)
 * и безопасно логирует инциденты без утечки чувствительных токенов и URL.
 */
object VoxImageRetryPolicy {

    const val MAX_RETRIES = 2
    const val BASE_BACKOFF_MS = 250L

    enum class ImageErrorType(val code: String) {
        DNS_ERROR(VoxLogCode.IMAGE_DNS_ERROR),
        TIMEOUT(VoxLogCode.IMAGE_TIMEOUT),
        REQUEST_FAILED(VoxLogCode.IMAGE_REQUEST_FAILED)
    }

    private val retryAttempts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    @JvmStatic
    fun canRetry(currentAttempt: Int): Boolean {
        return currentAttempt < MAX_RETRIES
    }

    @JvmStatic
    fun getBackoffDelayMs(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val multiplier = 1L shl (attempt - 1).coerceAtMost(4)
        return BASE_BACKOFF_MS * multiplier
    }

    @JvmStatic
    fun isCancellationOrRecycled(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        var current: Throwable? = throwable
        while (current != null) {
            val className = current.javaClass.simpleName.lowercase()
            val msg = current.message?.lowercase() ?: ""
            if (className.contains("cancel") || className.contains("recycle") || className.contains("interrupted")) {
                return true
            }
            if (msg.contains("request was cancelled") || msg.contains("target was cleared") || msg.contains("recycled")) {
                return true
            }
            current = current.cause
        }
        return false
    }

    @JvmStatic
    fun classifyError(throwable: Throwable?): ImageErrorType {
        if (throwable == null) return ImageErrorType.REQUEST_FAILED

        var current: Throwable? = throwable
        while (current != null) {
            when (current) {
                is UnknownHostException -> return ImageErrorType.DNS_ERROR
                is SocketTimeoutException -> return ImageErrorType.TIMEOUT
                is ConnectException -> return ImageErrorType.REQUEST_FAILED
            }
            val msg = current.message?.lowercase() ?: ""
            if (msg.contains("resolve") || msg.contains("dns") || msg.contains("unknownhost")) {
                return ImageErrorType.DNS_ERROR
            }
            if (msg.contains("timeout") || msg.contains("timed out")) {
                return ImageErrorType.TIMEOUT
            }
            current = current.cause
        }

        return ImageErrorType.REQUEST_FAILED
    }

    @JvmStatic
    fun logFailure(throwable: Throwable?, attempt: Int, imageType: String = "thumbnail") {
        if (isCancellationOrRecycled(throwable)) {
            // Отмена запроса или переиспользование View в карусели не является сетевой ошибкой
            return
        }

        val errorType = classifyError(throwable)
        val details = mapOf(
            "attempt" to attempt.toString(),
            "imageType" to imageType,
            "errorType" to errorType.name
        )

        if (attempt < MAX_RETRIES) {
            retryAttempts.computeIfAbsent(imageType) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
            VoxSafeLogger.info(
                VoxLogCategory.UI,
                VoxLogCode.IMAGE_REQUEST_RETRY,
                "Повтор загрузки $imageType (попытка $attempt/$MAX_RETRIES): ${errorType.name}",
                details
            )
        } else {
            VoxSafeLogger.warn(
                VoxLogCategory.UI,
                VoxLogCode.IMAGE_REQUEST_FAILED_FINAL,
                "Окончательный сбой загрузки $imageType (попытка $attempt/$MAX_RETRIES): ${errorType.name}",
                details
            )
        }
    }

    @JvmStatic
    fun logSuccess(imageType: String = "thumbnail") {
        val attempts = retryAttempts.remove(imageType)
        if (attempts != null && attempts.get() > 0) {
            VoxSafeLogger.info(
                VoxLogCategory.UI,
                VoxLogCode.IMAGE_REQUEST_RECOVERED,
                "Загрузка $imageType успешно восстановлена после повторной попытки",
                mapOf("imageType" to imageType)
            )
        }
    }
}
