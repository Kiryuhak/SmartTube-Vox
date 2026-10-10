package com.liskovsoft.smartyoutubetv2.common.vox.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ImageRetryPolicyTest {

    @Test
    fun testRetryCountLimits() {
        assertTrue(VoxImageRetryPolicy.canRetry(0))
        assertTrue(VoxImageRetryPolicy.canRetry(1))
        assertFalse(VoxImageRetryPolicy.canRetry(2))
        assertFalse(VoxImageRetryPolicy.canRetry(3))
    }

    @Test
    fun testBackoffDelayCalculation() {
        assertEquals(0L, VoxImageRetryPolicy.getBackoffDelayMs(0))
        assertEquals(250L, VoxImageRetryPolicy.getBackoffDelayMs(1))
        assertEquals(500L, VoxImageRetryPolicy.getBackoffDelayMs(2))
        assertEquals(1000L, VoxImageRetryPolicy.getBackoffDelayMs(3))
    }

    @Test
    fun testErrorClassification() {
        val dnsException = UnknownHostException("i.ytimg.com")
        assertEquals(VoxImageRetryPolicy.ImageErrorType.DNS_ERROR, VoxImageRetryPolicy.classifyError(dnsException))

        val timeoutException = SocketTimeoutException("connect timed out")
        assertEquals(VoxImageRetryPolicy.ImageErrorType.TIMEOUT, VoxImageRetryPolicy.classifyError(timeoutException))

        val connectException = ConnectException("Connection refused")
        assertEquals(VoxImageRetryPolicy.ImageErrorType.REQUEST_FAILED, VoxImageRetryPolicy.classifyError(connectException))

        val wrappedDns = RuntimeException("Glide failure", UnknownHostException("ytimg"))
        assertEquals(VoxImageRetryPolicy.ImageErrorType.DNS_ERROR, VoxImageRetryPolicy.classifyError(wrappedDns))

        val messageTimeout = RuntimeException("Request timeout occurred")
        assertEquals(VoxImageRetryPolicy.ImageErrorType.TIMEOUT, VoxImageRetryPolicy.classifyError(messageTimeout))

        assertEquals(VoxImageRetryPolicy.ImageErrorType.REQUEST_FAILED, VoxImageRetryPolicy.classifyError(null))
    }
}
