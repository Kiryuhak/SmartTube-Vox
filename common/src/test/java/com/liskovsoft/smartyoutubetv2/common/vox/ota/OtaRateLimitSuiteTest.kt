package com.liskovsoft.smartyoutubetv2.common.vox.ota

import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxDiagnosticsPolicy
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogEvent
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OtaRateLimitSuiteTest {

    @Test
    fun testOtaRateLimitCooldown() {
        val exRate = IOException("HTTP 403 API rate limit exceeded")
        val otaEx = VoxOtaUpdateManager.classifyException(exRate)

        assertEquals(VoxOtaErrorCode.RATE_LIMIT, otaEx.code)
        assertTrue(otaEx.isRetryable)
        assertTrue(otaEx.userMessageRu.contains("ограничил частоту проверок"))
    }

    @Test
    fun testOtaRateLimitDedup() {
        val event1 = VoxLogEvent(
            timestamp = 1000L,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.OTA,
            code = VoxLogCode.OTA_RATE_LIMIT,
            message = "Rate limit exceeded (attempt 1)",
            repeatCount = 1
        )
        val event2 = VoxLogEvent(
            timestamp = 2000L,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.OTA,
            code = VoxLogCode.OTA_RATE_LIMIT,
            message = "Rate limit exceeded (attempt 2)",
            repeatCount = 1
        )

        val deduplicated = VoxDiagnosticsPolicy.deduplicateEvents(listOf(event1, event2))
        assertEquals(1, deduplicated.size)
        assertEquals(2, deduplicated[0].repeatCount)
        assertEquals(2000L, deduplicated[0].timestamp)
    }

    @Test
    fun testOtaLaterNoUpdateSupersedesFailure() {
        val rateLimitEvent = VoxLogEvent(
            timestamp = 1000L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.OTA,
            code = VoxLogCode.OTA_RATE_LIMIT,
            message = "Rate limit 403"
        )
        val laterSuccessEvent = VoxLogEvent(
            timestamp = 2000L,
            level = VoxLogLevel.INFO,
            category = VoxLogCategory.OTA,
            code = VoxLogCode.OTA_NO_UPDATE,
            message = "Latest version installed"
        )

        // Once successful check occurs later, the earlier rate limit is recovered
        assertTrue(VoxDiagnosticsPolicy.isEventRecovered(rateLimitEvent, listOf(rateLimitEvent, laterSuccessEvent)))
    }

    @Test
    fun testOtaHistoricalRateLimitPreserved() {
        val rateLimitEvent = VoxLogEvent(
            timestamp = 1000L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.OTA,
            code = VoxLogCode.OTA_RATE_LIMIT,
            message = "Rate limit 403"
        )
        val unrelatedEvent = VoxLogEvent(
            timestamp = 2000L,
            level = VoxLogLevel.INFO,
            category = VoxLogCategory.UI,
            code = "HUD_SHOWN",
            message = "HUD shown"
        )

        // Without a subsequent successful OTA event, the error remains unrecovered
        assertFalse(VoxDiagnosticsPolicy.isEventRecovered(rateLimitEvent, listOf(rateLimitEvent, unrelatedEvent)))
    }

    @Test
    fun testOtaNoFalseNetworkErrorForNoUpdate() {
        // Normal NO_UPDATE should not produce network error or failure
        val noUpdate = VoxOtaResult(
            code = VoxOtaErrorCode.NO_UPDATE,
            httpStatus = 200
        )
        assertTrue(noUpdate.isSuccessful)
        assertFalse(noUpdate.isUpdateAvailable)
        assertEquals(VoxOtaErrorCode.NO_UPDATE.userMessageRu, "У вас установлена последняя версия")
    }
}
