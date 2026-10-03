package com.liskovsoft.smartyoutubetv2.common.vox.download

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Структурированные категории ошибок процесса скачивания.
 */
enum class VoxDownloadFailureCategory(val userMessageRu: String, val isRecoverable: Boolean) {
    NETWORK("Ошибка сети. Попробуйте позже", true),
    HTTP_FORBIDDEN("Доступ ограничен сервером", false),
    URL_EXPIRED("Ссылка на видео устарела. Повторяем загрузку…", true),
    STORAGE_FULL("Недостаточно свободного места", false),
    STREAM_UNAVAILABLE("Видео недоступно для скачивания", false),
    AUDIO_UNAVAILABLE("Не удалось получить аудиодорожку", false),
    FORMAT_UNSUPPORTED("Этот формат пока не поддерживается", false),
    TRANSLATION_UNAVAILABLE("Перевод для этого видео недоступен", false),
    VIDEO_UNAVAILABLE("Это видео пока нельзя скачать", false),
    LIVE_NOT_SUPPORTED("Прямые трансляции нельзя скачать", false),
    CANCELED("Загрузка отменена", false),
    INTEGRITY_FAILED("Не удалось проверить целостность файла", true),
    UNKNOWN("Не удалось скачать видео", false);
}

/**
 * Классификатор ошибок скачивания VOX для сопоставления технических исключений
 * с понятными пользователю категориями и текстовыми сообщениями.
 */
object VoxDownloadFailureClassifier {

    @JvmStatic
    fun classify(throwable: Throwable?, httpStatusCode: Int? = null): VoxDownloadFailureCategory {
        if (httpStatusCode != null) {
            when (httpStatusCode) {
                403 -> return VoxDownloadFailureCategory.HTTP_FORBIDDEN
                410 -> return VoxDownloadFailureCategory.URL_EXPIRED
                416 -> return VoxDownloadFailureCategory.INTEGRITY_FAILED
                429 -> return VoxDownloadFailureCategory.NETWORK
                in 500..599 -> return VoxDownloadFailureCategory.NETWORK
            }
        }

        if (throwable == null) {
            return VoxDownloadFailureCategory.UNKNOWN
        }

        if (throwable is VoxDownloadException) {
            return when (throwable.code) {
                VoxDownloadErrorCode.CANCELLED -> VoxDownloadFailureCategory.CANCELED
                VoxDownloadErrorCode.INSUFFICIENT_STORAGE,
                VoxDownloadErrorCode.STORAGE_ERROR -> VoxDownloadFailureCategory.STORAGE_FULL
                VoxDownloadErrorCode.URL_EXPIRED -> VoxDownloadFailureCategory.URL_EXPIRED
                VoxDownloadErrorCode.AUTH_REQUIRED -> VoxDownloadFailureCategory.HTTP_FORBIDDEN
                VoxDownloadErrorCode.STREAM_UNAVAILABLE -> VoxDownloadFailureCategory.STREAM_UNAVAILABLE
                VoxDownloadErrorCode.TRANSLATION_UNAVAILABLE -> VoxDownloadFailureCategory.TRANSLATION_UNAVAILABLE
                VoxDownloadErrorCode.UNSUPPORTED_CODEC -> VoxDownloadFailureCategory.FORMAT_UNSUPPORTED
                VoxDownloadErrorCode.MEDIA_PARSE_ERROR -> VoxDownloadFailureCategory.INTEGRITY_FAILED
                VoxDownloadErrorCode.NETWORK_ERROR -> classifyThrowable(throwable.cause ?: throwable)
                else -> VoxDownloadFailureCategory.UNKNOWN
            }
        }

        return classifyThrowable(throwable)
    }

    private fun classifyThrowable(throwable: Throwable): VoxDownloadFailureCategory {
        return when (throwable) {
            is SocketTimeoutException -> VoxDownloadFailureCategory.NETWORK
            is UnknownHostException -> VoxDownloadFailureCategory.NETWORK
            is ConnectException, is SocketException -> VoxDownloadFailureCategory.NETWORK
            is InterruptedIOException, is InterruptedException -> VoxDownloadFailureCategory.CANCELED
            else -> {
                val msg = throwable.message?.lowercase() ?: ""
                when {
                    msg.contains("timeout") || msg.contains("timed out") -> VoxDownloadFailureCategory.NETWORK
                    msg.contains("unable to resolve host") || msg.contains("no address") -> VoxDownloadFailureCategory.NETWORK
                    msg.contains("403") || msg.contains("forbidden") -> VoxDownloadFailureCategory.HTTP_FORBIDDEN
                    msg.contains("410") || msg.contains("gone") || msg.contains("expired") -> VoxDownloadFailureCategory.URL_EXPIRED
                    msg.contains("space") || msg.contains("storage") || msg.contains("enospc") -> VoxDownloadFailureCategory.STORAGE_FULL
                    msg.contains("cancel") -> VoxDownloadFailureCategory.CANCELED
                    msg.contains("audio") && msg.contains("missing") -> VoxDownloadFailureCategory.AUDIO_UNAVAILABLE
                    msg.contains("live") -> VoxDownloadFailureCategory.LIVE_NOT_SUPPORTED
                    else -> VoxDownloadFailureCategory.UNKNOWN
                }
            }
        }
    }

    @JvmStatic
    fun getUserMessage(category: VoxDownloadFailureCategory): String = category.userMessageRu

    @JvmStatic
    fun getUserMessage(code: VoxDownloadErrorCode?): String {
        return when (code) {
            VoxDownloadErrorCode.AUTH_REQUIRED -> "Для скачивания требуется вход"
            VoxDownloadErrorCode.INSUFFICIENT_STORAGE, VoxDownloadErrorCode.STORAGE_ERROR -> "Недостаточно свободного места"
            VoxDownloadErrorCode.URL_EXPIRED -> "Ссылка на видео устарела"
            VoxDownloadErrorCode.NETWORK_ERROR -> "Ошибка сети. Попробуйте позже"
            VoxDownloadErrorCode.STREAM_UNAVAILABLE -> "Видео недоступно для скачивания"
            VoxDownloadErrorCode.TRANSLATION_UNAVAILABLE -> "Перевод недоступен"
            VoxDownloadErrorCode.UNSUPPORTED_CODEC -> "Этот формат пока не поддерживается"
            VoxDownloadErrorCode.MEDIA_PARSE_ERROR -> "Не удалось обработать видеофайл"
            VoxDownloadErrorCode.CANCELLED -> "Загрузка отменена"
            else -> "Не удалось скачать видео"
        }
    }
}
