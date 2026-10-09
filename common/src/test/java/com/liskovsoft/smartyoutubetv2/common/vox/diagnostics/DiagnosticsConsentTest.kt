package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

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
class DiagnosticsConsentTest {

    private lateinit var channelManager: VoxUpdateChannelManager

    @Before
    fun setUp() {
        channelManager = VoxUpdateChannelManager.instance(RuntimeEnvironment.getApplication())
        channelManager.setUpdateChannel(VoxUpdateChannel.STABLE)
        channelManager.setBetaDiagnosticsEnabled(false)
        channelManager.setStableDiagnosticsEnabled(false)
        channelManager.setLastDiagnosticsSubmissionTimestamp(0L)
    }

    @Test
    fun testDefaultDiagnosticsAreDisabled() {
        assertFalse(channelManager.isBetaDiagnosticsEnabled())
        assertFalse(channelManager.isStableDiagnosticsEnabled())
        assertFalse(channelManager.isDiagnosticsActive())
    }

    @Test
    fun testTestChannelDiagnosticsConsent() {
        channelManager.setUpdateChannel(VoxUpdateChannel.TEST)
        assertFalse(channelManager.isDiagnosticsActive())

        channelManager.setBetaDiagnosticsEnabled(true)
        assertTrue(channelManager.isBetaDiagnosticsEnabled())
        assertTrue(channelManager.isDiagnosticsActive())
    }

    @Test
    fun testStableChannelDiagnosticsConsentIsSeparate() {
        channelManager.setUpdateChannel(VoxUpdateChannel.STABLE)
        assertFalse(channelManager.isDiagnosticsActive())

        channelManager.setStableDiagnosticsEnabled(true)
        assertTrue(channelManager.isStableDiagnosticsEnabled())
        assertTrue(channelManager.isDiagnosticsActive())
        // Бета согласие не должно включиться
        assertFalse(channelManager.isBetaDiagnosticsEnabled())
    }

    @Test
    fun testSwitchingTestToStableRevokesBetaConsent() {
        channelManager.setUpdateChannel(VoxUpdateChannel.TEST)
        channelManager.setBetaDiagnosticsEnabled(true)
        assertTrue(channelManager.isBetaDiagnosticsEnabled())

        // Переключение на Stable
        channelManager.setUpdateChannel(VoxUpdateChannel.STABLE)
        assertFalse(channelManager.isBetaDiagnosticsEnabled())
        assertFalse(channelManager.isStableDiagnosticsEnabled())
        assertFalse(channelManager.isDiagnosticsActive())
    }
}
