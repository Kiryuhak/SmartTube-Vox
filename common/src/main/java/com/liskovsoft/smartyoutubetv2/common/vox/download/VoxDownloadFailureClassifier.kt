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
    RESOLVE_FAILED("Не удалось получить ссылку на поток", true),
    VIDEO_DOWNLOAD_FAILED("Ошибка загрузки видео", true),
    AUDIO_DOWNLOAD_FAILED("Ошибка загрузки аудио", true),
    TRANSLATION_FAILED("Ошибка загрузки перевода", true),
    PACKAGING_FAILED("Не удалось собрать итоговый файл", false),
    MUX_FAILED("Ошибка сборки медиаконтейнера", false),
    STORAGE_FULL("Недостаточно свободного места", false),
    STORAGE_PERMISSION("Нет доступа к хранилищу", false),
    TEMP_FILE_FAILED("Ошибка создания временных файлов", false),
    FINALIZE_FAILED("Ошибка сохранения итогового файла", false),
    OUTPUT_MOVE_FAILED("Не удалось переместить готовый файл", false),
    CANCELLED("Загрузка отменена", false),
    CANCELED("Загрузка отменена", false),
    INVALID_MEDIA("Повреждённые медиаданные", false),
    UNSUPPORTED_FORMAT("Этот формат пока не поддерживается", false),
    FORMAT_UNSUPPORTED("Этот формат пока не поддерживается", false),
    TIMEOUT("Превышено время ожидания ответа", true),
    CHECKSUM_FAILED("Не удалось проверить целостность файла", true),
    INTEGRITY_FAILED("Не удалось проверить целостность файла", true),
    HTTP_FORBIDDEN("Доступ ограничен сервером", false),
    URL_EXPIRED("Ссылка на видео устарела. Повторяем загрузку…", true),
    STREAM_UNAVAILABLE("Видео недоступно для скачивания", false),
    AUDIO_UNAVAILABLE("Не удалось получить аудиодорожку", false),
    TRANSLATION_UNAVAILABLE("Перевод для этого видео недоступен", false),
    VIDEO_UNAVAILABLE("Это видео пока нельзя скачать", false),
    LIVE_NOT_SUPPORTED("Прямые трансляции нельзя скачать", false),
    UNKNOWN("Не удалось скачать видео", false);
}

/**
 * Классификатор ошибок скачивания VOX для сопоставления технических исключений
 * с понятными пользователю категориями, безопасными кодами диагностики и сообщениями.
 */
object VoxDownloadFailureClassifier {

    @JvmStatic
    fun extractRootCause(throwable: Throwable?): Throwable? {
        if (throwable == null) return null
        var current: Throwable = throwable
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (current.cause != null && current.cause !== current && !visited.contains(current.cause)) {
            visited.add(current)
            current = current.cause!!
        }
        return current
    }

    @JvmStatic
    fun classify(throwable: Throwable?, httpStatusCode: Int? = null): VoxDownloadFailureCategory {
        return classifyWithReason(throwable, httpStatusCode).first
    }

