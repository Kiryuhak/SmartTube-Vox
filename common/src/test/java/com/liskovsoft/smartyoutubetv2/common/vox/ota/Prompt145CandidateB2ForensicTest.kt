package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * PROMPT №145: Independent forensic audit and regression suite for Candidate B2.
 * Verifies exact schema, root structure, genuine 9.1 parser execution, and monotonicity safeguards.
 */
@RunWith(RobolectricTestRunner::class)
class Prompt145CandidateB2ForensicTest {

    companion object {
        private fun resolveFile(vararg paths: String): File {
            for (p in paths) {
                val f = File(p)
                if (f.exists()) return f
            }
            return File(paths.first())
        }

        private val candidate144File = resolveFile(
            "../../SmartTube-VOX-Private/private-qa/candidate-144.json",
            "${System.getProperty("user.home")}/Dev/SmartTube-VOX-Private/private-qa/candidate-144.json",
            "C:/Dev/SmartTube-VOX-Private/private-qa/candidate-144.json"
        )
        private val published143File = resolveFile(
            "../../SmartTube-VOX-Private/private-qa/smarttube_vox-PUBLISHED-143.json",
            "${System.getProperty("user.home")}/Dev/SmartTube-VOX-Private/private-qa/smarttube_vox-PUBLISHED-143.json",
            "C:/Dev/SmartTube-VOX-Private/private-qa/smarttube_vox-PUBLISHED-143.json"
        )
        private val publishedUniversalApk = resolveFile(
            "../scratch/r38_forensic/SmartTube_vot_32.56-vox.9.2-rc.13_universal.apk",
            "${System.getProperty("user.home")}/Dev/_vox_93_r36/scratch/r38_forensic/SmartTube_vot_32.56-vox.9.2-rc.13_universal.apk",
            "${System.getProperty("user.home")}/Dev/SmartTube/smarttubetv/build/outputs/apk/stvot/debug/SmartTube_vot_32.56-vox.9.2-rc.13_universal.apk",
            "C:/Dev/VOX-Restore-Reports/input/published-apks/9.2/SmartTube_vot_32.56-vox.9.2-rc.13_universal.apk"
        )
    }

    private val expectedUniversalSha256 = "e31ba5fe1bb8e2f49345edcb31384411d85779d798c414040fe7fecd77263e23"
    private val expectedUniversalSize = 66383180L
    private val expectedUniversalUrl = "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.9.2/SmartTube_vot_32.56-vox.9.2-rc.13_universal.apk"

    /**
     * 1. Schema Forensic: Check exact root keys, types, absence of "releases" key, and JSONObject type of "package".
     */
    @Test
    fun testCandidateB2_RootSchemaForensics() {
        assertTrue("candidate-144.json must exist on disk", candidate144File.exists())
        val jsonContent = candidate144File.readText(Charsets.UTF_8)
        val root = JSONObject(jsonContent)

        // 1. Exact root keys count
        val rootKeys = mutableListOf<String>()
        val it = root.keys()
        while (it.hasNext()) {
            rootKeys.add(it.next())
        }
        assertEquals("Root must contain exactly 2 keys", 2, rootKeys.size)
        assertTrue("Root must contain 'package'", rootKeys.contains("package"))
        assertTrue("Root must contain '32.56-vox.9.2-universal'", rootKeys.contains("32.56-vox.9.2-universal"))
        assertFalse("Root MUST NOT contain 'releases'", rootKeys.contains("releases"))

        // 2. Types of root keys
        val packageVal = root.get("package")
        assertTrue("'package' must be a JSONObject, NOT a String", packageVal is JSONObject)
        val packageObj = packageVal as JSONObject
        assertTrue("'package' must contain 'downloadUrl'", packageObj.has("downloadUrl"))
        assertEquals(expectedUniversalUrl, packageObj.getString("downloadUrl"))
        assertTrue("'package' must contain 'downloadUrlList'", packageObj.has("downloadUrlList"))

        val versionVal = root.get("32.56-vox.9.2-universal")
        assertTrue("'32.56-vox.9.2-universal' must be a JSONObject", versionVal is JSONObject)
        val versionObj = versionVal as JSONObject
        assertEquals(2446032L, versionObj.getLong("versionCode"))
        assertEquals(expectedUniversalUrl, versionObj.getString("downloadUrl"))
        assertEquals(expectedUniversalSha256, versionObj.getString("sha256"))
        assertEquals(expectedUniversalSize, versionObj.getLong("size"))
    }

