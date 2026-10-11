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
        READY,
        TIMEOUT,
        FAILED,
        CANCELLED
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

    private var stageStartedAtMs: Long = 0L
    private var jobStartedAtMs: Long = 0L

    // ETA countdown management: monotonically non-increasing bounded countdown
    private var initialEtaSec: Int = 0
    private var lastReportedEtaSec: Int = -1
    private var etaTargetMs: Long = 0L

    fun transitionTo(newState: State, nowMs: Long = System.currentTimeMillis()) {
        val prev = currentState
        if (prev == newState) return

        val stageElapsed = if (stageStartedAtMs > 0L) Math.max(0L, nowMs - stageStartedAtMs) else 0L

        when (prev) {
            State.LOOKUP -> lookupElapsedMs = stageElapsed
            State.REQUESTING -> requestElapsedMs = stageElapsed
            State.WAITING_PROVIDER -> providerWaitElapsedMs = stageElapsed
            State.PROCESSING -> processingElapsedMs = stageElapsed
            else -> Unit
        }

        currentState = newState
        stageStartedAtMs = nowMs

        if (newState == State.LOOKUP || newState == State.REQUESTING) {
            if (jobStartedAtMs == 0L) jobStartedAtMs = nowMs
        } else if (newState == State.READY || newState == State.TIMEOUT || newState == State.FAILED || newState == State.CANCELLED) {
            if (jobStartedAtMs > 0L) {
                totalTranslationStartupMs = Math.max(0L, nowMs - jobStartedAtMs)
            }
        }
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

    fun reset() {
        currentState = State.IDLE
        reuseSource = ReuseSource.NONE
        lookupElapsedMs = 0L
        requestElapsedMs = 0L
        providerWaitElapsedMs = 0L
        processingElapsedMs = 0L
        audioAttachElapsedMs = 0L
        totalTranslationStartupMs = 0L
        stageStartedAtMs = 0L
        jobStartedAtMs = 0L
        initialEtaSec = 0
        lastReportedEtaSec = -1
        etaTargetMs = 0L
    }

    enum class TranslationSource(val label: String) {
        LOCAL_CACHE("LOCAL_CACHE"),
        PERSISTED_SESSION("PERSISTED_SESSION"),
        BACKEND_READY("BACKEND_READY"),
        BACKEND_PENDING("BACKEND_PENDING"),
        NEW_REQUEST("NEW_REQUEST")
    }

    fun getDiagnosticsSummary(): Map<String, Any> {
        val src = when (reuseSource) {
            ReuseSource.MEMORY_CACHE -> TranslationSource.LOCAL_CACHE.label
            ReuseSource.PERSISTED_SESSION -> TranslationSource.PERSISTED_SESSION.label
            ReuseSource.BACKEND_READY_LOOKUP -> TranslationSource.BACKEND_READY.label
            ReuseSource.EXISTING_PROVIDER_SESSION -> TranslationSource.BACKEND_PENDING.label
            else -> TranslationSource.NEW_REQUEST.label
        }
        return mapOf(
            "translationSource" to src,
            "translationLookupMs" to lookupElapsedMs,
            "translationWaitMs" to (requestElapsedMs + providerWaitElapsedMs),
            "translationAttachMs" to audioAttachElapsedMs,
            "translationTotalMs" to totalTranslationStartupMs
        )
    }

    fun toDiagnosticsContext(): Map<String, String> {
        return mapOf(
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
    }
}