    @JvmStatic
    fun classifyWithReason(throwable: Throwable?, httpStatusCode: Int? = null): Pair<VoxDownloadFailureCategory, String> {
        if (httpStatusCode != null) {
            when (httpStatusCode) {
                403 -> return Pair(VoxDownloadFailureCategory.HTTP_FORBIDDEN, "HTTP_403_FORBIDDEN")
                410 -> return Pair(VoxDownloadFailureCategory.URL_EXPIRED, "HTTP_410_URL_EXPIRED")
                416 -> return Pair(VoxDownloadFailureCategory.CHECKSUM_FAILED, "HTTP_416_RANGE_NOT_SATISFIABLE")
                429 -> return Pair(VoxDownloadFailureCategory.NETWORK, "HTTP_429_RATE_LIMITED")
                in 500..599 -> return Pair(VoxDownloadFailureCategory.NETWORK, "HTTP_${httpStatusCode}_SERVER_ERROR")
            }
        }

        if (throwable == null) {
            return Pair(VoxDownloadFailureCategory.UNKNOWN, "UNKNOWN_NO_THROWABLE")
        }

        if (throwable is VoxDownloadException) {
            val root = extractRootCause(throwable)
            val subReason = if (root != null && root !== throwable) classifyThrowable(root).second else throwable.message ?: throwable.code.name
            val mappedCategory = when (throwable.code) {
                VoxDownloadErrorCode.CANCELLED -> VoxDownloadFailureCategory.CANCELLED
                VoxDownloadErrorCode.INSUFFICIENT_STORAGE,
                VoxDownloadErrorCode.STORAGE_FULL -> VoxDownloadFailureCategory.STORAGE_FULL
                VoxDownloadErrorCode.STORAGE_PERMISSION -> VoxDownloadFailureCategory.STORAGE_PERMISSION
                VoxDownloadErrorCode.STORAGE_ERROR -> {
                    val rootCat = root?.let { classifyThrowable(it).first }
                    when (rootCat) {
                        VoxDownloadFailureCategory.STORAGE_FULL,
                        VoxDownloadFailureCategory.STORAGE_PERMISSION,
                        VoxDownloadFailureCategory.FINALIZE_FAILED,
                        VoxDownloadFailureCategory.OUTPUT_MOVE_FAILED -> rootCat
                        else -> VoxDownloadFailureCategory.FINALIZE_FAILED
                    }
                }
                VoxDownloadErrorCode.TEMP_FILE_FAILED -> VoxDownloadFailureCategory.TEMP_FILE_FAILED
                VoxDownloadErrorCode.OUTPUT_MOVE_FAILED -> VoxDownloadFailureCategory.OUTPUT_MOVE_FAILED
                VoxDownloadErrorCode.FINALIZE_FAILED -> VoxDownloadFailureCategory.FINALIZE_FAILED
                VoxDownloadErrorCode.URL_EXPIRED -> VoxDownloadFailureCategory.URL_EXPIRED
                VoxDownloadErrorCode.AUTH_REQUIRED -> VoxDownloadFailureCategory.HTTP_FORBIDDEN
                VoxDownloadErrorCode.STREAM_UNAVAILABLE,
                VoxDownloadErrorCode.RESOLVE_FAILED -> VoxDownloadFailureCategory.RESOLVE_FAILED
                VoxDownloadErrorCode.VIDEO_DOWNLOAD_FAILED -> VoxDownloadFailureCategory.VIDEO_DOWNLOAD_FAILED
                VoxDownloadErrorCode.AUDIO_DOWNLOAD_FAILED -> VoxDownloadFailureCategory.AUDIO_DOWNLOAD_FAILED
                VoxDownloadErrorCode.TRANSLATION_UNAVAILABLE,
                VoxDownloadErrorCode.TRANSLATION_FAILED -> VoxDownloadFailureCategory.TRANSLATION_FAILED
                VoxDownloadErrorCode.UNSUPPORTED_CODEC,
                VoxDownloadErrorCode.UNSUPPORTED_FORMAT -> VoxDownloadFailureCategory.UNSUPPORTED_FORMAT
                VoxDownloadErrorCode.MEDIA_PARSE_ERROR,
                VoxDownloadErrorCode.INVALID_MEDIA -> VoxDownloadFailureCategory.INVALID_MEDIA
                VoxDownloadErrorCode.PACKAGING_FAILED -> VoxDownloadFailureCategory.PACKAGING_FAILED
                VoxDownloadErrorCode.MUX_FAILED -> VoxDownloadFailureCategory.MUX_FAILED
                VoxDownloadErrorCode.TIMEOUT -> VoxDownloadFailureCategory.TIMEOUT
                VoxDownloadErrorCode.CHECKSUM_FAILED -> VoxDownloadFailureCategory.CHECKSUM_FAILED
                VoxDownloadErrorCode.PROCESSING_STALLED -> VoxDownloadFailureCategory.PACKAGING_FAILED
                VoxDownloadErrorCode.NETWORK_ERROR,
                VoxDownloadErrorCode.NETWORK -> {
                    if (root != null && root !== throwable) classifyThrowable(root).first else VoxDownloadFailureCategory.NETWORK
                }
                else -> {
                    if (root != null && root !== throwable) classifyThrowable(root).first else VoxDownloadFailureCategory.UNKNOWN
                }
            }
            return Pair(mappedCategory, safeReasonString(subReason))
        }

        // Не-VoxDownloadException: исследуем цепочку причин (root cause)
        val root = extractRootCause(throwable) ?: throwable
        return classifyThrowable(root)
    }

