package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import android.content.SharedPreferences
import com.liskovsoft.sharedutils.mylogger.Log

/**
 * Менеджер жизненного цикла автоматической анонимной диагностики SmartTube VOX.
 *
 * Политика:
 * - Режим A (стандартный):
 *   - Запускается только при первой установке или реальном обновлении версии приложения.
 *   - Интервал периодической отправки: 6 часов.
 *   - Продолжительность: 3 календарных суток (72 часа) от cycleStartedAt.
 *   - По истечении срока автоматическая отправка отключается.
 *
 * - Режим B (расширенная диагностика):
 *   - Интервал периодической отправки: 12 часов.
 *   - Продолжительность: 5 суток (120 часов) от первоначального cycleStartedAt текущей версии.
 *   - По истечении срока авто-отправка отключается, а галочка расширенной диагностики снимается.
 *   - Режим B замещает стандартный график (не добавляет 5 суток поверх 3).
 *   - Первоначальный cycleStartedAt при смене режима строго сохраняется.
 *   - Если 120 часов истекли, повторный запуск невозможен до следующей версии приложения.
 *
 * - Приоритет согласия:
 *   - Явный отказ пользователя (opt-out) имеет абсолютный приоритет над расписанием и обновлениями.
 *   - Ручная отправка отчёта всегда остаётся доступной.
 */
class VoxDiagnosticsLifecycleManager private constructor(private val context: Context) {

    enum class DiagnosticsMode {
        STANDARD,
        EXTENDED,
        DISABLED
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "VoxDiagLifecycle"
        const val PREFS_NAME = "vox_diagnostics_lifecycle_prefs"

        const val KEY_CYCLE_STARTED_AT = "diag_cycle_started_at"
        const val KEY_LAST_APP_VERSION = "diag_last_app_version"
        const val KEY_AUTO_DIAGNOSTICS_ACTIVE = "diag_auto_active"
        const val KEY_EXTENDED_MODE_ENABLED = "diag_extended_mode"
        const val KEY_USER_EXPLICIT_OPT_OUT = "diag_user_opt_out"
        const val KEY_LAST_SUBMISSION_TIMESTAMP = "diag_last_submission_ts"

        const val STANDARD_INTERVAL_MS = 6 * 60 * 60 * 1000L           // 6 часов
        const val EXTENDED_INTERVAL_MS = 12 * 60 * 60 * 1000L          // 12 часов
        const val STANDARD_CYCLE_DURATION_MS = 72 * 60 * 60 * 1000L     // 3 суток (72 часа)
        const val EXTENDED_CYCLE_DURATION_MS = 120 * 60 * 60 * 1000L    // 5 суток (120 часов)

        @Volatile
        private var sInstance: VoxDiagnosticsLifecycleManager? = null

        @JvmStatic
        fun instance(context: Context): VoxDiagnosticsLifecycleManager {
            return sInstance ?: synchronized(this) {
                sInstance ?: VoxDiagnosticsLifecycleManager(context.applicationContext).also { sInstance = it }
            }
        }
    }

    /**
     * Обрабатывает запуск приложения.
     * Запускает новый цикл ТОЛЬКО при:
     * 1. Первой установке (lastAppVersion == null).
     * 2. Реальном обновлении версии приложения (lastAppVersion != currentVersion).
     *
     * НЕ сбрасывает цикл при:
     * - обычном перезапуске;
     * - перезагрузке ОС;
     * - падении;
     * - очистке кеша;
     * - повторном открытии настроек.
     */
    @Synchronized
    fun onAppStartup(currentVersion: String, now: Long = System.currentTimeMillis()) {
        if (isUserOptedOut()) {
            Log.d(TAG, "onAppStartup: user explicitly opted out from automatic diagnostics, keeping disabled")
            return
        }

        val lastVersion = prefs.getString(KEY_LAST_APP_VERSION, null)

        if (lastVersion == null) {
            // Первая установка приложения
            Log.i(TAG, "First app installation detected ($currentVersion), starting new 72h standard cycle")
            startNewCycle(currentVersion, now)
        } else if (lastVersion != currentVersion) {
            // Реальное обновление версии приложения
            Log.i(TAG, "App updated from $lastVersion to $currentVersion, starting new 72h standard cycle")
            startNewCycle(currentVersion, now)
        } else {
            // Повторный запуск той же версии — не сбрасываем cycleStartedAt, проверяем истечение
            checkExpiration(now)
        }
    }

