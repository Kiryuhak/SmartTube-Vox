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
     * Определяет каноническую категорию по коду события.
     */
    @JvmStatic
    fun inferCategoryFromCode(code: String?): String? {
        if (code.isNullOrBlank()) return null
        val c = code.uppercase(java.util.Locale.ROOT)
        return when {
            c.startsWith("OTA_") -> VoxLogCategory.OTA.name
            c.startsWith("HUD_") || c.startsWith("SIDEBAR_") || c.startsWith("CARD_") -> VoxLogCategory.UI.name
            c.startsWith("PLAYER_") || c.startsWith("MEDIA3_") || c.startsWith("DECODER_") || c.startsWith("BUFFER_") || c.startsWith("LIVE_") || c.startsWith("LOCAL_") -> VoxLogCategory.PLAYER.name
            c.startsWith("DOWNLOAD_") || c.startsWith("MUX_") -> VoxLogCategory.DOWNLOAD.name
            c.startsWith("TRANSLATION_") || c.startsWith("LIVE_TRANSLATION_") -> VoxLogCategory.TRANSLATION.name
            c.startsWith("NETWORK_") || c.contains("HTTP_") || c.contains("DNS_") -> VoxLogCategory.NETWORK.name
            c.startsWith("BACKGROUND_") -> "BACKGROUND"
            c.startsWith("OFFLINE_") -> "OFFLINE"
            c.startsWith("CHANNEL_GROUP_") -> VoxLogCategory.CHANNEL_GROUP.name
            c.startsWith("AUTO_TUNING_") || c.startsWith("AUTO_SETUP_") -> VoxLogCategory.COMPATIBILITY.name
            else -> VoxLogCategory.DIAGNOSTICS.name
        }
    }

    /**
     * Нормализует название платформы для машинной сигнатуры (строго ASCII, без локализованных строк).
     */
    @JvmStatic
    fun normalizePlatformForSignature(platform: String?, sdkInt: Int): String {
        val raw = platform?.trim() ?: ""
        val normalizedPlat = if (raw.isEmpty() ||
            raw.equals("unknown", ignoreCase = true) ||
            raw.contains("неизвестн", ignoreCase = true) ||
            raw.any { it.code > 127 }
        ) {
            "UNKNOWN_PLATFORM"
        } else {
            raw.uppercase(java.util.Locale.ROOT)
                .replace(Regex("[^A-Z0-9]+"), "_")
                .trim('_')
                .ifEmpty { "UNKNOWN_PLATFORM" }
        }
        return if (sdkInt > 0) "${normalizedPlat}_API$sdkInt" else normalizedPlat
    }

    /**
     * Строит каноническую машинную сигнатуру ошибки (SINGLE SOURCE OF TRUTH на клиенте).
     */
    @JvmStatic
    fun buildCanonicalErrorSignature(
        errorCategory: String?,
        primaryCode: String?,
        platform: String?,
        sdkInt: Int,
        reportPurpose: String?
    ): String {
        val platformTag = normalizePlatformForSignature(platform, sdkInt)
        if (!errorCategory.isNullOrBlank() && !primaryCode.isNullOrBlank()) {
            return "${errorCategory.uppercase(java.util.Locale.ROOT)}|${primaryCode.uppercase(java.util.Locale.ROOT)}|$platformTag"
        }
        if (!errorCategory.isNullOrBlank()) {
            return "${errorCategory.uppercase(java.util.Locale.ROOT)}|UNKNOWN_CODE|$platformTag"
        }
        if (reportPurpose == VoxDiagnosticReport.PURPOSE_AUTO_BETA) {
            return "SYSTEM|AUTO_BETA_ROUTINE|$platformTag"
        }
        if (reportPurpose == VoxDiagnosticReport.PURPOSE_USER_MANUAL) {
            return "GENERAL|USER_REPORT|$platformTag"
        }
        return ""
    }

    private fun toChronologicalOrder(events: List<VoxLogEvent>): List<VoxLogEvent> {
        if (events.size < 2) return events
        return if (events.first().timestamp > events.last().timestamp) {
            events.asReversed()
        } else {
            events
        }
    }

    @JvmStatic
    fun isSyntheticFixtureEvent(event: VoxLogEvent): Boolean {
        if (event.code != VoxLogCode.DOWNLOAD_STATE_INCONSISTENT) return false
        val msg = event.message.lowercase(java.util.Locale.ROOT)
        val dlId = event.context?.get("downloadId")?.lowercase(java.util.Locale.ROOT) ?: ""
        return msg.contains("p37-") || msg.contains("test-fixture-") || msg.contains("test-dl-") ||
            dlId.startsWith("p37-") || dlId.startsWith("test-fixture-") || dlId.startsWith("test-dl-")
    }

    /**
     * Проверяет, было ли событие ошибки устранено последующим успешным событием.
     * Корректно работает как со списком newest-first (из VoxLogStore), так и со списком oldest-first.
     */
    @JvmStatic
    fun isEventRecovered(event: VoxLogEvent, allEvents: List<VoxLogEvent>): Boolean {
        val chronoEvents = toChronologicalOrder(allEvents)
        val eventIndex = chronoEvents.indexOf(event)
        val laterEvents = if (eventIndex >= 0) {
            chronoEvents.subList(eventIndex + 1, chronoEvents.size)
                .filter { it.timestamp >= event.timestamp }
        } else {
            chronoEvents.filter { it.timestamp > event.timestamp }
        }
        if (laterEvents.isEmpty()) return false

        // OTA recovery
        if (event.category == VoxLogCategory.OTA || event.code.startsWith("OTA_")) {
            val otaErrors = listOf(
                VoxLogCode.OTA_RATE_LIMIT, VoxLogCode.OTA_FAILED,
                VoxLogCode.OTA_HTTP_ERROR, VoxLogCode.OTA_TIMEOUT, VoxLogCode.OTA_DNS_ERROR,
                VoxLogCode.OTA_PRE_VALIDATION_FAILED
            )
            if (event.code in otaErrors) {
                val otaSuccesses = listOf(
                    VoxLogCode.OTA_NO_UPDATE, VoxLogCode.OTA_CHECK_COMPLETED,
                    VoxLogCode.OTA_RELEASE_PARSED, VoxLogCode.OTA_UPDATE_AVAILABLE,
                    VoxLogCode.OTA_RATE_LIMIT_RECOVERED, VoxLogCode.OTA_VERSION_COMPARED,
                    VoxLogCode.OTA_PRE_VALIDATION_PASSED
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

        // Download recovery
        if (event.category == VoxLogCategory.DOWNLOAD || event.code.startsWith("DOWNLOAD_")) {
            if (event.code == VoxLogCode.DOWNLOAD_STATE_INCONSISTENT) {
                if (laterEvents.any { it.code == VoxLogCode.DOWNLOAD_STATE_REPAIRED || it.code == VoxLogCode.DOWNLOAD_COMPLETED }) return true
            }
        }

        // Player recovery
        if (event.category == VoxLogCategory.PLAYER || event.code.startsWith("PLAYER_")) {
            val playerErrors = listOf(
                VoxLogCode.PLAYER_HTTP_ERROR, VoxLogCode.PLAYER_REBUFFER, VoxLogCode.PLAYER_SOURCE_ERROR,
                VoxLogCode.PLAYER_RENDERER_ERROR, VoxLogCode.PLAYER_DECODER_ERROR,
                VoxLogCode.PLAYER_RECOVERABLE_403
            )
            if (event.code in playerErrors) {
                val playerSuccesses = listOf(
                    "PLAYER_PLAYBACK_PROGRESS", "PLAYER_STATE_READY",
                    "PLAYER_RECOVERED", "PLAYER_SOURCE_RECOVERED",
                    VoxLogCode.OFFLINE_PLAYBACK_OPENED
                )
                if (laterEvents.any { it.code in playerSuccesses }) return true
            }
        }

        return false
    }

    /**
     * Находит наиболее значимое неустранённое событие среди журнала (предпочитая самое свежее).
     */
    @JvmStatic
    fun findProminentUnrecoveredEvent(events: List<VoxLogEvent>): VoxLogEvent? {
        val chronoEvents = toChronologicalOrder(events)
        val candidates = chronoEvents.filter {
            (it.level == VoxLogLevel.ERROR || it.level == VoxLogLevel.WARNING) &&
                !isSyntheticFixtureEvent(it) &&
                !isEventRecovered(it, chronoEvents)
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
            val isSameInconsistentDownload = last != null &&
                last.code == VoxLogCode.DOWNLOAD_STATE_INCONSISTENT &&
                event.code == VoxLogCode.DOWNLOAD_STATE_INCONSISTENT
            val isSameOtaRateLimit = last != null &&
                last.code == VoxLogCode.OTA_RATE_LIMIT &&
                event.code == VoxLogCode.OTA_RATE_LIMIT
            if (last != null &&
                last.category == event.category &&
                last.code == event.code &&
                (last.message == event.message || isSameInconsistentDownload || isSameOtaRateLimit)
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
        // Exclude synthetic test fixture events from production reports
        if (isSyntheticFixtureEvent(event)) {
            return false
        }

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
