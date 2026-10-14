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

    @Test
    fun testVox9ReleaseUpgradeAndDowngradeMatrix() {
        val vox9 = "32.56-vox.9"
        val parsedVox9 = VoxVersionComparator.parseVersion(vox9)
        assertNotNull(parsedVox9)
        assertEquals(9, parsedVox9!!.voxMajor)
        assertEquals(0, parsedVox9.voxMinor)
        assertEquals(VoxReleaseStage.STABLE, parsedVox9.stage)
        assertFalse(parsedVox9.isPreRelease)

        // 1. Same VOX 9 final: no update
        assertFalse(VoxVersionComparator.isUpdateAvailable(vox9, vox9, isBetaChannel = false))
        assertFalse(VoxVersionComparator.isUpdateAvailable(vox9, vox9, isBetaChannel = true))

        // 2. Older VOX 8 stable upgrades to VOX 9
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.1", vox9, isBetaChannel = false))
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.8", vox9, isBetaChannel = false))

        // 3. Older VOX 8 beta upgrades to VOX 9
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2-beta.2", vox9, isBetaChannel = false))

        // 4. Older VOX 9 prerelease upgrades to VOX 9
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.9-beta.1", vox9, isBetaChannel = false))
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.9-rc.1", vox9, isBetaChannel = false))

        // 5. No downgrade: VOX 9 never accepts older VOX 8 or older dev
        assertFalse(VoxVersionComparator.isUpdateAvailable(vox9, "32.56-vox.8.1", isBetaChannel = false))
        assertFalse(VoxVersionComparator.isUpdateAvailable(vox9, "32.56-vox.8.2-beta.2", isBetaChannel = true))
        assertFalse(VoxVersionComparator.isUpdateAvailable(vox9, "32.56-vox.9-dev", isBetaChannel = true))

        // 6. Newer hypothetical VOX 9.1 is available
        assertTrue(VoxVersionComparator.isUpdateAvailable(vox9, "32.56-vox.9.1", isBetaChannel = false))
        assertTrue(VoxVersionComparator.isUpdateAvailable(vox9, "32.56-vox.10", isBetaChannel = false))

        // 7. Malformed version handled safely
        assertFalse(VoxVersionComparator.isUpdateAvailable(vox9, "invalid-version", isBetaChannel = false))
        assertFalse(VoxVersionComparator.isUpdateAvailable(vox9, null, isBetaChannel = false))
    }
}
