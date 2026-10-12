package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannel
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannelManager

/**
 * Политика автоматической анонимной диагностики SmartTube VOX.
 *
 * Правила:
 * 1. Тестовый канал (Test/Beta): отправка не чаще 1 раза в 6 часов при наличии явного согласия.
 * 2. Стабильный канал (Stable): отправка не чаще 1 раза в 72 часа при наличии отдельного согласия.
 * 3. Дедупликация: повторяющиеся ошибки объединяются через repeatCount.
 * 4. Пакетная отправка: не отправляет HTTP POST на каждое отдельное событие.
 * 5. Конфиденциальность: все данные проверяются через VoxDiagnosticSanitizer.
 */
object VoxDiagnosticsPolicy {

    const val BETA_DIAGNOSTICS_INTERVAL_MS = 6 * 60 * 60 * 1000L      // 6 часов
    const val STABLE_DIAGNOSTICS_INTERVAL_MS = 72 * 60 * 60 * 1000L   // 72 часа
    const val INCIDENT_AGGREGATION_WINDOW_MS = 45 * 1000L              // Окно агрегации инцидентов (30-60с)
    const val INCIDENT_MIN_INTERVAL_MS = 5 * 60 * 1000L                // Минимальный интервал между авто-отчетами об инцидентах
    const val MAX_OFFLINE_QUEUE_SIZE = 10                              // Ограниченная очередь оффлайн отчетов
    const val MAX_OFFLINE_QUEUE_AGE_MS = 48 * 60 * 60 * 1000L          // 48 часов максимальное время жизни в очереди
    const val MAX_OFFLINE_RETRY_COUNT = 5                              // Максимум 5 попыток перед удалением
    const val OFFLINE_INITIAL_BACKOFF_MS = 15 * 1000L                  // 15 секунд начальная задержка
    const val OFFLINE_MAX_BACKOFF_MS = 15 * 60 * 1000L                 // 15 минут максимальная задержка
    const val MAX_BATCH_EVENTS = 50

    /**
     * Проверяет, разрешена ли отправка автоматической периодической диагностики.
     */
    @JvmStatic
    @JvmOverloads
    fun canSendPeriodicDiagnostics(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val manager = VoxUpdateChannelManager.instance(context)
        val channel = manager.getUpdateChannel()
        val lastSubmission = manager.getLastDiagnosticsSubmissionTimestamp()

        return if (manager.isBetaChannel()) {
            if (!manager.isBetaDiagnosticsEnabled()) return false
            lastSubmission == 0L || (now - lastSubmission) >= BETA_DIAGNOSTICS_INTERVAL_MS
        } else {
            if (!manager.isStableDiagnosticsEnabled()) return false
            lastSubmission == 0L || (now - lastSubmission) >= STABLE_DIAGNOSTICS_INTERVAL_MS
        }
    }

    /**
     * Проверяет, разрешена ли отправка отчёта об инциденте в текущий момент времени.
     */
    @JvmStatic
    @JvmOverloads
    fun canSendIncidentDiagnostics(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val manager = VoxUpdateChannelManager.instance(context)
        val enabled = if (manager.isBetaChannel()) manager.isBetaDiagnosticsEnabled() else manager.isStableDiagnosticsEnabled()
        if (!enabled) return false

        val lastSubmission = manager.getLastDiagnosticsSubmissionTimestamp()
        return lastSubmission == 0L || (now - lastSubmission) >= INCIDENT_MIN_INTERVAL_MS
    }

    /**
     * Проверяет, является ли код ошибки критическим / фатальным.
     */
    @JvmStatic
    fun isFatalOrCriticalError(code: String): Boolean {
        return code == VoxLogCode.PLAYER_INIT_FAILED ||
               code == VoxLogCode.PLAYER_RENDERER_ERROR ||
               code == VoxLogCode.LOCAL_FILE_OPEN_FAILED ||
               code == VoxLogCode.DOWNLOAD_FAILED
    }

