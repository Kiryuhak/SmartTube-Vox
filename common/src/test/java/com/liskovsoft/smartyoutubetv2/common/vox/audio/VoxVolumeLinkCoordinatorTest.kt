package com.liskovsoft.smartyoutubetv2.common.vox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for VoxVolumeLinkCoordinator verifying 1:1 mathematical compliance with
 * volumeLink.ts (applyVolumeLinkDelta, reentrancy guards, clamping).
 */
class VoxVolumeLinkCoordinatorTest {

    private lateinit var coordinator: VoxVolumeLinkCoordinator

    @Before
    fun setUp() {
        coordinator = VoxVolumeLinkCoordinator(translationMin = 0, translationMax = 100)
    }

    @Test
    fun testInitialDelta_initializesState() {
        val result = coordinator.onVideoVolumeChanged(newVideoPercent = 80, currentTranslationPercent = 60)
        assertEquals(60, result.nextTranslationPercent)
        assertEquals(80, coordinator.getState().lastVideoPercent)
        assertEquals(60, coordinator.getState().lastTranslationPercent)
        assertTrue(coordinator.getState().initialized)
    }

    @Test
    fun testUserChangesVideoVolume_updatesTranslationBySameDelta() {
        // Set baseline video volume to 70 and translation to 50
        coordinator.onVideoVolumeChanged(newVideoPercent = 70, currentTranslationPercent = 50)

        // User raises video volume by +15 (from 70 to 85)
        val result = coordinator.onVideoVolumeChanged(newVideoPercent = 85, currentTranslationPercent = 50)
        assertEquals(65, result.nextTranslationPercent) // 50 + 15 = 65
        assertEquals(85, coordinator.getState().lastVideoPercent)
        assertEquals(65, coordinator.getState().lastTranslationPercent)
    }

    @Test
    fun testUserChangesTranslationVolume_updatesVideoBySameDelta() {
        // Set baseline translation volume to 60 and video to 80
        coordinator.onTranslationVolumeChanged(newTranslationPercent = 60, currentVideoPercent = 80)

        // User lowers translation volume by -20 (from 60 to 40)
        val result = coordinator.onTranslationVolumeChanged(newTranslationPercent = 40, currentVideoPercent = 80)
        assertEquals(60, result.nextVideoPercent) // 80 - 20 = 60
        assertEquals(40, coordinator.getState().lastTranslationPercent)
        assertEquals(60, coordinator.getState().lastVideoPercent)
    }

    @Test
    fun testClamping_withinMinMaxBounds() {
        // Video volume at 90, translation at 90
        coordinator.onVideoVolumeChanged(newVideoPercent = 90, currentTranslationPercent = 90)

        // User increases video volume by +10 (90 -> 100)
        val result = coordinator.onVideoVolumeChanged(newVideoPercent = 100, currentTranslationPercent = 95)
        assertEquals(100, result.nextTranslationPercent) // 90 + 10 = 100

        // User decreases video volume by -50 (100 -> 50)
        coordinator.onVideoVolumeChanged(newVideoPercent = 100, currentTranslationPercent = 30)
        val resultDown = coordinator.onVideoVolumeChanged(newVideoPercent = 50, currentTranslationPercent = 30)
        assertEquals(0, resultDown.nextTranslationPercent) // 30 - 50 = -20 -> clamped to 0
    }

    @Test
    fun testProgrammaticGuard_preventsReentrantEchoUpdates() {
        assertFalse(coordinator.isProgrammatic())

        var executed = false
        coordinator.withProgrammaticGuard {
            assertTrue(coordinator.isProgrammatic())
            // Nested call during programmatic volume adjustment must return empty LinkResult
            val result = coordinator.onVideoVolumeChanged(newVideoPercent = 50, currentTranslationPercent = 50)
            assertEquals(null, result.nextTranslationPercent)
            executed = true
        }

        assertTrue(executed)
        assertFalse(coordinator.isProgrammatic())
    }

    @Test
    fun testReset_clearsHistoricalVolumeLevels() {
        coordinator.onVideoVolumeChanged(newVideoPercent = 70, currentTranslationPercent = 50)
        coordinator.onTranslationVolumeChanged(newTranslationPercent = 50, currentVideoPercent = 70)

        coordinator.reset()

        assertFalse(coordinator.getState().initialized)
        assertEquals(0, coordinator.getState().lastVideoPercent)
        assertEquals(0, coordinator.getState().lastTranslationPercent)
    }
}
