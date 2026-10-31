package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.KeyEvent
import com.liskovsoft.sharedutils.mylogger.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class VoxUserJourneyMode(val titleRu: String) {
    OFF("Выключено"),
    STANDARD("Стандартный (анонимный)"),
    DETAILED("Подробный (отладка действий)")
}

/**
 * Unified Observability 2.0 User Journey Recorder.
 *
 * Implements strict privacy, low-overhead ring-buffer telemetry,
 * causal action tracking, and local crash forensics.
 */
class VoxUserJourneyRecorder private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "VoxUserJourneyRecorder"
        private const val PREFS_NAME = "vox_user_journey_prefs"
        private const val KEY_MODE = "user_journey_mode"
        private const val KEY_INCLUDE_CONTENT = "user_journey_include_content"
        private const val KEY_DETAILED_STARTED_AT = "user_journey_detailed_started_at"
        private const val KEY_SESSION_ACTIVE = "user_journey_session_active"

        const val MAX_EVENTS = 200
        const val MAX_EVENTS_STANDARD = 200
        const val MAX_EVENTS_DETAILED = 500
        const val MAX_PINNED_BOOKMARKS = 20
        const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val JOURNEY_FILE_NAME = "vox_user_journey.json"
        private const val CRASH_SNAPSHOT_FILE_NAME = "vox_crash_snapshot.json"

        @Volatile
        private var sInstance: VoxUserJourneyRecorder? = null

        @JvmStatic
        fun resetForTests() {
            synchronized(this) {
                sInstance = null
            }
        }

        @JvmStatic
        fun instance(context: Context): VoxUserJourneyRecorder {
            return sInstance ?: synchronized(this) {
                sInstance ?: VoxUserJourneyRecorder(context.applicationContext ?: context).also {
                    sInstance = it
                    it.init()
                }
            }
        }
    }

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val lock = Any()
    private val memoryEvents = ArrayList<VoxUserJourneyEvent>(MAX_EVENTS_DETAILED)
    private val pinnedBookmarks = ArrayList<VoxUserJourneyEvent>(MAX_PINNED_BOOKMARKS)
    private var hadAbnormalExitFlag = false
    private var hadForceStopFlag = false
    private val sequenceCounter = AtomicLong(1L)
    private var currentSessionId: String = UUID.randomUUID().toString()
    private var currentPlaybackSessionId: String? = null
    private var lastRecordedKey: String? = null
    private var lastKeyTimestamp: Long = 0L

    private val ioExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "VoxUserJourney-IO").apply { isDaemon = true }
    }
    private var pendingSaveFuture: ScheduledFuture<*>? = null

    private val journeyFile: File by lazy {
        File(appContext.filesDir, JOURNEY_FILE_NAME)
    }

    private val crashSnapshotFile: File by lazy {
        File(appContext.filesDir, CRASH_SNAPSHOT_FILE_NAME)
    }

    private fun init() {
        checkPreviousSessionAbnormalExit()
        checkTtlExpiration()
        loadFromDisk()
    }

    private fun checkPreviousSessionAbnormalExit() {
        val hasCrash = crashSnapshotFile.exists() && crashSnapshotFile.length() > 0
        val wasActive = prefs.getBoolean(KEY_SESSION_ACTIVE, false)
        if (hasCrash) {
            hadAbnormalExitFlag = true
            hadForceStopFlag = false
            Log.w(TAG, "Abnormal exit detected: uncaught exception crash snapshot from previous session is present.")
        } else if (wasActive) {
            hadAbnormalExitFlag = false
            hadForceStopFlag = true
            Log.w(TAG, "Abnormal exit detected: previous session was terminated abnormally (force-stop or process kill).")
        } else {
            hadAbnormalExitFlag = false
            hadForceStopFlag = false
        }
        // Mark current session as active
        prefs.edit().putBoolean(KEY_SESSION_ACTIVE, true).apply()
    }

    fun hadAbnormalExit(): Boolean = hadAbnormalExitFlag || hadForceStopFlag
    fun hadUncaughtExceptionCrash(): Boolean = hadAbnormalExitFlag
    fun hadForceStop(): Boolean = hadForceStopFlag

    fun markCleanShutdown() {
        try {
            prefs.edit().putBoolean(KEY_SESSION_ACTIVE, false).commit()
            Log.i(TAG, "Session marked as clean shutdown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark clean shutdown", e)
        }
    }

    fun getMode(): VoxUserJourneyMode {
        if (checkTtlExpiration()) {
            return VoxUserJourneyMode.STANDARD
        }
        val modeStr = prefs.getString(KEY_MODE, VoxUserJourneyMode.STANDARD.name)
        return try {
            VoxUserJourneyMode.valueOf(modeStr ?: VoxUserJourneyMode.STANDARD.name)
        } catch (e: Exception) {
            VoxUserJourneyMode.STANDARD
        }
    }

    fun setMode(mode: VoxUserJourneyMode) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val editor = prefs.edit().putString(KEY_MODE, mode.name)
            if (mode == VoxUserJourneyMode.DETAILED) {
                editor.putLong(KEY_DETAILED_STARTED_AT, now)
            } else {
                editor.remove(KEY_DETAILED_STARTED_AT)
            }
            editor.commit()

            if (mode == VoxUserJourneyMode.OFF) {
                purgeAll()
            }
            Log.i(TAG, "User journey mode updated to: ${mode.name}")
        }
    }

    fun isContentDetailsEnabled(): Boolean {
        // By default false. Only allowed when mode is DETAILED.
        return getMode() == VoxUserJourneyMode.DETAILED && prefs.getBoolean(KEY_INCLUDE_CONTENT, false)
    }

    fun setContentDetailsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_CONTENT, enabled).commit()
        Log.i(TAG, "Content details inclusion set to: $enabled")
    }

    fun startNewPlaybackSession(): String {
        val newId = UUID.randomUUID().toString()
        synchronized(lock) {
            currentPlaybackSessionId = newId
        }
        return newId
    }

    fun getCurrentPlaybackSessionId(): String? = synchronized(lock) { currentPlaybackSessionId }

    private fun checkTtlExpiration(): Boolean {
        val modeStr = prefs.getString(KEY_MODE, null)
        val startedAt = prefs.getLong(KEY_DETAILED_STARTED_AT, 0L)
        val now = System.currentTimeMillis()
        if (modeStr == VoxUserJourneyMode.DETAILED.name) {
            // Guard against 24h expiration OR backwards system clock jump (e.g. TV boot without NTP)
            if (startedAt > 0L && ((now - startedAt) > DEFAULT_TTL_MS || now < startedAt)) {
                Log.w(TAG, "Detailed mode TTL expired or clock anomaly detected. Automatically reverting to STANDARD mode.")
                prefs.edit()
                    .putString(KEY_MODE, VoxUserJourneyMode.STANDARD.name)
                    .remove(KEY_DETAILED_STARTED_AT)
                    .putBoolean(KEY_INCLUDE_CONTENT, false)
                    .commit()
                deleteExternalExportFiles()
                return true
            }
        }
        return false
    }

    fun recordEvent(event: VoxUserJourneyEvent) {
        val mode = getMode()
        if (mode == VoxUserJourneyMode.OFF) {
            return
        }

        val maxCapacity = if (mode == VoxUserJourneyMode.DETAILED) MAX_EVENTS_DETAILED else MAX_EVENTS_STANDARD

        // Privacy Gate: filter content if not authorized
        val sanitizedEvent = if (!isContentDetailsEnabled() && event.videoRef != "redacted") {
            event.copy(videoRef = "redacted")
        } else {
            event
        }

        synchronized(lock) {
            while (memoryEvents.size >= maxCapacity) {
                memoryEvents.removeAt(0)
            }
            memoryEvents.add(sanitizedEvent)

            if (isPinnedCandidate(sanitizedEvent)) {
                if (pinnedBookmarks.size >= MAX_PINNED_BOOKMARKS) {
                    pinnedBookmarks.removeAt(0)
                }
                pinnedBookmarks.add(sanitizedEvent)
            }

            scheduleSaveToDiskLocked()
        }
    }

    private fun isPinnedCandidate(event: VoxUserJourneyEvent): Boolean {
        val cat = event.category.uppercase()
        val evt = event.event.uppercase()
        val st = event.status?.uppercase() ?: ""
        return cat == "CRASH" ||
               cat == "RECOVERY" ||
               cat == "DECODER" ||
               evt.contains("CRASH") ||
               evt.contains("START") ||
               evt.contains("ERROR") ||
               evt.contains("FAIL") ||
               st.contains("ERROR") ||
               st.contains("FAIL") ||
               st.contains("FALLBACK")
    }

    fun getPinnedBookmarks(): List<VoxUserJourneyEvent> = synchronized(lock) {
        ArrayList(pinnedBookmarks)
    }

    @JvmOverloads
    fun recordKeyAction(
        keyCode: Int,
        action: Int,
        screen: String? = null,
        viewRole: String? = null,
        outcome: String? = null
    ) {
        val keyName = KeyEvent.keyCodeToString(keyCode)
        recordKeyAction(keyName, "key_action_$action", screen, viewRole, outcome)
    }

    @JvmOverloads
    fun recordKeyAction(
        key: String,
        cause: String? = null,
        screen: String? = null,
        viewRole: String? = null,
        outcome: String? = null
    ) {
        val mode = getMode()
        if (mode == VoxUserJourneyMode.OFF) return

        val nowElapsed = SystemClock.elapsedRealtime()

        synchronized(lock) {
            val lastEvent = memoryEvents.lastOrNull()
            if (lastEvent != null &&
                lastEvent.category == "USER_INPUT" &&
                lastEvent.event == "KEY_ACTION" &&
                lastEvent.cause == (cause ?: key) &&
                lastRecordedKey == key &&
                (nowElapsed - lastKeyTimestamp) < 500L
            ) {
                val prevCount = (lastEvent.details?.get("repeatCount") as? Number)?.toInt() ?: 1
                val firstAt = (lastEvent.details?.get("firstAtElapsedMs") as? Number)?.toLong() ?: lastEvent.elapsedMs
                val newCount = prevCount + 1

                val updatedDetails = mutableMapOf<String, Any>()
                if (lastEvent.details != null) {
                    updatedDetails.putAll(lastEvent.details)
                }
                updatedDetails["repeatCount"] = newCount
                updatedDetails["firstAtElapsedMs"] = firstAt
                updatedDetails["lastAtElapsedMs"] = nowElapsed

                val updatedEvent = lastEvent.copy(
                    durationMs = nowElapsed - firstAt,
                    details = updatedDetails
                )
                memoryEvents[memoryEvents.size - 1] = updatedEvent
                lastKeyTimestamp = nowElapsed
                scheduleSaveToDiskLocked()
                return
            }

            lastRecordedKey = key
            lastKeyTimestamp = nowElapsed

            val detailsMap = mutableMapOf<String, Any>(
                "repeatCount" to 1,
                "firstAtElapsedMs" to nowElapsed,
                "lastAtElapsedMs" to nowElapsed
            )
            if (!outcome.isNullOrBlank()) {
                detailsMap["outcome"] = outcome
            }

            val event = VoxUserJourneyEvent(
                eventId = UUID.randomUUID().toString(),
                sessionId = currentSessionId,
                playbackSessionId = currentPlaybackSessionId,
                sequence = sequenceCounter.getAndIncrement(),
                wallTimeUtc = VoxUserJourneyEvent.currentUtcTimestamp(),
                elapsedMs = nowElapsed,
                category = "USER_INPUT",
                event = "KEY_ACTION",
                cause = cause ?: key,
                screen = screen,
                viewRole = viewRole,
                privacyLevel = mode.name,
                status = outcome ?: "FINISHED",
                durationMs = 0L,
                details = detailsMap
            )
            recordEvent(event)
        }
    }

    @JvmOverloads
    fun recordPlayback(
        event: String,
        videoTitle: String? = null,
        videoId: String? = null,
        positionMs: Long? = null,
        details: Map<String, Any>? = null,
        cause: String? = null,
        operationId: String? = null,
        parentEventId: String? = null,
        status: String? = null,
        durationMs: Long? = null
    ) {
        val rawRef = when {
            !videoId.isNullOrBlank() && !videoTitle.isNullOrBlank() -> "$videoTitle [$videoId]"
            !videoId.isNullOrBlank() -> videoId
            !videoTitle.isNullOrBlank() -> videoTitle
            else -> null
        }
        recordPlaybackDetailed(
            event = event,
            cause = cause,
            positionBeforeMs = positionMs,
            positionRequestedMs = null,
            liveWindowStartMs = null,
            liveWindowEndMs = null,
            videoRefRaw = rawRef,
            parentEventId = parentEventId,
            operationId = operationId,
            status = status,
            durationMs = durationMs,
            details = details
        )
    }

    @JvmOverloads
    fun recordPlaybackDetailed(
        event: String,
        cause: String? = null,
        positionBeforeMs: Long? = null,
        positionRequestedMs: Long? = null,
        liveWindowStartMs: Long? = null,
        liveWindowEndMs: Long? = null,
        videoRefRaw: String? = null,
        parentEventId: String? = null,
        operationId: String? = null,
        status: String? = null,
        durationMs: Long? = null,
        details: Map<String, Any>? = null
    ) {
        val mode = getMode()
        if (mode == VoxUserJourneyMode.OFF) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val videoRef = if (isContentDetailsEnabled() && !videoRefRaw.isNullOrBlank()) {
            VoxDiagnosticSanitizer.sanitize(videoRefRaw)
        } else {
            "redacted"
        }

        val journeyEvent = VoxUserJourneyEvent(
            eventId = UUID.randomUUID().toString(),
            sessionId = currentSessionId,
            playbackSessionId = currentPlaybackSessionId,
            sequence = sequenceCounter.getAndIncrement(),
            wallTimeUtc = VoxUserJourneyEvent.currentUtcTimestamp(),
            elapsedMs = nowElapsed,
            category = "PLAYBACK",
            event = event,
            cause = cause,
            positionBeforeMs = positionBeforeMs,
            positionRequestedMs = positionRequestedMs,
            liveWindowStartMs = liveWindowStartMs,
            liveWindowEndMs = liveWindowEndMs,
            videoRef = videoRef,
            privacyLevel = mode.name,
            parentEventId = parentEventId,
            operationId = operationId,
            status = status,
            durationMs = durationMs,
            details = details
        )
        recordEvent(journeyEvent)
    }

    @JvmOverloads
    fun recordVot(
        event: String,
        stage: String? = null,
        driftMs: Long? = null,
        duckingVolume: Float? = null,
        operationId: String? = null,
        parentEventId: String? = null,
        status: String? = null,
        details: Map<String, Any>? = null
    ) {
        val mode = getMode()
        if (mode == VoxUserJourneyMode.OFF) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val votDetails = mutableMapOf<String, Any>()
        if (stage != null) votDetails["stage"] = stage
        if (driftMs != null) votDetails["driftMs"] = driftMs
        if (duckingVolume != null) votDetails["duckingVolume"] = duckingVolume
        if (details != null) votDetails.putAll(details)

        val journeyEvent = VoxUserJourneyEvent(
            eventId = UUID.randomUUID().toString(),
            sessionId = currentSessionId,
            playbackSessionId = currentPlaybackSessionId,
            sequence = sequenceCounter.getAndIncrement(),
            wallTimeUtc = VoxUserJourneyEvent.currentUtcTimestamp(),
            elapsedMs = nowElapsed,
            category = "VOT",
            event = event,
            privacyLevel = mode.name,
            operationId = operationId,
            parentEventId = parentEventId,
            status = status,
            details = votDetails
        )
        recordEvent(journeyEvent)
    }

    @JvmOverloads
    fun recordDownload(
        event: String,
        jobId: String? = null,
        quality: String? = null,
        bytesDownloaded: Long? = null,
        bytesTotal: Long? = null,
        operationId: String? = null,
        status: String? = null,
        details: Map<String, Any>? = null
    ) {
        val mode = getMode()
        if (mode == VoxUserJourneyMode.OFF) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val dlDetails = mutableMapOf<String, Any>()
        if (jobId != null) dlDetails["jobId"] = jobId
        if (quality != null) dlDetails["quality"] = quality
        if (bytesDownloaded != null) dlDetails["bytesDownloaded"] = bytesDownloaded
        if (bytesTotal != null) dlDetails["bytesTotal"] = bytesTotal
        if (details != null) dlDetails.putAll(details)

        val journeyEvent = VoxUserJourneyEvent(
            eventId = UUID.randomUUID().toString(),
            sessionId = currentSessionId,
            playbackSessionId = currentPlaybackSessionId,
            sequence = sequenceCounter.getAndIncrement(),
            wallTimeUtc = VoxUserJourneyEvent.currentUtcTimestamp(),
            elapsedMs = nowElapsed,
            category = "DOWNLOAD",
            event = event,
            privacyLevel = mode.name,
            operationId = operationId ?: jobId,
            status = status,
            details = dlDetails
        )
        recordEvent(journeyEvent)
    }

    @JvmOverloads
    fun recordError(
        category: String,
        errorClass: String,
        message: String,
        topFrames: String? = null,
        parentEventId: String? = null,
        details: Map<String, Any>? = null
    ) {
        val mode = getMode()
        if (mode == VoxUserJourneyMode.OFF) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val errDetails = mutableMapOf<String, Any>(
            "errorClass" to errorClass,
            "message" to VoxDiagnosticSanitizer.sanitize(message)
        )
        if (!topFrames.isNullOrBlank()) errDetails["topFrames"] = VoxDiagnosticSanitizer.sanitize(topFrames)
        if (details != null) errDetails.putAll(details)

        val journeyEvent = VoxUserJourneyEvent(
            eventId = UUID.randomUUID().toString(),
            sessionId = currentSessionId,
            playbackSessionId = currentPlaybackSessionId,
            sequence = sequenceCounter.getAndIncrement(),
            wallTimeUtc = VoxUserJourneyEvent.currentUtcTimestamp(),
            elapsedMs = nowElapsed,
            category = category,
            event = "ERROR",
            privacyLevel = mode.name,
            parentEventId = parentEventId,
            status = "FAILED",
            details = errDetails
        )
        recordEvent(journeyEvent)
    }

    fun getRecentEvents(limit: Int = 100): List<VoxUserJourneyEvent> {
        synchronized(lock) {
            val count = memoryEvents.size.coerceAtMost(limit)
            return memoryEvents.takeLast(count)
        }
    }

    private fun getExternalMirrorFile(filename: String): File? {
        return try {
            val dir = appContext.getExternalFilesDir(null) ?: return null
            if (!dir.exists()) {
                dir.mkdirs()
            }
            File(dir, filename)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun deleteExternalExportFiles() {
        try {
            val dir = appContext.getExternalFilesDir(null) ?: return
            val exportFileNames = listOf(
                JOURNEY_FILE_NAME,
                CRASH_SNAPSHOT_FILE_NAME,
                "vox_user_journey_export.json",
                "vox_user_journey_detailed_export.json",
                "vox_crash_snapshot_export.json",
                "${JOURNEY_FILE_NAME}.tmp",
                "${CRASH_SNAPSHOT_FILE_NAME}.tmp"
            )
            for (name in exportFileNames) {
                val f = File(dir, name)
                if (f.exists()) {
                    f.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete external export files", e)
        }
    }

    private fun writeSafely(targetFile: File, bytes: ByteArray) {
        val parent = targetFile.parentFile ?: return
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(parent, "${targetFile.name}.tmp")
        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(bytes)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (ignored: Throwable) {}
            }
            if (!tmpFile.renameTo(targetFile)) {
                FileOutputStream(targetFile).use { it.write(bytes) }
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed safe write to ${targetFile.name}", e)
        }
    }

    fun clearEvents() {
        synchronized(lock) {
            memoryEvents.clear()
            pinnedBookmarks.clear()
            sequenceCounter.set(1L)
            lastRecordedKey = null
            lastKeyTimestamp = 0L
            ioExecutor.execute {
                try {
                    if (journeyFile.exists()) {
                        journeyFile.delete()
                    }
                    val tmp = File(journeyFile.parentFile, "${journeyFile.name}.tmp")
                    if (tmp.exists()) tmp.delete()
                    deleteExternalExportFiles()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete journey file", e)
                }
            }
        }
    }

    fun purgeAll() {
        synchronized(lock) {
            memoryEvents.clear()
            pinnedBookmarks.clear()
            sequenceCounter.set(1L)
            lastRecordedKey = null
            lastKeyTimestamp = 0L
            hadAbnormalExitFlag = false
            hadForceStopFlag = false
            prefs.edit().putBoolean(KEY_SESSION_ACTIVE, false).apply()
            try {
                if (journeyFile.exists()) {
                    journeyFile.delete()
                }
                val tmpJourney = File(journeyFile.parentFile, "${journeyFile.name}.tmp")
                if (tmpJourney.exists()) tmpJourney.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete journey file on purgeAll", e)
            }
            try {
                if (crashSnapshotFile.exists()) {
                    crashSnapshotFile.delete()
                }
                val tmpCrash = File(crashSnapshotFile.parentFile, "${crashSnapshotFile.name}.tmp")
                if (tmpCrash.exists()) tmpCrash.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete crash snapshot on purgeAll", e)
            }
            deleteExternalExportFiles()
        }
    }

    fun getIncidentSummary(): JSONObject {
        synchronized(lock) {
            return buildIncidentSummaryLocked()
        }
    }

    private fun buildIncidentSummaryLocked(): JSONObject {
        val summary = JSONObject()
        val trigger = memoryEvents.lastOrNull { ev ->
            ev.category.uppercase() == "CRASH" ||
            ev.status?.uppercase() in listOf("FAILED", "ERROR") ||
            ev.event.uppercase().contains("ERROR") ||
            ev.event.uppercase().contains("CRASH") ||
            ev.event.uppercase().contains("FAIL")
        }

        summary.put("hasIncident", trigger != null || hadAbnormalExitFlag || hadForceStopFlag)
        summary.put("hadUncaughtExceptionCrash", hadAbnormalExitFlag)
        summary.put("hadForceStop", hadForceStopFlag)
        if (trigger != null) {
            val trigObj = JSONObject()
            trigObj.put("category", trigger.category)
            trigObj.put("event", trigger.event)
            trigObj.put("cause", trigger.cause ?: "")
            trigObj.put("status", trigger.status ?: "")
            trigObj.put("elapsedMs", trigger.elapsedMs)
            trigObj.put("wallTimeUtc", trigger.wallTimeUtc)
            trigObj.put("operationId", trigger.operationId ?: "")
            summary.put("triggerEvent", trigObj)
        } else {
            summary.put("triggerEvent", JSONObject.NULL)
        }

        val finalStatus = when {
            hadAbnormalExitFlag -> "UNCAUGHT_EXCEPTION_CRASH"
            hadForceStopFlag -> "FORCE_STOP_EXIT"
            trigger != null -> "ERROR_TRIGGERED"
            else -> "HEALTHY"
        }
        summary.put("finalStatus", finalStatus)
        summary.put("appVersion", "32.56-vox.9.2-rc.8")
        summary.put("eventCount", memoryEvents.size)
        summary.put("pinnedCount", pinnedBookmarks.size)
        return summary
    }

    fun exportTimelineJson(): String {
        synchronized(lock) {
            val root = JSONObject()
            root.put("schemaVersion", 2)
            val mode = getMode()
            root.put("mode", mode.name)
            root.put("contentDetailsEnabled", isContentDetailsEnabled())
            root.put("totalEvents", memoryEvents.size)
            root.put("pinnedBookmarksCount", pinnedBookmarks.size)

            val incidentSummary = buildIncidentSummaryLocked()
            root.put("incidentSummary", incidentSummary)

            val array = JSONArray()
            memoryEvents.forEach { array.put(it.toJson()) }
            root.put("timeline", array)

            val pinnedArray = JSONArray()
            pinnedBookmarks.forEach { pinnedArray.put(it.toJson()) }
            root.put("pinnedBookmarks", pinnedArray)

            val result = root.toString(2)
            val bytes = result.toByteArray(StandardCharsets.UTF_8)
            val exportFileName = if (mode == VoxUserJourneyMode.DETAILED) {
                "vox_user_journey_detailed_export.json"
            } else {
                "vox_user_journey_export.json"
            }
            getExternalMirrorFile(exportFileName)?.let { writeSafely(it, bytes) }
            getExternalMirrorFile("vox_user_journey_export.json")?.let { writeSafely(it, bytes) }
            return result
        }
    }

    fun exportCrashSnapshotJson(): String? {
        val snapshotText = getCrashSnapshot() ?: return null
        try {
            val bytes = snapshotText.toByteArray(StandardCharsets.UTF_8)
            getExternalMirrorFile("vox_crash_snapshot_export.json")?.let { writeSafely(it, bytes) }
            getExternalMirrorFile(CRASH_SNAPSHOT_FILE_NAME)?.let { writeSafely(it, bytes) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export crash snapshot", e)
        }
        return snapshotText
    }

    fun saveCrashSnapshot(throwable: Throwable?) {
        // NOTE: Native crash (SIGSEGV/SIGABRT) or QEMU exit 139 bypasses this Java handler.
        // Coverage gap for native crashes is documented and expected.
        try {
            val snapshot = JSONObject()
            snapshot.put("schemaVersion", 2)
            snapshot.put("category", "CRASH")
            snapshot.put("event", "UNCAUGHT_EXCEPTION")
            snapshot.put("cause", VoxDiagnosticSanitizer.sanitize(throwable?.message ?: "None"))
            snapshot.put("timestampUtc", VoxUserJourneyEvent.currentUtcTimestamp())
            snapshot.put("sessionId", currentSessionId)
            snapshot.put("playbackSessionId", currentPlaybackSessionId ?: "none")
            snapshot.put("exceptionClass", throwable?.javaClass?.name ?: "UnknownCrash")
            snapshot.put("exceptionMessage", VoxDiagnosticSanitizer.sanitize(throwable?.message ?: "None"))

            val details = JSONObject()
            details.put("exceptionClass", throwable?.javaClass?.name ?: "UnknownCrash")
            details.put("exceptionMessage", VoxDiagnosticSanitizer.sanitize(throwable?.message ?: "None"))
            val stackTrace = throwable?.let {
                val sw = java.io.StringWriter()
                it.printStackTrace(java.io.PrintWriter(sw))
                VoxDiagnosticSanitizer.sanitize(sw.toString())
            } ?: ""
            details.put("stackTrace", stackTrace)
            snapshot.put("details", details)

            val recentEventsArray = JSONArray()
            synchronized(lock) {
                val lastEvents = memoryEvents.takeLast(50)
                lastEvents.forEach { recentEventsArray.put(it.toJson()) }
            }
            snapshot.put("recentJourneyTimeline", recentEventsArray)

            val bytes = snapshot.toString(2).toByteArray(StandardCharsets.UTF_8)
            // Continuous background crash snapshot stays strictly in internal storage
            writeSafely(crashSnapshotFile, bytes)
            Log.i(TAG, "Saved crash snapshot with ${recentEventsArray.length()} journey events")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash snapshot", e)
        }
    }

    fun getCrashSnapshot(): String? {
        return try {
            if (crashSnapshotFile.exists() && crashSnapshotFile.length() > 0) {
                crashSnapshotFile.readText(StandardCharsets.UTF_8)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun clearCrashSnapshot() {
        try {
            if (crashSnapshotFile.exists()) {
                crashSnapshotFile.delete()
            }
            val tmp = File(crashSnapshotFile.parentFile, "${crashSnapshotFile.name}.tmp")
            if (tmp.exists()) tmp.delete()
            getExternalMirrorFile("vox_crash_snapshot_export.json")?.delete()
            getExternalMirrorFile(CRASH_SNAPSHOT_FILE_NAME)?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete crash snapshot", e)
        }
    }

    fun hasCrashSnapshot(): Boolean = crashSnapshotFile.exists() && crashSnapshotFile.length() > 0
    fun getEvents(): List<VoxUserJourneyEvent> {
        val limit = if (getMode() == VoxUserJourneyMode.DETAILED) MAX_EVENTS_DETAILED else MAX_EVENTS_STANDARD
        return getRecentEvents(limit)
    }
    fun getEventCount(): Int = synchronized(lock) { memoryEvents.size }
    fun clear() = clearEvents()
    fun exportEventsJson(): String = exportTimelineJson()
    fun isIncludeContentDetails(): Boolean = isContentDetailsEnabled()
    fun setIncludeContentDetails(enabled: Boolean) = setContentDetailsEnabled(enabled)

    private fun scheduleSaveToDiskLocked() {
        pendingSaveFuture?.cancel(false)
        pendingSaveFuture = ioExecutor.schedule({
            saveToDisk()
        }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun saveToDisk() {
        val jsonString: String
        synchronized(lock) {
            val array = JSONArray()
            memoryEvents.forEach { array.put(it.toJson()) }
            jsonString = array.toString()
        }

        try {
            val bytes = jsonString.toByteArray(StandardCharsets.UTF_8)
            // Continuous background recording stays strictly in internal storage
            writeSafely(journeyFile, bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save user journey to disk", e)
        }
    }

    private fun loadFromDisk() {
        if (!journeyFile.exists() || journeyFile.length() == 0L) return

        try {
            val text = journeyFile.readText(StandardCharsets.UTF_8)
            val array = JSONArray(text)
            synchronized(lock) {
                memoryEvents.clear()
                pinnedBookmarks.clear()
                var maxSeq = 0L
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val event = VoxUserJourneyEvent.fromJson(obj) ?: continue
                    memoryEvents.add(event)
                    if (isPinnedCandidate(event) && pinnedBookmarks.size < MAX_PINNED_BOOKMARKS) {
                        pinnedBookmarks.add(event)
                    }
                    if (event.sequence > maxSeq) {
                        maxSeq = event.sequence
                    }
                }
                sequenceCounter.set(maxSeq + 1)
            }
            Log.i(TAG, "Loaded ${memoryEvents.size} user journey events from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load user journey from disk (corrupted or unparseable), starting fresh", e)
            synchronized(lock) {
                memoryEvents.clear()
                pinnedBookmarks.clear()
            }
        }
    }
}
