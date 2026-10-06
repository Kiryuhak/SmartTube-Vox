package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.*
import org.junit.Test

class VoxDownloadCompletionValidatorTest {
    private fun ready() = VoxDownloadJob(VoxDownloadRequest(videoId = "test", videoTitle = "Test")).apply {
        VoxDownloadTrack.values().forEach { updateTrackProgress(it, 100, 100, VoxTrackState.COMPLETED) }
        hasTranslatedAudio = true
        durationMs = 240_000
        finalFileBytes = 300
        publishedUri = "content://media/external/video/media/123"
    }

    @Test fun completionRequiresEveryArtifact() {
        VoxDownloadCompletionValidator.check(ready(), 300)
        val invalid = listOf(
            ready().apply { publishedUri = null },
            ready().apply { hasTranslatedAudio = false },
            ready().apply { durationMs = 0 },
            ready().apply { finalFileBytes = 0 },
            ready().apply { updateTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO, 99, 100, VoxTrackState.IN_PROGRESS) }
        )
        for (job in invalid) {
            try { VoxDownloadCompletionValidator.check(job, 300); fail("Incomplete artifact accepted") }
            catch (e: VoxDownloadException) { assertEquals(VoxDownloadErrorCode.STORAGE_ERROR, e.code) }
        }
        for (size in listOf(0L, 299L, 301L)) {
            try { VoxDownloadCompletionValidator.check(ready(), size); fail("Unreadable/truncated artifact accepted") }
            catch (e: VoxDownloadException) { assertEquals(VoxDownloadErrorCode.STORAGE_ERROR, e.code) }
        }
    }

    @Test fun ordinaryDownloadRequiresOnlyVideoAndOriginalAudio() {
        val job = VoxDownloadJob(VoxDownloadRequest(
            videoId = "ordinary", videoTitle = "Ordinary",
            translationMode = VoxTranslationMode.NONE
        )).apply {
            updateTrackProgress(VoxDownloadTrack.VIDEO, 200, 200, VoxTrackState.COMPLETED)
            updateTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, 100, 100, VoxTrackState.COMPLETED)
            durationMs = 240_000
            finalFileBytes = 300
            publishedUri = "content://media/external/video/media/124"
        }
        assertEquals(VoxDownloadTranslationState.NONE, job.translationState)
        assertEquals(300L, job.getSnapshot().totalBytesExpected)
        VoxDownloadCompletionValidator.check(job, 300)
        job.hasTranslatedAudio = true
        try { VoxDownloadCompletionValidator.check(job, 300); fail("Incorrect translated marker accepted") }
        catch (e: VoxDownloadException) { assertEquals(VoxDownloadErrorCode.STORAGE_ERROR, e.code) }
    }
}
