package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.json.JSONObject

enum class VoxLogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

enum class VoxLogCategory(val displayNameRu: String) {
    APP("Приложение"),
    PLAYER("Плеер"),
    MEDIA3("Медиа-движок"),
    CODEC("Кодеки"),
    DOWNLOAD("Загрузки"),
    TRANSLATION("Перевод"),
    YANDEX_AUTH("Авторизация"),
    NETWORK("Сеть"),
    PROXY("Прокси"),
    COMPATIBILITY("Совместимость"),
    STORAGE("Хранилище"),
    TIZEN("Tizen"),
    DIAGNOSTICS("Диагностика"),
    BACKGROUND("Фоновый режим"),
    OTA("Обновление ПО")
}

object VoxLogCode {
    const val APP_START = "APP_START"
    const val PLAYER_INIT_FAILED = "PLAYER_INIT_FAILED"
    const val PLAYER_RENDERER_ERROR = "PLAYER_RENDERER_ERROR"
    const val PLAYER_DECODER_ERROR = "PLAYER_DECODER_ERROR"
    const val PLAYER_DECODER_FALLBACK = "PLAYER_DECODER_FALLBACK"
    const val PLAYER_STARTUP_SLOW = "PLAYER_STARTUP_SLOW"
    const val PLAYER_REBUFFER = "PLAYER_REBUFFER"
    const val PLAYER_LOCAL_SOURCE_ERROR = "PLAYER_LOCAL_SOURCE_ERROR"
    const val PLAYER_HTTP_ERROR = "PLAYER_HTTP_ERROR"
    const val PLAYER_SOURCE_ERROR = "PLAYER_SOURCE_ERROR"
    const val PLAYER_TRACK_FALLBACK = "PLAYER_TRACK_FALLBACK"
    const val VIDEO_TRACK_MISSING = "VIDEO_TRACK_MISSING"
    const val AUDIO_TRACK_MISSING = "AUDIO_TRACK_MISSING"
    const val LOCAL_FILE_OPEN_FAILED = "LOCAL_FILE_OPEN_FAILED"
    const val CODEC_FALLBACK_USED = "CODEC_FALLBACK_USED"

    const val DOWNLOAD_QUEUED = "DOWNLOAD_QUEUED"
    const val DOWNLOAD_STARTED = "DOWNLOAD_STARTED"
    const val DOWNLOAD_VIDEO_STARTED = "DOWNLOAD_VIDEO_STARTED"
    const val DOWNLOAD_VIDEO_COMPLETED = "DOWNLOAD_VIDEO_COMPLETED"
    const val DOWNLOAD_AUDIO_STARTED = "DOWNLOAD_AUDIO_STARTED"
    const val DOWNLOAD_AUDIO_COMPLETED = "DOWNLOAD_AUDIO_COMPLETED"
    const val DOWNLOAD_TRANSLATION_STARTED = "DOWNLOAD_TRANSLATION_STARTED"
    const val DOWNLOAD_TRANSLATION_COMPLETED = "DOWNLOAD_TRANSLATION_COMPLETED"
    const val DOWNLOAD_PACKAGING_STARTED = "DOWNLOAD_PACKAGING_STARTED"
    const val DOWNLOAD_PACKAGING_COMPLETED = "DOWNLOAD_PACKAGING_COMPLETED"
    const val DOWNLOAD_PACKAGING_FAILED = "DOWNLOAD_PACKAGING_FAILED"
    const val DOWNLOAD_FINALIZE_STARTED = "DOWNLOAD_FINALIZE_STARTED"
    const val DOWNLOAD_FINALIZE_FAILED = "DOWNLOAD_FINALIZE_FAILED"
    const val DOWNLOAD_COMPLETED = "DOWNLOAD_COMPLETED"
    const val DOWNLOAD_CANCELLED = "DOWNLOAD_CANCELLED"
    const val DOWNLOAD_RESUMED = "DOWNLOAD_RESUMED"
    const val DOWNLOAD_HTTP_403 = "DOWNLOAD_HTTP_403"
    const val DOWNLOAD_URL_EXPIRED = "DOWNLOAD_URL_EXPIRED"
    const val DOWNLOAD_STORAGE_FULL = "DOWNLOAD_STORAGE_FULL"
    const val DOWNLOAD_FORMAT_UNAVAILABLE = "DOWNLOAD_FORMAT_UNAVAILABLE"
    const val DOWNLOAD_FAILED = "DOWNLOAD_FAILED"

