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
    private const val MAX_MEMORY_ENTRIES = 50
    private const val MAX_PERSISTED_ENTRIES = 100
    private const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

    data class TranslationRecord(
        val videoId: String,
        val canonicalUrl: String,
        val sourceLang: String,
        val targetLang: String,
        val audioUrl: String,
        val durationSec: Double,
        val useLivelyVoice: Boolean,
        val createdAtMs: Long
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
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadPersistedRecords()
        }
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
        val key = VoxTranslationReusePolicy.buildCacheKey(rawUrl ?: videoId, sourceLang, targetLang, useLively)

        val startMs = System.currentTimeMillis()
        val baseCtx = mapOf(
            "stage" to "LOOKUP",
            "mode" to if (useLively) "live_voice" else "standard"
        )
        VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.TRANSLATION_LOOKUP_STARTED, "Translation lookup started", baseCtx)

        // 1. Local memory cache
        val memRecord = memoryCache[key]
        if (memRecord != null && isValid(memRecord)) {
            val elapsedMs = System.currentTimeMillis() - startMs
            val ctx = mapOf(
                "elapsedMs" to elapsedMs.toString(),
                "source" to "MEMORY_CACHE",
                "finalPath" to "CACHE"
            )
            VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.TRANSLATION_LOCAL_CACHE_HIT, "Translation memory cache hit", ctx)
            return LookupResult.Hit(memRecord, "MEMORY_CACHE")
        }

        // 2. Local persisted session
        val persistedRecord = getPersistedRecord(key)
        if (persistedRecord != null && isValid(persistedRecord)) {
            memoryCache[key] = persistedRecord
            val elapsedMs = System.currentTimeMillis() - startMs
            val ctx = mapOf(
                "elapsedMs" to elapsedMs.toString(),
                "source" to "PERSISTED_SESSION",
                "finalPath" to "CACHE"
            )
            VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.TRANSLATION_PERSISTED_SESSION_HIT, "Translation persisted session hit", ctx)
            return LookupResult.Hit(persistedRecord, "PERSISTED_SESSION")
        }

        // 3. Existing active job
        val activeJob = activeJobs[key]
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

    private fun isValid(record: TranslationRecord): Boolean {
        if (record.audioUrl.isBlank()) return false
        val ageMs = System.currentTimeMillis() - record.createdAtMs
        return ageMs in 0..DEFAULT_TTL_MS
    }

    private fun getPersistedRecord(key: String): TranslationRecord? {
        val p = prefs ?: return null
        val rawJson = p.getString("rec_$key", null) ?: return null
        return try {
            val json = JSONObject(rawJson)
            TranslationRecord(
                videoId = json.getString("videoId"),
                canonicalUrl = json.getString("canonicalUrl"),
                sourceLang = json.optString("sourceLang", ""),
                targetLang = json.optString("targetLang", "ru"),
                audioUrl = json.getString("audioUrl"),
                durationSec = json.optDouble("durationSec", 0.0),
                useLivelyVoice = json.optBoolean("useLivelyVoice", false),
                createdAtMs = json.optLong("createdAtMs", 0L)
            )
        } catch (e: Exception) {
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
            }
            p.edit().putString("rec_$key", json.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist translation record: %s", e.message)
        }
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

    @JvmStatic
    fun clear() {
        clearMemory()
    }

    @JvmStatic
    fun clearMemory() {
        memoryCache.clear()
        activeJobs.clear()
    }
}
