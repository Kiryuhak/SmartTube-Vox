package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class VoxYouTubeRetryPolicyTest {

    private val policy = VoxYouTubeRetryPolicy(
        maxRetries = 3,
        initialBackoffMs = 1000L,
        backoffMultiplier = 2.0
    )

    @Test
    fun testClassifyErrorByHttpStatus() {
        assertEquals(VoxYouTubeErrorCategory.HTTP_403, policy.classifyError(null, 403))
        assertEquals(VoxYouTubeErrorCategory.HTTP_410, policy.classifyError(null, 410))
        assertEquals(VoxYouTubeErrorCategory.HTTP_429, policy.classifyError(null, 429))
        assertEquals(VoxYouTubeErrorCategory.HTTP_5XX, policy.classifyError(null, 503))
    }

    @Test
    fun testClassifyErrorByThrowable() {
        assertEquals(VoxYouTubeErrorCategory.NETWORK_TIMEOUT, policy.classifyError(SocketTimeoutException("Read timed out"), null))
        assertEquals(VoxYouTubeErrorCategory.DNS_FAILURE, policy.classifyError(UnknownHostException("googlevideo.com"), null))
        assertEquals(
            VoxYouTubeErrorCategory.CANCELLED,
            policy.classifyError(VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "User cancelled"), null)
        )
    }

    @Test
    fun testRetryScheduleWithBackoff() {
        val decision1 = policy.evaluate(VoxYouTubeErrorCategory.NETWORK_TIMEOUT, currentAttempt = 0)
        assertTrue(decision1 is VoxRetryDecision.Retry)
        assertEquals(1000L, (decision1 as VoxRetryDecision.Retry).delayMs)
        assertEquals(1, decision1.attempt)

        val decision2 = policy.evaluate(VoxYouTubeErrorCategory.NETWORK_TIMEOUT, currentAttempt = 1)
        assertTrue(decision2 is VoxRetryDecision.Retry)
        assertEquals(2000L, (decision2 as VoxRetryDecision.Retry).delayMs)
        assertEquals(2, decision2.attempt)

        val decision3 = policy.evaluate(VoxYouTubeErrorCategory.NETWORK_TIMEOUT, currentAttempt = 2)
        assertTrue(decision3 is VoxRetryDecision.Retry)
        assertEquals(4000L, (decision3 as VoxRetryDecision.Retry).delayMs)
        assertEquals(3, decision3.attempt)

        val decision4 = policy.evaluate(VoxYouTubeErrorCategory.NETWORK_TIMEOUT, currentAttempt = 3)
        assertTrue(decision4 is VoxRetryDecision.Fatal)
    }

    @Test
    fun testUrlRefreshDecision() {
        val decisionWithProvider = policy.evaluate(
            VoxYouTubeErrorCategory.HTTP_403,
            currentAttempt = 0,
            hasUrlRefreshProvider = true
        )
        assertEquals(VoxRetryDecision.RefreshUrlAndRetry, decisionWithProvider)

        val decisionWithoutProvider = policy.evaluate(
            VoxYouTubeErrorCategory.HTTP_403,
            currentAttempt = 0,
            hasUrlRefreshProvider = false
        )
        assertTrue(decisionWithoutProvider is VoxRetryDecision.Fatal)
    }

    @Test
    fun testFatalErrorsFailImmediately() {
        val cancelledDecision = policy.evaluate(VoxYouTubeErrorCategory.CANCELLED, currentAttempt = 0)
        assertTrue(cancelledDecision is VoxRetryDecision.Fatal)

        val storageDecision = policy.evaluate(VoxYouTubeErrorCategory.INSUFFICIENT_STORAGE, currentAttempt = 0)
        assertTrue(storageDecision is VoxRetryDecision.Fatal)
    }
}
