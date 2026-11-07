package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тестирование совместимости старых парсеров и логики политики версий (Gate 1).
 *
 * Проверяет математическую и логическую модель перехода:
 * 8, 8.1, 9, 9.1, 9.2 -> 9.3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoxOtaLegacyCompatibilityTest {

    @Test
    fun testParseCandidate93Version() {
        val parsed = VoxVersionComparator.parseVersion("32.71-vox.9.3")
        assertNotNull(parsed)
        assertEquals(32, parsed?.major)
        assertEquals(71, parsed?.minor)
        assertEquals(9, parsed?.voxMajor)
        assertEquals(3, parsed?.voxMinor)
        assertEquals(VoxReleaseStage.STABLE, parsed?.stage)
        // В силу формулы VoxVersionComparator: (voxMajor >= 9 && voxMinor > 0) -> isPreRelease == true
        assertTrue("vox.9.3 has voxMajor>=9 && voxMinor>0 so isPreRelease is true in legacy logic", parsed?.isPreRelease == true)
    }

    @Test
    fun test91And92ClientsAccept93OnBetaChannel() {
        // 9.1 и 9.2 имеют buildChannel == BETA по умолчанию
        val canUpdate91 = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9.1",
            targetVersion = "32.71-vox.9.3",
            channel = VoxUpdateChannel.BETA,
            installedVersionCode = 2446016L,
            targetVersionCode = 2446050L
        )
        assertTrue("9.1 client on BETA channel must see 9.3", canUpdate91)

        val canUpdate92 = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9.2-rc.13",
            targetVersion = "32.71-vox.9.3",
            channel = VoxUpdateChannel.BETA,
            installedVersionCode = 2446032L,
            targetVersionCode = 2446050L
        )
        assertTrue("9.2-rc.13 client on BETA channel must see 9.3", canUpdate92)
    }

    @Test
    fun testLegacyStable8ClientsBlockedByPreReleasePolicy() {
        // Проверка доказанного инварианта: клиенты 8 / 8.1 на STABLE канале отклоняют 9.x (где x > 0)
        val canUpdate8 = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.8",
            targetVersion = "32.71-vox.9.3",
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = 2446011L,
            targetVersionCode = 2446050L
        )
        // BLOCKED_LEGACY_VERSION_POLICY: 8 stable rejects 9.3 because 9.3 is flagged as pre-release
        assertFalse("Legacy 8 client on STABLE channel rejects 9.3 due to PRE_RELEASE_ON_STABLE policy", canUpdate8)

        val canUpdate81 = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.8.1",
            targetVersion = "32.71-vox.9.3",
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = 2446012L,
            targetVersionCode = 2446050L
        )
        assertFalse("Legacy 8.1 client on STABLE channel rejects 9.3 due to PRE_RELEASE_ON_STABLE policy", canUpdate81)
    }

    @Test
    fun testVersionCodeMonotonicityGuard() {
        // Если versionCode кандидата меньше или равен установленному, обновление отклоняется
        val rejectedSameVc = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9.2-rc.13",
            targetVersion = "32.71-vox.9.3",
            channel = VoxUpdateChannel.BETA,
            installedVersionCode = 2446050L,
            targetVersionCode = 2446050L
        )
        assertFalse("Same versionCode must be rejected", rejectedSameVc)

        val rejectedLowerVc = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9.2-rc.13",
            targetVersion = "32.71-vox.9.3",
            channel = VoxUpdateChannel.BETA,
            installedVersionCode = 2446050L,
            targetVersionCode = 2446032L
        )
        assertFalse("Lower versionCode must be rejected", rejectedLowerVc)
    }

    @Test
    fun testParseCandidateManifest() {
        val manifestJson = """
        {
          "package": {
            "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.9.3/SmartTube_vot_32.71-vox.9.3_universal.apk"
          },
          "32.71-vox.9.3": {
            "versionCode": 2446050,
            "prerelease": false,
            "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.9.3/SmartTube_vot_32.71-vox.9.3_universal.apk",
            "sha256": "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            "size": 65000000,
            "assets": [
              {
                "name": "SmartTube_vot_32.71-vox.9.3_arm64-v8a.apk",
                "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.9.3/SmartTube_vot_32.71-vox.9.3_arm64-v8a.apk",
                "size": 32000000
              },
              {
                "name": "SmartTube_vot_32.71-vox.9.3_armeabi-v7a.apk",
                "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.9.3/SmartTube_vot_32.71-vox.9.3_armeabi-v7a.apk",
                "size": 30000000
              },
              {
                "name": "SmartTube_vot_32.71-vox.9.3_universal.apk",
                "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.9.3/SmartTube_vot_32.71-vox.9.3_universal.apk",
                "size": 65000000
              },
              {
                "name": "SHA256SUMS.txt",
                "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.9.3/SHA256SUMS.txt",
                "size": 350
              }
            ]
          }
        }
        """.trimIndent()

        val release = VoxOtaReleaseParser.parseRelease(
            jsonString = manifestJson,
            channel = VoxUpdateChannel.BETA,
            currentVersion = "32.56-vox.9.2-rc.13",
            installedVersionCode = 2446032L
        )

        assertNotNull(release)
        assertEquals("32.71-vox.9.3", release.versionName)
        assertEquals(2446050L, release.versionCode)
    }

    // =========================================================================
    // Gate B / Gate C: Строгая эмуляция исторического компаратора всех 5 тегов
    // =========================================================================

    /**
     * Точная копия компаратора версий из оригинальных тегов v32.56-vox.8 и v32.56-vox.8.1.
     * voxMajor -> voxMinor -> major -> minor -> stage (dev < rc < final).
     * isPreRelease: isDev || rcNumber != null.
     */
    private fun legacyCompareVox8(installedVer: String, candidateVer: String): Int {
        val pInstalled = parseLegacyVersion(installedVer)
        val pCandidate = parseLegacyVersion(candidateVer)
        if (pInstalled.voxMajor != pCandidate.voxMajor) return pCandidate.voxMajor.compareTo(pInstalled.voxMajor)
        if (pInstalled.voxMinor != pCandidate.voxMinor) return pCandidate.voxMinor.compareTo(pInstalled.voxMinor)
        if (pInstalled.major != pCandidate.major) return pCandidate.major.compareTo(pInstalled.major)
        if (pInstalled.minor != pCandidate.minor) return pCandidate.minor.compareTo(pInstalled.minor)
        return pCandidate.stageLevel.compareTo(pInstalled.stageLevel)
    }

    /**
     * Точная копия компаратора из v32.56-vox.9.1-release и v32.56-vox.9.2.
     * isPreRelease = stage != STABLE || (voxMajor >= 9 && voxMinor > 0)
     * isCanonicalStable = stage == STABLE && (voxMajor < 9 || voxMinor == 0)
     */
    private fun legacyIsUpdateAvailableVox92(installedVer: String, candidateVer: String, isBeta: Boolean): Boolean {
        val pInstalled = parseLegacyVersion(installedVer)
        val pCandidate = parseLegacyVersion(candidateVer)

        val candidateIsPre = pCandidate.stageLevel < 100 || (pCandidate.voxMajor >= 9 && pCandidate.voxMinor > 0)
        val candidateIsCanonicalStable = pCandidate.stageLevel == 100 && (pCandidate.voxMajor < 9 || pCandidate.voxMinor == 0)

        if (!isBeta && !candidateIsCanonicalStable) {
            return false // Stable channel rejects non-canonical stable
        }

        if (pInstalled.voxMajor != pCandidate.voxMajor) return pCandidate.voxMajor > pInstalled.voxMajor
        if (pInstalled.voxMinor != pCandidate.voxMinor) return pCandidate.voxMinor > pInstalled.voxMinor
        if (pInstalled.major != pCandidate.major) return pCandidate.major > pInstalled.major
        if (pInstalled.minor != pCandidate.minor) return pCandidate.minor > pInstalled.minor
        return pCandidate.stageLevel > pInstalled.stageLevel
    }

    private data class LegacyParsed(
        val major: Int, val minor: Int,
        val voxMajor: Int, val voxMinor: Int,
        val stageLevel: Int // -1: dev, 1..99: rc, 100: stable
    )

    private fun parseLegacyVersion(ver: String): LegacyParsed {
        val match = Regex("""^(\d+)\.(\d+)-(?:vox|vot)\.(\d+)(?:\.(\d+))?(?:-(dev|rc\.?(\d+)))?""", RegexOption.IGNORE_CASE).find(ver)
            ?: throw IllegalArgumentException("Unparseable: $ver")
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        val voxMajor = match.groupValues[3].toInt()
        val voxMinor = match.groupValues[4].takeIf { it.isNotBlank() }?.toInt() ?: 0
        val devTag = match.groupValues[5]
        val rcNum = match.groupValues[6].takeIf { it.isNotBlank() }?.toInt()

        val stageLevel = when {
            devTag.equals("dev", ignoreCase = true) -> -1
            rcNum != null -> rcNum
            else -> 100 // Final/Stable
        }
        return LegacyParsed(major, minor, voxMajor, voxMinor, stageLevel)
    }

    @Test
    fun testLegacyFiveTagsDirectGoalVsBridgeMatrix() {
        val installedTags = listOf(
            "32.56-vox.8",
            "32.56-vox.8.1",
            "32.56-vox.9",
            "32.56-vox.9.1",
            "32.56-vox.9.2-rc.13"
        )

        // 1. Прямая цель 32.71-vox.1 — отвергается ВСЕМИ историческими клиентами (Downgrade: voxMajor 8,9 > 1)
        for (installed in installedTags) {
            val cmpVox8 = legacyCompareVox8(installed, "32.71-vox.1")
            assertTrue("Client $installed MUST reject 32.71-vox.1 as downgrade under vox8 comparator", cmpVox8 < 0)

            val updateVox92Stable = legacyIsUpdateAvailableVox92(installed, "32.71-vox.1", isBeta = false)
            assertFalse("Client $installed MUST reject 32.71-vox.1 on Stable under vox92 comparator", updateVox92Stable)

            val updateVox92Beta = legacyIsUpdateAvailableVox92(installed, "32.71-vox.1", isBeta = true)
            assertFalse("Client $installed MUST reject 32.71-vox.1 on Beta under vox92 comparator", updateVox92Beta)
        }

        // 2. Мост 32.71-vox.10 — принимается ВСЕМИ пятью клиентами на ОБОИХ каналах (Stable и Beta)!
        for (installed in installedTags) {
            val cmpVox8 = legacyCompareVox8(installed, "32.71-vox.10")
            assertTrue("Client $installed MUST accept 32.71-vox.10 as upgrade under vox8 comparator", cmpVox8 > 0)

            val updateVox92Stable = legacyIsUpdateAvailableVox92(installed, "32.71-vox.10", isBeta = false)
            assertTrue("Client $installed MUST accept 32.71-vox.10 on Stable under vox92 comparator", updateVox92Stable)

            val updateVox92Beta = legacyIsUpdateAvailableVox92(installed, "32.71-vox.10", isBeta = true)
            assertTrue("Client $installed MUST accept 32.71-vox.10 on Beta under vox92 comparator", updateVox92Beta)
        }
    }

    @Test
    fun testDualFeedParsingAndNPlus2Rotation() {
        // Тест схемы smarttube_vox_v2.json
        val v2Json = """
        {
          "schemaVersion": 2,
          "version": "32.71-vox.10",
          "versionCode": 2446050,
          "minSdk": 21,
          "targetSdk": 34,
          "changelog": [
            "SmartTube VOX 9.3 Bridge Release",
            "SmartTube 32.71s engine upgrade",
            "VOT 1.12.6 integration"
          ],
          "assets": [
            {
              "name": "SmartTube_vot_32.71-vox.10_arm64-v8a.apk",
              "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.10/SmartTube_vot_32.71-vox.10_arm64-v8a.apk",
              "size": 25000000,
              "sha256": "1111111111111111111111111111111111111111111111111111111111111111"
            },
            {
              "name": "SmartTube_vot_32.71-vox.10_armeabi-v7a.apk",
              "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.10/SmartTube_vot_32.71-vox.10_armeabi-v7a.apk",
              "size": 23000000,
              "sha256": "2222222222222222222222222222222222222222222222222222222222222222"
            },
            {
              "name": "SmartTube_vot_32.71-vox.10_universal.apk",
              "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.10/SmartTube_vot_32.71-vox.10_universal.apk",
              "size": 42000000,
              "sha256": "3333333333333333333333333333333333333333333333333333333333333333"
            }
          ],
          "sha256SumsUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.10/SHA256SUMS.txt"
        }
        """.trimIndent()

        val parsedV2 = VoxOtaReleaseParser.parseRelease(v2Json, channel = VoxUpdateChannel.STABLE)
        assertNotNull(parsedV2)
        assertEquals("32.71-vox.10", parsedV2.versionName)
        assertEquals(2446050L, parsedV2.versionCode)
        assertEquals(3, parsedV2.assets.size)
        assertEquals("1111111111111111111111111111111111111111111111111111111111111111", parsedV2.assets[0].sha256)
        assertEquals("https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.10/SHA256SUMS.txt", parsedV2.sha256SumsUrl)

        // N+1 ротация: выход будущего 33.25-vox.1 в modern feed
        val futureModernJson = """
        {
          "schemaVersion": 2,
          "version": "33.25-vox.1",
          "versionCode": 2446100,
          "changelog": ["Upstream 33.25 update"],
          "assets": [
            {
              "name": "SmartTube_vot_33.25-vox.1_universal.apk",
              "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v33.25-vox.1/SmartTube_vot_33.25-vox.1_universal.apk",
              "size": 43000000
            }
          ]
        }
        """.trimIndent()

        // 1. Клиент на Bridge (32.71-vox.10) обновляется до 33.25-vox.1 благодаря engine-first
        val modernParsed = VoxOtaReleaseParser.parseRelease(futureModernJson, channel = VoxUpdateChannel.STABLE)
        val canUpdateModern = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.71-vox.10",
            targetVersion = modernParsed.versionName,
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = 2446050L,
            targetVersionCode = modernParsed.versionCode ?: 0L
        )
        assertTrue("Bridge client 32.71-vox.10 must upgrade to 33.25-vox.1 via engine-first", canUpdateModern)

        // 2. В то же время старый клиент VOX 8 запрашивает pinned smarttube_vox.json (который указывает на v32.71-vox.10)
        val legacyShimJson = """
        {
          "version": "32.71-vox.10",
          "versionCode": 2446050,
          "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.10/SmartTube_vot_32.71-vox.10_universal.apk"
        }
        """.trimIndent()
        val legacyParsed = VoxOtaReleaseParser.parseRelease(legacyShimJson, channel = VoxUpdateChannel.STABLE)
        assertEquals("32.71-vox.10", legacyParsed.versionName)
        val canLegacyUpgrade = legacyIsUpdateAvailableVox92("32.56-vox.8", legacyParsed.versionName, isBeta = false)
        assertTrue("Old client retains valid upgrade to bridge even after future releases", canLegacyUpgrade)
    }

    @Test
    fun testErrorAndMalformedManifestHandling() {
        // 1. 404 HTML body
        try {
            VoxOtaReleaseParser.parseRelease("<html><body>404 Not Found</body></html>")
            org.junit.Assert.fail("Expected exception for HTML response")
        } catch (e: VoxOtaException) {
            assertEquals(VoxOtaErrorCode.RELEASE_PARSE_FAILED, e.code)
        }

        // 2. Empty string
        try {
            VoxOtaReleaseParser.parseRelease("   ")
            org.junit.Assert.fail("Expected exception for blank response")
        } catch (e: VoxOtaException) {
            assertEquals(VoxOtaErrorCode.RELEASE_PARSE_FAILED, e.code)
        }

        // 3. Invalid JSON syntax
        try {
            VoxOtaReleaseParser.parseRelease("{invalid_json: true")
            org.junit.Assert.fail("Expected exception for malformed JSON")
        } catch (e: VoxOtaException) {
            assertEquals(VoxOtaErrorCode.RELEASE_PARSE_FAILED, e.code)
        }
    }
}
