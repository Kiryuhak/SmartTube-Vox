package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import com.liskovsoft.sharedutils.mylogger.Log

/**
 * Безопасный изолированный логгер событий SmartTube VOX.
 * 
 * Особенности:
 * - Структурированные события по строгим категориям (VoxLogCategory) и кодам (VoxLogCode);
 * - Гарантированная санитизация сообщений и контекста ДО попадания в буфер или на диск;
 * - Исключение стектрейсов и персональных данных (PII);
 * - Фильтрация уровней логирования (в хранилище попадают только INFO, WARNING, ERROR).
 */
object VoxSafeLogger {

    private const val TAG = "VoxSafeLogger"
    private const val MAX_MESSAGE_LENGTH = 500
    private const val MAX_CONTEXT_ENTRIES = 10
    private const val MAX_CONTEXT_VALUE_LENGTH = 200

    @Volatile
    private var logStore: VoxLogStore? = null

    @JvmStatic
    fun init(context: Context) {
        if (logStore == null) {
            synchronized(this) {
                if (logStore == null) {
                    logStore = VoxLogStore.instance(context)
                }
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun d(category: VoxLogCategory, code: String, message: String, context: Map<String, String>? = null) {
        logInternal(VoxLogLevel.DEBUG, category, code, message, context, null)
    }

    @JvmStatic
    @JvmOverloads
    fun i(category: VoxLogCategory, code: String, message: String, context: Map<String, String>? = null) {
        logInternal(VoxLogLevel.INFO, category, code, message, context, null)
    }

    @JvmStatic
    @JvmOverloads
    fun w(category: VoxLogCategory, code: String, message: String, context: Map<String, String>? = null) {
        logInternal(VoxLogLevel.WARNING, category, code, message, context, null)
    }

    @JvmStatic
    @JvmOverloads
    fun e(
        category: VoxLogCategory,
        code: String,
        message: String,
        context: Map<String, String>? = null,
        throwable: Throwable? = null
    ) {
        logInternal(VoxLogLevel.ERROR, category, code, message, context, throwable)
    }

    @JvmStatic
    @JvmOverloads
    fun debug(category: VoxLogCategory, code: String, message: String, context: Map<String, String>? = null) {
        d(category, code, message, context)
    }

    @JvmStatic
    @JvmOverloads
    fun info(category: VoxLogCategory, code: String, message: String, context: Map<String, String>? = null) {
        i(category, code, message, context)
    }

    @JvmStatic
    @JvmOverloads
    fun warn(category: VoxLogCategory, code: String, message: String, context: Map<String, String>? = null) {
        w(category, code, message, context)
    }

    @JvmStatic
    @JvmOverloads
    fun error(
        category: VoxLogCategory,
        code: String,
        message: String,
        context: Map<String, String>? = null,
        throwable: Throwable? = null
    ) {
        e(category, code, message, context, throwable)
    }

    @JvmStatic
    fun logBackgroundFailureSnapshot(
        reason: String,
        playerState: String,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        audioFocusState: String?,
        serviceState: String?,
        mediaSessionState: String?,
        backgroundEnabled: Boolean,
        audioOnlyEnabled: Boolean
    ) {
        val context = mapOf(
            "playerState" to playerState,
            "playWhenReady" to playWhenReady.toString(),
            "isPlaying" to isPlaying.toString(),
            "audioFocusState" to (audioFocusState ?: "unknown"),
            "serviceState" to (serviceState ?: "unknown"),
            "mediaSessionState" to (mediaSessionState ?: "unknown"),
            "backgroundEnabled" to backgroundEnabled.toString(),
            "audioOnlyEnabled" to audioOnlyEnabled.toString()
        )
        w(VoxLogCategory.BACKGROUND, VoxLogCode.BACKGROUND_PLAYER_PAUSED, "Background playback stopped unexpectedly: $reason", context)
    }

    private fun logInternal(
        level: VoxLogLevel,
        category: VoxLogCategory,
        code: String,
        rawMessage: String,
        rawContext: Map<String, String>?,
        throwable: Throwable?
    ) {
        val sanitizedCode = VoxDiagnosticSanitizer.sanitize(code).take(64)
        var sanitizedMsg = VoxDiagnosticSanitizer.sanitize(rawMessage).take(MAX_MESSAGE_LENGTH)

        if (throwable != null) {
            val exName = throwable.javaClass.simpleName
            val exMsg = VoxDiagnosticSanitizer.sanitize(throwable.message ?: "").take(200)
            sanitizedMsg = if (exMsg.isNotBlank()) {
                "$sanitizedMsg [$exName: $exMsg]".take(MAX_MESSAGE_LENGTH)
            } else {
                "$sanitizedMsg [$exName]".take(MAX_MESSAGE_LENGTH)
            }
        }

        val sanitizedContext = if (!rawContext.isNullOrEmpty()) {
            val map = mutableMapOf<String, String>()
            rawContext.entries.take(MAX_CONTEXT_ENTRIES).forEach { (k, v) ->
                val safeKey = VoxDiagnosticSanitizer.sanitize(k).take(32)
                val safeVal = VoxDiagnosticSanitizer.sanitize(v).take(MAX_CONTEXT_VALUE_LENGTH)
                map[safeKey] = safeVal
            }
            map
        } else null

        // System Logcat routing (for developer console)
        try {
            when (level) {
                VoxLogLevel.DEBUG -> Log.d(TAG, "[$category][$sanitizedCode] $sanitizedMsg")
                VoxLogLevel.INFO -> Log.i(TAG, "[$category][$sanitizedCode] $sanitizedMsg")
                VoxLogLevel.WARNING -> Log.w(TAG, "[$category][$sanitizedCode] $sanitizedMsg")
                VoxLogLevel.ERROR -> Log.e(TAG, "[$category][$sanitizedCode] $sanitizedMsg")
            }
        } catch (ignored: Throwable) {
            // JVM unit test environment fallback
        }

        // Production bounded disk store (INFO, WARNING, ERROR)
        if (level != VoxLogLevel.DEBUG) {
            val event = VoxLogEvent(
                timestamp = System.currentTimeMillis(),
                level = level,
                category = category,
                code = sanitizedCode,
                message = sanitizedMsg,
                context = sanitizedContext
            )
            try {
                logStore?.addEvent(event)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record event to log store: ${e.message}")
            }
        }
    }
}
