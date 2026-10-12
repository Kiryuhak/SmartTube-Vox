package com.liskovsoft.smartyoutubetv2.common.vox.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ImageRetryRecoveredTest {
    @Test
    fun testAttempt1CanRetryAndRecovers() {
        assertTrue(VoxImageRetryPolicy.canRetry(1))
        VoxImageRetryPolicy.logFailure(IOException("Socket closed"), 1, "card_thumbnail_test")
        // Now succeeds:
        VoxImageRetryPolicy.logSuccess("card_thumbnail_test")
    }
}

class ImageFinalFailureTest {
    @Test
    fun testAttempt2IsFinalFailure() {
        assertFalse(VoxImageRetryPolicy.canRetry(2))
        VoxImageRetryPolicy.logFailure(IOException("Connection refused"), 2, "card_thumbnail_test2")
    }
}

class ImageViewRecycleNotFailureTest {
    @Test
    fun testRecycledOrCancelledViewIsNotTreatedAsNetworkFailure() {
        val cancelledException = IOException("Target was cleared / request was cancelled")
        assertTrue(VoxImageRetryPolicy.isCancellationOrRecycled(cancelledException))

        val regularException = IOException("Connection reset by peer")
        assertFalse(VoxImageRetryPolicy.isCancellationOrRecycled(regularException))
    }
}

class ImageFailureAggregationTest {
    @Test
    fun testBackoffCalculatedPerAttempt() {
        assertEquals(0L, VoxImageRetryPolicy.getBackoffDelayMs(0))
        assertEquals(250L, VoxImageRetryPolicy.getBackoffDelayMs(1))
        assertEquals(500L, VoxImageRetryPolicy.getBackoffDelayMs(2))
    }
}
