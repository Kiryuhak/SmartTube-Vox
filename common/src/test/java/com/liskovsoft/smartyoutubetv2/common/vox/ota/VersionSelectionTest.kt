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

        // 6. Newer hypothetical VOX 9.1 is available on beta, but blocked on stable; VOX 10 stable is available on stable
        assertFalse(VoxVersionComparator.isUpdateAvailable(vox9, "32.56-vox.9.1", isBetaChannel = false))
        assertTrue(VoxVersionComparator.isUpdateAvailable(vox9, "32.56-vox.9.1", isBetaChannel = true))
        assertTrue(VoxVersionComparator.isUpdateAvailable(vox9, "32.56-vox.10", isBetaChannel = false))

        // 7. Malformed version handled safely
        assertFalse(VoxVersionComparator.isUpdateAvailable(vox9, "invalid-version", isBetaChannel = false))
        assertFalse(VoxVersionComparator.isUpdateAvailable(vox9, null, isBetaChannel = false))
    }

    @Test
    fun testQaVersionParsingAndProgression() {
        val qa1 = VoxVersionComparator.parseVersion("32.56-vox.9.1-otaqa.1")
        assertNotNull(qa1)
        assertEquals(9, qa1!!.voxMajor)
        assertEquals(1, qa1.voxMinor)
        assertEquals(VoxReleaseStage.QA, qa1.stage)
        assertEquals(1, qa1.stageNumber)
        assertTrue(qa1.isQa)

        val qa2 = VoxVersionComparator.parseVersion("32.56-vox.9.2-otaqa.2")
        assertNotNull(qa2)
        assertEquals(9, qa2!!.voxMajor)
        assertEquals(2, qa2.voxMinor)
        assertEquals(VoxReleaseStage.QA, qa2.stage)
        assertEquals(2, qa2.stageNumber)
        assertTrue(qa2.isQa)

        val qa3 = VoxVersionComparator.parseVersion("32.56-vox.9.3-otaqa.3")
        assertNotNull(qa3)
        assertEquals(9, qa3!!.voxMajor)
        assertEquals(3, qa3.voxMinor)
        assertEquals(VoxReleaseStage.QA, qa3.stage)
        assertEquals(3, qa3.stageNumber)
        assertTrue(qa3.isQa)

        // QA 9.1 < QA 9.2 < QA 9.3
        assertTrue(VoxVersionComparator.compare("32.56-vox.9.1-otaqa.1", "32.56-vox.9.2-otaqa.2") < 0)
        assertTrue(VoxVersionComparator.compare("32.56-vox.9.2-otaqa.2", "32.56-vox.9.3-otaqa.3") < 0)
    }

    @Test
    fun testChannelIsolationRules() {
        val qa1 = "32.56-vox.9.1-otaqa.1"
        val qa2 = "32.56-vox.9.2-otaqa.2"
        val qa3 = "32.56-vox.9.3-otaqa.3"
        val stable = "32.56-vox.9"
        val beta = "32.56-vox.9.2-beta.1"

        // 1. Stable client NEVER sees QA
        assertFalse(VoxVersionComparator.isUpdateAvailable(stable, qa2, channel = VoxUpdateChannel.STABLE))
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.1", qa1, channel = VoxUpdateChannel.STABLE))

        // 2. Beta client NEVER sees QA
        assertFalse(VoxVersionComparator.isUpdateAvailable(beta, qa2, channel = VoxUpdateChannel.BETA))
        assertFalse(VoxVersionComparator.isUpdateAvailable(stable, qa2, channel = VoxUpdateChannel.BETA))

        // 3. QA client ONLY sees QA
        assertTrue(VoxVersionComparator.isUpdateAvailable(qa1, qa2, channel = VoxUpdateChannel.QA))
        assertTrue(VoxVersionComparator.isUpdateAvailable(qa2, qa3, channel = VoxUpdateChannel.QA))
        assertFalse(VoxVersionComparator.isUpdateAvailable(qa1, "32.56-vox.9.2", channel = VoxUpdateChannel.QA))
        assertFalse(VoxVersionComparator.isUpdateAvailable(qa1, beta, channel = VoxUpdateChannel.QA))

        // 4. Downgrade in QA blocked
        assertFalse(VoxVersionComparator.isUpdateAvailable(qa3, qa2, channel = VoxUpdateChannel.QA))
        assertFalse(VoxVersionComparator.isUpdateAvailable(qa2, qa1, channel = VoxUpdateChannel.QA))
        assertFalse(VoxVersionComparator.isUpdateAvailable(qa2, qa2, channel = VoxUpdateChannel.QA))
    }

    @Test
    fun testDevBuildPolicySection51() {
        // Section 51: installed: 32.56-vox.9.1-dev, available: VOX 9 stable -> NO_UPDATE / DOWNGRADE_BLOCKED
        val dev91 = "32.56-vox.9.1-dev"
        val stable9 = "32.56-vox.9"
        assertFalse(VoxVersionComparator.isUpdateAvailable(dev91, stable9, channel = VoxUpdateChannel.STABLE))
        assertFalse(VoxVersionComparator.isUpdateAvailable(dev91, stable9, channel = VoxUpdateChannel.BETA))
    }
}
