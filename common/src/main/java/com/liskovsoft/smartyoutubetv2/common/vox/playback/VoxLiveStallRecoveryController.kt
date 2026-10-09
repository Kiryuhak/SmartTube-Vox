package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.misc.NetworkEngineRecoveryPolicy
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Состояния контролируемого автомата восстановления прямого эфира (Live Stall Recovery FSM).
 */
enum class VoxLiveStallState {
    IDLE,
    BUFFERING_OBSERVED,
    STALL_DETECTED,
    REFRESHING_MANIFEST,
    CLAMPING_LIVE_WINDOW,
    RESEEKING,
    RECREATING_SOURCE,
    NETWORK_FALLBACK,
    RECOVERED,
    FAILED;
}

/**
 * Контроллер защиты от зависаний и сбоев перемотки прямого эфира (Live Random Stalls & DVR Seek Recovery).
 *
 * Patch #17:
 * 1. Отслеживает зависания live-буфера через таймер Watchdog (3.5с классификация, 6.0с запуск восстановления).
 * 2. Выполняет ступенчатое восстановление: Refresh Manifest -> Clamp Live Window -> Reseek Live Offset -> Recreate Source.
 * 3. Предотвращает циклические переключения сетевых движков (Cronet/OkHttp).
 * 4. Валидирует границы DVR live window и восстанавливает воспроизведение при BehindLiveWindowException без зависаний.
 * 5. Нулевая телеметрия (Zero-PII) при логировании диагностических событий.
 */
