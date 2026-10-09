package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticsDeduplicationTest {

    @Test
    fun testConsecutiveIdenticalEventsAreCoalesced() {
        val event1 = VoxLogEvent(
            timestamp = 1000L,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.PLAYER,
            code = "PLAYER_REBUFFER",
            message = "Buffer underrun",
            repeatCount = 1
        )
        val event2 = VoxLogEvent(
            timestamp = 2000L,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.PLAYER,
            code = "PLAYER_REBUFFER",
            message = "Buffer underrun",
            repeatCount = 1
        )
        val event3 = VoxLogEvent(
            timestamp = 3000L,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.PLAYER,
            code = "PLAYER_REBUFFER",
            message = "Buffer underrun",
            repeatCount = 2
        )

        val deduplicated = VoxDiagnosticsPolicy.deduplicateEvents(listOf(event1, event2, event3))
        assertEquals(1, deduplicated.size)
        assertEquals(4, deduplicated[0].repeatCount)
        assertEquals(3000L, deduplicated[0].timestamp)
    }

    @Test
    fun testDistinctEventsArePreserved() {
        val event1 = VoxLogEvent(
            timestamp = 1000L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = "PLAYER_RENDERER_ERROR",
            message = "Renderer error"
        )
        val event2 = VoxLogEvent(
            timestamp = 2000L,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.NETWORK,
            code = "NETWORK_RETRY",
            message = "Retrying connection"
        )
        val event3 = VoxLogEvent(
            timestamp = 3000L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = "PLAYER_RENDERER_ERROR",
            message = "Renderer error"
        )

        val deduplicated = VoxDiagnosticsPolicy.deduplicateEvents(listOf(event1, event2, event3))
        assertEquals(3, deduplicated.size)
    }
}
