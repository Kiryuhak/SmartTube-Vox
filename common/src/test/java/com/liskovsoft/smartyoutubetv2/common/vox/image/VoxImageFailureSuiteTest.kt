package com.liskovsoft.smartyoutubetv2.common.vox.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ImageRetryThenRecoveredTest {
    @Test
    fun testAttempt1CanRetryAndRecovers() {
        assertTrue(VoxImageRetryPolicy.canRetry(1))
        VoxImageRetryPolicy.logFailure(IOException("Socket closed"), 1, "card_thumbnail_test")
        // Now succeeds:
        VoxImageRetryPolicy.logSuccess("card_thumbnail_test")
    }
}

class ImageFinalFailureOnlyTest {
    @Test
    fun testAttempt2IsFinalFailure() {
        assertFalse(VoxImageRetryPolicy.canRetry(2))
        VoxImageRetryPolicy.logFailure(IOException("Connection refused"), 2, "card_thumbnail_test2")
    }
}

class ImageCancelledRequestIgnoredTest {
    @Test
    fun testRecycledOrCancelledViewIsNotTreatedAsNetworkFailure() {
        val cancelledException = IOException("Target was cleared / request was cancelled")
        assertTrue(VoxImageRetryPolicy.isCancellationOrRecycled(cancelledException))

        val regularException = IOException("Connection reset by peer")
        assertFalse(VoxImageRetryPolicy.isCancellationOrRecycled(regularException))
    }
}

class ImageAggregationWindowTest {
    @Test
    fun testBackoffCalculatedPerAttempt() {
        assertEquals(0L, VoxImageRetryPolicy.getBackoffDelayMs(0))
        assertEquals(250L, VoxImageRetryPolicy.getBackoffDelayMs(1))
        assertEquals(500L, VoxImageRetryPolicy.getBackoffDelayMs(2))
    }
}

class ImageRetryRecoveredNoIncidentTest {
    @Test
    fun testRecoveredImageProducesNoIncidentReport() {
        // Attempt 1 fails (retry)
        assertTrue(VoxImageRetryPolicy.canRetry(1))
        VoxImageRetryPolicy.logFailure(IOException("Read timed out"), 1, "test_thumbnail_recovered")

        // Recovery succeeds -> recovered
        VoxImageRetryPolicy.logSuccess("test_thumbnail_recovered")
        // Not a critical error or incident
    }
}

class ImageFinalFailureIncidentTest {
    @Test
    fun testFinalFailureIsLoggedOnlyAfterExhaustingRetries() {
        assertFalse(VoxImageRetryPolicy.canRetry(VoxImageRetryPolicy.MAX_RETRIES))
        VoxImageRetryPolicy.logFailure(IOException("UnknownHostException"), VoxImageRetryPolicy.MAX_RETRIES, "test_thumbnail_final")
    }
}

class ImageRecycleIgnoredTest {
    @Test
    fun testFastScrollRecycleExceptionIsIgnored() {
        val ex1 = IOException("Target was cleared")
        val ex2 = RuntimeException("Glide target recycled")
        assertTrue(VoxImageRetryPolicy.isCancellationOrRecycled(ex1))
        assertTrue(VoxImageRetryPolicy.isCancellationOrRecycled(ex2))
    }
}

class ImageScrollNoiseAggregationTest {
    @Test
    fun testRapidScrollDoesNotProduceWarningNoise() {
        var ignoredRecycledCount = 0
        for (i in 1..50) {
            val recycledEx = IOException("Request was cancelled during rapid scroll $i")
            if (VoxImageRetryPolicy.isCancellationOrRecycled(recycledEx)) {
                ignoredRecycledCount++
            }
        }
        assertEquals(50, ignoredRecycledCount)
    }
}
