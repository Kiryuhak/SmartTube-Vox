package com.liskovsoft.smartyoutubetv2.common.vox.badge

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class QualityBadgeSettingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun qualityBadgesSettingDefaultsToTrue() {
        val uiData = MainUIData.instance(context)
        assertTrue("Quality badges must be enabled by default", uiData.isQualityBadgesEnabled)
    }

    @Test
    fun qualityBadgesSettingCanBeDisabledAndPersisted() {
        val uiData = MainUIData.instance(context)
        uiData.enableQualityBadges(false)
        assertFalse("Quality badges must be disabled after toggle", uiData.isQualityBadgesEnabled)

        // Process delayed persist task
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Re-read profile
        uiData.onProfileChanged()
        assertFalse("Quality badges state false must persist across reloads", uiData.isQualityBadgesEnabled)

        // Re-enable
        uiData.enableQualityBadges(true)
        assertTrue("Quality badges must be enabled after re-toggle", uiData.isQualityBadgesEnabled)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        uiData.onProfileChanged()
        assertTrue("Quality badges state true must persist across reloads", uiData.isQualityBadgesEnabled)
    }
}
