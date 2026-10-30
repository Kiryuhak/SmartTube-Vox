package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.os.Handler
import android.os.Looper
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.util.UUID

/**
 * Controller for bounded, non-looping player recovery during stalls, frozen buffers,
 * HTTP 403 errors, DNS failures, and high-bitrate 4K rebuffering.
 *
 * Guarantees:
 * 1. Bounded multi-stage recovery:
 *    - Stage 1: Source refresh (up to MAX_SOURCE_RECOVERIES = 2).
 *    - Stage 2: Network engine switch (up to MAX_ENGINE_SWITCHES = 1, no ping-pong).
 *    - Stage 3: Quality fallback for 4K / high-bitrate (up to MAX_QUALITY_FALLBACKS = 1).
 *    - Stage 4: Terminal failure (RECOVERY_EXHAUSTED) with manual retry & safe back options.
 * 2. Real sustained success verification:
 *    - Playback must advance position by >= 2,000ms AND remain playing for >= 4,000ms.
 *    - Immediate rebuffering during verification fails verification and escalates to the next stage.
 *    - Zero premature PLAYER_RECOVERY_SUCCEEDED events.
 * 3. Attempt limiter preservation:
 *    - Counters survive internal video reloads and engine restarts for the same video.
 *    - Reset ONLY upon confirmed sustained playback or explicit opening of a new video.
 * 4. Concurrency safety:
 *    - Only 1 active recovery at a time. Watchdog and errors cannot spawn parallel recoveries.
 * 5. Clean terminal UX:
 *    - Hides buffering spinner on failure, shows localized exhausted message with Retry and Back.
 *    - Back navigation is always responsive and safe.
 */
