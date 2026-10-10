package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticEventPriorityTest {

    private lateinit var store: VoxLogStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        store = VoxLogStore.instance(context)
        store.clearLogs()
    }

    @Test
    fun coalescesConsecutiveSidebarFocusEvents() {
        val t0 = System.currentTimeMillis() - 60_000L
        val ev1 = VoxLogEvent(
            level = VoxLogLevel.DEBUG,
            category = VoxLogCategory.APP,
            code = VoxLogCode.SIDEBAR_FOCUS_CHANGED,
            message = "Focus moved to search",
            timestamp = t0
        )
        val ev2 = VoxLogEvent(
            level = VoxLogLevel.DEBUG,
            category = VoxLogCategory.APP,
            code = VoxLogCode.SIDEBAR_FOCUS_CHANGED,
            message = "Focus moved to subscriptions",
            timestamp = t0 + 500L
        )

        store.addEvent(ev1)
        store.addEvent(ev2)

        val events = store.getEvents(10, VoxLogLevel.DEBUG)
        assertEquals(1, events.size)
        assertEquals(2, events[0].repeatCount)
        assertEquals(t0 + 500L, events[0].timestamp)
    }

    @Test
    fun boundsSidebarFocusQuota() {
        val t0 = System.currentTimeMillis() - 60_000L
        // Add 25 distinct sidebar focus events (alternating message to avoid immediate coalesce)
        for (i in 0 until 25) {
            store.addEvent(
                VoxLogEvent(
                    level = VoxLogLevel.DEBUG,
                    category = VoxLogCategory.APP,
                    code = if (i % 2 == 0) VoxLogCode.SIDEBAR_FOCUS_CHANGED else VoxLogCode.APP_START,
                    message = "Event $i",
                    timestamp = t0 + i * 100L
                )
            )
        }

        val allEvents = store.getEvents(100, VoxLogLevel.DEBUG)
        val sidebarCount = allEvents.count { it.code == VoxLogCode.SIDEBAR_FOCUS_CHANGED }
        assertTrue(sidebarCount <= VoxLogStore.MAX_SIDEBAR_FOCUS_EVENTS)
    }

    @Test
    fun errorEventsAreNeverDisplacedBySidebarNoise() {
        val t0 = System.currentTimeMillis() - 60_000L
        val criticalError = VoxLogEvent(
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_RENDERER_ERROR,
            message = "Renderer crash",
            timestamp = t0
        )
        store.addEvent(criticalError)

        // Flood buffer with non-error and sidebar events
        for (i in 0 until VoxLogStore.MAX_EVENTS + 50) {
            store.addEvent(
                VoxLogEvent(
                    level = VoxLogLevel.DEBUG,
                    category = VoxLogCategory.APP,
                    code = if (i % 2 == 0) VoxLogCode.SIDEBAR_FOCUS_CHANGED else VoxLogCode.APP_START,
                    message = "Noise $i",
                    timestamp = t0 + 1000L + i * 10L
                )
            )
        }

        val errors = store.getEvents(100, VoxLogLevel.ERROR)
        assertEquals(1, errors.size)
        assertEquals(VoxLogCode.PLAYER_RENDERER_ERROR, errors[0].code)
    }

    @Test
    fun recentEventsFiltersExcessiveSidebarNoise() {
        val t0 = System.currentTimeMillis() - 60_000L
        for (i in 0 until 15) {
            store.addEvent(
                VoxLogEvent(
                    level = VoxLogLevel.DEBUG,
                    category = VoxLogCategory.APP,
                    code = if (i % 2 == 0) VoxLogCode.SIDEBAR_FOCUS_CHANGED else VoxLogCode.PLAYER_REBUFFER,
                    message = "Event $i",
                    timestamp = t0 + i * 20L
                )
            )
        }

        val recent = store.getRecentEvents(50)
        val sidebarInRecent = recent.count { it.code == VoxLogCode.SIDEBAR_FOCUS_CHANGED }
        assertTrue(sidebarInRecent <= 5)
    }
}