class VoxLiveStallRecoveryController @JvmOverloads constructor(
    private val actionHandler: LiveRecoveryActionHandler,
    private val looper: Looper = Looper.getMainLooper()
) {

    interface LiveRecoveryActionHandler {
        fun onRefreshManifestRequested()
        fun onReseekRequested(targetPositionMs: Long)
        fun onRecreateSourceRequested()
        fun onNetworkFallbackRequested(nextEngine: Int)
        fun onReturnToLiveRequested()
        fun showMessage(message: String)
    }

    companion object {
        const val STALL_CLASSIFICATION_DELAY_MS = 3_500L
        const val STALL_RECOVERY_TRIGGER_DELAY_MS = 6_000L
        const val SAFE_LIVE_OFFSET_MS = 15_000L
        const val MIN_LIVE_WINDOW_CLAMP_BUFFER_MS = 2_000L
        const val MAX_RECOVERY_ATTEMPTS = 3
    }

    private val handler = Handler(looper)
    private val networkRecoveryPolicy = NetworkEngineRecoveryPolicy()

    private var currentState: VoxLiveStallState = VoxLiveStallState.IDLE
    private var isLiveSession: Boolean = false
    private var recoveryAttemptsCount: Int = 0
    private var bufferingStartMs: Long = 0L
    private var lastObservedLiveOffsetMs: Long = 0L
    private var lastObservedDurationMs: Long = 0L

    private val classificationRunnable = Runnable {
        onStallClassified()
    }

    private val recoveryTriggerRunnable = Runnable {
        onStallRecoveryTriggered()
    }

    fun getState(): VoxLiveStallState = currentState
    fun getRecoveryAttempts(): Int = recoveryAttemptsCount

    @Synchronized
    fun onPlaybackStart(isLive: Boolean) {
        reset()
        isLiveSession = isLive
        currentState = VoxLiveStallState.IDLE
    }

    @Synchronized
    fun onBufferingStarted(
        isLive: Boolean,
        currentPositionMs: Long,
        durationMs: Long,
        liveOffsetMs: Long
    ) {
        if (!isLive) return

        isLiveSession = true
        lastObservedLiveOffsetMs = liveOffsetMs
        lastObservedDurationMs = durationMs
        bufferingStartMs = System.currentTimeMillis()

        if (currentState != VoxLiveStallState.BUFFERING_OBSERVED) {
            currentState = VoxLiveStallState.BUFFERING_OBSERVED
            handler.removeCallbacks(classificationRunnable)
            handler.removeCallbacks(recoveryTriggerRunnable)
            handler.postDelayed(classificationRunnable, STALL_CLASSIFICATION_DELAY_MS)
            handler.postDelayed(recoveryTriggerRunnable, STALL_RECOVERY_TRIGGER_DELAY_MS)
        }
    }

    @Synchronized
    fun onBufferingEnded() {
        if (!isLiveSession) return

        handler.removeCallbacks(classificationRunnable)
        handler.removeCallbacks(recoveryTriggerRunnable)

        if (currentState != VoxLiveStallState.IDLE) {
            val wasStalled = currentState != VoxLiveStallState.BUFFERING_OBSERVED
            currentState = VoxLiveStallState.RECOVERED
            networkRecoveryPolicy.onPlaybackProgress()
            if (wasStalled) {
                VoxSafeLogger.info(
                    VoxLogCategory.PLAYER,
                    VoxLogCode.LIVE_STALL_RECOVERY_SUCCESS,
                    "Live playback recovered from stall (attempts=$recoveryAttemptsCount)"
                )
            }
            recoveryAttemptsCount = 0
        }
        currentState = VoxLiveStallState.IDLE
    }

    @Synchronized
    fun onSeekRequested(
        requestedPosMs: Long,
        windowDurationMs: Long,
        windowStartMs: Long,
        isLive: Boolean
    ): Long {
        if (!isLive || windowDurationMs <= 0L) {
            return requestedPosMs
        }

        currentState = VoxLiveStallState.CLAMPING_LIVE_WINDOW
        val minSafePos = if (windowStartMs > 0L) windowStartMs + 1_000L else 0L
        val maxSafePos = Math.max(minSafePos, windowDurationMs - MIN_LIVE_WINDOW_CLAMP_BUFFER_MS)

        val clamped = when {
            requestedPosMs < minSafePos -> {
                VoxSafeLogger.w(
                    VoxLogCategory.PLAYER,
                    VoxLogCode.LIVE_DVR_SEEK_CLAMP,
                    "DVR seek clamped to window start: requested=$requestedPosMs, minSafe=$minSafePos"
                )
                minSafePos
            }
            requestedPosMs > maxSafePos -> {
                VoxSafeLogger.w(
                    VoxLogCategory.PLAYER,
                    VoxLogCode.LIVE_DVR_SEEK_CLAMP,
                    "DVR seek clamped to window end: requested=$requestedPosMs, maxSafe=$maxSafePos"
                )
                maxSafePos
            }
            else -> requestedPosMs
        }

        currentState = VoxLiveStallState.IDLE
        return clamped
    }

    @Synchronized
    fun handleBehindLiveWindow(context: Context?, onReturnToLive: Runnable) {
        VoxSafeLogger.w(
            VoxLogCategory.PLAYER,
            VoxLogCode.LIVE_BEHIND_WINDOW_RECOVERED,
            "BehindLiveWindow detected; recovering to live edge"
        )
        context?.let {
            val msg = it.getString(R.string.vox_live_return_to_live_prompt)
            actionHandler.showMessage(msg)
        }
        onReturnToLive.run()
    }

    @Synchronized
    fun handleNetworkTransportError(
        error: Throwable,
        currentEngine: Int,
        availableEngines: IntArray
    ): Boolean {
        if (!isLiveSession) return false

        val isTransportError = isConfirmedTransportError(error)
        if (!isTransportError) {
            return false
        }

        val nowMs = System.currentTimeMillis()
        val nextEngine = networkRecoveryPolicy.selectNextEngine(currentEngine, availableEngines, nowMs)
        if (nextEngine != currentEngine) {
            currentState = VoxLiveStallState.NETWORK_FALLBACK
            VoxSafeLogger.w(
                VoxLogCategory.PLAYER,
                VoxLogCode.LIVE_NETWORK_ENGINE_SWITCH,
                "Live network engine switched on confirmed transport error: $currentEngine -> $nextEngine"
            )
            actionHandler.onNetworkFallbackRequested(nextEngine)
            return true
        }

        return false
    }

    private fun onStallClassified() {
        if (currentState != VoxLiveStallState.BUFFERING_OBSERVED) return

        currentState = VoxLiveStallState.STALL_DETECTED
        val snapshot = VoxLivePlaybackMonitor.getMetricsSummary()
        VoxSafeLogger.w(
            VoxLogCategory.PLAYER,
            VoxLogCode.LIVE_STALL_CLASSIFIED,
            "Live stall classified at offsetMs=${snapshot.liveOffsetMs}, errorCategory=${snapshot.lastErrorCategory}"
        )
    }

    private fun onStallRecoveryTriggered() {
        if (currentState != VoxLiveStallState.STALL_DETECTED && currentState != VoxLiveStallState.BUFFERING_OBSERVED) {
            return
        }

        recoveryAttemptsCount++
        if (recoveryAttemptsCount > MAX_RECOVERY_ATTEMPTS) {
            currentState = VoxLiveStallState.FAILED
            VoxSafeLogger.e(
                VoxLogCategory.PLAYER,
                VoxLogCode.LIVE_STALL_RECOVERY_FAILED,
                "Live stall recovery exceeded maximum attempts ($MAX_RECOVERY_ATTEMPTS)",
                null,
                null
            )
            actionHandler.onRecreateSourceRequested()
            return
        }

        VoxSafeLogger.w(
            VoxLogCategory.PLAYER,
            VoxLogCode.LIVE_STALL_RECOVERY_ATTEMPT,
            "Executing live stall recovery step attempt=$recoveryAttemptsCount"
        )

        when (recoveryAttemptsCount) {
            1 -> {
                currentState = VoxLiveStallState.REFRESHING_MANIFEST
                actionHandler.onRefreshManifestRequested()
            }
            2 -> {
                currentState = VoxLiveStallState.RESEEKING
                try {
                    actionHandler.showMessage("Восстанавливаем трансляцию…")
                } catch (ignored: Exception) {}
                val target = if (lastObservedDurationMs > SAFE_LIVE_OFFSET_MS) {
                    lastObservedDurationMs - SAFE_LIVE_OFFSET_MS
                } else {
                    0L
                }
                actionHandler.onReseekRequested(target)
            }
            else -> {
                currentState = VoxLiveStallState.RECREATING_SOURCE
                actionHandler.onRecreateSourceRequested()
            }
        }
    }

    @Synchronized
    fun reset() {
        handler.removeCallbacks(classificationRunnable)
        handler.removeCallbacks(recoveryTriggerRunnable)
        currentState = VoxLiveStallState.IDLE
        recoveryAttemptsCount = 0
        bufferingStartMs = 0L
        lastObservedLiveOffsetMs = 0L
        lastObservedDurationMs = 0L
        networkRecoveryPolicy.reset()
    }

    private fun isConfirmedTransportError(error: Throwable?): Boolean {
        var curr = error
        while (curr != null) {
            if (curr is UnknownHostException || curr is SocketTimeoutException || curr is ConnectException) {
                return true
            }
            curr = curr.cause
        }
        return false
    }
}
