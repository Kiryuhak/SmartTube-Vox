package com.liskovsoft.smartyoutubetv2.common.vox.badge

/**
 * Канонические возрастные рейтинги контента.
 */
enum class VoxAgeRating(val label: String, val minimumAge: Int) {
    AGE_0("0+", 0),
    AGE_6("6+", 6),
    AGE_12("12+", 12),
    AGE_16("16+", 16),
    AGE_18("18+", 18)
}
