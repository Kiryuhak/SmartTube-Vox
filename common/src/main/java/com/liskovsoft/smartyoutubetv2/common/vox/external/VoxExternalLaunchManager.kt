package com.liskovsoft.smartyoutubetv2.common.vox.external

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter
import com.liskovsoft.smartyoutubetv2.common.prefs.RemoteControlData

/**
 * Менеджер внешнего запуска видео и DIAL-сервера.
 * 
 * Отвечает за:
 * 1. Запуск и остановку HTTP/DIAL сервера и SSDP-ответчика.
 * 2. Дедупликацию повторных внешних запросов (окно 3 секунды).
 * 3. Безопасную передачу команды воспроизведения в ExoPlayer / PlaybackPresenter на главном потоке.
 */
class VoxExternalLaunchManager internal constructor(
    private val appContext: Context,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        private val TAG = VoxExternalLaunchManager::class.java.simpleName
        /** Окно дедупликации: 3 секунды */
        const val DEDUP_WINDOW_MS = 3000L
        /**
         * Два запуска одного видео с разницей по времени более 5 секунд
         * считаются РАЗНЫМИ (пользователь намеренно перематывает на другую позицию).
         */
        const val DEDUP_TIME_TOLERANCE_MS = 5000L

        @Volatile
        private var instance: VoxExternalLaunchManager? = null

        @JvmStatic
        fun instance(context: Context): VoxExternalLaunchManager {
            return instance ?: synchronized(this) {
                instance ?: VoxExternalLaunchManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val deviceIdentity = VoxDeviceIdentity(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var dialServer: VoxDialServer? = null
    private var ssdpResponder: VoxSsdpResponder? = null

    @Volatile
    private var lastLaunchedVideoId: String? = null

    @Volatile
    private var lastLaunchTimeMs: Long = 0L

    /** Позиция видео (timeMs) из последнего принятого запроса. */
    @Volatile
    private var lastLaunchedPositionMs: Long = -1L


    @Synchronized
    fun start(): Boolean {
        val prefs = RemoteControlData.instance(appContext)
        if (!prefs.isExternalLaunchEnabled) {
            VoxLog.d(TAG, "External launch is disabled in settings, skipping start")
            return false
        }

        if (dialServer?.isRunning() == true) {
            VoxLog.d(TAG, "DIAL server already running")
            return true
        }

        VoxLog.i(TAG, "Starting External Launch / DIAL services...")

        val server = VoxDialServer(
            deviceIdentity = deviceIdentity,
            preferredPort = 8081,
            onVideoLaunch = { request -> handleVideoLaunch(request) },
            onVideoStop = { handleVideoStop() },
            isVideoRunningProvider = { isVideoCurrentlyPlaying() }
        )

        if (server.start()) {
            dialServer = server
            val responder = VoxSsdpResponder(
                context = appContext,
                deviceIdentity = deviceIdentity,
                httpPortProvider = { server.getPort() }
            )
            responder.start()
            ssdpResponder = responder
            VoxLog.i(TAG, "External Launch / DIAL services started successfully on port ${server.getPort()}")
            return true
        } else {
            VoxLog.e(TAG, "Failed to start DIAL HTTP server")
            return false
        }
    }

    @Synchronized
    fun stop() {
        VoxLog.i(TAG, "Stopping External Launch / DIAL services...")
        ssdpResponder?.stop()
        ssdpResponder = null

        dialServer?.stop()
        dialServer = null
    }

    fun isRunning(): Boolean = dialServer?.isRunning() == true

    fun getServerPort(): Int = dialServer?.getPort() ?: 0

    @Synchronized
    fun syncWithSettings(): Boolean {
        val prefs = RemoteControlData.instance(appContext)
        return if (prefs.isExternalLaunchEnabled) {
            if (!isRunning()) {
                start()
            } else {
                true
            }
        } else {
            if (isRunning()) {
                stop()
            }
            true
        }
    }


    /**
     * Проверяет, является ли запрос дубликатом последнего запуска, без изменения состояния.
     * Возвращает true, если запрос считается дубликатом.
     */
    internal fun isDuplicate(videoId: String, requestedPositionMs: Long, currentTimeMs: Long = clock()): Boolean {
        synchronized(this) {
            val sameVideo = videoId == lastLaunchedVideoId
            val withinWindow = (currentTimeMs - lastLaunchTimeMs) < DEDUP_WINDOW_MS
            val positionSimilar = kotlin.math.abs(requestedPositionMs - lastLaunchedPositionMs) < DEDUP_TIME_TOLERANCE_MS
            return sameVideo && withinWindow && positionSimilar
        }
    }

    /**
     * Обработка внешнего запроса запуска видео с дедупликацией.
     * Возвращает true при успешном приёме (включая подавленный дубликат как no-op success).
     */
    internal fun handleVideoLaunch(request: VoxExternalVideoRequest, dispatchPlayback: Boolean = true): Boolean {
        val now = clock()
        val videoId = request.videoId
        val requestedPositionMs = request.timeMs

        synchronized(this) {
            val sameVideo = videoId == lastLaunchedVideoId
            val withinWindow = (now - lastLaunchTimeMs) < DEDUP_WINDOW_MS
            val positionSimilar = kotlin.math.abs(requestedPositionMs - lastLaunchedPositionMs) < DEDUP_TIME_TOLERANCE_MS

            if (sameVideo && withinWindow && positionSimilar) {
                VoxLog.i(TAG, "Ignoring duplicate video launch for $videoId (within ${now - lastLaunchTimeMs}ms, position diff=${kotlin.math.abs(requestedPositionMs - lastLaunchedPositionMs)}ms)")
                return true // Duplicate accepted cleanly as no-op per DIAL spec (201 Created returned to sender)
            }
            lastLaunchedVideoId = videoId
            lastLaunchTimeMs = now
            lastLaunchedPositionMs = requestedPositionMs
        }

        if (dispatchPlayback) {
            mainHandler.post {
                try {
                    VoxLog.i(TAG, "Opening video from external DIAL request: $videoId (timeMs=${request.timeMs})")
                    val playbackPresenter = PlaybackPresenter.instance(appContext)
                    playbackPresenter.openVideo(videoId, false, request.timeMs, false)
                } catch (e: Exception) {
                    VoxLog.e(TAG, "Error executing video playback for $videoId: ${e.message}")
                }
            }
        }

        return true
    }




    private fun handleVideoStop() {
        mainHandler.post {
            try {
                PlaybackPresenter.instance(appContext).forceFinish()
            } catch (e: Exception) {
                VoxLog.w(TAG, "Error stopping playback: ${e.message}")
            }
        }
    }

    private fun isVideoCurrentlyPlaying(): Boolean {
        return try {
            PlaybackPresenter.instance(appContext).isPlaying
        } catch (e: Exception) {
            false
        }
    }
}
