package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class VoxLogStoreNoDiskIoOnMainThreadTest {
    @Test
    fun testAddEventPerformsZeroMainThreadDiskIoAndFlushesInBackground() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = VoxLogStore.instance(context)
        store.clearLogs()
        store.flushToDiskSyncForTest()
        val baseMainIo = store.mainThreadDiskIoCount.get()
        val baseBgWrites = store.backgroundDiskWriteCount.get()

        for (i in 1..25) {
            store.addEvent(
                VoxLogEvent(
                    timestamp = System.currentTimeMillis() + i,
                    level = VoxLogLevel.INFO,
                    category = VoxLogCategory.UI,
                    code = VoxLogCode.HUD_SHOWN,
                    message = "Event $i"
                )
            )
        }

        // Calling addEvent on the main/test thread must NEVER perform synchronous disk I/O
        assertEquals(baseMainIo, store.mainThreadDiskIoCount.get())
        assertTrue(store.getRecentEvents(50).isNotEmpty())

        // Wait for background flush and verify file was written off-thread
        store.flushToDiskSyncForTest()
        assertEquals(baseMainIo, store.mainThreadDiskIoCount.get())
        assertTrue(store.backgroundDiskWriteCount.get() > baseBgWrites)
        assertTrue(store.hasPersistedLogFile())
    }
}

@RunWith(RobolectricTestRunner::class)
class DiagnosticsReportBuildOffMainThreadTest {
    @Test
    fun testPeriodicCheckRunsOnBackgroundExecutorNotMainThread() {
        val context: Context = RuntimeEnvironment.getApplication()
        val channelManager = com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannelManager.instance(context)
        channelManager.setBetaDiagnosticsEnabled(false)
        channelManager.setStableDiagnosticsEnabled(false)

        val scheduler = VoxDiagnosticsScheduler.instance(context)
        val mainThreadId = Thread.currentThread().id
        var callbackThreadId = -1L
        val latch = java.util.concurrent.CountDownLatch(1)

        scheduler.schedulePeriodicCheck {
            callbackThreadId = Thread.currentThread().id
            latch.countDown()
        }

        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(callbackThreadId != -1L)
        assertTrue("Diagnostics check must execute off the main thread", callbackThreadId != mainThreadId)
    }
}

@RunWith(RobolectricTestRunner::class)
class OfflineQueueWritesOnlyOnMutationTest {
    @Test
    fun testClearEmptyOfflineQueueDoesNotPerformRedundantDiskWrite() {
        val context: Context = RuntimeEnvironment.getApplication()
        val scheduler = VoxDiagnosticsScheduler.instance(context)

        // Ensure queue is empty first
        scheduler.clearOfflineQueue()
        val initialWrites = scheduler.diskWriteCountForTest.get()

        // Clearing an already-empty queue must NOT write to disk
        scheduler.clearOfflineQueue()
        assertEquals(initialWrites, scheduler.diskWriteCountForTest.get())
    }
}
