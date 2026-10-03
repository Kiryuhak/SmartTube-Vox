package com.liskovsoft.smartyoutubetv2.common.vox.capability

/**
 * Единая модель платформ SmartTube VOX.
 */
enum class VoxPlatform(val id: String, val displayName: String) {
    ANDROID_TV("android_tv", "Android TV"),
    GOOGLE_TV("google_tv", "Google TV"),
    TIZEN("tizen", "Samsung Tizen"),
    UNKNOWN("unknown", "Неизвестная платформа");

    companion object {
        @JvmStatic
        fun fromId(id: String?): VoxPlatform {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: UNKNOWN
        }
    }
}