    const val TRANSLATION_REQUESTED = "TRANSLATION_REQUESTED"
    const val TRANSLATION_STARTED = "TRANSLATION_STARTED"
    const val TRANSLATION_COMPLETED = "TRANSLATION_COMPLETED"
    const val TRANSLATION_TIMEOUT = "TRANSLATION_TIMEOUT"
    const val TRANSLATION_BACKEND_ERROR = "TRANSLATION_BACKEND_ERROR"
    const val TRANSLATION_DESYNC_WARNING = "TRANSLATION_DESYNC_WARNING"
    const val LIVE_TRANSLATION_START = "LIVE_TRANSLATION_START"
    const val LIVE_TRANSLATION_BUFFER_READY = "LIVE_TRANSLATION_BUFFER_READY"
    const val LIVE_TRANSLATION_BUFFER_LOW = "LIVE_TRANSLATION_BUFFER_LOW"
    const val LIVE_TRANSLATION_DELAY_INCREASED = "LIVE_TRANSLATION_DELAY_INCREASED"
    const val LIVE_TRANSLATION_DELAY_DECREASED = "LIVE_TRANSLATION_DELAY_DECREASED"
    const val LIVE_TRANSLATION_REBUFFER = "LIVE_TRANSLATION_REBUFFER"
    const val LIVE_TRANSLATION_BACKEND_TIMEOUT = "LIVE_TRANSLATION_BACKEND_TIMEOUT"
    const val LIVE_TRANSLATION_FALLBACK_ORIGINAL = "LIVE_TRANSLATION_FALLBACK_ORIGINAL"
    const val LIVE_TRANSLATION_STOPPED = "LIVE_TRANSLATION_STOPPED"

    const val AUTH_STARTED = "AUTH_STARTED"
    const val DEVICE_CODE_RECEIVED = "DEVICE_CODE_RECEIVED"
    const val AUTH_SUCCESS = "AUTH_SUCCESS"
    const val AUTH_TIMEOUT = "AUTH_TIMEOUT"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val TOKEN_SAVE_SUCCESS = "TOKEN_SAVE_SUCCESS"
    const val TOKEN_SAVE_FAILED = "TOKEN_SAVE_FAILED"
    const val TOKEN_RESET = "TOKEN_RESET"

    const val PROXY_CONNECTED = "PROXY_CONNECTED"
    const val PROXY_FAILED = "PROXY_FAILED"
    const val PROXY_TIMEOUT = "PROXY_TIMEOUT"
    const val PROXY_CONNECTION_FAILED = "PROXY_CONNECTION_FAILED"

    const val DEVICE_PROFILE_UNKNOWN = "DEVICE_PROFILE_UNKNOWN"
    const val DIAGNOSTIC_SEND_FAILED = "DIAGNOSTIC_SEND_FAILED"
    const val DIAGNOSTIC_SEND_SUCCESS = "DIAGNOSTIC_SEND_SUCCESS"

    const val STORAGE_CLEARED = "STORAGE_CLEARED"

    // UI & Badges
    const val SIDEBAR_FOCUS_CHANGED = "SIDEBAR_FOCUS_CHANGED"
    const val QUALITY_BADGE_BOUND = "QUALITY_BADGE_BOUND"
    const val AGE_BADGE_BOUND = "AGE_BADGE_BOUND"

    // Offline / Downloaded Playback Probe & Observability
    const val OFFLINE_PLAYBACK_OPENED = "OFFLINE_PLAYBACK_OPENED"
    const val OFFLINE_VIDEO_TRACK_READY = "OFFLINE_VIDEO_TRACK_READY"
    const val OFFLINE_TIMELINE_READY = "OFFLINE_TIMELINE_READY"
    const val OFFLINE_FIRST_FRAME_RENDERED = "OFFLINE_FIRST_FRAME_RENDERED"
    const val OFFLINE_POSITION_ADVANCING = "OFFLINE_POSITION_ADVANCING"
    const val OFFLINE_SEEK = "OFFLINE_SEEK"
    const val OFFLINE_SEEK_REQUESTED = "OFFLINE_SEEK_REQUESTED"
    const val OFFLINE_SEEK_COMPLETED = "OFFLINE_SEEK_COMPLETED"
    const val OFFLINE_POSSIBLE_BLACK_SCREEN = "OFFLINE_POSSIBLE_BLACK_SCREEN"

