package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannelManager
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Фоновый планировщик анонимной диагностики SmartTube VOX.
 *
 * Особенности:
 * - Выполняется только при соблюдении политики интервалов (6ч для Test, 72ч для Stable);
 * - Проверяет явное согласие пользователя перед каждой попыткой;
 * - Выполняет дедупликацию и санитизацию событий перед отправкой;
 * - Агрегирует инциденты в скользящем окне (30-60с) с repeatCount, firstSeen, lastSeen;
 * - Поддерживает ограниченную оффлайн-очередь с защитой от переполнения и телеметрией.
 */
class VoxDiagnosticsScheduler private constructor(private val context: Context) {

    data class AutoBetaIncident(
        val triggerCode: String,
        val normalizedSignature: String,
        val appSessionId: String,
        val playbackSessionId: String?,
        val hudSessionId: String?,
        var repeatCount: Int = 1,
        val firstSeenMs: Long = System.currentTimeMillis(),
        var lastSeenMs: Long = System.currentTimeMillis(),
        var isCritical: Boolean = false
    ) {
        fun toMap(): Map<String, Any> {
            val map = mutableMapOf<String, Any>(
                "triggerCode" to triggerCode,
                "normalizedSignature" to normalizedSignature,
                "appSessionId" to appSessionId,
                "repeatCount" to repeatCount,
                "firstSeenMs" to firstSeenMs,
                "lastSeenMs" to lastSeenMs,
                "isCritical" to isCritical
            )
            if (playbackSessionId != null) map["playbackSessionId"] = playbackSessionId
            if (hudSessionId != null) map["hudSessionId"] = hudSessionId
            return map
        }
    }

    data class QueuedOfflineReport(
        val report: VoxDiagnosticReport,
        val queuedTimestampMs: Long = System.currentTimeMillis(),
        var retryCount: Int = 0
    )

    companion object {
        private const val TAG = "VoxDiagnosticsScheduler"
        private val EXECUTOR = Executors.newSingleThreadExecutor()
        val APP_SESSION_ID: String = UUID.randomUUID().toString().substring(0, 8)

        @Volatile
        private var sInstance: VoxDiagnosticsScheduler? = null

        @JvmStatic
        fun instance(context: Context): VoxDiagnosticsScheduler {
            return sInstance ?: synchronized(this) {
                sInstance ?: VoxDiagnosticsScheduler(context.applicationContext).also { sInstance = it }
            }
        }
    }

    private val isRunning = AtomicBoolean(false)
    private val offlineQueue = ConcurrentLinkedQueue<QueuedOfflineReport>()
    private var pendingIncident: AutoBetaIncident? = null
    private var pendingIncidentTimer: ScheduledFuture<*>? = null
    private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()

    fun getOfflineQueueSize(): Int = offlineQueue.size

    fun getOfflineQueueSummary(): Map<String, Any> {
        val count = offlineQueue.size
        val oldest = offlineQueue.peek()
        val oldestAgeMs = if (oldest != null) System.currentTimeMillis() - oldest.queuedTimestampMs else 0L
        val maxRetries = offlineQueue.maxOfOrNull { it.retryCount } ?: 0
        return mapOf(
            "queuedCount" to count,
            "oldestAgeMs" to oldestAgeMs,
            "maxRetries" to maxRetries
        )
    }

    fun enqueueOfflineReport(report: VoxDiagnosticReport) {
        val now = System.currentTimeMillis()
        while (offlineQueue.size >= VoxDiagnosticsPolicy.MAX_OFFLINE_QUEUE_SIZE) {
            val dropped = offlineQueue.poll()
            val oldestAge = if (dropped != null) now - dropped.queuedTimestampMs else 0L
            val dropCtx = mapOf(
                "dropReason" to "QUEUE_FULL",
                "oldestAgeMs" to oldestAge.toString(),
                "queuedCount" to offlineQueue.size.toString()
            )
            VoxSafeLogger.w(
                VoxLogCategory.DIAGNOSTICS,
                VoxLogCode.AUTO_DIAG_UPLOAD_DROPPED,
                "Offline diagnostic queue full, dropped oldest report",
                dropCtx
            )
        }
        val entry = QueuedOfflineReport(report, now, 0)
        offlineQueue.offer(entry)
        val queueCtx = mapOf(
            "queuedCount" to offlineQueue.size.toString(),
            "trigger" to (report.triggerEvent ?: "UNKNOWN"),
            "oldestAgeMs" to (now - (offlineQueue.peek()?.queuedTimestampMs ?: now)).toString()
        )
        VoxSafeLogger.d(
            VoxLogCategory.DIAGNOSTICS,
            VoxLogCode.AUTO_DIAG_UPLOAD_QUEUED,
            "Diagnostic report enqueued to offline queue",
            queueCtx
        )
    }

    fun interface CompletionCallback {
        fun onComplete(success: Boolean)
    }

    fun pollOfflineReport(): VoxDiagnosticReport? = offlineQueue.poll()?.report