    private fun classifyThrowable(throwable: Throwable): Pair<VoxDownloadFailureCategory, String> {
        return when (throwable) {
            is SocketTimeoutException -> Pair(VoxDownloadFailureCategory.TIMEOUT, "SOCKET_TIMEOUT")
            is UnknownHostException -> Pair(VoxDownloadFailureCategory.NETWORK, "DNS_LOOKUP_FAILED")
            is ConnectException -> Pair(VoxDownloadFailureCategory.NETWORK, "CONNECTION_REFUSED")
            is SocketException -> Pair(VoxDownloadFailureCategory.NETWORK, "SOCKET_EXCEPTION")
            is InterruptedIOException, is InterruptedException -> Pair(VoxDownloadFailureCategory.CANCELLED, "THREAD_INTERRUPTED")
            is SecurityException -> Pair(VoxDownloadFailureCategory.STORAGE_PERMISSION, "STORAGE_ACCESS_DENIED")
            else -> {
                val msg = throwable.message?.lowercase() ?: ""
                when {
                    msg.contains("enospc") || msg.contains("no space left") || msg.contains("disk full") ||
                        (msg.contains("storage") && msg.contains("full")) ->
                        Pair(VoxDownloadFailureCategory.STORAGE_FULL, "NO_SPACE_LEFT_ON_DEVICE")
                    msg.contains("eacces") || msg.contains("permission denied") || msg.contains("access denied") ->
                        Pair(VoxDownloadFailureCategory.STORAGE_PERMISSION, "STORAGE_PERMISSION_DENIED")
                    msg.contains("rename") || msg.contains("failed to rename") ->
                        Pair(VoxDownloadFailureCategory.OUTPUT_MOVE_FAILED, "ATOMIC_RENAME_FAILED")
                    msg.contains("mediastore") || msg.contains("openoutputstream") || msg.contains("publish") ->
                        Pair(VoxDownloadFailureCategory.FINALIZE_FAILED, "OUTPUT_STREAM_WRITE_FAILED")
                    msg.contains("mux") || msg.contains("ebml") || msg.contains("cluster") || msg.contains("cues") ->
                        Pair(VoxDownloadFailureCategory.MUX_FAILED, "MUX_STREAM_WRITE_FAILED")
                    msg.contains("timed out") || msg.contains("timeout") ->
                        Pair(VoxDownloadFailureCategory.TIMEOUT, "TIMEOUT")
                    msg.contains("unable to resolve host") || msg.contains("no address") ->
                        Pair(VoxDownloadFailureCategory.NETWORK, "DNS_RESOLUTION_FAILED")
                    msg.contains("403") || msg.contains("forbidden") ->
                        Pair(VoxDownloadFailureCategory.HTTP_FORBIDDEN, "HTTP_403_FORBIDDEN")
                    msg.contains("410") || msg.contains("gone") || msg.contains("expired") ->
                        Pair(VoxDownloadFailureCategory.URL_EXPIRED, "URL_EXPIRED")
                    msg.contains("cancel") ->
                        Pair(VoxDownloadFailureCategory.CANCELLED, "CANCELLED")
                    msg.contains("empty output track") || msg.contains("mediaextractor") || msg.contains("missing required streams") ->
                        Pair(VoxDownloadFailureCategory.INVALID_MEDIA, "INVALID_MEDIA_STREAM")
                    msg.contains("audio") && msg.contains("missing") ->
                        Pair(VoxDownloadFailureCategory.AUDIO_DOWNLOAD_FAILED, "AUDIO_STREAM_MISSING")
                    msg.contains("video") && msg.contains("missing") ->
                        Pair(VoxDownloadFailureCategory.VIDEO_DOWNLOAD_FAILED, "VIDEO_STREAM_MISSING")
                    msg.contains("live") ->
                        Pair(VoxDownloadFailureCategory.LIVE_NOT_SUPPORTED, "LIVE_STREAM_UNSUPPORTED")
                    msg.contains("unsupported") && (msg.contains("codec") || msg.contains("format")) ->
                        Pair(VoxDownloadFailureCategory.UNSUPPORTED_FORMAT, "CODEC_NOT_SUPPORTED")
                    msg.contains("checksum") || msg.contains("integrity") || msg.contains("hash") ->
                        Pair(VoxDownloadFailureCategory.CHECKSUM_FAILED, "INTEGRITY_VERIFICATION_FAILED")
                    throwable is IOException ->
                        Pair(VoxDownloadFailureCategory.PACKAGING_FAILED, "IO_FAILURE")
                    else ->
                        Pair(VoxDownloadFailureCategory.UNKNOWN, safeReasonString(throwable.javaClass.simpleName))
                }
            }
        }
    }

