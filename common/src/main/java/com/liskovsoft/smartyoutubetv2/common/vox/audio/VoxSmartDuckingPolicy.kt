package com.liskovsoft.smartyoutubetv2.common.vox.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Pure, deterministic Smart Ducking policy implementing exact 1:1 behavior with VOT userscript 1.11.17
 * (src/videoHandler/modules/ducking.ts and src/videoHandler/modules/smartDuckingRuntime.ts).
 *
 * Zero Android dependencies for fast, hermetic unit testing.
 */
class VoxSmartDuckingPolicy(
    val tickMs: Long = TICK_MS,
    val thresholdOnRms: Float = THRESHOLD_ON_RMS,
    val thresholdOffRms: Float = THRESHOLD_OFF_RMS,
    val rmsAttackTauMs: Long = RMS_ATTACK_TAU_MS,
    val rmsReleaseTauMs: Long = RMS_RELEASE_TAU_MS,
    val holdMs: Long = HOLD_MS,
    val attackTauMs: Long = ATTACK_TAU_MS,
    val releaseTauMs: Long = RELEASE_TAU_MS,
    val maxDownPerSec: Float = MAX_DOWN_PER_SEC,
    val maxUpPerSec: Float = MAX_UP_PER_SEC,
    val rmsMissingGraceMs: Long = RMS_MISSING_GRACE_MS,
    val maxDtMs: Long = MAX_DT_MS,
    val externalBaselineDelta01: Float = EXTERNAL_BASELINE_DELTA_01,
    val unduckTolerance01: Float = UNDUCK_TOLERANCE_01,
    val volumeStep01: Float = VOLUME_STEP_01,
    val applyDeltaThreshold01: Float = APPLY_DELTA_THRESHOLD_01
) {

    companion object {
        const val TICK_MS: Long = 50L
        const val THRESHOLD_ON_RMS: Float = 0.012f
        const val THRESHOLD_OFF_RMS: Float = 0.009f
        const val RMS_ATTACK_TAU_MS: Long = 60L
        const val RMS_RELEASE_TAU_MS: Long = 240L
        const val HOLD_MS: Long = 520L
        const val ATTACK_TAU_MS: Long = 110L
        const val RELEASE_TAU_MS: Long = 600L
        const val MAX_DOWN_PER_SEC: Float = 3.5f
        const val MAX_UP_PER_SEC: Float = 0.9f
        const val RMS_MISSING_GRACE_MS: Long = 200L
        const val MAX_DT_MS: Long = 250L
        const val EXTERNAL_BASELINE_DELTA_01: Float = 0.02f
        const val UNDUCK_TOLERANCE_01: Float = 0.01f
        const val VOLUME_STEP_01: Float = 0.01f
        const val APPLY_DELTA_THRESHOLD_01: Float = 0.005f
        private const val EPS: Double = 1e-6
    }

    enum class DuckingState {
        IDLE,
        ATTACK,
        DUCKED,
        HOLD,
        RELEASE
    }

    data class State(
        val isDucked: Boolean = false,
        val speechGateOpen: Boolean = false,
        val rmsEnvelope: Float = 0f,
        val baseline: Float = 1.0f,
        val lastApplied: Float? = null,
        val lastTickAt: Long = 0L,
        val lastSoundAt: Long = 0L,
        val rmsMissingSinceAt: Long? = null,
        val duckingState: DuckingState = DuckingState.IDLE
    )

    data class Input(
        val nowMs: Long,
        val translationActive: Boolean,
        val autoVolumeEnabled: Boolean,
        val smartDuckingEnabled: Boolean,
        val audioIsPlaying: Boolean,
        val hostVideoActive: Boolean,
        val currentVideoVolume: Float,
        val volumeOnStart: Float,
        val duckingTarget01: Float,
        val rms: Float?,
        val hasRms: Boolean
    )

    sealed class Decision {
        abstract val newState: State

        data class Apply(
            val targetVolume: Float,
            override val newState: State,
            val reason: String
        ) : Decision()

        data class Noop(
            override val newState: State,
            val reason: String
        ) : Decision()

        data class Stop(
            val restoreVolume: Float,
            override val newState: State,
            val reason: String
        ) : Decision()
    }

    fun initState(initialBaseline: Float = 1.0f): State {
        val clamped = clamp01(initialBaseline)
        return State(
            baseline = clamped,
            lastApplied = clamped
        )
    }

    /**
     * Updates baseline if user manually adjusts TV/video volume outside the ducking controller's write.
     */
    fun updateBaselineOnUserChange(currentVideoVolume: Float, state: State): State {
        val clamped = clamp01(currentVideoVolume)
        if (!state.isDucked) {
            return state.copy(baseline = clamped)
        }
        val lastApp = state.lastApplied
        if (lastApp != null && abs(clamped - lastApp) > externalBaselineDelta01) {
            return state.copy(baseline = clamped)
        }
        return state
    }

    /**
     * Computes single step of smart ducking state machine matching computeSmartDuckingStep from reference.
     */
    fun evaluateStep(input: Input, state: State): Decision {
        val volumeOnStart = clamp01(input.volumeOnStart)

        // 1. If translation is inactive or auto-volume is off -> STOP
        if (!input.translationActive || !input.autoVolumeEnabled) {
            val restoreVol = state.baseline.takeIf { it > 0.001f } ?: volumeOnStart
            val resetState = state.copy(
                isDucked = false,
                speechGateOpen = false,
                rmsEnvelope = 0f,
                lastApplied = null,
                duckingState = DuckingState.IDLE
            )
            return Decision.Stop(restoreVol, resetState, "translation_inactive_or_disabled")
        }

        val now = if (input.nowMs > 0L) input.nowMs else System.currentTimeMillis()
        val dtMs = if (state.lastTickAt > 0L) {
            min(maxDtMs, max(0L, now - state.lastTickAt))
        } else {
            0L
        }
        val dtSec = dtMs / 1000.0f

        // 2. RMS envelope calculation
        val hasRms = input.hasRms && input.rms != null && input.rms.isFinite()
        val rmsValue = if (hasRms) clamp01(input.rms!!) else 0f
        val prevEnv = state.rmsEnvelope
        val envTauMs = if (rmsValue > prevEnv) rmsAttackTauMs else rmsReleaseTauMs
        val envAlpha = if (envTauMs > 0L) 1.0f - exp(-dtMs.toDouble() / envTauMs.toDouble()).toFloat() else 1.0f
        val nextEnv = clamp01(prevEnv + (rmsValue - prevEnv) * envAlpha)

        // 3. Speech gate decision
        var gateOpen = state.speechGateOpen
        var lastSoundAt = state.lastSoundAt
        var rmsMissingSinceAt = state.rmsMissingSinceAt

        if (!input.smartDuckingEnabled) {
            // Classic fixed ducking mode: gate open whenever translation is active
            gateOpen = true
            lastSoundAt = now
            rmsMissingSinceAt = null
        } else if (input.audioIsPlaying && !hasRms) {
            if (rmsMissingSinceAt == null) {
                rmsMissingSinceAt = now
            }
            if (gateOpen) lastSoundAt = now
            if (now - rmsMissingSinceAt >= rmsMissingGraceMs) {
                lastSoundAt = now
                gateOpen = true
            }
        } else {
            rmsMissingSinceAt = null
            if (!input.audioIsPlaying) {
                gateOpen = gateOpen && (now - lastSoundAt <= holdMs)
            } else if (!gateOpen && nextEnv >= thresholdOnRms) {
                lastSoundAt = now
                gateOpen = true
            } else if (gateOpen && nextEnv >= thresholdOffRms) {
                lastSoundAt = now
                gateOpen = true
            } else {
                gateOpen = gateOpen && (now - lastSoundAt <= holdMs)
            }
        }

        // 4. Baseline resolution
        val currentVideoVolume = clamp01(input.currentVideoVolume)
        var baseline = state.baseline
        if (state.isDucked && state.lastApplied != null && abs(currentVideoVolume - state.lastApplied) > externalBaselineDelta01) {
            baseline = currentVideoVolume
        }
        if (!state.isDucked) {
            baseline = currentVideoVolume
        }

        if (!input.hostVideoActive) {
            val pausedState = state.copy(
                speechGateOpen = gateOpen,
                rmsEnvelope = nextEnv,
                baseline = baseline,
                lastApplied = currentVideoVolume,
                lastTickAt = now,
                lastSoundAt = lastSoundAt,
                rmsMissingSinceAt = rmsMissingSinceAt,
                duckingState = if (state.isDucked) DuckingState.DUCKED else DuckingState.IDLE
            )
            return Decision.Noop(pausedState, "host_video_inactive")
        }

        // 5. Desired volume resolution
        val duckedTarget = min(baseline, clamp01(input.duckingTarget01))
        var isDucked = state.isDucked
        val desired: Float
        if (gateOpen) {
            isDucked = true
            desired = duckedTarget
        } else {
            if (isDucked && abs(baseline - currentVideoVolume) < unduckTolerance01) {
                isDucked = false
            }
            desired = baseline
        }

        // 6. Smooth volume change with attack / release and slew limits
        val smoothed = smoothVolumeChange(desired, currentVideoVolume, dtMs, dtSec)
        val quantized = snapVolume01Towards(smoothed, currentVideoVolume, desired, volumeStep01)

        // 7. Determine high-level ducking state for telemetry/observability
        val duckingState = when {
            !isDucked && !gateOpen -> DuckingState.IDLE
            gateOpen && currentVideoVolume > duckedTarget + unduckTolerance01 -> DuckingState.ATTACK
            gateOpen -> DuckingState.DUCKED
            now - lastSoundAt <= holdMs -> DuckingState.HOLD
            else -> DuckingState.RELEASE
        }

        val nextState = state.copy(
            isDucked = isDucked,
            speechGateOpen = gateOpen,
            rmsEnvelope = nextEnv,
            baseline = baseline,
            lastApplied = quantized,
            lastTickAt = now,
            lastSoundAt = lastSoundAt,
            rmsMissingSinceAt = rmsMissingSinceAt,
            duckingState = duckingState
        )

        // 8. Delta threshold check to avoid micro-adjustments
        val lastApplied = state.lastApplied
        if (abs(quantized - currentVideoVolume) < applyDeltaThreshold01) {
            return Decision.Noop(nextState, "delta_below_threshold")
        }
        if (lastApplied == null || abs(quantized - lastApplied) >= applyDeltaThreshold01) {
            return Decision.Apply(quantized, nextState, "apply_volume")
        }

        return Decision.Noop(nextState, "no_significant_change")
    }

    fun resolveBaseline(state: State, currentVideoVolume: Float, volumeOnStart: Float = 1.0f): Float {
        val clamped = clamp01(currentVideoVolume)
        var baseline = state.baseline
        if (state.isDucked && state.lastApplied != null && abs(clamped - state.lastApplied) > externalBaselineDelta01) {
            baseline = clamped
        }
        if (!state.isDucked) {
            baseline = clamped
        }
        return if (baseline > 0.001f) baseline else volumeOnStart
    }

    fun smoothVolumeChange(
        desired: Float,
        current: Float,
        dtMs: Long,
        dtSec: Float
    ): Float {
        val smoothingTauMs = if (desired < current) attackTauMs else releaseTauMs
        val smoothingAlpha = if (smoothingTauMs > 0L) {
            1.0f - exp(-dtMs.toDouble() / smoothingTauMs.toDouble()).toFloat()
        } else {
            1.0f
        }
        var next = current + (desired - current) * smoothingAlpha
        val maxDelta = (if (desired < current) maxDownPerSec else maxUpPerSec) * dtSec
        if (maxDelta > 0f) {
            next = max(current - maxDelta, min(current + maxDelta, next))
        }
        return clamp01(next)
    }

    private fun snapVolume01Towards(next: Float, current: Float, desired: Float, step: Float): Float {
        val cur = clamp01(current)
        val des = clamp01(desired)
        if (des < cur) {
            val q = snapVolume01(next, "down", step)
            return max(des, q)
        }
        if (des > cur) {
            val q = snapVolume01(next, "up", step)
            return min(des, q)
        }
        return snapVolume01(next, "nearest", step)
    }

    private fun snapVolume01(volume01: Float, direction: String, step: Float): Float {
        return clamp01(quantizeToStep(clamp01(volume01), step, direction))
    }

    private fun quantizeToStep(value: Float, step: Float, direction: String): Float {
        if (!value.isFinite() || step <= 0f) return value
        val inv = 1.0 / step.toDouble()
        val scaled = value.toDouble() * inv
        val res = when (direction) {
            "down" -> floor(scaled + EPS) / inv
            "up" -> ceil(scaled - EPS) / inv
            else -> round(scaled) / inv
        }
        return res.toFloat()
    }

    private fun clamp01(v: Float): Float {
        return max(0.0f, min(1.0f, v))
    }
}