    // Hardware Auto Tuning
    const val AUTO_TUNING_SCANNED = "AUTO_TUNING_SCANNED"
    const val AUTO_TUNING_RECOMMENDED = "AUTO_TUNING_RECOMMENDED"
    const val AUTO_TUNING_APPLIED = "AUTO_TUNING_APPLIED"
    const val AUTO_TUNING_RESTORED = "AUTO_TUNING_RESTORED"
    const val AUTO_TUNING_MANUAL_OVERRIDE = "AUTO_TUNING_MANUAL_OVERRIDE"
    const val AUTO_TUNING_RISK_WARNING = "AUTO_TUNING_RISK_WARNING"

    // Background Playback
    const val BACKGROUND_PLAYBACK_REQUESTED = "BACKGROUND_PLAYBACK_REQUESTED"
    const val BACKGROUND_PLAYBACK_ALLOWED = "BACKGROUND_PLAYBACK_ALLOWED"
    const val BACKGROUND_PLAYBACK_BLOCKED = "BACKGROUND_PLAYBACK_BLOCKED"
    const val BACKGROUND_AUDIO_ONLY_ENTER = "BACKGROUND_AUDIO_ONLY_ENTER"
    const val BACKGROUND_AUDIO_ONLY_EXIT = "BACKGROUND_AUDIO_ONLY_EXIT"
    const val BACKGROUND_PLAYER_CONTINUED = "BACKGROUND_PLAYER_CONTINUED"
    const val BACKGROUND_PLAYER_PAUSED = "BACKGROUND_PLAYER_PAUSED"
    const val BACKGROUND_SERVICE_STARTED = "BACKGROUND_SERVICE_STARTED"
    const val BACKGROUND_SERVICE_STOPPED = "BACKGROUND_SERVICE_STOPPED"
    const val BACKGROUND_AUDIO_FOCUS_GAIN = "BACKGROUND_AUDIO_FOCUS_GAIN"
    const val BACKGROUND_AUDIO_FOCUS_LOSS = "BACKGROUND_AUDIO_FOCUS_LOSS"
    const val BACKGROUND_MEDIASESSION_ACTIVE = "BACKGROUND_MEDIASESSION_ACTIVE"
    const val BACKGROUND_MEDIASESSION_RELEASED = "BACKGROUND_MEDIASESSION_RELEASED"

    // OTA Update
    const val OTA_CHECK_STARTED = "OTA_CHECK_STARTED"
    const val OTA_RELEASE_FOUND = "OTA_RELEASE_FOUND"
    const val OTA_VERSION_COMPARED = "OTA_VERSION_COMPARED"
    const val OTA_ASSET_SELECTED = "OTA_ASSET_SELECTED"
    const val OTA_DOWNLOAD_STARTED = "OTA_DOWNLOAD_STARTED"
    const val OTA_DOWNLOAD_COMPLETED = "OTA_DOWNLOAD_COMPLETED"
    const val OTA_HASH_VERIFIED = "OTA_HASH_VERIFIED"
    const val OTA_SIGNATURE_VERIFIED = "OTA_SIGNATURE_VERIFIED"
    const val OTA_INSTALL_REQUESTED = "OTA_INSTALL_REQUESTED"
    const val OTA_FAILED = "OTA_FAILED"

    const val UNKNOWN = "UNKNOWN"
}

/**
 * Неизменяемая структура безопасного события журнала VOX.
 */
data class VoxLogEvent(
    val timestamp: Long,
    val level: VoxLogLevel,
    val category: VoxLogCategory,
    val code: String,
    val message: String,
    val context: Map<String, String>? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("timestamp", timestamp)
        json.put("level", level.name)
        json.put("category", category.name)
        json.put("code", code)
        json.put("message", message)
        if (!context.isNullOrEmpty()) {
            val ctxObj = JSONObject()
            context.forEach { (k, v) -> ctxObj.put(k, v) }
            json.put("context", ctxObj)
        }
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): VoxLogEvent? {
            return try {
                val ts = json.optLong("timestamp", System.currentTimeMillis())
                val lvlStr = json.optString("level", "INFO")
                val level = try { VoxLogLevel.valueOf(lvlStr) } catch (e: Exception) { VoxLogLevel.INFO }
                val catStr = json.optString("category", "APP")
                val category = try { VoxLogCategory.valueOf(catStr) } catch (e: Exception) { VoxLogCategory.APP }
                val code = json.optString("code", "UNKNOWN")
                val message = json.optString("message", "")
                val ctxObj = json.optJSONObject("context")
                val ctxMap = if (ctxObj != null) {
                    val map = mutableMapOf<String, String>()
                    val keys = ctxObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        map[key] = ctxObj.optString(key, "")
                    }
                    map
                } else null

                VoxLogEvent(ts, level, category, code, message, ctxMap)
            } catch (e: Exception) {
                null
            }
        }
    }
}
