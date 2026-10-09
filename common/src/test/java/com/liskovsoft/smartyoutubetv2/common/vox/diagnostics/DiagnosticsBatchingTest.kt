package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticsBatchingTest {

    @Test
    fun testBatchCapLimitsTo50Events() {
        val events = (1..100).map { i ->
            VoxLogEvent(
                timestamp = 1000L + i,
                level = VoxLogLevel.ERROR,
                category = VoxLogCategory.PLAYER,
                code = "CODE_$i",
                message = "Error message $i"
            )
        }

        val batched = VoxDiagnosticsPolicy.deduplicateEvents(events)
        assertEquals(50, batched.size)
        assertEquals(VoxDiagnosticsPolicy.MAX_BATCH_EVENTS, batched.size)
    }

    @Test
    fun testEmptyEventsListReturnsEmpty() {
        val batched = VoxDiagnosticsPolicy.deduplicateEvents(emptyList())
        assertTrue(batched.isEmpty())
    }
}
