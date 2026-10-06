package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxPerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxPlaybackBufferPolicyTest {

    @Test
    fun testLiveStreamConfiguration() {
        val config = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.POWERFUL,
            ramBytes = 4L * 1024 * 1024 * 1024,
            isLive = true,
            isLocal = false
        )

        assertEquals(VoxBufferProfile.BALANCED, config.profile)
        assertEquals(15_000, config.minBufferMs)
        assertEquals(30_000, config.maxBufferMs)
        assertEquals(2_000, config.bufferForPlaybackMs)
        assertEquals(4_000, config.bufferForPlaybackAfterRebufferMs)
        assertEquals(0, config.backBufferMs)
        assertEquals(32 * 1024 * 1024, config.targetBufferBytes)
    }

    @Test
    fun testLocalPlaybackConfiguration() {
        val config = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.STANDARD,
            ramBytes = 3L * 1024 * 1024 * 1024,
            isLive = false,
            isLocal = true
        )

        assertEquals(VoxBufferProfile.BALANCED, config.profile)
        assertEquals(8_000, config.minBufferMs)
        assertEquals(15_000, config.maxBufferMs)
        assertEquals(800, config.bufferForPlaybackMs)
        assertEquals(1_500, config.bufferForPlaybackAfterRebufferMs)
        assertEquals(10_000, config.backBufferMs)
        assertTrue(config.targetBufferBytes in (20 * 1024 * 1024)..(32 * 1024 * 1024))
    }

    @Test
    fun testHighPerformanceTierNetworkVod() {
        val config = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.POWERFUL,
            ramBytes = 4L * 1024 * 1024 * 1024,
            isLive = false,
            isLocal = false,
            videoHeight = 2160
        )

        assertEquals(VoxBufferProfile.HIGH_PERFORMANCE, config.profile)
        assertEquals(35_000, config.minBufferMs)
        assertEquals(60_000, config.maxBufferMs)
        assertEquals(2_000, config.bufferForPlaybackMs)
        assertEquals(4_000, config.bufferForPlaybackAfterRebufferMs)
        assertEquals(30_000, config.backBufferMs)
        assertTrue(config.targetBufferBytes >= 48 * 1024 * 1024)
    }

    @Test
    fun testLowTierOrLowRamNetworkVod() {
        val configLowTier = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.BASIC,
            ramBytes = 2L * 1024 * 1024 * 1024,
            isLive = false,
            isLocal = false
        )

        assertEquals(VoxBufferProfile.CONSERVATIVE, configLowTier.profile)
        assertEquals(15_000, configLowTier.minBufferMs)
        assertEquals(25_000, configLowTier.maxBufferMs)
        assertEquals(2_000, configLowTier.bufferForPlaybackMs)
        assertEquals(3_500, configLowTier.bufferForPlaybackAfterRebufferMs)
        assertEquals(5_000, configLowTier.backBufferMs)

        val configLowRam = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.STANDARD,
            ramBytes = 1024L * 1024 * 1024, // 1GB
            isLive = false,
            isLocal = false
        )
        assertEquals(VoxBufferProfile.CONSERVATIVE, configLowRam.profile)
    }

    @Test
    fun testBalancedNetworkVod() {
        val config = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.STANDARD,
            ramBytes = 2L * 1024 * 1024 * 1024,
            isLive = false,
            isLocal = false,
            videoHeight = 1080
        )

        assertEquals(VoxBufferProfile.BALANCED, config.profile)
        assertEquals(25_000, config.minBufferMs)
        assertEquals(40_000, config.maxBufferMs)
        assertEquals(2_500, config.bufferForPlaybackMs)
        assertEquals(4_500, config.bufferForPlaybackAfterRebufferMs)
        assertEquals(15_000, config.backBufferMs)
    }

    @Test
    fun testFallbackOnZeroOrNegativeRam() {
        val config = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.STANDARD,
            ramBytes = 0L,
            isLive = false,
            isLocal = false
        )

        assertEquals(VoxBufferProfile.BALANCED, config.profile)
        assertTrue(config.targetBufferBytes >= 20 * 1024 * 1024)
    }
}
