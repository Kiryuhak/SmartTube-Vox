package com.liskovsoft.smartyoutubetv2.common.vox.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoxAdaptiveNetworkEnginePolicyTest {

    private lateinit var policy: VoxAdaptiveNetworkEnginePolicy

    @Before
    fun setUp() {
        policy = VoxAdaptiveNetworkEnginePolicy()
    }

    @Test
    fun testInitialStateIsHealthy() {
        assertEquals(VoxAdaptiveNetworkEnginePolicy.HealthState.HEALTHY, policy.currentHealthState)
        assertEquals(0, policy.getSwitchCount(1000L))
    }

    @Test
    fun testNonNetworkIssueBlocksSwitch() {
        val decision = policy.evaluateFallback(
            currentEngine = VoxAdaptiveNetworkEnginePolicy.Engine.CRONET,
            isConfirmedNetworkIssue = false,
            nowMs = 1000L
        )
        assertTrue(decision is VoxAdaptiveNetworkEnginePolicy.SwitchDecision.BlockedNonNetwork)
    }

    @Test
    fun testInPlaceReconnectRequiredFirst() {
        val t0 = 100_000L
        policy.onNetworkFailure("HttpTimeoutException", t0)

        val decision1 = policy.evaluateFallback(
            currentEngine = VoxAdaptiveNetworkEnginePolicy.Engine.CRONET,
            isConfirmedNetworkIssue = true,
            nowMs = t0 + 100L
        )
        // Must try in-place reconnect first
        assertTrue(decision1 is VoxAdaptiveNetworkEnginePolicy.SwitchDecision.Stay)
        val stay1 = decision1 as VoxAdaptiveNetworkEnginePolicy.SwitchDecision.Stay
        assertEquals("IN_PLACE_RECONNECT", stay1.reason)
    }

    @Test
    fun testConsecutiveFailuresThresholdRequired() {
        val t0 = 100_000L
        // First failure and in-place reconnect
        policy.onNetworkFailure("HttpTimeoutException", t0)
        policy.evaluateFallback(VoxAdaptiveNetworkEnginePolicy.Engine.CRONET, true, t0 + 100L)

        // Only 1 failure so far -> consecutive failures < 2 -> stay
        val decision2 = policy.evaluateFallback(
            currentEngine = VoxAdaptiveNetworkEnginePolicy.Engine.CRONET,
            isConfirmedNetworkIssue = true,
            nowMs = t0 + 200L
        )
        assertTrue(decision2 is VoxAdaptiveNetworkEnginePolicy.SwitchDecision.Stay)
        assertEquals("AWAITING_FAILURE_THRESHOLD", (decision2 as VoxAdaptiveNetworkEnginePolicy.SwitchDecision.Stay).reason)
    }

    @Test
    fun testSwitchExecutedWhenHoldTimeAndThresholdSatisfied() {
        val t0 = 100_000L
        // 1st failure + in-place reconnect
        policy.onNetworkFailure("HttpTimeoutException", t0)
        policy.evaluateFallback(VoxAdaptiveNetworkEnginePolicy.Engine.CRONET, true, t0)

        // 2nd failure
        policy.onNetworkFailure("HttpTimeoutException", t0 + 1000L)

        // Now both in-place reconnect and failure count >= 2 are satisfied
        val decision = policy.evaluateFallback(
            currentEngine = VoxAdaptiveNetworkEnginePolicy.Engine.CRONET,
            isConfirmedNetworkIssue = true,
            nowMs = t0 + 2000L
        )
        assertTrue(decision is VoxAdaptiveNetworkEnginePolicy.SwitchDecision.SwitchTo)
        val switchTo = decision as VoxAdaptiveNetworkEnginePolicy.SwitchDecision.SwitchTo
        assertEquals(VoxAdaptiveNetworkEnginePolicy.Engine.OKHTTP, switchTo.newEngine)
        assertEquals(1, policy.getSwitchCount(t0 + 2000L))
    }

    @Test
    fun testAntiPingPongHoldTimeAndCooldown() {
        val t0 = 100_000L
        // Execute first switch at t0
        policy.onNetworkFailure("HttpTimeoutException", t0)
        policy.evaluateFallback(VoxAdaptiveNetworkEnginePolicy.Engine.CRONET, true, t0)
        policy.onNetworkFailure("HttpTimeoutException", t0)
        val switch1 = policy.evaluateFallback(VoxAdaptiveNetworkEnginePolicy.Engine.CRONET, true, t0)
        assertTrue(switch1 is VoxAdaptiveNetworkEnginePolicy.SwitchDecision.SwitchTo)

        // 10 seconds later on OkHttp, failures occur
        val t1 = t0 + 10_000L
        policy.onNetworkFailure("SocketTimeoutException", t1)
        policy.evaluateFallback(VoxAdaptiveNetworkEnginePolicy.Engine.OKHTTP, true, t1)
        policy.onNetworkFailure("SocketTimeoutException", t1)

        // Attempting to switch again after only 10s MUST be blocked by MIN_HOLD_TIME (60s)
        val decisionEarly = policy.evaluateFallback(
            currentEngine = VoxAdaptiveNetworkEnginePolicy.Engine.OKHTTP,
            isConfirmedNetworkIssue = true,
            nowMs = t1
        )
        assertTrue(decisionEarly is VoxAdaptiveNetworkEnginePolicy.SwitchDecision.Stay)
        assertEquals("HOLD_TIME_ACTIVE", (decisionEarly as VoxAdaptiveNetworkEnginePolicy.SwitchDecision.Stay).reason)
    }

    @Test
    fun testSessionSwitchBudgetExhausted() {
        var t = 100_000L

        // Switch 1
        policy.onNetworkFailure("Err", t)
        policy.evaluateFallback(VoxAdaptiveNetworkEnginePolicy.Engine.CRONET, true, t)
        policy.onNetworkFailure("Err", t)
        val s1 = policy.evaluateFallback(VoxAdaptiveNetworkEnginePolicy.Engine.CRONET, true, t)
        assertTrue(s1 is VoxAdaptiveNetworkEnginePolicy.SwitchDecision.SwitchTo)

        // Fast-forward 65 seconds (exceeding hold time)
        t += 65_000L

        // Switch 2
        policy.onNetworkFailure("Err", t)
        policy.evaluateFallback(VoxAdaptiveNetworkEnginePolicy.Engine.OKHTTP, true, t)
        policy.onNetworkFailure("Err", t)
        val s2 = policy.evaluateFallback(VoxAdaptiveNetworkEnginePolicy.Engine.OKHTTP, true, t)
        assertTrue(s2 is VoxAdaptiveNetworkEnginePolicy.SwitchDecision.SwitchTo)

        // Fast-forward another 65 seconds
        t += 65_000L

        // Switch 3 attempt within the 5-minute window -> Budget must be exhausted (max 2 switches)
        policy.onNetworkFailure("Err", t)
        policy.evaluateFallback(VoxAdaptiveNetworkEnginePolicy.Engine.CRONET, true, t)
        policy.onNetworkFailure("Err", t)
        val s3 = policy.evaluateFallback(VoxAdaptiveNetworkEnginePolicy.Engine.CRONET, true, t)
        assertTrue(s3 is VoxAdaptiveNetworkEnginePolicy.SwitchDecision.BudgetExhausted)
    }
}
