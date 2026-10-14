package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Проверка безопасности скачанных APK файлов обновления:
 * - Сверка контрольной суммы SHA-256 с SHA256SUMS.txt;
 * - Проверка цифровой подписи APK с ожидаемым отпечатком сертификата VOX или текущего приложения.
 */
object VoxOtaSecurityVerifier {

    /**
     * Ожидаемый отпечаток SHA-256 официального сертификата подписи SmartTube VOX.
     */
    const val EXPECTED_SIGNER_SHA256 = "e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d"

    /**
     * Разбирает содержимое файла SHA256SUMS.txt в карту [имя файла -> sha256 хэш].
     */
    @JvmStatic
    fun parseSha256Sums(content: String?): Map<String, String> {
        if (content.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        content.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isNotBlank() && !line.startsWith("#")) {
                val parts = line.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) {
                    val hash = parts[0].trim().lowercase()
                    val fileName = parts[1].trim().removePrefix("*").trim()
                    if (hash.length == 64 && fileName.isNotEmpty()) {
                        result[fileName] = hash
                    }
                }
            }
        }
        return result
    }

    /**
     * Вычисляет SHA-256 хэш локального файла.
     */
    @JvmStatic
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(128 * 1024)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Проверяет, совпадает ли SHA-256 хэш файла с ожидаемым значением.
     */
    @JvmStatic
    fun verifyFileSha256(file: File, expectedHash: String): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val computed = computeSha256(file)
        return computed.equals(expectedHash.trim(), ignoreCase = true)
    }

    /**
     * Извлекает массив подписей из PackageInfo с поддержкой Android P+ SigningInfo (включая key history)
     * и обратной совместимостью со старым массивом signatures.
     */
    @JvmStatic
    fun extractSignatures(packageInfo: PackageInfo?): Array<Signature>? {
        if (packageInfo == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            if (signingInfo != null) {
                val signers = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                if (!signers.isNullOrEmpty()) {
                    return signers
                }
            }
        }
        @Suppress("DEPRECATION")
        return packageInfo.signatures
    }

    @JvmStatic
    fun getSignatureSha256(signature: Signature): String {
        val md = MessageDigest.getInstance("SHA-256")
        val certDigest = md.digest(signature.toByteArray())
        return certDigest.joinToString("") { "%02x".format(it) }
    }

    @JvmStatic
    fun getApkSignaturesSha256(context: Context?, apkFile: File): List<String> {
        if (context == null || !apkFile.exists()) return emptyList()
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES or @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            val signatures = extractSignatures(packageInfo)
            signatures?.map { getSignatureSha256(it).lowercase() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @JvmStatic
    fun getInstalledAppSignaturesSha256(context: Context?): List<String> {
        if (context == null) return emptyList()
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES or @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val packageInfo = pm.getPackageInfo(context.packageName, flags)
            val signatures = extractSignatures(packageInfo)
            signatures?.map { getSignatureSha256(it).lowercase() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Проверяет цифровую подпись APK файла:
     * - совпадает ли она с официальным сертификатом VOX (EXPECTED_SIGNER_SHA256);
     * - ИЛИ совпадает ли она с подписью текущего установленного приложения (для debug/custom сборок).
     */
    @JvmStatic
    @JvmOverloads
    fun verifyApkSignature(
        context: Context?,
        apkFile: File,
        expectedSha256: String = EXPECTED_SIGNER_SHA256
    ): Boolean {
        if (context == null || !apkFile.exists()) return false
        val apkSignatures = getApkSignaturesSha256(context, apkFile)
        if (apkSignatures.isEmpty()) return false

        val targetHash = expectedSha256.trim().lowercase()
        // 1. Проверяем совпадение с официальным ключом выпуска VOX
        if (apkSignatures.any { it.equals(targetHash, ignoreCase = true) }) {
            return true
        }

        // 2. Проверяем совпадение с подписью установленного приложения
        val installedSignatures = getInstalledAppSignaturesSha256(context)
        if (installedSignatures.isNotEmpty()) {
            if (apkSignatures.any { apkSig -> installedSignatures.any { instSig -> apkSig.equals(instSig, ignoreCase = true) } }) {
                return true
            }
        }

        return false
    }
}
