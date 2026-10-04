package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Состояние сессии перевода прямого эфира.
 */
data class VoxLiveTranslationState(
    val sessionId: String,
    val videoId: String,
    val isLive: Boolean,
    val bufferState: VoxLiveTranslationBufferState,
    val eligibility: VoxLiveTranslationEligibilityResult,
    val isActive: Boolean = false,
    val failureReasonRu: String? = null
)

/**
 * Политика буферизации сессии перевода прямого эфира.
 */
object VoxLiveTranslationBufferPolicy {
    @JvmStatic
    fun resolveTargetDelayMs(mode: VoxLiveDelayMode): Long {
        return VoxLiveTranslationDelayPolicy.getBounds(mode).targetDelayMs
    }

    @JvmStatic
    fun shouldRebuffer(bufferAheadMs: Long, mode: VoxLiveDelayMode): Boolean {
        return VoxLiveTranslationDelayPolicy.shouldRebuffer(bufferAheadMs, mode)
    }

    @JvmStatic
    fun canResume(bufferAheadMs: Long, mode: VoxLiveDelayMode): Boolean {
        return VoxLiveTranslationDelayPolicy.canResumeAfterRebuffer(bufferAheadMs, mode)
    }
}

/**
 * Высокоуровневая абстракция сессии перевода прямого эфира.
 * Предоставляет честное состояние поддержки (VOD_ONLY / Unsupported Live) и управление синхронизацией.
 */
class VoxLiveTranslationSession(
    val sessionId: String,
    val videoId: String,
    val isLive: Boolean,
    val mode: VoxLiveDelayMode = VoxLiveDelayMode.AUTO,
    private val controller: VoxLiveTranslationController = VoxLiveTranslationController()
) {
    fun checkEligibility(
        isDvrAvailable: Boolean = false,
        isSeekable: Boolean = false,
        audioTrackAvailable: Boolean = true,
        sourceLanguage: String? = "en"
    ): VoxLiveTranslationEligibilityResult {
        return controller.checkEligibility(
            isLive = isLive,
            isDvrAvailable = isDvrAvailable,
            isSeekable = isSeekable,
            audioTrackAvailable = audioTrackAvailable,
            sourceLanguage = sourceLanguage
        )
    }

    fun getState(): VoxLiveTranslationState {
        val eligibility = checkEligibility()
        return VoxLiveTranslationState(
            sessionId = sessionId,
            videoId = videoId,
            isLive = isLive,
            bufferState = controller.getBufferState(),
            eligibility = eligibility,
            isActive = controller.isLiveTranslationActive()
        )
    }
}