    private fun startNewCycle(version: String, now: Long) {
        prefs.edit()
            .putLong(KEY_CYCLE_STARTED_AT, now)
            .putString(KEY_LAST_APP_VERSION, version)
            .putBoolean(KEY_AUTO_DIAGNOSTICS_ACTIVE, true)
            .putBoolean(KEY_EXTENDED_MODE_ENABLED, false) // Сброс режима на стандартный для новой версии
            .putLong(KEY_LAST_SUBMISSION_TIMESTAMP, 0L)
            .apply()

        VoxSafeLogger.i(
            VoxLogCategory.DIAGNOSTICS,
            VoxLogCode.AUTO_DIAG_STARTED,
            "Запущен новый цикл автоматической диагностики (72ч, интервал 6ч)",
            mapOf("version" to version, "cycleStartedAt" to now.toString())
        )
    }

    /**
     * Проверяет истечение срока действия автоматической диагностики.
     * Если срок истёк — автоматически выключает автодиагностику и снимает расширенную галочку.
     * @return true если цикл истёк или неактивен, false если цикл ещё действует.
     */
    @Synchronized
    fun checkExpiration(now: Long = System.currentTimeMillis()): Boolean {
        if (!isAutoDiagnosticsActive()) {
            return true
        }

        val startedAt = getCycleStartedAt()
        if (startedAt <= 0L) {
            setAutoDiagnosticsActive(false)
            return true
        }

        val elapsed = now - startedAt
        val isExtended = isExtendedModeEnabled()
        val maxDuration = if (isExtended) EXTENDED_CYCLE_DURATION_MS else STANDARD_CYCLE_DURATION_MS

        if (elapsed >= maxDuration) {
            Log.i(TAG, "Automatic diagnostics cycle expired (elapsed=${elapsed / 3_600_000}h >= ${maxDuration / 3_600_000}h), disabling")
            prefs.edit()
                .putBoolean(KEY_AUTO_DIAGNOSTICS_ACTIVE, false)
                .putBoolean(KEY_EXTENDED_MODE_ENABLED, false)
                .apply()

            VoxSafeLogger.i(
                VoxLogCategory.DIAGNOSTICS,
                VoxLogCode.AUTO_DIAG_STOPPED,
                "Цикл автодиагностики завершён по истечении времени",
                mapOf("elapsedHours" to (elapsed / 3_600_000).toString(), "mode" to if (isExtended) "EXTENDED" else "STANDARD")
            )
            return true
        }

        return false
    }

    /**
     * Разрешена ли отправка автоматической периодической диагностики в текущий момент.
     */
    @Synchronized
    fun isAutoDiagnosticsPermitted(now: Long = System.currentTimeMillis()): Boolean {
        if (isUserOptedOut()) return false
        if (checkExpiration(now)) return false
        return isAutoDiagnosticsActive()
    }

    /**
     * Проверяет, можно ли отправить очередной периодический отчёт с учётом интервала (6ч или 12ч).
     */
    @Synchronized
    fun canSendPeriodic(now: Long = System.currentTimeMillis()): Boolean {
        if (!isAutoDiagnosticsPermitted(now)) return false

        val lastSub = getLastSubmissionTimestamp()
        val interval = getCurrentIntervalMs()

        return lastSub == 0L || (now - lastSub) >= interval
    }

    /**
     * Возвращает желаемый интервал отправки для текущего режима.
     */
    fun getCurrentIntervalMs(): Long {
        return if (isExtendedModeEnabled()) EXTENDED_INTERVAL_MS else STANDARD_INTERVAL_MS
    }

