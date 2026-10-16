package com.liskovsoft.smartyoutubetv2.common.vox.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoxPlayerTrackerTest {

    @Before
    fun setUp() {
        VoxPlayerTracker.reset()
    }

    @Test
    fun testPlayerSingleInstanceInvariant() {
        assertEquals(0, VoxPlayerTracker.getActivePlayerInstances())
        assertTrue(VoxPlayerTracker.isSingleInstanceInvariantSatisfied())

        // Player created
        VoxPlayerTracker.onPlayerCreated()
        assertEquals(1, VoxPlayerTracker.getActivePlayerInstances())
        assertTrue(VoxPlayerTracker.isSingleInstanceInvariantSatisfied())

        // Normal player swap: release old then create new
        VoxPlayerTracker.onPlayerReleased()
        assertEquals(0, VoxPlayerTracker.getActivePlayerInstances())
        VoxPlayerTracker.onPlayerCreated()
        assertEquals(1, VoxPlayerTracker.getActivePlayerInstances())
        assertTrue(VoxPlayerTracker.isSingleInstanceInvariantSatisfied())

        // Player released
        VoxPlayerTracker.onPlayerReleased()
        assertEquals(0, VoxPlayerTracker.getActivePlayerInstances())
        assertTrue(VoxPlayerTracker.isSingleInstanceInvariantSatisfied())

        // Clamping check: extra release doesn't make negative
        VoxPlayerTracker.onPlayerReleased()
        assertEquals(0, VoxPlayerTracker.getActivePlayerInstances())
    }

    @Test
    fun testRecreationTracking() {
        assertEquals(0, VoxPlayerTracker.getRecreationCount())
        VoxPlayerTracker.onPlayerRecreated()
        assertEquals(1, VoxPlayerTracker.getRecreationCount())
        VoxPlayerTracker.onPlayerRecreated()
        assertEquals(2, VoxPlayerTracker.getRecreationCount())
    }

    @Test
    fun testBoundedOomRecoveryPolicy() {
        // Test bounded recovery simulation:
        // Max 1 recovery attempt allowed per video playback session.
        var oomRecoveryAttempts = 0
        val maxOomAttempts = 1

        fun handleOom(): Boolean {
            if (oomRecoveryAttempts < maxOomAttempts) {
                oomRecoveryAttempts++
                VoxPlayerTracker.onPlayerRecreated()
                return true // Attempted recovery
            } else {
                return false // Exhausted, stop playback
            }
        }

        // First OOM error in session
        val firstAttemptResult = handleOom()
        assertTrue("First OOM error must trigger bounded recovery", firstAttemptResult)
        assertEquals(1, oomRecoveryAttempts)
        assertEquals(1, VoxPlayerTracker.getRecreationCount())

        // Second OOM error in same session
        val secondAttemptResult = handleOom()
        assertFalse("Second OOM error in same session must be rejected (exhausted)", secondAttemptResult)
        assertEquals(1, oomRecoveryAttempts)
        assertEquals(1, VoxPlayerTracker.getRecreationCount())

        // New video reset
        oomRecoveryAttempts = 0
        val newVideoAttemptResult = handleOom()
        assertTrue("New video resets bounded OOM recovery limit", newVideoAttemptResult)
        assertEquals(1, oomRecoveryAttempts)
        assertEquals(2, VoxPlayerTracker.getRecreationCount())
    }
}
