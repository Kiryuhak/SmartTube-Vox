package com.liskovsoft.smartyoutubetv2.common.vox.external

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
class VoxExternalLaunchManagerTest {

    private class FakeContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private lateinit var simulatedClock: AtomicLong
    private lateinit var manager: VoxExternalLaunchManager

    @Before
    fun setUp() {
        simulatedClock = AtomicLong(100_000L)
        manager = VoxExternalLaunchManager(FakeContext()) { simulatedClock.get() }
    }

    @Test
    fun testDuplicateSuppressionSameVideoAndTimestamp() {
        val req1 = VoxExternalVideoRequest(
            videoId = "dQw4w9WgXcQ",
            playlistId = null,
            timeMs = 45_000L,
            clientIp = "192.168.1.50",
            timestampMs = simulatedClock.get()
        )

        // First launch: must not be duplicate
        assertFalse(manager.isDuplicate(req1.videoId, req1.timeMs))
        assertTrue(manager.handleVideoLaunch(req1, dispatchPlayback = false))

        // Same launch 1 second later (within 3s window): isDuplicate must be true
        simulatedClock.addAndGet(1000L)
        assertTrue(manager.isDuplicate("dQw4w9WgXcQ", 45_000L))

        // handleVideoLaunch accepts duplicate gracefully (returns true for DIAL 201 Created)
        assertTrue(manager.handleVideoLaunch(req1, dispatchPlayback = false))
    }

    @Test
    fun testDifferentTimestampAllowedEvenWithinWindow() {
        val req1 = VoxExternalVideoRequest(
            videoId = "dQw4w9WgXcQ",
            playlistId = null,
            timeMs = 10_000L,
            clientIp = "192.168.1.50",
            timestampMs = simulatedClock.get()
        )
        assertTrue(manager.handleVideoLaunch(req1, dispatchPlayback = false))

        // 500ms later, user sends same video but timeMs = 90_000L (position diff > 5000ms)
        simulatedClock.addAndGet(500L)
        assertFalse(manager.isDuplicate("dQw4w9WgXcQ", 90_000L))

        val req2 = VoxExternalVideoRequest(
            videoId = "dQw4w9WgXcQ",
            playlistId = null,
            timeMs = 90_000L,
            clientIp = "192.168.1.50",
            timestampMs = simulatedClock.get()
        )
        assertTrue(manager.handleVideoLaunch(req2, dispatchPlayback = false))
    }

    @Test
    fun testDifferentVideoAllowedImmediately() {
        val req1 = VoxExternalVideoRequest(
            videoId = "dQw4w9WgXcQ",
            playlistId = null,
            timeMs = 0L,
            clientIp = "192.168.1.50",
            timestampMs = simulatedClock.get()
        )
        assertTrue(manager.handleVideoLaunch(req1, dispatchPlayback = false))

        // 100ms later, completely different video ID
        simulatedClock.addAndGet(100L)
        assertFalse(manager.isDuplicate("jNQXAC9IVRw", 0L))

        val req2 = VoxExternalVideoRequest(
            videoId = "jNQXAC9IVRw",
            playlistId = null,
            timeMs = 0L,
            clientIp = "192.168.1.50",
            timestampMs = simulatedClock.get()
        )
        assertTrue(manager.handleVideoLaunch(req2, dispatchPlayback = false))
    }

    @Test
    fun testSameVideoAllowedAfterWindowExpires() {
        val req1 = VoxExternalVideoRequest(
            videoId = "dQw4w9WgXcQ",
            playlistId = null,
            timeMs = 0L,
            clientIp = "192.168.1.50",
            timestampMs = simulatedClock.get()
        )
        assertTrue(manager.handleVideoLaunch(req1, dispatchPlayback = false))

        // Advance clock past 3 seconds (e.g. 3500ms)
        simulatedClock.addAndGet(3500L)
        assertFalse(manager.isDuplicate("dQw4w9WgXcQ", 0L))
    }

    @Test
    fun testZeroTimestampHandling() {
        val req = VoxExternalVideoRequest(
            videoId = "dQw4w9WgXcQ",
            playlistId = null,
            timeMs = 0L,
            clientIp = "192.168.1.50",
            timestampMs = simulatedClock.get()
        )
        assertTrue(manager.handleVideoLaunch(req, dispatchPlayback = false))
        assertEquals(0L, req.timeMs)
    }
}
