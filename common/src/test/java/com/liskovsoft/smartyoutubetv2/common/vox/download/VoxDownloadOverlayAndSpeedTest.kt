package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxDownloadOverlayAndSpeedTest {

    @Test
    fun testSpeedEstimatorWarmupSuppression() {
        val estimator = VoxDownloadSpeedEstimator(
            alpha = 0.25f,
            minAccumulationMs = 3000L,
            minIntervalMs = 250L
        )

        val totalBytes = 100L * 1024L * 1024L // 100 MB
        var now = 10_000L

        // Start at t=10s, 0 bytes
        assertNull(estimator.update(0L, totalBytes, now))

        // t=11s, 2MB downloaded -> elapsed=1s < 3s warmup -> ETA must be null
        now += 1000L
        assertNull(estimator.update(2L * 1024L * 1024L, totalBytes, now))

        // t=12s, 4MB downloaded -> elapsed=2s < 3s warmup -> ETA must be null
        now += 1000L
        assertNull(estimator.update(4L * 1024L * 1024L, totalBytes, now))

        // t=13.5s, 7MB downloaded -> elapsed=3.5s >= 3s warmup -> ETA should be computed
        now += 1500L
        val eta = estimator.update(7L * 1024L * 1024L, totalBytes, now)
        assertNotNull(eta)
        assertTrue("ETA should be positive: $eta", eta!! > 0L)
    }

    @Test
    fun testSpeedEstimatorCompletionAndReset() {
        val estimator = VoxDownloadSpeedEstimator()
        val totalBytes = 50L * 1024L * 1024L

        // Downloaded >= total -> 0L
        assertEquals(0L, estimator.update(50L * 1024L * 1024L, totalBytes, 10_000L))
        assertEquals(0L, estimator.update(60L * 1024L * 1024L, totalBytes, 10_000L))

        // Null/zero total -> null
        assertNull(estimator.update(10L, null, 10_000L))
        assertNull(estimator.update(10L, 0L, 10_000L))

        // Reset
        estimator.reset()
        assertEquals(0.0, estimator.getEmaSpeedBytesPerSec(), 0.001)
    }

    @Test
    fun testSpeedEstimatorEmaCalculation() {
        val estimator = VoxDownloadSpeedEstimator(
            alpha = 0.5f,
            minAccumulationMs = 3000L,
            minIntervalMs = 500L
        )

        val totalBytes = 100_000_000L
        var now = 0L

        // Initial sample
        assertNull(estimator.update(0L, totalBytes, now))

        // 1000ms: 1,000,000 bytes (1 MB/s)
        now = 1000L
        assertNull(estimator.update(1_000_000L, totalBytes, now))

        // 2000ms: 2,000,000 bytes (1 MB/s)
        now = 2000L
        assertNull(estimator.update(2_000_000L, totalBytes, now))

        // 3500ms: 3,500,000 bytes (1 MB/s) -> 3.5s elapsed -> ETA calculated
        now = 3500L
        val eta = estimator.update(3_500_000L, totalBytes, now)
        assertNotNull(eta)
        // 96.5 MB remaining at ~1 MB/s -> ~96-97 seconds
        assertTrue("Expected ~96s ETA, got $eta", eta in 90L..105L)
    }
}
