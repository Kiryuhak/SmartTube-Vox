package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class VoxDownloadFailureClassifierTest {

    @Test
    fun testClassifyHttpCodes() {
        assertEquals(VoxDownloadFailureCategory.HTTP_FORBIDDEN, VoxDownloadFailureClassifier.classify(null, 403))
        assertEquals(VoxDownloadFailureCategory.URL_EXPIRED, VoxDownloadFailureClassifier.classify(null, 410))
        assertEquals(VoxDownloadFailureCategory.CHECKSUM_FAILED, VoxDownloadFailureClassifier.classify(null, 416))
        assertEquals(VoxDownloadFailureCategory.NETWORK, VoxDownloadFailureClassifier.classify(null, 429))
        assertEquals(VoxDownloadFailureCategory.NETWORK, VoxDownloadFailureClassifier.classify(null, 500))
        assertEquals(VoxDownloadFailureCategory.NETWORK, VoxDownloadFailureClassifier.classify(null, 503))
    }

    @Test
    fun testClassifyNetworkExceptions() {
        assertEquals(VoxDownloadFailureCategory.TIMEOUT, VoxDownloadFailureClassifier.classify(SocketTimeoutException("Read timed out")))
        assertEquals(VoxDownloadFailureCategory.NETWORK, VoxDownloadFailureClassifier.classify(UnknownHostException("Unable to resolve host")))
        assertEquals(VoxDownloadFailureCategory.NETWORK, VoxDownloadFailureClassifier.classify(ConnectException("Connection refused")))
    }

    @Test
    fun testClassifyVoxDownloadExceptions() {
        val expiredEx = VoxDownloadException(VoxDownloadErrorCode.URL_EXPIRED, "Expired signed URL")
        assertEquals(VoxDownloadFailureCategory.URL_EXPIRED, VoxDownloadFailureClassifier.classify(expiredEx))
        assertTrue(VoxDownloadFailureClassifier.classify(expiredEx).isRecoverable)

        val storageEx = VoxDownloadException(VoxDownloadErrorCode.STORAGE_FULL, "Disk full")
        assertEquals(VoxDownloadFailureCategory.STORAGE_FULL, VoxDownloadFailureClassifier.classify(storageEx))
        assertFalse(VoxDownloadFailureClassifier.classify(storageEx).isRecoverable)

        val codecEx = VoxDownloadException(VoxDownloadErrorCode.UNSUPPORTED_FORMAT, "Unsupported AV1")
        assertEquals(VoxDownloadFailureCategory.UNSUPPORTED_FORMAT, VoxDownloadFailureClassifier.classify(codecEx))

        val cancelEx = VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Cancelled")
        assertEquals(VoxDownloadFailureCategory.CANCELLED, VoxDownloadFailureClassifier.classify(cancelEx))

        val packagingEx = VoxDownloadException(VoxDownloadErrorCode.PACKAGING_FAILED, "Packaging failed")
        assertEquals(VoxDownloadFailureCategory.PACKAGING_FAILED, VoxDownloadFailureClassifier.classify(packagingEx))

        val muxEx = VoxDownloadException(VoxDownloadErrorCode.MUX_FAILED, "Mux failed")
        assertEquals(VoxDownloadFailureCategory.MUX_FAILED, VoxDownloadFailureClassifier.classify(muxEx))

        val permEx = VoxDownloadException(VoxDownloadErrorCode.STORAGE_PERMISSION, "Permission denied")
        assertEquals(VoxDownloadFailureCategory.STORAGE_PERMISSION, VoxDownloadFailureClassifier.classify(permEx))

        val moveEx = VoxDownloadException(VoxDownloadErrorCode.OUTPUT_MOVE_FAILED, "Move failed")
        assertEquals(VoxDownloadFailureCategory.OUTPUT_MOVE_FAILED, VoxDownloadFailureClassifier.classify(moveEx))

        val finEx = VoxDownloadException(VoxDownloadErrorCode.FINALIZE_FAILED, "Finalize failed")
        assertEquals(VoxDownloadFailureCategory.FINALIZE_FAILED, VoxDownloadFailureClassifier.classify(finEx))
    }

    @Test
    fun testPreserveRootCause() {
        val root = IOException("ENOSPC: No space left on device")
        val wrapper = RuntimeException("Worker failed", root)
        val outer = VoxDownloadException(VoxDownloadErrorCode.STORAGE_ERROR, "Storage write failed", wrapper)

        val extracted = VoxDownloadFailureClassifier.extractRootCause(outer)
        assertEquals(root, extracted)

        val (category, reason) = VoxDownloadFailureClassifier.classifyWithReason(outer)
        assertEquals(VoxDownloadFailureCategory.STORAGE_FULL, category)
        assertEquals("NO_SPACE_LEFT_ON_DEVICE", reason)
    }

    @Test
    fun testRootCauseCircularReferenceSafety() {
        val ex1 = Exception("Exception 1")
        val ex2 = Exception("Exception 2", ex1)
        // Ensure no infinite loop when evaluating causes
        val extracted = VoxDownloadFailureClassifier.extractRootCause(ex2)
        assertNotNull(extracted)
    }

    @Test
    fun testUserMessagesAreNonEmptyAndRussian() {
        for (category in VoxDownloadFailureCategory.values()) {
            val msg = VoxDownloadFailureClassifier.getUserMessage(category)
            assertTrue("Message for $category should not be blank", msg.isNotBlank())
            assertFalse("Message for $category should not be generic raw exception", msg.contains("Exception"))
        }
    }
}
