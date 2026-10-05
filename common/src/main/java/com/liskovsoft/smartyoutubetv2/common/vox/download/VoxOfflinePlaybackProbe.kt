package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger

/**
 * Диагностический зонд наблюдаемости (Observability Probe) для локального воспроизведения.
 * Безопасно собирает метаданные треков, рендерера, шкалы времени и прогресса,
 * обнаруживая black-screen и timeline anomalies без логирования PII или путей к файлам.
 */
class VoxOfflinePlaybackProbe {

    var isLocal: Boolean = false
        private set
    var hasTranslatedAudio: Boolean = false
        private set
    var sourceScheme: String = "unknown"
        private set
    var containerCategory: String = "UNKNOWN"
        private set
    var storedDurationMs: Long = 0L
        private set
    var actualQuality: String? = null
        private set
    var ageMetadataPresent: Boolean = false
        private set

    var videoTrackCount: Int = 0
        private set
    var audioTrackCount: Int = 0
        private set
    var translatedTrackPresent: Boolean = false
        private set
    var translatedTrackSelected: Boolean = false
        private set
    var videoMimeCategory: String = "NONE"
        private set
    var audioMimeCategory: String = "NONE"
        private set

    var videoRendererEnabled: Boolean = false
        private set
    var firstFrameRendered: Boolean = false
        private set
    var timelineReady: Boolean = false
        private set
    var durationKnown: Boolean = false
        private set
    var durationMs: Long = 0L
        private set
    var isSeekable: Boolean = false
        private set
    var isDynamic: Boolean = false
        private set
    var positionAdvancing: Boolean = false
        private set

    var possibleBlackScreen: Boolean = false
        private set
    var timelineFailure: Boolean = false
        private set

    private var initialPositionSample: Long = -1L
    private var initialSampleTimeMs: Long = 0L
    private var sampleCount: Int = 0

    fun onBeforeOpen(video: Video?, uriString: String?) {
        if (video == null) return
        isLocal = video.isLocal
        hasTranslatedAudio = video.isDownloadedTranslated
        actualQuality = video.badge
        ageMetadataPresent = !video.ageRating.isNullOrBlank()
        storedDurationMs = video.durationMs

        sourceScheme = when {
            uriString == null -> "none"
            uriString.startsWith("content://") -> "content"
            uriString.startsWith("file://") -> "file"
            uriString.startsWith("http://") || uriString.startsWith("https://") -> "http"
            else -> "other"
        }

        containerCategory = when {
            uriString == null -> "UNKNOWN"
            uriString.contains(".mkv", ignoreCase = true) || uriString.contains("matroska", ignoreCase = true) -> "MATROSKA"
            uriString.contains(".mp4", ignoreCase = true) -> "MP4"
            else -> "OTHER"
        }
    }

    fun onMediaSourceCreated(dataSourceType: String, containerType: String) {
        if (!isLocal) return
        if (containerCategory == "UNKNOWN") {
            containerCategory = when {
                containerType.contains("Matroska", ignoreCase = true) -> "MATROSKA"
                containerType.contains("Mp4", ignoreCase = true) -> "MP4"
                else -> containerType.uppercase()
            }
        }
    }

    fun onTracksDiscovered(
        videoTracks: Int,
        audioTracks: Int,
        videoMime: String?,
        audioMime: String?,
        hasTranslated: Boolean,
        isTranslatedSelected: Boolean
    ) {
        if (!isLocal) return
        videoTrackCount = videoTracks
        audioTrackCount = audioTracks
        translatedTrackPresent = hasTranslated
        translatedTrackSelected = isTranslatedSelected

        videoMimeCategory = categorizeVideoMime(videoMime)
        audioMimeCategory = categorizeAudioMime(audioMime)

        if (videoTracks > 0) {
            videoRendererEnabled = true
            VoxSafeLogger.info(
                VoxLogCategory.PLAYER,
                VoxLogCode.OFFLINE_VIDEO_TRACK_READY,
                "Offline video tracks ready: count=$videoTracks, mime=$videoMimeCategory"
            )
        }
    }

    fun onFirstFrameRendered() {
        if (!isLocal) return
        firstFrameRendered = true
        possibleBlackScreen = false

        VoxSafeLogger.info(
            VoxLogCategory.PLAYER,
            VoxLogCode.OFFLINE_FIRST_FRAME_RENDERED,
            "First frame rendered successfully for offline playback"
        )
    }

