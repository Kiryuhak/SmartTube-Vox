package com.liskovsoft.smartyoutubetv2.common.vox.network

import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.util.ArrayDeque
import java.util.Deque

/**
 * Adaptive Network Engine 2.0: Health state tracking, hysteresis, and anti-ping-pong switching policy.
 *
 * Guarantees that the engine never toggles back and forth every few seconds.
 * Requires in-place reconnect attempts, hold time, cooldown, and strict switch budget.
 */
class VoxAdaptiveNetworkEnginePolicy {

    enum class Engine(val id: Int, val displayName: String) {
        CRONET(0, "Cronet"),
        OKHTTP(1, "OkHttp"),
        AUTO(2, "Auto");

        companion object {
            fun fromId(id: Int): Engine = when (id) {
                0 -> CRONET
                1 -> OKHTTP
                else -> AUTO
            }
        }
    }

    enum class HealthState {
        HEALTHY,
        DEGRADED,
        RECOVERING,
        COOLDOWN,
        FAILED
    }

    enum class TrafficProfile {
        VOD,
        LIVE,
        IMAGE,
        TRANSLATION_API,
        DOWNLOAD,
        OTA
    }

    sealed class SwitchDecision {
        data class SwitchTo(val newEngine: Engine, val reason: String) : SwitchDecision()
        data class Stay(val currentEngine: Engine, val reason: String) : SwitchDecision()
        object BudgetExhausted : SwitchDecision()
        object BlockedNonNetwork : SwitchDecision()
    }

    companion object {
        const val MIN_HOLD_TIME_MS = 60_000L
        const val ENGINE_COOLDOWN_MS = 60_000L
        const val SWITCH_BUDGET_WINDOW_MS = 300_000L // 5 min
        const val MAX_SWITCHES_PER_SESSION = 2
        const val CONSECUTIVE_FAILURES_BEFORE_SWITCH = 2
    }

    private val switchTimestamps: Deque<Long> = ArrayDeque()
    private var lastSwitchTimeMs: Long = Long.MIN_VALUE
    private var previousEngine: Engine? = null
    private var consecutiveFailures = 0
    private var reconnectAttempted = false

    var currentHealthState: HealthState = HealthState.HEALTHY
        private set

    fun reset() {
        switchTimestamps.clear()
        lastSwitchTimeMs = Long.MIN_VALUE
        previousEngine = null
        consecutiveFailures = 0
        reconnectAttempted = false
        currentHealthState = HealthState.HEALTHY
    }

    fun onPlaybackProgress(nowMs: Long = System.currentTimeMillis()) {
        consecutiveFailures = 0
        reconnectAttempted = false
        if (currentHealthState != HealthState.HEALTHY) {
            currentHealthState = HealthState.HEALTHY
            val ctx = mapOf(
                "state" to HealthState.HEALTHY.name,
                "elapsedSinceLastSwitchMs" to elapsedSinceLastSwitch(nowMs).toString()
            )
            VoxSafeLogger.d(VoxLogCategory.NETWORK, VoxLogCode.NETWORK_ENGINE_HEALTH, "Network engine healthy", ctx)
        }
    }

    fun onNetworkFailure(errorClass: String, nowMs: Long = System.currentTimeMillis()): HealthState {
        consecutiveFailures++
        currentHealthState = if (consecutiveFailures >= CONSECUTIVE_FAILURES_BEFORE_SWITCH) {
            HealthState.DEGRADED
        } else {
            HealthState.RECOVERING
        }

        val ctx = mapOf(
            "state" to currentHealthState.name,
            "consecutiveFailures" to consecutiveFailures.toString(),
            "failureClass" to errorClass
        )
        VoxSafeLogger.w(VoxLogCategory.NETWORK, VoxLogCode.NETWORK_ENGINE_DEGRADED, "Network engine failure recorded", ctx)
        return currentHealthState
    }

