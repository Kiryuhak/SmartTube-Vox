package com.liskovsoft.smartyoutubetv2.common.vox.ui

import android.content.Context
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DownloadsSectionPersistenceTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPrefs

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = AppPrefs.instance(context)
        prefs.setSidebarData(null)
        prefs.setDownloadedSectionMigrated(false)
        SidebarService.resetInstance()
    }

    @After
    fun tearDown() {
        SidebarService.resetInstance()
        prefs.setSidebarData(null)
        prefs.setDownloadedSectionMigrated(false)
    }

    @Test
    fun testFreshInstallEnablesDownloadedSectionByDefault() {
        val service = SidebarService.instance(context)

        assertTrue(
            "Fresh install should have Downloaded section pinned by default",
            service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
        assertTrue(
            "Fresh install should mark downloaded section as migrated",
            prefs.isDownloadedSectionMigrated
        )
    }

    @Test
    fun testHidingDownloadedSectionPersistsAcrossAppRestart() {
        val service = SidebarService.instance(context)
        assertTrue(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // User unpins / disables the Downloaded section (via settings toggle or long-press menu)
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)
        assertFalse(
            "Downloaded section should be unpinned after disable",
            service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )

        // Simulate app restart / process kill
        SidebarService.resetInstance()
        val restartedService = SidebarService.instance(context)

        assertFalse(
            "Downloaded section must remain unpinned after app restart",
            restartedService.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
    }

    @Test
    fun testOneTimeMigrationAddsDownloadedSectionIfMissing() {
        // Prepare pre-existing user state without Downloaded section and migration flag = false
        val preExistingItems = listOf(
            Video().apply { sectionId = MediaGroup.TYPE_HOME },
            Video().apply { sectionId = MediaGroup.TYPE_SUBSCRIPTIONS },
            Video().apply { sectionId = MediaGroup.TYPE_HISTORY }
        )
        val data = Helpers.mergeData(preExistingItems, MediaGroup.TYPE_HOME, true)
        prefs.sidebarData = data
        prefs.setDownloadedSectionMigrated(false)

        // First launch on new version with migration logic
        val service = SidebarService.instance(context)

        assertTrue(
            "One-time migration should add Downloaded section for upgrading users",
            service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
        assertTrue(
            "Migration flag should now be true",
            prefs.isDownloadedSectionMigrated
        )

        // Now user decides they don't want it and disables it
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)
        assertFalse(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // Subsequent restart must respect user choice and NOT re-add it
        SidebarService.resetInstance()
        val restartedService = SidebarService.instance(context)
        assertFalse(
            "Downloaded section must stay hidden on subsequent restarts after migration",
            restartedService.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
    }

    @Test
    fun testResetSidebarRestoresDefaultsIncludingDownloaded() {
        val service = SidebarService.instance(context)
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)
        assertFalse(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // Reset sidebar to default
        service.resetSidebar()

        assertTrue(
            "Reset sidebar should restore Downloaded section",
            service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
        assertTrue(prefs.isDownloadedSectionMigrated)

        // Verify it persists after restart
        SidebarService.resetInstance()
        val restartedService = SidebarService.instance(context)
        assertTrue(
            "Downloaded section should remain pinned after restart following reset",
            restartedService.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
    }
}
