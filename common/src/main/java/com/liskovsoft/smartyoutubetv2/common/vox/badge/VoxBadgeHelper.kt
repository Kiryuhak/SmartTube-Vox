package com.liskovsoft.smartyoutubetv2.common.vox.badge

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import java.util.Locale
import java.util.regex.Pattern

/**
 * Помощник для сопоставления и форматирования бейджей качества видео (4K, 2K, FHD, HD, SD).
 * Гарантирует, что при отсутствии достоверных метаданных бейдж не отображается.
 */
object VoxBadgeHelper {

    const val BADGE_4K = "4K"
    const val BADGE_2K = "2K"
    const val BADGE_FHD = "FHD"
    const val BADGE_HD = "HD"
    const val BADGE_SD = "SD"

    private val QUALITY_NUMERIC_PATTERN = Pattern.compile("(?i)(\\d{3,4})p?")

    @JvmStatic
    fun normalizeQuality(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val upper = trimmed.uppercase(Locale.US)

        // Исключаем плейсхолдеры и неизвестные значения
        if (upper == "UNKNOWN" || upper == "0P" || upper == "AUTO" || upper == "NONE") {
            return null
        }

        // Прямые текстовые совпадения
        if (upper == "4K" || upper.startsWith("4K ") || upper.endsWith(" 4K") || upper.contains("8K") || upper.contains("4320")) {
            return BADGE_4K
        }
        if (upper == "2K" || upper == "QHD" || upper.startsWith("2K ") || upper.endsWith(" 2K")) {
            return BADGE_2K
        }
        if (upper == "FHD" || upper == "FULL HD" || upper == "FULLHD") {
            return BADGE_FHD
        }
        if (upper == "HD" || upper.startsWith("HD ") || upper.endsWith(" HD")) {
            return BADGE_HD
        }
        if (upper == "SD") {
            return BADGE_SD
        }

        // Числовой поиск (например: "2160p", "1440p", "1080p60", "720p", "480p")
        val matcher = QUALITY_NUMERIC_PATTERN.matcher(upper)
        if (matcher.find()) {
            val numStr = matcher.group(1)
            val height = numStr?.toIntOrNull() ?: return null
            return when {
                height >= 2160 -> BADGE_4K
                height >= 1440 -> BADGE_2K
                height >= 1080 -> BADGE_FHD
                height >= 720 -> BADGE_HD
                height > 0 -> BADGE_SD
                else -> null
            }
        }

        return null
    }

    @JvmStatic
    fun getQualityBadgeFromDimensions(width: Int, height: Int): String? {
        if (width <= 0 && height <= 0) return null
        return when {
            height >= 2160 || width >= 3840 -> BADGE_4K
            height >= 1440 || width >= 2560 -> BADGE_2K
            height >= 1080 || width >= 1920 -> BADGE_FHD
            height >= 720 || width >= 1280 -> BADGE_HD
            height in 1..719 || width in 1..1279 -> BADGE_SD
            else -> null
        }
    }

    @JvmStatic
    fun getQualityBadge(video: Video?): String? {
        if (video == null) return null

        // 1. Для локально скачанных видео качество известно точно из параметров загрузки
        if (video.isLocal) {
            normalizeQuality(video.badge)?.let { return it }
        }

        // 2. Явный бейдж в модели видео (например, YouTube descBadge "4K" или "HD")
        normalizeQuality(video.badge)?.let { return it }

        // 3. Проверка метаданных описания/второй строки (например "4K · Автор · 1 млн")
        val second = video.secondTitle?.toString()
        if (!second.isNullOrBlank()) {
            val tokens = second.split("•", "·", "|", "-")
            for (token in tokens) {
                val candidate = normalizeQuality(token.trim())
                if (candidate != null) return candidate
            }
        }

        return null
    }
}