    @JvmOverloads
    fun flushOfflineQueue(onComplete: CompletionCallback? = null) {
        val entry = offlineQueue.poll()
        if (entry == null) {
            onComplete?.onComplete(true)
            return
        }

        entry.retryCount++
        val retryCtx = mapOf(
            "retryCount" to entry.retryCount.toString(),
            "queuedCount" to offlineQueue.size.toString(),
            "trigger" to (entry.report.triggerEvent ?: "UNKNOWN")
        )
        VoxSafeLogger.d(
            VoxLogCategory.DIAGNOSTICS,
            VoxLogCode.AUTO_DIAG_UPLOAD_RETRY,
            "Retrying offline diagnostic report upload",
            retryCtx
        )

        VoxDiagnosticsClient.instance().submitReportAsync(entry.report, object : VoxDiagnosticsClient.Callback {
            override fun onSuccess(reportId: String) {
                val sentCtx = mapOf(
                    "reportId" to reportId,
                    "retryCount" to entry.retryCount.toString()
                )
                VoxSafeLogger.i(
                    VoxLogCategory.DIAGNOSTICS,
                    VoxLogCode.AUTO_DIAG_UPLOAD_SENT,
                    "Offline diagnostic report successfully submitted: $reportId",
                    sentCtx
                )
                VoxUpdateChannelManager.instance(context).setLastDiagnosticsSubmissionTimestamp(System.currentTimeMillis())
                flushOfflineQueue(onComplete)
            }

            override fun onError(errorMessage: String) {
                enqueueOfflineReport(entry.report)
                onComplete?.onComplete(false)
            }
        })
    }

