package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoxTranslationStateMachineTest {

    private lateinit var stateMachine: VoxTranslationStateMachine

    @Before
    fun setUp() {
        stateMachine = VoxTranslationStateMachine()
    }

    @Test
    fun testInitialState() {
        assertEquals(VoxTranslationStateMachine.State.IDLE, stateMachine.currentState)
        assertEquals(VoxTranslationStateMachine.ReuseSource.NONE, stateMachine.reuseSource)
        assertEquals(0L, stateMachine.totalTranslationStartupMs)
        assertEquals(0, stateMachine.getCountdownSec(1000L))
        assertFalse(stateMachine.isEtaKnown())
    }

    @Test
    fun testMonotonicallyNonIncreasingCountdown() {
        val t0 = 10_000L

        // Initial ETA from backend = 25s
        val eta1 = stateMachine.reconcileEtaSec(25, t0)
        assertEquals(25, eta1)
        assertTrue(stateMachine.isEtaKnown())

        // 5 seconds pass -> remaining should be 20s
        val t1 = t0 + 5_000L
        val countdown1 = stateMachine.getCountdownSec(t1)
        assertEquals(20, countdown1)

        // Backend tries to report an increased ETA (e.g. 35s, caused by server re-queueing)
        // Machine MUST NOT jump up to 35s; it must clamp to monotonically non-increasing value
        val etaClamped = stateMachine.reconcileEtaSec(35, t1)
        assertEquals(20, etaClamped)

        // Further down: backend reports 12s, which is lower than current 20s -> accepted
        val etaLower = stateMachine.reconcileEtaSec(12, t1)
        assertEquals(12, etaLower)

        // Zero remaining terminates countdown
        val etaZero = stateMachine.reconcileEtaSec(0, t1 + 1000L)
        assertEquals(0, etaZero)
        assertFalse(stateMachine.isEtaKnown())
    }

    @Test
    fun testStageDurationProfiling() {
        val t0 = 1000L
        stateMachine.transitionTo(VoxTranslationStateMachine.State.LOOKUP, t0)
        assertEquals(VoxTranslationStateMachine.State.LOOKUP, stateMachine.currentState)

        val t1 = t0 + 150L
        stateMachine.transitionTo(VoxTranslationStateMachine.State.REQUESTING, t1)
        assertEquals(150L, stateMachine.lookupElapsedMs)

        val t2 = t1 + 300L
        stateMachine.transitionTo(VoxTranslationStateMachine.State.WAITING_PROVIDER, t2)
        assertEquals(300L, stateMachine.requestElapsedMs)

        val t3 = t2 + 2500L
        stateMachine.transitionTo(VoxTranslationStateMachine.State.PROCESSING, t3)
        assertEquals(2500L, stateMachine.providerWaitElapsedMs)

        val t4 = t3 + 1200L
        stateMachine.transitionTo(VoxTranslationStateMachine.State.READY, t4)
        assertEquals(1200L, stateMachine.processingElapsedMs)

        assertEquals(t4 - t0, stateMachine.totalTranslationStartupMs)
    }

    @Test
    fun testReuseSourceAndDiagnosticsContext() {
        stateMachine.markLookupReady(VoxTranslationStateMachine.ReuseSource.MEMORY_CACHE, 2000L)
        assertEquals(VoxTranslationStateMachine.State.LOOKUP_READY, stateMachine.currentState)
        assertEquals(VoxTranslationStateMachine.ReuseSource.MEMORY_CACHE, stateMachine.reuseSource)

        val ctx = stateMachine.toDiagnosticsContext()
        assertEquals("LOOKUP_READY", ctx["state"])
        assertEquals("MEMORY_CACHE", ctx["reuseSource"])
        assertEquals("true", ctx["isReused"])
    }

    @Test
    fun testTranslationDiagnosticsSummary() {
        stateMachine.markLookupReady(VoxTranslationStateMachine.ReuseSource.BACKEND_READY_LOOKUP, 1000L)
        val summary = stateMachine.getDiagnosticsSummary()
        assertEquals(VoxTranslationStateMachine.TranslationSource.BACKEND_READY.label, summary["translationSource"])
        assertTrue((summary["translationLookupMs"] as Long) >= 0L)
        assertTrue(summary.containsKey("translationWaitMs"))
        assertTrue(summary.containsKey("translationAttachMs"))
        assertTrue(summary.containsKey("translationTotalMs"))
    }

    @Test
    fun testFallbackStageResetAndTimeoutGuard() {
        val t0 = 1000L
        stateMachine.transitionTo(VoxTranslationStateMachine.State.PROCESSING, t0)
        assertEquals(VoxTranslationStateMachine.State.PROCESSING, stateMachine.currentState)

        // Fallback recorded
        assertTrue(stateMachine.canAttemptFallback())
        assertTrue(stateMachine.recordFallbackAttempt("NEW_BACKEND_HTTP_400"))
        assertFalse(stateMachine.canAttemptFallback())

        // Fallback transitions stage to WAITING_PROVIDER and resets stage transition timer
        val t1 = t0 + 20_000L
        stateMachine.transitionTo(VoxTranslationStateMachine.State.WAITING_PROVIDER, t1)
        assertEquals(VoxTranslationStateMachine.State.WAITING_PROVIDER, stateMachine.currentState)

        // Verify stage is not stalled at t1 + 30s (total 50s from t0, but only 30s from t1)
        assertFalse(stateMachine.isStageStalled(t1 + 30_000L, 45_000L))
        // Verify stage stalls only after full timeout from t1
        assertTrue(stateMachine.isStageStalled(t1 + 46_000L, 45_000L))
    }
}
