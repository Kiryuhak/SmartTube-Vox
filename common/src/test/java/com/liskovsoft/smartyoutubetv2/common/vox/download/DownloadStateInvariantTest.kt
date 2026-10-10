package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DownloadStateInvariantTest {

    private lateinit var storage: VoxDownloadStorage
    private lateinit var downloadId: String
    private lateinit var request: VoxDownloadRequest

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        storage = VoxDownloadStorage(context)
        downloadId = "test_invariant_job_1"
        request = VoxDownloadRequest(
            downloadId = downloadId,
            videoId = "vid_123",
            videoTitle = "Test Video",
            qualityPreference = VoxQualityPreference.QUALITY_1080P,
            translationMode = VoxTranslationMode.STANDARD
        )
    }

    @Test
    fun validCompletedJobRestoresAsCompleted() {
        val outputFile = storage.getOutputFile(downloadId)
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(ByteArray(1024)) // 1KB valid file

        val vProgress = VoxTrackProgress(VoxDownloadTrack.VIDEO, 1024, 1024, VoxTrackState.COMPLETED)
        val oProgress = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, 512, 512, VoxTrackState.COMPLETED)
        val tProgress = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO, 256, 256, VoxTrackState.COMPLETED)

        storage.saveJobMetadata(
            request = request,
            state = VoxDownloadState.COMPLETED,
            videoProgress = vProgress,
            originalAudioProgress = oProgress,
            translatedAudioProgress = tProgress,
            hasTranslatedAudio = true,
            finalFileBytes = 1024L,
            durationMs = 60_000L,
            packagingStarted = true,
            packagingCompleted = true,
            finalizeCompleted = true
        )

        val loaded = storage.loadJobMetadata(downloadId)
        assertNotNull(loaded)
        assertEquals(VoxDownloadState.COMPLETED, loaded!!.state)
        assertTrue(loaded.packagingCompleted)
        assertTrue(loaded.finalizeCompleted)
        assertTrue(loaded.hasTranslatedAudio)
    }

    @Test
    fun completedJobWithoutPackagingIsRepairedWhenFileIsValid() {
        // Create valid file on disk but job.json has packagingCompleted = false (legacy/interrupted metadata)
        val outputFile = storage.getOutputFile(downloadId)
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(ByteArray(1024))

        val vProgress = VoxTrackProgress(VoxDownloadTrack.VIDEO, 1024, 1024, VoxTrackState.COMPLETED)
        val oProgress = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, 512, 512, VoxTrackState.COMPLETED)
        val tProgress = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO, 256, 256, VoxTrackState.COMPLETED)

        // Write directly inconsistent json to disk simulating legacy report
        val jobDir = storage.getJobDir(downloadId)
        val json = org.json.JSONObject().apply {
            put("downloadId", downloadId)
            put("videoId", "vid_123")
            put("videoTitle", "Test Video")
            put("qualityPreference", "QUALITY_1080P")
            put("translationMode", "STANDARD")
            put("translationState", "DOWNLOADED_TRANSLATED")
            put("createdAt", System.currentTimeMillis())
            put("state", "COMPLETED")
            put("hasTranslatedAudio", true)
            put("finalFileBytes", 1024L)
            put("packagingStarted", true)
            put("packagingCompleted", false) // legacy/interrupted metadata
            put("finalizeCompleted", false)
            put("durationMs", 60_000L)
            put("video", org.json.JSONObject().apply {
                put("bytes", 1024L); put("total", 1024L); put("state", "COMPLETED")
            })
            put("originalAudio", org.json.JSONObject().apply {
                put("bytes", 512L); put("total", 512L); put("state", "COMPLETED")
            })
            put("translatedAudio", org.json.JSONObject().apply {
                put("bytes", 256L); put("total", 256L); put("state", "COMPLETED")
            })
        }
        File(jobDir, "job.json").writeText(json.toString())

        val loaded = storage.loadJobMetadata(downloadId)
        assertNotNull(loaded)
        // Must be REPAIRED to COMPLETED because file is physically valid on disk!
        assertEquals(VoxDownloadState.COMPLETED, loaded!!.state)
        assertTrue(loaded.packagingCompleted)
        assertTrue(loaded.finalizeCompleted)
    }

    @Test
    fun completedJobWithoutFileFailsInvariantCheck() {
        // File does NOT exist on disk and finalFileBytes is 0
        val jobDir = storage.getJobDir(downloadId)
        jobDir.mkdirs()
        val json = org.json.JSONObject().apply {
            put("downloadId", downloadId)
            put("videoId", "vid_123")
            put("videoTitle", "Test Video")
            put("qualityPreference", "QUALITY_1080P")
            put("translationMode", "STANDARD")
            put("translationState", "DOWNLOADED_TRANSLATED")
            put("createdAt", System.currentTimeMillis())
            put("state", "COMPLETED")
            put("hasTranslatedAudio", true)
            put("finalFileBytes", 0L)
            put("packagingStarted", true)
            put("packagingCompleted", true)
            put("finalizeCompleted", true)
            put("durationMs", 60_000L)
            put("video", org.json.JSONObject().apply {
                put("bytes", 1024L); put("total", 1024L); put("state", "COMPLETED")
            })
            put("originalAudio", org.json.JSONObject().apply {
                put("bytes", 512L); put("total", 512L); put("state", "COMPLETED")
            })
            put("translatedAudio", org.json.JSONObject().apply {
                put("bytes", 256L); put("total", 256L); put("state", "COMPLETED")
            })
        }
        File(jobDir, "job.json").writeText(json.toString())

        val loaded = storage.loadJobMetadata(downloadId)
        assertNotNull(loaded)
        assertEquals(VoxDownloadState.FAILED, loaded!!.state)
        assertEquals(VoxDownloadErrorCode.STORAGE_ERROR, loaded.errorCode)
        assertTrue(loaded.errorMessage?.contains("DOWNLOAD_STATE_INCONSISTENT") == true)
    }

    @Test
    fun completionValidatorRejectsIncompletePackagingOrFinalize() {
        val job = VoxDownloadJob(
            request = request,
            initialState = VoxDownloadState.FINALIZING,
            initialVideo = VoxTrackProgress(VoxDownloadTrack.VIDEO, 1024, 1024, VoxTrackState.COMPLETED),
            initialOriginalAudio = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, 512, 512, VoxTrackState.COMPLETED),
            initialTranslatedAudio = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO, 256, 256, VoxTrackState.COMPLETED)
        )
        job.publishedUri = "file:///dummy/path.mkv"
        job.finalFileBytes = 1024L
        job.durationMs = 60_000L
        job.hasTranslatedAudio = true
        job.packagingCompleted = false // incomplete
        job.finalizeCompleted = true

        try {
            VoxDownloadCompletionValidator.check(job, 1024L)
            fail("Should throw exception when packagingCompleted is false")
        } catch (e: VoxDownloadException) {
            assertTrue(e.message?.contains("DOWNLOAD_STATE_INCONSISTENT") == true)
        }
    }
}
