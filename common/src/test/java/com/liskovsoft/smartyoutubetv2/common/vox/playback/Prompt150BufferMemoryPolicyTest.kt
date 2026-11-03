package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxPerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Gate C Buffering & Memory Invariant Tests.
 * PROMPT №150.
 *
 * Verifies code contracts and invariants of VoxPlaybackBufferPolicy:
 * - Monotonicity: minBufferMs <= maxBufferMs
 * - Rebuffer hysteresis: bufferForPlaybackMs <= bufferForPlaybackAfterRebufferMs
 * - Strict clamping of targetBufferBytes across RAM tiers (preventing OOM)
 * - Synthetic traces modeling memory footprints across resolution and bitrates.
 */
@RunWith(RobolectricTestRunner::class)
class Prompt150BufferMemoryPolicyTest {

    private val RAM_1GB = 1L * 1024 * 1024 * 1024
    private val RAM_2GB = 2L * 1024 * 1024 * 1024
    private val RAM_4GB = 4L * 1024 * 1024 * 1024

    @Test
    fun testLowRamTier_EnforcesConservativeBounds() {
        val cfg = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.BASIC,
            ramBytes = RAM_1GB,
            isLive = false,
            isLocal = false,
            videoHeight = 1080
        )

        assertEquals(VoxBufferProfile.CONSERVATIVE, cfg.profile)
        assertEquals(15_000, cfg.minBufferMs)
        assertEquals(25_000, cfg.maxBufferMs)
        assertEquals(2_000, cfg.bufferForPlaybackMs)
        assertEquals(3_500, cfg.bufferForPlaybackAfterRebufferMs)
        assertEquals(5_000, cfg.backBufferMs)
        // targetBufferBytes must be clamped to max 32MB on low RAM
        assertTrue("targetBufferBytes must be <= 32MB", cfg.targetBufferBytes <= 32 * 1024 * 1024)
        assertTrue("targetBufferBytes must be >= 20MB", cfg.targetBufferBytes >= 20 * 1024 * 1024)
    }

    @Test
    fun testStandardRamTier_EnforcesBalancedBounds() {
        val cfg = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.STANDARD,
            ramBytes = RAM_2GB,
            isLive = false,
            isLocal = false,
            videoHeight = 1080
        )

        assertEquals(VoxBufferProfile.BALANCED, cfg.profile)
        assertEquals(25_000, cfg.minBufferMs)
        assertEquals(40_000, cfg.maxBufferMs)
        assertEquals(2_500, cfg.bufferForPlaybackMs)
        assertEquals(4_500, cfg.bufferForPlaybackAfterRebufferMs)
        assertEquals(15_000, cfg.backBufferMs)
        assertTrue("targetBufferBytes must be <= 64MB", cfg.targetBufferBytes <= 64 * 1024 * 1024)
        assertTrue("targetBufferBytes must be >= 20MB", cfg.targetBufferBytes >= 20 * 1024 * 1024)
    }

    @Test
    fun testPowerfulRamTier_EnforcesHighPerformanceBounds() {
        val cfg = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.POWERFUL,
            ramBytes = RAM_4GB,
            isLive = false,
            isLocal = false,
            videoHeight = 2160
        )

        assertEquals(VoxBufferProfile.HIGH_PERFORMANCE, cfg.profile)
        assertEquals(35_000, cfg.minBufferMs)
        assertEquals(60_000, cfg.maxBufferMs)
        assertEquals(2_000, cfg.bufferForPlaybackMs)
        assertEquals(5_000, cfg.bufferForPlaybackAfterRebufferMs)
        assertEquals(30_000, cfg.backBufferMs)
        // Target buffer bytes capped at 96MB
        assertTrue("targetBufferBytes must be <= 96MB", cfg.targetBufferBytes <= 96 * 1024 * 1024)
        assertTrue("targetBufferBytes must be >= 48MB", cfg.targetBufferBytes >= 48 * 1024 * 1024)
    }

    @Test
    fun testLiveStream_EnforcesBalancedZeroBackBuffer() {
        val cfg = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.STANDARD,
            ramBytes = RAM_2GB,
            isLive = true,
            isLocal = false,
            videoHeight = 1080
        )

        assertEquals(VoxBufferProfile.BALANCED, cfg.profile)
        assertEquals(VoxPlaybackBufferPolicy.LIVE_MIN_BUFFER_MS, cfg.minBufferMs)
        assertEquals(VoxPlaybackBufferPolicy.LIVE_MAX_BUFFER_MS, cfg.maxBufferMs)
        assertEquals(0, cfg.backBufferMs) // Zero backBuffer to prevent live drift
        assertEquals(VoxPlaybackBufferPolicy.LIVE_TARGET_BUFFER_BYTES, cfg.targetBufferBytes)
    }

    @Test
    fun testLocalPlayback_EnforcesFastStartCompactBuffer() {
        val cfg = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.STANDARD,
            ramBytes = RAM_2GB,
            isLive = false,
            isLocal = true,
            videoHeight = 1080
        )

        assertEquals(VoxBufferProfile.BALANCED, cfg.profile)
        assertEquals(8_000, cfg.minBufferMs)
        assertEquals(15_000, cfg.maxBufferMs)
        assertEquals(800, cfg.bufferForPlaybackMs) // Ultra-fast disk start
        assertEquals(1_500, cfg.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun testAdaptiveHighBitrate4K_EnforcesHardMemoryCeiling() {
        val cfg = VoxPlaybackBufferPolicy.resolveAdaptive(
            tier = VoxPerformanceTier.POWERFUL,
            ramBytes = RAM_4GB,
            isLive = false,
            isLocal = false,
            videoHeight = 2160,
            bitrate = 45_000_000L, // 45 Mbps
            rebufferCount = 2
        )

        assertEquals(VoxBufferProfile.HIGH_PERFORMANCE, cfg.profile)
        assertTrue("Min buffer must be boosted", cfg.minBufferMs >= 35_000)
        assertTrue("Max buffer must be boosted", cfg.maxBufferMs >= 60_000)
        // Hard OOM safety ceiling: targetBufferBytes must never exceed 96 MB
        assertEquals(96 * 1024 * 1024, cfg.targetBufferBytes)
    }

    @Test
    fun testBufferInvariants_HoldAcrossAllTiers() {
        val tiers = listOf(VoxPerformanceTier.BASIC, VoxPerformanceTier.STANDARD, VoxPerformanceTier.POWERFUL)
        val rams = listOf(512L * 1024 * 1024, RAM_1GB, RAM_2GB, RAM_4GB)
        val heights = listOf(720, 1080, 1440, 2160)

        for (tier in tiers) {
            for (ram in rams) {
                for (h in heights) {
                    val cfg = VoxPlaybackBufferPolicy.resolve(tier, ram, false, false, h)
                    // Invariant 1: Monotonicity
                    assertTrue("minBufferMs (${cfg.minBufferMs}) must be <= maxBufferMs (${cfg.maxBufferMs})",
                        cfg.minBufferMs <= cfg.maxBufferMs)
                    // Invariant 2: Rebuffer Hysteresis
                    assertTrue("bufferForPlaybackMs (${cfg.bufferForPlaybackMs}) must be <= bufferForPlaybackAfterRebufferMs (${cfg.bufferForPlaybackAfterRebufferMs})",
                        cfg.bufferForPlaybackMs <= cfg.bufferForPlaybackAfterRebufferMs)
                    // Invariant 3: Positive values
                    assertTrue(cfg.minBufferMs > 0)
                    assertTrue(cfg.maxBufferMs > 0)
                    assertTrue(cfg.bufferForPlaybackMs > 0)
                    assertTrue(cfg.bufferForPlaybackAfterRebufferMs > 0)
                    assertTrue(cfg.targetBufferBytes >= 20 * 1024 * 1024)
                    assertTrue(cfg.targetBufferBytes <= 96 * 1024 * 1024)
                }
            }
        }
    }
}
