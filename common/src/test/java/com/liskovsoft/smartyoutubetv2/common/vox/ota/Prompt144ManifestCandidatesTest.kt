package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Prompt144ManifestCandidatesTest {

    private val realUniversalUrl = "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.9.2/SmartTube_vot_32.56-vox.9.2-rc.13_universal.apk"
    private val realArm64Url = "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.9.2/SmartTube_vot_32.56-vox.9.2-rc.13_arm64-v8a.apk"
    private val realArmeabiUrl = "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.9.2/SmartTube_vot_32.56-vox.9.2-rc.13_armeabi-v7a.apk"
    private val realX86Url = "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.9.2/SmartTube_vot_32.56-vox.9.2-rc.13_x86.apk"
    private val realSha256SumsUrl = "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.9.2/SHA256SUMS.txt"

    private val expectedUniversalSha256 = "e31ba5fe1bb8e2f49345edcb31384411d85779d798c414040fe7fecd77263e23"
    private val expectedUniversalSize = 66383180L

    /**
     * Candidate B1: Dual keys ("32.56-vox.9.2" AND "32.56-vox.9.2-universal").
     * Proof that key order determines success vs fatal failure, making B1 NON-DETERMINISTIC.
     */
    @Test
    fun testCandidateB1_NonDeterministicKeyOrdering() {
        // Order A: "32.56-vox.9.2" is first
        val jsonOrderA = """
        {
            "package": { "downloadUrl": "$realUniversalUrl" },
            "32.56-vox.9.2": {
                "versionCode": 2446032,
                "downloadUrl": "$realUniversalUrl"
            },
            "32.56-vox.9.2-universal": {
                "versionCode": 2446032,
                "downloadUrl": "$realUniversalUrl"
            }
        }
        """.trimIndent()

        val releaseA = VoxOtaReleaseParser.parseRelease(
            jsonOrderA,
            channel = VoxUpdateChannel.STABLE,
            currentVersion = "32.56-vox.9.1",
            installedVersionCode = 2446016L
        )

        // When "32.56-vox.9.2" is selected first, its synthesized asset name is "SmartTube_vot_32.56-vox.9.2.apk"
        // which lacks "universal", so selectAsset MUST fail.
        val selectedAssetA = VoxReleaseAssetSelector.selectAsset(releaseA.assets)
        val isFailOrderA = selectedAssetA == null

        // Order B: "32.56-vox.9.2-universal" is first
        val jsonOrderB = """
        {
            "package": { "downloadUrl": "$realUniversalUrl" },
            "32.56-vox.9.2-universal": {
                "versionCode": 2446032,
                "downloadUrl": "$realUniversalUrl"
            },
            "32.56-vox.9.2": {
                "versionCode": 2446032,
                "downloadUrl": "$realUniversalUrl"
            }
        }
        """.trimIndent()

        val releaseB = VoxOtaReleaseParser.parseRelease(
            jsonOrderB,
            channel = VoxUpdateChannel.STABLE,
            currentVersion = "32.56-vox.9.1",
            installedVersionCode = 2446016L
        )
        val selectedAssetB = VoxReleaseAssetSelector.selectAsset(releaseB.assets)
        val isPassOrderB = selectedAssetB != null

        // In JSON Order A, the classic manifest iteration returns 32.56-vox.9.2, failing universal selection.
        assertTrue("Candidate B1 Order A must fail asset selection", isFailOrderA)
        assertTrue("Candidate B1 Order B passes asset selection", isPassOrderB)
        // Since behavior depends entirely on JSON key iteration order, B1 is strictly NON-DETERMINISTIC (FAIL).
    }

    /**
     * Candidate B2: Single version key "32.56-vox.9.2-universal" + mandatory "package".
     * Deterministic, 100% compliant with classic manifest parser.
     */
    @Test
    fun testCandidateB2_DeterministicForClient91() {
        val jsonB2 = """
        {
            "package": {
                "downloadUrl": "$realUniversalUrl",
                "downloadUrlList_armeabi-v7a": [ "$realArmeabiUrl" ],
                "downloadUrlList_arm64-v8a": [ "$realArm64Url" ],
                "downloadUrlList_x86": [ "$realX86Url" ],
                "downloadUrlList": [ "$realUniversalUrl" ]
            },
            "32.56-vox.9.2-universal": {
                "versionCode": 2446032,
                "prerelease": false,
                "downloadUrl": "$realUniversalUrl",
                "sha256": "$expectedUniversalSha256",
                "size": $expectedUniversalSize,
                "changelog": [ "Test changelog" ]
            }
        }
        """.trimIndent()

        // 1. Client 9.1 Stable
        val release91Stable = VoxOtaReleaseParser.parseRelease(
            jsonB2,
            channel = VoxUpdateChannel.STABLE,
            currentVersion = "32.56-vox.9.1",
            installedVersionCode = 2446016L
        )
        assertEquals("32.56-vox.9.2-universal", release91Stable.versionName)
        assertEquals(2446032L, release91Stable.versionCode)

        val isUpdateAvail91Stable = VoxVersionComparator.isUpdateAvailable(
            "32.56-vox.9.1",
            release91Stable.versionName,
            VoxUpdateChannel.STABLE,
            2446016L,
            release91Stable.versionCode
        )
        assertTrue("Update must be available for 9.1 Stable", isUpdateAvail91Stable)

        val selectedAsset91 = VoxReleaseAssetSelector.selectAsset(release91Stable.assets)
        assertNotNull("Universal asset must be selected for 9.1", selectedAsset91)
        assertEquals("SmartTube_vot_32.56-vox.9.2-universal.apk", selectedAsset91!!.name)
        assertEquals(realUniversalUrl, selectedAsset91.downloadUrl)

        // 2. Client 9.1 Beta
        val release91Beta = VoxOtaReleaseParser.parseRelease(
            jsonB2,
            channel = VoxUpdateChannel.BETA,
            currentVersion = "32.56-vox.9.1",
            installedVersionCode = 2446016L
        )
        val isUpdateAvail91Beta = VoxVersionComparator.isUpdateAvailable(
            "32.56-vox.9.1",
            release91Beta.versionName,
            VoxUpdateChannel.BETA,
            2446016L,
            release91Beta.versionCode
        )
        assertTrue("Update must be available for 9.1 Beta", isUpdateAvail91Beta)

        // 3. Client 9.2 (RC.13 or final) -> NO UPDATE MUST BE OFFERED!
        val release92 = VoxOtaReleaseParser.parseRelease(
            jsonB2,
            channel = VoxUpdateChannel.STABLE,
            currentVersion = "32.56-vox.9.2-rc.13",
            installedVersionCode = 2446032L
        )
        val isUpdateAvail92 = VoxVersionComparator.isUpdateAvailable(
            "32.56-vox.9.2-rc.13",
            release92.versionName,
            VoxUpdateChannel.STABLE,
            2446032L,
            release92.versionCode
        )
        assertFalse("9.2 must NOT be offered self-update (versionCode 2446032 <= 2446032)", isUpdateAvail92)
    }

    /**
     * Candidate B3: Custom VOX Manifest (root has "version" and "assets").
     * Tests Variant 2 in VoxOtaReleaseParser.
     */
    @Test
    fun testCandidateB3_CustomVoxManifest() {
        val jsonB3 = """
        {
            "package": {
                "downloadUrl": "$realUniversalUrl"
            },
            "version": "32.56-vox.9.2",
            "versionCode": 2446032,
            "prerelease": false,
            "downloadUrl": "$realUniversalUrl",
            "sha256": "$expectedUniversalSha256",
            "size": $expectedUniversalSize,
            "assets": [
                {
                    "name": "SHA256SUMS.txt",
                    "downloadUrl": "$realSha256SumsUrl",
                    "size": 452
                },
                {
                    "name": "SmartTube_vot_32.56-vox.9.2-rc.13_armeabi-v7a.apk",
                    "downloadUrl": "$realArmeabiUrl",
                    "size": 31071720
                },
                {
                    "name": "SmartTube_vot_32.56-vox.9.2-rc.13_arm64-v8a.apk",
                    "downloadUrl": "$realArm64Url",
                    "size": 32685169
                },
                {
                    "name": "SmartTube_vot_32.56-vox.9.2-rc.13_x86.apk",
                    "downloadUrl": "$realX86Url",
                    "size": 34505609
                },
                {
                    "name": "SmartTube_vot_32.56-vox.9.2-rc.13_universal.apk",
                    "downloadUrl": "$realUniversalUrl",
                    "size": $expectedUniversalSize
                }
            ],
            "32.56-vox.9.2": {
                "versionCode": 2446032,
                "downloadUrl": "$realUniversalUrl"
            }
        }
        """.trimIndent()

        // 1. Client 9.1
        val release91 = VoxOtaReleaseParser.parseRelease(
            jsonB3,
            channel = VoxUpdateChannel.STABLE,
            currentVersion = "32.56-vox.9.1",
            installedVersionCode = 2446016L
        )
        assertEquals("32.56-vox.9.2", release91.versionName)
        assertEquals(2446032L, release91.versionCode)
        assertEquals(5, release91.assets.size)

        val isUpdateAvail91 = VoxVersionComparator.isUpdateAvailable(
            "32.56-vox.9.1",
            release91.versionName,
            VoxUpdateChannel.STABLE,
            2446016L,
            release91.versionCode
        )
        assertTrue("Update must be available for 9.1 in B3", isUpdateAvail91)

        val selectedAsset91 = VoxReleaseAssetSelector.selectAsset(release91.assets)
        assertNotNull("Asset selection must succeed in B3", selectedAsset91)
        assertEquals("SmartTube_vot_32.56-vox.9.2-rc.13_universal.apk", selectedAsset91!!.name)

        // 2. Client 9.2 -> NO UPDATE
        val release92 = VoxOtaReleaseParser.parseRelease(
            jsonB3,
            channel = VoxUpdateChannel.STABLE,
            currentVersion = "32.56-vox.9.2-rc.13",
            installedVersionCode = 2446032L
        )
        val isUpdateAvail92 = VoxVersionComparator.isUpdateAvailable(
            "32.56-vox.9.2-rc.13",
            release92.versionName,
            VoxUpdateChannel.STABLE,
            2446032L,
            release92.versionCode
        )
        assertFalse("9.2 must NOT update to itself in B3", isUpdateAvail92)
    }
}
