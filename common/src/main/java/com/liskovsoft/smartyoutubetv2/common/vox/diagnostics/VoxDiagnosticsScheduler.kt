package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannelManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Фоновый планировщик анонимной диагностики SmartTube VOX.
 *
 * Особенности:
 * - Выполняется только при соблюдении политики интервалов (6ч для Test, 72ч для Stable);
 * - Проверяет явное согласие пользователя перед каждой попыткой;
 * - Выполняет дедупликацию и санитизацию событий перед отправкой;
 * - Не блокирует UI и сетевой поток воспроизведения видео.
 */
class VoxDiagnosticsScheduler private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VoxDiagnosticsScheduler"
        private val EXECUTOR = Executors.newSingleThreadExecutor()

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
    private val offlineQueue = java.util.concurrent.ConcurrentLinkedQueue<VoxDiagnosticReport>()
    private var pendingIncidentTimer: java.util.concurrent.ScheduledFuture<*>? = null
    private val scheduledExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    fun getOfflineQueueSize(): Int = offlineQueue.size

    fun enqueueOfflineReport(report: VoxDiagnosticReport) {
        while (offlineQueue.size >= VoxDiagnosticsPolicy.MAX_OFFLINE_QUEUE_SIZE) {
            offlineQueue.poll() // Drop oldest to enforce bounded buffer
        }
        offlineQueue.offer(report)
    }

    fun pollOfflineReport(): VoxDiagnosticReport? = offlineQueue.poll()

    /**
     * Запускает фоновую периодическую проверку и отправку отчёта.
     */
    fun schedulePeriodicCheck(onComplete: ((Boolean) -> Unit)? = null) {
        if (isRunning.getAndSet(true)) {
            onComplete?.invoke(false)
            return
        }

        EXECUTOR.execute {
            try {
                val canSend = VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context)
                if (!canSend) {
                    Log.d(TAG, "Periodic diagnostics skipped: policy criteria not met (consent or interval).")
                    isRunning.set(false)
                    onComplete?.invoke(false)
                    return@execute
                }

                val hasValuableData = VoxDiagnosticsPolicy.hasValuableDiagnostics(context)
                if (!hasValuableData) {
                    Log.d(TAG, "Periodic diagnostics skipped: no valuable error/warning events to report.")
                    isRunning.set(false)
                    onComplete?.invoke(false)
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
                        onComplete?.invoke(true)
                    }

                    override fun onError(errorMessage: String) {
                        Log.w(TAG, "Periodic diagnostics submission failed: $errorMessage, enqueuing offline")
                        enqueueOfflineReport(finalReport)
                        isRunning.set(false)
                        onComplete?.invoke(false)
                    }
                })

            } catch (e: Throwable) {
                Log.e(TAG, "Error during periodic diagnostics check", e)
                isRunning.set(false)
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * Запускает обработку инцидента с окном агрегации (30-60с) или немедленно при критической ошибке.
     */
    fun triggerIncident(
        triggerCode: String,
        isImmediate: Boolean = VoxDiagnosticsPolicy.isFatalOrCriticalError(triggerCode),
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val delayMs = if (isImmediate) 0L else VoxDiagnosticsPolicy.INCIDENT_AGGREGATION_WINDOW_MS

        synchronized(this) {
            pendingIncidentTimer?.cancel(false)
            pendingIncidentTimer = scheduledExecutor.schedule({
                EXECUTOR.execute {
                    try {
                        val canSend = VoxDiagnosticsPolicy.canSendIncidentDiagnostics(context)
                        if (!canSend) {
                            Log.d(TAG, "Incident report suppressed by policy/rate-limit for $triggerCode")
                            onComplete?.invoke(false)
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
                            triggerEvent = triggerCode,
                            aggregationWindowMs = delayMs
                        )
                        val filteredEvents = rawReport.safeRecentEvents
                            .filter { VoxDiagnosticsPolicy.isEventAllowedByPrivacy(it) }
                        val deduplicatedEvents = VoxDiagnosticsPolicy.deduplicateEvents(filteredEvents)
                        val finalReport = rawReport.copy(safeRecentEvents = deduplicatedEvents)

                        VoxDiagnosticsClient.instance().submitReportAsync(finalReport, object : VoxDiagnosticsClient.Callback {
                            override fun onSuccess(reportId: String) {
                                Log.i(TAG, "Incident diagnostics submitted: $reportId (trigger=$triggerCode)")
                                VoxUpdateChannelManager.instance(context).setLastDiagnosticsSubmissionTimestamp(System.currentTimeMillis())
                                onComplete?.invoke(true)
                            }

                            override fun onError(errorMessage: String) {
                                Log.w(TAG, "Incident diagnostics submission failed: $errorMessage, enqueuing offline")
                                enqueueOfflineReport(finalReport)
                                onComplete?.invoke(false)
                            }
                        })
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error submitting incident report", e)
                        onComplete?.invoke(false)
                    }
                }
            }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }
}
