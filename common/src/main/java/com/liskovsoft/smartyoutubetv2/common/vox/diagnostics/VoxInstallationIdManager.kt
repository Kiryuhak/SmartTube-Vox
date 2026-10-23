package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import android.content.SharedPreferences
import com.liskovsoft.sharedutils.mylogger.Log
import java.util.UUID

/**
 * Централизованный менеджер постоянного анонимного идентификатора установки SmartTube VOX.
 *
 * Требования безопасности и конфиденциальности:
 * 1. Генерирует случайный криптостойкий UUID v4 при первом запуске;
 * 2. Полностью анонимен: не использует Google Account ID, Android ID, IMEI, MAC-адрес или серийные номера;
 * 3. Сохраняется между перезапусками приложения и при любых OTA обновлениях (VOX 9.1 -> 9.2);
 * 4. Защищён от неконтролируемого клонирования на другие устройства через исключение файла
 *    настроек vox_installation_identity.xml из cloud backup и device transfer;
 * 5. Поддерживает безопасный сброс при очистке диагностических данных.
 */
class VoxInstallationIdManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedInstallationId: String? = null

    /**
     * Возвращает постоянный идентификатор установки приложения (UUID v4).
     * Если идентификатор ещё не создан, атомарно генерирует новый и сохраняет его.
     */
    fun getInstallationId(): String {
        cachedInstallationId?.let { return it }

        synchronized(this) {
            cachedInstallationId?.let { return it }

            var storedId = prefs.getString(KEY_INSTALLATION_ID, null)
            if (storedId.isNullOrBlank()) {
                val newId = UUID.randomUUID().toString().lowercase()
                prefs.edit().putString(KEY_INSTALLATION_ID, newId).apply()
                storedId = newId
                Log.d(TAG, "Generated new anonymous installation ID: $newId")
            }
            cachedInstallationId = storedId
            return storedId
        }
    }

    /**
     * Сбрасывает сохранённый идентификатор установки (вызывается только при явном сбросе
     * диагностических данных пользователем в настройках).
     */
    fun resetInstallationId(): String {
        synchronized(this) {
            val newId = UUID.randomUUID().toString().lowercase()
            prefs.edit().putString(KEY_INSTALLATION_ID, newId).apply()
            cachedInstallationId = newId
            Log.d(TAG, "Reset installation ID to: $newId")
            return newId
        }
    }

    companion object {
        private const val TAG = "VoxInstallationId"
        const val PREFS_NAME = "vox_installation_identity"
        const val KEY_INSTALLATION_ID = "vox_installation_id"

        @Volatile
        private var sInstance: VoxInstallationIdManager? = null

        @JvmStatic
        fun getInstance(context: Context): VoxInstallationIdManager {
            return sInstance ?: synchronized(this) {
                sInstance ?: VoxInstallationIdManager(context.applicationContext).also { sInstance = it }
            }
        }

        /**
         * Метод для тестового сброса синглтона.
         */
        @JvmStatic
        internal fun resetInstanceForTesting() {
            synchronized(this) {
                sInstance = null
            }
        }
    }
}
