package com.liskovsoft.smartyoutubetv2.common.vox.ui

import android.content.Context
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.oauth.Account
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadJob
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadRepository
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadRequest
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadState
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxQualityPreference
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxTranslationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.lang.reflect.Proxy

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
        prefs.setDownloadedSectionExplicitlyHidden(false)
        cleanCoordinatorJobs()
        SidebarService.resetInstance()
    }

    @After
    fun tearDown() {
        cleanCoordinatorJobs()
        SidebarService.resetInstance()
        prefs.setSidebarData(null)
        prefs.setDownloadedSectionMigrated(false)
        prefs.setDownloadedSectionExplicitlyHidden(false)
    }

    private fun cleanCoordinatorJobs() {
        try {
            val coordinator = VoxDownloadCoordinator.instance(context)
            for (job in coordinator.getAllJobs()) {
                coordinator.deleteDownload(job.downloadId)
            }
        } catch (ignored: Throwable) {}
    }

    private fun injectCoordinatorJob(coordinator: VoxDownloadCoordinator, job: VoxDownloadJob) {
        val repoField = VoxDownloadCoordinator::class.java.getDeclaredField("repository").apply { isAccessible = true }
        val repo = repoField.get(coordinator) as VoxDownloadRepository
        repo.addOrUpdateJob(job)
    }

    private fun createMockAccount(name: String): Account {
        return Proxy.newProxyInstance(
            Account::class.java.classLoader,
            arrayOf(Account::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> name
                "getEmail" -> "$name@example.com"
                "isSelected" -> true
                "toString" -> "Account($name)"
                else -> null
            }
        } as Account
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
        val preExistingItems = listOf(
            Video().apply { sectionId = MediaGroup.TYPE_HOME },
            Video().apply { sectionId = MediaGroup.TYPE_SUBSCRIPTIONS },
            Video().apply { sectionId = MediaGroup.TYPE_HISTORY }
        )
        val data = Helpers.mergeData(preExistingItems, MediaGroup.TYPE_HOME, true)
        prefs.sidebarData = data
        prefs.setDownloadedSectionMigrated(false)

        val service = SidebarService.instance(context)

        assertTrue(
            "One-time migration should add Downloaded section for upgrading users",
            service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
        assertTrue(
            "Migration flag should now be true",
            prefs.isDownloadedSectionMigrated
        )

        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)
        assertFalse(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        SidebarService.resetInstance()
        val restartedService = SidebarService.instance(context)
        assertFalse(
            "Downloaded section must stay hidden on subsequent restarts after migration",
            restartedService.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
    }

    @Test
    fun testScenarioA_FreshInstallEmptyDownloads_Hide_PersistsOnColdStart() {
        val coordinator = VoxDownloadCoordinator.instance(context)
        assertEquals("Scenario A: Clean install has 0 downloads", 0, coordinator.getAllJobs().size)

        val service = SidebarService.instance(context)
        assertTrue(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // User Ренат88 turns off Downloaded section
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)
        assertFalse(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))
        assertTrue(prefs.isDownloadedSectionExplicitlyHidden)

        // Cold boot
        SidebarService.resetInstance()
        val freshBootService = SidebarService.instance(context)

        assertFalse(
            "Scenario A: Downloaded section must remain hidden on cold boot with empty library",
            freshBootService.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
        assertFalse(
            "Scenario A: getPinnedItems must not contain Downloaded section",
            freshBootService.pinnedItems.any { it.sectionId == BrowsePresenter.TYPE_DOWNLOADED }
        )
    }

    @Test
    fun testScenarioB_Upgrade91To92_Hide_PersistsOnColdStart() {
        val legacyItems = listOf(
            Video().apply { sectionId = MediaGroup.TYPE_HOME },
            Video().apply { sectionId = MediaGroup.TYPE_SUBSCRIPTIONS },
            Video().apply { sectionId = MediaGroup.TYPE_HISTORY },
            Video().apply { sectionId = MediaGroup.TYPE_SETTINGS }
        )
        prefs.sidebarData = Helpers.mergeData(legacyItems, MediaGroup.TYPE_HOME, true)
        prefs.setDownloadedSectionMigrated(false)
        prefs.setDownloadedSectionExplicitlyHidden(false)

        val service = SidebarService.instance(context)
        assertTrue("Migration adds section on first 9.2 launch", service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // User disables it
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)
        assertFalse(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // Process killed and relaunched
        SidebarService.resetInstance()
        val coldService = SidebarService.instance(context)
        assertFalse(
            "Scenario B: Section must not reappear on cold start after upgrade migration was disabled",
            coldService.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
    }

    @Test
    fun testScenarioC_PreExistingDownloadsSection_Hide_PersistsAcrossRestarts() {
        val preItems = listOf(
            Video().apply { sectionId = MediaGroup.TYPE_HOME },
            Video().apply { sectionId = BrowsePresenter.TYPE_DOWNLOADED },
            Video().apply { sectionId = MediaGroup.TYPE_SETTINGS }
        )
        prefs.sidebarData = Helpers.mergeData(preItems, MediaGroup.TYPE_HOME, true)
        prefs.setDownloadedSectionMigrated(true)
        prefs.setDownloadedSectionExplicitlyHidden(false)

        val service = SidebarService.instance(context)
        assertTrue(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // User unpins via long-press or settings
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)
        assertFalse(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // Multiple cold restarts
        for (i in 1..3) {
            SidebarService.resetInstance()
            val restarted = SidebarService.instance(context)
            assertFalse(
                "Scenario C: Section must remain hidden across restart #$i",
                restarted.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
            )
        }
    }

    @Test
    fun testScenarioD_EmptyLibrary0B_Hide_RemainsHidden() {
        val coordinator = VoxDownloadCoordinator.instance(context)
        assertTrue("Precondition: Coordinator has empty jobs", coordinator.getAllJobs().isEmpty())

        val service = SidebarService.instance(context)
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)

        SidebarService.resetInstance()
        val serviceAfterRestart = SidebarService.instance(context)

        assertFalse(
            "Scenario D: 0 B empty library must never cause section to reappear",
            serviceAfterRestart.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
    }

    @Test
    fun testScenarioE_FilesExistOnDisk_Hide_FilesAndCoordinatorJobsIntact() {
        val coordinator = VoxDownloadCoordinator.instance(context)
        val storage = VoxDownloadStorage(context)
        val req = VoxDownloadRequest(
            videoId = "test_vid_preserve",
            videoTitle = "Preserved Video",
            qualityPreference = VoxQualityPreference.QUALITY_2160P,
            translationMode = VoxTranslationMode.NONE
        )
        val job = VoxDownloadJob(
            request = req,
            initialState = VoxDownloadState.COMPLETED
        )
        injectCoordinatorJob(coordinator, job)

        val dummyFile = storage.getOutputFile(job.downloadId)
        dummyFile.parentFile?.mkdirs()
        dummyFile.writeBytes(ByteArray(1024 * 1024) { 0x55.toByte() })
        assertTrue("Precondition: dummy download file exists", dummyFile.exists())

        val service = SidebarService.instance(context)
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)
        assertFalse(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // Verify files and jobs are completely untouched by hiding sidebar UI
        assertTrue("Scenario E: File on disk must remain intact when section hidden", dummyFile.exists())
        assertEquals("Scenario E: File size must be intact", 1024 * 1024L, dummyFile.length())

        val retrievedJob = coordinator.getJob(job.downloadId)
        assertNotNull("Scenario E: Coordinator job must remain intact", retrievedJob)
        assertEquals(VoxDownloadState.COMPLETED, retrievedJob?.state)

        dummyFile.delete()
    }

    @Test
    fun testScenarioF_ActiveDownloadInProgress_Hide_DownloadContinues() {
        val coordinator = VoxDownloadCoordinator.instance(context)
        val req = VoxDownloadRequest(
            videoId = "test_vid_active",
            videoTitle = "Active Video",
            qualityPreference = VoxQualityPreference.QUALITY_1080P,
            translationMode = VoxTranslationMode.STANDARD
        )
        val job = VoxDownloadJob(
            request = req,
            initialState = VoxDownloadState.DOWNLOADING_VIDEO
        )
        injectCoordinatorJob(coordinator, job)

        val service = SidebarService.instance(context)
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)

        val activeJob = coordinator.getJob(job.downloadId)
        assertNotNull("Scenario F: Active job must exist", activeJob)
        assertEquals(VoxDownloadState.DOWNLOADING_VIDEO, activeJob?.state)
    }

    @Test
    fun testScenarioG_ProfileSwitch_ExplicitlyHiddenPreservedAcrossAccounts() {
        val service = SidebarService.instance(context)
        assertTrue(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // User hides Downloaded section in primary/current profile
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)
        assertFalse(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // Switch to account 1 (e.g. Renat88)
        val account1 = createMockAccount("UserRenat88")
        prefs.onAccountChanged(account1)
        SidebarService.resetInstance()
        val renatService = SidebarService.instance(context)
        assertFalse(
            "Scenario G: Section must remain hidden after switching to Account 1",
            renatService.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )

        // Switch to account 2 (e.g. FamilyAccount)
        val account2 = createMockAccount("FamilyAccount")
        prefs.onAccountChanged(account2)
        SidebarService.resetInstance()
        val familyService = SidebarService.instance(context)
        assertFalse(
            "Scenario G: Section must remain hidden after switching to Account 2",
            familyService.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )

        // Switch back to anonymous
        prefs.onAccountChanged(null)
        SidebarService.resetInstance()
        val anonService = SidebarService.instance(context)
        assertFalse(
            "Scenario G: Section must remain hidden when returning to anonymous profile",
            anonService.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
    }

    @Test
    fun testScenarioH_ActivityRecreationAndRefresh_RemainsHidden() {
        val service = SidebarService.instance(context)
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)

        // Simulate activity recreation / onProfileChanged / lifecycle refresh
        service.onProfileChanged()

        assertFalse(
            "Scenario H: Section must stay hidden on activity recreation/refresh",
            service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
        assertFalse(
            "Scenario H: getPinnedItems must not contain Downloaded section",
            service.pinnedItems.any { it.sectionId == BrowsePresenter.TYPE_DOWNLOADED }
        )
    }

    @Test
    fun testScenarioI_UserManuallyReEnables_SectionReappearsAndPersists() {
        val service = SidebarService.instance(context)
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)
        assertFalse(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // User goes back into settings and re-enables "Скачанные видео"
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, true)
        assertTrue(
            "Scenario I: Re-enabling section must make it pinned immediately",
            service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
        assertFalse(
            "Scenario I: Explicitly hidden flag must be cleared",
            prefs.isDownloadedSectionExplicitlyHidden
        )

        // Cold restart
        SidebarService.resetInstance()
        val restartedService = SidebarService.instance(context)
        assertTrue(
            "Scenario I: Section must remain pinned after cold restart following re-enable",
            restartedService.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
    }

    @Test
    fun testScenarioJ_ResetSidebar_RestoresDefault() {
        val service = SidebarService.instance(context)
        service.enableSection(BrowsePresenter.TYPE_DOWNLOADED, false)
        assertFalse(service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED))

        // Reset sidebar to default
        service.resetSidebar()

        assertTrue(
            "Scenario J: Reset sidebar must restore Downloaded section",
            service.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
        assertFalse(prefs.isDownloadedSectionExplicitlyHidden)
        assertTrue(prefs.isDownloadedSectionMigrated)

        SidebarService.resetInstance()
        val restartedService = SidebarService.instance(context)
        assertTrue(
            "Scenario J: Restored section must persist across restart",
            restartedService.isSectionPinned(BrowsePresenter.TYPE_DOWNLOADED)
        )
    }
}
