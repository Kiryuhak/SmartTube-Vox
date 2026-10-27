package com.liskovsoft.smartyoutubetv2.common.vox.download

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Категории ошибок при взаимодействии с сетевыми медиа-потоками YouTube и сервисами VOX.
 */
enum class VoxYouTubeErrorCategory {
    NETWORK_TIMEOUT,
    CDN_CONNECT_TIMEOUT,
    CDN_READ_TIMEOUT,
    DNS_FAILURE,
    HTTP_403,
    HTTP_410,
    HTTP_429,
    HTTP_5XX,
    RANGE_MISMATCH,
    MANIFEST_EXPIRED,
    PARTIAL_FILE,
    NO_FORMAT_2160P,
    PLAYER_RESPONSE_ERROR,
    FORMAT_EMPTY,
    SIGNED_URL_EXPIRED,
    UNSUPPORTED_CODEC,
    VIDEO_UNAVAILABLE,
    LOGIN_REQUIRED,
    AGE_RESTRICTED,
    LIVE_UNSUPPORTED,
    CANCELLED,
    INSUFFICIENT_STORAGE,
    UNKNOWN
}

/**
 * Решение политики повторов при возникновении ошибки.
 */
sealed class VoxRetryDecision {
    data class Retry(val delayMs: Long, val attempt: Int) : VoxRetryDecision()
    object RefreshUrlAndRetry : VoxRetryDecision()
    data class Fatal(val category: VoxYouTubeErrorCategory, val reason: String) : VoxRetryDecision()
}

/**
 * Централизованная политика повторов и классификации сетевых ошибок YouTube/VOX.
 *
 * Правила:
 * - Таймауты и временные ошибки сети: ограниченное количество повторов с экспоненциальной задержкой (1s, 2s, 4s).
 * - Ошибки 403/410 (истечение подписанного URL): однократное обновление метаданных потока.
 * - 429 (Rate Limit): повтор с увеличенной задержкой.
 * - 5xx и DNS: ограниченные повторы.
 * - Фатальные ошибки (недостаток места, отмена, неподдерживаемый кодек, возрастные ограничения): мгновенное завершение без циклов.
 */
