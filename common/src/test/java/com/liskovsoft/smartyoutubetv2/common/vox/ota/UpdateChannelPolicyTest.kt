package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateChannelPolicyTest {

    private lateinit var channelManager: VoxUpdateChannelManager

    @Before
    fun setUp() {
        channelManager = VoxUpdateChannelManager.instance(RuntimeEnvironment.getApplication())
        channelManager.setUpdateChannel(VoxUpdateChannel.STABLE)
        channelManager.setBetaDiagnosticsEnabled(false)
        channelManager.setStableDiagnosticsEnabled(false)
    }

    @Test
    fun testDefaultChannelIsStable() {
        assertEquals(VoxUpdateChannel.STABLE, channelManager.getUpdateChannel())
        assertFalse(channelManager.isBetaChannel())
    }

    @Test
    fun testSwitchToTestChannel() {
        channelManager.setUpdateChannel(VoxUpdateChannel.TEST)
        assertEquals(VoxUpdateChannel.TEST, channelManager.getUpdateChannel())
        assertTrue(channelManager.isBetaChannel())
    }

    @Test
    fun testSwitchFromTestToStableResetsBetaConsent() {
        channelManager.setUpdateChannel(VoxUpdateChannel.TEST)
        channelManager.setBetaDiagnosticsEnabled(true)
        assertTrue(channelManager.isBetaDiagnosticsEnabled())

        // Переключение обратно на стабильный канал должно сбросить согласие на бета-диагностику
        channelManager.setUpdateChannel(VoxUpdateChannel.STABLE)
        assertFalse(channelManager.isBetaDiagnosticsEnabled())
        assertEquals(VoxUpdateChannel.STABLE, channelManager.getUpdateChannel())
    }

    @Test
    fun testFromIdParsing() {
        assertEquals(VoxUpdateChannel.TEST, VoxUpdateChannel.fromId("test"))
        assertEquals(VoxUpdateChannel.TEST, VoxUpdateChannel.fromId("beta"))
        assertEquals(VoxUpdateChannel.TEST, VoxUpdateChannel.fromId("BETA"))
        assertEquals(VoxUpdateChannel.STABLE, VoxUpdateChannel.fromId("stable"))
        assertEquals(VoxUpdateChannel.STABLE, VoxUpdateChannel.fromId("unknown"))
        assertEquals(VoxUpdateChannel.STABLE, VoxUpdateChannel.fromId(null))
    }
}
