package com.liskovsoft.smartyoutubetv2.common.misc

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NetworkBackendSwitchPolicyTest {

    private lateinit var policy: NetworkEngineRecoveryPolicy
    private val engines = intArrayOf(2, 0, 1) // Auto/Default, Cronet, OkHttp

    @Before
    fun setUp() {
        policy = NetworkEngineRecoveryPolicy()
    }

    @Test
    fun firstFailureDoesNotImmediatelySwitchEngine() {
        val currentEngine = 2
        // On first failure, policy attempts in-place reconnect without switching engine
        val candidate = policy.selectNextEngine(currentEngine, engines, 1000L)
        assertEquals(currentEngine, candidate)
    }

    @Test
    fun repeatedFailureWithinShortWindowSwitchesEngine() {
        val currentEngine = 2
        policy.selectNextEngine(currentEngine, engines, 1000L)
        val switchedEngine = policy.selectNextEngine(currentEngine, engines, 2000L)
        assertNotEquals(currentEngine, switchedEngine)
    }

    @Test
    fun respectsCooldownPeriodBeforeSwitchingAgain() {
        val engine1 = 2
        policy.selectNextEngine(engine1, engines, 1000L)
        val engine2 = policy.selectNextEngine(engine1, engines, 2000L) // switches to 0

        // If another failure occurs within grace period (60s), stay on engine2
        val candidateDuringGrace = policy.selectNextEngine(engine2, engines, 30_000L)
        assertEquals(engine2, candidateDuringGrace)
    }

    @Test
    fun playbackProgressResetsTransientState() {
        val currentEngine = 2
        policy.selectNextEngine(currentEngine, engines, 1000L)
        policy.onPlaybackProgress()

        // After playback progresses, the next failure is treated as transient again
        val next = policy.selectNextEngine(currentEngine, engines, 5000L)
        assertEquals(currentEngine, next)
    }
}
