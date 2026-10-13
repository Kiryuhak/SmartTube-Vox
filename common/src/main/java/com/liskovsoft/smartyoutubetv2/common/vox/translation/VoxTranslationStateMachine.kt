package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * State machine for VOT translation jobs with bounded countdown ETA and stage duration profiling.
 */
class VoxTranslationStateMachine {

    enum class State {
        IDLE,
        LOOKUP,
        LOOKUP_READY,
        REQUESTING,
        WAITING_PROVIDER,
        PROCESSING,
        ATTACHING,
        READY,
        TIMEOUT,
        FAILED,
        CANCELLED
    }

    enum class ProgressUpdateType {
        REAL_PROGRESS,
        HEARTBEAT_ONLY
    }

    enum class ReuseSource {
        NONE,
        MEMORY_CACHE,
        PERSISTED_SESSION,
        EXISTING_PROVIDER_SESSION,
        BACKEND_READY_LOOKUP
    }

    var currentState: State = State.IDLE
        private set

    var reuseSource: ReuseSource = ReuseSource.NONE
        private set

    var lookupElapsedMs: Long = 0L
    var requestElapsedMs: Long = 0L
    var providerWaitElapsedMs: Long = 0L
    var processingElapsedMs: Long = 0L
    var audioAttachElapsedMs: Long = 0L
    var totalTranslationStartupMs: Long = 0L

    var lastRealProgressPercent: Int = -1
        private set
    var lastStageTransitionAtMs: Long = 0L
        private set
    var lastHeartbeatAtMs: Long = 0L
        private set
    var lastAudioProgressAtMs: Long = 0L
        private set
    var lastAudioBufferedPositionMs: Long = -1L
        private set
    var primaryFailureReason: String? = null
        private set
    var fallbackAttempted: Boolean = false
        private set

    private var stageStartedAtMs: Long = 0L
    private var jobStartedAtMs: Long = 0L

    // ETA countdown management: monotonically non-increasing bounded countdown
    private var initialEtaSec: Int = 0
    private var lastReportedEtaSec: Int = -1
    private var lowestBackendEtaSec: Int = Int.MAX_VALUE
    private var etaTargetMs: Long = 0L

