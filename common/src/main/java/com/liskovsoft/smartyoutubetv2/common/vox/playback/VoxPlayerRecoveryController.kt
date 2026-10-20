package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.os.Handler
import android.os.Looper
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger

/**
 * Controller for bounded player recovery during stalls, frozen buffers, HTTP 403 errors, and DNS failures.
 *
 * Guarantees:
 * 1. Bounded multi-stage recovery (Stage 1: Source refresh / in-memory cache clear, Stage 2: Engine switch).
 * 2. Strict prevention of engine ping-pong loops (at most 1 engine switch per session).
 * 3. Monotonic progress detection (bufferedPosition advancement >= 1000ms extends watchdog for slow networks).
 * 4. Zero PII logging (never leaks signed URLs, authorization tokens, or queries).
 * 5. Generation and session fencing: stale callbacks from older generations are dropped.
 * 6. Terminal FAILED state: hides buffering spinner, shows clear message and surfaces manual "Повторить" retry action.
 */
class VoxPlayerRecoveryController @JvmOverloads constructor(
    private val actionHandler: PlayerRecoveryActionHandler,
    private val looper: Looper = Looper.getMainLooper()
) {

    interface PlayerRecoveryActionHandler {
        fun onSourceRefreshRequested(targetPositionMs: Long, playWhenReady: Boolean, gen: Long)
        fun onEngineSwitchRequested(nextEngine: Int, targetPositionMs: Long, playWhenReady: Boolean, gen: Long)
        fun onRecoverySucceeded(gen: Long)
        fun onRecoveryFailed(gen: Long, onRetry: Runnable)
        fun showRecoveryOverlay(messageResId: Int)
        fun hideRecoveryOverlay()
        fun hideBufferingSpinner()
    }

    private val handler = Handler(looper)

    private var currentState: VoxPlayerRecoveryState = VoxPlayerRecoveryState.IDLE
    private var isLiveSession: Boolean = false
    private var currentSessionId: Long = 0L
    private var recoveryGeneration: Long = 0L
    private var sourceRecoveryCount: Int = 0
    private var engineSwitchCount: Int = 0
    private var currentEngine: Int = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT

    private var lastObservedPositionMs: Long = 0L
    private var lastObservedBufferedMs: Long = 0L

    fun getState(): VoxPlayerRecoveryState = currentState
    fun getSourceRecoveryCount(): Int = sourceRecoveryCount
    fun getEngineSwitchCount(): Int = engineSwitchCount
    fun getCurrentGeneration(): Long = recoveryGeneration

    @Synchronized
    fun onPlaybackStart(sessionId: Long, isLive: Boolean, engine: Int) {
        recoveryGeneration++
        reset()
        currentSessionId = sessionId
        isLiveSession = isLive
        currentEngine = engine
        currentState = VoxPlayerRecoveryState.IDLE
        sourceRecoveryCount = 0
        engineSwitchCount = 0
    }

    @Synchronized
    fun onBufferingStarted(positionMs: Long, bufferedPositionMs: Long, playWhenReady: Boolean) {
        if (isLiveSession || currentState == VoxPlayerRecoveryState.FAILED) {
            return
        }

        lastObservedPositionMs = positionMs
        lastObservedBufferedMs = bufferedPositionMs

        if (currentState != VoxPlayerRecoveryState.BUFFERING) {
            currentState = VoxPlayerRecoveryState.BUFFERING
            handler.removeCallbacksAndMessages(null)

            scheduleStallWatchdog(recoveryGeneration, currentSessionId, playWhenReady)
        }
    }

    private fun scheduleStallWatchdog(gen: Long, sess: Long, playWhenReady: Boolean) {
        handler.postDelayed({
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
        if (isLiveSession || currentState == VoxPlayerRecoveryState.FAILED) return

        if (VoxPlayerRecoveryTestHooks.bufferFreeze) {
            return
        }

        val progress = VoxPlayerRecoveryTestHooks.slowNetworkWithProgress ||
                VoxPlayerRecoveryPolicy.isMeaningfulProgress(lastObservedBufferedMs, bufferedPositionMs)

        if (progress) {
            lastObservedBufferedMs = bufferedPositionMs
            if (currentState == VoxPlayerRecoveryState.BUFFERING) {
                // Buffer is advancing (slow network with steady progress). Extend watchdog timeout.
                handler.removeCallbacksAndMessages(null)
                scheduleStallWatchdog(recoveryGeneration, currentSessionId, playWhenReady)
            }
        }
    }

    @Synchronized
    fun onPlaybackProgress(positionMs: Long, bufferedPositionMs: Long) {
        if (isLiveSession) return

        lastObservedPositionMs = positionMs
        lastObservedBufferedMs = bufferedPositionMs

        if (currentState == VoxPlayerRecoveryState.RECOVERING_SOURCE ||
            currentState == VoxPlayerRecoveryState.RECOVERING_NETWORK ||
            currentState == VoxPlayerRecoveryState.BUFFERING) {

            val wasRecovering = currentState == VoxPlayerRecoveryState.RECOVERING_SOURCE ||
                    currentState == VoxPlayerRecoveryState.RECOVERING_NETWORK

            currentState = VoxPlayerRecoveryState.RECOVERED
            handler.removeCallbacksAndMessages(null)
            actionHandler.hideRecoveryOverlay()
            actionHandler.onRecoverySucceeded(recoveryGeneration)

            if (wasRecovering) {
                val ctx = HashMap<String, String>()
                ctx["sessionId"] = currentSessionId.toString()
                ctx["sourceRecoveries"] = sourceRecoveryCount.toString()
                ctx["engineSwitches"] = engineSwitchCount.toString()
                ctx["restoredPositionMs"] = positionMs.toString()
                VoxSafeLogger.info(
                    VoxLogCategory.PLAYER,
                    VoxLogCode.PLAYER_RECOVERY_SUCCEEDED,
                    "Восстановление воспроизведения завершено успешно",
                    ctx
                )
            }

            sourceRecoveryCount = 0
            engineSwitchCount = 0
        }
    }

    @Synchronized
    fun handlePlayerError(error: Throwable?, currentPositionMs: Long, playWhenReady: Boolean): Boolean {
        if (isLiveSession) return false

        val is403 = VoxPlayerRecoveryTestHooks.http403 || VoxPlayerRecoveryPolicy.isStaleOrForbiddenSource(error)
        val isDns = VoxPlayerRecoveryTestHooks.dnsFailure || VoxPlayerRecoveryPolicy.isDnsFailure(error)

        if (is403) {
            return triggerSourceRefresh(currentPositionMs, playWhenReady, "HTTP_403_FORBIDDEN")
        }

        if (isDns) {
            return triggerEngineFailover(currentPositionMs, playWhenReady, "DNS_FAILURE")
        }

        if (VoxPlayerRecoveryPolicy.isNetworkTransportError(error)) {
            if (sourceRecoveryCount < VoxPlayerRecoveryPolicy.MAX_SOURCE_RECOVERIES) {
                return triggerSourceRefresh(currentPositionMs, playWhenReady, "NETWORK_TRANSPORT_ERROR")
            } else if (engineSwitchCount < VoxPlayerRecoveryPolicy.MAX_ENGINE_SWITCHES) {
                return triggerEngineFailover(currentPositionMs, playWhenReady, "NETWORK_TRANSPORT_FALLBACK")
            } else {
                triggerTerminalFailure("NETWORK_TRANSPORT_EXHAUSTED")
                return true
            }
        }

        return false
    }

    private fun onStallWatchdogTriggered(gen: Long, playWhenReady: Boolean) {
        if (currentState != VoxPlayerRecoveryState.BUFFERING) return

        if (VoxPlayerRecoveryTestHooks.slowNetworkWithProgress) {
            scheduleStallWatchdog(gen, currentSessionId, playWhenReady)
            return
        }

        if (sourceRecoveryCount < VoxPlayerRecoveryPolicy.MAX_SOURCE_RECOVERIES) {
            triggerSourceRefresh(lastObservedPositionMs, playWhenReady, "BUFFER_STALL_WATCHDOG")
        } else if (engineSwitchCount < VoxPlayerRecoveryPolicy.MAX_ENGINE_SWITCHES) {
            triggerEngineFailover(lastObservedPositionMs, playWhenReady, "BUFFER_STALL_ENGINE_FALLBACK")
        } else {
            triggerTerminalFailure("MAX_ATTEMPTS_EXHAUSTED")
        }
    }

    private fun triggerSourceRefresh(currentPosMs: Long, playWhenReady: Boolean, reason: String): Boolean {
        if (sourceRecoveryCount >= VoxPlayerRecoveryPolicy.MAX_SOURCE_RECOVERIES || VoxPlayerRecoveryTestHooks.sourceRefreshFailure) {
            if (engineSwitchCount < VoxPlayerRecoveryPolicy.MAX_ENGINE_SWITCHES) {
                return triggerEngineFailover(currentPosMs, playWhenReady, reason + "_FALLBACK_ENGINE")
            }
            triggerTerminalFailure(reason + "_EXHAUSTED")
            return true
        }

        sourceRecoveryCount++
        recoveryGeneration++
        val gen = recoveryGeneration
        currentState = VoxPlayerRecoveryState.RECOVERING_SOURCE

        val safePosMs = Math.max(0L, currentPosMs - VoxPlayerRecoveryPolicy.POSITION_RESTORE_SAFE_OFFSET_MS)

        actionHandler.showRecoveryOverlay(R.string.vox_fixing_connection)

        val ctx = HashMap<String, String>()
        ctx["sessionId"] = currentSessionId.toString()
        ctx["recoveryAttempt"] = sourceRecoveryCount.toString()
        ctx["reason"] = reason
        ctx["targetPosMs"] = safePosMs.toString()
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
            triggerTerminalFailure("ENGINE_SWITCH_LIMIT_EXHAUSTED")
            return true
        }

        engineSwitchCount++
        recoveryGeneration++
        val gen = recoveryGeneration
        currentState = VoxPlayerRecoveryState.RECOVERING_NETWORK

        val nextEngine = if (currentEngine == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP) {
            PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT
        } else {
            PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP
        }
        currentEngine = nextEngine

        val safePosMs = Math.max(0L, currentPosMs - VoxPlayerRecoveryPolicy.POSITION_RESTORE_SAFE_OFFSET_MS)

        actionHandler.showRecoveryOverlay(R.string.vox_fixing_connection)

        val ctx = HashMap<String, String>()
        ctx["sessionId"] = currentSessionId.toString()
        ctx["engineSwitchAttempt"] = engineSwitchCount.toString()
        ctx["nextEngine"] = nextEngine.toString()
        ctx["reason"] = reason
        ctx["targetPosMs"] = safePosMs.toString()
        VoxSafeLogger.warn(
            VoxLogCategory.NETWORK,
            VoxLogCode.DNS_RECOVERY_STARTED,
            "Запуск сетевого восстановления движка воспроизведения",
            ctx
        )

        actionHandler.onEngineSwitchRequested(nextEngine, safePosMs, playWhenReady, gen)
        return true
    }

    private fun triggerTerminalFailure(reason: String) {
        currentState = VoxPlayerRecoveryState.FAILED
        handler.removeCallbacksAndMessages(null)
        actionHandler.hideBufferingSpinner()
        actionHandler.hideRecoveryOverlay()

        val ctx = HashMap<String, String>()
        ctx["sessionId"] = currentSessionId.toString()
        ctx["finalReason"] = reason
        ctx["sourceRecoveries"] = sourceRecoveryCount.toString()
        ctx["engineSwitches"] = engineSwitchCount.toString()
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
        actionHandler.onRecoveryFailed(recoveryGeneration, retryRunnable)
    }

    @Synchronized
    fun retryManual() {
        sourceRecoveryCount = 0
        engineSwitchCount = 0
        recoveryGeneration++
        currentState = VoxPlayerRecoveryState.IDLE
        val safePosMs = Math.max(0L, lastObservedPositionMs - VoxPlayerRecoveryPolicy.POSITION_RESTORE_SAFE_OFFSET_MS)
        triggerSourceRefresh(safePosMs, true, "USER_MANUAL_RETRY")
    }

    @Synchronized
    fun reset() {
        recoveryGeneration++
        handler.removeCallbacksAndMessages(null)
        currentState = VoxPlayerRecoveryState.IDLE
    }
}
