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
 * Гарантирует выбор наиболее стабильного и совместимого потока с защитой от тихого занижения качества.
 */
object VoxDownloadFallbackPolicy {

    /**
     * Оценивает эффективное разрешение потока с учетом различных соотношений сторон:
     * - 16:9 Standard: 3840x2160 (4K), 2560x1440 (2K), 1920x1080 (FHD), 1280x720 (HD).
     * - Portrait / Shorts: 2160x3840 (4K portrait), 1440x2560 (2K portrait), 1080x1920 (FHD portrait), 720x1280.
     * - Ultrawide / Cinematic: 3840x1600 (4K widescreen 2.4:1), 3840x1620, 2560x1080, 1920x800, 1280x540.
     * - DCI 4K: 4096x2160.
     * - Потоки только с указанием высоты: height in 2160..4320 -> 2160, 1400..2159 -> 1440, etc.
     */
    @JvmStatic
    fun getEffectiveResolution(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) {
            val h = if (height > 0) height else width
            return when {
                h >= 2160 -> 2160
                h >= 1400 -> 1440
                h >= 1000 -> 1080
                h >= 700 -> 720
                h >= 450 -> 480
                h >= 300 -> 360
                h > 0 -> h
                else -> 0
            }
        }

        val longDim = maxOf(width, height)
        val shortDim = minOf(width, height)

        return when {
            // 4K (2160p): 16:9 (3840x2160), portrait (2160x3840), ultrawide (3840x1600), DCI 4K (4096x2160)
            longDim >= 3840 || shortDim >= 2160 -> 2160
            // 2K (1440p): 16:9 (2560x1440), portrait (1440x2560), ultrawide (2560x1080)
            longDim >= 2560 || shortDim >= 1440 -> 1440
            // 1080p (FHD): 16:9 (1920x1080), portrait (1080x1920), ultrawide (1920x800)
            longDim >= 1920 || shortDim >= 1080 -> 1080
            // 720p (HD): 16:9 (1280x720), portrait (720x1280), ultrawide (1280x540)
            longDim >= 1280 || shortDim >= 720 -> 720
            // 480p: 854x480, 480x854
            longDim >= 848 || shortDim >= 480 -> 480
            // 360p: 640x360, 360x640
            longDim >= 640 || shortDim >= 360 -> 360
            shortDim > 0 -> shortDim
            else -> 0
        }
    }

    /**
     * Проверяет, удовлетворяет ли формат запрошенному качеству.
     */
    @JvmStatic
    fun matchesQuality(format: MediaFormat, preference: VoxQualityPreference): Boolean {
        if (preference == VoxQualityPreference.QUALITY_AUTO) return true
        val eff = getEffectiveResolution(format.width, format.height)
        return eff == preference.maxResolution
    }

    /**
     * Подбирает оптимальный видеопоток:
     * 1. При явном выборе разрешения (2160p, 1440p, 1080p, 720p и т.д.):
     *    - Ищет форматы, соответствующие запрошенному инварианту разрешения.
     *    - Для Ultra-HD (> 1080p): приоритет кодеков VP9 -> AV1 -> AVC.
     *    - Для стандартных (<= 1080p): приоритет AVC (H.264) -> VP9 -> AV1.
     *    - Если формат запрошенного качества отсутствует: ВОЗВРАЩАЕТ NULL (тихий даунгрейд исключен).
     * 2. При выборе QUALITY_AUTO:
     *    - Выбирает наивысшее доступное разрешение среди всех кандидатов.
     *    - Формирует явную отметку "Выбрано наилучшее доступное: [разрешение]p".
     */
    @JvmStatic
    fun selectVideoFormat(
        candidates: List<MediaFormat>,
        preference: VoxQualityPreference
    ): VoxFormatFallbackResult<MediaFormat>? {
        val validCandidates = candidates.filter { !it.url.isNullOrBlank() }
        if (validCandidates.isEmpty()) return null

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

        fun isAv1(f: MediaFormat): Boolean {
            val mime = f.mimeType?.lowercase(Locale.US) ?: ""
            return mime.contains("av01") || mime.contains("av1")
        }

        fun codecPriority(f: MediaFormat, isUhd: Boolean): Int {
            if (VoxCodecTestOverrides.forceVp9Video) {
                return when {
                    isVp9(f) -> 0
                    isAvc(f) -> 1
                    isAv1(f) -> 2
                    else -> 3
                }
            }
            return if (isUhd) {
                // Для 4K/2K YouTube почти исключительно отдаёт VP9/AV1
                when {
                    isVp9(f) -> 0
                    isAv1(f) -> 1
                    isAvc(f) -> 2
                    else -> 3
                }
            } else {
                // Для <= 1080p AVC обеспечивает максимальную аппаратную совместимость
                when {
                    isAvc(f) -> 0
                    isVp9(f) -> 1
                    isAv1(f) -> 2
                    else -> 3
                }
            }
        }

        // Явный выбор разрешения пользователем: строгая проверка инварианта
        if (preference != VoxQualityPreference.QUALITY_AUTO) {
            val targetRes = preference.maxResolution
            val matching = validCandidates.filter { getEffectiveResolution(it.width, it.height) == targetRes }

            if (matching.isEmpty()) {
                // Строго: никогда не занижать разрешение молча!
                return null
            }

            val isUhd = targetRes > 1080
            val chosen = matching.minWithOrNull(
                compareBy<MediaFormat> { codecPriority(it, isUhd) }
                    .thenByDescending { VotMediaFormatSelector.parseBitrate(it.bitrate).let { b -> if (b == 999_999_999) 0 else b } }
            ) ?: matching.first()

            return VoxFormatFallbackResult(
                selected = chosen,
                fallbackApplied = false,
                note = null
            )
        }

        // QUALITY_AUTO: выбор наивысшего доступного разрешения среди всех форматов
        val sortedByResAndCodec = validCandidates.sortedWith(
            compareByDescending<MediaFormat> { getEffectiveResolution(it.width, it.height) }
                .thenBy { codecPriority(it, getEffectiveResolution(it.width, it.height) > 1080) }
                .thenByDescending { VotMediaFormatSelector.parseBitrate(it.bitrate).let { b -> if (b == 999_999_999) 0 else b } }
        )

        val best = sortedByResAndCodec.firstOrNull() ?: return null
        val effRes = getEffectiveResolution(best.width, best.height)
        val note = "Выбрано наилучшее доступное: ${if (effRes > 0) "${effRes}p" else "Auto"}"

        return VoxFormatFallbackResult(
            selected = best,
            fallbackApplied = false,
            note = note
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
            val nonRussian = aacCandidates.filter {
                !VotMediaFormatSelector.isRussianFormat(it) &&
                !VotMediaFormatSelector.isDubbedFormat(it) &&
                !VotMediaFormatSelector.isAutoDubbedFormat(it)
            }
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
            val nonRussian = anyAudio.filter {
                !VotMediaFormatSelector.isRussianFormat(it) &&
                !VotMediaFormatSelector.isDubbedFormat(it) &&
                !VotMediaFormatSelector.isAutoDubbedFormat(it)
            }
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
