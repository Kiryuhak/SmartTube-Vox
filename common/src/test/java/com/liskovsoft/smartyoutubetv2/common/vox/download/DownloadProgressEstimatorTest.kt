package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DownloadProgressEstimatorTest {

    private lateinit var estimator: VoxDownloadSpeedEstimator

    @Before
    fun setUp() {
        estimator = VoxDownloadSpeedEstimator(
            alpha = 0.5f,
            minAccumulationMs = 1000L,
            minIntervalMs = 200L
        )
    }

    @Test
    fun returnsNullDuringWarmupPeriod() {
        val t0 = 10000L
        assertNull(estimator.update(0L, 10_000_000L, t0))
        assertNull(estimator.update(500_000L, 10_000_000L, t0 + 500L))
        assertNull(estimator.update(900_000L, 10_000_000L, t0 + 999L))
    }

    @Test
    fun calculatesSpeedAndEtaAfterWarmup() {
        val t0 = 10000L
        val totalBytes = 10_000_000L // 10 MB

        estimator.update(0L, totalBytes, t0)
        estimator.update(1_000_000L, totalBytes, t0 + 500L) // 1 MB at 0.5s -> 2 MB/s
        val etaSec = estimator.update(2_000_000L, totalBytes, t0 + 1000L) // 2 MB at 1.0s -> 2 MB/s

        assertNotNull(etaSec)
        assertTrue(etaSec!! > 0L)
        assertTrue(estimator.getEmaSpeedBytesPerSec() > 1024.0)
        assertTrue(estimator.getFormattedSpeed().contains("МБ/с") || estimator.getFormattedSpeed().contains("КБ/с"))
    }

    @Test
    fun returnsZeroWhenDownloadedEqualsOrExceedsTotal() {
        val t0 = 10000L
        val total = 5_000_000L

        assertEquals(0L, estimator.update(5_000_000L, total, t0))
        assertEquals(0L, estimator.update(6_000_000L, total, t0 + 500L))
    }

    @Test
    fun handlesResetGracefully() {
        val t0 = 10000L
        estimator.update(0L, 10_000_000L, t0)
        estimator.update(2_000_000L, 10_000_000L, t0 + 2000L)
        assertTrue(estimator.getEmaSpeedBytesPerSec() > 0.0)

        estimator.reset()
        assertEquals(0.0, estimator.getEmaSpeedBytesPerSec(), 0.001)
        assertEquals("", estimator.getFormattedSpeed())
    }
}
