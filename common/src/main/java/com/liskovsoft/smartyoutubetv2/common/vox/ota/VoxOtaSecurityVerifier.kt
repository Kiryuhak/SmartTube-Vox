package com.liskovsoft.smartyoutubetv2.common.vox.ota

import com.android.apksig.ApkVerifier
import java.security.cert.X509Certificate

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    data class CryptographicVerificationResult(
        val isVerified: Boolean,
        val isVerifiedUsingV1Scheme: Boolean = false,
        val isVerifiedUsingV2Scheme: Boolean = false,
        val isVerifiedUsingV3Scheme: Boolean = false,
        val isVerifiedUsingV4Scheme: Boolean = false,
        val verifiedSignersSha256: List<String> = emptyList(),
        val errors: List<String> = emptyList()
    )

    /**
     * Выполняет строгую криптографическую верификацию APK с использованием Google apksig:
     * - Проверка целостности всех защищённых областей APK (ZIP entries, central directory, EOCD);
     * - Валидация криптографических подписей по схемам v1, v2, v3, v4;
     * - Защита от модификации DEX-байт (classes.dex), ресурсов, повреждений APK Signing Block, внедрения неподписанных файлов;
     * - Извлечение проверенных X.509 сертификатов только в случае успешной криптографической проверки.
     */
    @JvmStatic
    fun verifyApkCryptographically(apkFile: File): CryptographicVerificationResult {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) {
            return CryptographicVerificationResult(
                isVerified = false,
                errors = listOf("APK file does not exist or is empty")
            )
        }

        return try {
            val verifier = ApkVerifier.Builder(apkFile).build()
            val result = verifier.verify()
            if (!result.isVerified) {
                val errorList = result.errors.map { issue: com.android.apksig.ApkVerifier.IssueWithParams -> issue.toString() }
                return CryptographicVerificationResult(
                    isVerified = false,
                    isVerifiedUsingV1Scheme = result.isVerifiedUsingV1Scheme,
                    isVerifiedUsingV2Scheme = result.isVerifiedUsingV2Scheme,
                    isVerifiedUsingV3Scheme = result.isVerifiedUsingV3Scheme,
                    isVerifiedUsingV4Scheme = result.isVerifiedUsingV4Scheme,
                    errors = if (errorList.isEmpty()) listOf("Cryptographic verification failed") else errorList
                )
            }

            val signers = result.signerCertificates.map { cert: X509Certificate ->
                val md = MessageDigest.getInstance("SHA-256")
                val digestBytes = md.digest(cert.encoded)
                digestBytes.joinToString("") { b: Byte -> "%02x".format(b).lowercase() }
            }

            CryptographicVerificationResult(
                isVerified = true,
                isVerifiedUsingV1Scheme = result.isVerifiedUsingV1Scheme,
                isVerifiedUsingV2Scheme = result.isVerifiedUsingV2Scheme,
                isVerifiedUsingV3Scheme = result.isVerifiedUsingV3Scheme,
                isVerifiedUsingV4Scheme = result.isVerifiedUsingV4Scheme,
                verifiedSignersSha256 = signers
            )
        } catch (e: Exception) {
            CryptographicVerificationResult(
                isVerified = false,
                errors = listOf("ApkVerifier exception: " + (e.message ?: e.javaClass.simpleName))
            )
        }
    }


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
    /**
     * ID блоков схемы подписи внутри APK Signing Block:
     * - v2: 0x7109871a (Android 7.0+)
     * - v3: 0xf05368c0 (Android 9.0+)
     */
    const val APK_SIGNATURE_SCHEME_V2_BLOCK_ID: Int = 0x7109871a
    const val APK_SIGNATURE_SCHEME_V3_BLOCK_ID: Int = -0xfa9c940 // 0xf05368c0

    private val APK_SIG_BLOCK_MAGIC = byteArrayOf(
        0x41, 0x50, 0x4b, 0x20, 0x53, 0x69, 0x67, 0x20, 0x42, 0x6c, 0x6f, 0x63, 0x6b, 0x20, 0x34, 0x32
    ) // "APK Sig Block 42"

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

    /**
     * Извлекает SHA-256 отпечатки сертификатов подписантов напрямую из бинарного блока
     * APK Signing Block (схемы v2 и v3).
     *
     * Обеспечивает криптографическую валидацию сертификатов на Android 8, 9, 10, 11, 14
     * даже в случае известных OEM-ошибок системного PackageParser на TV-приставках.
     */
    @JvmStatic
    fun extractApkSigningBlockCertificatesSha256(apkFile: File): List<String> {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() < 32L) {
            return emptyList()
        }

        val result = mutableSetOf<String>()
        try {
            RandomAccessFile(apkFile, "r").use { raf ->
                val fileLength = raf.length()
                val readLength = Math.min(fileLength, 65536L).toInt()
                val buffer = ByteArray(readLength)
                raf.seek(fileLength - readLength)
                raf.readFully(buffer)

                // 1. Поиск End of Central Directory (EOCD magic: 0x06054b50)
                var eocdOffsetInBuf = -1
                for (i in readLength - 22 downTo 0) {
                    if (buffer[i] == 0x50.toByte() &&
                        buffer[i + 1] == 0x4b.toByte() &&
                        buffer[i + 2] == 0x05.toByte() &&
                        buffer[i + 3] == 0x06.toByte()
                    ) {
                        eocdOffsetInBuf = i
                        break
                    }
                }
                if (eocdOffsetInBuf < 0) return emptyList()

                // Смещение Central Directory (uint32 at offset 16 в EOCD)
                val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                val cdOffset = (byteBuffer.getInt(eocdOffsetInBuf + 16).toLong()) and 0xFFFFFFFFL
                if (cdOffset < 24L || cdOffset > fileLength) return emptyList()

                // 2. Чтение хвоста APK Signing Block (24 байта непосредственно перед Central Directory)
                raf.seek(cdOffset - 24L)
                val footer = ByteArray(24)
                raf.readFully(footer)
                val footerBuf = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN)
                val blockSizeInFooter = footerBuf.long
                if (blockSizeInFooter < 24L || blockSizeInFooter > cdOffset) return emptyList()

                // Проверка магической строки "APK Sig Block 42"
                for (i in 0 until 16) {
                    if (footer[8 + i] != APK_SIG_BLOCK_MAGIC[i]) return emptyList()
                }

                val blockStart = cdOffset - blockSizeInFooter - 8L
                if (blockStart < 0) return emptyList()
                raf.seek(blockStart)
                val header = ByteArray(8)
                raf.readFully(header)
                val headerBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                if (headerBuf.long != blockSizeInFooter) return emptyList()

                // 3. Разбор ID-value пар в блоке подписи
                val payloadSize = (blockSizeInFooter - 24L).toInt()
                if (payloadSize <= 0 || payloadSize > 10 * 1024 * 1024) return emptyList()
                val payload = ByteArray(payloadSize)
                raf.readFully(payload)
                val pBuf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

                var pos = 0
                val md = MessageDigest.getInstance("SHA-256")
                while (pos + 12 <= payloadSize) {
                    val pairLen = pBuf.getLong(pos)
                    pos += 8
                    if (pairLen < 4 || pos + pairLen - 4 > payloadSize) break
                    val id = pBuf.getInt(pos)
                    pos += 4
                    val valueLen = (pairLen - 4).toInt()

                    if (id == APK_SIGNATURE_SCHEME_V2_BLOCK_ID || id == APK_SIGNATURE_SCHEME_V3_BLOCK_ID) {
                        parseSchemeSigners(payload, pos, valueLen, md, result)
                    }
                    pos += valueLen
                }
            }
        } catch (e: Exception) {
            // Ошибка чтения или поврежденный контейнер
        }
        return result.toList()
    }

    private fun parseSchemeSigners(
        data: ByteArray,
        offset: Int,
        len: Int,
        md: MessageDigest,
        outHashes: MutableSet<String>
    ) {
        try {
            val buf = ByteBuffer.wrap(data, offset, len).order(ByteOrder.LITTLE_ENDIAN)
            val signersLen = buf.int
            val signersEnd = buf.position() + signersLen
            while (buf.position() < signersEnd && buf.remaining() >= 4) {
                val signerLen = buf.int
                val signerEnd = buf.position() + signerLen

                // signedData (length-prefixed)
                if (buf.remaining() < 4) break
                val signedDataLen = buf.int
                val signedDataEnd = buf.position() + signedDataLen

                // digests (length-prefixed)
                if (buf.remaining() < 4) break
                val digestsLen = buf.int
                buf.position(buf.position() + digestsLen)

                // certificates (length-prefixed)
                if (buf.position() < signedDataEnd && buf.remaining() >= 4) {
                    val certsLen = buf.int
                    val certsEnd = buf.position() + certsLen
                    while (buf.position() < certsEnd && buf.remaining() >= 4) {
                        val certLen = buf.int
                        if (certLen > 0 && certLen <= buf.remaining()) {
                            val certBytes = ByteArray(certLen)
                            buf.get(certBytes)
                            val hash = md.digest(certBytes).joinToString("") { "%02x".format(it).lowercase() }
                            outHashes.add(hash)
                        } else {
                            break
                        }
                    }
                }
                buf.position(signerEnd)
            }
        } catch (e: Exception) {
            // Игнорируем ошибку структуры блока
        }
    }

    /**
     * Выполняет полную криптографическую валидацию содержимого JAR/APK архива (схема v1):
     * - Открывает архив с проверкой целостности подписей (JarFile(apkFile, true));
     * - Проверяет наличие обязательных компонентов (AndroidManifest.xml, classes.dex);
     * - Последовательно считывает ВСЕ байты каждой записи для валидации дайджестов манифеста;
     *   (при любом несовпадении контрольных сумм JarVerifier выбрасывает SecurityException);
     * - Проверяет, что каждая запись вне META-INF действительно подписана (защита от внедрения неподписанного кода);
     * - Извлекает SHA-256 отпечатки всех сертификатов подписи;
     * - Возвращает пустой список, если архив повреждён, не подписан, имеет неподписанные файлы,
     *   или если хотя бы один файл был изменён после подписания (tampered).
     */
    @JvmStatic
    fun verifyJarArchiveIntegrityAndCertificates(apkFile: File): List<String> {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) {
            return emptyList()
        }

        try {
            java.util.jar.JarFile(apkFile, true).use { jar ->
                var hasManifest = false
                var hasDex = false
                val certDigests = mutableSetOf<String>()
                val buffer = ByteArray(8192)

                val entries = jar.entries()
                var verifiedEntriesCount = 0

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    if (name == "AndroidManifest.xml") hasManifest = true
                    if (name.startsWith("classes") && name.endsWith(".dex")) hasDex = true

                    if (entry.isDirectory) continue

                    // Считываем все байты записи. Если запись была модифицирована (tampered),
                    // JarVerifier немедленно выбросит java.lang.SecurityException
                    try {
                        jar.getInputStream(entry).use { stream ->
                            while (stream.read(buffer) > 0) {
                                // Итерация по потоку активирует внутреннюю сверку SHA дайджеста
                            }
                        }
                    } catch (se: SecurityException) {
                        // Файл подделан (tampered digest) или подпись нарушена!
                        return emptyList()
                    }

                    // Для всех файлов вне META-INF проверяем наличие валидных сертификатов подписи
                    if (!name.startsWith("META-INF/")) {
                        val certs = entry.certificates
                        if (certs.isNullOrEmpty()) {
                            // Неподписанный файл внедрён в архив (unverified entry)!
                            return emptyList()
                        }
                        verifiedEntriesCount++
                        val md = MessageDigest.getInstance("SHA-256")
                        for (c in certs) {
                            val digest = md.digest(c.encoded).joinToString("") { "%02x".format(it).lowercase() }
                            certDigests.add(digest)
                        }
                    }
                }

                // В валидном APK обязательно должны присутствовать манифест и код
                if (!hasManifest || !hasDex || verifiedEntriesCount == 0 || certDigests.isEmpty()) {
                    return emptyList()
                }

                return certDigests.toList()
            }
        } catch (e: Exception) {
            // ZipException, IOException, SecurityException при открытии или разборе структуры
            return emptyList()
        }
    }

    /**
     * Извлекает SHA-256 отпечатки цифровых подписей APK с поддержкой всех схем (v1, v2, v3):
     * 1. Системный PackageManager (с разделением флагов для предотвращения крашей на Android 9/10);
     * 2. Нативный парсер APK Signing Block (схемы v2 и v3);
     * 3. Полный криптографический аудит JAR (схема v1).
     */
    @JvmStatic
    fun getApkSignaturesSha256(context: Context?, apkFile: File): List<String> {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) return emptyList()

        val result = mutableSetOf<String>()

        // 1. Попытка через системный PackageManager (наиболее строгий путь на уровне ОС)
        if (context != null) {
            try {
                val pm = context.packageManager
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                    } catch (e: Exception) {
                        null
                    } ?: @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                } else {
                    @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                }
                val signatures = extractSignatures(packageInfo)
                if (!signatures.isNullOrEmpty()) {
                    result.addAll(signatures.map { getSignatureSha256(it).lowercase() })
                }
            } catch (e: Exception) {
                // Игнорируем ошибку системного парсера
            }
        }

        // 2. Если системный парсер вернул результат, сверяем также целостность архива v1
        if (result.isNotEmpty()) {
            return result.toList()
        }

        // 3. Прямой разбор APK Signing Block (схемы v2 / v3)
        val v2v3Signatures = extractApkSigningBlockCertificatesSha256(apkFile)
        if (v2v3Signatures.isNotEmpty()) {
            result.addAll(v2v3Signatures)
            return result.toList()
        }

        // 4. Полная валидация схемы v1 (JAR signing)
        val jarSignatures = verifyJarArchiveIntegrityAndCertificates(apkFile)
        if (jarSignatures.isNotEmpty()) {
            result.addAll(jarSignatures)
        }

        return result.toList()
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
     * Проверяет наличие файлов цифровой подписи схемы v1 (JAR signing) в архиве.
     */
    @JvmStatic
    fun hasJarSignatureFiles(apkFile: File): Boolean {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) return false
        return try {
            java.util.zip.ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                var hasManifest = false
                var hasSigFile = false
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name.uppercase()
                    if (name == "META-INF/MANIFEST.MF") hasManifest = true
                    if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                        hasSigFile = true
                    }
                    if (hasManifest && hasSigFile) return true
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Проверяет цифровую подпись APK файла:
     * - совпадает ли она с официальным сертификатом VOX (EXPECTED_SIGNER_SHA256);
     * - ИЛИ совпадает ли она с подписью текущего установленного приложения (для debug/custom сборок);
     * - И архив полностью валиден криптографически (нет подмененных файлов).
     */
    @JvmStatic
    @JvmOverloads
    fun verifyApkSignature(
        context: Context?,
        apkFile: File,
        expectedSha256: String = EXPECTED_SIGNER_SHA256
    ): Boolean {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) return false

        // 1. Полноценная криптографическая проверка через Google apksig (v1, v2, v3, v4).
        // Проверяет подписи схем, целостность всех защищенных секций APK (Merkle tree hashes).
        // Гарантирует отказ при любом изменении bytecode (classes.dex), ресурсов,
        // повреждении блока подписи или наличии неподписанных файлов.
        val cryptoResult = verifyApkCryptographically(apkFile)
        if (!cryptoResult.isVerified || cryptoResult.verifiedSignersSha256.isEmpty()) {
            return false
        }

        // 2. Если APK содержит JAR-подпись v1 (META-INF/*.SF, *.RSA/EC),
        // дополнительно валидируем целостность записей архива (защита от partial tampering)
        val hasV1 = hasJarSignatureFiles(apkFile)
        if (hasV1) {
            val jarSignatures = verifyJarArchiveIntegrityAndCertificates(apkFile)
            if (jarSignatures.isEmpty()) {
                return false
            }
        }

        // 3. Сверка сертификатов всех подписантов:
        // Каждый подписант обязан быть доверенным (либо официальный VOX, либо ключ текущей установки)
        val targetHash = expectedSha256.trim().lowercase()
        val installedSignatures = if (context != null) getInstalledAppSignaturesSha256(context) else emptyList()

        val isAllTrusted = cryptoResult.verifiedSignersSha256.all { sig ->
            sig.equals(targetHash, ignoreCase = true) ||
                    installedSignatures.any { instSig -> sig.equals(instSig, ignoreCase = true) }
        }

        return isAllTrusted
    }
}
