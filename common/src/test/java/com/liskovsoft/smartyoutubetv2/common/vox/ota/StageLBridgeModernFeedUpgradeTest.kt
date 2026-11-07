package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class StageLBridgeModernFeedUpgradeTest {

    private val sampleV2ManifestJson = """
    {
      "schemaVersion": 2,
      "version": "32.71-vox.1",
      "versionCode": 2461001,
      "minSdk": 21,
      "targetSdk": 34,
      "changelog": [
        "Base engine upgraded to SmartTube 32.71 and VOT 1.12.6 integration",
        "Canonical 3-APK distribution: arm64-v8a, armeabi-v7a, and universal"
      ],
      "assets": [
        {
          "name": "SmartTube_vot_32.71-vox.1_arm64-v8a.apk",
          "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.1/SmartTube_vot_32.71-vox.1_arm64-v8a.apk",
          "size": 31327960,
          "sha256": "5222897ef650f4ab408181cf949ce0818ad57041de68507ba49299b2d328da57"
        },
        {
          "name": "SmartTube_vot_32.71-vox.1_armeabi-v7a.apk",
          "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.1/SmartTube_vot_32.71-vox.1_armeabi-v7a.apk",
          "size": 29547210,
          "sha256": "1671067839d5e8f04f9e03377fb7dd38678c1a4bec319ba0d82913c4aa082c4c"
        },
        {
          "name": "SmartTube_vot_32.71-vox.1_universal.apk",
          "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.1/SmartTube_vot_32.71-vox.1_universal.apk",
          "size": 68346349,
          "sha256": "16bb7775eea84ba2d88d55db4d4251c45e4cb93314c18bfd7075e7e0e503db5b"
        }
      ],
      "sha256SumsUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.1/SHA256SUMS.txt"
    }
    """.trimIndent()

    @Test
    fun testStageL_ParsesV2ManifestWithThreeAssetsAndSha256() {
        val releaseInfo = VoxOtaReleaseParser.parseRelease(sampleV2ManifestJson)
        assertNotNull("Release info should not be null", releaseInfo)
        assertEquals("32.71-vox.1", releaseInfo.versionName)
        assertEquals(2461001L, releaseInfo.versionCode)
        assertEquals(3, releaseInfo.assets.size)

        val arm64Asset = releaseInfo.assets.firstOrNull { it.name.contains("arm64-v8a") }
        assertNotNull(arm64Asset)
        assertEquals("5222897ef650f4ab408181cf949ce0818ad57041de68507ba49299b2d328da57", arm64Asset?.sha256)

        val v7aAsset = releaseInfo.assets.firstOrNull { it.name.contains("armeabi-v7a") }
        assertNotNull(v7aAsset)
        assertEquals("1671067839d5e8f04f9e03377fb7dd38678c1a4bec319ba0d82913c4aa082c4c", v7aAsset?.sha256)

        val universalAsset = releaseInfo.assets.firstOrNull { it.name.contains("universal") }
        assertNotNull(universalAsset)
        assertEquals("16bb7775eea84ba2d88d55db4d4251c45e4cb93314c18bfd7075e7e0e503db5b", universalAsset?.sha256)

        assertEquals("https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.1/SHA256SUMS.txt", releaseInfo.sha256SumsUrl)
    }

    @Test
    fun testStageL_EngineFirstUpdateDecision() {
        val installedVersion = "32.56-vox.10"
        val installedVersionCode = 2446050L

        // 1. Stage L (32.56-vox.10) -> Stage N (32.71-vox.1): MUST be available!
        val canUpgradeToStageN = VoxVersionComparator.isUpdateAvailable(
            currentVersion = installedVersion,
            targetVersion = "32.71-vox.1",
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = installedVersionCode,
            targetVersionCode = 2461001L
        )
        assertTrue("Stage L must recognize Stage N as update", canUpgradeToStageN)

        // 2. Next release: 32.71-vox.2 > 32.71-vox.1
        val nextVoxRelease = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.71-vox.1",
            targetVersion = "32.71-vox.2",
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = 2461001L,
            targetVersionCode = 2461002L
        )
        assertTrue("Next vox patch must be recognized as update", nextVoxRelease)

        // 3. Upstream jump: 33.25-vox.1 > 32.71-vox.9
        val upstreamJump = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.71-vox.9",
            targetVersion = "33.25-vox.1",
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = 2461009L,
            targetVersionCode = 2471001L
        )
        assertTrue("New upstream engine must be recognized as update", upstreamJump)

        // 4. Anti-loop: 32.56-vox.10 vs 32.56-vox.10: NO update
        val selfLoop = VoxVersionComparator.isUpdateAvailable(
            currentVersion = installedVersion,
            targetVersion = installedVersion,
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = installedVersionCode,
            targetVersionCode = installedVersionCode
        )
        assertFalse("Stage L must not offer self-loop update", selfLoop)

        // 5. Anti-downgrade: 32.56-vox.10 vs 32.56-vox.9.2: NO update
        val downgrade = VoxVersionComparator.isUpdateAvailable(
            currentVersion = installedVersion,
            targetVersion = "32.56-vox.9.2",
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = installedVersionCode,
            targetVersionCode = 2446032L
        )
        assertFalse("Stage L must reject downgrade to 9.2", downgrade)
    }

    @Test
    fun testStageL_StickyAbiAssetSelection() {
        val releaseInfo = VoxOtaReleaseParser.parseRelease(sampleV2ManifestJson)

        // 1. ARM64-v8a selection
        val selectedArm64 = VoxReleaseAssetSelector.selectAsset(
            releaseInfo.assets,
            preferredVariant = VoxReleaseAssetSelector.Variant.ARM64_V8A,
            primaryAbi = "arm64-v8a"
        )
        assertNotNull(selectedArm64)
        assertTrue(selectedArm64!!.name.contains("arm64-v8a"))

        // 2. ARMEABI-v7a selection
        val selectedV7a = VoxReleaseAssetSelector.selectAsset(
            releaseInfo.assets,
            preferredVariant = VoxReleaseAssetSelector.Variant.ARMEABI_V7A,
            primaryAbi = "armeabi-v7a"
        )
        assertNotNull(selectedV7a)
        assertTrue(selectedV7a!!.name.contains("armeabi-v7a"))

        // 3. Universal selection
        val selectedUniversal = VoxReleaseAssetSelector.selectAsset(
            releaseInfo.assets,
            preferredVariant = VoxReleaseAssetSelector.Variant.UNIVERSAL,
            primaryAbi = "x86"
        )
        assertNotNull(selectedUniversal)
        assertTrue(selectedUniversal!!.name.contains("universal"))

        // 4. Fallback on x86 when no preferred variant stored (legacy first hop from 8/9.1/9.2)
        val legacyHop = VoxReleaseAssetSelector.selectAsset(
            releaseInfo.assets,
            preferredVariant = null,
            primaryAbi = "x86"
        )
        assertNotNull(legacyHop)
        assertTrue(legacyHop!!.name.contains("universal"))
    }
}
