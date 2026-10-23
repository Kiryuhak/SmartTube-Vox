package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import android.os.Build
import com.liskovsoft.sharedutils.mylogger.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Менеджер отправки минимальных анонимных heartbeat-событий SmartTube VOX для оценки
 * примерного онлайна активных устройств (окно активности 45 минут).
 *
 * Политика безопасности и приватности:
 * 1. Отправляется не чаще одного раза в 30 минут при активном использовании приложения;
 * 2. Не использует постоянный foreground-сервис и не выполняет пробуждений устройства в режиме сна;
 * 3. Передаёт только анонимный Installation ID (UUID v4), модель, производителя, версию VOX и SDK;
 * 4. Полностью соблюдает явный отказ пользователя (opt-out): при включённом opt-out сетевые запросы блокируются;
 * 5. Безопасен к старым версиям Cloudflare Worker: при получении HTTP 404 запрос не перезапускается циклически
 *    и не влияет на воспроизведение видео или пользовательский интерфейс.
 */
class VoxHeartbeatManager private constructor(private val context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val isSending = AtomicBoolean(false)

    /**
     * Уведомляет систему об активности пользователя (открытие интерфейса, запуск воспроизведения).
     * Если с момента последней отправки прошло более 30 минут и пользователь не отказался от сбора аналитики,
     * асинхронно отправляет минимальный heartbeat на сервер.
     */
    @JvmOverloads
    fun notifyUserActivity(endpointUrl: String = DEFAULT_HEARTBEAT_ENDPOINT) {
        val lifecycle = VoxDiagnosticsLifecycleManager.instance(context)
        if (isAnalyticsOptedOut() || lifecycle.isUserOptedOut()) {
            Log.d(TAG, "Heartbeat suppressed: user opted out from telemetry/analytics.")
            return
        }

        val now = System.currentTimeMillis()
        val lastSent = getLastHeartbeatSentMs()
        if (now - lastSent < HEARTBEAT_INTERVAL_MS) {
            // Менее 30 минут с прошлой отправки
            return
        }

        if (!isSending.compareAndSet(false, true)) {
            return
        }

        if (directDispatchForTesting) {
            try {
                sendHeartbeatInternal(endpointUrl, now)
            } finally {
                isSending.set(false)
            }
            return
        }

        EXECUTOR.execute {
            try {
                sendHeartbeatInternal(endpointUrl, now)
            } finally {
                isSending.set(false)
            }
        }
    }

    private fun sendHeartbeatInternal(endpointUrl: String, timestampMs: Long) {
        val installationId = VoxInstallationIdManager.getInstance(context).getInstallationId()
        val pInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
        val appVer = pInfo?.versionName ?: "32.56-vox.9.2"

        val payload = JSONObject().apply {
            put("installationId", installationId)
            put("appVersion", appVer)
            put("platform", "Android TV")
            put("manufacturer", Build.MANUFACTURER ?: "")
            put("model", Build.MODEL ?: "")
            put("sdkInt", Build.VERSION.SDK_INT)
            put("timestamp", timestampMs)
        }

        if (directDispatchForTesting || isRunningInUnitTest()) {
            // В юнит-тестах не выполняем реальных сетевых подключений
            setLastHeartbeatSentMs(timestampMs)
            lastSentPayloadForTesting = payload.toString()
            Log.d(TAG, "[UnitTest] Heartbeat captured: $payload")
            return
        }

        var conn: HttpURLConnection? = null
        try {
            val url = URL(endpointUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", "SmartTube-VOX/$appVer (Heartbeat)")
            }

            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                setLastHeartbeatSentMs(timestampMs)
                Log.d(TAG, "Heartbeat accepted by server (HTTP $code).")
            } else if (code == 404) {
                // Старый Cloudflare Worker без эндпоинта /v1/analytics/heartbeat.
                // Отмечаем время отправки, чтобы не спамить повторными запросами.
                setLastHeartbeatSentMs(timestampMs)
                Log.d(TAG, "Heartbeat endpoint not available on server (HTTP 404). Fallback active.")
            } else {
                Log.w(TAG, "Heartbeat responded with HTTP $code, will re-try on next active cycle.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed silently without retry: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    fun getLastHeartbeatSentMs(): Long {
        return prefs.getLong(KEY_LAST_HEARTBEAT_MS, 0L)
    }

    fun setLastHeartbeatSentMs(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_HEARTBEAT_MS, timestampMs).apply()
    }

    fun isAnalyticsOptedOut(): Boolean {
        return prefs.getBoolean(KEY_ANALYTICS_OPT_OUT, false)
    }

    fun setAnalyticsOptedOut(optOut: Boolean) {
        prefs.edit().putBoolean(KEY_ANALYTICS_OPT_OUT, optOut).apply()
    }

    fun resetForTesting(directDispatch: Boolean = true) {
        prefs.edit().clear().commit()
        lastSentPayloadForTesting = null
        directDispatchForTesting = directDispatch
        isSending.set(false)
    }

    companion object {
        private const val TAG = "VoxHeartbeatManager"
        const val PREFS_NAME = "vox_heartbeat_prefs"
        const val KEY_LAST_HEARTBEAT_MS = "last_heartbeat_sent_ms"
        const val KEY_ANALYTICS_OPT_OUT = "analytics_user_opt_out"
        const val DEFAULT_HEARTBEAT_ENDPOINT = "https://vox-diagnostics.amn2402.workers.dev/v1/analytics/heartbeat"
        const val HEARTBEAT_INTERVAL_MS = 30 * 60 * 1000L // 30 минут
        private const val TIMEOUT_MS = 5000

        private val EXECUTOR = Executors.newSingleThreadExecutor()

        @Volatile
        private var sInstance: VoxHeartbeatManager? = null

        @Volatile
        internal var lastSentPayloadForTesting: String? = null

        @Volatile
        internal var directDispatchForTesting: Boolean = false

        @JvmStatic
        fun getInstance(context: Context): VoxHeartbeatManager {
            return sInstance ?: synchronized(this) {
                sInstance ?: VoxHeartbeatManager(context.applicationContext).also { sInstance = it }
            }
        }

        private fun isRunningInUnitTest(): Boolean {
            val fingerprint = Build.FINGERPRINT?.lowercase() ?: ""
            return fingerprint.contains("robolectric") ||
                Build.HARDWARE?.lowercase() == "goldfish" && Build.BRAND == "Android" && !Build.PRODUCT.contains("sdk") ||
                System.getProperty("java.vm.name", "").contains("OpenJDK") && Build.DEVICE == "robolectric" ||
                Build.DEVICE?.lowercase() == "robolectric"
        }
    }
}
