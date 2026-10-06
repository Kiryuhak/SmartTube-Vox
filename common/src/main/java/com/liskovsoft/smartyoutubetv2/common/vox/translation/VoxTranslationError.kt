package com.liskovsoft.smartyoutubetv2.common.vox.translation

import kotlin.math.min
import kotlin.math.pow

/**
 * Типизированная категория ошибки перевода Translation 2.0.
 */
enum class VoxTranslationErrorType(val id: String, val titleRu: String, val defaultRetryable: Boolean) {
    NETWORK("network", "Сетевая ошибка", true),
    AUTH("auth", "Ошибка авторизации Яндекс ID", false),
    BACKEND_UNAVAILABLE("backend_unavailable", "Сервер перевода временно недоступен", true),
    RATE_LIMITED("rate_limited", "Превышен лимит запросов", true),
    UNSUPPORTED("unsupported", "Формат или тип трансляции не поддерживается", false),
    TIMEOUT("timeout", "Превышено время ожидания ответа", true),
    INVALID_RESPONSE("invalid_response", "Некорректный ответ сервера перевода", false),
    SEGMENT_FAILED("segment_failed", "Ошибка обработки аудио-сегмента", true),
    BUFFER_UNDERRUN("buffer_underrun", "Критическое истощение буфера перевода", true),
    CANCELLED("cancelled", "Операция отменена", false),
    UNKNOWN("unknown", "Неизвестная ошибка", false);
}

/**
 * Модель ошибки подсистемы перевода без раскрытия чувствительных данных.
 */
data class VoxTranslationError(
    val type: VoxTranslationErrorType,
    val messageRu: String,
    val isRetryable: Boolean = type.defaultRetryable,
    val technicalDetails: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun fromHttpStatus(statusCode: Int, message: String? = null): VoxTranslationError {
            val type = when (statusCode) {
                401, 403 -> VoxTranslationErrorType.AUTH
                429 -> VoxTranslationErrorType.RATE_LIMITED
                in 500..599 -> VoxTranslationErrorType.BACKEND_UNAVAILABLE
                400, 404, 415, 422 -> VoxTranslationErrorType.UNSUPPORTED
                else -> VoxTranslationErrorType.UNKNOWN
            }
            return VoxTranslationError(
                type = type,
                messageRu = type.titleRu,
                technicalDetails = "HTTP $statusCode${if (message != null) ": $message" else ""}"
            )
        }
    }
}

/**
 * Политика повторов запросов перевода (Bounded Retry Policy with exponential backoff & jitter).
 */
class VoxTranslationRetryPolicy(
    val maxRetries: Int = 3,
    val baseBackoffMs: Long = 1000L,
    val maxBackoffMs: Long = 15000L
) {
    fun shouldRetry(error: VoxTranslationError, attempt: Int): Boolean {
        if (!error.isRetryable) {
            return false
        }
        if (error.type == VoxTranslationErrorType.AUTH ||
            error.type == VoxTranslationErrorType.UNSUPPORTED ||
            error.type == VoxTranslationErrorType.CANCELLED) {
            return false
        }
        return attempt < maxRetries
    }

    fun calculateBackoffMs(attempt: Int, jitterMs: Long = 0L): Long {
        val expMultiplier = 2.0.pow(attempt.toDouble().coerceAtLeast(0.0)).toLong()
        val calculated = baseBackoffMs * expMultiplier + jitterMs.coerceAtLeast(0L)
        return min(calculated, maxBackoffMs)
    }
}