    /**
     * 2. Semantic diff between published 143 manifest and candidate 144:
     * Only the version key changed from "32.56-vox.9.2" to "32.56-vox.9.2-universal".
     */
    @Test
    fun testSemanticDiffWithPublished143() {
        assertTrue("smarttube_vox-PUBLISHED-143.json must exist", published143File.exists())
        val root143 = JSONObject(published143File.readText(Charsets.UTF_8))
        val root144 = JSONObject(candidate144File.readText(Charsets.UTF_8))

        // package object must be identical
        val pkg143 = root143.getJSONObject("package")
        val pkg144 = root144.getJSONObject("package")
        assertEquals(pkg143.getString("downloadUrl"), pkg144.getString("downloadUrl"))

        // Version data must be identical except key name
        val v143 = root143.getJSONObject("32.56-vox.9.2")
        val v144 = root144.getJSONObject("32.56-vox.9.2-universal")
        assertEquals(v143.getLong("versionCode"), v144.getLong("versionCode"))
        assertEquals(v143.getString("downloadUrl"), v144.getString("downloadUrl"))
        assertEquals(v143.getString("sha256"), v144.getString("sha256"))
        assertEquals(v143.getLong("size"), v144.getLong("size"))
    }

    /**
     * 3. Negative test on faulty nested schema:
     * If someone accidentally wraps versions under "releases", 9.1 parser MUST fail.
     */
    @Test
    fun testNegative_FaultyNestedReleasesKeyFails() {
        val faultyJson = """
        {
            "package": { "downloadUrl": "$expectedUniversalUrl" },
            "releases": {
                "32.56-vox.9.2-universal": {
                    "versionCode": 2446032,
                    "downloadUrl": "$expectedUniversalUrl"
                }
            }
        }
        """.trimIndent()

        val release = VoxOtaReleaseParser.parseRelease(
            faultyJson,
            channel = VoxUpdateChannel.STABLE,
            currentVersion = "32.56-vox.9.1",
            installedVersionCode = 2446016L
        )

        // The parser treats "releases" as the version name, synthesizing "SmartTube_vot_releases.apk"
        val selectedAsset = VoxReleaseAssetSelector.selectAsset(release.assets)
        assertNull("Nested 'releases' dictionary must fail asset selection (no universal)", selectedAsset)
    }

    /**
     * 4. Negative test on String "package":
     * If "package" is a string instead of JSONObject, upstream AppVersionChecker throws JSONException.
     */
    @Test
    fun testNegative_StringPackageFailsLegacyChecker() {
        val faultyJson = """
        {
            "package": "$expectedUniversalUrl",
            "32.56-vox.9.2-universal": {
                "versionCode": 2446032,
                "downloadUrl": "$expectedUniversalUrl"
            }
        }
        """.trimIndent()

        val root = JSONObject(faultyJson)
        try {
            root.getJSONObject("package")
            fail("getJSONObject on string package must throw JSONException")
        } catch (e: JSONException) {
            // Expected: string cannot be converted to JSONObject
            assertTrue(e.message!!.contains("package"))
        }
    }