    fun onTimelineChanged(timelineDurationMs: Long, seekable: Boolean, dynamic: Boolean) {
        if (!isLocal) return
        timelineReady = true
        durationMs = timelineDurationMs
        durationKnown = timelineDurationMs > 0
        isSeekable = seekable
        isDynamic = dynamic

        if (!durationKnown && storedDurationMs > 0) {
            durationMs = storedDurationMs
            durationKnown = true
        }

        timelineFailure = !durationKnown && timelineDurationMs <= 0 && storedDurationMs <= 0

        VoxSafeLogger.info(
            VoxLogCategory.PLAYER,
            VoxLogCode.OFFLINE_TIMELINE_READY,
            "Offline timeline ready: durationMs=$durationMs, seekable=$seekable, dynamic=$dynamic"
        )
    }

    fun onPositionSample(currentPosMs: Long, isPlaying: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (!isLocal || !isPlaying) return

        if (initialPositionSample < 0) {
            initialPositionSample = currentPosMs
            initialSampleTimeMs = nowMs
            sampleCount = 1
            return
        }

        sampleCount++
        val elapsedWallMs = nowMs - initialSampleTimeMs
        val deltaMediaMs = currentPosMs - initialPositionSample

        if (elapsedWallMs >= 1000L && deltaMediaMs >= 500L) {
            if (!positionAdvancing) {
                positionAdvancing = true
                VoxSafeLogger.info(
                    VoxLogCategory.PLAYER,
                    VoxLogCode.OFFLINE_POSITION_ADVANCING,
                    "Offline playback position is advancing normally (deltaMediaMs=$deltaMediaMs in elapsedWallMs=$elapsedWallMs)"
                )
            }
        }
    }

    fun onSeekRequested(fromPosMs: Long, targetPosMs: Long) {
        if (!isLocal) return
        VoxSafeLogger.info(
            VoxLogCategory.PLAYER,
            VoxLogCode.OFFLINE_SEEK_REQUESTED,
            "Offline seek requested: fromBucket=${fromPosMs / 1000}s, toBucket=${targetPosMs / 1000}s"
        )
    }

    fun onSeekCompleted(settledPosMs: Long) {
        if (!isLocal) return
        VoxSafeLogger.info(
            VoxLogCategory.PLAYER,
            VoxLogCode.OFFLINE_SEEK_COMPLETED,
            "Offline seek completed at ${settledPosMs / 1000}s"
        )
    }

    fun checkBlackScreenCondition(isPlaying: Boolean, timeSinceStartMs: Long): Boolean {
        if (!isLocal) return false

        if (isPlaying && videoTrackCount > 0 && !firstFrameRendered && timeSinceStartMs >= 3500L) {
            possibleBlackScreen = true
            VoxSafeLogger.warn(
                VoxLogCategory.PLAYER,
                VoxLogCode.OFFLINE_POSSIBLE_BLACK_SCREEN,
                "Possible offline black screen: playing=true, videoTracks=$videoTrackCount, firstFrameRendered=false after ${timeSinceStartMs}ms"
            )
            return true
        }

        return false
    }

    fun toSafeDiagnosticMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["isLocal"] = isLocal
        map["hasTranslatedAudio"] = hasTranslatedAudio
        map["sourceScheme"] = sourceScheme
        map["containerCategory"] = containerCategory
        map["videoTrackCount"] = videoTrackCount
        map["audioTrackCount"] = audioTrackCount
        map["translatedTrackPresent"] = translatedTrackPresent
        map["translatedTrackSelected"] = translatedTrackSelected
        map["videoMimeCategory"] = videoMimeCategory
        map["audioMimeCategory"] = audioMimeCategory
        map["firstFrameRendered"] = firstFrameRendered
        map["timelineReady"] = timelineReady
        map["durationKnown"] = durationKnown
        map["isSeekable"] = isSeekable
        map["positionAdvancing"] = positionAdvancing
        map["possibleBlackScreen"] = possibleBlackScreen
        return map
    }

    private fun categorizeVideoMime(mime: String?): String {
        if (mime == null) return "NONE"
        val lower = mime.lowercase()
        return when {
            lower.contains("av01") || lower.contains("av1") -> "AV1"
            lower.contains("vp9") || lower.contains("vp09") -> "VP9"
            lower.contains("hevc") || lower.contains("h265") || lower.contains("265") -> "HEVC"
            lower.contains("avc") || lower.contains("h264") || lower.contains("264") -> "AVC"
            else -> "OTHER"
        }
    }

    private fun categorizeAudioMime(mime: String?): String {
        if (mime == null) return "NONE"
        val lower = mime.lowercase()
        return when {
            lower.contains("opus") -> "OPUS"
            lower.contains("mp4a") || lower.contains("aac") -> "AAC"
            lower.contains("eac3") || lower.contains("ec-3") -> "EAC3"
            lower.contains("ac3") || lower.contains("ac-3") -> "AC3"
            lower.contains("mp3") || lower.contains("mpeg") -> "MP3"
            else -> "OTHER"
        }
    }
}
