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
        val errorType = classifyError(throwable)
        val details = mapOf(
            "attempt" to attempt.toString(),
            "imageType" to imageType,
            "errorType" to errorType.name
        )

        VoxSafeLogger.w(
            VoxLogCategory.NETWORK,
            errorType.code,
            "Сбой загрузки $imageType (попытка $attempt/$MAX_RETRIES): ${errorType.name}",
            details
        )
    }
}
