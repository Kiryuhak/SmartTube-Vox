package com.liskovsoft.smartyoutubetv2.common.vox.external

/**
 * Данные запроса внешнего запуска видео (DIAL / LAN).
 */
data class VoxExternalVideoRequest(
    val videoId: String,
    val playlistId: String? = null,
    val timeMs: Long = -1L,
    val clientIp: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)
