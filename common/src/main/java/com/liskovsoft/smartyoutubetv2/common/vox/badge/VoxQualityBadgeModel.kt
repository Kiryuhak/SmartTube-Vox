package com.liskovsoft.smartyoutubetv2.common.vox.badge

/**
 * Quality tiers for video resolution presentation.
 */
enum class QualityTier(val label: String) {
    TIER_4K("4K"),
    TIER_2K("2K"),
    TIER_FHD("FHD"),
    TIER_HD("HD"),
    TIER_SD("SD")
}

/**
 * Structured model representing a quality badge.
 */
data class VoxQualityBadge(
    val tier: QualityTier,
    val resolutionP: Int? = null,
    val fps: Int? = null,
    val isHdr: Boolean = false
) {
    fun toDisplayString(): String? {
        return VoxQualityBadgeFormatter.format(this)
    }
}
