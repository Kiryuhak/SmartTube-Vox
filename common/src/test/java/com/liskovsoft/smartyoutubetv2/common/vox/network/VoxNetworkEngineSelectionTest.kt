package com.liskovsoft.smartyoutubetv2.common.vox.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NetworkEngineManualSelectionTest {
    @Test
    fun testManualEngineSelectionPersistenceAndFallback() {
        val context = RuntimeEnvironment.getApplication()
        val tweaks = com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData.instance(context)

        // 1. Select OkHttp manually
        tweaks.playerDataSource = com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP
        tweaks.clearRuntimeFallbackEngine()
        assertTrue(tweaks.isManualEngineSelected())
        assertEquals(com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP, tweaks.effectivePlayerDataSource)

        // 2. Set runtime fallback to Default
        tweaks.setRuntimeFallbackEngine(com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)
        // Persistent source is still OkHttp!
        assertEquals(com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP, tweaks.playerDataSource)
        // Effective source is runtime fallback
        assertEquals(com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, tweaks.effectivePlayerDataSource)

        // 3. Clear runtime fallback
        tweaks.clearRuntimeFallbackEngine()
        assertEquals(com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP, tweaks.effectivePlayerDataSource)

        // 4. Select Auto (Default)
        tweaks.playerDataSource = com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT
        assertFalse(tweaks.isManualEngineSelected())
    }
}
