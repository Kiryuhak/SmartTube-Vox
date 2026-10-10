package com.liskovsoft.smartyoutubetv2.common.misc

import org.junit.Assert.*
import org.junit.Test

class NetworkRecoveryPolicyTest {

    @Test
    fun preventsImmediateSwitchAndRespectsCooldown() {
        val policy = NetworkEngineRecoveryPolicy()
        val engines = intArrayOf(2, 0, 1)

        // First attempt reconnects
        assertEquals(2, policy.selectNextEngine(2, engines, 0L))
        // Second attempt switches
        val next = policy.selectNextEngine(2, engines, 1000L)
        assertNotEquals(2, next)

        // Inside grace period stays on switched engine
        assertEquals(next, policy.selectNextEngine(next, engines, 30_000L))
    }
}
