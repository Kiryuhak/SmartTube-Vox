package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Обязательные регрессионные и негативные тесты криптографической проверки APK (Section 24 Security Hardening).
 *
 * Проверяет:
 * 1. Валидный официальный релизный APK -> проходит валидацию;
 * 2. Модифицированный (tampered) APK (подмена байт в classes.dex без валидной подписи) -> отклоняется (SecurityException);
 * 3. Поврежденный (corrupted) APK -> отклоняется;
 * 4. Неподписанный APK или APK с отсутствующей подписью -> отклоняется;
 * 5. APK с несовпадающим сертификатом подписи (wrong signer) -> отклоняется;
 * 6. APK с внедренным неподписанным файлом (injected unverified entry) -> отклоняется.
 */
@RunWith(RobolectricTestRunner::class)
class VoxOtaSecurityVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var officialReleaseApk: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        // Поиск официального подписанного релизного APK в локальном репозитории
        val candidates = listOf(
            File("../build/release-candidate/vox-9.1/SmartTube_vot_32.56-vox.9.1_armeabi-v7a.apk"),
            File("../build/release-candidate/vox-9.1/SmartTube_vot_32.56-vox.9.1_universal.apk"),
            File("../smarttubetv/build/outputs/apk/stvot/release/SmartTube_vot_32.56-vox.9.1_universal.apk")
        )
        val apk = candidates.map { it.canonicalFile }.firstOrNull { it.exists() }
        org.junit.Assume.assumeTrue("Official release APK not found for security verification testing", apk != null)
        officialReleaseApk = apk!!
    }

    /**
     * Создает копию исходного APK, в которой содержимое classes.dex изменено
     * при сохранении оригинального манифеста подписей META-INF (tampered APK).
     */
    private fun createTamperedApk(source: File, destination: File) {
        FileInputStream(source).use { fis ->
            ZipInputStream(fis).use { zis ->
                FileOutputStream(destination).use { fos ->
                    ZipOutputStream(fos).use { zos ->
                        var entry: ZipEntry? = zis.nextEntry
                        val buffer = ByteArray(8192)
                        while (entry != null) {
                            val newEntry = ZipEntry(entry.name)
                            zos.putNextEntry(newEntry)
                            if (entry.name == "classes.dex") {
                                // Записываем фальсифицированные байты вместо оригинального DEX
                                zos.write("TAMPERED_MALICIOUS_BYTECODE_PAYLOAD_HERE".toByteArray())
                            } else {
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    zos.write(buffer, 0, len)
                                }
                            }
                            zos.closeEntry()
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            }
        }
    }

    /**
     * Создает копию исходного APK, в которую внедрен дополнительный неподписанный файл (injected payload).
     */
    private fun createInjectedApk(source: File, destination: File) {
        FileInputStream(source).use { fis ->
            ZipInputStream(fis).use { zis ->
                FileOutputStream(destination).use { fos ->
                    ZipOutputStream(fos).use { zos ->
                        var entry: ZipEntry? = zis.nextEntry
                        val buffer = ByteArray(8192)
                        while (entry != null) {
                            zos.putNextEntry(ZipEntry(entry.name))
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                zos.write(buffer, 0, len)
                            }
                            zos.closeEntry()
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                        // Внедряем неподписанный файл
                        zos.putNextEntry(ZipEntry("assets/unverified_injected.dat"))
                        zos.write("INJECTED_UNVERIFIED_DATA".toByteArray())
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    /**
     * Создает неподписанный APK с манифестом и classes.dex.
     */
    private fun createUnsignedApk(destination: File) {
        FileOutputStream(destination).use { fos ->
            ZipOutputStream(fos).use { zos ->
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write("<manifest package=\"com.test.mock\"/>".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("classes.dex"))
                zos.write("DEX_CODE_WITHOUT_SIGNATURE".toByteArray())
                zos.closeEntry()
            }
        }
    }

    @Test
    fun testValidReleaseApk_passesVerification() {
        val certs = VoxOtaSecurityVerifier.verifyJarArchiveIntegrityAndCertificates(officialReleaseApk)
        assertFalse("Official APK must contain valid signatures", certs.isEmpty())
        assertTrue(
            "Official APK must contain expected VOX signer fingerprint",
            certs.contains(VoxOtaSecurityVerifier.EXPECTED_SIGNER_SHA256)
        )

        val isValid = VoxOtaSecurityVerifier.verifyApkSignature(
            context,
            officialReleaseApk,
            VoxOtaSecurityVerifier.EXPECTED_SIGNER_SHA256
        )
        assertTrue("Official release APK must pass verification", isValid)
    }

    @Test
    fun testApkSigningBlockV2V3_extractsExpectedCertificate() {
        val blockCerts = VoxOtaSecurityVerifier.extractApkSigningBlockCertificatesSha256(officialReleaseApk)
        assertFalse("Official APK must contain v2/v3 signatures in APK Signing Block", blockCerts.isEmpty())
        assertTrue(
            "APK Signing Block must contain official VOX signer fingerprint",
            blockCerts.contains(VoxOtaSecurityVerifier.EXPECTED_SIGNER_SHA256)
        )
    }

    @Test
    fun testTamperedApk_failsVerification() {
        val tamperedApk = tempFolder.newFile("tampered_release.apk")
        createTamperedApk(officialReleaseApk, tamperedApk)

        val certs = VoxOtaSecurityVerifier.verifyJarArchiveIntegrityAndCertificates(tamperedApk)
        assertTrue("Tampered APK must return empty certificates list due to SecurityException", certs.isEmpty())

        val isValid = VoxOtaSecurityVerifier.verifyApkSignature(
            context,
            tamperedApk,
            VoxOtaSecurityVerifier.EXPECTED_SIGNER_SHA256
        )
        assertFalse("Tampered APK must be rejected", isValid)
    }

    @Test
    fun testCorruptedApk_failsVerification() {
        val corruptedApk = tempFolder.newFile("corrupted.apk")
        FileOutputStream(corruptedApk).use { fos ->
            fos.write("CORRUPTED_NON_ZIP_DATA_GARBAGE_PAYLOAD_HERE_1234567890".toByteArray())
        }

        val certs = VoxOtaSecurityVerifier.verifyJarArchiveIntegrityAndCertificates(corruptedApk)
        assertTrue("Corrupted APK must return empty certificates", certs.isEmpty())

        val isValid = VoxOtaSecurityVerifier.verifyApkSignature(
            context,
            corruptedApk,
            VoxOtaSecurityVerifier.EXPECTED_SIGNER_SHA256
        )
        assertFalse("Corrupted APK must be rejected", isValid)
    }

    @Test
    fun testUnsignedOrMissingSignature_failsVerification() {
        val unsignedApk = tempFolder.newFile("unsigned.apk")
        createUnsignedApk(unsignedApk)

        val certs = VoxOtaSecurityVerifier.verifyJarArchiveIntegrityAndCertificates(unsignedApk)
        assertTrue("Unsigned APK must return empty certificates", certs.isEmpty())

        val isValid = VoxOtaSecurityVerifier.verifyApkSignature(
            context,
            unsignedApk,
            VoxOtaSecurityVerifier.EXPECTED_SIGNER_SHA256
        )
        assertFalse("Unsigned APK must be rejected", isValid)
    }

    @Test
    fun testWrongSigner_failsVerification() {
        val fakeSigner = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val isValid = VoxOtaSecurityVerifier.verifyApkSignature(
            context,
            officialReleaseApk,
            fakeSigner
        )
        assertFalse("APK checked against wrong signer hash must be rejected", isValid)
    }

    @Test
    fun testInjectedUnsignedEntry_failsVerification() {
        val injectedApk = tempFolder.newFile("injected_unverified.apk")
        createInjectedApk(officialReleaseApk, injectedApk)

        val certs = VoxOtaSecurityVerifier.verifyJarArchiveIntegrityAndCertificates(injectedApk)
        assertTrue("APK with injected unsigned entry must return empty certificates", certs.isEmpty())

        val isValid = VoxOtaSecurityVerifier.verifyApkSignature(
            context,
            injectedApk,
            VoxOtaSecurityVerifier.EXPECTED_SIGNER_SHA256
        )
        assertFalse("APK with injected unsigned entry must be rejected", isValid)
    }

    @Test
    fun testVerifyApkCryptographically_validReleaseApk_succeeds() {
        val cryptoResult = VoxOtaSecurityVerifier.verifyApkCryptographically(officialReleaseApk)
        assertTrue("Official release APK must pass cryptographic verification", cryptoResult.isVerified)
        assertTrue(
            "Official release APK must be verified using at least one supported scheme",
            cryptoResult.isVerifiedUsingV1Scheme || cryptoResult.isVerifiedUsingV2Scheme || cryptoResult.isVerifiedUsingV3Scheme
        )
        assertTrue(
            "Cryptographically verified signers must include expected VOX signer",
            cryptoResult.verifiedSignersSha256.contains(VoxOtaSecurityVerifier.EXPECTED_SIGNER_SHA256)
        )
        assertTrue("Verification errors list must be empty", cryptoResult.errors.isEmpty())
    }

    @Test
    fun testVerifyApkCryptographically_tamperedDEX_rejected() {
        val tamperedApk = tempFolder.newFile("crypto_tampered.apk")
        createTamperedApk(officialReleaseApk, tamperedApk)
        val cryptoResult = VoxOtaSecurityVerifier.verifyApkCryptographically(tamperedApk)
        assertFalse("Tampered DEX must fail cryptographic verification", cryptoResult.isVerified)
        assertTrue("Cryptographically verified signers must be empty for tampered APK", cryptoResult.verifiedSignersSha256.isEmpty())
        assertFalse(
            "Tampered APK must be rejected by verifyApkSignature",
            VoxOtaSecurityVerifier.verifyApkSignature(context, tamperedApk)
        )
    }

    @Test
    fun testVerifyApkCryptographically_corruptedApk_rejected() {
        val corruptedApk = tempFolder.newFile("crypto_corrupted.apk")
        FileOutputStream(corruptedApk).use { fos ->
            fos.write("CORRUPTED_GARBAGE_PAYLOAD_FOR_APKSIG_12345".toByteArray())
        }
        val cryptoResult = VoxOtaSecurityVerifier.verifyApkCryptographically(corruptedApk)
        assertFalse("Corrupted file must fail cryptographic verification", cryptoResult.isVerified)
        assertTrue("Errors must be reported for corrupted file", cryptoResult.errors.isNotEmpty())
        assertFalse(
            "Corrupted file must be rejected by verifyApkSignature",
            VoxOtaSecurityVerifier.verifyApkSignature(context, corruptedApk)
        )
    }

    @Test
    fun testVerifyApkCryptographically_injectedEntry_rejected() {
        val injectedApk = tempFolder.newFile("crypto_injected.apk")
        createInjectedApk(officialReleaseApk, injectedApk)
        val cryptoResult = VoxOtaSecurityVerifier.verifyApkCryptographically(injectedApk)
        assertFalse("APK with injected file must fail cryptographic verification", cryptoResult.isVerified)
        assertFalse(
            "APK with injected file must be rejected by verifyApkSignature",
            VoxOtaSecurityVerifier.verifyApkSignature(context, injectedApk)
        )
    }

    @Test
    fun testVerifyApkCryptographically_corruptedSigningBlock_rejected() {
        val corruptedBlockApk = tempFolder.newFile("corrupted_signing_block.apk")
        officialReleaseApk.copyTo(corruptedBlockApk, overwrite = true)
        java.io.RandomAccessFile(corruptedBlockApk, "rw").use { raf ->
            val len = raf.length()
            if (len > 1024) {
                raf.seek(len - 512)
                raf.write(ByteArray(64) { 0x58.toByte() })
            }
        }
        val cryptoResult = VoxOtaSecurityVerifier.verifyApkCryptographically(corruptedBlockApk)
        assertFalse("APK with corrupted signing block must fail cryptographic verification", cryptoResult.isVerified)
        assertFalse(
            "APK with corrupted signing block must be rejected by verifyApkSignature",
            VoxOtaSecurityVerifier.verifyApkSignature(context, corruptedBlockApk)
        )
    }
}
