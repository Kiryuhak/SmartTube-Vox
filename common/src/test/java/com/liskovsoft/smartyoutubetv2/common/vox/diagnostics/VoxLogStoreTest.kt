package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class VoxLogStoreTest {

    private lateinit var store: VoxLogStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        store = VoxLogStore.instance(context)
        store.clearLogs()
    }

    @Test
    fun testAddAndGetEvents() {
        val ev1 = VoxLogEvent(
            timestamp = System.currentTimeMillis() - 1000,
            level = VoxLogLevel.INFO,
            category = VoxLogCategory.APP,
            code = VoxLogCode.APP_START,
            message = "Приложение запущено"
        )
        val ev2 = VoxLogEvent(
            timestamp = System.currentTimeMillis(),
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.DOWNLOAD,
            code = VoxLogCode.DOWNLOAD_HTTP_403,
            message = "Ссылка устарела",
            context = mapOf("stage" to "VIDEO")
        )

        store.addEvent(ev1)
        store.addEvent(ev2)

        val events = store.getEvents()
        assertEquals(2, events.size)
        // Newest first
        assertEquals(VoxLogCode.DOWNLOAD_HTTP_403, events[0].code)
        assertEquals(VoxLogCode.APP_START, events[1].code)
        assertEquals(2, store.getJournalEventCount())
    }

    @Test
    fun testRingBufferRotation() {
        for (i in 1..550) {
            val ev = VoxLogEvent(
                timestamp = System.currentTimeMillis() + i,
                level = VoxLogLevel.INFO,
                category = VoxLogCategory.DOWNLOAD,
                code = "EVENT_$i",
                message = "Message #$i"
            )
            store.addEvent(ev)
        }

        val events = store.getEvents(1000)
        assertEquals(VoxLogStore.MAX_EVENTS, events.size)
        // Oldest 50 events should have been rotated out
        assertEquals("EVENT_550", events.first().code)
        assertEquals("EVENT_51", events.last().code)
    }

    @Test
    fun testRetentionExpiration() {
        val eightDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)
        val oldEvent = VoxLogEvent(
            timestamp = eightDaysAgo,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = "OLD_ERROR",
            message = "Old error from 8 days ago"
        )
        val newEvent = VoxLogEvent(
            timestamp = System.currentTimeMillis(),
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = "NEW_ERROR",
            message = "Recent error"
        )

        store.addEvent(oldEvent)
        store.addEvent(newEvent)

        val events = store.getEvents()
        assertEquals(1, events.size)
        assertEquals("NEW_ERROR", events[0].code)
    }

    @Test
    fun testClearLogs() {
        val ev = VoxLogEvent(
            timestamp = System.currentTimeMillis(),
            level = VoxLogLevel.INFO,
            category = VoxLogCategory.APP,
            code = VoxLogCode.APP_START,
            message = "Started"
        )
        store.addEvent(ev)
        assertEquals(1, store.getJournalEventCount())

        store.clearLogs()
        assertEquals(0, store.getJournalEventCount())
        assertEquals("Ошибок пока не зафиксировано.", store.getFormattedJournal())
    }

    @Test
    fun testCorruptedStorageFileRecovery() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "vox_safe_logs.json")
        file.writeText("INVALID JSON {{{ NOT_AN_ARRAY")

        // Reload should recover safely without throwing
        val newStore = VoxLogStore.instance(context)
        assertNotNull(newStore)
        val events = newStore.getEvents()
        assertEquals(0, events.size)
    }
}
