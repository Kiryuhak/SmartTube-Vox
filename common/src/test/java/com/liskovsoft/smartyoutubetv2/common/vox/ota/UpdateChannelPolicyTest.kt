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
    fun testProductionChannelEnforcesStable() {
        // Under Prompt 163-R51, production flavor blocks switching to TEST/BETA
        channelManager.setUpdateChannel(VoxUpdateChannel.TEST)
        assertEquals(VoxUpdateChannel.STABLE, channelManager.getUpdateChannel())
        assertFalse(channelManager.isBetaChannel())
    }

    @Test
    fun testSwitchFromTestToStableResetsBetaConsent() {
        channelManager.setBetaDiagnosticsEnabled(true)
        // Production enforces false for beta diagnostics
        assertFalse(channelManager.isBetaDiagnosticsEnabled())

        channelManager.setUpdateChannel(VoxUpdateChannel.STABLE)
        assertFalse(channelManager.isBetaDiagnosticsEnabled())
        assertEquals(VoxUpdateChannel.STABLE, channelManager.getUpdateChannel())
    }

    @Test
    fun testProductionBlocksQaChannel() {
        // Under Prompt 163-R51, production flavor blocks switching to QA
        channelManager.setUpdateChannel(VoxUpdateChannel.QA)
        assertEquals(VoxUpdateChannel.STABLE, channelManager.getUpdateChannel())
        assertFalse(channelManager.isQaChannel())
        assertFalse(channelManager.isBetaChannel())
    }

    @Test
    fun testFromIdParsing() {
        assertEquals(VoxUpdateChannel.BETA, VoxUpdateChannel.fromId("test"))
        assertEquals(VoxUpdateChannel.BETA, VoxUpdateChannel.fromId("beta"))
        assertEquals(VoxUpdateChannel.BETA, VoxUpdateChannel.fromId("BETA"))
        assertEquals(VoxUpdateChannel.QA, VoxUpdateChannel.fromId("qa"))
        assertEquals(VoxUpdateChannel.QA, VoxUpdateChannel.fromId("otaqa"))
        assertEquals(VoxUpdateChannel.QA, VoxUpdateChannel.fromId("QA"))
        assertEquals(VoxUpdateChannel.STABLE, VoxUpdateChannel.fromId("stable"))
        assertEquals(VoxUpdateChannel.STABLE, VoxUpdateChannel.fromId("unknown"))
        assertEquals(VoxUpdateChannel.STABLE, VoxUpdateChannel.fromId(null))
    }
}