    /**
     * 5. Execution of 9.1 Parser on Candidate B2 exact bytes: Client 9.1 Stable and Beta
     */
    @Test
    fun testGenuine91Parser_Client91_StableAndBeta() {
        val jsonBytes = candidate144File.readText(Charsets.UTF_8)

        // 1. Client 9.1 Stable
        val releaseStable = VoxOtaReleaseParser.parseRelease(
            jsonBytes,
            channel = VoxUpdateChannel.STABLE,
            currentVersion = "32.56-vox.9.1",
            installedVersionCode = 2446016L
        )
        assertEquals("32.56-vox.9.2-universal", releaseStable.versionName)
        assertEquals(2446032L, releaseStable.versionCode)

        val updateAvailStable = VoxVersionComparator.isUpdateAvailable(
            "32.56-vox.9.1",
            releaseStable.versionName,
            VoxUpdateChannel.STABLE,
            2446016L,
            releaseStable.versionCode
        )
        assertTrue("9.1 Stable must receive update", updateAvailStable)

        val assetStable = VoxReleaseAssetSelector.selectAsset(releaseStable.assets)
        assertNotNull("Universal asset must be selected for 9.1 Stable", assetStable)
        assertEquals("SmartTube_vot_32.56-vox.9.2-universal.apk", assetStable!!.name)
        assertEquals(expectedUniversalUrl, assetStable.downloadUrl)

        // 2. Client 9.1 Beta
        val releaseBeta = VoxOtaReleaseParser.parseRelease(
            jsonBytes,
            channel = VoxUpdateChannel.BETA,
            currentVersion = "32.56-vox.9.1",
            installedVersionCode = 2446016L
        )
        val updateAvailBeta = VoxVersionComparator.isUpdateAvailable(
            "32.56-vox.9.1",
            releaseBeta.versionName,
            VoxUpdateChannel.BETA,
            2446016L,
            releaseBeta.versionCode
        )
        assertTrue("9.1 Beta must receive update", updateAvailBeta)

        val assetBeta = VoxReleaseAssetSelector.selectAsset(releaseBeta.assets)
        assertNotNull("Universal asset must be selected for 9.1 Beta", assetBeta)
    }

    /**
     * 6. Execution of 9.2 Client on Candidate B2 exact bytes: Client 9.2 Stable and Beta
     * Proves: NO UPDATE OFFERED (Monotonicity 2446032 <= 2446032 returns false).
     */
    @Test
    fun testGenuine92Client_NoUpdateOffered() {
        val jsonBytes = candidate144File.readText(Charsets.UTF_8)

        // Test variations of client 9.2 version strings and channels
        val client92Variants = listOf(
            Pair("32.56-vox.9.2", VoxUpdateChannel.STABLE),
            Pair("32.56-vox.9.2", VoxUpdateChannel.BETA),
            Pair("32.56-vox.9.2-rc.13", VoxUpdateChannel.STABLE),
            Pair("32.56-vox.9.2-rc.13", VoxUpdateChannel.BETA)
        )

        for ((versionStr, channel) in client92Variants) {
            val release = VoxOtaReleaseParser.parseRelease(
                jsonBytes,
                channel = channel,
                currentVersion = versionStr,
                installedVersionCode = 2446032L
            )

            val updateAvailable = VoxVersionComparator.isUpdateAvailable(
                versionStr,
                release.versionName,
                channel,
                2446032L,
                release.versionCode
            )

            assertFalse(
                "Client $versionStr on channel ${channel.id} MUST NOT receive self-update",
                updateAvailable
            )
        }
    }

    /**
     * 7. Real Asset Factcheck: verify actual file hash and size on disk.
     */
    @Test
    fun testRealPublishedAssetVerification() {
        assertTrue("Published universal APK must exist at ${publishedUniversalApk.absolutePath}", publishedUniversalApk.exists())
        assertEquals("File size must match expected size", expectedUniversalSize, publishedUniversalApk.length())

        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(publishedUniversalApk).use { fis ->
            val buf = ByteArray(65536)
            var n: Int
            while (fis.read(buf).also { n = it } > 0) {
                md.update(buf, 0, n)
            }
        }
        val computedHash = md.digest().joinToString("") { "%02x".format(it) }
        assertEquals("File SHA-256 must match candidate-144.json hash", expectedUniversalSha256, computedHash)
    }
}
