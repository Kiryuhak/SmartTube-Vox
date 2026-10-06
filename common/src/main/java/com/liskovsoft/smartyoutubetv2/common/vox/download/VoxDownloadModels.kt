package com.liskovsoft.smartyoutubetv2.common.vox.download

import java.util.UUID

/**
 * Режим перевода видео для скачивания.
 */
enum class VoxTranslationMode {
    NONE,
    STANDARD,
    LIVELY
}

/**
 * Состояние перевода скачанного видео.
 */
enum class VoxDownloadTranslationState {
    NONE,
    DOWNLOADED_TRANSLATED,
    UNKNOWN;

    companion object {
        @JvmStatic
        fun fromString(value: String?): VoxDownloadTranslationState {
            if (value == null) return UNKNOWN
            return values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

/**
 * Предпочтительное качество видео для скачивания.
 */
enum class VoxQualityPreference(val label: String, val maxResolution: Int) {
    QUALITY_1080P("1080p", 1080),
    QUALITY_720P("720p", 720),
    QUALITY_480P("480p", 480),
    QUALITY_360P("360p", 360),
    QUALITY_AUTO("Auto / Best", 2160);

    companion object {
        @JvmStatic
        fun fromLabel(label: String?): VoxQualityPreference {
            if (label == null) return QUALITY_AUTO
            return values().firstOrNull { it.label.equals(label, ignoreCase = true) } ?: QUALITY_AUTO
        }
    }
}

/**
 * Неизменяемая модель запроса на скачивание видео с переводом.
 *
 * Безопасность: никогда не сохраняет подписанные URL YouTube/Яндекса,
 * токены авторизации или секретные заголовки.
 */
data class VoxDownloadRequest(
    val downloadId: String = UUID.randomUUID().toString(),
    val videoId: String,
    val videoTitle: String,
    val qualityPreference: VoxQualityPreference = VoxQualityPreference.QUALITY_AUTO,
    val translationMode: VoxTranslationMode = VoxTranslationMode.STANDARD,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Тип загружаемого медиа-трека.
 */
enum class VoxDownloadTrack(val fileName: String) {
    VIDEO("video.part"),
    ORIGINAL_AUDIO("original_audio.part"),
    TRANSLATED_AUDIO("translated_audio.part");
}

/**
 * Состояние отдельного медиа-трека.
 */
enum class VoxTrackState {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Прогресс скачивания отдельного медиа-трека.
 */
data class VoxTrackProgress(
    val track: VoxDownloadTrack,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val state: VoxTrackState = VoxTrackState.PENDING
) {
    val percent: Int?
        get() = if (totalBytes != null && totalBytes > 0) {
            ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            null
        }
}

/**
 * Этапы жизненного цикла задания на скачивание.
 */
enum class VoxDownloadState {
    IDLE,
    PREPARING_TRANSLATION,
    RESOLVING_STREAMS,
    DOWNLOADING_VIDEO,
    DOWNLOADING_ORIGINAL_AUDIO,
    DOWNLOADING_TRANSLATED_AUDIO,
    READY_FOR_MUX,
    MUXING,
    FINALIZING,
    MUXED,
    PUBLISHING,
    COMPLETED,
    PAUSED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

/**
 * Полный снимок прогресса задания на скачивание для UI и координатора.
 */
data class VoxDownloadProgress(
    val downloadId: String,
    val state: VoxDownloadState = VoxDownloadState.IDLE,
    val video: VoxTrackProgress = VoxTrackProgress(VoxDownloadTrack.VIDEO),
    val originalAudio: VoxTrackProgress = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO),
    val translatedAudio: VoxTrackProgress = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO),
    val muxBytesProcessed: Long = 0L,
    val muxTotalBytes: Long = 0L,
    val muxPercent: Int = 0,
    val publishBytesProcessed: Long = 0L,
    val publishTotalBytes: Long = 0L,
    val publishPercent: Int = 0,
    val translationState: VoxDownloadTranslationState = VoxDownloadTranslationState.UNKNOWN,
    val publishedUri: String? = null,
    val publishedFilePath: String? = null,
    val requestedQuality: String? = null,
    val actualQuality: String? = null,
    val fallbackReason: String? = null,
    val errorMessage: String? = null,
    val errorCode: VoxDownloadErrorCode? = null
) {
    val totalBytesDownloaded: Long
        get() = video.bytesDownloaded + originalAudio.bytesDownloaded + translatedAudio.bytesDownloaded

    val totalBytesExpected: Long?
        get() {
            val v = video.totalBytes ?: return null
            val o = originalAudio.totalBytes ?: return null
            val t = if (translationState == VoxDownloadTranslationState.NONE) 0L
                else translatedAudio.totalBytes ?: return null
            return v + o + t
        }

    val overallPercent: Int?
        get() {
            if (state == VoxDownloadState.MUXING) {
                return if (muxTotalBytes > 0) muxPercent.coerceIn(0, 99) else null
            }
            if (state == VoxDownloadState.FINALIZING || state == VoxDownloadState.MUXED || state == VoxDownloadState.PUBLISHING) {
                return null
            }
            if (state == VoxDownloadState.MUXED || state == VoxDownloadState.COMPLETED) {
                return 100
            }
            val expected = totalBytesExpected ?: return null
            if (expected <= 0) return null
            return ((totalBytesDownloaded * 100) / expected).toInt().coerceIn(0, 100)
        }
}

/**
 * Категории ошибок скачивания.
 */
enum class VoxDownloadErrorCode {
    AUTH_REQUIRED,
    INSUFFICIENT_STORAGE,
    STORAGE_ERROR,
    URL_EXPIRED,
    NETWORK_ERROR,
    STREAM_UNAVAILABLE,
    TRANSLATION_UNAVAILABLE,
    INVALID_URL,
    UNSUPPORTED_CODEC,
    MEDIA_PARSE_ERROR,
    PROCESSING_STALLED,
    CANCELLED,
    UNKNOWN
}

/**
 * Исключение процесса скачивания с типизированным кодом ошибки.
 */
class VoxDownloadException(
    val code: VoxDownloadErrorCode,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
