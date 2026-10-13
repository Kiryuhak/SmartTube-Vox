package com.liskovsoft.smartyoutubetv2.common.vox.translation

import android.content.Context
import android.content.SharedPreferences
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Translation Reuse 2.0: Lookup-first cache and active job registry.
 *
 * Pipeline:
 * USER_PRESS_TRANSLATE
 * -> NORMALIZE_VIDEO
 * -> LOCAL_MEMORY_CACHE
 * -> LOCAL_PERSISTED_SESSION
 * -> EXISTING_PROVIDER_SESSION / PENDING JOB
 * -> BACKEND_READY_LOOKUP
 * -> only after complete MISS create NEW TRANSLATION REQUEST
 */
object VoxTranslationSessionCache {

    private const val TAG = "VoxTranslationSessionCache"
    private const val PREFS_NAME = "vox_translation_sessions_v2"
    private const val CURRENT_SCHEMA_VERSION = 2
    private const val MAX_MEMORY_ENTRIES = 50
    private const val MAX_PERSISTED_ENTRIES = 100
    private const val DEFAULT_TTL_MS = 12L * 60 * 60 * 1000 // 12 hours bounded for remote signed URLs

    @Volatile private var customTtlMs: Long? = null

    private val memoryHitCount = java.util.concurrent.atomic.AtomicLong(0)
    private val persistedHitCount = java.util.concurrent.atomic.AtomicLong(0)
    private val serverExistingCount = java.util.concurrent.atomic.AtomicLong(0)
    private val newRequestCount = java.util.concurrent.atomic.AtomicLong(0)
    private val expiredRefreshCount = java.util.concurrent.atomic.AtomicLong(0)

    data class TranslationRecord(
        val videoId: String,
        val canonicalUrl: String,
        val sourceLang: String,
        val targetLang: String,
        val audioUrl: String,
        val durationSec: Double,
        val useLivelyVoice: Boolean,
        val createdAtMs: Long,
        val schemaVersion: Int = CURRENT_SCHEMA_VERSION
    )

    enum class JobStatus {
        PENDING,
        READY,
        FAILED
    }

    data class ActiveJob(
        val key: String,
        val videoId: String,
        var status: JobStatus,
        val startedAtMs: Long,
        var readyRecord: TranslationRecord? = null
    )

    sealed class LookupResult {
        data class Hit(val record: TranslationRecord, val source: String) : LookupResult()
        data class PendingJob(val job: ActiveJob) : LookupResult()
        object Miss : LookupResult()
    }

    private val memoryCache = ConcurrentHashMap<String, TranslationRecord>()
    private val activeJobs = ConcurrentHashMap<String, ActiveJob>()
    @Volatile private var prefs: SharedPreferences? = null

