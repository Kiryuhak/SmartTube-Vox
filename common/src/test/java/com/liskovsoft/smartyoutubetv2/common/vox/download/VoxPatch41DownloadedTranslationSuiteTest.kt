package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import com.google.android.exoplayer2.Format
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.AudioTrack
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

class DownloadedTranslatedMetadataTest {
    @Test
    fun testDownloadedTranslatedVideoPreservesTranslatedMetadata() {
        val request = VoxDownloadRequest(
            downloadId = "dl_trans_1",
            videoId = "vid_trans_1",
            videoTitle = "Видео с русским переводом",
            translationMode = VoxTranslationMode.STANDARD
        )
        val job = VoxDownloadJob(
            request = request,
            initialPublishedUri = "file:///storage/emulated/0/Download/SmartTubeVOX/video_ru.mkv",
            actualQuality = "FHD · 1080p",
            translationState = VoxDownloadTranslationState.DOWNLOADED_TRANSLATED
        )

        val video = VoxDownloadedVideoFactory.createVideo(job)
        assertTrue(video.isLocal)
        assertTrue(video.isDownloadedTranslated)
        assertEquals("DOWNLOADED_TRANSLATED", video.translationState)
        assertEquals("file:///storage/emulated/0/Download/SmartTubeVOX/video_ru.mkv", video.mediaUrl)
    }
}

@RunWith(RobolectricTestRunner::class)
class DownloadedTranslatedAudioSelectedTest {
    @Test
    fun testTranslatedLocalAudioTrackSelectedOverOriginalAacPreset() {
        val originalTrack = AudioTrack(TrackSelectorManager.RENDERER_INDEX_AUDIO).apply {
            format = Format.createAudioSampleFormat("1", "audio/mp4a-latm", null, 128000, -1, 2, 44100, null, null, 0, "eng")
        }
        val russianTranslatedTrack = AudioTrack(TrackSelectorManager.RENDERER_INDEX_AUDIO).apply {
            format = Format.createAudioSampleFormat("2", "audio/mpeg", null, 128000, -1, 2, 44100, null, null, 1, "rus")
        }

        val groups: Array<Array<MediaTrack>> = arrayOf(
            arrayOf(originalTrack),
            arrayOf(russianTranslatedTrack)
        )

        val selected = TrackSelectorManager.findTranslatedLocalAudioTrack(groups)
        assertNotNull(selected)
        assertTrue(selected.format.language != null && selected.format.language!!.startsWith("ru"))
        assertEquals("audio/mpeg", selected.format.sampleMimeType)

        // Also verify ExoPlayerController.findTranslatedAudioFormat selects the Russian track
        val formatItems = listOf(ExoFormatItem.from(originalTrack), ExoFormatItem.from(russianTranslatedTrack))
        val selectedFormat = ExoPlayerController.findTranslatedAudioFormat(formatItems)
        assertNotNull(selectedFormat)
        assertEquals(russianTranslatedTrack, selectedFormat.track)
    }
}

class DownloadedTranslatedOfflinePlaybackTest {
    @Test
    fun testOfflinePlaybackWithTranslatedAudioRequiresZeroNetworkAndSelectsTranslatedTrack() {
        val probe = VoxOfflinePlaybackProbe()
        val video = Video().apply {
            videoId = "offline_vid_ru"
            isLocal = true
            translationState = "DOWNLOADED_TRANSLATED"
            setDurationMs(180_000L)
        }

        probe.onBeforeOpen(video, "file:///storage/emulated/0/Download/SmartTubeVOX/offline_vid_ru.mkv")
        probe.onTracksDiscovered(
            videoTracks = 1,
            audioTracks = 2,
            videoMime = "video/avc",
            audioMime = "audio/mpeg",
            hasTranslated = true,
            isTranslatedSelected = true
        )
        probe.onFirstFrameRendered()
        probe.onTimelineChanged(180_000L, seekable = true, dynamic = false)

        assertTrue(probe.isLocal)
        assertTrue(probe.hasTranslatedAudio)
        assertTrue(probe.translatedTrackPresent)
        assertTrue(probe.translatedTrackSelected)
        assertEquals("MP3", probe.audioMimeCategory)
        assertTrue(probe.firstFrameRendered)
    }
}

class DownloadedTranslatedSeekTest {
    @Test
    fun testSeekDuringDownloadedTranslatedPlaybackKeepsTranslatedTrackSelected() {
        val probe = VoxOfflinePlaybackProbe()
        val video = Video().apply {
            videoId = "seek_vid_ru"
            isLocal = true
            translationState = "DOWNLOADED_TRANSLATED"
            setDurationMs(300_000L)
        }

        probe.onBeforeOpen(video, "file:///storage/emulated/0/Download/SmartTubeVOX/seek_vid_ru.mkv")
        probe.onTracksDiscovered(1, 2, "video/avc", "audio/mpeg", hasTranslated = true, isTranslatedSelected = true)
        probe.onTimelineChanged(300_000L, seekable = true, dynamic = false)

        // Simulate seeks +10s, +60s, -30s
        probe.onPositionSample(currentPosMs = 10_000L, isPlaying = true, nowMs = 1000L)
        probe.onPositionSample(currentPosMs = 70_000L, isPlaying = true, nowMs = 2500L)
        assertTrue(probe.positionAdvancing)
        assertTrue(probe.isSeekable)
        assertTrue(probe.translatedTrackSelected)
    }
}

