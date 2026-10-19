package com.liskovsoft.smartyoutubetv2.common.vox.shorts

import android.os.Handler
import android.os.Looper
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

enum class ShortsTransitionState {
    IDLE,
    TRANSITIONING,
    PREPARING,
    PLAYING,
    FEED_ENDED,
    ERROR
}

data class ShortsTransitionRecord(
    val index: Int,
    val videoAId: String,
    val videoBId: String,
    val endToFirstFrameMs: Long,
    val endToAudioMs: Long,
    val endToPlayingMs: Long,
    val focusOk: Boolean,
    val doubleAdvance: Boolean,
    val staleCallbackIgnored: Boolean
)

class VoxShortsAutoNextController private constructor(
    private var handler: Handler? = null
) {
    companion object {
        private const val TAG = "VoxShortsAutoNext"
        const val MAX_RETRY = 3
        const val MAX_SKIP_COUNT = 3
        const val TRANSITION_HARD_TIMEOUT_MS = 6000L

        @JvmStatic
        val INSTANCE: VoxShortsAutoNextController by lazy {
            VoxShortsAutoNextController(createDefaultHandler())
        }

        @JvmStatic
        fun createForTesting(handler: Handler? = null): VoxShortsAutoNextController {
            return VoxShortsAutoNextController(handler)
        }

        private fun createDefaultHandler(): Handler? {
            return try {
                val looper = Looper.getMainLooper()
                if (looper != null) Handler(looper) else null
            } catch (e: Throwable) {
                null
            }
        }
    }

    private val generationSeq = AtomicLong(0L)
    @Volatile private var activeGeneration: Long = 0L

    @Volatile private var activeVideoId: String? = null
    @Volatile private var currentVideo: Video? = null
    @Volatile private var targetVideo: Video? = null
    @Volatile private var transitionSourceVideoId: String? = null

    @Volatile var state: ShortsTransitionState = ShortsTransitionState.IDLE
        private set

    @Volatile private var isPaused: Boolean = false
    @Volatile private var transitionStartTimeMs: Long = 0L
    @Volatile private var firstFrameTimeMs: Long = 0L
    @Volatile private var audioStartTimeMs: Long = 0L

    @Volatile private var skipCount: Int = 0
    @Volatile private var retryCount: Int = 0
    @Volatile private var staleCallbackCount: Int = 0
    @Volatile private var doubleAdvanceDetected: Boolean = false

    private val transitionHistory = CopyOnWriteArrayList<ShortsTransitionRecord>()

    var onEnsureFocus: (() -> Unit)? = null
    var onTransitionComplete: ((ShortsTransitionRecord) -> Unit)? = null

    private val timeoutRunnable = Runnable {
        handleTransitionTimeout()
    }

    @Synchronized
    fun resetForTesting() {
        cancelTimeout()
        generationSeq.set(0L)
        activeGeneration = 0L
        activeVideoId = null
        currentVideo = null
        targetVideo = null
        transitionSourceVideoId = null
        state = ShortsTransitionState.IDLE
        isPaused = false
        transitionStartTimeMs = 0L
        firstFrameTimeMs = 0L
        audioStartTimeMs = 0L
        skipCount = 0
        retryCount = 0
        staleCallbackCount = 0
        doubleAdvanceDetected = false
        transitionHistory.clear()
    }

    fun getCurrentGeneration(): Long = activeGeneration

    fun getActiveVideoId(): String? = activeVideoId

    fun getTransitionHistory(): List<ShortsTransitionRecord> = transitionHistory.toList()

    fun isShort(video: Video?): Boolean {
        if (video == null) return false
        return video.isShorts || video.belongsToShortsGroup()
    }

    @Synchronized
    fun onNewShort(video: Video?): Long {
        if (video == null) return activeGeneration
        val newGen = generationSeq.incrementAndGet()
        activeGeneration = newGen
        val prevVideoId = activeVideoId ?: "NONE"
        activeVideoId = video.videoId
        currentVideo = video
        cancelTimeout()

        state = ShortsTransitionState.PREPARING
        skipCount = 0
        retryCount = 0
        isPaused = false

        logInfo(
            VoxLogCode.SHORTS_TRANSITION_START,
            "SHORTS_TRANSITION_START generation=$newGen videoId=${video.videoId} prevVideoId=$prevVideoId",
            mapOf("generation" to newGen.toString(), "videoId" to (video.videoId ?: ""), "prevVideoId" to prevVideoId)
        )
        return newGen
    }

    @Synchronized
    fun onShortEnded(
        video: Video?,
        gen: Long,
        onNextAction: () -> Unit
    ): Boolean {
        if (video == null) return false

        // Guard A: Stale generation check
        if (gen != 0L && gen != activeGeneration) {
            recordStaleCallback(gen, "ENDED_STALE_GEN_MISMATCH")
            return false
        }

        // Guard B: Active video ID check
        if (video.videoId != null && activeVideoId != null && video.videoId != activeVideoId) {
            recordStaleCallback(gen, "ENDED_VIDEO_ID_MISMATCH")
            return false
        }

        // Guard C: Idempotent duplicate ended and feed ended check
        if (state == ShortsTransitionState.TRANSITIONING || state == ShortsTransitionState.FEED_ENDED) {
            recordStaleCallback(gen, if (state == ShortsTransitionState.FEED_ENDED) "FEED_ENDED_CALL" else "DUPLICATE_END_CALL")
            return false
        }

        // Guard D: Pause near end guard
        if (isPaused) {
            recordStaleCallback(gen, "PAUSE_NEAR_END")
            return false
        }

        transitionSourceVideoId = activeVideoId ?: video.videoId
        state = ShortsTransitionState.TRANSITIONING
        transitionStartTimeMs = System.currentTimeMillis()
        firstFrameTimeMs = 0L
        audioStartTimeMs = 0L

        logInfo(
            VoxLogCode.SHORTS_END,
            "SHORTS_END videoId=${video.videoId} generation=$activeGeneration",
            mapOf("videoId" to (video.videoId ?: ""), "generation" to activeGeneration.toString())
        )

        scheduleTimeout(TRANSITION_HARD_TIMEOUT_MS)

        onNextAction()
        return true
    }

    @Synchronized
    fun onNextResolved(nextVideo: Video, gen: Long): Boolean {
        if (gen != 0L && gen != activeGeneration) {
            recordStaleCallback(gen, "NEXT_RESOLVE_STALE_GEN")
            return false
        }

        targetVideo = nextVideo
        logInfo(
            VoxLogCode.SHORTS_NEXT_RESOLVED,
            "SHORTS_NEXT_RESOLVED nextVideoId=${nextVideo.videoId} generation=$activeGeneration",
            mapOf("nextVideoId" to (nextVideo.videoId ?: ""), "generation" to activeGeneration.toString())
        )
        return true
    }

    @Synchronized
    fun onManualNext(
        current: Video?,
        onExecuteNext: () -> Unit
    ): Long {
        cancelTimeout()
        transitionSourceVideoId = current?.videoId ?: activeVideoId
        val newGen = generationSeq.incrementAndGet()
        activeGeneration = newGen
        state = ShortsTransitionState.TRANSITIONING
        transitionStartTimeMs = System.currentTimeMillis()
        firstFrameTimeMs = 0L
        audioStartTimeMs = 0L

        logInfo(
            VoxLogCode.SHORTS_TRANSITION_START,
            "SHORTS_TRANSITION_START generation=$newGen isManual=true videoId=${current?.videoId}",
            mapOf("generation" to newGen.toString(), "isManual" to "true", "videoId" to (current?.videoId ?: ""))
        )

        scheduleTimeout(TRANSITION_HARD_TIMEOUT_MS)
        onExecuteNext()
        return newGen
    }

    @Synchronized
    fun onManualPrevious(
        current: Video?,
        onExecutePrevious: () -> Unit
    ): Long {
        cancelTimeout()
        transitionSourceVideoId = current?.videoId ?: activeVideoId
        val newGen = generationSeq.incrementAndGet()
        activeGeneration = newGen
        state = ShortsTransitionState.TRANSITIONING
        transitionStartTimeMs = System.currentTimeMillis()
        firstFrameTimeMs = 0L
        audioStartTimeMs = 0L

        logInfo(
            VoxLogCode.SHORTS_TRANSITION_START,
            "SHORTS_TRANSITION_START generation=$newGen isManualPrevious=true videoId=${current?.videoId}",
            mapOf("generation" to newGen.toString(), "isManualPrevious" to "true", "videoId" to (current?.videoId ?: ""))
        )

        scheduleTimeout(TRANSITION_HARD_TIMEOUT_MS)
        onExecutePrevious()
        return newGen
    }

    @Synchronized
    fun onPlayerPrepared(video: Video?, gen: Long): Boolean {
        if (gen != 0L && gen != activeGeneration) {
            recordStaleCallback(gen, "PREPARED_STALE_GEN")
            return false
        }

        logInfo(
            VoxLogCode.SHORTS_PLAYER_PREPARED,
            "SHORTS_PLAYER_PREPARED videoId=${video?.videoId} generation=$activeGeneration",
            mapOf("videoId" to (video?.videoId ?: ""), "generation" to activeGeneration.toString())
        )
        return true
    }

    @Synchronized
    fun onFirstFrame(video: Video?, gen: Long = 0L): Boolean {
        val effectiveGen = if (gen != 0L) gen else activeGeneration
        if (effectiveGen != activeGeneration) {
            recordStaleCallback(effectiveGen, "FIRST_FRAME_STALE_GEN")
            return false
        }

        firstFrameTimeMs = System.currentTimeMillis()
        state = ShortsTransitionState.PLAYING
        cancelTimeout()

        val endToFirstFrameMs = if (transitionStartTimeMs > 0) firstFrameTimeMs - transitionStartTimeMs else 0L

        logInfo(
            VoxLogCode.SHORTS_FIRST_FRAME,
            "SHORTS_FIRST_FRAME videoId=${video?.videoId} generation=$activeGeneration endToFirstFrameMs=$endToFirstFrameMs",
            mapOf("videoId" to (video?.videoId ?: ""), "generation" to activeGeneration.toString(), "endToFirstFrameMs" to endToFirstFrameMs.toString())
        )

        // Transfer / ensure focus on the active player container
        onEnsureFocus?.invoke()

        finishTransitionRecord(video, endToFirstFrameMs)
        return true
    }

    @Synchronized
    fun onAudioStart(video: Video?, gen: Long = 0L): Boolean {
        val effectiveGen = if (gen != 0L) gen else activeGeneration
        if (effectiveGen != activeGeneration) {
            recordStaleCallback(effectiveGen, "AUDIO_START_STALE_GEN")
            return false
        }

        audioStartTimeMs = System.currentTimeMillis()
        val endToAudioMs = if (transitionStartTimeMs > 0) audioStartTimeMs - transitionStartTimeMs else 0L

        logInfo(
            VoxLogCode.SHORTS_AUDIO_START,
            "SHORTS_AUDIO_START videoId=${video?.videoId} generation=$activeGeneration endToAudioMs=$endToAudioMs",
            mapOf("videoId" to (video?.videoId ?: ""), "generation" to activeGeneration.toString(), "endToAudioMs" to endToAudioMs.toString())
        )
        return true
    }

    @Synchronized
    fun onNextUnavailable(
        failedVideo: Video?,
        gen: Long,
        onTryNext: () -> Unit,
        onFeedEnded: () -> Unit
    ): Boolean {
        if (gen != 0L && gen != activeGeneration) {
            recordStaleCallback(gen, "NEXT_UNAVAILABLE_STALE_GEN")
            return false
        }

        skipCount++
        logInfo(
            VoxLogCode.SHORTS_SKIP_INVALID,
            "SHORTS_SKIP_INVALID skipCount=$skipCount failedVideoId=${failedVideo?.videoId} generation=$activeGeneration",
            mapOf("skipCount" to skipCount.toString(), "failedVideoId" to (failedVideo?.videoId ?: ""))
        )

        if (skipCount <= MAX_SKIP_COUNT) {
            onTryNext()
            return true
        } else {
            onFeedEnd(gen, onFeedEnded)
            return false
        }
    }

    @Synchronized
    fun onFeedEnd(gen: Long, onFeedEndedAction: () -> Unit) {
        if (gen != 0L && gen != activeGeneration) {
            recordStaleCallback(gen, "FEED_END_STALE_GEN")
            return
        }

        state = ShortsTransitionState.FEED_ENDED
        cancelTimeout()

        logInfo(
            VoxLogCode.SHORTS_END_OF_FEED,
            "SHORTS_END_OF_FEED generation=$activeGeneration",
            mapOf("generation" to activeGeneration.toString())
        )
        onFeedEndedAction()
    }

    @Synchronized
    fun onPauseStateChanged(isPausedNow: Boolean) {
        this.isPaused = isPausedNow
    }

    private fun finishTransitionRecord(video: Video?, endToFirstFrameMs: Long) {
        val now = System.currentTimeMillis()
        val endToAudioMs = if (audioStartTimeMs > 0 && transitionStartTimeMs > 0) audioStartTimeMs - transitionStartTimeMs else endToFirstFrameMs
        val endToPlayingMs = if (transitionStartTimeMs > 0) now - transitionStartTimeMs else 0L

        val record = ShortsTransitionRecord(
            index = transitionHistory.size + 1,
            videoAId = transitionSourceVideoId ?: "UNKNOWN_A",
            videoBId = video?.videoId ?: targetVideo?.videoId ?: activeVideoId ?: "UNKNOWN_B",
            endToFirstFrameMs = endToFirstFrameMs,
            endToAudioMs = endToAudioMs,
            endToPlayingMs = endToPlayingMs,
            focusOk = true,
            doubleAdvance = doubleAdvanceDetected,
            staleCallbackIgnored = staleCallbackCount > 0
        )
        transitionHistory.add(record)

        logInfo(
            VoxLogCode.SHORTS_TRANSITION_DONE,
            "SHORTS_TRANSITION_DONE videoId=${record.videoBId} index=${record.index} endToFirstFrameMs=$endToFirstFrameMs endToAudioMs=$endToAudioMs",
            mapOf(
                "videoId" to record.videoBId,
                "index" to record.index.toString(),
                "endToFirstFrameMs" to endToFirstFrameMs.toString(),
                "endToAudioMs" to endToAudioMs.toString()
            )
        )

        onTransitionComplete?.invoke(record)
    }

    private fun handleTransitionTimeout() {
        synchronized(this) {
            if (state == ShortsTransitionState.TRANSITIONING || state == ShortsTransitionState.PREPARING) {
                retryCount++
                logInfo(
                    VoxLogCode.SHORTS_TRANSITION_TIMEOUT,
                    "SHORTS_TRANSITION_TIMEOUT generation=$activeGeneration retryCount=$retryCount",
                    mapOf("generation" to activeGeneration.toString(), "retryCount" to retryCount.toString())
                )
                if (retryCount > MAX_RETRY) {
                    state = ShortsTransitionState.ERROR
                }
            }
        }
    }

    private fun recordStaleCallback(gen: Long, reason: String) {
        staleCallbackCount++
        logInfo(
            VoxLogCode.SHORTS_STALE_EVENT_IGNORED,
            "SHORTS_STALE_EVENT_IGNORED generation=$gen current=$activeGeneration reason=$reason",
            mapOf("generation" to gen.toString(), "current" to activeGeneration.toString(), "reason" to reason)
        )
    }

    private fun scheduleTimeout(delayMs: Long) {
        cancelTimeout()
        handler?.postDelayed(timeoutRunnable, delayMs)
    }

    private fun cancelTimeout() {
        handler?.removeCallbacks(timeoutRunnable)
    }

    private fun logInfo(code: String, message: String, ctx: Map<String, String>) {
        try {
            Log.i(TAG, message)
        } catch (t: Throwable) {
            println("[$TAG] $message")
        }
        try {
            VoxSafeLogger.info(VoxLogCategory.PLAYER, code, message, ctx)
        } catch (t: Throwable) {
            // Safe fallback if logger not ready in isolated unit test
        }
    }
}