    @JvmStatic
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadPersistedRecords()
    }

    @JvmStatic
    fun setTtlForTesting(ttlMs: Long) {
        customTtlMs = ttlMs
    }

    @JvmStatic
    fun resetTtlForTesting() {
        customTtlMs = null
    }

    @JvmStatic
    fun incrementMemoryHit(): Long = memoryHitCount.incrementAndGet()

    @JvmStatic
    fun incrementPersistedHit(): Long = persistedHitCount.incrementAndGet()

    @JvmStatic
    fun incrementServerExisting(): Long = serverExistingCount.incrementAndGet()

    @JvmStatic
    fun incrementNewRequest(): Long = newRequestCount.incrementAndGet()

    @JvmStatic
    fun incrementExpiredRefresh(): Long = expiredRefreshCount.incrementAndGet()

    @JvmStatic
    fun getStats(): Map<String, Long> = mapOf(
        "memoryHitCount" to memoryHitCount.get(),
        "persistedHitCount" to persistedHitCount.get(),
        "serverExistingCount" to serverExistingCount.get(),
        "newRequestCount" to newRequestCount.get(),
        "expiredRefreshCount" to expiredRefreshCount.get()
    )

    @JvmStatic
    fun resetStats() {
        memoryHitCount.set(0)
        persistedHitCount.set(0)
        serverExistingCount.set(0)
        newRequestCount.set(0)
        expiredRefreshCount.set(0)
    }

    @JvmStatic
    fun lookup(
        videoId: String?,
        rawUrl: String?,
        sourceLang: String?,
        targetLang: String?,
        useLively: Boolean
    ): LookupResult {
        if (videoId.isNullOrBlank()) return LookupResult.Miss
        val effectiveTarget = if (!targetLang.isNullOrBlank()) targetLang.trim().lowercase() else "ru"
        val key = VoxTranslationReusePolicy.buildCacheKey(rawUrl ?: videoId, sourceLang, effectiveTarget, useLively)

        val startMs = System.currentTimeMillis()
        val baseCtx = mapOf(
            "stage" to "LOOKUP",
            "mode" to if (useLively) "live_voice" else "standard"
        )
        VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.TRANSLATION_LOOKUP_STARTED, "Translation lookup started", baseCtx)

        // 1. Local memory cache (exact key or fallback key with targetLang verification)
        val normalizedUrl = VoxTranslationReusePolicy.normalizeVideoUrl(rawUrl ?: videoId)
        val fallbackKey = VoxTranslationReusePolicy.buildFallbackCacheKey(normalizedUrl, effectiveTarget, useLively)

        var memRecord = memoryCache[key]
        if (memRecord == null || !isValid(memRecord, effectiveTarget)) {
            memRecord = memoryCache[fallbackKey]
        }
        if (memRecord == null || !isValid(memRecord, effectiveTarget)) {
            memRecord = memoryCache.values.firstOrNull {
                (it.videoId == videoId || it.canonicalUrl == normalizedUrl) &&
                        it.useLivelyVoice == useLively &&
                        it.targetLang.equals(effectiveTarget, ignoreCase = true) &&
                        isValid(it, effectiveTarget)
            }
        }

        if (memRecord != null && isValid(memRecord, effectiveTarget)) {
            val elapsedMs = System.currentTimeMillis() - startMs
            val ctx = mapOf(
                "elapsedMs" to elapsedMs.toString(),
                "source" to "MEMORY_CACHE",
                "finalPath" to "CACHE"
            )
            VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.TRANSLATION_LOCAL_CACHE_HIT, "Translation memory cache hit", ctx)
            incrementMemoryHit()
            return LookupResult.Hit(memRecord, "MEMORY_CACHE")
        }

        // 2. Local persisted session (exact key or fallback key with targetLang verification)
        var persistedRecord = getPersistedRecord(key)
        if (persistedRecord == null || !isValid(persistedRecord, effectiveTarget)) {
            persistedRecord = getPersistedRecord(fallbackKey)
        }
        if (persistedRecord == null || !isValid(persistedRecord, effectiveTarget)) {
            persistedRecord = findPersistedRecordMatching(videoId, normalizedUrl, effectiveTarget, useLively)
        }

        if (persistedRecord != null && isValid(persistedRecord, effectiveTarget)) {
            memoryCache[key] = persistedRecord
            val elapsedMs = System.currentTimeMillis() - startMs
            val ctx = mapOf(
                "elapsedMs" to elapsedMs.toString(),
                "source" to "PERSISTED_SESSION",
                "finalPath" to "CACHE"
            )
            VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.TRANSLATION_PERSISTED_SESSION_HIT, "Translation persisted session hit", ctx)
            incrementPersistedHit()
            return LookupResult.Hit(persistedRecord, "PERSISTED_SESSION")
        }

        // 3. Existing active job
        var activeJob = activeJobs[key]
        if (activeJob == null) {
            activeJob = activeJobs[fallbackKey]
        }
        if (activeJob != null) {
            if (activeJob.status == JobStatus.READY && activeJob.readyRecord != null) {
                val elapsedMs = System.currentTimeMillis() - startMs
                val ctx = mapOf(
                    "elapsedMs" to elapsedMs.toString(),
                    "source" to "EXISTING_PROVIDER_SESSION",
                    "finalPath" to "EXISTING_READY"
                )
                VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.TRANSLATION_EXISTING_READY, "Existing translation job already ready", ctx)
                return LookupResult.Hit(activeJob.readyRecord!!, "EXISTING_READY")
            } else if (activeJob.status == JobStatus.PENDING) {
                val elapsedMs = System.currentTimeMillis() - startMs
                val ctx = mapOf(
                    "elapsedMs" to elapsedMs.toString(),
                    "source" to "EXISTING_PROVIDER_SESSION",
                    "finalPath" to "EXISTING_PENDING"
                )
                VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.TRANSLATION_EXISTING_PENDING, "Existing translation job is pending", ctx)
                return LookupResult.PendingJob(activeJob)
            }
        }

        val elapsedMs = System.currentTimeMillis() - startMs
        val missCtx = mapOf(
            "elapsedMs" to elapsedMs.toString(),
            "source" to "LOCAL",
            "finalPath" to "EXISTING_MISS"
        )
        VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.TRANSLATION_LOCAL_CACHE_MISS, "Translation local cache miss", missCtx)
        return LookupResult.Miss
    }

    @JvmStatic
    fun registerPendingJob(
        videoId: String,
        rawUrl: String?,
        sourceLang: String?,
        targetLang: String?,
        useLively: Boolean
    ): ActiveJob {
        val key = VoxTranslationReusePolicy.buildCacheKey(rawUrl ?: videoId, sourceLang, targetLang, useLively)
        val job = ActiveJob(
            key = key,
            videoId = videoId,
            status = JobStatus.PENDING,
            startedAtMs = System.currentTimeMillis()
        )
        activeJobs[key] = job
        return job
    }

    @JvmStatic
    fun storeReadyTranslation(
        videoId: String,
        rawUrl: String?,
        sourceLang: String?,
        targetLang: String?,
        audioUrl: String,
        durationSec: Double,
        useLively: Boolean
    ) {
        if (videoId.isBlank() || audioUrl.isBlank()) return
        val canonicalUrl = VoxTranslationReusePolicy.normalizeVideoUrl(rawUrl ?: videoId)
        val key = VoxTranslationReusePolicy.buildCacheKey(canonicalUrl, sourceLang, targetLang, useLively)

        val record = TranslationRecord(
            videoId = videoId,
            canonicalUrl = canonicalUrl,
            sourceLang = sourceLang ?: "",
            targetLang = targetLang ?: "ru",
            audioUrl = audioUrl,
            durationSec = durationSec,
            useLivelyVoice = useLively,
            createdAtMs = System.currentTimeMillis()
        )

        if (memoryCache.size >= MAX_MEMORY_ENTRIES) {
            val oldestKey = memoryCache.minByOrNull { it.value.createdAtMs }?.key
            if (oldestKey != null) {
                memoryCache.remove(oldestKey)
            }
        }
        memoryCache[key] = record
        activeJobs[key]?.let {
            it.status = JobStatus.READY
            it.readyRecord = record
        }

        persistRecord(key, record)
    }

    @JvmStatic
    fun markJobFailed(
        videoId: String,
        rawUrl: String?,
        sourceLang: String?,
        targetLang: String?,
        useLively: Boolean
    ) {
        val key = VoxTranslationReusePolicy.buildCacheKey(rawUrl ?: videoId, sourceLang, targetLang, useLively)
        activeJobs[key]?.status = JobStatus.FAILED
    }

    private fun isValid(record: TranslationRecord, expectedTargetLang: String? = null): Boolean {
        if (record.audioUrl.isBlank() || record.videoId.isBlank()) return false
        if (!expectedTargetLang.isNullOrBlank() && !record.targetLang.equals(expectedTargetLang, ignoreCase = true)) {
            return false
        }
        val ttl = customTtlMs ?: DEFAULT_TTL_MS
        val ageMs = System.currentTimeMillis() - record.createdAtMs
        return ageMs in 0..ttl
    }

    private fun getPersistedRecord(key: String): TranslationRecord? {
        val p = prefs ?: return null
        val rawJson = p.getString("rec_$key", null) ?: return null
        return try {
            val json = JSONObject(rawJson)
            val videoId = json.getString("videoId")
            val audioUrl = json.getString("audioUrl")
            val createdAt = json.optLong("createdAtMs", 0L)
            if (videoId.isBlank() || audioUrl.isBlank() || createdAt <= 0L) {
                p.edit().remove("rec_$key").commit()
                return null
            }
            TranslationRecord(
                videoId = videoId,
                canonicalUrl = json.optString("canonicalUrl", ""),
                sourceLang = json.optString("sourceLang", ""),
                targetLang = json.optString("targetLang", "ru"),
                audioUrl = audioUrl,
                durationSec = json.optDouble("durationSec", 0.0),
                useLivelyVoice = json.optBoolean("useLivelyVoice", false),
                createdAtMs = createdAt,
                schemaVersion = json.optInt("schemaVersion", CURRENT_SCHEMA_VERSION)
            )
        } catch (e: Exception) {
            p.edit().remove("rec_$key").commit()
            null
        }
    }

    private fun persistRecord(key: String, record: TranslationRecord) {
        val p = prefs ?: return
        try {
            val json = JSONObject().apply {
                put("videoId", record.videoId)
                put("canonicalUrl", record.canonicalUrl)
                put("sourceLang", record.sourceLang)
                put("targetLang", record.targetLang)
                put("audioUrl", record.audioUrl)
                put("durationSec", record.durationSec)
                put("useLivelyVoice", record.useLivelyVoice)
                put("createdAtMs", record.createdAtMs)
                put("schemaVersion", CURRENT_SCHEMA_VERSION)
            }
            val editor = p.edit().putString("rec_$key", json.toString())
            trimPersistedRecordsIfNeeded(p, editor)
            editor.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist translation record: %s", e.message)
        }
    }

    private fun trimPersistedRecordsIfNeeded(p: SharedPreferences, editor: SharedPreferences.Editor) {
        try {
            val records = mutableListOf<Pair<String, Long>>()
            for ((k, v) in p.all) {
                if (k.startsWith("rec_") && v is String) {
                    try {
                        val jobj = JSONObject(v)
                        val created = jobj.optLong("createdAtMs", 0L)
                        records.add(Pair(k, created))
                    } catch (_: Exception) {
                        editor.remove(k)
                    }
                }
            }
            if (records.size > MAX_PERSISTED_ENTRIES) {
                records.sortBy { it.second }
                val toRemove = records.size - MAX_PERSISTED_ENTRIES
                for (i in 0 until toRemove) {
                    editor.remove(records[i].first)
                }
            }
        } catch (_: Exception) {}
    }

    private fun loadPersistedRecords() {
        val p = prefs ?: return
        try {
            val all = p.all
            for ((k, v) in all) {
                if (k.startsWith("rec_") && v is String) {
                    val key = k.removePrefix("rec_")
                    val rec = getPersistedRecord(key)
                    if (rec != null && isValid(rec)) {
                        memoryCache[key] = rec
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted records: %s", e.message)
        }
    }

    private fun findPersistedRecordMatching(videoId: String, normalizedUrl: String, targetLang: String, useLively: Boolean): TranslationRecord? {
        val p = prefs ?: return null
        try {
            val all = p.all
            for ((k, v) in all) {
                if (k.startsWith("rec_") && v is String) {
                    val rec = getPersistedRecord(k.removePrefix("rec_"))
                    if (rec != null && (rec.videoId == videoId || rec.canonicalUrl == normalizedUrl) &&
                        rec.useLivelyVoice == useLively &&
                        rec.targetLang.equals(targetLang, ignoreCase = true) &&
                        isValid(rec, targetLang)) {
                        return rec
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    @JvmStatic
    fun invalidate(videoId: String?, rawUrl: String?) {
        if (videoId.isNullOrBlank() && rawUrl.isNullOrBlank()) return
        val canonicalUrl = if (!rawUrl.isNullOrBlank()) VoxTranslationReusePolicy.normalizeVideoUrl(rawUrl) else ""

        // 1. Invalidate memory cache
        val itMem = memoryCache.entries.iterator()
        while (itMem.hasNext()) {
            val entry = itMem.next()
            val rec = entry.value
            if ((!videoId.isNullOrBlank() && rec.videoId == videoId) ||
                (canonicalUrl.isNotBlank() && rec.canonicalUrl == canonicalUrl)) {
                itMem.remove()
            }
        }

        // 2. Invalidate active jobs
        val itJobs = activeJobs.entries.iterator()
        while (itJobs.hasNext()) {
            val entry = itJobs.next()
            if (!videoId.isNullOrBlank() && entry.value.videoId == videoId) {
                itJobs.remove()
            }
        }

        // 3. Invalidate persisted records
        val p = prefs ?: return
        try {
            val editor = p.edit()
            for ((k, _) in p.all) {
                if (k.startsWith("rec_")) {
                    val rec = getPersistedRecord(k.removePrefix("rec_"))
                    if (rec != null && ((!videoId.isNullOrBlank() && rec.videoId == videoId) ||
                                (canonicalUrl.isNotBlank() && rec.canonicalUrl == canonicalUrl))) {
                        editor.remove(k)
                    }
                }
            }
            editor.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to invalidate persisted record: %s", e.message)
        }
    }

    @JvmStatic
    fun hasPersistedRecord(videoId: String): Boolean {
        if (videoId.isBlank()) return false
        val inMemory = memoryCache.values.any { it.videoId == videoId && isValid(it) }
        if (inMemory) return true
        val p = prefs ?: return false
        try {
            for ((k, v) in p.all) {
                if (k.startsWith("rec_") && v is String) {
                    val rec = getPersistedRecord(k.removePrefix("rec_"))
                    if (rec != null && rec.videoId == videoId && isValid(rec)) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    @JvmStatic
    fun clear() {
        clearMemory()
        clearPersisted()
    }

    @JvmStatic
    fun clearMemory() {
        memoryCache.clear()
        activeJobs.clear()
    }

    @JvmStatic
    fun clearPersisted() {
        val p = prefs ?: return
        try {
            val editor = p.edit()
            for ((k, _) in p.all) {
                if (k.startsWith("rec_")) {
                    editor.remove(k)
                }
            }
            editor.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear persisted records: %s", e.message)
        }
    }
}
