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
    const val MAX_BATCH_EVENTS = 50

    /**
     * Проверяет, разрешена ли отправка автоматической диагностики в текущий момент времени.
     */
    @JvmStatic
    @JvmOverloads
    fun canSendPeriodicDiagnostics(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val manager = VoxUpdateChannelManager.instance(context)
        val channel = manager.getUpdateChannel()
        val lastSubmission = manager.getLastDiagnosticsSubmissionTimestamp()

        return when (channel) {
            VoxUpdateChannel.TEST -> {
                if (!manager.isBetaDiagnosticsEnabled()) return false
                lastSubmission == 0L || (now - lastSubmission) >= BETA_DIAGNOSTICS_INTERVAL_MS
            }
            VoxUpdateChannel.STABLE -> {
                if (!manager.isStableDiagnosticsEnabled()) return false
                lastSubmission == 0L || (now - lastSubmission) >= STABLE_DIAGNOSTICS_INTERVAL_MS
            }
        }
    }

    /**
     * Проверяет наличие полезных диагностических данных для формирования отчёта.
     */
    @JvmStatic
    fun hasValuableDiagnostics(context: Context): Boolean {
        val count = VoxLogStore.instance(context).getJournalEventCount()
        if (count <= 0) return false

        // Проверяем наличие хотя бы одного события уровня WARNING или ERROR
        val events = VoxLogStore.instance(context).getEvents(MAX_BATCH_EVENTS, VoxLogLevel.INFO)
        return events.any { it.level == VoxLogLevel.WARNING || it.level == VoxLogLevel.ERROR }
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
