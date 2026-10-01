package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.mediaserviceinterfaces.MediaItemService
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.smartyoutubetv2.common.vot.VotMediaFormatSelector
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import java.util.Locale

/**
 * Метаданные разрешенного медиа-потока (находятся исключительно в оперативной памяти).
 */
data class VoxResolvedStream(
    val url: String,
    val itag: Int,
    val mimeType: String,
    val codec: String,
    val contentLength: Long? = null,
    val supportsRange: Boolean = true,
    val width: Int = 0,
    val height: Int = 0
)

/**
 * Интерфейс резолвера медиа-потоков YouTube.
 */
interface VoxStreamResolver {
    @Throws(VoxDownloadException::class)
    fun resolveVideoStream(videoId: String, qualityPreference: VoxQualityPreference): VoxResolvedStream

    @Throws(VoxDownloadException::class)
    fun resolveOriginalAudio(videoId: String): VoxResolvedStream
}

/**
 * Реализация по умолчанию резолвера медиа-потоков YouTube через MediaItemService.
 */
class DefaultVoxStreamResolver(
    private val mediaItemServiceProvider: () -> MediaItemService? = {
        YouTubeServiceManager.instance()?.mediaItemService
    }
) : VoxStreamResolver {

    @Throws(VoxDownloadException::class)
    override fun resolveVideoStream(videoId: String, qualityPreference: VoxQualityPreference): VoxResolvedStream {
        val formatInfo = fetchFormatInfo(videoId)
        val formats = collectAllFormats(formatInfo)

        val videoCandidates = formats.filter { format ->
            val mime = format.mimeType?.lowercase(Locale.US) ?: ""
            mime.startsWith("video/") && !format.url.isNullOrBlank()
        }

        if (videoCandidates.isEmpty()) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.STREAM_UNAVAILABLE,
                "No playable video streams found for videoId=$videoId"
            )
        }

        // Выбираем формат с наилучшим совпадением по разрешению
        val selectedFormat = selectBestVideoFormat(videoCandidates, qualityPreference)
            ?: videoCandidates.first()

        val url = selectedFormat.url
            ?: throw VoxDownloadException(VoxDownloadErrorCode.STREAM_UNAVAILABLE, "Video format missing URL")

        // Проверка безопасности URL
        VoxUrlSecurityValidator.validateUrl(url)

        val clen = selectedFormat.clen?.toLongOrNull()
        val mime = selectedFormat.mimeType ?: "video/mp4"
        val codec = extractCodec(mime)
        val itag = selectedFormat.iTag?.toIntOrNull() ?: 0

        return VoxResolvedStream(
            url = url,
            itag = itag,
            mimeType = mime,
            codec = codec,
            contentLength = clen,
            supportsRange = true,
            width = selectedFormat.width,
            height = selectedFormat.height
        )
    }

    @Throws(VoxDownloadException::class)
    override fun resolveOriginalAudio(videoId: String): VoxResolvedStream {
        val formatInfo = fetchFormatInfo(videoId)
        val formats = collectAllFormats(formatInfo)

        // Для скачивания в MKV отдаем предпочтение audio/mp4 (AAC), чтобы MediaExtractor гарантированно получил csd-0 (AudioSpecificConfig)
        val audioFormat = selectBestMuxAudioFormat(formats)
            ?: VotMediaFormatSelector.selectBestAudioFormat(formats)
            ?: formats.firstOrNull { f ->
                val mime = f.mimeType?.lowercase(Locale.US) ?: ""
                mime.startsWith("audio/") && !f.url.isNullOrBlank()
            }
            ?: throw VoxDownloadException(
                VoxDownloadErrorCode.STREAM_UNAVAILABLE,
                "No compatible audio streams found for videoId=$videoId"
            )

        val url = audioFormat.url
            ?: throw VoxDownloadException(VoxDownloadErrorCode.STREAM_UNAVAILABLE, "Audio format missing URL")

        // Проверка безопасности URL
        VoxUrlSecurityValidator.validateUrl(url)

        val clen = audioFormat.clen?.toLongOrNull()
        val mime = audioFormat.mimeType ?: "audio/mp4"
        val codec = extractCodec(mime)
        val itag = audioFormat.iTag?.toIntOrNull() ?: 0

        return VoxResolvedStream(
            url = url,
            itag = itag,
            mimeType = mime,
            codec = codec,
            contentLength = clen,
            supportsRange = true
        )
    }

    private fun selectBestMuxAudioFormat(formats: List<MediaFormat>): MediaFormat? {
        val candidates = formats.filter { f ->
            val mime = f.mimeType?.lowercase(Locale.US) ?: ""
            mime.startsWith("audio/") && (mime.contains("mp4") || mime.contains("mp4a") || mime.contains("aac")) && !f.url.isNullOrBlank()
        }
        if (candidates.isEmpty()) return null

        // Исключаем дублированные русские дорожки, если есть оригиналы
        val nonRussian = candidates.filter { !VotMediaFormatSelector.isRussianFormat(it) && !VotMediaFormatSelector.isDubbedFormat(it) }
        val pool = if (nonRussian.isNotEmpty()) nonRussian else candidates

        // Сортируем по максимальному битрейту для лучшего качества звука в MKV
        return pool.maxByOrNull { VotMediaFormatSelector.parseBitrate(it.bitrate).let { b -> if (b == 999_999_999) 0 else b } }
    }

    private fun fetchFormatInfo(videoId: String): MediaItemFormatInfo {
        val service = mediaItemServiceProvider()
            ?: throw VoxDownloadException(
                VoxDownloadErrorCode.STREAM_UNAVAILABLE,
                "MediaItemService unavailable"
            )

        return try {
            service.getFormatInfo(videoId)
                ?: throw VoxDownloadException(
                    VoxDownloadErrorCode.STREAM_UNAVAILABLE,
                    "Failed to retrieve format info for videoId=$videoId"
                )
        } catch (e: VoxDownloadException) {
            throw e
        } catch (e: Exception) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.STREAM_UNAVAILABLE,
                "Error querying stream metadata for videoId=$videoId: ${e.message}",
                e
            )
        }
    }

    private fun collectAllFormats(formatInfo: MediaItemFormatInfo): List<MediaFormat> {
        val list = mutableListOf<MediaFormat>()
        formatInfo.adaptiveFormats?.let { list.addAll(it) }
        formatInfo.urlFormats?.let { list.addAll(it) }
        return list
    }

    private fun selectBestVideoFormat(
        candidates: List<MediaFormat>,
        preference: VoxQualityPreference
    ): MediaFormat? {
        val maxRes = preference.maxResolution

        fun isAvc(f: MediaFormat): Boolean {
            val mime = f.mimeType?.lowercase(Locale.US) ?: ""
            return mime.contains("avc") || mime.contains("h264") || mime.contains("mp4v") || mime.contains("mp4")
        }

        // 1. Предпочитаем AVC/H.264 форматы, чья высота <= maxRes (по убыванию высоты)
        val avcMatching = candidates
            .filter { isAvc(it) && it.height in 1..maxRes }
            .sortedByDescending { it.height }

        if (avcMatching.isNotEmpty()) {
            return avcMatching.first()
        }

        // 2. Любые другие видеоформаты, чья высота <= maxRes (по убыванию высоты)
        val anyMatching = candidates
            .filter { it.height in 1..maxRes }
            .sortedByDescending { it.height }

        if (anyMatching.isNotEmpty()) {
            return anyMatching.first()
        }

        // 3. Если все форматы выше maxRes, берем AVC с наименьшей высотой
        val higherAvc = candidates
            .filter { isAvc(it) && it.height > maxRes }
            .sortedBy { it.height }

        if (higherAvc.isNotEmpty()) {
            return higherAvc.first()
        }

        // 4. Иначе берем наименьший из доступных форматов выше maxRes
        val higher = candidates
            .filter { it.height > maxRes }
            .sortedBy { it.height }

        if (higher.isNotEmpty()) {
            return higher.first()
        }

        // 5. Если высота не указана явно (например progressive mp4), берем первый доступный
        return candidates.firstOrNull()
    }

    private fun extractCodec(mimeType: String): String {
        val lower = mimeType.lowercase(Locale.US)
        val codecsIdx = lower.indexOf("codecs=\"")
        if (codecsIdx >= 0) {
            val end = lower.indexOf("\"", codecsIdx + 8)
            if (end > codecsIdx + 8) {
                return lower.substring(codecsIdx + 8, end)
            }
        }
        return when {
            lower.contains("avc1") -> "avc1"
            lower.contains("vp9") -> "vp9"
            lower.contains("av01") -> "av01"
            lower.contains("opus") -> "opus"
            lower.contains("mp4a") -> "mp4a"
            else -> "unknown"
        }
    }
}
