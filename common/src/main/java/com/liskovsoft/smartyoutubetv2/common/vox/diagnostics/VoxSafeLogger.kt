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
        when (level) {
            VoxLogLevel.DEBUG -> Log.d(TAG, "[$category][$sanitizedCode] $sanitizedMsg")
            VoxLogLevel.INFO -> Log.i(TAG, "[$category][$sanitizedCode] $sanitizedMsg")
            VoxLogLevel.WARNING -> Log.w(TAG, "[$category][$sanitizedCode] $sanitizedMsg")
            VoxLogLevel.ERROR -> Log.e(TAG, "[$category][$sanitizedCode] $sanitizedMsg")
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
