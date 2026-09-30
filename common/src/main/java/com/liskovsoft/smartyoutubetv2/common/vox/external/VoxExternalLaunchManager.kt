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
class VoxExternalLaunchManager private constructor(private val appContext: Context) {

    companion object {
        private val TAG = VoxExternalLaunchManager::class.java.simpleName
        private const val DEDUP_WINDOW_MS = 3000L

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

    @Synchronized
    fun start() {
        val prefs = RemoteControlData.instance(appContext)
        if (!prefs.isExternalLaunchEnabled) {
            VoxLog.d(TAG, "External launch is disabled in settings, skipping start")
            return
        }

        if (dialServer?.isRunning() == true) {
            VoxLog.d(TAG, "DIAL server already running")
            return
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
        } else {
            VoxLog.e(TAG, "Failed to start DIAL HTTP server")
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
    fun syncWithSettings() {
        val prefs = RemoteControlData.instance(appContext)
        if (prefs.isExternalLaunchEnabled) {
            if (!isRunning()) {
                start()
            }
        } else {
            if (isRunning()) {
                stop()
            }
        }
    }

    /**
     * Обработка внешнего запроса запуска видео с дедупликацией.
     */
    private fun handleVideoLaunch(request: VoxExternalVideoRequest): Boolean {
        val now = System.currentTimeMillis()
        val videoId = request.videoId

        synchronized(this) {
            if (videoId == lastLaunchedVideoId && (now - lastLaunchTimeMs) < DEDUP_WINDOW_MS) {
                VoxLog.i(TAG, "Ignoring duplicate video launch for $videoId (within ${now - lastLaunchTimeMs}ms)")
                return true
            }
            lastLaunchedVideoId = videoId
            lastLaunchTimeMs = now
        }

        mainHandler.post {
            try {
                VoxLog.i(TAG, "Opening video from external DIAL request: $videoId (timeMs=${request.timeMs})")
                val playbackPresenter = PlaybackPresenter.instance(appContext)
                playbackPresenter.openVideo(videoId, false, request.timeMs, false)
            } catch (e: Exception) {
                VoxLog.e(TAG, "Error executing video playback for $videoId: ${e.message}")
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
