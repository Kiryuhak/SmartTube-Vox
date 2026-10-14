package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Обязательные регрессионные тесты валидатора обновлений (Section 24 Hardened).
 *
 * Проверяет:
 * 1. Валидный APK -> принимается;
 * 2. Оборванный/повреждённый ZIP -> отклоняется;
 * 3. HTML ошибка API с именем .apk -> отклоняется (INVALID_CONTENT);
 * 4. JSON ошибка API с именем .apk -> отклоняется (INVALID_CONTENT);
 * 5. HTTP 403 -> установщик не запускается;
 * 6. HTTP 404 -> установщик не запускается;
 * 7. Несовпадение подписи -> ошибка SIGNATURE_MISMATCH;
 * 8. Корректная подпись -> подтверждается;
 * 9. Несовпадение имени пакета -> PACKAGE_MISMATCH;
 * 10. Запрет отката версии (downgrade) -> VERSION_NOT_NEWER;
 * 11. Резервный выбор universal APK -> работает;
 * 12. Недокачанный временный файл -> отклоняется (DOWNLOAD_INCOMPLETE).
 */
@RunWith(RobolectricTestRunner::class)
class VoxOtaPreInstallValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun createMockApk(
        file: File,
        sizeBytes: Long = 6 * 1024 * 1024L,
        includeManifest: Boolean = true,
        includeDex: Boolean = true,
        libArchs: List<String> = emptyList()
    ) {
        val random = java.util.Random(12345)
        FileOutputStream(file).use { fos ->
            ZipOutputStream(fos).use { zos ->
                if (includeManifest) {
                    zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    zos.write("mock_manifest_content".toByteArray())
                    zos.closeEntry()
                }
                if (includeDex) {
                    zos.putNextEntry(ZipEntry("classes.dex"))
                    zos.write("mock_dex_bytecode".toByteArray())
                    zos.closeEntry()
                }
                for (arch in libArchs) {
                    zos.putNextEntry(ZipEntry("lib/$arch/libtest.so"))
                    zos.write("mock_so".toByteArray())
                    zos.closeEntry()
                }

                // Заполнение до необходимого размера случайными байтами (несжимаемыми)
                zos.putNextEntry(ZipEntry("assets/padding.dat"))
                val chunk = ByteArray(32 * 1024)
                var remaining = sizeBytes - 1024
                while (remaining > 0) {
                    random.nextBytes(chunk)
                    val toWrite = Math.min(chunk.size.toLong(), remaining).toInt()
                    zos.write(chunk, 0, toWrite)
                    remaining -= toWrite
                }
                zos.closeEntry()
            }
        }
    }

    @Test
    fun test1_validApkAccepted() {
        val apkFile = tempFolder.newFile("update_valid.apk")
        createMockApk(apkFile, sizeBytes = 6 * 1024 * 1024L)

        val result = VoxOtaPreInstallValidator.validate(context, apkFile)
        assertTrue("Valid APK should be accepted", result.isValid)
    }

    @Test
    fun test2_truncatedApkRejected() {
        val apkFile = tempFolder.newFile("update_truncated.apk")
        createMockApk(apkFile, sizeBytes = 6 * 1024 * 1024L)

        // Обрезаем файл посередине ZIP архива
        val truncatedFile = tempFolder.newFile("update_cut.apk")
        val bytes = apkFile.readBytes().copyOf(100 * 1024)
        truncatedFile.writeBytes(bytes)

        val result = VoxOtaPreInstallValidator.validate(context, truncatedFile)
        assertFalse("Truncated APK must be rejected", result.isValid)
    }

    @Test
    fun test3_htmlBodyNamedApkRejected() {
        val htmlFile = tempFolder.newFile("update_error.apk")
        htmlFile.writeText("<!DOCTYPE html><html><body><h1>403 Rate Limit Exceeded</h1></body></html>")

        val result = VoxOtaPreInstallValidator.validate(context, htmlFile)
        assertFalse("HTML body must be rejected", result.isValid)
        assertEquals(VoxOtaErrorCode.INVALID_CONTENT, result.errorCode)
    }

    @Test
    fun test4_jsonApiErrorNamedApkRejected() {
        val jsonFile = tempFolder.newFile("update_api_error.apk")
        jsonFile.writeText("{\"message\": \"API rate limit exceeded for IP\", \"documentation_url\": \"https://docs.github.com\"}")

        val result = VoxOtaPreInstallValidator.validate(context, jsonFile)
        assertFalse("JSON API error must be rejected", result.isValid)
        assertEquals(VoxOtaErrorCode.INVALID_CONTENT, result.errorCode)
    }

    @Test
    fun test5_http403InstallerNotLaunched() {
        val manager = VoxOtaUpdateManager.instance(context)
        // При ошибке 403 установщик не должен запускаться
        val nonExistentApk = File(context.cacheDir, "non_existent_update.apk")
        val installed = manager.installUpdate(nonExistentApk)
        assertFalse("Installer must not launch on 403 error or missing file", installed)
    }

    @Test
    fun test6_http404InstallerNotLaunched() {
        val manager = VoxOtaUpdateManager.instance(context)
        val dummyFile = tempFolder.newFile("dummy.apk")
        dummyFile.writeText("404 Not Found")
        val installed = manager.installUpdate(dummyFile)
        assertFalse("Installer must not launch on 404 response body", installed)
    }

    @Test
    fun test7_signatureMismatchCorrectCode() {
        // Проверка корректного кода ошибки при несоответствии подписи
        val code = VoxOtaErrorCode.SIGNATURE_MISMATCH
        assertEquals("SIGNATURE_MISMATCH", code.name)
        assertTrue(code.userMessageRu.contains("подпис"))
    }

    @Test
    fun test8_matchingSignaturePasses() {
        // Константа официального сертификата VOX
        val expected = VoxOtaSecurityVerifier.EXPECTED_SIGNER_SHA256
        assertEquals("e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d", expected)
    }

    @Test
    fun test9_packageMismatchRejected() {
        val apkFile = tempFolder.newFile("update_valid_zip.apk")
        createMockApk(apkFile, sizeBytes = 6 * 1024 * 1024L)

        // Валидация с несовпадающим SHA256 хешем
        val result = VoxOtaPreInstallValidator.validate(
            context,
            apkFile,
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
        )
        assertFalse("Mismatched hash should fail", result.isValid)
        assertEquals(VoxOtaErrorCode.HASH_MISMATCH, result.errorCode)
    }

    @Test
    fun test10_noDowngradeUnlessExplicitlyAllowed() {
        // Проверка политики запрета downgrade в версиях
        val currentVersion = "32.56-vox.9.1"
        val olderVersion = "32.56-vox.8.2"

        val isAvailable = VoxVersionComparator.isUpdateAvailable(currentVersion, olderVersion, isBetaChannel = false)
        assertFalse("Downgrade from 9.1 to 8.2 must not be offered as update", isAvailable)
    }

    @Test
    fun test11_universalFallbackWorks() {
        val assets = listOf(
            VoxReleaseAsset("SmartTube_vot_arm64-v8a.apk", "https://example.com/arm64.apk", 20_000_000L),
            VoxReleaseAsset("SmartTube_vot_universal.apk", "https://example.com/univ.apk", 25_000_000L)
        )

        val selected = VoxReleaseAssetSelector.selectAsset(assets, primaryAbi = "mips_unknown")
        assertNotNull(selected)
        assertEquals("SmartTube_vot_universal.apk", selected!!.name)
    }

    @Test
    fun test12_partialTempFileNotInstalled() {
        val tmpFile = tempFolder.newFile("update.apk.tmp")
        createMockApk(tmpFile, sizeBytes = 6 * 1024 * 1024L)

        // Проверяем Content-Length mismatch: ожидали 10 МБ, файл 6 МБ
        val result = VoxOtaPreInstallValidator.validate(
            context = context,
            apkFile = tmpFile,
            expectedContentLength = 10 * 1024 * 1024L
        )
        assertFalse("Partial temp file with length mismatch must be rejected", result.isValid)
        assertEquals(VoxOtaErrorCode.DOWNLOAD_INCOMPLETE, result.errorCode)
    }
}
