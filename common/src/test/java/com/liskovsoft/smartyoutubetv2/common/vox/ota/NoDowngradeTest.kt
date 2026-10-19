package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoDowngradeTest {

    @Test
    fun testNoDowngradeFromHigherBetaToLowerStable() {
        // Установлена 8.2-beta.1, на сервере 8.1 stable -> обновление НЕ доступно
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2-beta.1", "32.56-vox.8.1", isBetaChannel = true))
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2-beta.1", "32.56-vox.8.1", isBetaChannel = false))
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2-beta.1", "32.56-vox.8.1") > 0)
    }

    @Test
    fun testNoDowngradeFromHigherMinorToLowerMinor() {
        // 8.2 vs 8.1
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2", "32.56-vox.8.1"))
        // 8.1 vs 8.0
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.1", "32.56-vox.8.0"))
        // 8.0 vs 7.0
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8", "32.56-vox.7"))
    }

    @Test
    fun testSameVersionNeverProducesUpdate() {
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2-beta.1", "32.56-vox.8.2-beta.1"))
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.1", "32.56-vox.8.1"))
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8", "32.56-vox.8"))
    }

    @Test
    fun testNoDowngradeFromRcToBeta() {
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2-rc1", "32.56-vox.8.2-beta.1"))
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2-rc2", "32.56-vox.8.2-rc1"))
    }

    @Test
    fun testMonotonicVersionCodeBlocksDowngrade() {
        // installed versionCode = 2446092, candidate = 2446091 -> false
        assertFalse(VoxVersionComparator.isUpdateAvailable(
            "32.56-vox.9.2-otaqa.2",
            "32.56-vox.9.1-otaqa.1",
            VoxUpdateChannel.QA,
            installedVersionCode = 2446092L,
            targetVersionCode = 2446091L
        ))

        // same versionCode = 2446092 -> false
        assertFalse(VoxVersionComparator.isUpdateAvailable(
            "32.56-vox.9.2-otaqa.2",
            "32.56-vox.9.2-otaqa.2",
            VoxUpdateChannel.QA,
            installedVersionCode = 2446092L,
            targetVersionCode = 2446092L
        ))

        // newer versionCode = 2446093 -> true
        assertTrue(VoxVersionComparator.isUpdateAvailable(
            "32.56-vox.9.2-otaqa.2",
            "32.56-vox.9.3-otaqa.3",
            VoxUpdateChannel.QA,
            installedVersionCode = 2446092L,
            targetVersionCode = 2446093L
        ))
    }

    @Test
    fun testDevBuildCannotBeDowngradedToOlderStable() {
        // Section 51
        assertFalse(VoxVersionComparator.isUpdateAvailable(
            "32.56-vox.9.1-dev",
            "32.56-vox.9",
            VoxUpdateChannel.STABLE
        ))
    }
}