    fun evaluateFallback(
        currentEngine: Engine,
        isConfirmedNetworkIssue: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): SwitchDecision {
        if (!isConfirmedNetworkIssue) {
            val ctx = mapOf(
                "currentEngine" to currentEngine.displayName,
                "reason" to "NON_NETWORK_FAILURE"
            )
            VoxSafeLogger.i(VoxLogCategory.NETWORK, VoxLogCode.NETWORK_ENGINE_SWITCH_BLOCKED, "Engine switch blocked: non-network issue", ctx)
            return SwitchDecision.BlockedNonNetwork
        }

        pruneBudget(nowMs)

        // 1. Must attempt reconnect in place first
        if (!reconnectAttempted) {
            reconnectAttempted = true
            currentHealthState = HealthState.RECOVERING
            val ctx = mapOf(
                "currentEngine" to currentEngine.displayName,
                "reason" to "IN_PLACE_RECONNECT_FIRST"
            )
            VoxSafeLogger.d(VoxLogCategory.NETWORK, VoxLogCode.NETWORK_ENGINE_RECOVERY_STARTED, "Attempting in-place reconnect on current engine", ctx)
            return SwitchDecision.Stay(currentEngine, "IN_PLACE_RECONNECT")
        }

        // 2. Must meet consecutive failure threshold
        if (consecutiveFailures < CONSECUTIVE_FAILURES_BEFORE_SWITCH) {
            return SwitchDecision.Stay(currentEngine, "AWAITING_FAILURE_THRESHOLD")
        }

        // 3. Minimum hold time enforcement
        val holdElapsed = elapsedSinceLastSwitch(nowMs)
        if (holdElapsed < MIN_HOLD_TIME_MS) {
            val ctx = mapOf(
                "currentEngine" to currentEngine.displayName,
                "holdElapsedMs" to holdElapsed.toString(),
                "minHoldMs" to MIN_HOLD_TIME_MS.toString()
            )
            VoxSafeLogger.i(VoxLogCategory.NETWORK, VoxLogCode.NETWORK_ENGINE_SWITCH_BLOCKED, "Engine switch blocked by minimum hold time", ctx)
            return SwitchDecision.Stay(currentEngine, "HOLD_TIME_ACTIVE")
        }

        // 4. Session switch budget enforcement
        if (switchTimestamps.size >= MAX_SWITCHES_PER_SESSION) {
            val ctx = mapOf(
                "switchCount" to switchTimestamps.size.toString(),
                "maxSwitches" to MAX_SWITCHES_PER_SESSION.toString()
            )
            VoxSafeLogger.w(VoxLogCategory.NETWORK, VoxLogCode.NETWORK_ENGINE_SWITCH_BUDGET_EXHAUSTED, "Network engine switch budget exhausted", ctx)
            return SwitchDecision.BudgetExhausted
        }

        // 5. Select candidate alternate engine
        val candidate = when (currentEngine) {
            Engine.CRONET -> Engine.OKHTTP
            Engine.OKHTTP -> Engine.CRONET
            Engine.AUTO -> Engine.OKHTTP
        }

        // 6. Check cooldown for candidate if it was the previous engine
        if (candidate == previousEngine && holdElapsed < ENGINE_COOLDOWN_MS) {
            val ctx = mapOf(
                "candidate" to candidate.displayName,
                "cooldownRemainingMs" to (ENGINE_COOLDOWN_MS - holdElapsed).toString()
            )
            VoxSafeLogger.i(VoxLogCategory.NETWORK, VoxLogCode.NETWORK_ENGINE_SWITCH_BLOCKED, "Candidate engine in cooldown", ctx)
            return SwitchDecision.Stay(currentEngine, "CANDIDATE_IN_COOLDOWN")
        }

        // Execute fallback
        previousEngine = currentEngine
        lastSwitchTimeMs = nowMs
        switchTimestamps.addLast(nowMs)
        consecutiveFailures = 0
        reconnectAttempted = false
        currentHealthState = HealthState.COOLDOWN

        val ctx = mapOf(
            "fromEngine" to currentEngine.displayName,
            "toEngine" to candidate.displayName,
            "switchNumber" to switchTimestamps.size.toString()
        )
        VoxSafeLogger.i(VoxLogCategory.NETWORK, VoxLogCode.NETWORK_ENGINE_FALLBACK, "Network engine fallback executed", ctx)
        return SwitchDecision.SwitchTo(candidate, "FALLBACK_TRIGGERED")
    }

    fun elapsedSinceLastSwitch(nowMs: Long): Long {
        return if (lastSwitchTimeMs == Long.MIN_VALUE) Long.MAX_VALUE else Math.max(0L, nowMs - lastSwitchTimeMs)
    }

    fun getSwitchCount(nowMs: Long): Int {
        pruneBudget(nowMs)
        return switchTimestamps.size
    }

    private fun pruneBudget(nowMs: Long) {
        while (!switchTimestamps.isEmpty() && nowMs - switchTimestamps.peekFirst() >= SWITCH_BUDGET_WINDOW_MS) {
            switchTimestamps.removeFirst()
        }
    }
}
