package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannelManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
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
        var isCritical: Boolean = false,
        var isSynthetic: Boolean = false
    ) {
        fun toMap(): Map<String, Any> {
            val map = mutableMapOf<String, Any>(
                "triggerCode" to triggerCode,
                "normalizedSignature" to normalizedSignature,
                "appSessionId" to appSessionId,
                "repeatCount" to repeatCount,
                "firstSeenMs" to firstSeenMs,
                "lastSeenMs" to lastSeenMs,
                "isCritical" to isCritical,
                "isSynthetic" to isSynthetic
            )
            if (playbackSessionId != null) map["playbackSessionId"] = playbackSessionId
            if (hudSessionId != null) map["hudSessionId"] = hudSessionId
            return map
        }
    }

    data class QueuedOfflineReport(
        val report: VoxDiagnosticReport,
        val queuedTimestampMs: Long = System.currentTimeMillis(),
        var retryCount: Int = 0,
        var nextRetryTimestampMs: Long = 0L
    ) {
        fun toJson(): JSONObject {
            val obj = JSONObject()
            obj.put("report", JSONObject(report.toJson()))
            obj.put("queuedTimestampMs", queuedTimestampMs)
            obj.put("retryCount", retryCount)
            obj.put("nextRetryTimestampMs", nextRetryTimestampMs)
            return obj
        }

        companion object {
            fun fromJson(obj: JSONObject): QueuedOfflineReport? {
                return try {
                    val rObj = obj.optJSONObject("report") ?: return null
                    val report = VoxDiagnosticReport.fromJson(rObj) ?: return null
                    val ts = obj.optLong("queuedTimestampMs", System.currentTimeMillis())
                    val retries = obj.optInt("retryCount", 0)
                    val nextRetry = obj.optLong("nextRetryTimestampMs", 0L)
                    QueuedOfflineReport(report, ts, retries, nextRetry)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    companion object {
        private const val TAG = "VoxDiagnosticsScheduler"
        private const val QUEUE_FILE_NAME = "vox_offline_diagnostics.json"
        private const val QUEUE_TMP_FILE_NAME = "vox_offline_diagnostics.json.tmp"
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
    private val queueLock = Any()
    private val offlineQueue = ArrayList<QueuedOfflineReport>()
    private var pendingIncident: AutoBetaIncident? = null
    private var pendingIncidentTimer: ScheduledFuture<*>? = null
    private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()

    init {
        synchronized(queueLock) {
            loadFromDiskLocked()
        }
    }

    fun getOfflineQueueSize(): Int = synchronized(queueLock) { offlineQueue.size }

    fun getOfflineQueueSummary(): Map<String, Any> = synchronized(queueLock) {
        val count = offlineQueue.size
        val oldest = offlineQueue.firstOrNull()
        val oldestAgeMs = if (oldest != null) System.currentTimeMillis() - oldest.queuedTimestampMs else 0L
        val maxRetries = offlineQueue.maxOfOrNull { it.retryCount } ?: 0
        mapOf(
            "queuedCount" to count,
            "oldestAgeMs" to oldestAgeMs,
            "maxRetries" to maxRetries
        )
    }

    val diskWriteCountForTest = java.util.concurrent.atomic.AtomicInteger(0)

    fun clearOfflineQueue() {
        synchronized(queueLock) {
            if (offlineQueue.isNotEmpty() || File(context.filesDir, QUEUE_FILE_NAME).exists()) {
                offlineQueue.clear()
                saveToDiskLocked()
            }
        }
    }

    private fun loadFromDiskLocked() {
        offlineQueue.clear()
        val file = File(context.filesDir, QUEUE_FILE_NAME)
        if (!file.exists()) return

        try {
            val content = file.readText(StandardCharsets.UTF_8)
            if (content.isBlank()) return
            val jsonArray = JSONArray(content)
            val now = System.currentTimeMillis()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val entry = QueuedOfflineReport.fromJson(obj)
                if (entry != null) {
                    val age = now - entry.queuedTimestampMs
                    if (age <= VoxDiagnosticsPolicy.MAX_OFFLINE_QUEUE_AGE_MS &&
                        entry.retryCount < VoxDiagnosticsPolicy.MAX_OFFLINE_RETRY_COUNT) {
                        offlineQueue.add(entry)
                    }
                }
            }
            while (offlineQueue.size > VoxDiagnosticsPolicy.MAX_OFFLINE_QUEUE_SIZE) {
                offlineQueue.removeAt(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Corrupted offline diagnostics queue file recovered, resetting: ${e.message}")
            offlineQueue.clear()
            try { file.delete() } catch (ignored: Exception) {}
        }
    }

    private fun saveToDiskLocked() {
        try {
            diskWriteCountForTest.incrementAndGet()
            val file = File(context.filesDir, QUEUE_FILE_NAME)
            if (offlineQueue.isEmpty()) {
                if (file.exists()) file.delete()
                return
            }
            val jsonArray = JSONArray()
            for (item in offlineQueue) {
                jsonArray.put(item.toJson())
            }
            val tmp = File(context.filesDir, QUEUE_TMP_FILE_NAME)
            tmp.writeText(jsonArray.toString(2), StandardCharsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist offline diagnostics queue to disk", e)
        }
    }

    fun enqueueOfflineReport(report: VoxDiagnosticReport) {
        val now = System.currentTimeMillis()
        synchronized(queueLock) {
            // Deduplication: check if already exists by reportId or incident signature
            val reportSig = report.incidentSummary?.get("normalizedSignature")?.toString()
            val existingIndex = offlineQueue.indexOfFirst {
                it.report.reportId == report.reportId ||
                (reportSig != null && it.report.incidentSummary?.get("normalizedSignature")?.toString() == reportSig)
            }

            if (existingIndex >= 0) {
                val existing = offlineQueue[existingIndex]
                val mergedReport = if (report.incidentSummary != null && existing.report.incidentSummary != null) {
                    val exRepeats = (existing.report.incidentSummary["repeatCount"] as? Number)?.toInt() ?: 1
                    val newRepeats = (report.incidentSummary["repeatCount"] as? Number)?.toInt() ?: 1
                    val updatedSummary = report.incidentSummary.toMutableMap()
                    updatedSummary["repeatCount"] = Math.max(exRepeats, newRepeats)
                    report.copy(incidentSummary = updatedSummary)
                } else {
                    report
                }
                offlineQueue[existingIndex] = existing.copy(report = mergedReport, nextRetryTimestampMs = now)
                saveToDiskLocked()
                VoxSafeLogger.d(
                    VoxLogCategory.DIAGNOSTICS,
                    VoxLogCode.AUTO_DIAG_UPLOAD_QUEUED,
                    "Offline diagnostic report deduplicated & updated in queue",
                    mapOf("reportId" to (report.reportId ?: "UNKNOWN"), "queuedCount" to offlineQueue.size.toString())
                )
                return
            }

            offlineQueue.removeAll {
                val age = now - it.queuedTimestampMs
                age > VoxDiagnosticsPolicy.MAX_OFFLINE_QUEUE_AGE_MS || it.retryCount >= VoxDiagnosticsPolicy.MAX_OFFLINE_RETRY_COUNT
            }

            while (offlineQueue.size >= VoxDiagnosticsPolicy.MAX_OFFLINE_QUEUE_SIZE) {
                val dropped = offlineQueue.removeAt(0)
                val oldestAge = now - dropped.queuedTimestampMs
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

            val entry = QueuedOfflineReport(report, now, 0, now)
            offlineQueue.add(entry)
            saveToDiskLocked()

            val queueCtx = mapOf(
                "queuedCount" to offlineQueue.size.toString(),
                "trigger" to (report.triggerEvent ?: "UNKNOWN"),
                "reportId" to (report.reportId ?: "UNKNOWN")
            )
            VoxSafeLogger.d(
                VoxLogCategory.DIAGNOSTICS,
                VoxLogCode.AUTO_DIAG_UPLOAD_QUEUED,
                "Diagnostic report enqueued to persistent offline queue",
                queueCtx
            )
        }
    }

    fun interface CompletionCallback {
        fun onComplete(success: Boolean)
    }

    fun pollOfflineReport(): VoxDiagnosticReport? = synchronized(queueLock) {
        if (offlineQueue.isNotEmpty()) {
            val rep = offlineQueue.removeAt(0).report
            saveToDiskLocked()
            rep
        } else null
    }

    private fun calculateBackoffMs(retryCount: Int): Long {
        val multiplier = 1L shl (retryCount - 1).coerceIn(0, 6)
        val delay = VoxDiagnosticsPolicy.OFFLINE_INITIAL_BACKOFF_MS * multiplier
        return delay.coerceAtMost(VoxDiagnosticsPolicy.OFFLINE_MAX_BACKOFF_MS)
    }

    @JvmOverloads
    fun flushOfflineQueue(onComplete: CompletionCallback? = null) {
        flushOfflineQueueInternal(forceBypassBackoff = false, onComplete = onComplete)
    }

    fun flushOfflineQueueForce(onComplete: CompletionCallback? = null) {
        flushOfflineQueueInternal(forceBypassBackoff = true, onComplete = onComplete)
    }

    private fun flushOfflineQueueInternal(forceBypassBackoff: Boolean, onComplete: CompletionCallback?) {
        val entry = synchronized(queueLock) {
            val now = System.currentTimeMillis()
            val removedExpired = offlineQueue.removeAll {
                val age = now - it.queuedTimestampMs
                age > VoxDiagnosticsPolicy.MAX_OFFLINE_QUEUE_AGE_MS || it.retryCount >= VoxDiagnosticsPolicy.MAX_OFFLINE_RETRY_COUNT
            }
            if (offlineQueue.isEmpty()) {
                if (removedExpired) {
                    saveToDiskLocked()
                }
                return@synchronized null
            }
            val eligibleIndex = offlineQueue.indexOfFirst { forceBypassBackoff || now >= it.nextRetryTimestampMs }
            if (eligibleIndex >= 0) {
                offlineQueue.removeAt(eligibleIndex)
            } else {
                if (removedExpired) {
                    saveToDiskLocked()
                }
                null
            }
        }

        if (entry == null) {
            onComplete?.onComplete(true)
            return
        }

        entry.retryCount++
        val retryCtx = mapOf(
            "retryCount" to entry.retryCount.toString(),
            "queuedCount" to getOfflineQueueSize().toString(),
            "trigger" to (entry.report.triggerEvent ?: "UNKNOWN"),
            "reportId" to (entry.report.reportId ?: "UNKNOWN")
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
                synchronized(queueLock) {
                    saveToDiskLocked()
                }
                flushOfflineQueueInternal(forceBypassBackoff, onComplete)
            }

            override fun onError(errorMessage: String) {
                synchronized(queueLock) {
                    if (entry.retryCount >= VoxDiagnosticsPolicy.MAX_OFFLINE_RETRY_COUNT) {
                        val dropCtx = mapOf(
                            "dropReason" to "MAX_RETRIES_EXCEEDED",
                            "retryCount" to entry.retryCount.toString(),
                            "reportId" to (entry.report.reportId ?: "UNKNOWN")
                        )
                        VoxSafeLogger.w(
                            VoxLogCategory.DIAGNOSTICS,
                            VoxLogCode.AUTO_DIAG_UPLOAD_DROPPED,
                            "Offline report dropped: exceeded max retry limit",
                            dropCtx
                        )
                    } else {
                        val delayMs = calculateBackoffMs(entry.retryCount)
                        entry.nextRetryTimestampMs = System.currentTimeMillis() + delayMs
                        offlineQueue.add(entry)
                        val backoffCtx = mapOf(
                            "retryCount" to entry.retryCount.toString(),
                            "nextRetryDelayMs" to delayMs.toString(),
                            "reportId" to (entry.report.reportId ?: "UNKNOWN")
                        )
                        VoxSafeLogger.w(
                            VoxLogCategory.DIAGNOSTICS,
                            VoxLogCode.AUTO_DIAG_UPLOAD_RETRY,
                            "Offline diagnostic report upload failed, will retry with backoff",
                            backoffCtx
                        )
                    }
                    saveToDiskLocked()
                }
                onComplete?.onComplete(false)
            }
        })
    }

    /**
     * Запускает фоновую периодическую проверку и отправку отчёта.
     */
    @JvmOverloads
    fun schedulePeriodicCheck(onComplete: CompletionCallback? = null) {
        EXECUTOR.execute {
            if (isRunning.getAndSet(true)) {
                onComplete?.onComplete(false)
                return@execute
            }
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
                val prominentEvent = VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(filteredEvents)
                val finalReport = rawReport.copy(
                    safeRecentEvents = deduplicatedEvents,
                    triggerEvent = rawReport.triggerEvent ?: prominentEvent?.code
                ).withCanonicalClassification()

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
        isSyntheticTest: Boolean = false,
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
                if (isSyntheticTest) {
                    existing.isSynthetic = true
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
                isCritical = isImmediate,
                isSynthetic = isSyntheticTest
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

                val purpose = when {
                    incident.isSynthetic -> VoxDiagnosticReport.PURPOSE_TEST
                    VoxUpdateChannelManager.instance(context).isBetaChannel() -> VoxDiagnosticReport.PURPOSE_AUTO_BETA
                    else -> VoxDiagnosticReport.PURPOSE_USER_MANUAL
                }

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
                val finalReport = rawReport.copy(safeRecentEvents = deduplicatedEvents).withCanonicalClassification()

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
