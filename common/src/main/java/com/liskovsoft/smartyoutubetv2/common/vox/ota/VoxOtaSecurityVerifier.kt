package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Проверка безопасности скачанных APK файлов обновления:
 * - Сверка контрольной суммы SHA-256 с SHA256SUMS.txt;
 * - Проверка цифровой подписи APK с ожидаемым отпечатком сертификата VOX.
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
     * Проверяет цифровую подпись APK файла на соответствие доверенному сертификату VOX.
     */
    @JvmStatic
    @JvmOverloads
    fun verifyApkSignature(
        context: Context?,
        apkFile: File,
        expectedSha256: String = EXPECTED_SIGNER_SHA256
    ): Boolean {
        if (context == null || !apkFile.exists()) return false
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

            val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return false

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures.isNullOrEmpty()) return false

            val md = MessageDigest.getInstance("SHA-256")
            for (sig in signatures) {
                val certDigest = md.digest(sig.toByteArray())
                val certHex = certDigest.joinToString("") { "%02x".format(it) }
                if (certHex.equals(expectedSha256.trim(), ignoreCase = true)) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            // В среде unit-тестов JVM packageManager может выбросить stub exception
            false
        }
    }
}
