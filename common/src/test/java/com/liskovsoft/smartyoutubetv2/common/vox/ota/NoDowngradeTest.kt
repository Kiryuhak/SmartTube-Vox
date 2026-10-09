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
}
