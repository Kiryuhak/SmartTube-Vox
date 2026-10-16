package com.liskovsoft.smartyoutubetv2.common.vox.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Repeatable lifecycle stress suite verifying player instance bounding,
 * listener detachment, controller cleanup, and heap trend stability across 30-50 cycles.
 */
class VoxPlayerLifecycleStressTest {

    interface SimulatedPlayerListener {
        fun onPlayerStateChanged(state: Int)
    }

    class SimulatedExoPlayer {
        var isReleased = false
            private set
        val listeners = CopyOnWriteArrayList<SimulatedPlayerListener>()
        var surfaceRef: Any? = Any()

        fun addListener(l: SimulatedPlayerListener) {
            listeners.add(l)
        }

        fun removeListener(l: SimulatedPlayerListener) {
            listeners.remove(l)
        }

        fun clearVideoSurface() {
            surfaceRef = null
        }

        fun release() {
            listeners.clear()
            clearVideoSurface()
            isReleased = true
        }
    }

    class SimulatedPlayerController : SimulatedPlayerListener {
        var player: SimulatedExoPlayer? = null
            private set

        fun setPlayer(newPlayer: SimulatedExoPlayer?) {
            if (player != null && player !== newPlayer) {
                releasePlayer()
            }
            player = newPlayer
            if (newPlayer != null) {
                VoxPlayerTracker.onPlayerCreated()
                newPlayer.addListener(this)
            }
        }

        fun releasePlayer() {
            val p = player
            if (p != null) {
                p.removeListener(this)
                p.release()
                player = null
                VoxPlayerTracker.onPlayerReleased()
            }
        }

        override fun onPlayerStateChanged(state: Int) {}
    }

    @Before
    fun setUp() {
        VoxPlayerTracker.reset()
    }

    @Test
    fun testPlayerLifecycleStressAndHeapTrend() {
        val controller = SimulatedPlayerController()
        val cycles = 50

        System.gc()
        val rt = Runtime.getRuntime()
        val heapBefore = rt.totalMemory() - rt.freeMemory()

        var heapAfter10 = 0L
        var heapAfter30 = 0L
        var heapAfter50 = 0L

        for (i in 1..cycles) {
            // 1. Create player
            val player = SimulatedExoPlayer()
            controller.setPlayer(player)

            // Invariant: active players == 1
            assertEquals(1, VoxPlayerTracker.getActivePlayerInstances())
            assertTrue(VoxPlayerTracker.isSingleInstanceInvariantSatisfied())
            assertEquals(1, player.listeners.size)
            assertTrue(player.surfaceRef != null)

            // 2. Engine switch / recreation roundtrip
            VoxPlayerTracker.onPlayerRecreated()
            val newPlayer = SimulatedExoPlayer()
            controller.setPlayer(newPlayer)

            // Old player must be released, single instance preserved
            assertTrue("Old player instance must be released", player.isReleased)
            assertEquals("Old player listeners must be detached", 0, player.listeners.size)
            assertTrue("Old player surface reference must be cleared", player.surfaceRef == null)
            assertEquals(1, VoxPlayerTracker.getActivePlayerInstances())
            assertTrue(VoxPlayerTracker.isSingleInstanceInvariantSatisfied())

            // 3. Close / Release
            controller.releasePlayer()
            assertTrue(newPlayer.isReleased)
            assertEquals(0, VoxPlayerTracker.getActivePlayerInstances())
            assertTrue(VoxPlayerTracker.isSingleInstanceInvariantSatisfied())

            if (i == 10) {
                heapAfter10 = rt.totalMemory() - rt.freeMemory()
            } else if (i == 30) {
                heapAfter30 = rt.totalMemory() - rt.freeMemory()
            } else if (i == 50) {
                heapAfter50 = rt.totalMemory() - rt.freeMemory()
            }
        }

        System.gc()
        val heapAfterGc = rt.totalMemory() - rt.freeMemory()

        // Assert invariant throughout stress test
        assertEquals(0, VoxPlayerTracker.getActivePlayerInstances())
        assertEquals(cycles, VoxPlayerTracker.getRecreationCount())
        assertTrue(VoxPlayerTracker.isSingleInstanceInvariantSatisfied())

        println("Memory trend: before=${heapBefore / 1024}KB, after10=${heapAfter10 / 1024}KB, after30=${heapAfter30 / 1024}KB, after50=${heapAfter50 / 1024}KB, afterGC=${heapAfterGc / 1024}KB")
    }
}
