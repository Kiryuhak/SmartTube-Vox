package com.liskovsoft.smartyoutubetv2.common.vox.playback

/**
 * Состояния жизненного цикла фонового воспроизведения SmartTube VOX.
 */
enum class VoxBackgroundPlaybackState {
    IDLE,
    REQUESTED,
    ENTERING_AUDIO_ONLY,
    ACTIVE,
    FOCUS_LOST_TEMPORARY,
    RESTORING,
    EXITING,
    FAILED
}
