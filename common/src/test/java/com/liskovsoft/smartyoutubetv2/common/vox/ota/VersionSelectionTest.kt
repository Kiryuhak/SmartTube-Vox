package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VersionSelectionTest {

    @Test
    fun testParseBetaAndRcVersions() {
        val beta1 = VoxVersionComparator.parseVersion("32.56-vox.8.2-beta.1")
        assertNotNull(beta1)
        assertEquals(32, beta1!!.major)
        assertEquals(56, beta1.minor)
        assertEquals(8, beta1.voxMajor)
        assertEquals(2, beta1.voxMinor)
        assertEquals(VoxReleaseStage.BETA, beta1.stage)
        assertEquals(1, beta1.stageNumber)
        assertTrue(beta1.isPreRelease)

        val rc2 = VoxVersionComparator.parseVersion("32.56-vox.8.2-rc.2")
        assertNotNull(rc2)
        assertEquals(VoxReleaseStage.RC, rc2!!.stage)
        assertEquals(2, rc2.stageNumber)
        assertTrue(rc2.isPreRelease)

        val stable = VoxVersionComparator.parseVersion("32.56-vox.8.1")
        assertNotNull(stable)
        assertEquals(VoxReleaseStage.STABLE, stable!!.stage)
        assertFalse(stable.isPreRelease)
    }

    @Test
    fun testStageHierarchyProgression() {
        // dev < beta.1 < beta.2 < rc1 < rc2 < stable 8.2
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2-dev", "32.56-vox.8.2-beta.1") < 0)
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2-beta.1", "32.56-vox.8.2-beta.2") < 0)
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2-beta.2", "32.56-vox.8.2-rc1") < 0)
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2-rc1", "32.56-vox.8.2-rc.2") < 0)
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2-rc.2", "32.56-vox.8.2") < 0)
    }

    @Test
    fun testStableIgnoresBetaUnlessOnTestChannel() {
        // 8.1 stable vs 8.2-beta.1
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.1", "32.56-vox.8.2-beta.1", isBetaChannel = false))
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.1", "32.56-vox.8.2-beta.1", isBetaChannel = true))

        // 8.1 stable vs 8.2 final (доступно на обоих каналах)
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.1", "32.56-vox.8.2", isBetaChannel = false))
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.1", "32.56-vox.8.2", isBetaChannel = true))
    }

    @Test
    fun testBetaUserReceivesNextBetaAndRcAndFinal() {
        // Пользователь на 8.2-beta.1 видит 8.2-beta.2 даже без флага канала
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2-beta.1", "32.56-vox.8.2-beta.2", isBetaChannel = false))
        // Пользователь на 8.2-beta.2 видит 8.2-rc.1
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2-beta.2", "32.56-vox.8.2-rc.1", isBetaChannel = false))
        // Пользователь на 8.2-rc.1 видит финальный 8.2
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2-rc.1", "32.56-vox.8.2", isBetaChannel = false))
    }
}
