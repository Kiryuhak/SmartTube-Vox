package com.liskovsoft.smartyoutubetv2.common.vox.badge

import java.util.Locale
import java.util.regex.Pattern

/**
 * Formatter for premium TV quality badges.
 * Converts raw resolution/quality descriptors into unified canonical formats:
 * - 4K · 2160p (or 4K)
 * - 2K · 1440p (or 2K)
 * - FHD · 1080p (or FHD)
 * - HD · 720p (or HD)
 * - SD · 480p / SD · 360p (or SD)
 */
object VoxQualityBadgeFormatter {

    // Matches e.g. "720p", "1080p", "1080p60", "2160p", "1440p HDR"
    private val EXPLICIT_P_PATTERN = Pattern.compile("(?i)\\b(2160|1440|1080|720|480|360|240|144)p(?:\\d{2})?\\b")
    private val STANDALONE_RES_PATTERN = Pattern.compile("(?i)\\b(4320|2160|1440|1080|720|480|360)\\b")

    @JvmStatic
    fun format(badge: VoxQualityBadge?): String? {
        if (badge == null) return null
        val tier = badge.tier.label
        val res = badge.resolutionP
        val text = if (res != null && res > 0) {
            "$tier · ${res}p"
        } else {
            tier
        }
        return text + if (badge.isHdr) " · HDR" else ""
    }

    @JvmStatic
    fun formatFromHeight(height: Int): String? {
        val badge = fromHeight(height) ?: return null
        return format(badge)
    }

    @JvmStatic
    fun fromHeight(height: Int): VoxQualityBadge? {
        return when {
            height >= 2160 -> VoxQualityBadge(QualityTier.TIER_4K, 2160)
            height >= 1440 -> VoxQualityBadge(QualityTier.TIER_2K, 1440)
            height >= 1080 -> VoxQualityBadge(QualityTier.TIER_FHD, 1080)
            height >= 720 -> VoxQualityBadge(QualityTier.TIER_HD, 720)
            height in 480..719 -> VoxQualityBadge(QualityTier.TIER_SD, 480)
            height in 1..479 -> VoxQualityBadge(QualityTier.TIER_SD, height)
            else -> null
        }
    }

    @JvmStatic
    fun parse(raw: String?): VoxQualityBadge? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val upper = trimmed.uppercase(Locale.US)

        // Filter out false positives like views ("624K VIEWS", "100K", "1.2M VIEWS", "AGO", "SUBSCRIBERS")
        if (upper.contains("VIEW") || upper.contains("ПРОСМОТР") || upper.contains("SUB") ||
            upper.contains("AGO") || upper.contains("НАЗАД") || upper.contains("YEAR") ||
            upper.contains("MONTH") || upper.contains("DAY") || upper.contains("HOUR") ||
            upper.contains("MIN") || upper == "UNKNOWN" || upper == "0P" || upper == "AUTO" || upper == "NONE") {
            return null
        }

        // 1. Explicit resolution with 'p' (e.g. "720p", "1080p60", "2160p")
        val pMatcher = EXPLICIT_P_PATTERN.matcher(upper)
        if (pMatcher.find()) {
            val height = pMatcher.group(1)?.toIntOrNull()
            if (height != null) {
                return when {
                    height >= 2160 -> VoxQualityBadge(QualityTier.TIER_4K, height)
                    height >= 1440 -> VoxQualityBadge(QualityTier.TIER_2K, height)
                    height >= 1080 -> VoxQualityBadge(QualityTier.TIER_FHD, height)
                    height >= 720 -> VoxQualityBadge(QualityTier.TIER_HD, height)
                    height in 480..719 -> VoxQualityBadge(QualityTier.TIER_SD, height)
                    else -> VoxQualityBadge(QualityTier.TIER_SD, height)
                }
            }
        }

        // 2. Pure tier labels
        if (upper == "4K" || upper.startsWith("4K ") || upper.endsWith(" 4K") || upper == "8K" || upper.contains("4320")) {
            return VoxQualityBadge(QualityTier.TIER_4K, 2160)
        }
        if (upper == "2K" || upper == "QHD" || upper.startsWith("2K ") || upper.endsWith(" 2K")) {
            return VoxQualityBadge(QualityTier.TIER_2K, 1440)
        }
        if (upper == "FHD" || upper == "FULL HD" || upper == "FULLHD") {
            return VoxQualityBadge(QualityTier.TIER_FHD, 1080)
        }
        if (upper == "HD" || upper.startsWith("HD ") || upper.endsWith(" HD")) {
            return VoxQualityBadge(QualityTier.TIER_HD, 720)
        }
        if (upper == "SD") {
            return VoxQualityBadge(QualityTier.TIER_SD, 480)
        }

        // 3. Standalone known resolution numbers (only exact matches, not parts of words/views)
        val standaloneMatcher = STANDALONE_RES_PATTERN.matcher(upper)
        if (standaloneMatcher.matches()) {
            val height = standaloneMatcher.group(1)?.toIntOrNull()
            if (height != null) {
                return fromHeight(height)
            }
        }

        return null
    }

    @JvmStatic
    fun isQualityString(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return parse(raw) != null
    }
}
