package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
import com.liskovsoft.smartyoutubetv2.common.vot.VotMediaFormatSelector
import java.util.Locale

/**
 * Результат применения политики выбора и отката форматов для скачивания.
 */
data class VoxFormatFallbackResult<T>(
    val selected: T,
    val fallbackApplied: Boolean,
    val note: String? = null
)

/**
 * Политика резервных вариантов форматов для скачивания видео и аудио.
 * Гарантирует выбор наиболее стабильного и совместимого потока без превышения запрошенного разрешения.
 */
object VoxDownloadFallbackPolicy {

    /**
     * Подбирает оптимальный видеопоток с безопасным откатом:
     * 1. Ищет потоки с высотой <= preference.maxResolution.
     * 2. Приоритет кодеков: AVC/H.264 (максимальная совместимость с Matroska/MediaExtractor) -> VP9 -> AV1.
     * 3. При недоступности запрошенного разрешения выбирает наивысшее доступное разрешение <= запрошенного.
     */
    @JvmStatic
    fun selectVideoFormat(
        candidates: List<MediaFormat>,
        preference: VoxQualityPreference
    ): VoxFormatFallbackResult<MediaFormat>? {
        if (candidates.isEmpty()) return null

        val maxRes = preference.maxResolution

        fun isAvc(f: MediaFormat): Boolean {
            val mime = f.mimeType?.lowercase(Locale.US) ?: ""
            if (mime.contains("av01") || mime.contains("av1") || mime.contains("vp9") || mime.contains("vp09")) {
                return false
            }
            return mime.contains("avc") || mime.contains("h264") || mime.contains("mp4v") || mime.contains("mp4")
        }

        fun isVp9(f: MediaFormat): Boolean {
            val mime = f.mimeType?.lowercase(Locale.US) ?: ""
            return mime.contains("vp9") || mime.contains("vp09")
        }

        // 1. Предпочитаем AVC/H.264 потоки, чья высота <= maxRes (по убыванию высоты)
        val avcMatching = candidates
            .filter { isAvc(it) && it.height in 1..maxRes && !it.url.isNullOrBlank() }
            .sortedByDescending { it.height }

        if (avcMatching.isNotEmpty()) {
            val chosen = avcMatching.first()
            val isFallback = preference != VoxQualityPreference.QUALITY_AUTO && chosen.height < preference.maxResolution
            return VoxFormatFallbackResult(
                selected = chosen,
                fallbackApplied = isFallback,
                note = if (isFallback) "Качество снижено до ${chosen.height}p" else null
            )
        }

        // 2. VP9 потоки <= maxRes
        val vp9Matching = candidates
            .filter { isVp9(it) && it.height in 1..maxRes && !it.url.isNullOrBlank() }
            .sortedByDescending { it.height }

        if (vp9Matching.isNotEmpty()) {
            val chosen = vp9Matching.first()
            return VoxFormatFallbackResult(
                selected = chosen,
                fallbackApplied = true,
                note = "Используется VP9 ${chosen.height}p"
            )
        }

        // 3. Любые другие видеоформаты <= maxRes
        val anyMatching = candidates
            .filter { it.height in 1..maxRes && !it.url.isNullOrBlank() }
            .sortedByDescending { it.height }

        if (anyMatching.isNotEmpty()) {
            val chosen = anyMatching.first()
            return VoxFormatFallbackResult(
                selected = chosen,
                fallbackApplied = true,
                note = "Используется поток ${chosen.height}p"
            )
        }

        // 4. Если нет форматов <= maxRes, выбираем наименьший доступный формат выше maxRes
        val higherAvc = candidates
            .filter { isAvc(it) && it.height > maxRes && !it.url.isNullOrBlank() }
            .sortedBy { it.height }

        if (higherAvc.isNotEmpty()) {
            return VoxFormatFallbackResult(
                selected = higherAvc.first(),
                fallbackApplied = true,
                note = "Выбран минимальный доступный поток ${higherAvc.first().height}p"
            )
        }

        val higherAny = candidates
            .filter { it.height > maxRes && !it.url.isNullOrBlank() }
            .sortedBy { it.height }

        if (higherAny.isNotEmpty()) {
            return VoxFormatFallbackResult(
                selected = higherAny.first(),
                fallbackApplied = true,
                note = "Выбран минимальный доступный поток ${higherAny.first().height}p"
            )
        }

        // 5. Форматы без явной высоты (progressive)
        val firstAvailable = candidates.firstOrNull { !it.url.isNullOrBlank() } ?: return null
        return VoxFormatFallbackResult(
            selected = firstAvailable,
            fallbackApplied = false
        )
    }

    /**
     * Подбирает оптимальный аудиопоток с безопасным откатом:
     * 1. Приоритет: AAC (audio/mp4) с наилучшим битрейтом для гарантированного распознавания в MediaExtractor.
     * 2. Резерв: Opus.
     */
    @JvmStatic
    fun selectAudioFormat(candidates: List<MediaFormat>): VoxFormatFallbackResult<MediaFormat>? {
        if (candidates.isEmpty()) return null

        val isAac = { f: MediaFormat ->
            val mime = f.mimeType?.lowercase(Locale.US) ?: ""
            mime.startsWith("audio/") && (mime.contains("mp4") || mime.contains("mp4a") || mime.contains("aac")) && !f.url.isNullOrBlank()
        }

        val aacCandidates = candidates.filter { isAac(it) }
        if (aacCandidates.isNotEmpty()) {
            val nonRussian = aacCandidates.filter { !VotMediaFormatSelector.isRussianFormat(it) && !VotMediaFormatSelector.isDubbedFormat(it) }
            val pool = if (nonRussian.isNotEmpty()) nonRussian else aacCandidates
            val best = pool.maxByOrNull { VotMediaFormatSelector.parseBitrate(it.bitrate).let { b -> if (b == 999_999_999) 0 else b } }
                ?: pool.first()
            return VoxFormatFallbackResult(
                selected = best,
                fallbackApplied = false
            )
        }

        // Резервный откат на любой доступный аудиопоток (например, Opus)
        val anyAudio = candidates.filter {
            val mime = it.mimeType?.lowercase(Locale.US) ?: ""
            mime.startsWith("audio/") && !it.url.isNullOrBlank()
        }
        if (anyAudio.isNotEmpty()) {
            val nonRussian = anyAudio.filter { !VotMediaFormatSelector.isRussianFormat(it) && !VotMediaFormatSelector.isDubbedFormat(it) }
            val pool = if (nonRussian.isNotEmpty()) nonRussian else anyAudio
            val best = pool.maxByOrNull { VotMediaFormatSelector.parseBitrate(it.bitrate).let { b -> if (b == 999_999_999) 0 else b } }
                ?: pool.first()
            return VoxFormatFallbackResult(
                selected = best,
                fallbackApplied = true,
                note = "Используется резервный аудиопоток"
            )
        }

        return null
    }
}