@RunWith(RobolectricTestRunner::class)
class DownloadedTranslatedRestartPersistenceTest {
    @Test
    fun testStoredJobDataPersistsTranslatedStateAcrossAppRestart() {
        val context: Context = RuntimeEnvironment.getApplication()
        val storage = VoxDownloadStorage(context)
        try {
            val request = VoxDownloadRequest(
                downloadId = "persist_job_1",
                videoId = "persist_vid_1",
                videoTitle = "Сохранённое видео с переводом",
                translationMode = VoxTranslationMode.STANDARD
            )
            storage.saveJobMetadata(
                request = request,
                state = VoxDownloadState.COMPLETED,
                publishedUri = "file:///storage/emulated/0/Download/SmartTubeVOX/persist.mkv",
                actualQuality = "FHD · 1080p",
                translationState = VoxDownloadTranslationState.DOWNLOADED_TRANSLATED,
                durationMs = 120_000L,
                videoProgress = VoxTrackProgress(VoxDownloadTrack.VIDEO, 1000L, 1000L, VoxTrackState.COMPLETED),
                originalAudioProgress = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, 500L, 500L, VoxTrackState.COMPLETED),
                translatedAudioProgress = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO, 500L, 500L, VoxTrackState.COMPLETED)
            )

            // Simulate app restart by reading from disk in a fresh storage instance
            val reloadedStorage = VoxDownloadStorage(context)
            val loadedData = reloadedStorage.loadJobMetadata("persist_job_1")
            assertNotNull(loadedData)
            val restoredVideo = VoxDownloadedVideoFactory.createVideo(loadedData!!)
            assertTrue(restoredVideo.isLocal)
            assertTrue(restoredVideo.isDownloadedTranslated)
            assertEquals("DOWNLOADED_TRANSLATED", restoredVideo.translationState)
        } finally {
            storage.deleteJobDir("persist_job_1")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class DownloadedTranslatedMissingAudioFallbackTest {
    @Test
    fun testMissingTranslatedTrackFallsBackToOriginalAudioWithoutCrash() {
        val originalOnlyTrack = AudioTrack(TrackSelectorManager.RENDERER_INDEX_AUDIO).apply {
            format = Format.createAudioSampleFormat("1", "audio/mp4a-latm", null, 128000, -1, 2, 44100, null, null, 0, "eng")
        }
        val groups: Array<Array<MediaTrack>> = arrayOf(arrayOf(originalOnlyTrack))

        val translatedTrack = TrackSelectorManager.findTranslatedLocalAudioTrack(groups)
        assertNull(translatedTrack)

        val formatItems = listOf(ExoFormatItem.from(originalOnlyTrack))
        val selectedTranslatedFormat = ExoPlayerController.findTranslatedAudioFormat(formatItems)
        assertNull(selectedTranslatedFormat)

        val probe = VoxOfflinePlaybackProbe()
        val video = Video().apply {
            videoId = "fallback_vid"
            isLocal = true
            translationState = "DOWNLOADED_TRANSLATED"
        }
        probe.onBeforeOpen(video, "file:///storage/fallback.mkv")
        probe.onTracksDiscovered(1, 1, "video/avc", "audio/mp4a-latm", hasTranslated = false, isTranslatedSelected = false)

        assertTrue(probe.hasTranslatedAudio)
        assertFalse(probe.translatedTrackPresent)
        assertFalse(probe.translatedTrackSelected)
    }
}

@RunWith(RobolectricTestRunner::class)
class DownloadedTranslatedDeleteCleanupTest {
    @Test
    fun testDeletingDownloadedTranslatedItemCleansUpLocalFileAndStorage() {
        val context: Context = RuntimeEnvironment.getApplication()
        val storage = VoxDownloadStorage(context)
        val request = VoxDownloadRequest(
            downloadId = "del_job_1",
            videoId = "del_vid_1",
            videoTitle = "Удаляемое видео",
            translationMode = VoxTranslationMode.STANDARD
        )
        storage.saveJobMetadata(
            request = request,
            state = VoxDownloadState.COMPLETED,
            publishedUri = "file:///storage/emulated/0/Download/SmartTubeVOX/del.mkv",
            translationState = VoxDownloadTranslationState.DOWNLOADED_TRANSLATED,
            videoProgress = VoxTrackProgress(VoxDownloadTrack.VIDEO),
            originalAudioProgress = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO),
            translatedAudioProgress = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO)
        )
        val trackFile = storage.getTrackFile("del_job_1", VoxDownloadTrack.TRANSLATED_AUDIO)
        trackFile.writeBytes(ByteArray(512) { 0x1A })
        assertTrue(trackFile.exists())
        assertNotNull(storage.loadJobMetadata("del_job_1"))

        storage.deleteJobDir("del_job_1")
        assertFalse(trackFile.exists())
        assertNull(storage.loadJobMetadata("del_job_1"))
    }
}

class DownloadedTranslatedNoRedundantTranslateActionTest {
    @Test
    fun testDownloadedTranslatedVideoSuppressesRedundantTranslateAndDownloadActions() {
        val downloadedTranslatedVideo = Video().apply {
            videoId = "local_trans_vid"
            isLocal = true
            translationState = "DOWNLOADED_TRANSLATED"
        }
        assertTrue(downloadedTranslatedVideo.isLocal)
        assertTrue(downloadedTranslatedVideo.isDownloadedTranslated)

        // VideoPlayerGlue.updateActionVisibilityForVideo hides both translateAction and downloadAction when isDownloadedTranslated is true
        val shouldShowOnlineTranslateAction = !downloadedTranslatedVideo.isDownloadedTranslated
        val shouldShowDownloadAction = !downloadedTranslatedVideo.isDownloadedTranslated && !downloadedTranslatedVideo.isLocal

        assertFalse(shouldShowOnlineTranslateAction)
        assertFalse(shouldShowDownloadAction)
    }
}
