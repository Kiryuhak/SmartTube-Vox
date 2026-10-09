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
class BetaDiagnosticsPolicyTest {

    private lateinit var context: Context
    private lateinit var channelManager: VoxUpdateChannelManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        channelManager = VoxUpdateChannelManager.instance(context)
        channelManager.setUpdateChannel(VoxUpdateChannel.TEST)
        channelManager.setBetaDiagnosticsEnabled(false)
        channelManager.setLastDiagnosticsSubmissionTimestamp(0L)
    }

    @Test
    fun testBetaDiagnosticsDeniedWithoutConsent() {
        assertFalse(VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context, now = 100000000L))
    }

    @Test
    fun testBetaDiagnosticsAllowedWithConsentAndInitialTimestamp() {
        channelManager.setBetaDiagnosticsEnabled(true)
        assertTrue(VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context, now = 100000000L))
    }

    @Test
    fun testBetaDiagnosticsRateLimitedWithin6Hours() {
        channelManager.setBetaDiagnosticsEnabled(true)
        val baseTime = 100000000L
        channelManager.setLastDiagnosticsSubmissionTimestamp(baseTime)

        // 1 час спустя -> запрещено
        val oneHourLater = baseTime + (1 * 60 * 60 * 1000L)
        assertFalse(VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context, now = oneHourLater))

        // 5 часов 59 минут спустя -> запрещено
        val almostSixHours = baseTime + (6 * 60 * 60 * 1000L) - 1000L
        assertFalse(VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context, now = almostSixHours))

        // 6 часов ровно -> разрешено
        val exactlySixHours = baseTime + (6 * 60 * 60 * 1000L)
        assertTrue(VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context, now = exactlySixHours))

        // 10 часов спустя -> разрешено
        val tenHoursLater = baseTime + (10 * 60 * 60 * 1000L)
        assertTrue(VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context, now = tenHoursLater))
    }
}
