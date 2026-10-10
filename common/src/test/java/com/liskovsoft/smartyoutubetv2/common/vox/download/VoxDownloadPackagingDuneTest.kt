package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

class VoxDownloadPackagingDuneTest {

    @Test
    fun testPackagingAndFinalizeNeverReturnUnknown() {
        val finalizeEx = VoxDownloadException(VoxDownloadErrorCode.FINALIZE_FAILED, "Failed to copy MKV to MediaStore")
        val (cat1, reason1) = VoxDownloadFailureClassifier.classifyWithReason(finalizeEx)
        assertNotEquals(VoxDownloadFailureCategory.UNKNOWN, cat1)
        assertEquals(VoxDownloadFailureCategory.FINALIZE_FAILED, cat1)

        val moveEx = VoxDownloadException(VoxDownloadErrorCode.OUTPUT_MOVE_FAILED, "Failed to rename or copy temporary file")
        val (cat2, reason2) = VoxDownloadFailureClassifier.classifyWithReason(moveEx)
        assertNotEquals(VoxDownloadFailureCategory.UNKNOWN, cat2)
        assertEquals(VoxDownloadFailureCategory.OUTPUT_MOVE_FAILED, cat2)

        val storageErrorEx = VoxDownloadException(VoxDownloadErrorCode.STORAGE_ERROR, "Published file validation failed")
        val (cat3, reason3) = VoxDownloadFailureClassifier.classifyWithReason(storageErrorEx)
        assertNotEquals(VoxDownloadFailureCategory.UNKNOWN, cat3)
        assertEquals(VoxDownloadFailureCategory.FINALIZE_FAILED, cat3)

        val packagingEx = VoxDownloadException(VoxDownloadErrorCode.PACKAGING_FAILED, "Packaging failed")
        val (cat4, reason4) = VoxDownloadFailureClassifier.classifyWithReason(packagingEx)
        assertNotEquals(VoxDownloadFailureCategory.UNKNOWN, cat4)
        assertEquals(VoxDownloadFailureCategory.PACKAGING_FAILED, cat4)
    }

    @Test
    fun testCrossMountCopyFallbackAndLengthVerification() {
        val srcFile = File.createTempFile("vox_src_", ".mkv")
        val dstFile = File.createTempFile("vox_dst_", ".mkv")
        dstFile.delete() // Ensure destination does not exist yet

        try {
            // Write 256 KiB of dummy payload
            val data = ByteArray(256 * 1024) { (it % 127).toByte() }
            srcFile.writeBytes(data)

            // Simulate cross-mount copy fallback with 128 KiB buffer
            val buffer = ByteArray(128 * 1024)
            srcFile.inputStream().use { input ->
                dstFile.outputStream().use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }

            assertTrue(dstFile.exists())
            assertEquals(srcFile.length(), dstFile.length())
        } finally {
            srcFile.delete()
            dstFile.delete()
        }
    }

    @Test
    fun testAtomicCompletionValidator() {
        val request = VoxDownloadRequest(
            videoId = "test_vid",
            videoTitle = "Title",
            qualityPreference = VoxQualityPreference.QUALITY_720P,
            translationMode = VoxTranslationMode.NONE
        )
        val job = VoxDownloadJob(request).apply {
            durationMs = 120_000L
            updateTrackProgress(VoxDownloadTrack.VIDEO, 1000L, 1000L, VoxTrackState.COMPLETED)
            updateTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, 500L, 500L, VoxTrackState.COMPLETED)
            finalFileBytes = 1500L
            publishedUri = "file:///tmp/test.mkv"
            packagingCompleted = true
            finalizeCompleted = true
        }

        // Valid completion
        VoxDownloadCompletionValidator.check(job, 1500L)

        // Invalid: missing or zero size must fail validation
        try {
            VoxDownloadCompletionValidator.check(job, 0L)
            fail("Should fail validation on 0 readable bytes")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.STORAGE_ERROR, e.code)
        }

        // Invalid: size mismatch must fail validation
        try {
            VoxDownloadCompletionValidator.check(job, 1200L)
            fail("Should fail validation on size mismatch")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.STORAGE_ERROR, e.code)
        }
    }
}
