package com.liskovsoft.smartyoutubetv2.common.vox.language

import android.content.Context
import android.content.SharedPreferences
import com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxTranslationReusePolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * Локальный persistent-кэш распознанных языков видео.
 * Исключает повторные обращения к сети при повторном воспроизведении видео.
 * Ключ: канонический URL (https://youtu.be/<videoId>).
 */
class VoxVideoLanguageCache private constructor(private val prefs: SharedPreferences? = null) {

    data class CacheEntry(
        val language: String,
        val timestamp: Long
    )

    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()

    companion object {
        const val PREFS_NAME = "vox_video_language_cache"
        const val KEY_PREFIX = "lang_"
        const val DEFAULT_TTL_MS = 7 * 24 * 60 * 60 * 1000L // 7 дней
        const val MAX_MEMORY_ENTRIES = 500 // Соответствует reference MAX_SHARED_LANGUAGE_STATES

        @Volatile
        private var instance: VoxVideoLanguageCache? = null

        @JvmStatic
        fun getInstance(context: Context? = null): VoxVideoLanguageCache {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val sp = context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    VoxVideoLanguageCache(sp).also { instance = it }
                }
            }
        }

        @JvmStatic
        fun createInMemory(): VoxVideoLanguageCache {
            return VoxVideoLanguageCache(null)
        }
    }

    fun getLanguage(urlOrVideoId: String?): String? {
        val canonicalKey = getCanonicalKey(urlOrVideoId) ?: return null
        val now = System.currentTimeMillis()

        // 1. Быстрый memory cache lookup
        val memEntry = memoryCache[canonicalKey]
        if (memEntry != null) {
            if (now - memEntry.timestamp <= DEFAULT_TTL_MS) {
                return memEntry.language
            } else {
                memoryCache.remove(canonicalKey)
            }
        }

        // 2. Persistent storage lookup
        val sp = prefs ?: return null
        val stored = sp.getString(KEY_PREFIX + canonicalKey, null) ?: return null
        val parts = stored.split(";")
        if (parts.size >= 2) {
            val lang = parts[0]
            val ts = parts[1].toLongOrNull() ?: 0L
            if (now - ts <= DEFAULT_TTL_MS && lang.isNotEmpty()) {
                val entry = CacheEntry(lang, ts)
                memoryCache[canonicalKey] = entry
                return lang
            } else {
                sp.edit().remove(KEY_PREFIX + canonicalKey).apply()
            }
        }

        return null
    }

    fun putLanguage(urlOrVideoId: String?, language: String?) {
        val canonicalKey = getCanonicalKey(urlOrVideoId) ?: return
        val normalizedLang = VoxLanguagePolicy.normalizeToRequestLang(language)
        if (normalizedLang.isNullOrBlank() || normalizedLang == VoxLanguagePolicy.LANG_AUTO) {
            return
        }

        val now = System.currentTimeMillis()
        val entry = CacheEntry(normalizedLang, now)

        if (memoryCache.size >= MAX_MEMORY_ENTRIES) {
            val oldestKey = memoryCache.entries.minByOrNull { it.value.timestamp }?.key
            if (oldestKey != null) {
                memoryCache.remove(oldestKey)
            }
        }
        memoryCache[canonicalKey] = entry

        prefs?.let { sp ->
            sp.edit().putString(KEY_PREFIX + canonicalKey, "$normalizedLang;$now").apply()
        }
    }

    fun hasLanguage(urlOrVideoId: String?): Boolean {
        return getLanguage(urlOrVideoId) != null
    }

    fun clear() {
        memoryCache.clear()
        prefs?.edit()?.clear()?.apply()
    }

    private fun getCanonicalKey(urlOrVideoId: String?): String? {
        if (urlOrVideoId.isNullOrBlank()) return null
        val videoId = VoxTranslationReusePolicy.extractVideoId(urlOrVideoId)
        return if (!videoId.isNullOrBlank()) {
            videoId
        } else {
            VoxTranslationReusePolicy.normalizeVideoUrl(urlOrVideoId)
        }
    }
}
