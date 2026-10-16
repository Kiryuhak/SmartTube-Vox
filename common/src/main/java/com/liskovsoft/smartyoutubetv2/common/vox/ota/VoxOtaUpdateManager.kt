package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.liskovsoft.sharedutils.helpers.DeviceHelpers
import com.liskovsoft.sharedutils.helpers.FileHelpers
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/**
 * Менеджер обновлений OTA для SmartTube VOX (Patch #35 Hardened).
 *
 * Особенности:
 * - Строгая классификация ошибок (DNS, Timeout, TLS, Rate Limit, HTTP, Release Not Found);
 * - Гарантия Single-Flight и защита от параллельного спама запросов;
 * - Безопасный переход NO_UPDATE / VERSION_NOT_NEWER без сетевых ложных тревог;
 * - Обработка 404 манифеста с прозрачным переходом к актуальному релизу;
 * - Разделение Stable и Beta каналов с корректной иерархией версий;
 * - Полное покрытие телеметрией этапов проверки обновления.
 */
class VoxOtaUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "VoxOtaUpdateManager"
        const val GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/Kiryuhak/SmartTube-Vox/releases/latest"
        const val GITHUB_RELEASES_LIST_API =
            "https://api.github.com/repos/Kiryuhak/SmartTube-Vox/releases?per_page=15"
        private const val USER_AGENT = "SmartTube-VOX-OTA/1.0"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
        private const val BUFFER_SIZE = 128 * 1024
        const val AUTO_CHECK_COOLDOWN_MS = 4L * 60 * 60 * 1000 // 4 hours
        const val RATE_LIMIT_COOLDOWN_MS = 1L * 60 * 60 * 1000 // 1 hour

        @Volatile
        @JvmStatic
        var testUpdateBaseUrl: String? = null

        @Volatile
        private var sInstance: VoxOtaUpdateManager? = null

        @JvmStatic
        fun instance(context: Context): VoxOtaUpdateManager {
            return sInstance ?: synchronized(this) {
                sInstance ?: VoxOtaUpdateManager(context.applicationContext).also { sInstance = it }
            }
        }

        @JvmStatic
        fun resetInstanceForTesting() {
            synchronized(this) {
                sInstance = null
                testUpdateBaseUrl = null
            }
        }

        @JvmStatic
        fun classifyException(
            throwable: Throwable,
            customMessage: String? = null,
            stage: VoxOtaStage = VoxOtaStage.HTTP
        ): VoxOtaException {
            if (throwable is VoxOtaException) return throwable
            val msg = customMessage ?: throwable.message ?: throwable.javaClass.simpleName
            val (code, resolvedStage, isRetryable) = when (throwable) {
                is UnknownHostException -> Triple(VoxOtaErrorCode.DNS_ERROR, VoxOtaStage.DNS, true)
                is SocketTimeoutException -> Triple(VoxOtaErrorCode.TIMEOUT, VoxOtaStage.CONNECT, true)
                is ConnectException -> Triple(VoxOtaErrorCode.NETWORK_ERROR, VoxOtaStage.CONNECT, true)
                is SSLException -> Triple(VoxOtaErrorCode.TLS_ERROR, VoxOtaStage.TLS, false)
                is org.json.JSONException -> Triple(VoxOtaErrorCode.PARSE_ERROR, VoxOtaStage.PARSE, false)
                else -> {
                    val m = throwable.message?.lowercase() ?: ""
                    when {
                        m.contains("rate limit") || m.contains("429") -> Triple(VoxOtaErrorCode.RATE_LIMIT, VoxOtaStage.HTTP, true)
                        m.contains("404") || m.contains("not found") -> Triple(VoxOtaErrorCode.RELEASE_NOT_FOUND, VoxOtaStage.HTTP, false)
                        m.contains("dns") || m.contains("unknown host") -> Triple(VoxOtaErrorCode.DNS_ERROR, VoxOtaStage.DNS, true)
                        m.contains("timeout") || m.contains("timed out") -> Triple(VoxOtaErrorCode.TIMEOUT, VoxOtaStage.CONNECT, true)
                        m.contains("ssl") || m.contains("tls") || m.contains("handshake") || m.contains("certificate") -> Triple(VoxOtaErrorCode.TLS_ERROR, VoxOtaStage.TLS, false)
                        m.contains("refused") || m.contains("reset") || m.contains("no route") || m.contains("unreachable") -> Triple(VoxOtaErrorCode.NETWORK_ERROR, VoxOtaStage.CONNECT, true)
                        m.contains("500") || m.contains("502") || m.contains("503") || m.contains("504") -> Triple(VoxOtaErrorCode.HTTP_ERROR, VoxOtaStage.HTTP, true)
                        else -> Triple(VoxOtaErrorCode.NETWORK_ERROR, stage, false)
                    }
                }
            }
            return VoxOtaException(
                code = code,
                message = msg,
                stage = resolvedStage,
                httpStatus = null,
                isRetryable = isRetryable,
                cause = throwable
            )
        }

        private fun sanitizeUrl(urlStr: String): String {
            return try {
                val url = URL(urlStr)
                "${url.protocol}://${url.host}${url.path}"
            } catch (e: Exception) {
                urlStr.split("?")[0]
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isInFlight = AtomicBoolean(false)
    private val isDownloadCancelled = AtomicBoolean(false)

    @Volatile
    private var activeDownloadConnection: HttpURLConnection? = null

    @Volatile
    private var lastSuccessfulCheckTimeMs: Long = 0L

    @Volatile
    private var lastRateLimitTimeMs: Long = 0L

    @Volatile
    private var wasRateLimited: Boolean = false

    fun getLastSuccessfulCheckTimeMs(): Long = lastSuccessfulCheckTimeMs
    fun getLastRateLimitTimeMs(): Long = lastRateLimitTimeMs
    fun wasRateLimited(): Boolean = wasRateLimited

    fun cancelDownload() {
        isDownloadCancelled.set(true)
        try {
            activeDownloadConnection?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        val cacheDir = FileHelpers.getCacheDir(context) ?: context.cacheDir
        val tmpFile = File(cacheDir, "update.apk.tmp")
        if (tmpFile.exists()) {
            tmpFile.delete()
        }
        VoxSafeLogger.info(
            VoxLogCategory.OTA,
            VoxLogCode.OTA_DOWNLOAD_CANCELLED,
            "Скачивание обновления отменено пользователем",
            mapOf("reason" to "USER_CANCELLED")
        )
    }

    fun isDownloadCancelled(): Boolean = isDownloadCancelled.get()

    fun resetCooldownForTesting() {
        lastSuccessfulCheckTimeMs = 0L
        lastRateLimitTimeMs = 0L
        wasRateLimited = false
    }

    fun setCooldownStateForTesting(successTimeMs: Long, rateLimitTimeMs: Long, rateLimited: Boolean) {
        lastSuccessfulCheckTimeMs = successTimeMs
        lastRateLimitTimeMs = rateLimitTimeMs
        wasRateLimited = rateLimited
    }

    private fun markCheckSucceeded(currentVersion: String, channelName: String) {
        lastSuccessfulCheckTimeMs = System.currentTimeMillis()
        if (wasRateLimited) {
            wasRateLimited = false
            lastRateLimitTimeMs = 0L
            VoxSafeLogger.info(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_RATE_LIMIT_RECOVERED,
                "Связь с сервером обновлений успешно восстановлена после ограничения частоты",
                mapOf("currentVersion" to currentVersion, "channel" to channelName)
            )
        }
    }

    interface Callback {
        fun onUpdateAvailable(release: VoxReleaseInfo, asset: VoxReleaseAsset)
        fun onNoUpdateAvailable(currentVersion: String)
        fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int)
        fun onDownloadCompleted(apkFile: File)
        fun onError(error: VoxOtaException)
    }

    fun isCheckingForUpdates(): Boolean = isInFlight.get()

    /**
     * Запускает проверку наличия обновлений с защитой от параллельных запросов (Single-Flight)
     * и периодами охлаждения (Cooldown) для автоматических проверок.
     */
    @JvmOverloads
    fun checkForUpdates(
        currentVersion: String,
        manifestUrls: Array<String>?,
        isBetaChannel: Boolean = false,
        isManual: Boolean = false,
        callback: Callback
    ) {
        if (!isManual) {
            val now = System.currentTimeMillis()
            if (lastRateLimitTimeMs > 0L) {
                val rateLimitElapsed = now - lastRateLimitTimeMs
                if (rateLimitElapsed in 0 until RATE_LIMIT_COOLDOWN_MS) {
                    val remaining = RATE_LIMIT_COOLDOWN_MS - rateLimitElapsed
                    VoxSafeLogger.info(
                        VoxLogCategory.OTA,
                        VoxLogCode.OTA_CHECK_SKIPPED_COOLDOWN,
                        "Автоматическая проверка обновлений пропущена (активен период ожидания ограничения частоты)",
                        mapOf(
                            "currentVersion" to currentVersion,
                            "cooldownRemainingMs" to remaining.toString(),
                            "reason" to "RATE_LIMIT_COOLDOWN"
                        )
                    )
                    mainHandler.post { callback.onNoUpdateAvailable(currentVersion) }
                    return
                }
            }

            if (lastSuccessfulCheckTimeMs > 0L) {
                val successElapsed = now - lastSuccessfulCheckTimeMs
                if (successElapsed in 0 until AUTO_CHECK_COOLDOWN_MS) {
                    val remaining = AUTO_CHECK_COOLDOWN_MS - successElapsed
                    VoxSafeLogger.info(
                        VoxLogCategory.OTA,
                        VoxLogCode.OTA_CHECK_SKIPPED_COOLDOWN,
                        "Автоматическая проверка обновлений пропущена (активен период охлаждения)",
                        mapOf(
                            "currentVersion" to currentVersion,
                            "cooldownRemainingMs" to remaining.toString(),
                            "reason" to "AUTO_CHECK_COOLDOWN"
                        )
                    )
                    mainHandler.post { callback.onNoUpdateAvailable(currentVersion) }
                    return
                }
            }
        }

        if (!isInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Update check already in flight, ignoring duplicate request")
            return
        }

        executor.execute {
            val startTimeMs = System.currentTimeMillis()
            val channelName = if (isBetaChannel) "beta" else "stable"

            try {
                VoxSafeLogger.info(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_CHECK_STARTED,
                    "Начата проверка обновлений",
                    mapOf("currentVersion" to currentVersion, "channel" to channelName)
                )

                VoxSafeLogger.info(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_CHANNEL_SELECTED,
                    "Выбран канал обновления",
                    mapOf("channel" to channelName, "currentVersion" to currentVersion)
                )

                val release = try {
                    fetchReleaseInfo(manifestUrls, isBetaChannel, currentVersion)
                } catch (e: VoxOtaException) {
                    if (e.code == VoxOtaErrorCode.RELEASE_NOT_FOUND || e.code == VoxOtaErrorCode.NO_UPDATE) {
                        // 404, отсутствие релиза или отсутствие подходящих обновлений = NO_UPDATE
                        val elapsed = System.currentTimeMillis() - startTimeMs
                        VoxSafeLogger.info(
                            VoxLogCategory.OTA,
                            VoxLogCode.OTA_RELEASE_NOT_FOUND,
                            "Релиз не найден на сервере (трактуется как отсутствие обновлений)",
                            mapOf("currentVersion" to currentVersion, "channel" to channelName, "elapsedMs" to elapsed.toString())
                        )
                        VoxSafeLogger.info(
                            VoxLogCategory.OTA,
                            VoxLogCode.OTA_NO_UPDATE,
                            "Новых обновлений нет",
                            mapOf("currentVersion" to currentVersion, "reason" to e.code.name)
                        )
                        VoxSafeLogger.info(
                            VoxLogCategory.OTA,
                            VoxLogCode.OTA_CHECK_COMPLETED,
                            "Проверка обновлений завершена",
                            mapOf("resultCode" to VoxOtaErrorCode.NO_UPDATE.name, "elapsedMs" to elapsed.toString(), "retryable" to "false")
                        )
                        markCheckSucceeded(currentVersion, channelName)
                        mainHandler.post { callback.onNoUpdateAvailable(currentVersion) }
                        return@execute
                    }
                    throw e
                }

                val targetVersion = release.versionName
                VoxSafeLogger.info(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_RELEASE_PARSED,
                    "Информация о релизе успешно разобрана",
                    mapOf(
                        "targetVersion" to targetVersion,
                        "tag" to release.tagName,
                        "assetsCount" to release.assets.size.toString(),
                        "isPrerelease" to release.isPrerelease.toString()
                    )
                )

                val updateAvailable = VoxVersionComparator.isUpdateAvailable(currentVersion, targetVersion, isBetaChannel)

                VoxSafeLogger.info(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_VERSION_COMPARED,
                    "Сравнение версий завершено",
                    mapOf(
                        "currentVersion" to currentVersion,
                        "targetVersion" to targetVersion,
                        "updateAvailable" to updateAvailable.toString(),
                        "channel" to channelName
                    )
                )

                val elapsed = System.currentTimeMillis() - startTimeMs

                if (!updateAvailable) {
                    VoxSafeLogger.info(
                        VoxLogCategory.OTA,
                        VoxLogCode.OTA_NO_UPDATE,
                        "У вас установлена последняя версия",
                        mapOf(
                            "currentVersion" to currentVersion,
                            "targetVersion" to targetVersion,
                            "resultCode" to VoxOtaErrorCode.VERSION_NOT_NEWER.name,
                            "channel" to channelName
                        )
                    )
                    VoxSafeLogger.info(
                        VoxLogCategory.OTA,
                        VoxLogCode.OTA_CHECK_COMPLETED,
                        "Проверка обновлений завершена",
                        mapOf(
                            "resultCode" to VoxOtaErrorCode.NO_UPDATE.name,
                            "elapsedMs" to elapsed.toString(),
                            "retryable" to "false"
                        )
                    )
                    markCheckSucceeded(currentVersion, channelName)
                    mainHandler.post { callback.onNoUpdateAvailable(currentVersion) }
                    return@execute
                }

                val primaryAbi = try { DeviceHelpers.getPrimaryAbi() } catch (e: Exception) { null }
                val selectedAsset = VoxReleaseAssetSelector.selectAsset(release.assets, primaryAbi)
                    ?: throw VoxOtaException(
                        VoxOtaErrorCode.UNIVERSAL_ASSET_NOT_FOUND,
                        "Universal APK asset not found in release: ${release.tagName}",
                        VoxOtaStage.COMPARE,
                        null,
                        false
                    )

                VoxSafeLogger.info(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_UPDATE_AVAILABLE,
                    "Найдено доступное обновление",
                    mapOf(
                        "currentVersion" to currentVersion,
                        "targetVersion" to targetVersion,
                        "assetName" to selectedAsset.name
                    )
                )

                VoxSafeLogger.info(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_ASSET_SELECTED,
                    "Выбран файл обновления",
                    mapOf(
                        "assetName" to selectedAsset.name,
                        "sizeBytes" to selectedAsset.sizeBytes.toString(),
                        "deviceAbi" to (primaryAbi ?: "unknown")
                    )
                )

                VoxSafeLogger.info(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_CHECK_COMPLETED,
                    "Проверка обновлений завершена",
                    mapOf(
                        "resultCode" to VoxOtaErrorCode.UPDATE_AVAILABLE.name,
                        "elapsedMs" to elapsed.toString(),
                        "retryable" to "false"
                    )
                )

                markCheckSucceeded(currentVersion, channelName)
                mainHandler.post { callback.onUpdateAvailable(release, selectedAsset) }

            } catch (e: Throwable) {
                val elapsed = System.currentTimeMillis() - startTimeMs
                handleError(e, currentVersion, channelName, elapsed, callback)
            } finally {
                isInFlight.set(false)
            }
        }
    }

    /**
     * Загружает и проверяет выбранный APK файл обновления.
     */
    fun downloadAndVerify(
        asset: VoxReleaseAsset,
        release: VoxReleaseInfo,
        callback: Callback
    ) {
        executor.execute {
            var destinationFile: File? = null
            var tmpFile: File? = null
            isDownloadCancelled.set(false)
            try {
                VoxSafeLogger.info(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_DOWNLOAD_STARTED,
                    "Начато скачивание файла обновления",
                    mapOf("assetName" to asset.name)
                )

                val cacheDir = FileHelpers.getCacheDir(context) ?: context.cacheDir
                val freeSpace = cacheDir.usableSpace
                val requiredBytes = if (asset.sizeBytes > 0) asset.sizeBytes + 20 * 1024 * 1024L else 50 * 1024 * 1024L
                if (freeSpace in 1 until requiredBytes) {
                    throw VoxOtaException(
                        VoxOtaErrorCode.STORAGE_LOW,
                        "Недостаточно места на накопителе для загрузки обновления ($freeSpace байт свободно, требуется $requiredBytes байт)",
                        stage = VoxOtaStage.HTTP,
                        isRetryable = false
                    )
                }

                destinationFile = File(cacheDir, "update.apk")
                tmpFile = File(cacheDir, "update.apk.tmp")
                if (tmpFile.exists()) tmpFile.delete()
                if (destinationFile.exists()) destinationFile.delete()

                val startTime = System.currentTimeMillis()
                downloadToFile(asset.downloadUrl, tmpFile) { bytesRead, totalBytes, pct ->
                    mainHandler.post { callback.onDownloadProgress(bytesRead, totalBytes, pct) }
                }
                val elapsedMs = System.currentTimeMillis() - startTime

                // Проверка контрольной суммы SHA-256 (если доступна в манифесте релиза)
                val sumsUrl = release.sha256SumsUrl ?: VoxReleaseAssetSelector.findSha256SumsAsset(release.assets)?.downloadUrl
                var expectedSha256: String? = null
                if (!sumsUrl.isNullOrBlank()) {
                    try {
                        val sumsContent = downloadString(sumsUrl)
                        val sumsMap = VoxOtaSecurityVerifier.parseSha256Sums(sumsContent)
                        expectedSha256 = sumsMap[asset.name]
                    } catch (sumErr: Throwable) {
                        Log.w(TAG, "SHA256SUMS check skipped due to error: ${sumErr.message}")
                    }
                }

                // Строгая предварительная валидация скачанного APK перед переименованием
                val primaryAbi = try { DeviceHelpers.getPrimaryAbi() } catch (e: Exception) { null }
                VoxOtaPreInstallValidator.validateOrThrow(
                    context = context,
                    apkFile = tmpFile,
                    expectedSha256 = expectedSha256,
                    expectedContentLength = if (asset.sizeBytes > 0) asset.sizeBytes else null,
                    primaryAbi = primaryAbi
                )

                // Атомарное перемещение временного файла в update.apk
                if (!tmpFile.renameTo(destinationFile)) {
                    tmpFile.copyTo(destinationFile, overwrite = true)
                    tmpFile.delete()
                }

                VoxSafeLogger.info(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_DOWNLOAD_COMPLETED,
                    "Скачивание и предварительная валидация обновления завершены",
                    mapOf("assetName" to asset.name, "elapsedMs" to elapsedMs.toString(), "sizeBytes" to destinationFile.length().toString())
                )

                mainHandler.post { callback.onDownloadCompleted(destinationFile) }

            } catch (e: Throwable) {
                tmpFile?.delete()
                destinationFile?.delete()
                val otaEx = if (e is VoxOtaException) e else classifyException(e)
                if (otaEx.code == VoxOtaErrorCode.INSTALL_CANCELLED || isDownloadCancelled.get()) {
                    Log.d(TAG, "Download cancelled, skipping error callback")
                } else {
                    handleError(e, "", "", 0L, callback)
                }
            } finally {
                activeDownloadConnection = null
            }
        }
    }

    /**
     * Запускает установку скачанного APK обновления.
     */
    fun installUpdate(apkFile: File): Boolean {
        return try {
            val validation = VoxOtaPreInstallValidator.validate(context, apkFile)
            if (!validation.isValid) {
                VoxSafeLogger.error(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_PRE_VALIDATION_FAILED,
                    "Отклонён запуск установщика: предварительная валидация не пройдена (${validation.message})",
                    validation.details
                )
                return false
            }

            VoxSafeLogger.info(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_INSTALL_REQUESTED,
                "Запрос установки обновления приложения",
                mapOf("fileName" to apkFile.name, "fileSize" to apkFile.length().toString())
            )
            Helpers.installPackage(context, apkFile.absolutePath)
            true
        } catch (e: Exception) {
            VoxSafeLogger.error(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_FAILED,
                "Ошибка запуска установщика обновления",
                mapOf("error" to (e.message ?: e.javaClass.simpleName)),
                e
            )
            false
        }
    }

    private fun fetchReleaseInfo(
        manifestUrls: Array<String>?,
        isBetaChannel: Boolean,
        currentVersion: String? = null
    ): VoxReleaseInfo {
        val installedVersionCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        } catch (e: Throwable) {
            0L
        }

        // Сначала пробуем конфигурационные URL манифестов
        if (!manifestUrls.isNullOrEmpty()) {
            for (url in manifestUrls) {
                val safeUrl = sanitizeUrl(url)
                VoxSafeLogger.info(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_ENDPOINT_REQUEST,
                    "Запрос манифеста обновления",
                    mapOf("endpoint" to safeUrl, "stage" to VoxOtaStage.HTTP.name)
                )
                try {
                    val content = downloadString(url)
                    val parsed = VoxOtaReleaseParser.parseRelease(content, isBetaChannel, currentVersion, installedVersionCode)
                    return parsed
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch/parse manifest from $url: ${e.message}")
                }
            }
        }

        // Резервный канал: официальный GitHub Releases API или тестовый источник (только в debug)
        val testUrl = if (com.liskovsoft.smartyoutubetv2.common.BuildConfig.DEBUG) {
            testUpdateBaseUrl
                ?: System.getProperty("vox.ota.test_base_url")
                ?: getAndroidDebugProp("debug.vox.ota_base_url")
        } else {
            null
        }
        val fallbackApi = if (!testUrl.isNullOrBlank()) {
            val clean = testUrl.trimEnd('/')
            if (isBetaChannel) "$clean/releases" else "$clean/releases/latest"
        } else {
            if (isBetaChannel) GITHUB_RELEASES_LIST_API else GITHUB_LATEST_RELEASE_API
        }

        val safeApiUrl = sanitizeUrl(fallbackApi)
        VoxSafeLogger.info(
            VoxLogCategory.OTA,
            VoxLogCode.OTA_ENDPOINT_REQUEST,
            "Резервный запрос GitHub Releases API",
            mapOf("endpoint" to safeApiUrl, "stage" to VoxOtaStage.HTTP.name)
        )
        try {
            val content = downloadString(fallbackApi)
            return VoxOtaReleaseParser.parseRelease(content, isBetaChannel, currentVersion, installedVersionCode)
        } catch (e: Exception) {
            if (e is VoxOtaException) throw e
            throw classifyException(e, "Failed to reach update servers: ${e.message}", VoxOtaStage.CONNECT)
        }
    }

    private fun downloadString(urlStr: String): String {
        val reqStartTime = System.currentTimeMillis()
        val conn = openConnectionWithRedirects(urlStr)
        try {
            val code = conn.responseCode
            val reqElapsed = System.currentTimeMillis() - reqStartTime

            VoxSafeLogger.info(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_HTTP_RESULT,
                "HTTP ответ от сервера обновлений",
                mapOf("httpStatus" to code.toString(), "elapsedMs" to reqElapsed.toString())
            )

            if (code !in 200..299) {
                val remainingHeader = conn.getHeaderField("X-RateLimit-Remaining")
                val isRateLimited = code == 429 || (code == 403 && (remainingHeader == "0" || conn.getHeaderField("Retry-After") != null))

                val errorCode = when {
                    isRateLimited -> VoxOtaErrorCode.RATE_LIMIT
                    code == 404 -> VoxOtaErrorCode.RELEASE_NOT_FOUND
                    code in 500..599 -> VoxOtaErrorCode.HTTP_ERROR
                    code == 403 -> {
                        val errBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
                        if (errBody.contains("rate limit", ignoreCase = true)) {
                            VoxOtaErrorCode.RATE_LIMIT
                        } else {
                            VoxOtaErrorCode.HTTP_ERROR
                        }
                    }
                    else -> VoxOtaErrorCode.HTTP_ERROR
                }

                val isRetryable = errorCode == VoxOtaErrorCode.RATE_LIMIT ||
                        errorCode == VoxOtaErrorCode.TIMEOUT ||
                        errorCode == VoxOtaErrorCode.HTTP_ERROR

                throw VoxOtaException(
                    code = errorCode,
                    message = "HTTP error $code while fetching ${sanitizeUrl(urlStr)}",
                    stage = VoxOtaStage.HTTP,
                    httpStatus = code,
                    isRetryable = isRetryable
                )
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            if (e is VoxOtaException) throw e
            throw classifyException(e, "Network error fetching ${sanitizeUrl(urlStr)}", VoxOtaStage.CONNECT)
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadToFile(
        urlStr: String,
        targetFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long, percent: Int) -> Unit
    ) {
        val conn = openConnectionWithRedirects(urlStr)
        activeDownloadConnection = conn
        try {
            if (isDownloadCancelled.get()) {
                throw VoxOtaException(
                    code = VoxOtaErrorCode.INSTALL_CANCELLED,
                    message = "Download cancelled by user",
                    stage = VoxOtaStage.HTTP,
                    httpStatus = null,
                    isRetryable = false
                )
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errorCode = when (code) {
                    429 -> VoxOtaErrorCode.RATE_LIMIT
                    404 -> VoxOtaErrorCode.RELEASE_NOT_FOUND
                    else -> VoxOtaErrorCode.ASSET_DOWNLOAD_FAILED
                }
                throw VoxOtaException(
                    code = errorCode,
                    message = "HTTP error $code while downloading APK",
                    stage = VoxOtaStage.HTTP,
                    httpStatus = code,
                    isRetryable = errorCode == VoxOtaErrorCode.RATE_LIMIT
                )
            }

            val totalBytes = conn.contentLength.toLong()
            var bytesCopied = 0L

            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (isDownloadCancelled.get()) {
                            throw VoxOtaException(
                                code = VoxOtaErrorCode.INSTALL_CANCELLED,
                                message = "Download cancelled by user",
                                stage = VoxOtaStage.HTTP,
                                httpStatus = null,
                                isRetryable = false
                            )
                        }
                        output.write(buffer, 0, read)
                        bytesCopied += read
                        if (totalBytes > 0L) {
                            val pct = ((bytesCopied * 100) / totalBytes).toInt().coerceIn(0, 100)
                            onProgress(bytesCopied, totalBytes, pct)
                        } else {
                            onProgress(bytesCopied, -1L, -1)
                        }
                    }
                    output.flush()
                    try {
                        output.fd.sync()
                    } catch (e: Exception) {
                        Log.w(TAG, "File descriptor sync skipped: ${e.message}")
                    }
                }
            }

            if (totalBytes > 0L && bytesCopied != totalBytes) {
                throw VoxOtaException(
                    VoxOtaErrorCode.DOWNLOAD_INCOMPLETE,
                    "Загрузка оборвана: получено $bytesCopied байт из ожидаемых $totalBytes",
                    stage = VoxOtaStage.HTTP,
                    httpStatus = code,
                    isRetryable = true
                )
            }
        } catch (e: Exception) {
            if (e is VoxOtaException) throw e
            throw classifyException(e, "Failed to download update APK: ${e.message}", VoxOtaStage.CONNECT)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnectionWithRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var redirectCount = 0

        while (redirectCount < MAX_REDIRECTS) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json, application/json, */*")

            val status = conn.responseCode
            if (status in 301..308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw VoxOtaException(
                        code = VoxOtaErrorCode.NETWORK_ERROR,
                        message = "Empty redirect location header",
                        stage = VoxOtaStage.CONNECT,
                        httpStatus = status,
                        isRetryable = false
                    )
                }
                currentUrl = location
                redirectCount++
            } else {
                return conn
            }
        }
        throw VoxOtaException(
            code = VoxOtaErrorCode.NETWORK_ERROR,
            message = "Too many redirects while accessing $initialUrl",
            stage = VoxOtaStage.CONNECT,
            httpStatus = null,
            isRetryable = false
        )
    }

    private fun handleError(
        throwable: Throwable,
        currentVersion: String,
        channel: String,
        elapsedMs: Long,
        callback: Callback
    ) {
        val otaError = if (throwable is VoxOtaException) {
            throwable
        } else {
            classifyException(throwable)
        }

        val eventCode = when (otaError.code) {
            VoxOtaErrorCode.RATE_LIMIT -> VoxLogCode.OTA_RATE_LIMIT
            VoxOtaErrorCode.DNS_ERROR -> VoxLogCode.OTA_DNS_ERROR
            VoxOtaErrorCode.TIMEOUT -> VoxLogCode.OTA_TIMEOUT
            VoxOtaErrorCode.TLS_ERROR -> VoxLogCode.OTA_TLS_ERROR
            VoxOtaErrorCode.HTTP_ERROR -> VoxLogCode.OTA_HTTP_ERROR
            VoxOtaErrorCode.RELEASE_NOT_FOUND -> VoxLogCode.OTA_RELEASE_NOT_FOUND
            VoxOtaErrorCode.INVALID_RELEASE -> VoxLogCode.OTA_INVALID_RELEASE
            VoxOtaErrorCode.CHANNEL_MISMATCH -> VoxLogCode.OTA_CHANNEL_MISMATCH
            else -> VoxLogCode.OTA_FAILED
        }

        VoxSafeLogger.error(
            VoxLogCategory.OTA,
            eventCode,
            "Ошибка процесса обновления: ${otaError.userMessageRu}",
            mapOf(
                "errorCode" to otaError.code.name,
                "stage" to otaError.stage.name,
                "httpStatus" to (otaError.httpStatus?.toString() ?: "none"),
                "isRetryable" to otaError.isRetryable.toString(),
                "reason" to (throwable.javaClass.simpleName),
                "channel" to channel,
                "currentVersion" to currentVersion
            )
        )

        VoxSafeLogger.info(
            VoxLogCategory.OTA,
            VoxLogCode.OTA_CHECK_COMPLETED,
            "Проверка обновлений завершена с ошибкой",
            mapOf(
                "resultCode" to otaError.code.name,
                "elapsedMs" to elapsedMs.toString(),
                "retryable" to otaError.isRetryable.toString()
            )
        )

        if (otaError.code == VoxOtaErrorCode.RATE_LIMIT) {
            lastRateLimitTimeMs = System.currentTimeMillis()
            wasRateLimited = true
        }

        // RELEASE_NOT_FOUND трактуется как отсутствие обновлений без пугания пользователя
        if (otaError.code == VoxOtaErrorCode.RELEASE_NOT_FOUND ||
            otaError.code == VoxOtaErrorCode.NO_UPDATE ||
            otaError.code == VoxOtaErrorCode.VERSION_NOT_NEWER) {
            mainHandler.post { callback.onNoUpdateAvailable(currentVersion) }
        } else {
            mainHandler.post { callback.onError(otaError) }
        }
    }

    private fun getAndroidDebugProp(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            val value = method.invoke(null, key) as? String
            if (value.isNullOrBlank()) null else value
        } catch (e: Throwable) {
            null
        }
    }
}