class VoxPlayerRecoveryController internal constructor(
    private val actionHandler: PlayerRecoveryActionHandler,
    private val scheduler: Scheduler
) {

    interface Scheduler {
        fun postDelayed(runnable: Runnable, delayMs: Long)
        fun removeCallbacks(runnable: Runnable)
        fun removeCallbacksAndMessages(token: Any?)
    }

    private class HandlerScheduler(private val handler: Handler) : Scheduler {
        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            handler.postDelayed(runnable, delayMs)
        }

        override fun removeCallbacks(runnable: Runnable) {
            handler.removeCallbacks(runnable)
        }

        override fun removeCallbacksAndMessages(token: Any?) {
            handler.removeCallbacksAndMessages(token)
        }
    }

    @JvmOverloads
    constructor(
        actionHandler: PlayerRecoveryActionHandler,
        looper: Looper = Looper.getMainLooper()
    ) : this(actionHandler, HandlerScheduler(Handler(looper)))

    interface PlayerRecoveryActionHandler {
        fun onSourceRefreshRequested(targetPositionMs: Long, playWhenReady: Boolean, gen: Long)
        fun onEngineSwitchRequested(nextEngine: Int, targetPositionMs: Long, playWhenReady: Boolean, gen: Long)
        fun onQualityFallbackRequested(targetPositionMs: Long, playWhenReady: Boolean, gen: Long): Boolean = false
        fun onRecoverySucceeded(gen: Long)
        fun onRecoveryFailed(gen: Long, onRetry: Runnable)
        fun onRecoveryFailed(gen: Long, failureReason: String, onRetry: Runnable) {
            onRecoveryFailed(gen, onRetry)
        }
        fun showRecoveryOverlay(messageResId: Int)
        fun hideRecoveryOverlay()
        fun hideBufferingSpinner()
    }

    private var currentState: VoxPlayerRecoveryState = VoxPlayerRecoveryState.IDLE
    private var isLiveSession: Boolean = false
    private var currentSessionId: Long = 0L
    private var recoveryGeneration: Long = 0L
    private var sourceRecoveryCount: Int = 0
    private var engineSwitchCount: Int = 0
    private var qualityFallbackCount: Int = 0
    private var currentEngine: Int = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT
    private var currentVideoId: String? = null
    private var isInternalRecoveryReload: Boolean = false

    // Diagnostic context tracking
    private var recoverySequenceId: String? = null
    private var recoveryStartTimeMs: Long = 0L
    private var activeRecoveryReason: String? = null
    private var currentCodec: String? = null
    private var currentHeight: Int = 0
    private var currentBitrate: Long = 0L

    // Progress & verification tracking
    private var lastObservedPositionMs: Long = 0L
    private var lastObservedBufferedMs: Long = 0L
    private var lastObservedProgressTimestampMs: Long = 0L
    private var lastObservedRenderedPositionMs: Long = 0L
    private var verificationStartPositionMs: Long = -1L
    private var verificationStartTimeMs: Long = 0L

    private val verificationRunnable = Runnable {
        checkVerificationSuccess()
    }

    fun getState(): VoxPlayerRecoveryState = currentState
    fun getSourceRecoveryCount(): Int = sourceRecoveryCount
    fun getEngineSwitchCount(): Int = engineSwitchCount
    fun getQualityFallbackCount(): Int = qualityFallbackCount
    fun getCurrentGeneration(): Long = recoveryGeneration
    fun getCurrentVideoId(): String? = currentVideoId
    fun getRecoverySequenceId(): String? = recoverySequenceId

    fun isRecovering(): Boolean {
        return currentState == VoxPlayerRecoveryState.RECOVERING_SOURCE ||
                currentState == VoxPlayerRecoveryState.SOURCE_REFRESHED ||
                currentState == VoxPlayerRecoveryState.RECOVERING_NETWORK ||
                currentState == VoxPlayerRecoveryState.RECOVERING_QUALITY ||
                currentState == VoxPlayerRecoveryState.RECOVERY_VERIFYING
    }

    fun isTerminalFailed(): Boolean {
        return currentState == VoxPlayerRecoveryState.FAILED ||
                currentState == VoxPlayerRecoveryState.RECOVERY_EXHAUSTED
    }

    private fun currentTimeMs(): Long {
        return VoxPlayerRecoveryTestHooks.mockCurrentTimeMs ?: android.os.SystemClock.elapsedRealtime()
    }

    fun updateMediaTrackInfo(codec: String?, height: Int, bitrate: Long) {
        currentCodec = codec
        currentHeight = height
        currentBitrate = bitrate
    }

    @Synchronized
    @JvmOverloads
    fun onPlaybackStart(sessionId: Long, isLive: Boolean, engine: Int, videoId: String? = null) {
        val isSameVideoRecovery = videoId != null && videoId == currentVideoId &&
                (isRecovering() || isInternalRecoveryReload)

        if (isSameVideoRecovery) {
            isLiveSession = isLive
            currentEngine = engine
            isInternalRecoveryReload = false
            return
        }

        recoveryGeneration++
        reset()
        currentVideoId = videoId
        isInternalRecoveryReload = false
        currentSessionId = sessionId
        isLiveSession = isLive
        currentEngine = engine
        currentState = VoxPlayerRecoveryState.IDLE
        sourceRecoveryCount = 0
        engineSwitchCount = 0
        qualityFallbackCount = 0
        recoverySequenceId = null
        activeRecoveryReason = null
        verificationStartPositionMs = -1L
        verificationStartTimeMs = 0L
        lastObservedProgressTimestampMs = currentTimeMs()
        lastObservedRenderedPositionMs = 0L
    }

    @Synchronized
    fun onPaused() {
        lastObservedProgressTimestampMs = currentTimeMs()
    }

    @Synchronized
    fun onSeekRequested(targetPositionMs: Long) {
        lastObservedPositionMs = targetPositionMs
        lastObservedRenderedPositionMs = targetPositionMs
        lastObservedProgressTimestampMs = currentTimeMs()
        scheduler.removeCallbacksAndMessages(null)
        if (currentState == VoxPlayerRecoveryState.BUFFERING ||
            currentState == VoxPlayerRecoveryState.RECOVERY_VERIFYING) {
            currentState = VoxPlayerRecoveryState.IDLE
            verificationStartPositionMs = -1L
            verificationStartTimeMs = 0L
        }
    }

    @Synchronized
    fun onSeekProcessed(positionMs: Long, bufferedPositionMs: Long) {
        lastObservedPositionMs = positionMs
        lastObservedRenderedPositionMs = positionMs
        lastObservedBufferedMs = bufferedPositionMs
        lastObservedProgressTimestampMs = currentTimeMs()
        scheduler.removeCallbacksAndMessages(null)
        if (currentState == VoxPlayerRecoveryState.BUFFERING ||
            currentState == VoxPlayerRecoveryState.RECOVERY_VERIFYING) {
            currentState = VoxPlayerRecoveryState.IDLE
            verificationStartPositionMs = -1L
            verificationStartTimeMs = 0L
        }
    }

    @Synchronized
    fun onPlaybackCheck(positionMs: Long, bufferedPositionMs: Long, isPlaying: Boolean, playWhenReady: Boolean) {
        if (isLiveSession || isTerminalFailed()) {
            return
        }

        val now = currentTimeMs()

        if (!playWhenReady) {
            lastObservedPositionMs = positionMs
            lastObservedBufferedMs = bufferedPositionMs
            lastObservedProgressTimestampMs = now
            return
        }

        if (lastObservedProgressTimestampMs == 0L) {
            lastObservedProgressTimestampMs = now
            lastObservedPositionMs = positionMs
            lastObservedBufferedMs = bufferedPositionMs
            return
        }

        val isPositionAdvancing = positionMs > lastObservedPositionMs
        val isBufferAdvancing = !VoxPlayerRecoveryTestHooks.bufferFreeze &&
                (VoxPlayerRecoveryTestHooks.slowNetworkWithProgress ||
                        bufferedPositionMs >= lastObservedBufferedMs + VoxPlayerRecoveryPolicy.MEANINGFUL_BUFFER_DELTA_MS)

        if (isPositionAdvancing || isBufferAdvancing) {
            lastObservedProgressTimestampMs = now
        }

        lastObservedPositionMs = positionMs
        if (isBufferAdvancing) {
            lastObservedBufferedMs = bufferedPositionMs
        }

        val elapsed = now - lastObservedProgressTimestampMs
        val bufferRemainingMs = bufferedPositionMs - positionMs

        val isStalled = (elapsed >= VoxPlayerRecoveryPolicy.STALL_WATCHDOG_TIMEOUT_MS) &&
                (!isPositionAdvancing && !isBufferAdvancing || bufferRemainingMs < 1000L ||
                        VoxPlayerRecoveryTestHooks.bufferFreeze || VoxPlayerRecoveryTestHooks.forceFrozenProgress)

        if (isStalled && !isTerminalFailed() && !isRecovering()) {
            lastObservedPositionMs = positionMs
            lastObservedBufferedMs = bufferedPositionMs
            lastObservedProgressTimestampMs = now

            escalateRecovery(positionMs, playWhenReady, "BUFFER_FREEZE_NO_PROGRESS")
        }
    }

    @Synchronized
    fun onBufferingStarted(positionMs: Long, bufferedPositionMs: Long, playWhenReady: Boolean) {
        if (isLiveSession || isTerminalFailed()) {
            return
        }

        lastObservedPositionMs = positionMs
        lastObservedBufferedMs = bufferedPositionMs

        if (currentState == VoxPlayerRecoveryState.RECOVERY_VERIFYING) {
            // Immediate rebuffer during verification window! Escalates to next stage.
            scheduler.removeCallbacks(verificationRunnable)
            onVerificationFailed("REBUFFER_DURING_VERIFICATION")
            return
        }

        if (currentState != VoxPlayerRecoveryState.BUFFERING) {
            currentState = VoxPlayerRecoveryState.BUFFERING
            scheduler.removeCallbacksAndMessages(null)
            scheduleStallWatchdog(recoveryGeneration, currentSessionId, playWhenReady)
        }
    }

    private fun scheduleStallWatchdog(gen: Long, sess: Long, playWhenReady: Boolean) {
        scheduler.postDelayed({
            synchronized(this@VoxPlayerRecoveryController) {
                if (gen != recoveryGeneration || sess != currentSessionId) {
                    return@synchronized
                }
                onStallWatchdogTriggered(gen, playWhenReady)
            }
        }, VoxPlayerRecoveryPolicy.STALL_WATCHDOG_TIMEOUT_MS)
    }

    @Synchronized
    fun onBufferProgress(bufferedPositionMs: Long, playWhenReady: Boolean) {
        if (isLiveSession || isTerminalFailed()) return

        if (VoxPlayerRecoveryTestHooks.bufferFreeze) {
            return
        }

        val progress = VoxPlayerRecoveryTestHooks.slowNetworkWithProgress ||
                VoxPlayerRecoveryPolicy.isMeaningfulProgress(lastObservedBufferedMs, bufferedPositionMs)

        if (progress) {
            lastObservedBufferedMs = bufferedPositionMs
            if (currentState == VoxPlayerRecoveryState.BUFFERING) {
                // Buffer is advancing steadily. Extend watchdog timeout.
                scheduler.removeCallbacksAndMessages(null)
                scheduleStallWatchdog(recoveryGeneration, currentSessionId, playWhenReady)
            }
        }
    }

    @Synchronized
    fun onPlaybackProgress(positionMs: Long, bufferedPositionMs: Long) {
        if (isLiveSession || isTerminalFailed()) return

        lastObservedPositionMs = positionMs
        lastObservedBufferedMs = bufferedPositionMs

        if (currentState == VoxPlayerRecoveryState.RECOVERING_SOURCE ||
            currentState == VoxPlayerRecoveryState.SOURCE_REFRESHED ||
            currentState == VoxPlayerRecoveryState.RECOVERING_NETWORK ||
            currentState == VoxPlayerRecoveryState.RECOVERING_QUALITY ||
            (currentState == VoxPlayerRecoveryState.BUFFERING && isInternalRecoveryReload)) {

            // Playback has just resumed after recovery action.
            // Transition to RECOVERY_VERIFYING instead of premature success.
            currentState = VoxPlayerRecoveryState.RECOVERY_VERIFYING
            verificationStartPositionMs = positionMs
            verificationStartTimeMs = currentTimeMs()
            scheduler.removeCallbacks(verificationRunnable)
            scheduler.postDelayed(verificationRunnable, VoxPlayerRecoveryPolicy.VERIFICATION_WINDOW_MS)

            val ctx = buildTelemetryContext("RECOVERY_VERIFYING")
            ctx["verifyingAtPosMs"] = positionMs.toString()
            VoxSafeLogger.info(
                VoxLogCategory.PLAYER,
                VoxLogCode.PLAYER_RECOVERY_VERIFYING,
                "Воспроизведение возобновлено, начата проверка устойчивости",
                ctx
            )
            return
        }

        if (currentState == VoxPlayerRecoveryState.BUFFERING) {
            currentState = VoxPlayerRecoveryState.IDLE
            scheduler.removeCallbacksAndMessages(null)
            return
        }

        if (currentState == VoxPlayerRecoveryState.RECOVERY_VERIFYING) {
            val progressDelta = positionMs - verificationStartPositionMs
            val elapsed = currentTimeMs() - verificationStartTimeMs
            if (elapsed >= VoxPlayerRecoveryPolicy.VERIFICATION_WINDOW_MS &&
                progressDelta >= VoxPlayerRecoveryPolicy.VERIFICATION_MIN_PROGRESS_MS) {
                confirmRecoverySuccess(positionMs)
            }
        }
    }

    @Synchronized
    private fun checkVerificationSuccess() {
        if (currentState != VoxPlayerRecoveryState.RECOVERY_VERIFYING) return

        val progressDelta = lastObservedPositionMs - verificationStartPositionMs
        val elapsed = currentTimeMs() - verificationStartTimeMs

        if (elapsed >= VoxPlayerRecoveryPolicy.VERIFICATION_WINDOW_MS &&
            progressDelta >= VoxPlayerRecoveryPolicy.VERIFICATION_MIN_PROGRESS_MS) {
            confirmRecoverySuccess(lastObservedPositionMs)
        } else if (elapsed >= VoxPlayerRecoveryPolicy.VERIFICATION_WINDOW_MS + 4_000L) {
            // Max verification window exceeded without sufficient progress
            onVerificationFailed("VERIFICATION_TIMEOUT_NO_PROGRESS")
        } else {
            // Still within verification period, check again in 1000ms
            scheduler.postDelayed(verificationRunnable, 1_000L)
        }
    }

    @Synchronized
    private fun onVerificationFailed(reason: String) {
        val safePosMs = Math.max(0L, lastObservedPositionMs - VoxPlayerRecoveryPolicy.POSITION_RESTORE_SAFE_OFFSET_MS)
        escalateRecovery(safePosMs, true, reason)
    }

    @Synchronized
    private fun confirmRecoverySuccess(positionMs: Long) {
        if (currentState != VoxPlayerRecoveryState.RECOVERY_VERIFYING && !isRecovering()) {
            return
        }

        currentState = VoxPlayerRecoveryState.RECOVERED
        scheduler.removeCallbacksAndMessages(null)
        actionHandler.hideRecoveryOverlay()
        actionHandler.onRecoverySucceeded(recoveryGeneration)

        val ctx = buildTelemetryContext("RECOVERY_SUCCEEDED", activeRecoveryReason, positionMs, "VERIFIED_SUSTAINED")
        ctx["restoredPositionMs"] = positionMs.toString()
        VoxSafeLogger.info(
            VoxLogCategory.PLAYER,
            VoxLogCode.PLAYER_RECOVERY_SUCCEEDED,
            "Восстановление воспроизведения подтверждено устойчивым воспроизведением",
            ctx
        )

        // Reset limiter after verified sustained playback
        sourceRecoveryCount = 0
        engineSwitchCount = 0
        qualityFallbackCount = 0
        recoverySequenceId = null
        activeRecoveryReason = null
        verificationStartPositionMs = -1L
        verificationStartTimeMs = 0L
    }

    @Synchronized
    fun handlePlayerError(error: Throwable?, currentPositionMs: Long, playWhenReady: Boolean): Boolean {
        if (isTerminalFailed()) return false

        val isDeadOrStale = VoxPlayerRecoveryTestHooks.http403 || VoxPlayerRecoveryPolicy.isStaleOrDeadUrl(error)
        val isDns = VoxPlayerRecoveryTestHooks.dnsFailure || VoxPlayerRecoveryPolicy.isDnsFailure(error)

        if (isDeadOrStale) {
            val failureType = VoxPlayerRecoveryPolicy.classifyNetworkFailureType(error)
            escalateRecovery(currentPositionMs, playWhenReady, if (failureType != "UNKNOWN") failureType else "HTTP_403_FORBIDDEN")
            return true
        }

        // Live sessions delegate live-specific stall/DVR handling to VoxLiveStallRecoveryController,
        // but HTTP 403 / token expiry is handled above via bounded source refresh.
        if (isLiveSession) return false

        if (isDns) {
            if (engineSwitchCount < VoxPlayerRecoveryPolicy.MAX_ENGINE_SWITCHES) {
                return triggerEngineFailover(currentPositionMs, playWhenReady, "DNS_FAILURE")
            } else {
                triggerTerminalFailure("DNS_FAILURE_EXHAUSTED")
                return true
            }
        }

        if (VoxPlayerRecoveryPolicy.isConnectionReset(error)) {
            escalateRecovery(currentPositionMs, playWhenReady, "CONNECTION_RESET")
            return true
        }

        if (VoxPlayerRecoveryPolicy.isConnectTimeout(error) || VoxPlayerRecoveryPolicy.isReadTimeout(error)) {
            escalateRecovery(currentPositionMs, playWhenReady, "SOCKET_TIMEOUT")
            return true
        }

        if (VoxPlayerRecoveryPolicy.isNetworkTransportError(error)) {
            escalateRecovery(currentPositionMs, playWhenReady, "NETWORK_TRANSPORT_ERROR")
            return true
        }

        return false
    }

    private fun onStallWatchdogTriggered(gen: Long, playWhenReady: Boolean) {
        if (currentState != VoxPlayerRecoveryState.BUFFERING &&
            currentState != VoxPlayerRecoveryState.RECOVERING_SOURCE &&
            currentState != VoxPlayerRecoveryState.RECOVERING_NETWORK &&
            currentState != VoxPlayerRecoveryState.RECOVERING_QUALITY &&
            currentState != VoxPlayerRecoveryState.SOURCE_REFRESHED) return

        if (VoxPlayerRecoveryTestHooks.slowNetworkWithProgress) {
            scheduleStallWatchdog(gen, currentSessionId, playWhenReady)
            return
        }

        if (VoxPendingSeekCoordinator.instance().hasActivePendingSeek()) {
            VoxSafeLogger.i(
                VoxLogCategory.PLAYER,
                "BUFFER_STALL_SEEK_WAIT",
                "Stall watchdog deferred: active seek in progress"
            )
            scheduleStallWatchdog(gen, currentSessionId, playWhenReady)
            return
        }

        escalateRecovery(lastObservedPositionMs, playWhenReady, "BUFFER_STALL_WATCHDOG")
    }

    @Synchronized
    private fun escalateRecovery(currentPosMs: Long, playWhenReady: Boolean, reason: String) {
        if (sourceRecoveryCount < VoxPlayerRecoveryPolicy.MAX_SOURCE_RECOVERIES && !VoxPlayerRecoveryTestHooks.sourceRefreshFailure) {
            triggerSourceRefresh(currentPosMs, playWhenReady, reason)
        } else if (engineSwitchCount < VoxPlayerRecoveryPolicy.MAX_ENGINE_SWITCHES) {
            triggerEngineFailover(currentPosMs, playWhenReady, reason)
        } else if (qualityFallbackCount < VoxPlayerRecoveryPolicy.MAX_QUALITY_FALLBACKS && canTriggerQualityFallback(reason)) {
            triggerQualityFallback(currentPosMs, playWhenReady, reason)
        } else {
            val terminalReason = if (reason.contains("DNS") || VoxPlayerRecoveryPolicy.isDnsFailureReason(reason)) {
                "DNS_FAILURE_EXHAUSTED"
            } else {
                "MAX_ATTEMPTS_EXHAUSTED"
            }
            triggerTerminalFailure(terminalReason)
        }
    }

    private fun canTriggerQualityFallback(reason: String = activeRecoveryReason ?: ""): Boolean {
        // Quality fallback is strictly for high bitrate buffer exhaustion, NEVER for pure DNS failure!
        if (reason.contains("DNS") || VoxPlayerRecoveryPolicy.isDnsFailureReason(reason)) {
            return false
        }
        return currentHeight >= 1440 || currentBitrate >= 12_000_000L || VoxPlayerRecoveryTestHooks.forceQualityFallbackEligible
    }

    private fun ensureRecoverySequenceId(reason: String) {
        if (recoverySequenceId == null) {
            recoverySequenceId = "REC-" + UUID.randomUUID().toString().substring(0, 8)
            recoveryStartTimeMs = currentTimeMs()
            activeRecoveryReason = reason
        }
    }

    private fun triggerSourceRefresh(currentPosMs: Long, playWhenReady: Boolean, reason: String): Boolean {
        if (sourceRecoveryCount >= VoxPlayerRecoveryPolicy.MAX_SOURCE_RECOVERIES || VoxPlayerRecoveryTestHooks.sourceRefreshFailure) {
            if (engineSwitchCount < VoxPlayerRecoveryPolicy.MAX_ENGINE_SWITCHES) {
                return triggerEngineFailover(currentPosMs, playWhenReady, reason + "_FALLBACK_ENGINE")
            }
            if (qualityFallbackCount < VoxPlayerRecoveryPolicy.MAX_QUALITY_FALLBACKS && canTriggerQualityFallback()) {
                return triggerQualityFallback(currentPosMs, playWhenReady, reason + "_FALLBACK_QUALITY")
            }
            triggerTerminalFailure(reason + "_EXHAUSTED")
            return true
        }

        ensureRecoverySequenceId(reason)
        sourceRecoveryCount++
        recoveryGeneration++
        isInternalRecoveryReload = true
        val gen = recoveryGeneration
        currentState = VoxPlayerRecoveryState.RECOVERING_SOURCE

        val safePosMs = Math.max(0L, currentPosMs - VoxPlayerRecoveryPolicy.POSITION_RESTORE_SAFE_OFFSET_MS)
        actionHandler.showRecoveryOverlay(R.string.vox_fixing_playback)

        val ctx = buildTelemetryContext("RECOVERY_STARTED", reason, safePosMs)
        VoxSafeLogger.warn(
            VoxLogCategory.PLAYER,
            VoxLogCode.PLAYER_RECOVERY_STARTED,
            "Запущено автоматическое восстановление потока (обновление источника)",
            ctx
        )

        actionHandler.onSourceRefreshRequested(safePosMs, playWhenReady, gen)
        return true
    }

    private fun triggerEngineFailover(currentPosMs: Long, playWhenReady: Boolean, reason: String): Boolean {
        if (engineSwitchCount >= VoxPlayerRecoveryPolicy.MAX_ENGINE_SWITCHES) {
            if (qualityFallbackCount < VoxPlayerRecoveryPolicy.MAX_QUALITY_FALLBACKS && canTriggerQualityFallback(reason)) {
                return triggerQualityFallback(currentPosMs, playWhenReady, reason + "_FALLBACK_QUALITY")
            }
            val terminalReason = if (reason.contains("DNS") || VoxPlayerRecoveryPolicy.isDnsFailureReason(reason)) {
                "DNS_FAILURE_EXHAUSTED"
            } else {
                "ENGINE_SWITCH_LIMIT_EXHAUSTED"
            }
            triggerTerminalFailure(terminalReason)
            return true
        }

        ensureRecoverySequenceId(reason)
        engineSwitchCount++
        recoveryGeneration++
        isInternalRecoveryReload = true
        val gen = recoveryGeneration
        currentState = VoxPlayerRecoveryState.RECOVERING_NETWORK

        val nextEngine = if (currentEngine == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP) {
            PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT
        } else {
            PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP
        }
        currentEngine = nextEngine

        val safePosMs = Math.max(0L, currentPosMs - VoxPlayerRecoveryPolicy.POSITION_RESTORE_SAFE_OFFSET_MS)
        actionHandler.showRecoveryOverlay(R.string.vox_fixing_playback)

        val ctx = buildTelemetryContext("RECOVERY_NETWORK", reason, safePosMs)
        ctx["nextEngine"] = nextEngine.toString()
        VoxSafeLogger.warn(
            VoxLogCategory.NETWORK,
            VoxLogCode.DNS_RECOVERY_STARTED,
            "Запуск сетевого восстановления движка воспроизведения",
            ctx
        )

        actionHandler.onEngineSwitchRequested(nextEngine, safePosMs, playWhenReady, gen)
        return true
    }

    private fun triggerQualityFallback(currentPosMs: Long, playWhenReady: Boolean, reason: String): Boolean {
        if (qualityFallbackCount >= VoxPlayerRecoveryPolicy.MAX_QUALITY_FALLBACKS) {
            triggerTerminalFailure("QUALITY_FALLBACK_LIMIT_EXHAUSTED")
            return true
        }

        ensureRecoverySequenceId(reason)
        qualityFallbackCount++
        recoveryGeneration++
        isInternalRecoveryReload = true
        val gen = recoveryGeneration
        currentState = VoxPlayerRecoveryState.RECOVERING_QUALITY

        val safePosMs = Math.max(0L, currentPosMs - VoxPlayerRecoveryPolicy.POSITION_RESTORE_SAFE_OFFSET_MS)
        actionHandler.showRecoveryOverlay(R.string.vox_fixing_playback)

        val ctx = buildTelemetryContext("RECOVERY_QUALITY", reason, safePosMs)
        VoxSafeLogger.warn(
            VoxLogCategory.PLAYER,
            VoxLogCode.PLAYER_RECOVERY_STARTED,
            "Запущено снижение качества потока для восстановления воспроизведения",
            ctx
        )

        val handled = actionHandler.onQualityFallbackRequested(safePosMs, playWhenReady, gen)
        if (!handled) {
            triggerTerminalFailure("QUALITY_FALLBACK_NOT_AVAILABLE")
            return false
        }
        return true
    }

    private fun triggerTerminalFailure(reason: String) {
        currentState = VoxPlayerRecoveryState.FAILED
        scheduler.removeCallbacksAndMessages(null)
        actionHandler.hideBufferingSpinner()
        actionHandler.hideRecoveryOverlay()

        val ctx = buildTelemetryContext("RECOVERY_EXHAUSTED", reason, lastObservedPositionMs)
        ctx["finalReason"] = reason
        VoxSafeLogger.error(
            VoxLogCategory.PLAYER,
            VoxLogCode.PLAYER_RECOVERY_FAILED,
            "Не удалось восстановить воспроизведение видео",
            ctx,
            null
        )

        val retryRunnable = Runnable {
            retryManual()
        }
        actionHandler.onRecoveryFailed(recoveryGeneration, reason, retryRunnable)
    }

    private fun buildTelemetryContext(
        stage: String,
        reason: String? = activeRecoveryReason,
        targetPosMs: Long = lastObservedPositionMs,
        verificationResult: String? = null
    ): HashMap<String, String> {
        val ctx = HashMap<String, String>()
        val seqId = recoverySequenceId ?: "REC-$currentSessionId"
        ctx["recoverySequenceId"] = seqId
        ctx["playbackSessionId"] = currentSessionId.toString()
        val totalAttempts = sourceRecoveryCount + engineSwitchCount + qualityFallbackCount
        ctx["recoveryAttempt"] = totalAttempts.toString()
        ctx["sourceRefreshCount"] = sourceRecoveryCount.toString()
        ctx["engineSwitchCount"] = engineSwitchCount.toString()
        ctx["qualityFallbackCount"] = qualityFallbackCount.toString()
        ctx["recoveryReason"] = reason ?: "UNKNOWN"
        ctx["recoveryStage"] = stage
        val elapsed = if (recoveryStartTimeMs > 0L) currentTimeMs() - recoveryStartTimeMs else 0L
        ctx["recoveryElapsedMs"] = elapsed.toString()
        val bufferAhead = Math.max(0L, lastObservedBufferedMs - lastObservedPositionMs)
        ctx["bufferAheadMs"] = bufferAhead.toString()
        ctx["selectedCodec"] = currentCodec ?: "UNKNOWN"
        ctx["selectedHeight"] = if (currentHeight > 0) currentHeight.toString() else "UNKNOWN"
        ctx["selectedBitrate"] = if (currentBitrate > 0L) currentBitrate.toString() else "UNKNOWN"
        val engineStr = when (currentEngine) {
            PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP -> "OKHTTP"
            PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET -> "CRONET"
            else -> "DEFAULT"
        }
        ctx["networkEngine"] = engineStr
        val isDns = reason?.contains("DNS") == true || VoxPlayerRecoveryPolicy.isDnsFailureReason(reason)
        ctx["dnsResolutionFailed"] = isDns.toString()
        val isTimeout = reason?.contains("TIMEOUT") == true
        ctx["connectionTimeout"] = isTimeout.toString()
        if (isDns) {
            ctx["networkFailureType"] = "DNS_RESOLUTION_FAILED"
        } else if (isTimeout) {
            ctx["networkFailureType"] = "TIMEOUT"
        } else if (reason?.contains("RESET") == true) {
            ctx["networkFailureType"] = "CONNECTION_RESET"
        } else if (reason?.contains("403") == true || reason?.contains("HTTP") == true) {
            ctx["networkFailureType"] = "HTTP_ERROR"
        } else {
            ctx["networkFailureType"] = "BUFFER_STALL"
        }
        if (verificationResult != null) {
            ctx["verificationResult"] = verificationResult
            ctx["recoveryVerificationResult"] = verificationResult
        }
        if (stage == "RECOVERY_EXHAUSTED") {
            ctx["finalRecoveryReason"] = reason ?: "UNKNOWN"
        }
        ctx["targetPosMs"] = targetPosMs.toString()
        return ctx
    }

    @Synchronized
    fun retryManual() {
        if (isRecovering()) {
            return
        }
        sourceRecoveryCount = 0
        engineSwitchCount = 0
        qualityFallbackCount = 0
        recoverySequenceId = null
        activeRecoveryReason = null
        verificationStartPositionMs = -1L
        verificationStartTimeMs = 0L
        recoveryGeneration++
        currentState = VoxPlayerRecoveryState.IDLE
        val safePosMs = Math.max(0L, lastObservedPositionMs - VoxPlayerRecoveryPolicy.POSITION_RESTORE_SAFE_OFFSET_MS)
        triggerSourceRefresh(safePosMs, true, "USER_MANUAL_RETRY")
    }

    @Synchronized
    fun reset() {
        recoveryGeneration++
        scheduler.removeCallbacksAndMessages(null)
        isInternalRecoveryReload = false
        lastObservedProgressTimestampMs = 0L
        verificationStartPositionMs = -1L
        verificationStartTimeMs = 0L
        recoverySequenceId = null
        activeRecoveryReason = null
        currentState = VoxPlayerRecoveryState.IDLE
    }
}
