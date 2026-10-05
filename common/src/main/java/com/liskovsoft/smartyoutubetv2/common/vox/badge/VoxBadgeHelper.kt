package com.liskovsoft.smartyoutubetv2.common.vox.badge

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video

/**
 * Помощник для сопоставления и форматирования бейджей качества видео (4K · 2160p, 2K · 1440p, FHD · 1080p, HD · 720p, SD · 480p).
 * Гарантирует, что при отсутствии достоверных метаданных бейдж не отображается.
 */
object VoxBadgeHelper {

    const val BADGE_4K = "4K · 2160p"
    const val BADGE_2K = "2K · 1440p"
    const val BADGE_FHD = "FHD · 1080p"
    const val BADGE_HD = "HD · 720p"
    const val BADGE_SD = "SD · 480p"

    @JvmStatic
    fun normalizeQuality(raw: String?): String? {
        val badge = VoxQualityBadgeFormatter.parse(raw) ?: return null
        return VoxQualityBadgeFormatter.format(badge)
    }

    @JvmStatic
    fun getQualityBadgeFromDimensions(width: Int, height: Int): String? {
        if (width <= 0 && height <= 0) return null
        val effectiveHeight = when {
            height > 0 -> height
            width >= 3840 -> 2160
            width >= 2560 -> 1440
            width >= 1920 -> 1080
            width >= 1280 -> 720
            width > 0 -> 480
            else -> 0
        }
        return VoxQualityBadgeFormatter.formatFromHeight(effectiveHeight)
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

    @JvmStatic
    fun isQualityText(raw: String?): Boolean {
        return VoxQualityBadgeFormatter.isQualityString(raw)
    }

    @JvmStatic
    fun normalizeAge(raw: String?): String? {
        val parsed = VoxAgeRatingFormatter.parse(raw) ?: return null
        return VoxAgeRatingFormatter.format(parsed)
    }

    @JvmStatic
    fun getAgeBadge(video: Video?): String? {
        if (video == null) return null

        // 1. Прямое поле возрастного рейтинга
        normalizeAge(video.ageRating)?.let { return it }

        // 2. Явный бейдж в модели видео
        normalizeAge(video.badge)?.let { return it }

        // 3. Проверка метаданных описания/второй строки
        val second = video.secondTitle?.toString()
        if (!second.isNullOrBlank()) {
            val tokens = second.split("•", "·", "|", "-")
            for (token in tokens) {
                val candidate = normalizeAge(token.trim())
                if (candidate != null) return candidate
            }
        }

        return null
    }

    @JvmStatic
    fun isAgeText(raw: String?): Boolean {
        return VoxAgeRatingFormatter.isAgeString(raw)
    }
}
