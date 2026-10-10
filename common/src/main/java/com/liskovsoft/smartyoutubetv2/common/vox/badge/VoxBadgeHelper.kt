package com.liskovsoft.smartyoutubetv2.common.vox.badge

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video

/**
 * Помощник для сопоставления и форматирования бейджей качества видео (4K · 2160p, 2K · 1440p, FHD · 1080p, HD · 720p, SD · 480p).
 * Гарантирует, что при отсутствии достоверных метаданных бейдж не отображается.
 */
enum class QualityConfidence {
    UNKNOWN,
    METADATA,
    PLAYBACK_CONFIRMED,
    DOWNLOAD_CONFIRMED
}

object VoxBadgeHelper {

    const val BADGE_4K = "4K"
    const val BADGE_2K = "1440p"
    const val BADGE_FHD = "1080p"
    const val BADGE_HD = "720p"
    const val BADGE_SD = "480p"

    @JvmStatic
    fun resolveQualityBadgeWithConfidence(video: Video?): Pair<String?, QualityConfidence> {
        if (video == null) return Pair(null, QualityConfidence.UNKNOWN)
        if (video.isLocal) {
            val badge = getQualityBadge(video)
            return if (badge != null) Pair(badge, QualityConfidence.DOWNLOAD_CONFIRMED) else Pair(null, QualityConfidence.UNKNOWN)
        }
        val badge = getQualityBadge(video)
        return if (badge != null) {
            Pair(badge, QualityConfidence.METADATA)
        } else {
            Pair(null, QualityConfidence.UNKNOWN)
        }
    }

    @JvmStatic
    fun normalizeQuality(raw: String?): String? {
        val badge = VoxQualityBadgeFormatter.parse(raw) ?: return null
        return VoxQualityBadgeFormatter.format(badge.copy(isHdr = raw?.contains("HDR", ignoreCase = true) == true))
    }

    /** Продолжительность независима от качества и не исчезает после выделения quality badge. */
    @JvmStatic fun getDurationBadge(video: Video?): String? {
        if (video == null || video.videoId == null || video.isChannel || video.isMix || video.isLive || video.isShorts || video.isUpcoming) return null
        val seconds = video.durationMs / 1000L
        if (seconds <= 0L) return video.badge?.takeIf { it.matches(Regex("\\d{1,3}:\\d{2}(?::\\d{2})?")) }
        return if (seconds >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    @JvmStatic
    fun getQualityBadgeFromDimensions(width: Int, height: Int): String? {
        if (width <= 0 && height <= 0) return null
        val effectiveRes = when {
            width > 0 && height > 0 -> kotlin.math.min(width, height)
            height > 0 -> height
            width >= 3840 -> 2160
            width >= 2560 -> 1440
            width >= 1920 -> 1080
            width >= 1280 -> 720
            width >= 854 -> 480
            width > 0 -> width
            else -> 0
        }
        if (effectiveRes <= 0) return null
        return VoxQualityBadgeFormatter.formatFromHeight(effectiveRes)
    }

    @JvmStatic
    fun getQualityBadge(video: Video?): String? {
        if (video == null) return null

        // 1. Для локально скачанных видео качество известно точно из параметров загрузки
        if (video.isLocal) {
            normalizeQuality(video.badge)?.let { return it }
            if (video.width > 0 || video.height > 0) {
                getQualityBadgeFromDimensions(video.width, video.height)?.let { return it }
            }
        }

        // 2. Явный бейдж в модели видео (например, YouTube descBadge "4K" или "HD")
        normalizeQuality(video.badge)?.let { return it }

        // 4. Проверка метаданных описания/второй строки (например "4K · Автор · 1 млн")
        val second = video.secondTitle?.toString()
        if (!second.isNullOrBlank()) {
            val tokens = second.split("•", "·", "|", "-")
            for (token in tokens) {
                val candidate = normalizeQuality(token.trim())
                if (candidate != null) return candidate
            }
        }

        // 5. Проверка заголовка (например "[4K] Documentary", "1080p60", "4K HDR")
        val title = video.title
        if (!title.isNullOrBlank()) {
            val tokens = title.split("[", "]", "(", ")", "【", "】", "「", "」", "•", "·", "|", " - ")
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