class VoxYouTubeRetryPolicy(
    private val maxRetries: Int = MAX_RETRIES_DEFAULT,
    private val initialBackoffMs: Long = INITIAL_BACKOFF_MS_DEFAULT,
    private val backoffMultiplier: Double = BACKOFF_MULTIPLIER_DEFAULT
) {

    companion object {
        const val MAX_RETRIES_DEFAULT = 3
        const val INITIAL_BACKOFF_MS_DEFAULT = 1000L
        const val BACKOFF_MULTIPLIER_DEFAULT = 2.0
    }

    /**
     * Классифицирует исключение или HTTP-код в категорию ошибок.
     */
    fun classifyError(throwable: Throwable?, httpStatusCode: Int? = null): VoxYouTubeErrorCategory {
        if (httpStatusCode != null) {
            when (httpStatusCode) {
                403 -> return VoxYouTubeErrorCategory.HTTP_403
                410 -> return VoxYouTubeErrorCategory.HTTP_410
                429 -> return VoxYouTubeErrorCategory.HTTP_429
                in 500..599 -> return VoxYouTubeErrorCategory.HTTP_5XX
            }
        }

        if (throwable == null) {
            return VoxYouTubeErrorCategory.UNKNOWN
        }

        if (throwable is VoxDownloadException) {
            return when (throwable.code) {
                VoxDownloadErrorCode.CANCELLED -> VoxYouTubeErrorCategory.CANCELLED
                VoxDownloadErrorCode.URL_EXPIRED,
                VoxDownloadErrorCode.MANIFEST_EXPIRED -> VoxYouTubeErrorCategory.MANIFEST_EXPIRED
                VoxDownloadErrorCode.INSUFFICIENT_STORAGE -> VoxYouTubeErrorCategory.INSUFFICIENT_STORAGE
                VoxDownloadErrorCode.UNSUPPORTED_CODEC -> VoxYouTubeErrorCategory.UNSUPPORTED_CODEC
                VoxDownloadErrorCode.STREAM_UNAVAILABLE -> VoxYouTubeErrorCategory.VIDEO_UNAVAILABLE
                VoxDownloadErrorCode.NO_FORMAT_2160P -> VoxYouTubeErrorCategory.NO_FORMAT_2160P
                VoxDownloadErrorCode.CDN_CONNECT_TIMEOUT -> VoxYouTubeErrorCategory.CDN_CONNECT_TIMEOUT
                VoxDownloadErrorCode.CDN_READ_TIMEOUT -> VoxYouTubeErrorCategory.CDN_READ_TIMEOUT
                VoxDownloadErrorCode.HTTP_403 -> VoxYouTubeErrorCategory.HTTP_403
                VoxDownloadErrorCode.HTTP_429 -> VoxYouTubeErrorCategory.HTTP_429
                VoxDownloadErrorCode.RANGE_MISMATCH -> VoxYouTubeErrorCategory.RANGE_MISMATCH
                VoxDownloadErrorCode.PARTIAL_FILE -> VoxYouTubeErrorCategory.PARTIAL_FILE
                VoxDownloadErrorCode.NETWORK_ERROR,
                VoxDownloadErrorCode.NETWORK -> classifyThrowable(throwable.cause ?: throwable)
                else -> VoxYouTubeErrorCategory.UNKNOWN
            }
        }

        return classifyThrowable(throwable)
    }

    private fun classifyThrowable(throwable: Throwable): VoxYouTubeErrorCategory {
        return when (throwable) {
            is SocketTimeoutException -> {
                val msg = throwable.message?.lowercase() ?: ""
                when {
                    msg.contains("connect") -> VoxYouTubeErrorCategory.CDN_CONNECT_TIMEOUT
                    msg.contains("read") -> VoxYouTubeErrorCategory.CDN_READ_TIMEOUT
                    else -> VoxYouTubeErrorCategory.NETWORK_TIMEOUT
                }
            }
            is UnknownHostException -> VoxYouTubeErrorCategory.DNS_FAILURE
            is ConnectException, is SocketException -> VoxYouTubeErrorCategory.CDN_CONNECT_TIMEOUT
            is InterruptedIOException, is InterruptedException -> VoxYouTubeErrorCategory.CANCELLED
            else -> {
                val msg = throwable.message?.lowercase() ?: ""
                when {
                    msg.contains("connect timed out") || msg.contains("connect timeout") -> VoxYouTubeErrorCategory.CDN_CONNECT_TIMEOUT
                    msg.contains("read timed out") || msg.contains("read timeout") -> VoxYouTubeErrorCategory.CDN_READ_TIMEOUT
                    msg.contains("timeout") || msg.contains("timed out") -> VoxYouTubeErrorCategory.NETWORK_TIMEOUT
                    msg.contains("unable to resolve host") || msg.contains("no address associated") -> VoxYouTubeErrorCategory.DNS_FAILURE
                    msg.contains("403") || msg.contains("forbidden") -> VoxYouTubeErrorCategory.HTTP_403
                    msg.contains("410") || msg.contains("gone") -> VoxYouTubeErrorCategory.HTTP_410
                    msg.contains("429") || msg.contains("too many requests") -> VoxYouTubeErrorCategory.HTTP_429
                    msg.contains("range") && (msg.contains("mismatch") || msg.contains("unsatisfiable")) -> VoxYouTubeErrorCategory.RANGE_MISMATCH
                    msg.contains("cancel") -> VoxYouTubeErrorCategory.CANCELLED
                    else -> VoxYouTubeErrorCategory.UNKNOWN
                }
            }
        }
    }

    /**
     * Определяет действие при ошибке на основе текущего числа попыток.
     */
    fun evaluate(
        category: VoxYouTubeErrorCategory,
        currentAttempt: Int,
        hasUrlRefreshProvider: Boolean = false
    ): VoxRetryDecision {
        when (category) {
            VoxYouTubeErrorCategory.CANCELLED,
            VoxYouTubeErrorCategory.INSUFFICIENT_STORAGE,
            VoxYouTubeErrorCategory.UNSUPPORTED_CODEC,
            VoxYouTubeErrorCategory.AGE_RESTRICTED,
            VoxYouTubeErrorCategory.LOGIN_REQUIRED,
            VoxYouTubeErrorCategory.NO_FORMAT_2160P,
            VoxYouTubeErrorCategory.LIVE_UNSUPPORTED -> {
                return VoxRetryDecision.Fatal(category, "Non-retryable terminal condition: $category")
            }

            VoxYouTubeErrorCategory.HTTP_403,
            VoxYouTubeErrorCategory.HTTP_410,
            VoxYouTubeErrorCategory.MANIFEST_EXPIRED,
            VoxYouTubeErrorCategory.SIGNED_URL_EXPIRED -> {
                return if (hasUrlRefreshProvider && currentAttempt < maxRetries) {
                    VoxRetryDecision.RefreshUrlAndRetry
                } else {
                    VoxRetryDecision.Fatal(category, "Signed URL expired and cannot be refreshed")
                }
            }

            VoxYouTubeErrorCategory.CDN_CONNECT_TIMEOUT,
            VoxYouTubeErrorCategory.CDN_READ_TIMEOUT,
            VoxYouTubeErrorCategory.NETWORK_TIMEOUT,
            VoxYouTubeErrorCategory.DNS_FAILURE,
            VoxYouTubeErrorCategory.RANGE_MISMATCH,
            VoxYouTubeErrorCategory.PARTIAL_FILE,
            VoxYouTubeErrorCategory.HTTP_429,
            VoxYouTubeErrorCategory.HTTP_5XX,
            VoxYouTubeErrorCategory.UNKNOWN -> {
                if (currentAttempt < maxRetries) {
                    val delay = calculateDelay(currentAttempt)
                    return VoxRetryDecision.Retry(delayMs = delay, attempt = currentAttempt + 1)
                } else {
                    return VoxRetryDecision.Fatal(category, "Max retry attempts reached ($maxRetries)")
                }
            }

            else -> {
                return if (currentAttempt < maxRetries) {
                    val delay = calculateDelay(currentAttempt)
                    VoxRetryDecision.Retry(delayMs = delay, attempt = currentAttempt + 1)
                } else {
                    VoxRetryDecision.Fatal(category, "Fatal error: $category")
                }
            }
        }
    }

    private fun calculateDelay(attempt: Int): Long {
        var delay = initialBackoffMs
        for (i in 0 until attempt) {
            delay = (delay * backoffMultiplier).toLong()
        }
        return delay.coerceAtMost(10_000L)
    }
}
