package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class VoxDownloadStorageTest {

    private lateinit var context: Context
    private lateinit var storage: VoxDownloadStorage
    private lateinit var testTempDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        testTempDir = File(context.filesDir, "test-vox-downloads")
        testTempDir.mkdirs()
        storage = VoxDownloadStorage(context)
    }

    @After
    fun tearDown() {
        testTempDir.deleteRecursively()
        storage.baseDir.deleteRecursively()
    }

    @Test
    fun testSaveAndLoadMetadata() {
        val req = VoxDownloadRequest(
            downloadId = "test-download-123",
            videoId = "UF8uR6Z6KLc",
            videoTitle = "Sample Video",
            qualityPreference = VoxQualityPreference.QUALITY_720P,
            translationMode = VoxTranslationMode.STANDARD
        )

        val vProg = VoxTrackProgress(VoxDownloadTrack.VIDEO, bytesDownloaded = 1000L, totalBytes = 5000L, state = VoxTrackState.IN_PROGRESS)
        val oProg = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, bytesDownloaded = 500L, totalBytes = 1000L, state = VoxTrackState.IN_PROGRESS)
        val tProg = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO, bytesDownloaded = 200L, totalBytes = 200L, state = VoxTrackState.COMPLETED)

        storage.saveJobMetadata(
            request = req,
            state = VoxDownloadState.DOWNLOADING_VIDEO,
            videoProgress = vProg,
            originalAudioProgress = oProg,
            translatedAudioProgress = tProg
        )

        val loaded = storage.loadJobMetadata("test-download-123")
        assertNotNull(loaded)
        assertEquals("test-download-123", loaded!!.request.downloadId)
        assertEquals("UF8uR6Z6KLc", loaded.request.videoId)
        assertEquals("Sample Video", loaded.request.videoTitle)
        assertEquals(VoxQualityPreference.QUALITY_720P, loaded.request.qualityPreference)
        assertEquals(VoxDownloadState.DOWNLOADING_VIDEO, loaded.state)

        assertEquals(1000L, loaded.videoProgress.bytesDownloaded)
        assertEquals(5000L, loaded.videoProgress.totalBytes)
        assertEquals(VoxTrackState.IN_PROGRESS, loaded.videoProgress.state)

        assertEquals(500L, loaded.originalAudioProgress.bytesDownloaded)
        assertEquals(200L, loaded.translatedAudioProgress.bytesDownloaded)
        assertEquals(VoxTrackState.COMPLETED, loaded.translatedAudioProgress.state)

        // Проверяем, что в сохраненном job.json нет конфиденциальных полей (подписанных URL, токенов)
        val rawJson = File(storage.getJobDir("test-download-123"), "job.json").readText()
        assertFalse(rawJson.contains("token", ignoreCase = true))
        assertFalse(rawJson.contains("http://", ignoreCase = true))
        assertFalse(rawJson.contains("https://", ignoreCase = true))
    }

    @Test
    fun testDeleteJob() {
        val req = VoxDownloadRequest(
            downloadId = "test-delete-job",
            videoId = "kJQP7kiw5Fk",
            videoTitle = "Test Delete"
        )
        storage.saveJobMetadata(
            req,
            VoxDownloadState.IDLE,
            VoxTrackProgress(VoxDownloadTrack.VIDEO),
            VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO),
            VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO)
        )

        val trackFile = storage.getTrackFile("test-delete-job", VoxDownloadTrack.VIDEO)
        trackFile.writeText("fake content")
        assertTrue(trackFile.exists())

        val deleted = storage.deleteJobDir("test-delete-job")
        assertTrue(deleted)
        assertFalse(trackFile.exists())
    }

    @Test(expected = VoxDownloadException::class)
    fun testPathTraversalProtectionSlash() {
        storage.getJobDir("../evil_dir")
    }

    @Test(expected = VoxDownloadException::class)
    fun testPathTraversalProtectionBackslash() {
        storage.getJobDir("..\\evil_dir")
    }

    @Test(expected = VoxDownloadException::class)
    fun testPathTraversalProtectionColon() {
        storage.getJobDir("C:evil_dir")
    }

    @Test(expected = VoxDownloadException::class)
    fun testPathTraversalProtectionNullByte() {
        storage.getJobDir("evil\u0000dir")
    }

    @Test
    fun testVideoTitleSafetyWithTraversalCharacters() {
        // Заголовок видео с кавычками, слешами, путями не должен влиять на имя директории
        val maliciousTitle = "../../etc/passwd: CON <malicious> \u0000"
        val req = VoxDownloadRequest(
            downloadId = "safe-download-id-999",
            videoId = "UF8uR6Z6KLc",
            videoTitle = maliciousTitle
        )
        storage.saveJobMetadata(
            req,
            VoxDownloadState.IDLE,
            VoxTrackProgress(VoxDownloadTrack.VIDEO),
            VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO),
            VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO)
        )

        val loaded = storage.loadJobMetadata("safe-download-id-999")
        assertNotNull(loaded)
        assertEquals(maliciousTitle, loaded!!.request.videoTitle)
        val jobDir = storage.getJobDir("safe-download-id-999")
        assertTrue(jobDir.path.endsWith("safe-download-id-999"))
    }
}

