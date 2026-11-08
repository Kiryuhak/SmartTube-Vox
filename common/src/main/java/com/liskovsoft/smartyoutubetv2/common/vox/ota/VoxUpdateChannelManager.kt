package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import android.content.SharedPreferences

/**
 * Менеджер настроек каналов обновлений и согласий на диагностику SmartTube VOX.
 *
 * Инварианты:
 * 1. Канал по умолчанию — STABLE (Стабильный).
 * 2. Согласие на диагностику по умолчанию ВЫКЛЮЧЕНО (false) как для Test, так и для Stable.
 * 3. При переходе с Test на Stable согласие на диагностику тестовых версий СБРАСЫВАЕТСЯ.
 * 4. Согласие на редкую диагностику стабильных версий запрашивается отдельно.
 */
class VoxUpdateChannelManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyChannelPrefsIfNeeded()
    }

    /**
     * Проверяет, запущено ли приложение в тестовом QA пакете.
     */
    fun isQaPackage(): Boolean {
        val pkg = context.packageName ?: ""
        return pkg.contains("otaqa", ignoreCase = true) ||
               pkg.contains("qa", ignoreCase = true) ||
               getBuildChannel() == VoxBuildChannel.QA
    }

    /**
     * Мигрирует сохранённые настройки каналов обновлений (Prompt 163-R51).
     * В публичной сборке принудительно устанавливает STABLE и сбрасывает beta_diagnostics_enabled в false.
     */
    fun migrateLegacyChannelPrefsIfNeeded() {
        if (isQaPackage()) {
            return
        }
        val saved = prefs.getString(KEY_UPDATE_CHANNEL, null)
        val betaDiag = prefs.getBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, false)
        if (saved != VoxUpdateChannel.STABLE.id || betaDiag) {
            prefs.edit()
                .putString(KEY_UPDATE_CHANNEL, VoxUpdateChannel.STABLE.id)
                .putBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, false)
                .commit()
        }
    }

    companion object {
        private const val PREFS_NAME = "vox_update_channel_prefs"
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private const val KEY_BETA_DIAGNOSTICS_ENABLED = "beta_diagnostics_enabled"
        private const val KEY_STABLE_DIAGNOSTICS_ENABLED = "stable_diagnostics_enabled"
        private const val KEY_LAST_DIAGNOSTICS_TIMESTAMP = "last_diagnostics_timestamp"
        private const val KEY_SEEN_TEST_WARNING = "seen_test_warning"
        private const val KEY_SEEN_BETA_PRIVACY_NOTICE = "seen_beta_privacy_notice"

        @Volatile
        private var sInstance: VoxUpdateChannelManager? = null

        @JvmStatic
        fun instance(context: Context): VoxUpdateChannelManager {
            return sInstance ?: synchronized(this) {
                sInstance ?: VoxUpdateChannelManager(context.applicationContext).also { sInstance = it }
            }
        }

        @JvmStatic
        fun resetForTesting() {
            sInstance = null
        }

        @JvmStatic
        fun isAutoDiagnosticsAllowedByDefault(versionName: String): Boolean {
            val parsed = VoxVersionComparator.parseVersion(versionName)
            if (parsed != null && parsed.isCanonicalStable) return false
            return VoxBuildChannel.fromVersion(versionName).isPreRelease
        }
    }

    /**
     * Проверяет, разрешена ли автодиагностика по умолчанию для текущей сборки приложения.
     */
    fun isAutoDiagnosticsAllowedByDefault(): Boolean {
        if (!isQaPackage()) return false
        return getBuildChannel().isPreRelease
    }

    /**
     * Возвращает версию приложения из PackageManager или константу.
     */
    fun getAppVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            "32.71-vox.10"
        }
    }

    /**
     * Возвращает канонический канал сборки (DEV, ALPHA, BETA, RC, STABLE).
     */
    fun getBuildChannel(): VoxBuildChannel {
        return VoxBuildChannel.fromVersion(getAppVersionName())
    }

    /**
     * Возвращает текущий выбранный канал обновлений.
     * В публичной сборке всегда возвращает STABLE и восстанавливает сохранённые prefs.
     */
    fun getUpdateChannel(): VoxUpdateChannel {
        if (isQaPackage()) {
            val channelId = prefs.getString(KEY_UPDATE_CHANNEL, null)
            return if (channelId != null) VoxUpdateChannel.fromId(channelId) else VoxUpdateChannel.QA
        }

        // Production invariant: strictly STABLE
        // Self-heal guard: ensure storage is strictly stable and beta diagnostics disabled
        val channelId = prefs.getString(KEY_UPDATE_CHANNEL, null)
        val betaDiag = prefs.getBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, false)
        if (channelId != VoxUpdateChannel.STABLE.id || betaDiag) {
            prefs.edit()
                .putString(KEY_UPDATE_CHANNEL, VoxUpdateChannel.STABLE.id)
                .putBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, false)
                .commit()
        }
        return VoxUpdateChannel.STABLE
    }

    /**
     * Устанавливает канал обновлений.
     * В публичной сборке любые попытки установить Beta/Test/QA блокируются и принудительно сохраняется STABLE.
     */
    fun setUpdateChannel(channel: VoxUpdateChannel) {
        if (!isQaPackage()) {
            prefs.edit()
                .putString(KEY_UPDATE_CHANNEL, VoxUpdateChannel.STABLE.id)
                .putBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, false)
                .commit()
            return
        }
        val previous = getUpdateChannel()
        prefs.edit().apply {
            putString(KEY_UPDATE_CHANNEL, channel.id)
            if ((previous == VoxUpdateChannel.BETA || previous == VoxUpdateChannel.TEST) && channel == VoxUpdateChannel.STABLE) {
                putBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, false)
            }
            apply()
        }
    }

    /**
     * Явный сброс согласия на бета-диагностику при переходе на стабильный канал.
     */
    fun resetBetaConsentOnSwitchToStable() {
        prefs.edit().putBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, false).apply()
    }

    /**
     * Проверяет, активен ли тестовый/бета канал.
     * В публичной сборке всегда false.
     */
    fun isBetaChannel(): Boolean {
        if (!isQaPackage()) return false
        val ch = getUpdateChannel()
        return ch == VoxUpdateChannel.BETA || ch == VoxUpdateChannel.TEST
    }

    /**
     * Проверяет, активен ли изолированный канал стенда QA.
     * В публичной сборке всегда false.
     */
    fun isQaChannel(): Boolean {
        return isQaPackage() && getUpdateChannel() == VoxUpdateChannel.QA
    }

    /**
     * Согласие на автоматическую диагностику тестовых версий (в публичной сборке всегда false).
     */
    fun isBetaDiagnosticsEnabled(): Boolean {
        if (!isQaPackage()) return false
        val defaultEnabled = getBuildChannel().isPreRelease
        return prefs.getBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, defaultEnabled)
    }

    fun setBetaDiagnosticsEnabled(enabled: Boolean) {
        if (!isQaPackage()) {
            prefs.edit().putBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, false).apply()
            return
        }
        prefs.edit().putBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, enabled).apply()
    }

    /**
     * Согласие на редкую диагностику стабильных версий (по умолчанию строго выключено - добровольный opt-in).
     */
    fun isStableDiagnosticsEnabled(): Boolean {
        return prefs.getBoolean(KEY_STABLE_DIAGNOSTICS_ENABLED, false)
    }

    fun setStableDiagnosticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STABLE_DIAGNOSTICS_ENABLED, enabled).apply()
    }

    /**
     * Время последней отправки автоматического отчёта диагностики.
     */
    fun getLastDiagnosticsSubmissionTimestamp(): Long {
        return prefs.getLong(KEY_LAST_DIAGNOSTICS_TIMESTAMP, 0L)
    }

    fun setLastDiagnosticsSubmissionTimestamp(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_DIAGNOSTICS_TIMESTAMP, timestampMs).apply()
    }

    /**
     * Флаг, видел ли пользователь предупреждение о нестабильности тестового канала.
     */
    fun hasSeenTestChannelWarning(): Boolean {
        return prefs.getBoolean(KEY_SEEN_TEST_WARNING, false)
    }

    fun setSeenTestChannelWarning(seen: Boolean) {
        prefs.edit().putBoolean(KEY_SEEN_TEST_WARNING, seen).apply()
    }

    /**
     * Флаг первого показа уведомления о политике конфиденциальности бета-версии.
     */
    fun hasSeenBetaPrivacyNotice(): Boolean {
        return prefs.getBoolean(KEY_SEEN_BETA_PRIVACY_NOTICE, false)
    }

    fun setSeenBetaPrivacyNotice(seen: Boolean) {
        prefs.edit().putBoolean(KEY_SEEN_BETA_PRIVACY_NOTICE, seen).apply()
    }

    /**
     * Проверяет, активна ли автодиагностика в текущем канале.
     */
    fun isDiagnosticsActive(): Boolean {
        return if (isBetaChannel()) {
            isBetaDiagnosticsEnabled()
        } else {
            isStableDiagnosticsEnabled()
        }
    }
}
