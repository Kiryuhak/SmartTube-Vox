package com.liskovsoft.smartyoutubetv2.common.vox.badge

import java.util.Locale
import java.util.regex.Pattern

/**
 * Formatter for premium TV quality badges.
 * Converts raw resolution/quality descriptors into unified canonical formats:
 * - 4K (or 4K60, 4K HDR)
 * - 1440p (or 1440p60)
 * - 1080p (or 1080p60)
 * - 720p (or 720p60)
 * - 480p / 360p / 240p
 */
object VoxQualityBadgeFormatter {

    // Matches e.g. "720p", "1080p", "1080p60", "2160p", "1440p HDR"
    private val EXPLICIT_P_PATTERN = Pattern.compile("(?i)\\b(2160|1440|1080|720|480|360|240|144)p(?:(\\d{2}))?\\b")
    private val STANDALONE_RES_PATTERN = Pattern.compile("(?i)\\b(4320|2160|1440|1080|720|480|360)\\b")

    @JvmStatic
    fun format(badge: VoxQualityBadge?): String? {
        if (badge == null) return null
        val base = when {
            badge.tier == QualityTier.TIER_4K && (badge.resolutionP == null || badge.resolutionP == 2160) -> "4K"
            badge.resolutionP != null && badge.resolutionP > 0 -> "${badge.resolutionP}p"
            else -> badge.tier.label
        }
        val fpsSuffix = if (badge.fps != null && badge.fps >= 50) "${badge.fps}" else ""
        val hdrSuffix = if (badge.isHdr) " HDR" else ""
        return "$base$fpsSuffix$hdrSuffix"
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

        val isHdr = upper.contains("HDR")

        // 1. Explicit resolution with 'p' (e.g. "720p", "1080p60", "2160p")
        val pMatcher = EXPLICIT_P_PATTERN.matcher(upper)
        if (pMatcher.find()) {
            val height = pMatcher.group(1)?.toIntOrNull()
            val fps = pMatcher.group(2)?.toIntOrNull()
            if (height != null) {
                return when {
                    height >= 2160 -> VoxQualityBadge(QualityTier.TIER_4K, height, fps = fps, isHdr = isHdr)
                    height >= 1440 -> VoxQualityBadge(QualityTier.TIER_2K, height, fps = fps, isHdr = isHdr)
                    height >= 1080 -> VoxQualityBadge(QualityTier.TIER_FHD, height, fps = fps, isHdr = isHdr)
                    height >= 720 -> VoxQualityBadge(QualityTier.TIER_HD, height, fps = fps, isHdr = isHdr)
                    height in 480..719 -> VoxQualityBadge(QualityTier.TIER_SD, height, fps = fps, isHdr = isHdr)
                    else -> VoxQualityBadge(QualityTier.TIER_SD, height, fps = fps, isHdr = isHdr)
                }
            }
        }

        // 2. Pure tier labels
        if (upper == "4K" || upper.startsWith("4K ") || upper.endsWith(" 4K") || upper == "8K" || upper.contains("4320")) {
            return VoxQualityBadge(QualityTier.TIER_4K, 2160, isHdr = isHdr)
        }
        if (upper == "2K" || upper == "QHD" || upper.startsWith("2K ") || upper.endsWith(" 2K")) {
            return VoxQualityBadge(QualityTier.TIER_2K, 1440, isHdr = isHdr)
        }
        if (upper == "FHD" || upper == "FULL HD" || upper == "FULLHD") {
            return VoxQualityBadge(QualityTier.TIER_FHD, 1080, isHdr = isHdr)
        }
        // Generic YouTube "HD" label is omitted to avoid falsely marking 1080p/1440p/4K videos as 720p.
        if (upper == "SD") {
            return VoxQualityBadge(QualityTier.TIER_SD, 480, isHdr = isHdr)
        }

        // 3. Standalone known resolution numbers (only exact matches, not parts of words/views)
        val standaloneMatcher = STANDALONE_RES_PATTERN.matcher(upper)
        if (standaloneMatcher.matches()) {
            val height = standaloneMatcher.group(1)?.toIntOrNull()
            if (height != null) {
                return fromHeight(height)?.copy(isHdr = isHdr)
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
