package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.liskovsoft.sharedutils.helpers.FileHelpers
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.io.File
import java.io.FileInputStream

/**
 * Надежный установщик пакетов обновления SmartTube VOX.
 *
 * Архитектура:
 * 1. Предварительная валидация APK (VoxOtaPreInstallValidator: целостность, подпись, minSize, versionCode).
 * 2. Проверка разрешения REQUEST_INSTALL_PACKAGES (canRequestPackageInstalls на API 26+).
 * 3. Основной механизм: android.content.pm.PackageInstaller Session API (commit с IntentSender).
 *    Передаёт управление системному PackageInstaller Android с обработкой STATUS_PENDING_USER_ACTION.
 * 4. Резервный механизм: Intent.ACTION_VIEW через FileProvider с FLAG_GRANT_READ_URI_PERMISSION.
 * 5. Не логирует приватные токены/секреты; фиксирует структурированные диагностические события.
 */
object VoxOtaInstaller {

    const val ACTION_INSTALL_STATUS = "com.liskovsoft.smartyoutubetv2.common.vox.ota.INSTALL_STATUS"
    const val REQUEST_CODE_INSTALL = 44921

    sealed class InstallResult {
        object Launched : InstallResult()
        object PermissionRequired : InstallResult()
        data class Failed(
            val code: VoxOtaErrorCode,
            val message: String,
            val cause: Throwable? = null
        ) : InstallResult()
    }

    /**
     * Проверяет, разрешена ли приложению установка неизвестных пакетов.
     * На Android < 8.0 (API 26) всегда возвращает true.
     */
    @JvmStatic
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.packageManager.canRequestPackageInstalls()
            } catch (e: Throwable) {
                true
            }
        } else {
            true
        }
    }

    /**
     * Формирует Intent для открытия системных настроек установки неизвестных приложений.
     */
    @JvmStatic
    fun createManageUnknownSourcesIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (e: Throwable) {
                Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Запускает установку скачанного файла обновления APK.
     *
     * @param context Контекст приложения
     * @param apkFile Локальный файл APK обновления
     * @return Результат запуска установки
     */
    @JvmStatic
    fun installPackage(context: Context, apkFile: File): InstallResult {
        // 1. Предварительная строгая валидация пакета
        val validation = VoxOtaPreInstallValidator.validate(context, apkFile)
        if (!validation.isValid) {
            val code = validation.errorCode ?: VoxOtaErrorCode.APK_INVALID
            val msg = validation.message ?: "Предварительная валидация пакета не пройдена"
            VoxSafeLogger.error(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_PRE_VALIDATION_FAILED,
                "Установка отклонена: $msg",
                validation.details
            )
            return InstallResult.Failed(code, msg)
        }

        // 2. Проверка разрешения на установку
        if (!canRequestPackageInstalls(context)) {
            VoxSafeLogger.warn(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_PERMISSION_DENIED,
                "Отсутствует системное разрешение canRequestPackageInstalls",
                mapOf("packageName" to context.packageName)
            )
            return InstallResult.PermissionRequired
        }

        VoxSafeLogger.info(
            VoxLogCategory.OTA,
            VoxLogCode.OTA_INSTALL_REQUESTED,
            "Запрос запуска системного установщика обновления",
            mapOf("fileName" to apkFile.name, "fileSize" to apkFile.length().toString())
        )

        // 3. Основной путь: PackageInstaller Session API (Android 5.0+, API 21+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val sessionResult = installViaPackageInstaller(context, apkFile)
            if (sessionResult is InstallResult.Launched) {
                return sessionResult
            }
            VoxSafeLogger.warn(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_INSTALL_FAILED,
                "PackageInstaller session не запустилась, переход на резервный FileProvider Intent",
                null
            )
        }

        // 4. Резервный путь: FileProvider + Intent.ACTION_VIEW
        return installViaFileProvider(context, apkFile)
    }

    private fun installViaPackageInstaller(context: Context, apkFile: File): InstallResult {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.use { s ->
                FileInputStream(apkFile).use { input ->
                    s.openWrite("package", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        s.fsync(output)
                    }
                }

                val intent = Intent(context, VoxPackageInstallerReceiver::class.java).apply {
                    action = ACTION_INSTALL_STATUS
                    setPackage(context.packageName)
                }

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    flags
                )

                s.commit(pendingIntent.intentSender)
            }

            VoxSafeLogger.info(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_INSTALL_REQUESTED,
                "Сессия PackageInstaller успешно передана в commit(IntentSender)",
                mapOf("sessionId" to sessionId.toString())
            )
            InstallResult.Launched
        } catch (e: Throwable) {
            VoxSafeLogger.error(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_INSTALL_FAILED,
                "Ошибка при создании/фиксации сессии PackageInstaller: ${e.message}",
                mapOf("error" to (e.message ?: e.javaClass.simpleName)),
                e
            )
            InstallResult.Failed(VoxOtaErrorCode.INSTALL_FAILED, e.message ?: "PackageInstaller failed", e)
        }
    }

    private fun installViaFileProvider(context: Context, apkFile: File): InstallResult {
        return try {
            val uri = FileHelpers.getFileUri(context, apkFile)
            if (uri == null) {
                return InstallResult.Failed(
                    VoxOtaErrorCode.FILE_PROVIDER_ERROR,
                    "Не удалось получить content URI файла через FileProvider"
                )
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
            VoxSafeLogger.info(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_INSTALL_REQUESTED,
                "Запущен Intent.ACTION_VIEW для установки через FileProvider",
                mapOf("uri" to uri.toString())
            )
            InstallResult.Launched
        } catch (e: Throwable) {
            VoxSafeLogger.error(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_INSTALL_FAILED,
                "Ошибка запуска Intent.ACTION_VIEW: ${e.message}",
                mapOf("error" to (e.message ?: e.javaClass.simpleName)),
                e
            )
            InstallResult.Failed(VoxOtaErrorCode.INSTALL_FAILED, e.message ?: "FileProvider launch failed", e)
        }
    }
}
