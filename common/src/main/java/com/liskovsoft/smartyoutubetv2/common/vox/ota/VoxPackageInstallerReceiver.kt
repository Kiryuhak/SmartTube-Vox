package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger

/**
 * Приёмник системных событий сессии Android PackageInstaller.
 *
 * Обрабатывает:
 * - STATUS_PENDING_USER_ACTION: запуск системного окна подтверждения установки;
 * - STATUS_SUCCESS: успешное обновление приложения;
 * - STATUS_FAILURE_ABORTED: отмена пользователем;
 * - Ошибки установки: CONFLICT, INCOMPATIBLE, INVALID, STORAGE.
 */
class VoxPackageInstallerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Системный установщик ожидает подтверждения пользователя
                @Suppress("DEPRECATION")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                        VoxSafeLogger.info(
                            VoxLogCategory.OTA,
                            VoxLogCode.OTA_INSTALL_REQUESTED,
                            "Отображено системное окно подтверждения установки Android",
                            mapOf("status" to "PENDING_USER_ACTION")
                        )
                    } catch (e: Throwable) {
                        VoxSafeLogger.error(
                            VoxLogCategory.OTA,
                            VoxLogCode.OTA_INSTALL_FAILED,
                            "Ошибка запуска системного окна подтверждения установки: ${e.message}",
                            mapOf("error" to (e.message ?: e.javaClass.simpleName)),
                            e
                        )
                    }
                } else {
                    VoxSafeLogger.error(
                        VoxLogCategory.OTA,
                        VoxLogCode.OTA_INSTALL_FAILED,
                        "STATUS_PENDING_USER_ACTION получен без EXTRA_INTENT",
                        null
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                VoxSafeLogger.info(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_INSTALLED,
                    "Обновление успешно установлено через PackageInstaller",
                    mapOf("status" to "SUCCESS")
                )
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                VoxSafeLogger.info(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_CANCELLED,
                    "Пользователь отменил установку обновления в системном окне",
                    mapOf("status" to "ABORTED")
                )
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                VoxSafeLogger.warn(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_PERMISSION_DENIED,
                    "Установка заблокирована политикой безопасности устройства",
                    mapOf("status" to "BLOCKED", "message" to (message ?: ""))
                )
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                VoxSafeLogger.error(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_INSTALL_FAILED,
                    "Конфликт пакета: несовпадение цифровой подписи или провайдера",
                    mapOf("status" to "CONFLICT", "message" to (message ?: ""))
                )
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                VoxSafeLogger.error(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_INSTALL_FAILED,
                    "Недостаточно места на накопителе для завершения установки",
                    mapOf("status" to "STORAGE", "message" to (message ?: ""))
                )
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                VoxSafeLogger.error(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_INSTALL_FAILED,
                    "Пакет обновления несовместим с аппаратной платформой устройства",
                    mapOf("status" to "INCOMPATIBLE", "message" to (message ?: ""))
                )
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                VoxSafeLogger.error(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_INSTALL_FAILED,
                    "Некорректная структура пакета обновления APK",
                    mapOf("status" to "INVALID", "message" to (message ?: ""))
                )
            }

            else -> {
                VoxSafeLogger.error(
                    VoxLogCategory.OTA,
                    VoxLogCode.OTA_INSTALL_FAILED,
                    "Ошибка установки PackageInstaller: код $status ($message)",
                    mapOf("status" to status.toString(), "message" to (message ?: ""))
                )
            }
        }
    }
}
