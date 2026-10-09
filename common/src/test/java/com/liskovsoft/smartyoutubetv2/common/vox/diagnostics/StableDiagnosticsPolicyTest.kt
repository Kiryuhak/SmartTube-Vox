package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannel
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannelManager
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StableDiagnosticsPolicyTest {

    private lateinit var context: Context
    private lateinit var channelManager: VoxUpdateChannelManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        channelManager = VoxUpdateChannelManager.instance(context)
        channelManager.setUpdateChannel(VoxUpdateChannel.STABLE)
        channelManager.setStableDiagnosticsEnabled(false)
        channelManager.setLastDiagnosticsSubmissionTimestamp(0L)
    }

    @Test
    fun testStableDiagnosticsDeniedWithoutConsent() {
        assertFalse(VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context, now = 100000000L))
    }

    @Test
    fun testStableDiagnosticsAllowedWithConsentAndInitialTimestamp() {
        channelManager.setStableDiagnosticsEnabled(true)
        assertTrue(VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context, now = 100000000L))
    }

    @Test
    fun testStableDiagnosticsRateLimitedWithin72Hours() {
        channelManager.setStableDiagnosticsEnabled(true)
        val baseTime = 100000000L
        channelManager.setLastDiagnosticsSubmissionTimestamp(baseTime)

        // 24 часа спустя -> запрещено
        val oneDayLater = baseTime + (24 * 60 * 60 * 1000L)
        assertFalse(VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context, now = oneDayLater))

        // 71 час 59 минут спустя -> запрещено
        val almost72Hours = baseTime + (72 * 60 * 60 * 1000L) - 1000L
        assertFalse(VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context, now = almost72Hours))

        // 72 часа ровно -> разрешено
        val exactly72Hours = baseTime + (72 * 60 * 60 * 1000L)
        assertTrue(VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context, now = exactly72Hours))

        // 96 часов спустя -> разрешено
        val fourDaysLater = baseTime + (96 * 60 * 60 * 1000L)
        assertTrue(VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context, now = fourDaysLater))
    }
}
