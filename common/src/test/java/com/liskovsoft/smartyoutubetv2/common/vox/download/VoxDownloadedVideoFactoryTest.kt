package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxDownloadedVideoFactoryTest {

    @Test
    fun testCreateVideoFromJob() {
        val request = VoxDownloadRequest(
            downloadId = "job_1",
            videoId = "test_vid_123",
            videoTitle = "Тестовый ролик в 4K",
            qualityPreference = VoxQualityPreference.QUALITY_AUTO
        )
        val job = VoxDownloadJob(
            request = request,
            initialPublishedUri = "content://media/external/video/media/42",
            actualQuality = "4K · 2160p",
            translationState = VoxDownloadTranslationState.DOWNLOADED_TRANSLATED
        )

        val video = VoxDownloadedVideoFactory.createVideo(job)

        assertNotNull(video)
        assertEquals("test_vid_123", video.videoId)
        assertEquals("Тестовый ролик в 4K", video.title)
        assertEquals("content://media/external/video/media/42", video.mediaUrl)
        assertTrue(video.isLocal)
        assertTrue(video.isDownloadedTranslated)
        assertEquals("4K · 2160p", video.badge)
        assertEquals("Скачанные видео", video.category)
    }

    @Test
    fun testCreateVideoFromStoredData() {
        val request = VoxDownloadRequest(
            downloadId = "job_stored_2",
            videoId = "stored_vid_456",
            videoTitle = "Офлайн фильм"
        )
        val data = StoredJobData(
            request = request,
            state = VoxDownloadState.COMPLETED,
            errorCode = null,
            errorMessage = null,
            publishedUri = "file:///storage/emulated/0/Movies/video.mkv",
            actualQuality = "FHD · 1080p",
            translationState = VoxDownloadTranslationState.NONE,
            durationMs = 3600000L,
            ageRating = "16+",
            videoProgress = VoxTrackProgress(VoxDownloadTrack.VIDEO),
            originalAudioProgress = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO),
            translatedAudioProgress = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO)
        )

        val video = VoxDownloadedVideoFactory.createVideo(data)

        assertNotNull(video)
        assertEquals("stored_vid_456", video.videoId)
        assertEquals("Офлайн фильм", video.title)
        assertEquals("file:///storage/emulated/0/Movies/video.mkv", video.mediaUrl)
        assertTrue(video.isLocal)
        assertEquals(false, video.isDownloadedTranslated)
        assertEquals("FHD · 1080p", video.badge)
        assertEquals(3600000L, video.durationMs)
        assertEquals("16+", video.ageRating)
        assertEquals("Скачанные видео", video.category)
    }

    @Test
    fun testUnknownAgeRatingYieldsNull() {
        val request = VoxDownloadRequest(
            downloadId = "job_3",
            videoId = "vid_unknown_age",
            videoTitle = "Ролик без возрастного рейтинга"
        )
        val data = StoredJobData(
            request = request,
            state = VoxDownloadState.COMPLETED,
            errorCode = null,
            errorMessage = null,
            ageRating = null,
            videoProgress = VoxTrackProgress(VoxDownloadTrack.VIDEO),
            originalAudioProgress = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO),
            translatedAudioProgress = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO)
        )

        val video = VoxDownloadedVideoFactory.createVideo(data)
        assertNull(video.ageRating)
    }
}