    /**
     * Проверяет, было ли событие ошибки устранено последующим успешным событием.
     */
    @JvmStatic
    fun isEventRecovered(event: VoxLogEvent, allEvents: List<VoxLogEvent>): Boolean {
        val eventIndex = allEvents.indexOf(event)
        if (eventIndex < 0) return false
        val laterEvents = allEvents.subList(eventIndex + 1, allEvents.size)

        // OTA recovery
        if (event.category == VoxLogCategory.OTA || event.code.startsWith("OTA_")) {
            val otaErrors = listOf(
                VoxLogCode.OTA_RATE_LIMIT, VoxLogCode.OTA_FAILED,
                VoxLogCode.OTA_HTTP_ERROR, VoxLogCode.OTA_TIMEOUT, VoxLogCode.OTA_DNS_ERROR
            )
            if (event.code in otaErrors) {
                val otaSuccesses = listOf(
                    VoxLogCode.OTA_NO_UPDATE, VoxLogCode.OTA_RELEASE_PARSED,
                    VoxLogCode.OTA_UPDATE_AVAILABLE, VoxLogCode.OTA_RATE_LIMIT_RECOVERED,
                    VoxLogCode.OTA_VERSION_COMPARED
                )
                if (laterEvents.any { it.code in otaSuccesses }) return true
            }
        }

        // HUD recovery
        if (event.category == VoxLogCategory.UI || event.code.startsWith("HUD_")) {
            if (event.code == VoxLogCode.HUD_STATE_INCONSISTENT || event.code == VoxLogCode.HUD_CONTROLS_DETACHED) {
                if (laterEvents.any { it.code == VoxLogCode.HUD_SELF_HEAL_SUCCESS || it.code == VoxLogCode.HUD_CONTROLS_ATTACHED }) return true
            }
        }

        // Image recovery
        if (event.code == VoxLogCode.IMAGE_REQUEST_FAILED) {
            if (laterEvents.any { it.code == VoxLogCode.IMAGE_REQUEST_RECOVERED }) return true
        }

        return false
    }

    /**
     * Находит наиболее значимое неустранённое событие среди журнала.
     */
    @JvmStatic
    fun findProminentUnrecoveredEvent(events: List<VoxLogEvent>): VoxLogEvent? {
        val candidates = events.filter {
            (it.level == VoxLogLevel.ERROR || it.level == VoxLogLevel.WARNING) && !isEventRecovered(it, events)
        }
        return candidates.lastOrNull { it.level == VoxLogLevel.ERROR }
            ?: candidates.lastOrNull { it.level == VoxLogLevel.WARNING }
    }

    /**
     * Проверяет наличие полезных диагностических данных для формирования отчёта.
     * Игнорирует ошибки, которые уже были устранены последующими успешными действиями.
     */
    @JvmStatic
    fun hasValuableDiagnostics(context: Context): Boolean {
        val count = VoxLogStore.instance(context).getJournalEventCount()
        if (count <= 0) return false

        val events = VoxLogStore.instance(context).getEvents(MAX_BATCH_EVENTS, VoxLogLevel.INFO)
        return hasValuableDiagnostics(events)
    }

    @JvmStatic
    fun hasValuableDiagnostics(events: List<VoxLogEvent>): Boolean {
        return findProminentUnrecoveredEvent(events) != null
    }

    /**
     * Дедупликация списка событий: последовательные одинаковые события (код + категория + сообщение)
     * объединяются с суммированием repeatCount.
     */
    @JvmStatic
    fun deduplicateEvents(events: List<VoxLogEvent>): List<VoxLogEvent> {
        if (events.isEmpty()) return emptyList()

        val deduplicated = mutableListOf<VoxLogEvent>()
        for (event in events) {
            val last = deduplicated.lastOrNull()
            if (last != null &&
                last.category == event.category &&
                last.code == event.code &&
                last.message == event.message
            ) {
                // Объединяем счетчики повторов
                deduplicated[deduplicated.size - 1] = last.copy(
                    repeatCount = last.repeatCount + event.repeatCount,
                    timestamp = maxOf(last.timestamp, event.timestamp)
                )
            } else {
                deduplicated.add(event)
            }
        }

        return deduplicated.take(MAX_BATCH_EVENTS)
    }

    /**
     * Проверяет соответствие события белому списку разрешённых категорий и полей (Privacy Allowlist).
     */
    @JvmStatic
    fun isEventAllowedByPrivacy(event: VoxLogEvent): Boolean {
        // Проверка сообщения на наличие сырых несанитизированных секретов
        if (VoxDiagnosticSanitizer.sanitize(event.message) != event.message) {
            return false
        }

        // Проверка контекста
        event.context?.let { ctx ->
            for ((key, value) in ctx) {
                if (VoxDiagnosticSanitizer.isForbiddenKey(key)) return false
                if (VoxDiagnosticSanitizer.sanitize(value) != value) return false
            }
        }

        return true
    }
}
