package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannel
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannelManager
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DiagnosticsSchedulerTest {

    private lateinit var context: Context
    private lateinit var scheduler: VoxDiagnosticsScheduler
    private lateinit var channelManager: VoxUpdateChannelManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        scheduler = VoxDiagnosticsScheduler.instance(context)
        channelManager = VoxUpdateChannelManager.instance(context)
        channelManager.setUpdateChannel(VoxUpdateChannel.STABLE)
        channelManager.setBetaDiagnosticsEnabled(false)
        channelManager.setStableDiagnosticsEnabled(false)
        channelManager.setLastDiagnosticsSubmissionTimestamp(0L)
    }

    @Test
    fun testSchedulerSkipsWhenNoConsentGiven() {
        val latch = CountDownLatch(1)
        var executed = false

        scheduler.schedulePeriodicCheck { success ->
            executed = success
            latch.countDown()
        }

        latch.await(3, TimeUnit.SECONDS)
        assertFalse(executed)
    }

    @Test
    fun testSchedulerSkipsWhenRateLimited() {
        channelManager.setUpdateChannel(VoxUpdateChannel.TEST)
        channelManager.setBetaDiagnosticsEnabled(true)
        // Задаем недавнее время отправки (1 минута назад)
        channelManager.setLastDiagnosticsSubmissionTimestamp(System.currentTimeMillis() - 60_000L)

        val latch = CountDownLatch(1)
        var executed = false

        scheduler.schedulePeriodicCheck { success ->
            executed = success
            latch.countDown()
        }

        latch.await(3, TimeUnit.SECONDS)
        assertFalse(executed)
    }
}
