package com.liskovsoft.smartyoutubetv2.common.vox.mux

import java.io.File

/**
 * Тип медиа-дорожки для мультиплексирования.
 */
enum class VoxMuxTrackType {
    VIDEO,
    AUDIO_ORIGINAL,
    AUDIO_TRANSLATED
}

/**
 * Поддерживаемые кодеки для упаковки в Matroska (MKV).
 */
enum class VoxMuxCodec(val matroskaCodecId: String) {
    AVC("V_MPEG4/ISO/AVC"),
    VP9("V_VP9"),
    AV1("V_AV1"),
    AAC("A_AAC"),
    OPUS("A_OPUS"),
    MP3("A_MPEG/L3"),
    UNKNOWN("V_UNSUPPORTED");

    companion object {
        fun fromMimeType(mime: String?): VoxMuxCodec {
            if (mime == null) return UNKNOWN
            val lower = mime.lowercase()
            return when {
                lower.contains("avc") || lower.contains("h264") || lower.contains("mp4v") -> AVC
                lower.contains("vp9") || lower.contains("vp09") -> VP9
                lower.contains("av01") || lower.contains("av1") -> AV1
                lower.contains("mp4a-latm") || lower.contains("aac") -> AAC
                lower.contains("opus") -> OPUS
                lower.contains("mpeg") || lower.contains("mp3") || lower.contains("mp4a") -> MP3
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Описание медиа-дорожки.
 */
data class VoxMuxTrackInfo(
    val trackNumber: Int,
    val trackUid: Long,
    val trackType: VoxMuxTrackType,
    val codec: VoxMuxCodec,
    val mimeType: String,
    val name: String,
    val language: String,
    val isDefault: Boolean,
    val isForced: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val sampleRate: Double = 0.0,
    val channels: Int = 0,
    val codecPrivate: ByteArray? = null,
    val durationUs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoxMuxTrackInfo) return false
        return trackNumber == other.trackNumber &&
                trackUid == other.trackUid &&
                trackType == other.trackType &&
                codec == other.codec &&
                mimeType == other.mimeType &&
                name == other.name &&
                language == other.language &&
                isDefault == other.isDefault &&
                width == other.width &&
                height == other.height &&
                sampleRate == other.sampleRate &&
                channels == other.channels &&
                codecPrivate.contentEquals(other.codecPrivate)
    }

    override fun hashCode(): Int {
        var result = trackNumber
        result = 31 * result + trackUid.hashCode()
        result = 31 * result + trackType.hashCode()
        result = 31 * result + codec.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + isDefault.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + sampleRate.hashCode()
        result = 31 * result + channels
        result = 31 * result + (codecPrivate?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Единичный медиа-сэмпл (кадр видео или аудио-фрейм).
 */
class VoxMuxSample(
    val trackNumber: Int,
    val presentationTimeUs: Long,
    val durationUs: Long,
    val isKeyFrame: Boolean,
    val data: ByteArray,
    val offset: Int = 0,
    val size: Int = data.size
)

/**
 * Прогресс процесса мультиплексирования.
 */
data class VoxMuxProgress(
    val bytesProcessed: Long,
    val totalInputBytes: Long,
    val percent: Int
)

/**
 * Итоговый результат сборки контейнера.
 */
data class VoxMuxResult(
    val outputFile: File,
    val durationMs: Long,
    val videoSamplesCount: Long,
    val origAudioSamplesCount: Long,
    val transAudioSamplesCount: Long,
    val totalBytesWritten: Long
)

/**
 * Интерфейс источника сэмплов для потоковой сборки без загрузки всего файла в память.
 */
interface VoxSampleSource : AutoCloseable {
    val trackInfo: VoxMuxTrackInfo
    fun readNextSample(): VoxMuxSample?
}
