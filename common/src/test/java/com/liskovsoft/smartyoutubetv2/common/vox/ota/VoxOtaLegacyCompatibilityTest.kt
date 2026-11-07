package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тестирование совместимости старых парсеров и логики политики версий (Gate 1).
 *
 * Проверяет математическую и логическую модель перехода:
 * 8, 8.1, 9, 9.1, 9.2 -> 9.3.
 */
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
}
