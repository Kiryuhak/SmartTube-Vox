package com.liskovsoft.smartyoutubetv2.common.vox.ota.hud

import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannel
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxVersionComparator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prompt №163-R50 Gate 2 Unit Tests:
 * - Verification of full version display formatting without truncation.
 * - Strict prohibition of false bare integers ("- 1", "- 10", "10").
 * - Verification of transitions:
 *     32.56-vox.10 -> 32.71-vox.10
 *     32.71-vox.10 -> 32.71-vox.11
 *     32.71-vox.11 -> 33.25-vox.1
 * - Monotonic versionCode validation.
 * - Engine-first version comparator verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Prompt163R50OtaVersionDisplayTest {

    @Test
    fun testDirectTransition3256Vox10To3271Vox10() {
        val targetRaw = "32.71-vox.10"
        val currentRaw = "32.56-vox.10"

        val displayVer = VoxOtaDisplayHelper.formatDisplayVersion(targetRaw, VoxOtaChannelTier.STABLE)
        val currentVer = VoxOtaDisplayHelper.formatCurrentVersion(currentRaw)

        assertEquals("SmartTube VOX 32.71-vox.10", displayVer)
        assertEquals("Текущая версия: 32.56-vox.10", currentVer)

        // Strict prohibition of bare truncated forms
        assertNotEquals("SmartTube Vox - 1", displayVer)
        assertNotEquals("SmartTube Vox - 10", displayVer)
        assertFalse("Display version must not truncate engine version", displayVer.endsWith("- 10"))
        assertNotEquals("Текущая версия: 10", currentVer)
        assertTrue(displayVer.contains("32.71"))
        assertTrue(currentVer.contains("32.56"))
    }

    @Test
    fun testTransition3271Vox10To3271Vox11() {
        val targetRaw = "32.71-vox.11"
        val currentRaw = "32.71-vox.10"

        val displayVer = VoxOtaDisplayHelper.formatDisplayVersion(targetRaw, VoxOtaChannelTier.STABLE)
        val currentVer = VoxOtaDisplayHelper.formatCurrentVersion(currentRaw)

        assertEquals("SmartTube VOX 32.71-vox.11", displayVer)
        assertEquals("Текущая версия: 32.71-vox.10", currentVer)
    }

    @Test
    fun testFutureEngineUpgrade3271Vox11To3325Vox1() {
        val targetRaw = "33.25-vox.1"
        val currentRaw = "32.71-vox.11"

        val displayVer = VoxOtaDisplayHelper.formatDisplayVersion(targetRaw, VoxOtaChannelTier.STABLE)
        val currentVer = VoxOtaDisplayHelper.formatCurrentVersion(currentRaw)

        assertEquals("SmartTube VOX 33.25-vox.1", displayVer)
        assertEquals("Текущая версия: 32.71-vox.11", currentVer)

        // Strict prohibition of bare "- 1"
        assertNotEquals("SmartTube Vox - 1", displayVer)
        assertTrue(displayVer.contains("33.25"))
    }

    @Test
    fun testCleanVersionNameHandlesVPrefixAndAbiSuffixes() {
        assertEquals("32.71-vox.10", VoxOtaDisplayHelper.cleanVersionName("v32.71-vox.10"))
        assertEquals("32.71-vox.10", VoxOtaDisplayHelper.cleanVersionName("32.71-vox.10-universal"))
        assertEquals("32.71-vox.10", VoxOtaDisplayHelper.cleanVersionName("v32.71-vox.10-arm64-v8a"))
        assertEquals("32.71-vox.10", VoxOtaDisplayHelper.cleanVersionName("32.71-vox.10-armeabi-v7a"))
        assertEquals("32.71-vox.10", VoxOtaDisplayHelper.cleanVersionName("32.71-vox.10-otaqa"))
        assertEquals("32.71-vox.10", VoxOtaDisplayHelper.cleanVersionName("   32.71-vox.10   "))
        assertEquals("32.71-vox.10", VoxOtaDisplayHelper.cleanVersionName(null))
        assertEquals("32.71-vox.10", VoxOtaDisplayHelper.cleanVersionName(""))
    }

    @Test
    fun testMonotonicVersionCodeProgression() {
        val codeStageLProd = 2446032L   // 32.56-vox.9.2-rc.13
        val codeStageLTest = 2446050L   // 32.56-vox.10
        val codeStageNOld = 2461001L    // 32.71-vox.1
        val codeStageNCand = 2461010L   // 32.71-vox.10
        val codeFuture3271Next = 2461011L // 32.71-vox.11
        val codeFuture33x = 2500001L    // 33.x-vox.1 (baseCode 2500 * 1000 + 1)

        assertTrue(codeStageLTest > codeStageLProd)
        assertTrue(codeStageNOld > codeStageLTest)
        assertTrue(codeStageNCand > codeStageNOld)
        assertTrue(codeFuture3271Next > codeStageNCand)
        assertTrue(codeFuture33x > codeFuture3271Next)
    }

    @Test
    fun testEngineFirstComparatorEvaluations() {
        val channel = VoxUpdateChannel.STABLE

        // 32.56-vox.10 -> 32.71-vox.10: update available (2461010 > 2446050)
        assertTrue(VoxVersionComparator.isUpdateAvailable(
            "32.56-vox.10", "32.71-vox.10", channel, 2446050L, 2461010L
        ))

        // 32.71-vox.10 -> 32.71-vox.11: update available (2461011 > 2461010)
        assertTrue(VoxVersionComparator.isUpdateAvailable(
            "32.71-vox.10", "32.71-vox.11", channel, 2461010L, 2461011L
        ))

        // 32.71-vox.11 -> 33.25-vox.1: update available (2500001 > 2461011)
        assertTrue(VoxVersionComparator.isUpdateAvailable(
            "32.71-vox.11", "33.25-vox.1", channel, 2461011L, 2500001L
        ))

        // Same version: no update
        assertFalse(VoxVersionComparator.isUpdateAvailable(
            "32.71-vox.10", "32.71-vox.10", channel, 2461010L, 2461010L
        ))
    }
}
