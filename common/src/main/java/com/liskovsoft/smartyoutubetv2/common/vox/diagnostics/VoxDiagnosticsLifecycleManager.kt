package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import android.content.SharedPreferences
import com.liskovsoft.sharedutils.mylogger.Log

/**
 * Менеджер жизненного цикла автоматической анонимной диагностики SmartTube VOX (Prompt #114).
 *
 * Политика (один последовательный цикл 8 суток / 192 часа):
 * - Фаза 1 (Активный мониторинг):
 *   - От T0 (первый запуск после установки или обновления versionCode) до T0 + 72ч (первые 3 суток).
 *   - Интервал периодической отправки: каждые 6 часов.
 *
 * - Фаза 2 (Контроль стабильности):
 *   - От T0 + 72ч до T0 + 192ч (последующие 5 суток, суммарно 8 суток).
 *   - Интервал периодической отправки: каждые 12 часов.
 *   - Переход между Фазой 1 и Фазой 2 происходит автоматически и последовательно.
 *
 * - После T0 + 192ч:
 *   - 8 суток завершены: периодическая диагностика полностью выключена до следующего реального обновления.
 *
 * - Важные гарантии:
 *   - T0 строго фиксируется при первом запуске нового versionCode и сохраняется при перезапусках, смене часовых поясов и крэшах.
 *   - Явный отказ пользователя (opt-out) имеет абсолютный приоритет и немедленно блокирует периодическую диагностику и heartbeat.
 *   - Критические ошибки (FATAL, ANR, PLAYER_RECOVERY_FAILED) обрабатываются через отдельную срочную очередь немедленной отправки.
 *   - Heartbeat активен раз в 30 минут только при активном сеансе пользователя.
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

        // Канонические интервалы и фазы последовательного 8-дневного цикла
        const val PHASE1_INTERVAL_MS = 6 * 60 * 60 * 1000L           // 6 часов (Фаза 1)
        const val PHASE2_INTERVAL_MS = 12 * 60 * 60 * 1000L          // 12 часов (Фаза 2)
        const val PHASE1_DURATION_MS = 72 * 60 * 60 * 1000L          // 72 часа (0..72ч)
        const val TOTAL_CYCLE_DURATION_MS = 192 * 60 * 60 * 1000L    // 8 суток / 192 часа (0..192ч)

        // Обратная совместимость для существующих вызовов
        const val STANDARD_INTERVAL_MS = PHASE1_INTERVAL_MS
        const val EXTENDED_INTERVAL_MS = PHASE2_INTERVAL_MS
        const val STANDARD_CYCLE_DURATION_MS = PHASE1_DURATION_MS
        const val EXTENDED_CYCLE_DURATION_MS = TOTAL_CYCLE_DURATION_MS

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
     * Запускает новый цикл 8 суток ТОЛЬКО при:
     * 1. Первой установке (lastAppVersion == null).
     * 2. Реальном обновлении версии приложения (lastAppVersion != currentVersion).
     *
     * НЕ сбрасывает T0 при:
     * - обычном перезапуске;
     * - перезагрузке ОС;
     * - смене часового пояса;
     * - очистке кэша;
     * - падении приложения.
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
            Log.i(TAG, "First app installation detected ($currentVersion), starting 8-day sequential cycle (72h @ 6h, then 120h @ 12h)")
            startNewCycle(currentVersion, now)
        } else if (lastVersion != currentVersion) {
            // Реальное обновление версии приложения
            Log.i(TAG, "App updated from $lastVersion to $currentVersion, starting fresh 8-day sequential cycle")
            startNewCycle(currentVersion, now)
        } else {
            // Повторный запуск той же версии — T0 сохраняется строго
            checkExpiration(now)
        }
    }

    private fun startNewCycle(version: String, now: Long) {
        prefs.edit()
            .putLong(KEY_CYCLE_STARTED_AT, now)
            .putString(KEY_LAST_APP_VERSION, version)
            .putBoolean(KEY_AUTO_DIAGNOSTICS_ACTIVE, true)
            .putBoolean(KEY_EXTENDED_MODE_ENABLED, false)
            .putLong(KEY_LAST_SUBMISSION_TIMESTAMP, 0L)
            .apply()

        VoxSafeLogger.i(
            VoxLogCategory.DIAGNOSTICS,
            VoxLogCode.AUTO_DIAG_STARTED,
            "Запущен новый 8-дневный цикл автоматической диагностики (Фаза 1: 0-72ч каждые 6ч; Фаза 2: 72-192ч каждые 12ч)",
            mapOf("version" to version, "cycleStartedAt" to now.toString())
        )
    }

    /**
     * Проверяет истечение срока действия автоматической диагностики (192 часа / 8 суток).
     * Если 192 часа истекли — автоматически выключает периодическую автодиагностику.
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

        if (elapsed >= TOTAL_CYCLE_DURATION_MS) {
            Log.i(TAG, "Automatic diagnostics cycle expired (elapsed=${elapsed / 3_600_000}h >= 192h / 8 days), disabling periodic diagnostics")
            prefs.edit()
                .putBoolean(KEY_AUTO_DIAGNOSTICS_ACTIVE, false)
                .putBoolean(KEY_EXTENDED_MODE_ENABLED, false)
                .apply()

            VoxSafeLogger.i(
                VoxLogCategory.DIAGNOSTICS,
                VoxLogCode.AUTO_DIAG_STOPPED,
                "Цикл автодиагностики завершён (8 суток истекли)",
                mapOf("elapsedHours" to (elapsed / 3_600_000).toString())
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
     * Проверяет, можно ли отправить очередной периодический отчёт с учётом текущей фазы:
     * - Фаза 1 (0..72ч): интервал 6 часов;
     * - Фаза 2 (72..192ч): интервал 12 часов.
     */
    @Synchronized
    fun canSendPeriodic(now: Long = System.currentTimeMillis()): Boolean {
        if (!isAutoDiagnosticsPermitted(now)) return false

        val lastSub = getLastSubmissionTimestamp()
        val interval = getCurrentIntervalMs(now)

        return lastSub == 0L || (now - lastSub) >= interval
    }

    /**
     * Возвращает текущий желаемый интервал отправки в зависимости от возраста цикла:
     * - менее 72 часов: 6 часов;
     * - от 72 до 192 часов: 12 часов.
     */
    fun getCurrentIntervalMs(now: Long = System.currentTimeMillis()): Long {
        val startedAt = getCycleStartedAt()
        if (startedAt <= 0L) return PHASE1_INTERVAL_MS
        val elapsed = now - startedAt
        return if (elapsed < PHASE1_DURATION_MS) PHASE1_INTERVAL_MS else PHASE2_INTERVAL_MS
    }

    /**
     * Возвращает true, если цикл находится в Фазе 2 (контроль стабильности, 72..192ч).
     */
    @JvmOverloads
    fun isExtendedModeEnabled(now: Long = System.currentTimeMillis()): Boolean {
        val startedAt = getCycleStartedAt()
        if (startedAt <= 0L) return false
        val elapsed = now - startedAt
        return elapsed in PHASE1_DURATION_MS until TOTAL_CYCLE_DURATION_MS && isAutoDiagnosticsActive()
    }

    /**
     * Совместимость с переключателем в UI настроек (сохраняет T0).
     */
    @Synchronized
    fun setExtendedMode(enabled: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        if (isUserOptedOut()) return false
        val startedAt = getCycleStartedAt()
        if (startedAt <= 0L) return false

        val elapsed = now - startedAt
        if (elapsed >= TOTAL_CYCLE_DURATION_MS) {
            checkExpiration(now)
            return false
        }

        prefs.edit()
            .putBoolean(KEY_EXTENDED_MODE_ENABLED, enabled)
            .putBoolean(KEY_AUTO_DIAGNOSTICS_ACTIVE, true)
            .apply()
        return true
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

            // Если время 8-дневного цикла текущей версии ещё не вышло — возобновляем
            val startedAt = getCycleStartedAt()
            if (startedAt > 0L) {
                val elapsed = now - startedAt
                if (elapsed < TOTAL_CYCLE_DURATION_MS) {
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

        if (elapsed >= TOTAL_CYCLE_DURATION_MS || !isAutoDiagnosticsActive()) {
            return "Завершено (8 суток истекли)"
        }

        val remainingTotalHours = ((TOTAL_CYCLE_DURATION_MS - elapsed) / 3_600_000L).coerceAtLeast(0L)
        return if (elapsed < PHASE1_DURATION_MS) {
            val phase1Remaining = ((PHASE1_DURATION_MS - elapsed) / 3_600_000L).coerceAtLeast(0L)
            "Активный мониторинг (интервал 6ч, фаза 1: ещё ${phase1Remaining}ч, всего: ${remainingTotalHours}ч)"
        } else {
            "Контроль стабильности (интервал 12ч, фаза 2, осталось ${remainingTotalHours}ч)"
        }
    }

    /**
     * Сброс состояния для unit-тестов.
     */
    fun resetForTest() {
        prefs.edit().clear().apply()
    }
}
