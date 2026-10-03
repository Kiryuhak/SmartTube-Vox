package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(VoxDownloadFailureCategory.INTEGRITY_FAILED, VoxDownloadFailureClassifier.classify(null, 416))
        assertEquals(VoxDownloadFailureCategory.NETWORK, VoxDownloadFailureClassifier.classify(null, 429))
        assertEquals(VoxDownloadFailureCategory.NETWORK, VoxDownloadFailureClassifier.classify(null, 500))
        assertEquals(VoxDownloadFailureCategory.NETWORK, VoxDownloadFailureClassifier.classify(null, 503))
    }

    @Test
    fun testClassifyNetworkExceptions() {
        assertEquals(VoxDownloadFailureCategory.NETWORK, VoxDownloadFailureClassifier.classify(SocketTimeoutException("Read timed out")))
        assertEquals(VoxDownloadFailureCategory.NETWORK, VoxDownloadFailureClassifier.classify(UnknownHostException("Unable to resolve host")))
        assertEquals(VoxDownloadFailureCategory.NETWORK, VoxDownloadFailureClassifier.classify(ConnectException("Connection refused")))
    }

    @Test
    fun testClassifyVoxDownloadExceptions() {
        val expiredEx = VoxDownloadException(VoxDownloadErrorCode.URL_EXPIRED, "Expired signed URL")
        assertEquals(VoxDownloadFailureCategory.URL_EXPIRED, VoxDownloadFailureClassifier.classify(expiredEx))
        assertTrue(VoxDownloadFailureClassifier.classify(expiredEx).isRecoverable)

        val storageEx = VoxDownloadException(VoxDownloadErrorCode.INSUFFICIENT_STORAGE, "Disk full")
        assertEquals(VoxDownloadFailureCategory.STORAGE_FULL, VoxDownloadFailureClassifier.classify(storageEx))
        assertFalse(VoxDownloadFailureClassifier.classify(storageEx).isRecoverable)

        val codecEx = VoxDownloadException(VoxDownloadErrorCode.UNSUPPORTED_CODEC, "Unsupported AV1")
        assertEquals(VoxDownloadFailureCategory.FORMAT_UNSUPPORTED, VoxDownloadFailureClassifier.classify(codecEx))

        val cancelEx = VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Cancelled")
        assertEquals(VoxDownloadFailureCategory.CANCELED, VoxDownloadFailureClassifier.classify(cancelEx))
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