    private fun safeReasonString(raw: String): String {
        val sanitized = raw.replace(Regex("[^a-zA-Z0-9_]"), "_").uppercase()
        return if (sanitized.length > 50) sanitized.substring(0, 50) else sanitized
    }

    @JvmStatic
    fun getUserMessage(category: VoxDownloadFailureCategory): String = category.userMessageRu

    @JvmStatic
    fun getUserMessage(code: VoxDownloadErrorCode?): String {
        return when (code) {
            VoxDownloadErrorCode.AUTH_REQUIRED -> "Для скачивания требуется вход"
            VoxDownloadErrorCode.INSUFFICIENT_STORAGE,
            VoxDownloadErrorCode.STORAGE_FULL,
            VoxDownloadErrorCode.STORAGE_ERROR -> "Недостаточно свободного места"
            VoxDownloadErrorCode.STORAGE_PERMISSION -> "Ошибка доступа к хранилищу"
            VoxDownloadErrorCode.TEMP_FILE_FAILED -> "Не удалось создать временный файл"
            VoxDownloadErrorCode.OUTPUT_MOVE_FAILED -> "Не удалось переместить готовый файл"
            VoxDownloadErrorCode.FINALIZE_FAILED -> "Ошибка при сохранении видео"
            VoxDownloadErrorCode.URL_EXPIRED -> "Ссылка на видео устарела"
            VoxDownloadErrorCode.NETWORK_ERROR,
            VoxDownloadErrorCode.NETWORK -> "Ошибка сети. Попробуйте позже"
            VoxDownloadErrorCode.TIMEOUT -> "Превышено время ожидания ответа"
            VoxDownloadErrorCode.STREAM_UNAVAILABLE,
            VoxDownloadErrorCode.RESOLVE_FAILED -> "Видео недоступно для скачивания"
            VoxDownloadErrorCode.VIDEO_DOWNLOAD_FAILED -> "Ошибка загрузки видеопотока"
            VoxDownloadErrorCode.AUDIO_DOWNLOAD_FAILED -> "Ошибка загрузки аудиодорожки"
            VoxDownloadErrorCode.TRANSLATION_UNAVAILABLE,
            VoxDownloadErrorCode.TRANSLATION_FAILED -> "Перевод недоступен"
            VoxDownloadErrorCode.UNSUPPORTED_CODEC,
            VoxDownloadErrorCode.UNSUPPORTED_FORMAT -> "Этот формат пока не поддерживается"
            VoxDownloadErrorCode.MEDIA_PARSE_ERROR,
            VoxDownloadErrorCode.INVALID_MEDIA -> "Не удалось обработать видеофайл"
            VoxDownloadErrorCode.PROCESSING_STALLED -> "Обработка остановилась. Повторите упаковку файла."
            VoxDownloadErrorCode.PACKAGING_FAILED,
            VoxDownloadErrorCode.MUX_FAILED -> "Не удалось собрать итоговый файл"
            VoxDownloadErrorCode.CHECKSUM_FAILED -> "Не удалось проверить целостность файла"
            VoxDownloadErrorCode.CANCELLED -> "Загрузка отменена"
            else -> "Не удалось скачать видео"
        }
    }
}
