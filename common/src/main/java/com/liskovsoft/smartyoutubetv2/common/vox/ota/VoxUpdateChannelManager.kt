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

    companion object {
        private const val PREFS_NAME = "vox_update_channel_prefs"
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private const val KEY_BETA_DIAGNOSTICS_ENABLED = "beta_diagnostics_enabled"
        private const val KEY_STABLE_DIAGNOSTICS_ENABLED = "stable_diagnostics_enabled"
        private const val KEY_LAST_DIAGNOSTICS_TIMESTAMP = "last_diagnostics_timestamp"
        private const val KEY_SEEN_TEST_WARNING = "seen_test_warning"

        @Volatile
        private var sInstance: VoxUpdateChannelManager? = null

        @JvmStatic
        fun instance(context: Context): VoxUpdateChannelManager {
            return sInstance ?: synchronized(this) {
                sInstance ?: VoxUpdateChannelManager(context.applicationContext).also { sInstance = it }
            }
        }
    }

    /**
     * Возвращает текущий выбранный канал обновлений.
     */
    fun getUpdateChannel(): VoxUpdateChannel {
        val channelId = prefs.getString(KEY_UPDATE_CHANNEL, VoxUpdateChannel.STABLE.id)
        return VoxUpdateChannel.fromId(channelId)
    }

    /**
     * Устанавливает канал обновлений.
     * При переходе с TEST на STABLE согласие на бета-диагностику автоматически аннулируется.
     */
    fun setUpdateChannel(channel: VoxUpdateChannel) {
        val previous = getUpdateChannel()
        prefs.edit().apply {
            putString(KEY_UPDATE_CHANNEL, channel.id)
            if (previous == VoxUpdateChannel.TEST && channel == VoxUpdateChannel.STABLE) {
                putBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, false)
            }
            apply()
        }
    }

    /**
     * Проверяет, активен ли тестовый/бета канал.
     */
    fun isBetaChannel(): Boolean {
        return getUpdateChannel() == VoxUpdateChannel.TEST
    }

    /**
     * Согласие на автоматическую диагностику тестовых версий (раз в 6 часов).
     */
    fun isBetaDiagnosticsEnabled(): Boolean {
        return prefs.getBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, false)
    }

    fun setBetaDiagnosticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BETA_DIAGNOSTICS_ENABLED, enabled).apply()
    }

    /**
     * Согласие на редкую диагностику стабильных версий (раз в 72 часа).
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
