package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import com.liskovsoft.sharedutils.mylogger.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Ограниченное кольцевое хранилище безопасных событий диагностики (VoxLogStore).
 * 
 * Особенности:
 * - Кольцевой буфер в памяти (максимум 500 событий);
 * - Ограниченный размер файла на диске (~256 КБ);
 * - Автоматическая ротация и удаление событий старше 7 дней;
 * - Гарантированная защита от сбоев при повреждении файла;
 * - Потокобезопасность.
 */
class VoxLogStore private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "VoxLogStore"
        private const val LOG_FILE_NAME = "vox_safe_logs.json"
        const val MAX_EVENTS = 500
        const val MAX_FILE_BYTES = 256 * 1024 // 256 KB
        val RETENTION_MS = TimeUnit.DAYS.toMillis(7)

        @Volatile
        private var sInstance: VoxLogStore? = null

        @JvmStatic
        fun instance(context: Context): VoxLogStore {
            return sInstance ?: synchronized(this) {
                sInstance ?: VoxLogStore(context.applicationContext ?: context).also {
                    sInstance = it
                    it.loadFromDisk()
                }
            }
        }
    }

    private val lock = Any()
    private val memoryEvents = ArrayList<VoxLogEvent>(MAX_EVENTS)
    private val logFile: File by lazy {
        File(appContext.filesDir, LOG_FILE_NAME)
    }

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun addEvent(event: VoxLogEvent) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            pruneExpiredEventsLocked(now)

            // Ring buffer eviction
            if (memoryEvents.size >= MAX_EVENTS) {
                memoryEvents.removeAt(0)
            }
            memoryEvents.add(event)

            saveToDiskLocked()
        }
    }

    fun getEvents(maxCount: Int = MAX_EVENTS, minLevel: VoxLogLevel = VoxLogLevel.INFO): List<VoxLogEvent> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            pruneExpiredEventsLocked(now)

            return memoryEvents
                .filter { it.level.ordinal >= minLevel.ordinal }
                .takeLast(maxCount)
                .reversed() // Newest first
        }
    }

    fun getRecentEvents(maxCount: Int = 50): List<VoxLogEvent> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            pruneExpiredEventsLocked(now)

            return memoryEvents
                .takeLast(maxCount)
                .reversed() // Newest first
        }
    }

    fun getJournalEventCount(): Int {
        synchronized(lock) {
            return memoryEvents.size
        }
    }

    fun clearLogs() {
        synchronized(lock) {
            memoryEvents.clear()
            try {
                if (logFile.exists()) {
                    logFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete log file on clear", e)
            }
        }
    }

    fun getFormattedJournal(): String {
        val events = getEvents(MAX_EVENTS, VoxLogLevel.INFO)
        if (events.isEmpty()) {
            return "Ошибок пока не зафиксировано."
        }

        val sb = StringBuilder()
        sb.append("=== Журнал событий SmartTube VOX ===\n\n")
        for (ev in events) {
            val timeStr = dateFormatter.format(Date(ev.timestamp))
            sb.append("[").append(timeStr).append("] [")
                .append(ev.level.name).append("] [")
                .append(ev.category.name).append("] ")
                .append(ev.code).append("\n")
            sb.append("Описание: ").append(ev.message).append("\n")
            if (!ev.context.isNullOrEmpty()) {
                sb.append("Контекст: ")
                ev.context.forEach { (k, v) ->
                    sb.append(k).append("=").append(v).append(" ")
                }
                sb.append("\n")
            }
            sb.append("----------------------------------------\n")
        }
        return sb.toString()
    }

    private fun pruneExpiredEventsLocked(now: Long) {
        val expiryCutoff = now - RETENTION_MS
        val it = memoryEvents.iterator()
        while (it.hasNext()) {
            val ev = it.next()
            if (ev.timestamp < expiryCutoff) {
                it.remove()
            }
        }
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            memoryEvents.clear()
            if (!logFile.exists()) return

            try {
                val text = logFile.readText(StandardCharsets.UTF_8)
                if (text.isBlank()) return

                val jsonArray = JSONArray(text)
                val now = System.currentTimeMillis()
                val cutoff = now - RETENTION_MS

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val event = VoxLogEvent.fromJson(obj)
                    if (event != null && event.timestamp >= cutoff) {
                        memoryEvents.add(event)
                    }
                }

                // Keep within ring buffer bounds
                while (memoryEvents.size > MAX_EVENTS) {
                    memoryEvents.removeAt(0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Corrupted log store file recovered, resetting: ${e.message}")
                memoryEvents.clear()
                try {
                    logFile.delete()
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun saveToDiskLocked() {
        try {
            val array = JSONArray()
            for (ev in memoryEvents) {
                array.put(ev.toJson())
            }
            val jsonString = array.toString()

            // Check max byte limit
            val bytes = jsonString.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_FILE_BYTES) {
                // Remove oldest quarter if exceeded
                val dropCount = memoryEvents.size / 4
                if (dropCount > 0 && dropCount < memoryEvents.size) {
                    for (i in 0 until dropCount) {
                        memoryEvents.removeAt(0)
                    }
                    saveToDiskLocked()
                    return
                }
            }

            FileOutputStream(logFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist log events to disk", e)
        }
    }
}
