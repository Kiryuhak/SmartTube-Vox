package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class VoxOtaTest {

    @Test
    fun testVersionParsingAndComparison() {
        // 6.1 < 7 (P0 case)
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.6.1", "32.56-vox.7"))
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vot.6.1", "32.56-vox.7"))
        assertTrue(VoxVersionComparator.isUpdateAvailable("6.1", "7"))

        // 7 == 7 (no update)
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.7", "32.56-vox.7"))
        assertFalse(VoxVersionComparator.isUpdateAvailable("7", "7"))
        assertEquals(0, VoxVersionComparator.compare("32.56-vox.7", "32.56-vox.7"))

        // 7 stable ignores 8-dev on stable channel
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.7", "32.56-vox.8-dev", isBetaChannel = false))
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.7", "32.56-vox.8-dev", isBetaChannel = true))

        // 8-dev vs 7: already ahead, no update
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8-dev", "32.56-vox.7"))
        assertTrue(VoxVersionComparator.compare("32.56-vox.8-dev", "32.56-vox.7") > 0)

        // RC tests (Patch #15)
        // 7 < 8-rc1
        assertTrue(VoxVersionComparator.compare("32.56-vox.7", "32.56-vox.8-rc1") < 0)
        // 8-dev < 8-rc1
        assertTrue(VoxVersionComparator.compare("32.56-vox.8-dev", "32.56-vox.8-rc1") < 0)
        // 8-rc1 < 8
        assertTrue(VoxVersionComparator.compare("32.56-vox.8-rc1", "32.56-vox.8") < 0)
        // 8-rc1 == 8-rc1
        assertEquals(0, VoxVersionComparator.compare("32.56-vox.8-rc1", "32.56-vox.8-rc1"))
        // 8-rc1 < 8-rc2
        assertTrue(VoxVersionComparator.compare("32.56-vox.8-rc1", "32.56-vox.8-rc2") < 0)

        // Stable channel: 7 ignores 8-rc1
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.7", "32.56-vox.8-rc1", isBetaChannel = false))
        // Beta channel: 7 sees 8-rc1
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.7", "32.56-vox.8-rc1", isBetaChannel = true))

        // Pre-release users (8-dev) get update to 8-rc1
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.8-dev", "32.56-vox.8-rc1", isBetaChannel = false))
        // 8-rc1 users get update to final 8
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.8-rc1", "32.56-vox.8", isBetaChannel = false))
    }

    @Test
    fun testAssetSelectionFilteringAndAbi() {
        val assets = listOf(
            VoxReleaseAsset("SmartTube-VOX-7.0.0.wgt", "https://example.com/SmartTube-VOX-7.0.0.wgt", 5000000L),
            VoxReleaseAsset("SHA256SUMS.txt", "https://example.com/SHA256SUMS.txt", 1024L),
            VoxReleaseAsset("SmartTube_vot_32.56-vox.7_armeabi-v7a.apk", "https://example.com/armv7.apk", 18000000L),
            VoxReleaseAsset("SmartTube_vot_32.56-vox.7_arm64-v8a.apk", "https://example.com/arm64.apk", 19000000L),
            VoxReleaseAsset("SmartTube_vot_32.56-vox.7_universal.apk", "https://example.com/univ.apk", 25000000L),
            VoxReleaseAsset("SmartTube_vot_32.56-vox.7_x86.apk", "https://example.com/x86.apk", 19000000L)
        )

        // Android updater must ignore .wgt and SHA256SUMS.txt
        val arm64Selected = VoxReleaseAssetSelector.selectAsset(assets, "arm64-v8a")
        assertNotNull(arm64Selected)
        assertEquals("SmartTube_vot_32.56-vox.7_arm64-v8a.apk", arm64Selected!!.name)

        val armv7Selected = VoxReleaseAssetSelector.selectAsset(assets, "armeabi-v7a")
        assertNotNull(armv7Selected)
        assertEquals("SmartTube_vot_32.56-vox.7_armeabi-v7a.apk", armv7Selected!!.name)

        val x86Selected = VoxReleaseAssetSelector.selectAsset(assets, "x86")
        assertNotNull(x86Selected)
        assertEquals("SmartTube_vot_32.56-vox.7_x86.apk", x86Selected!!.name)

        // Universal fallback when ABI is unknown or null
        val universalSelected = VoxReleaseAssetSelector.selectAsset(assets, "mips")
        assertNotNull(universalSelected)
        assertEquals("SmartTube_vot_32.56-vox.7_universal.apk", universalSelected!!.name)

        // Finding SHA256SUMS.txt
        val sumsAsset = VoxReleaseAssetSelector.findSha256SumsAsset(assets)
        assertNotNull(sumsAsset)
        assertEquals("SHA256SUMS.txt", sumsAsset!!.name)
    }

    @Test
    fun testReleaseParserHtmlDetectionAndJsonParsing() {
        // HTML error page must throw RELEASE_PARSE_FAILED without Java JSONException
        val htmlPage = "<!DOCTYPE html>\n<html><body>404 Not Found</body></html>"
        try {
            VoxOtaReleaseParser.parseRelease(htmlPage)
            fail("Should fail on HTML input")
        } catch (e: VoxOtaException) {
            assertEquals(VoxOtaErrorCode.RELEASE_PARSE_FAILED, e.code)
            assertFalse(e.userMessageRu.contains("java.lang"))
        }

        // GitHub Release JSON fixture
        val githubJson = """
            {
              "tag_name": "v32.56-vox.7",
              "name": "SmartTube VOX 7.0.0",
              "body": "Fixes and improvements:\n- Audio fix\n- OTA support",
              "assets": [
                {
                  "name": "SmartTube_vot_32.56-vox.7_arm64-v8a.apk",
                  "browser_download_url": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.7/SmartTube_vot_32.56-vox.7_arm64-v8a.apk",
                  "size": 19000000
                },
                {
                  "name": "SHA256SUMS.txt",
                  "browser_download_url": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.7/SHA256SUMS.txt",
                  "size": 512
                }
              ]
            }
        """.trimIndent()

        val parsed = VoxOtaReleaseParser.parseRelease(githubJson)
        assertEquals("32.56-vox.7", parsed.versionName)
        assertEquals(2, parsed.assets.size)
        assertNotNull(parsed.sha256SumsUrl)
        assertTrue(parsed.changelog.size >= 2)
    }

    @Test
    fun testSha256Verification() {
        val sumsContent = """
            # Checksums for SmartTube VOX 7
            e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  SmartTube_vot_32.56-vox.7_empty.apk
            ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  SmartTube_vot_32.56-vox.7_abc.apk
        """.trimIndent()

        val sums = VoxOtaSecurityVerifier.parseSha256Sums(sumsContent)
        assertEquals(2, sums.size)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sums["SmartTube_vot_32.56-vox.7_abc.apk"])

        val tempFile = File.createTempFile("vox_test_", ".apk")
        try {
            tempFile.writeText("abc")
            assertTrue(VoxOtaSecurityVerifier.verifyFileSha256(tempFile, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
            assertFalse(VoxOtaSecurityVerifier.verifyFileSha256(tempFile, "0000000000000000000000000000000000000000000000000000000000000000"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testExpectedSignerFingerprint() {
        assertEquals("e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d", VoxOtaSecurityVerifier.EXPECTED_SIGNER_SHA256)
    }

    @Test
    fun testTypedErrorsNeverShowJavaExceptions() {
        for (code in VoxOtaErrorCode.values()) {
            val ex = VoxOtaException(code, "Internal error")
            assertFalse(ex.userMessageRu.contains("java.lang"))
            assertFalse(ex.userMessageRu.contains("Exception"))
            assertTrue(ex.userMessageRu.isNotBlank())
        }
    }
}
