package com.liskovsoft.smartyoutubetv2.common.vox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for VoxSmartDuckingPolicy verifying 1:1 mathematical compliance with
 * userscript 1.11.17 ducking.ts & smartDuckingRuntime.ts.
 */
class VoxSmartDuckingPolicyTest {

    private lateinit var policy: VoxSmartDuckingPolicy

    @Before
    fun setUp() {
        policy = VoxSmartDuckingPolicy()
    }

    @Test
    fun testInitialState_isIdleAndMatchesBaseline() {
        val state = policy.initState(0.85f)
        assertEquals(0.85f, state.baseline, 0.001f)
        assertFalse(state.isDucked)
        assertFalse(state.speechGateOpen)
        assertEquals(0f, state.rmsEnvelope, 0.001f)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.IDLE, state.duckingState)
    }

    @Test
    fun testSilence_keepsGateClosedAndVolumeAtBaseline() {
        var state = policy.initState(1.0f)
        val now = 1000L

        val input = VoxSmartDuckingPolicy.Input(
            nowMs = now,
            translationActive = true,
            autoVolumeEnabled = true,
            smartDuckingEnabled = true,
            audioIsPlaying = true,
            hostVideoActive = true,
            currentVideoVolume = 1.0f,
            volumeOnStart = 1.0f,
            duckingTarget01 = 0.15f,
            rms = 0.002f, // well below thresholdOnRms (0.012)
            hasRms = true
        )

        val decision = policy.evaluateStep(input, state)
        assertTrue(decision is VoxSmartDuckingPolicy.Decision.Noop)
        state = decision.newState
        assertFalse(state.speechGateOpen)
        assertFalse(state.isDucked)
        assertEquals(1.0f, state.baseline, 0.001f)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.IDLE, state.duckingState)
    }

    @Test
    fun testSpeechDetection_opensGateAndSmoothsTowardsTarget() {
        var state = policy.initState(1.0f)
        var time = 1000L

        // Step 1: Speech detected with RMS = 0.04 (exceeds thresholdOn 0.012)
        val input = VoxSmartDuckingPolicy.Input(
            nowMs = time,
            translationActive = true,
            autoVolumeEnabled = true,
            smartDuckingEnabled = true,
            audioIsPlaying = true,
            hostVideoActive = true,
            currentVideoVolume = 1.0f,
            volumeOnStart = 1.0f,
            duckingTarget01 = 0.15f,
            rms = 0.04f,
            hasRms = true
        )

        var decision = policy.evaluateStep(input, state)
        assertTrue(decision is VoxSmartDuckingPolicy.Decision.Apply)
        val apply1 = decision as VoxSmartDuckingPolicy.Decision.Apply
        state = apply1.newState

        assertTrue(state.speechGateOpen)
        assertTrue(state.isDucked)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.ATTACK, state.duckingState)
        // Volume must decrease downwards towards 0.15
        assertTrue(apply1.targetVolume < 1.0f)
        assertTrue(apply1.targetVolume >= 0.15f)

        // Step 2: Progress through multiple 50ms ticks until volume settles at 0.15
        var currentVol = apply1.targetVolume
        for (i in 1..20) {
            time += 50L
            val stepInput = input.copy(nowMs = time, currentVideoVolume = currentVol, rms = 0.035f)
            decision = policy.evaluateStep(stepInput, state)
            state = decision.newState
            if (decision is VoxSmartDuckingPolicy.Decision.Apply) {
                currentVol = decision.targetVolume
            }
        }

        // Settles at ducked target (0.15)
        assertEquals(0.15f, currentVol, 0.02f)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.DUCKED, state.duckingState)
    }

    @Test
    fun testSpeechGateHysteresis() {
        var state = policy.initState(1.0f)
        var time = 1000L

        // 1. RMS at 0.010 (between off 0.009 and on 0.012) -> should NOT open gate
        val inputBelowOn = VoxSmartDuckingPolicy.Input(
            nowMs = time,
            translationActive = true,
            autoVolumeEnabled = true,
            smartDuckingEnabled = true,
            audioIsPlaying = true,
            hostVideoActive = true,
            currentVideoVolume = 1.0f,
            volumeOnStart = 1.0f,
            duckingTarget01 = 0.15f,
            rms = 0.010f,
            hasRms = true
        )
        var decision = policy.evaluateStep(inputBelowOn, state)
        state = decision.newState
        assertFalse(state.speechGateOpen)

        // 2. RMS at 0.020 (above 0.012) -> opens gate
        time += 50L
        val inputAboveOn = inputBelowOn.copy(nowMs = time, rms = 0.020f)
        decision = policy.evaluateStep(inputAboveOn, state)
        state = decision.newState
        assertTrue(state.speechGateOpen)

        // 3. RMS drops to 0.010 (between off 0.009 and on 0.012) -> should STAY open (hysteresis)
        time += 50L
        val inputInHysteresis = inputBelowOn.copy(nowMs = time, rms = 0.010f)
        decision = policy.evaluateStep(inputInHysteresis, state)
        state = decision.newState
        assertTrue(state.speechGateOpen)

        // 4. RMS drops to 0.005 (below 0.009) -> starts hold timer
        time += 50L
        val inputBelowOff = inputBelowOn.copy(nowMs = time, rms = 0.005f)
        decision = policy.evaluateStep(inputBelowOff, state)
        state = decision.newState
        // Within hold period (50ms < 520ms), speechGateOpen remains true
        assertTrue(state.speechGateOpen)
    }

    @Test
    fun testHoldDuration_protectsAgainstPumpingDuringBriefPauses() {
        var state = policy.initState(1.0f)
        var time = 1000L

        // Open gate with speech
        val speechInput = VoxSmartDuckingPolicy.Input(
            nowMs = time,
            translationActive = true,
            autoVolumeEnabled = true,
            smartDuckingEnabled = true,
            audioIsPlaying = true,
            hostVideoActive = true,
            currentVideoVolume = 1.0f,
            volumeOnStart = 1.0f,
            duckingTarget01 = 0.15f,
            rms = 0.03f,
            hasRms = true
        )
        var decision = policy.evaluateStep(speechInput, state)
        state = decision.newState
        assertTrue(state.speechGateOpen)

        // Pause speech (RMS = 0.001) for 400ms (< 520ms hold)
        for (i in 1..8) {
            time += 50L
            val silenceInput = speechInput.copy(nowMs = time, currentVideoVolume = 0.15f, rms = 0.001f)
            decision = policy.evaluateStep(silenceInput, state)
            state = decision.newState
            assertTrue("Gate must remain open at time delta " + (i * 50) + "ms", state.speechGateOpen)
        }

        // Advance past 520ms hold (e.g. 600ms total silence)
        time += 200L
        val expiredInput = speechInput.copy(nowMs = time, currentVideoVolume = 0.15f, rms = 0.001f)
        decision = policy.evaluateStep(expiredInput, state)
        state = decision.newState
        assertFalse("Gate must close after holdMs", state.speechGateOpen)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.RELEASE, state.duckingState)
    }

    @Test
    fun testSlewRateLimits_downwardAndUpward() {
        val dtSec = 0.05f // 50ms

        // Downward max delta = 3.5 * 0.05 = 0.175
        val downSmooth = policy.smoothVolumeChange(0.0f, 1.0f, 50L, dtSec)
        assertTrue(1.0f - downSmooth <= 0.1751f)

        // Upward max delta = 0.9 * 0.05 = 0.045
        val upSmooth = policy.smoothVolumeChange(1.0f, 0.15f, 50L, dtSec)
        assertTrue(upSmooth - 0.15f <= 0.0451f)
    }

    @Test
    fun testManualVolumeOverride_updatesBaselineWhenDeltaExceedsThreshold() {
        var state = policy.initState(1.0f)
        state = state.copy(isDucked = true, lastApplied = 0.15f)

        // 1. Minor fluctuation (delta = 0.01 <= 0.02) does NOT change baseline
        val baseMinor = policy.resolveBaseline(state, 0.16f, 1.0f)
        assertEquals(1.0f, baseMinor, 0.001f)

        // 2. User remote volume change (delta = 0.25 > 0.02) updates baseline
        val baseRemote = policy.resolveBaseline(state, 0.40f, 1.0f)
        assertEquals(0.40f, baseRemote, 0.001f)
    }

    @Test
    fun testFixedMode_keepsGateOpenAndDucksToMinBaselineAndTarget() {
        val state = policy.initState(0.80f)
        val input = VoxSmartDuckingPolicy.Input(
            nowMs = 1000L,
            translationActive = true,
            autoVolumeEnabled = true,
            smartDuckingEnabled = false, // Fixed Auto Volume
            audioIsPlaying = true,
            hostVideoActive = true,
            currentVideoVolume = 0.80f,
            volumeOnStart = 0.80f,
            duckingTarget01 = 0.15f,
            rms = 0.0f, // Silence
            hasRms = false
        )

        val decision = policy.evaluateStep(input, state)
        assertTrue(decision is VoxSmartDuckingPolicy.Decision.Apply)
        val apply = decision as VoxSmartDuckingPolicy.Decision.Apply
        assertTrue(apply.newState.speechGateOpen)
        assertTrue(apply.targetVolume < 0.80f)
    }

    @Test
    fun testStop_restoresBaselineVolume() {
        var state = policy.initState(0.90f)
        state = state.copy(isDucked = true, lastApplied = 0.15f)

        val inputStopped = VoxSmartDuckingPolicy.Input(
            nowMs = 1000L,
            translationActive = false, // Stopped
            autoVolumeEnabled = true,
            smartDuckingEnabled = true,
            audioIsPlaying = false,
            hostVideoActive = true,
            currentVideoVolume = 0.15f,
            volumeOnStart = 0.90f,
            duckingTarget01 = 0.15f,
            rms = 0f,
            hasRms = false
        )

        val decision = policy.evaluateStep(inputStopped, state)
        assertTrue(decision is VoxSmartDuckingPolicy.Decision.Stop)
        val stop = decision as VoxSmartDuckingPolicy.Decision.Stop
        assertEquals(0.90f, stop.restoreVolume, 0.001f)
        assertFalse(stop.newState.isDucked)
        assertFalse(stop.newState.speechGateOpen)
    }
}