    /**
     * Запускает фоновую периодическую проверку и отправку отчёта.
     */
    @JvmOverloads
    fun schedulePeriodicCheck(onComplete: CompletionCallback? = null) {
        if (isRunning.getAndSet(true)) {
            onComplete?.onComplete(false)
            return
        }

        EXECUTOR.execute {
            try {
                val canSend = VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context)
                if (!canSend) {
                    Log.d(TAG, "Periodic diagnostics skipped: policy criteria not met (consent or interval).")
                    isRunning.set(false)
                    onComplete?.onComplete(false)
                    return@execute
                }

                val hasValuableData = VoxDiagnosticsPolicy.hasValuableDiagnostics(context)
                if (!hasValuableData) {
                    Log.d(TAG, "Periodic diagnostics skipped: no valuable error/warning events to report.")
                    isRunning.set(false)
                    onComplete?.onComplete(false)
                    return@execute
                }

                // Формируем анонимный отчет с дедуплицированными событиями
                val purpose = if (VoxUpdateChannelManager.instance(context).isBetaChannel())
                    VoxDiagnosticReport.PURPOSE_AUTO_BETA else VoxDiagnosticReport.PURPOSE_USER_MANUAL
                val rawReport = VoxDiagnosticReport.create(
                    context = context,
                    reportId = null,
                    includeEvents = true,
                    reportPurpose = purpose,
                    autoSubmitted = true
                )
                val filteredEvents = rawReport.safeRecentEvents
                    .filter { VoxDiagnosticsPolicy.isEventAllowedByPrivacy(it) }
                val deduplicatedEvents = VoxDiagnosticsPolicy.deduplicateEvents(filteredEvents)
                val finalReport = rawReport.copy(safeRecentEvents = deduplicatedEvents)

                VoxDiagnosticsClient.instance().submitReportAsync(finalReport, object : VoxDiagnosticsClient.Callback {
                    override fun onSuccess(reportId: String) {
                        Log.i(TAG, "Periodic diagnostics successfully submitted: $reportId")
                        VoxUpdateChannelManager.instance(context).setLastDiagnosticsSubmissionTimestamp(System.currentTimeMillis())
                        isRunning.set(false)
                        onComplete?.onComplete(true)
                    }

                    override fun onError(errorMessage: String) {
                        Log.w(TAG, "Periodic diagnostics submission failed: $errorMessage, enqueuing offline")
                        enqueueOfflineReport(finalReport)
                        isRunning.set(false)
                        onComplete?.onComplete(false)
                    }
                })

            } catch (e: Throwable) {
                Log.e(TAG, "Error during periodic diagnostics check", e)
                isRunning.set(false)
                onComplete?.onComplete(false)
            }
        }
    }

    /**
     * Запускает обработку инцидента с окном агрегации (30-60с) или немедленно при критической ошибке.
     */
    @JvmOverloads
    fun triggerIncident(
        triggerCode: String,
        playbackSessionId: String? = null,
        hudSessionId: String? = null,
        isImmediate: Boolean = VoxDiagnosticsPolicy.isFatalOrCriticalError(triggerCode),
        onComplete: CompletionCallback? = null
    ) {
        val signature = "$triggerCode|${playbackSessionId ?: "none"}|${hudSessionId ?: "none"}"
        val now = System.currentTimeMillis()

        synchronized(this) {
            val existing = pendingIncident
            if (existing != null && existing.normalizedSignature == signature) {
                existing.repeatCount++
                existing.lastSeenMs = now
                if (isImmediate && !existing.isCritical) {
                    existing.isCritical = true
                }
                val aggCtx = mapOf(
                    "trigger" to triggerCode,
                    "repeatCount" to existing.repeatCount.toString(),
                    "signature" to signature,
                    "firstSeen" to existing.firstSeenMs.toString(),
                    "lastSeen" to existing.lastSeenMs.toString()
                )
                VoxSafeLogger.d(
                    VoxLogCategory.DIAGNOSTICS,
                    VoxLogCode.AUTO_DIAG_INCIDENT_AGGREGATED,
                    "Diagnostic incident aggregated (repeatCount=${existing.repeatCount})",
                    aggCtx
                )

                if (existing.isCritical) {
                    // Critical escalation flushes immediately
                    pendingIncidentTimer?.cancel(false)
                    pendingIncidentTimer = scheduledExecutor.schedule({
                        flushPendingIncident(onComplete)
                    }, 0L, TimeUnit.MILLISECONDS)
                }
                return
            }

            // New incident
            val newIncident = AutoBetaIncident(
                triggerCode = triggerCode,
                normalizedSignature = signature,
                appSessionId = APP_SESSION_ID,
                playbackSessionId = playbackSessionId,
                hudSessionId = hudSessionId,
                repeatCount = 1,
                firstSeenMs = now,
                lastSeenMs = now,
                isCritical = isImmediate
            )
            pendingIncident = newIncident

            val createCtx = mapOf(
                "trigger" to triggerCode,
                "signature" to signature,
                "appSessionId" to APP_SESSION_ID
            )
            VoxSafeLogger.d(
                VoxLogCategory.DIAGNOSTICS,
                VoxLogCode.AUTO_DIAG_INCIDENT_CREATED,
                "Diagnostic incident created for trigger: $triggerCode",
                createCtx
            )

            val delayMs = if (isImmediate) 0L else VoxDiagnosticsPolicy.INCIDENT_AGGREGATION_WINDOW_MS
            pendingIncidentTimer?.cancel(false)
            pendingIncidentTimer = scheduledExecutor.schedule({
                flushPendingIncident(onComplete)
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun flushPendingIncident(onComplete: CompletionCallback? = null) {
        val incident = synchronized(this) {
            val inc = pendingIncident
            pendingIncident = null
            inc
        } ?: run {
            onComplete?.onComplete(false)
            return
        }

        EXECUTOR.execute {
            try {
                val canSend = VoxDiagnosticsPolicy.canSendIncidentDiagnostics(context)
                if (!canSend) {
                    val rateLimitCtx = mapOf(
                        "trigger" to incident.triggerCode,
                        "signature" to incident.normalizedSignature,
                        "repeatCount" to incident.repeatCount.toString()
                    )
                    VoxSafeLogger.w(
                        VoxLogCategory.DIAGNOSTICS,
                        VoxLogCode.AUTO_DIAG_RATE_LIMITED,
                        "Incident upload suppressed by cooldown/rate-limit",
                        rateLimitCtx
                    )
                    onComplete?.onComplete(false)
                    return@execute
                }

                val purpose = if (VoxUpdateChannelManager.instance(context).isBetaChannel())
                    VoxDiagnosticReport.PURPOSE_AUTO_BETA else VoxDiagnosticReport.PURPOSE_USER_MANUAL

                val rawReport = VoxDiagnosticReport.create(
                    context = context,
                    reportId = null,
                    includeEvents = true,
                    reportPurpose = purpose,
                    autoSubmitted = true,
                    triggerEvent = incident.triggerCode,
                    aggregationWindowMs = VoxDiagnosticsPolicy.INCIDENT_AGGREGATION_WINDOW_MS,
                    incidentSummary = incident.toMap()
                )

                val filteredEvents = rawReport.safeRecentEvents
                    .filter { VoxDiagnosticsPolicy.isEventAllowedByPrivacy(it) }
                val deduplicatedEvents = VoxDiagnosticsPolicy.deduplicateEvents(filteredEvents)
                val finalReport = rawReport.copy(safeRecentEvents = deduplicatedEvents)

                VoxDiagnosticsClient.instance().submitReportAsync(finalReport, object : VoxDiagnosticsClient.Callback {
                    override fun onSuccess(reportId: String) {
                        val sentCtx = mapOf(
                            "reportId" to reportId,
                            "trigger" to incident.triggerCode,
                            "repeatCount" to incident.repeatCount.toString()
                        )
                        VoxSafeLogger.i(
                            VoxLogCategory.DIAGNOSTICS,
                            VoxLogCode.AUTO_DIAG_UPLOAD_SENT,
                            "Incident diagnostic report submitted: $reportId",
                            sentCtx
                        )
                        VoxUpdateChannelManager.instance(context).setLastDiagnosticsSubmissionTimestamp(System.currentTimeMillis())
                        onComplete?.onComplete(true)
                    }

                    override fun onError(errorMessage: String) {
                        enqueueOfflineReport(finalReport)
                        onComplete?.onComplete(false)
                    }
                })
            } catch (e: Throwable) {
                Log.e(TAG, "Error submitting incident report", e)
                onComplete?.onComplete(false)
            }
        }
    }
}
