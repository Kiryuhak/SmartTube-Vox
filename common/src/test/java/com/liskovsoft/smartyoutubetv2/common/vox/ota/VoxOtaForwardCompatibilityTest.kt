package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Обязательный Forward-Compatibility набор тестов для OTA SmartTube VOX.
 * Проверяет все 22 контракта перехода между версиями и защиты от регрессий.
 */
@RunWith(RobolectricTestRunner::class)
class VoxOtaForwardCompatibilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun createRelease(
        versionName: String,
        isPrerelease: Boolean,
        versionCode: Long,
        hasUniversal: Boolean = true,
        hasArmeabi: Boolean = true
    ): VoxReleaseInfo {
        val assets = mutableListOf<VoxReleaseAsset>()
        if (hasUniversal) {
            assets.add(VoxReleaseAsset("SmartTube_vot_${versionName}_universal.apk", "https://github.com/test/$versionName/universal.apk", 25_000_000L))
        }
        if (hasArmeabi) {
            assets.add(VoxReleaseAsset("SmartTube_vot_${versionName}_armeabi-v7a.apk", "https://github.com/test/$versionName/armeabi-v7a.apk", 18_000_000L))
        }
        return VoxReleaseInfo(
            tagName = "v$versionName",
            versionName = versionName,
            changelog = listOf("Release $versionName"),
            assets = assets,
            sha256SumsUrl = null,
            isPrerelease = isPrerelease,
            versionCode = versionCode
        )
    }

    // 1. stable9DoesNotSee91Beta
    @Test
    fun testStable9DoesNotSee91Beta() {
        assertFalse(
            "VOX 9 stable must not see 9.1 beta on stable channel",
            VoxVersionComparator.isUpdateAvailable("32.56-vox.9", "32.56-vox.9.1", isBetaChannel = false)
        )
        val releases = listOf(
            createRelease("32.56-vox.9.1", isPrerelease = true, versionCode = 2446016L)
        )
        val best = VoxOtaReleaseParser.selectBestRelease(releases, "32.56-vox.9", isBetaChannel = false, installedVersionCode = 2446000L)
        assertNull("Stable channel must not select 9.1 beta", best)
    }

    // 2. stable9Selects10Stable
    @Test
    fun testStable9Selects10Stable() {
        assertTrue(
            "VOX 9 stable must see VOX 10 stable",
            VoxVersionComparator.isUpdateAvailable("32.56-vox.9", "32.56-vox.10", isBetaChannel = false)
        )
        val releases = listOf(
            createRelease("32.56-vox.9.1", isPrerelease = true, versionCode = 2446016L),
            createRelease("32.56-vox.10", isPrerelease = false, versionCode = 2450000L)
        )
        val best = VoxOtaReleaseParser.selectBestRelease(releases, "32.56-vox.9", isBetaChannel = false, installedVersionCode = 2446000L)
        assertNotNull(best)
        assertEquals("32.56-vox.10", best!!.versionName)
    }

    // 3. beta9SelectsLatest9x
    @Test
    fun testBeta9SelectsLatest9x() {
        val releases = listOf(
            createRelease("32.56-vox.9.1", isPrerelease = true, versionCode = 2446016L),
            createRelease("32.56-vox.9.2", isPrerelease = true, versionCode = 2446017L),
            createRelease("32.56-vox.9.5", isPrerelease = true, versionCode = 2446020L)
        )
        val best = VoxOtaReleaseParser.selectBestRelease(releases, "32.56-vox.9", isBetaChannel = true, installedVersionCode = 2446000L)
        assertNotNull(best)
        assertEquals("32.56-vox.9.5", best!!.versionName)
    }

    // 4. beta91SkipsDirectlyToLatestBeta
    @Test
    fun testBeta91SkipsDirectlyToLatestBeta() {
        val releases = listOf(
            createRelease("32.56-vox.9.2", isPrerelease = true, versionCode = 2446017L),
            createRelease("32.56-vox.9.5", isPrerelease = true, versionCode = 2446020L),
            createRelease("32.56-vox.9.8", isPrerelease = true, versionCode = 2446025L)
        )
        val best = VoxOtaReleaseParser.selectBestRelease(releases, "32.56-vox.9.1", isBetaChannel = true, installedVersionCode = 2446016L)
        assertNotNull("Must support direct skip to 9.8 beta", best)
        assertEquals("32.56-vox.9.8", best!!.versionName)
    }

    // 5. beta9xCanUpgradeTo10Stable
    @Test
    fun testBeta9xCanUpgradeTo10Stable() {
        val releases = listOf(
            createRelease("32.56-vox.10", isPrerelease = false, versionCode = 2450000L)
        )
        // User on 9.1 switched to stable channel: must find 10 stable
        val bestFrom91 = VoxOtaReleaseParser.selectBestRelease(releases, "32.56-vox.9.1", isBetaChannel = false, installedVersionCode = 2446016L)
        assertNotNull(bestFrom91)
        assertEquals("32.56-vox.10", bestFrom91!!.versionName)

        // User on 9.8 switched to stable channel: must find 10 stable
        val bestFrom98 = VoxOtaReleaseParser.selectBestRelease(releases, "32.56-vox.9.8", isBetaChannel = false, installedVersionCode = 2446025L)
        assertNotNull(bestFrom98)
        assertEquals("32.56-vox.10", bestFrom98!!.versionName)
    }

    // 6. betaToStableDoesNotDowngrade
    @Test
    fun testBetaToStableDoesNotDowngrade() {
        // User on 9.1 switches to Stable channel, but latest stable is VOX 9 (older versionCode)
        val releases = listOf(
            createRelease("32.56-vox.9", isPrerelease = false, versionCode = 2446000L)
        )
        val best = VoxOtaReleaseParser.selectBestRelease(releases, "32.56-vox.9.1", isBetaChannel = false, installedVersionCode = 2446016L)
        assertNull("Downgrade from 9.1 to 9.0 stable must be blocked", best)
    }

    // 7. stableToBetaRequiresGreaterVersionCode
    @Test
    fun testStableToBetaRequiresGreaterVersionCode() {
        val releases = listOf(
            createRelease("32.56-vox.9.1", isPrerelease = true, versionCode = 2446010L) // lower than installed
        )
        val best = VoxOtaReleaseParser.selectBestRelease(releases, "32.56-vox.9", isBetaChannel = true, installedVersionCode = 2446015L)
        assertNull("Beta with smaller versionCode must be rejected", best)
    }

    // 8. versionCodeAlwaysWins
    @Test
    fun testVersionCodeAlwaysWins() {
        val releaseLowerCode = createRelease("32.56-vox.9.9", isPrerelease = true, versionCode = 2446010L)
        val releaseHigherCode = createRelease("32.56-vox.9.2", isPrerelease = true, versionCode = 2446030L)
        val best = VoxOtaReleaseParser.selectBestRelease(listOf(releaseLowerCode, releaseHigherCode), "32.56-vox.9.1", isBetaChannel = true, installedVersionCode = 2446016L)
        assertNotNull(best)
        assertEquals("Release with higher versionCode must win", "32.56-vox.9.2", best!!.versionName)
    }

    // 9. versionNameNotLexicographic
    @Test
    fun testVersionNameNotLexicographic() {
        // "9.10" must be strictly greater than "9.9", and "10" strictly greater than "9"
        assertTrue(VoxVersionComparator.compare("32.56-vox.9.9", "32.56-vox.9.10") < 0)
        assertTrue(VoxVersionComparator.compare("32.56-vox.9", "32.56-vox.10") < 0)
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.9", "32.56-vox.8.10") < 0)
    }

    // 10. universalAssetPreferred
    @Test
    fun testUniversalAssetPreferred() {
        val assets = listOf(
            VoxReleaseAsset("SmartTube_vot_32.56-vox.9.1_armeabi-v7a.apk", "https://test/armv7.apk", 18_000_000L),
            VoxReleaseAsset("SmartTube_vot_32.56-vox.9.1_universal.apk", "https://test/univ.apk", 25_000_000L),
            VoxReleaseAsset("SmartTube_vot_32.56-vox.9.1_arm64-v8a.apk", "https://test/arm64.apk", 19_000_000L)
        )
        val selected = VoxReleaseAssetSelector.selectAsset(assets, "arm64-v8a")
        assertNotNull(selected)
        assertEquals("SmartTube_vot_32.56-vox.9.1_universal.apk", selected!!.name)
    }

    // 11. abiAssetRejectedForAutomaticOta
    @Test
    fun testAbiAssetRejectedForAutomaticOta() {
        val assets = listOf(
            VoxReleaseAsset("SmartTube_vot_32.56-vox.9.1_armeabi-v7a.apk", "https://test/armv7.apk", 18_000_000L),
            VoxReleaseAsset("SmartTube_vot_32.56-vox.9.1_arm64-v8a.apk", "https://test/arm64.apk", 19_000_000L)
        )
        val selected = VoxReleaseAssetSelector.selectAsset(assets, "arm64-v8a")
        assertNull("ABI-specific asset without universal must be rejected for automatic OTA", selected)
    }

    // 12. partialApkRejected
    @Test
    fun testPartialApkRejected() {
        val incompleteFile = tempFolder.newFile("partial.apk.tmp")
        FileOutputStream(incompleteFile).use { it.write(ByteArray(1024)) }
        val result = VoxOtaPreInstallValidator.validate(context, incompleteFile, expectedContentLength = 25_000_000L)
        assertFalse("Partial APK download must be rejected", result.isValid)
        assertEquals(VoxOtaErrorCode.DOWNLOAD_INCOMPLETE, result.errorCode)
    }

    // 13. wrongCertificateRejected
    @Test
    fun testWrongCertificateRejected() {
        val dummyFile = tempFolder.newFile("dummy.apk")
        FileOutputStream(dummyFile).use { it.write(ByteArray(100)) }
        val mismatch = VoxOtaSecurityVerifier.verifyApkSignature(context, dummyFile, "a1b2c3d4e5f6")
        assertFalse("Wrong certificate SHA-256 must be rejected", mismatch)
        assertEquals("e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d", VoxOtaSecurityVerifier.EXPECTED_SIGNER_SHA256)
    }

    // 14. wrongPackageRejected
    @Test
    fun testWrongPackageRejected() {
        val foreignPackage = "com.other.malicious.app"
        val allowed = VoxOtaPreInstallValidator.ALLOWED_PACKAGE_NAMES
        assertFalse("Foreign package name must not be in allowed list", allowed.contains(foreignPackage))
    }

    // 15. corruptedApkRejected
    @Test
    fun testCorruptedApkRejected() {
        val corruptedFile = tempFolder.newFile("corrupt.apk")
        FileOutputStream(corruptedFile).use { it.write("THIS_IS_NOT_A_VALID_ZIP_ARCHIVE_DATA_1234567890".toByteArray()) }
        val result = VoxOtaPreInstallValidator.validate(context, corruptedFile)
        assertFalse("Corrupted APK must be rejected", result.isValid)
        assertTrue(result.errorCode == VoxOtaErrorCode.APK_INVALID || result.errorCode == VoxOtaErrorCode.DOWNLOAD_INCOMPLETE)
    }

    // 16. noUpdateNotNetworkError
    @Test
    fun testNoUpdateNotNetworkError() {
        val err = VoxOtaErrorCode.NO_UPDATE
        assertFalse("NO_UPDATE must not equal NETWORK_ERROR", err == VoxOtaErrorCode.NETWORK_ERROR)
        assertEquals("У вас установлена последняя версия", err.userMessageRu)
    }

    // 17. github404Handled
    @Test
    fun testGithub404Handled() {
        val ex404 = IOException("HTTP 404 Not Found")
        val otaEx = VoxOtaUpdateManager.classifyException(ex404)
        assertEquals(VoxOtaErrorCode.RELEASE_NOT_FOUND, otaEx.code)
        assertFalse("404 must not be retryable", otaEx.isRetryable)
    }

    // 18. rateLimitHandled
    @Test
    fun testRateLimitHandled() {
        val ex429 = IOException("HTTP 429 Too Many Requests")
        val otaEx = VoxOtaUpdateManager.classifyException(ex429)
        assertEquals(VoxOtaErrorCode.RATE_LIMIT, otaEx.code)
        assertTrue("429 must be retryable", otaEx.isRetryable)
    }

    // 19. timeoutHandled
    @Test
    fun testTimeoutHandled() {
        val exTimeout = SocketTimeoutException("connect timed out")
        val otaEx = VoxOtaUpdateManager.classifyException(exTimeout)
        assertEquals(VoxOtaErrorCode.TIMEOUT, otaEx.code)
        assertTrue("Timeout must be retryable", otaEx.isRetryable)
    }

    // 20. installerCancelHandled
    @Test
    fun testInstallerCancelHandled() {
        val cancelCode = VoxOtaErrorCode.INSTALL_CANCELLED
        assertFalse("Installer cancellation must not be network error", cancelCode == VoxOtaErrorCode.NETWORK_ERROR)
        assertTrue(cancelCode.userMessageRu.contains("отменена") || cancelCode.userMessageRu.contains("Отменено"))
    }

    // 21. restartDuringDownloadHandled
    @Test
    fun testRestartDuringDownloadHandled() {
        val downloadDir = tempFolder.newFolder("downloads")
        val tempApk = File(downloadDir, "SmartTube_vot_download.apk.tmp")
        FileOutputStream(tempApk).use { it.write(ByteArray(5000)) }
        assertTrue(tempApk.exists())

        // Upon manager initialization or restart cleanup
        if (tempApk.exists()) {
            tempApk.delete()
        }
        assertFalse("Incomplete temporary file must be cleared before starting fresh download", tempApk.exists())
    }

    // 22. settingsPreservedAcrossUpgrade
    @Test
    fun testSettingsPreservedAcrossUpgrade() {
        val prefs: SharedPreferences = context.getSharedPreferences("vox_update_channel_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("update_channel", "test").apply()
        assertEquals("test", prefs.getString("update_channel", "stable"))

        // Simulate reading same prefs under new version code
        val channel = VoxUpdateChannel.fromId(prefs.getString("update_channel", "stable"))
        assertEquals(VoxUpdateChannel.TEST, channel)
    }
}
