package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class VoxDownloadPublisherTest {

    private lateinit var context: Context
    private lateinit var publisher: VoxMediaStorePublisher
    private lateinit var testTempDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        publisher = VoxMediaStorePublisher(context)
        testTempDir = File(context.cacheDir, "vox_pub_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testTempDir.deleteRecursively()
    }

    @Test
    fun testSanitizeFilename_basic() {
        val input = "Normal Title 123"
        val output = VoxMediaStorePublisher.sanitizeFilename(input)
        assertEquals("Normal Title 123", output)
    }

    @Test
    fun testSanitizeFilename_emptyAndNull() {
        assertEquals("SmartTube_VOX", VoxMediaStorePublisher.sanitizeFilename(null))
        assertEquals("SmartTube_VOX", VoxMediaStorePublisher.sanitizeFilename(""))
        assertEquals("SmartTube_VOX", VoxMediaStorePublisher.sanitizeFilename("   "))
    }

    @Test
    fun testSanitizeFilename_forbiddenChars() {
        val input = "Video: <Why> \"Cats\" / Dogs | Test? * Yes \\ No"
        val output = VoxMediaStorePublisher.sanitizeFilename(input)
        assertEquals("Video_ _Why_ _Cats_ _ Dogs _ Test_ _ Yes _ No", output)
    }

    @Test
    fun testSanitizeFilename_trailingDotsAndSpaces() {
        val input = "Title with dots...   "
        val output = VoxMediaStorePublisher.sanitizeFilename(input)
        assertEquals("Title with dots", output)
    }

    @Test
    fun testSanitizeFilename_reservedNames() {
        assertEquals("CON_video", VoxMediaStorePublisher.sanitizeFilename("CON"))
        assertEquals("con_video", VoxMediaStorePublisher.sanitizeFilename("con"))
        assertEquals("AUX_video", VoxMediaStorePublisher.sanitizeFilename("AUX"))
        assertEquals("nul_video", VoxMediaStorePublisher.sanitizeFilename("nul"))
    }

    @Test
    fun testSanitizeFilename_maxLengthTruncation() {
        val longTitle = "A".repeat(150)
        val sanitized = VoxMediaStorePublisher.sanitizeFilename(longTitle)
        assertEquals(120, sanitized.length)
        assertEquals("A".repeat(120), sanitized)
    }

    @Test
    fun testBuildFilename() {
        val single = VoxMediaStorePublisher.buildFilename("Demo Video", 1)
        assertEquals("Demo Video — SmartTube VOX.mkv", single)

        val duplicate = VoxMediaStorePublisher.buildFilename("Demo Video", 2)
        assertEquals("Demo Video — SmartTube VOX (2).mkv", duplicate)

        val duplicate10 = VoxMediaStorePublisher.buildFilename("Demo Video", 10)
        assertEquals("Demo Video — SmartTube VOX (10).mkv", duplicate10)
    }

    @Test
    fun testPublish_emptyOrMissingFileThrows() {
        val missingFile = File(testTempDir, "missing.mkv")
        try {
            publisher.publish(missingFile, "Test Title")
            fail("Expected VoxDownloadException for missing file")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.STORAGE_ERROR, e.code)
        }

        val emptyFile = File(testTempDir, "empty.mkv").apply { createNewFile() }
        try {
            publisher.publish(emptyFile, "Test Title")
            fail("Expected VoxDownloadException for empty file")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.STORAGE_ERROR, e.code)
        }
    }

    @Test
    fun testPublish_cancellation() {
        val dummyFile = File(testTempDir, "source.mkv").apply {
            writeBytes(ByteArray(1024 * 128) { 0x55 })
        }
        val isCancelled = AtomicBoolean(true)

        try {
            publisher.publish(dummyFile, "Test Title", isCancelled)
            fail("Expected VoxDownloadException for cancelled publication")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.CANCELLED, e.code)
        }
    }

    @Test
    fun testIsPublishedFileAvailable_localFile() {
        val testFile = File(testTempDir, "published.mkv").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val fileUri = "file://${testFile.absolutePath}"

        assertTrue(publisher.isPublishedFileAvailable(fileUri))

        testFile.delete()
        assertFalse(publisher.isPublishedFileAvailable(fileUri))
        assertFalse(publisher.isPublishedFileAvailable(null))
        assertFalse(publisher.isPublishedFileAvailable(""))
    }

    @Test
    fun testDeletePublishedFile_localFile() {
        val testFile = File(testTempDir, "to_delete.mkv").apply {
            writeBytes(byteArrayOf(5, 6, 7, 8))
        }
        val fileUri = "file://${testFile.absolutePath}"

        assertTrue(testFile.exists())
        val deleted = publisher.deletePublishedFile(fileUri)
        assertTrue(deleted)
        assertFalse(testFile.exists())
    }
}