    companion object {
        @JvmStatic
        fun isValidTranslationId(translationId: String?): Boolean {
            if (translationId.isNullOrBlank()) return false
            val trimmed = translationId.trim()
            if (trimmed.equals("null", ignoreCase = true) ||
                trimmed.equals("undefined", ignoreCase = true) ||
                trimmed.equals("none", ignoreCase = true)) {
                return false
            }
            return trimmed.length in 2..256
        }

        @JvmStatic
        fun isValidAudioUrl(audioUrl: String?): Boolean {
            if (audioUrl.isNullOrBlank()) return false
            val trimmed = audioUrl.trim()
            if (!trimmed.startsWith("https://", ignoreCase = true) && !trimmed.startsWith("http://", ignoreCase = true)) {
                return false
            }
            return try {
                val uri = java.net.URI(trimmed)
                !uri.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }
    }

    fun transitionTo(newState: State, nowMs: Long = System.currentTimeMillis()) {
        val prev = currentState
        if (prev == newState) return

        val stageElapsed = if (stageStartedAtMs > 0L) Math.max(0L, nowMs - stageStartedAtMs) else 0L

        when (prev) {
            State.LOOKUP -> lookupElapsedMs = stageElapsed
            State.REQUESTING -> requestElapsedMs = stageElapsed
            State.WAITING_PROVIDER -> providerWaitElapsedMs = stageElapsed
            State.PROCESSING -> processingElapsedMs = stageElapsed
            State.ATTACHING -> audioAttachElapsedMs = stageElapsed
            else -> Unit
        }

        currentState = newState
        stageStartedAtMs = nowMs
        lastStageTransitionAtMs = nowMs
        lastHeartbeatAtMs = nowMs

        if (newState == State.ATTACHING) {
            lastRealProgressPercent = -1
            lastAudioProgressAtMs = nowMs
            lastAudioBufferedPositionMs = -1L
        }

        if (newState == State.LOOKUP || newState == State.REQUESTING) {
            if (jobStartedAtMs == 0L) jobStartedAtMs = nowMs
        } else if (newState == State.READY || newState == State.TIMEOUT || newState == State.FAILED || newState == State.CANCELLED) {
            if (jobStartedAtMs > 0L) {
                totalTranslationStartupMs = Math.max(0L, nowMs - jobStartedAtMs)
            }
        }
    }

    fun onProgressPoll(
        progressPercent: Int = -1,
        remainingSec: Int = -1,
        nowMs: Long = System.currentTimeMillis()
    ): ProgressUpdateType {
        lastHeartbeatAtMs = nowMs
        if (lastStageTransitionAtMs == 0L) {
            lastStageTransitionAtMs = nowMs
        }

        if (progressPercent in 1..100 && progressPercent > lastRealProgressPercent) {
            lastRealProgressPercent = progressPercent
            lastStageTransitionAtMs = nowMs
            return ProgressUpdateType.REAL_PROGRESS
        }

        if (remainingSec > 0) {
            reconcileEtaSec(remainingSec, nowMs)
            if (remainingSec < lowestBackendEtaSec) {
                lowestBackendEtaSec = remainingSec
                lastStageTransitionAtMs = nowMs
                return ProgressUpdateType.REAL_PROGRESS
            }
        }

        return ProgressUpdateType.HEARTBEAT_ONLY
    }

    fun onAudioProgressCheck(
        currentPosMs: Long,
        bufferedPosMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): ProgressUpdateType {
        val highest = Math.max(currentPosMs, bufferedPosMs)
        if (highest >= 0L && highest > lastAudioBufferedPositionMs) {
            lastAudioBufferedPositionMs = highest
            lastAudioProgressAtMs = nowMs
            return ProgressUpdateType.REAL_PROGRESS
        }
        if (lastAudioProgressAtMs == 0L) {
            lastAudioProgressAtMs = nowMs
        }
        return ProgressUpdateType.HEARTBEAT_ONLY
    }

    fun isAudioNoProgressTimedOut(nowMs: Long, maxNoProgressMs: Long = 12_000L): Boolean {
        return lastAudioProgressAtMs > 0L && (nowMs - lastAudioProgressAtMs) >= maxNoProgressMs
    }

    fun canAttemptFallback(): Boolean = !fallbackAttempted

    fun recordFallbackAttempt(primaryReason: String): Boolean {
        if (fallbackAttempted) return false
        fallbackAttempted = true
        primaryFailureReason = primaryReason
        return true
    }

    fun isStageStalled(nowMs: Long, maxStageStallMs: Long): Boolean {
        if (currentState == State.IDLE || currentState == State.READY ||
            currentState == State.TIMEOUT || currentState == State.FAILED || currentState == State.CANCELLED) {
            return false
        }
        return lastStageTransitionAtMs > 0L && (nowMs - lastStageTransitionAtMs) >= maxStageStallMs
    }

    fun isPostProcessTimedOut(nowMs: Long, postProcessTimeoutMs: Long = 15_000L): Boolean {
        return currentState == State.ATTACHING && stageStartedAtMs > 0L && (nowMs - stageStartedAtMs) >= postProcessTimeoutMs
    }

    fun markLookupReady(source: ReuseSource, nowMs: Long = System.currentTimeMillis()) {
        reuseSource = source
        transitionTo(State.LOOKUP_READY, nowMs)
    }

    fun recordAudioAttachDuration(durationMs: Long) {
        audioAttachElapsedMs = Math.max(0L, durationMs)
    }

    /**
     * Sets or reconciles backend ETA.
     * Enforces monotonically non-increasing countdown so time never increases (+00:17, +01:09).
     */
    fun reconcileEtaSec(backendRemainingSec: Int, nowMs: Long = System.currentTimeMillis()): Int {
        if (backendRemainingSec <= 0) {
            lastReportedEtaSec = 0
            etaTargetMs = 0L
            return 0
        }

        if (etaTargetMs <= 0L) {
            initialEtaSec = backendRemainingSec
            lastReportedEtaSec = backendRemainingSec
            etaTargetMs = nowMs + backendRemainingSec * 1000L
            return backendRemainingSec
        }

        // Compute remaining from target
        val remainingFromTarget = Math.max(0, ((etaTargetMs - nowMs + 999L) / 1000L).toInt())

        // Never increase countdown: take minimum of current remaining and backend remaining
        val effective = Math.min(remainingFromTarget, backendRemainingSec)
        lastReportedEtaSec = effective
        // Re-anchor target
        etaTargetMs = nowMs + effective * 1000L
        return effective
    }

    fun getCountdownSec(nowMs: Long = System.currentTimeMillis()): Int {
        if (etaTargetMs <= 0L || nowMs >= etaTargetMs) return 0
        return Math.max(0, ((etaTargetMs - nowMs + 999L) / 1000L).toInt())
    }

    fun isEtaKnown(): Boolean = etaTargetMs > 0L && lastReportedEtaSec > 0

    fun hasActiveOrCompletedSessionData(): Boolean {
        return jobStartedAtMs > 0L ||
                currentState != State.IDLE ||
                lookupElapsedMs > 0L ||
                requestElapsedMs > 0L ||
                providerWaitElapsedMs > 0L ||
                processingElapsedMs > 0L ||
                audioAttachElapsedMs > 0L ||
                totalTranslationStartupMs > 0L
    }

    fun reset() {
        currentState = State.IDLE
        reuseSource = ReuseSource.NONE
        lookupElapsedMs = 0L
        requestElapsedMs = 0L
        providerWaitElapsedMs = 0L
        processingElapsedMs = 0L
        audioAttachElapsedMs = 0L
        totalTranslationStartupMs = 0L
        lastRealProgressPercent = -1
        lastStageTransitionAtMs = 0L
        lastHeartbeatAtMs = 0L
        lastAudioProgressAtMs = 0L
        lastAudioBufferedPositionMs = -1L
        primaryFailureReason = null
        fallbackAttempted = false
        stageStartedAtMs = 0L
        jobStartedAtMs = 0L
        initialEtaSec = 0
        lastReportedEtaSec = -1
        lowestBackendEtaSec = Int.MAX_VALUE
        etaTargetMs = 0L
    }

    enum class TranslationSource(val label: String) {
        LOCAL_CACHE("LOCAL_CACHE"),
        PERSISTED_SESSION("PERSISTED_SESSION"),
        BACKEND_READY("BACKEND_READY"),
        BACKEND_PENDING("BACKEND_PENDING"),
        NEW_REQUEST("NEW_REQUEST"),
        NO_ACTIVE_SESSION("NO_ACTIVE_SESSION")
    }

    fun getDiagnosticsSummary(): Map<String, Any> {
        if (!hasActiveOrCompletedSessionData()) {
            return mapOf(
                "available" to false,
                "translationSource" to TranslationSource.NO_ACTIVE_SESSION.label
            )
        }
        val src = when (reuseSource) {
            ReuseSource.MEMORY_CACHE -> TranslationSource.LOCAL_CACHE.label
            ReuseSource.PERSISTED_SESSION -> TranslationSource.PERSISTED_SESSION.label
            ReuseSource.BACKEND_READY_LOOKUP -> TranslationSource.BACKEND_READY.label
            ReuseSource.EXISTING_PROVIDER_SESSION -> TranslationSource.BACKEND_PENDING.label
            else -> TranslationSource.NEW_REQUEST.label
        }
        return mapOf(
            "available" to true,
            "translationSource" to src,
            "translationLookupMs" to lookupElapsedMs,
            "translationWaitMs" to (requestElapsedMs + providerWaitElapsedMs),
            "translationAttachMs" to audioAttachElapsedMs,
            "translationTotalMs" to totalTranslationStartupMs
        )
    }

    fun toDiagnosticsContext(): Map<String, String> {
        val ctx = mutableMapOf(
            "state" to currentState.name,
            "reuseSource" to reuseSource.name,
            "lookupElapsedMs" to lookupElapsedMs.toString(),
            "requestElapsedMs" to requestElapsedMs.toString(),
            "providerWaitElapsedMs" to providerWaitElapsedMs.toString(),
            "processingElapsedMs" to processingElapsedMs.toString(),
            "audioAttachElapsedMs" to audioAttachElapsedMs.toString(),
            "totalTranslationStartupMs" to totalTranslationStartupMs.toString(),
            "isReused" to (reuseSource != ReuseSource.NONE).toString()
        )
        primaryFailureReason?.let { ctx["primaryFailureReason"] = it }
        if (fallbackAttempted) ctx["fallbackAttempted"] = "true"
        return ctx
    }
}
