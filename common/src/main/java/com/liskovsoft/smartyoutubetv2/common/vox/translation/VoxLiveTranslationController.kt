package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger

/**
 * Координатор и контроллер перевода прямых трансляций.
 */
class VoxLiveTranslationController(
    private val syncController: VoxLiveTranslationSyncController = VoxLiveTranslationSyncController(),
    private val backendCapability: VoxLiveBackendCapability = VoxLiveBackendCapability.VOD_ONLY
) {

    interface Callback {
        fun onPlaybackHoldRequired()
        fun onPlaybackResumeAllowed()
        fun onBufferStateUpdated(state: VoxLiveTranslationBufferState)
        fun onLiveTranslationError(reasonRu: String, fatal: Boolean)
        fun onFallbackToOriginalRequested()
    }

    private var callback: Callback? = null
    private var isTranslationActive: Boolean = false

    fun setCallback(cb: Callback?) {
        this.callback = cb
    }

    fun isLiveTranslationActive(): Boolean = isTranslationActive

    fun checkEligibility(
        isLive: Boolean,
        isDvrAvailable: Boolean,
        isSeekable: Boolean,
        audioTrackAvailable: Boolean,
        sourceLanguage: String?
    ): VoxLiveTranslationEligibilityResult {
        return VoxLiveTranslationEligibility.checkEligibility(
            isLive = isLive,
            isDvrAvailable = isDvrAvailable,
            isSeekable = isSeekable,
            audioTrackAvailable = audioTrackAvailable,
            sourceLanguage = sourceLanguage,
            backendCapability = backendCapability
        )
    }

    fun startLiveTranslation(
        isLive: Boolean,
        isDvrAvailable: Boolean,
        isSeekable: Boolean,
        audioTrackAvailable: Boolean,
        sourceLanguage: String?,
        currentPosMs: Long,
        liveEdgeMs: Long
    ): Boolean {
        val eligibility = checkEligibility(
            isLive = isLive,
            isDvrAvailable = isDvrAvailable,
            isSeekable = isSeekable,
            audioTrackAvailable = audioTrackAvailable,
            sourceLanguage = sourceLanguage
        )

        if (eligibility.status == VoxLiveEligibilityStatus.UNSUPPORTED) {
            callback?.onLiveTranslationError(eligibility.reasonRu, true)
            return false
        }

        isTranslationActive = true
        syncController.startPreparation(liveEdgeMs, currentPosMs)
        callback?.onPlaybackHoldRequired()
        callback?.onBufferStateUpdated(syncController.getBufferState())
        return true
    }

    fun onLiveSegmentTranslated(chunk: VoxLiveTranslationChunk) {
        if (!isTranslationActive) return

        val previousPhase = syncController.getBufferState().phase
        syncController.addTranslatedChunk(chunk)
        val currentState = syncController.getBufferState()

        if (previousPhase != VoxLivePlaybackPhase.PLAYING_TRANSLATED &&
            currentState.phase == VoxLivePlaybackPhase.PLAYING_TRANSLATED) {
            callback?.onPlaybackResumeAllowed()
        }

        callback?.onBufferStateUpdated(currentState)
    }

    fun onPositionTick(currentPositionMs: Long) {
        if (!isTranslationActive) return

        val previousPhase = syncController.getBufferState().phase
        val currentPhase = syncController.onPlaybackPositionUpdate(currentPositionMs)
        val currentState = syncController.getBufferState()

        if (previousPhase == VoxLivePlaybackPhase.PLAYING_TRANSLATED &&
            currentPhase == VoxLivePlaybackPhase.REBUFFERING) {
            callback?.onPlaybackHoldRequired()
        } else if (previousPhase == VoxLivePlaybackPhase.REBUFFERING &&
            currentPhase == VoxLivePlaybackPhase.PLAYING_TRANSLATED) {
            callback?.onPlaybackResumeAllowed()
        }

        callback?.onBufferStateUpdated(currentState)
    }

    fun handleSeek(newPositionMs: Long) {
        if (!isTranslationActive) return

        syncController.handleSeek(newPositionMs)
        val state = syncController.getBufferState()
        if (state.phase == VoxLivePlaybackPhase.HOLDING_INITIAL_BUFFER) {
            callback?.onPlaybackHoldRequired()
        }
        callback?.onBufferStateUpdated(state)
    }

    fun fallbackToOriginal() {
        VoxSafeLogger.w(
            VoxLogCategory.TRANSLATION,
            VoxLogCode.LIVE_TRANSLATION_FALLBACK_ORIGINAL,
            "Откат к оригинальной аудиодорожке трансляции"
        )
        isTranslationActive = false
        syncController.setPhase(VoxLivePlaybackPhase.FALLBACK_ORIGINAL)
        callback?.onPlaybackResumeAllowed()
        callback?.onFallbackToOriginalRequested()
        callback?.onBufferStateUpdated(syncController.getBufferState())
    }

    fun stop() {
        if (isTranslationActive) {
            VoxSafeLogger.i(
                VoxLogCategory.TRANSLATION,
                VoxLogCode.LIVE_TRANSLATION_STOPPED,
                "Остановка live-перевода"
            )
        }
        isTranslationActive = false
        syncController.reset()
        callback?.onBufferStateUpdated(syncController.getBufferState())
    }

    fun getBufferState(): VoxLiveTranslationBufferState {
        return syncController.getBufferState()
    }
}