    /**
     * Включение или выключение режима расширенной диагностики (Mode B).
     * - Сохраняет первоначальный cycleStartedAt (не сбрасывает срок).
     * - Если прошло >= 120ч от начала цикла, включить нельзя (возвращает false).
     * - Если стандартный цикл (72ч) уже завершился, но 120ч ещё не прошло,
     *   включение разрешено и действует строго до истечения 120ч.
     */
    @Synchronized
    fun setExtendedMode(enabled: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        if (isUserOptedOut()) return false

        val startedAt = getCycleStartedAt()
        if (startedAt <= 0L) return false

        val elapsed = now - startedAt

        if (enabled) {
            if (elapsed >= EXTENDED_CYCLE_DURATION_MS) {
                Log.w(TAG, "Cannot enable extended diagnostics: total duration 120h already expired for current version")
                checkExpiration(now)
                return false
            }

            prefs.edit()
                .putBoolean(KEY_EXTENDED_MODE_ENABLED, true)
                .putBoolean(KEY_AUTO_DIAGNOSTICS_ACTIVE, true)
                .apply()

            VoxSafeLogger.i(
                VoxLogCategory.DIAGNOSTICS,
                VoxLogCode.AUTO_DIAG_STARTED,
                "Включена расширенная диагностика (интервал 12ч, до 120ч от начала версии)",
                mapOf("remainingHours" to ((EXTENDED_CYCLE_DURATION_MS - elapsed) / 3_600_000).toString())
            )
            return true
        } else {
            val autoActive = elapsed < STANDARD_CYCLE_DURATION_MS
            prefs.edit()
                .putBoolean(KEY_EXTENDED_MODE_ENABLED, false)
                .putBoolean(KEY_AUTO_DIAGNOSTICS_ACTIVE, autoActive)
                .apply()

            Log.i(TAG, "Extended diagnostics disabled by user (standard autoActive=$autoActive)")
            return true
        }
    }

    /**
     * Явное переключение автоматической диагностики пользователем.
     * Явный отказ имеет абсолютный приоритет над любыми таймерами.
     */
    @Synchronized
    fun setUserExplicitOptOut(optOut: Boolean, now: Long = System.currentTimeMillis()) {
        if (optOut) {
            prefs.edit()
                .putBoolean(KEY_USER_EXPLICIT_OPT_OUT, true)
                .putBoolean(KEY_AUTO_DIAGNOSTICS_ACTIVE, false)
                .putBoolean(KEY_EXTENDED_MODE_ENABLED, false)
                .apply()

            VoxSafeLogger.i(
                VoxLogCategory.DIAGNOSTICS,
                VoxLogCode.AUTO_DIAG_STOPPED,
                "Автодиагностика полностью отключена пользователем (явный opt-out)"
            )
        } else {
            prefs.edit()
                .putBoolean(KEY_USER_EXPLICIT_OPT_OUT, false)
                .apply()

            // Если время цикла текущей версии ещё не вышло — возобновляем
            val startedAt = getCycleStartedAt()
            if (startedAt > 0L) {
                val elapsed = now - startedAt
                if (elapsed < STANDARD_CYCLE_DURATION_MS) {
                    setAutoDiagnosticsActive(true)
                }
            }
        }
    }

    fun isUserOptedOut(): Boolean = prefs.getBoolean(KEY_USER_EXPLICIT_OPT_OUT, false)

    fun isAutoDiagnosticsActive(): Boolean = prefs.getBoolean(KEY_AUTO_DIAGNOSTICS_ACTIVE, false)

    private fun setAutoDiagnosticsActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DIAGNOSTICS_ACTIVE, active).apply()
    }

    fun isExtendedModeEnabled(): Boolean = prefs.getBoolean(KEY_EXTENDED_MODE_ENABLED, false)

    fun getCycleStartedAt(): Long = prefs.getLong(KEY_CYCLE_STARTED_AT, 0L)

    fun getLastAppVersion(): String? = prefs.getString(KEY_LAST_APP_VERSION, null)

    fun getLastSubmissionTimestamp(): Long = prefs.getLong(KEY_LAST_SUBMISSION_TIMESTAMP, 0L)

    @Synchronized
    fun setLastSubmissionTimestamp(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_SUBMISSION_TIMESTAMP, timestampMs).apply()
    }

    /**
     * Возвращает человекочитаемый статус жизненного цикла для интерфейса настроек.
     */
    fun getStatusDescriptionRu(now: Long = System.currentTimeMillis()): String {
        if (isUserOptedOut()) {
            return "Отключено пользователем"
        }
        val startedAt = getCycleStartedAt()
        if (startedAt <= 0L) {
            return "Не активна"
        }
        val elapsed = now - startedAt
        val isExtended = isExtendedModeEnabled()
        val limit = if (isExtended) EXTENDED_CYCLE_DURATION_MS else STANDARD_CYCLE_DURATION_MS

        if (elapsed >= limit || !isAutoDiagnosticsActive()) {
            return "Завершено (срок истёк)"
        }

        val remainingHours = ((limit - elapsed) / 3_600_000L).coerceAtLeast(0L)
        val modeName = if (isExtended) "Расширенная (12ч)" else "Стандартная (6ч)"
        return "$modeName · осталось ${remainingHours}ч"
    }

    /**
     * Сброс состояния для unit-тестов.
     */
    fun resetForTest() {
        prefs.edit().clear().apply()
    }
}
