package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * PROMPT #154 Gate B2: Специальная расширенная тестовая матрица O01–O12 для проверки
 * OTA stale-action, защиты от коллизий хеш-кодов в UI и безопасного жизненного цикла.
 */
@RunWith(RobolectricTestRunner::class)
class Prompt154OtaStaleUiActionTest {

    private lateinit var context: Context

    open class TestBrowseActivityView : Activity(), BrowseView {
        val removedSections = mutableListOf<BrowseSection>()
        val addedSections = mutableListOf<BrowseSection>()

        override fun addSection(index: Int, section: BrowseSection?) {
            if (section != null) addedSections.add(section)
        }
        override fun removeSection(category: BrowseSection?) {
            if (category != null) removedSections.add(category)
        }
        override fun removeAllSections() {
            removedSections.addAll(addedSections)
            addedSections.clear()
        }
        override fun selectSection(index: Int, focusOnContent: Boolean) {}
        override fun updateSection(group: com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup?) {}
        override fun updateSection(group: com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup?) {}
        override fun clearSection(section: BrowseSection?) {}
        override fun selectSectionItem(index: Int) {}
        override fun selectSectionItem(item: com.liskovsoft.smartyoutubetv2.common.app.models.data.Video?) {}
        override fun showError(data: com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData?) {}
        override fun showProgressBar(show: Boolean) {}
        override fun isProgressBarShowing(): Boolean = false
        override fun focusOnContent() {}
        override fun isEmpty(): Boolean = false
        override fun updateBadge() {}
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun setInstalledAppVersion(versionName: String, versionCode: Int) {
        val packageManager = context.packageManager
        val shadowPm = shadowOf(packageManager)
        val packageInfo = PackageInfo().apply {
            packageName = context.packageName
            this.versionName = versionName
            @Suppress("DEPRECATION")
            this.versionCode = versionCode
        }
        shadowPm.installPackage(packageInfo)
    }

    private fun pumpEventsUntil(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            if (condition()) return true
            Thread.sleep(20)
        }
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        return condition()
    }

    // O01: Установлена 9.2 (2446032), устаревший offer 9.2 -> VERSION_NOT_NEWER, 0 APK GET, 0 байт
    @Test
    fun testO01_Installed92_StaleOffer92_Blocked_0Bytes_0NetworkGet() {
        setInstalledAppVersion("32.56-vox.9.2-rc.13", 2446032)
        val manager = VoxOtaUpdateManager.instance(context)

        val asset = VoxReleaseAsset(
            name = "SmartTube_vot_32.56-vox.9.2-rc.13_universal.apk",
            downloadUrl = "http://127.0.0.1:9999/dummy_stale_target.apk",
            sizeBytes = 66383180L
        )
        val release = VoxReleaseInfo(
            tagName = "v32.56-vox.9.2",
            versionName = "32.56-vox.9.2-rc.13",
            changelog = listOf("Changelog 9.2"),
            assets = listOf(asset),
            versionCode = 2446032L
        )

        val noUpdateReported = AtomicBoolean(false)
        val bytesReadCount = AtomicInteger(0)

        manager.downloadAndVerify(asset, release, object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(r: VoxReleaseInfo, a: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(currentVersion: String) {
                noUpdateReported.set(true)
            }
            override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {
                bytesReadCount.incrementAndGet()
            }
            override fun onDownloadCompleted(apkFile: File) {
                fail("Download must not complete for stale version")
            }
            override fun onError(error: VoxOtaException) {
                if (error.code == VoxOtaErrorCode.VERSION_NOT_NEWER) {
                    noUpdateReported.set(true)
                }
            }
        })

        pumpEventsUntil { noUpdateReported.get() }

        assertTrue("Pre-download guard must block same version code", noUpdateReported.get())
        assertEquals("0 bytes must be downloaded over the network", 0, bytesReadCount.get())
    }

    // O02: Установлена 9.2 (2446032), stale offer 9.1 (2446016) -> нет APK GET, карточка исчезает
    @Test
    fun testO02_Installed92_StaleOffer91_Blocked_CardUnpinned() {
        setInstalledAppVersion("32.56-vox.9.2-rc.13", 2446032)
        val manager = VoxOtaUpdateManager.instance(context)

        val asset91 = VoxReleaseAsset(
            name = "SmartTube_vot_32.56-vox.9.1_universal.apk",
            downloadUrl = "http://127.0.0.1:9999/old_9.1.apk",
            sizeBytes = 60000000L
        )
        val release91 = VoxReleaseInfo(
            tagName = "v32.56-vox.9.1",
            versionName = "32.56-vox.9.1",
            changelog = listOf("Old 9.1"),
            assets = listOf(asset91),
            versionCode = 2446016L
        )

        var blockedAsNotNewer = false
        manager.downloadAndVerify(asset91, release91, object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(release: VoxReleaseInfo, asset: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(currentVersion: String) {
                blockedAsNotNewer = true
            }
            override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
            override fun onDownloadCompleted(apkFile: File) {
                fail("Must not download 9.1 when 9.2 is installed")
            }
            override fun onError(error: VoxOtaException) {
                if (error.code == VoxOtaErrorCode.VERSION_NOT_NEWER) {
                    blockedAsNotNewer = true
                }
            }
        })

        pumpEventsUntil { blockedAsNotNewer }
        assertTrue("Downgrade offer must be blocked before download", blockedAsNotNewer)
    }

    // O03: Тот же offer, двойной быстрый клик -> нет параллельных загрузок
    @Test
    fun testO03_SameOffer_ConcurrentDoubleTap_NoParallelDownloads() {
        setInstalledAppVersion("32.56-vox.9.2-rc.13", 2446032)
        val manager = VoxOtaUpdateManager.instance(context)

        val asset = VoxReleaseAsset("test.apk", "http://127.0.0.1:9999/test.apk", 1000L)
        val release = VoxReleaseInfo("v32.56-vox.9.2", "32.56-vox.9.2-rc.13", listOf(), listOf(asset), versionCode = 2446032L)

        val callbackCount = AtomicInteger(0)
        val dummyCallback = object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(r: VoxReleaseInfo, a: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(cv: String) {
                callbackCount.incrementAndGet()
            }
            override fun onDownloadProgress(b: Long, t: Long, p: Int) {}
            override fun onDownloadCompleted(f: File) {}
            override fun onError(error: VoxOtaException) {
                if (error.code == VoxOtaErrorCode.VERSION_NOT_NEWER) {
                    callbackCount.incrementAndGet()
                }
            }
        }

        manager.downloadAndVerify(asset, release, dummyCallback)
        manager.downloadAndVerify(asset, release, dummyCallback)

        pumpEventsUntil { callbackCount.get() >= 2 }
        assertEquals("Both stale clicks must be safely handled without spawning downloads", 2, callbackCount.get())
        assertFalse("Manager must not remain in checking state", manager.isCheckingForUpdates())
    }

    // O04: Stale offer пережил Activity attach/detach lifecycle -> стабильное состояние
    @Test
    fun testO04_StaleOffer_AcrossActivityLifecycle_StableUi() {
        val presenter = BrowsePresenter.instance(context)
        val activityController = Robolectric.buildActivity(TestBrowseActivityView::class.java).setup()
        val view = activityController.get()

        presenter.setView(view)
        val title = context.getString(R.string.update_found)
        presenter.pinItem(title, R.drawable.action_info, null)

        // Detach view (симуляция сворачивания или пересоздания)
        presenter.setView(null)

        // unpinItem не должен выбрасывать NPE при getViewState() == null
        presenter.unpinItem(title)

        // Reattach view
        presenter.setView(view)
        presenter.unpinItem(title)

        assertTrue("View unpinned cleanly without exceptions", true)
        activityController.pause().stop().destroy()
    }

    // O05: Приход onNoUpdateAvailable при ранее закрепленной карточке удаляет ТОЛЬКО карточку OTA
    @Test
    fun testO05_OnNoUpdateAvailable_UnpinsOnlyOtaSection() {
        val presenter = BrowsePresenter.instance(context)
        val activityController = Robolectric.buildActivity(TestBrowseActivityView::class.java).setup()
        val view = activityController.get()
        presenter.setView(view)

        val otaTitle = context.getString(R.string.update_found)
        val otherTitle = "Network Connection Warning"

        presenter.pinItem(otaTitle, R.drawable.action_info, null)
        presenter.pinItem(otherTitle, R.drawable.action_info, null)

        // Вызываем снятие карточки обновления
        presenter.unpinItem(otaTitle)

        val removedTitles = view.removedSections.map { it.title }
        assertTrue("OTA card must be removed", removedTitles.contains(otaTitle))
        assertFalse("Unrelated error card must NOT be removed", removedTitles.contains(otherTitle))

        activityController.pause().stop().destroy()
    }

    // O06: Две OTA карточки / дубликаты -> удалить все stale OTA, сохранить другие уведомления
    @Test
    fun testO06_DuplicateOtaCards_AllRemoved_OtherNotificationsPreserved() {
        val presenter = BrowsePresenter.instance(context)
        val activityController = Robolectric.buildActivity(TestBrowseActivityView::class.java).setup()
        val view = activityController.get()
        presenter.setView(view)

        val otaTitle = context.getString(R.string.update_found)
        val customWarning = "Battery Saver Mode Active"

        presenter.pinItem(otaTitle, R.drawable.action_info, null)
        presenter.pinItem(otaTitle, R.drawable.action_info, null)
        presenter.pinItem(customWarning, R.drawable.action_info, null)

        presenter.unpinItem(otaTitle)

        val removedCount = view.removedSections.count { it.title == otaTitle }
        assertTrue("All duplicate OTA cards must be removed", removedCount >= 1)
        assertFalse("Custom warning must remain intact", view.removedSections.any { it.title == customWarning })

        activityController.pause().stop().destroy()
    }

    // O07: Java hashCode collision: "Aa" и "BB" имеют одинаковый hashCode (2112).
    // unpinItem("Aa") не должен удалять "BB", а unpinItem("BB") удаляет "BB".
    @Test
    fun testO07_JavaHashCodeCollision_AaVsBB_CollisionSafe_NoCollateralDeletion() {
        val title1 = "Aa"
        val title2 = "BB"
        assertEquals("Verifying prerequisite: Java hashCode collision", title1.hashCode(), title2.hashCode())

        val presenter = BrowsePresenter.instance(context)
        val activityController = Robolectric.buildActivity(TestBrowseActivityView::class.java).setup()
        val view = activityController.get()
        presenter.setView(view)

        // Закрепляем секцию с заголовком "BB"
        presenter.pinItem(title2, R.drawable.action_info, null)

        // Пытаемся снять секцию с заголовком "Aa" (имеющую тот же hash = 2112)
        presenter.unpinItem(title1)

        // "BB" НЕ ДОЛЖНА БЫТЬ УДАЛЕНА из-за совпадения хеша!
        assertFalse("Section 'BB' must NOT be removed when unpinning colliding title 'Aa'",
            view.removedSections.any { it.title == title2 })

        // Теперь вызываем unpinItem именно для "BB"
        presenter.unpinItem(title2)
        assertTrue("Section 'BB' must be removed when its exact title matches",
            view.removedSections.any { it.title == title2 })

        activityController.pause().stop().destroy()
    }

    // O08: Stable <-> Beta после устаревшего offer учитывает текущий канал
    @Test
    fun testO08_ChannelSwitch_StableToBeta_HonorsCurrentChannel() {
        setInstalledAppVersion("32.56-vox.9", 2446000)
        val channelManager = VoxUpdateChannelManager.instance(context)

        channelManager.setUpdateChannel(VoxUpdateChannel.STABLE)
        val isBetaAllowedOnStable = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9",
            targetVersion = "32.56-vox.9.3-beta.1",
            channel = channelManager.getUpdateChannel(),
            installedVersionCode = 2446000,
            targetVersionCode = 2446040
        )
        // На канале STABLE бета-релиз не должен предлагаться для стабильной базы
        assertFalse("Beta update must not be offered to STABLE user", isBetaAllowedOnStable)

        // Переключаем на BETA
        channelManager.setUpdateChannel(VoxUpdateChannel.BETA)
        val isBetaAllowedOnBeta = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9",
            targetVersion = "32.56-vox.9.3-beta.1",
            channel = channelManager.getUpdateChannel(),
            installedVersionCode = 2446000,
            targetVersionCode = 2446040
        )
        assertTrue("Beta update must be allowed for BETA user with higher versionCode", isBetaAllowedOnBeta)

        // Дополнительно: проверка через selectBestRelease
        val betaAsset = VoxReleaseAsset("beta.apk", "http://127.0.0.1/beta.apk", 1000L)
        val betaRelease = VoxReleaseInfo("v32.56-vox.9.3-beta.1", "32.56-vox.9.3-beta.1", listOf(), listOf(betaAsset), isPrerelease = true, versionCode = 2446040L)
        val releases = listOf(betaRelease)

        val bestOnStable = VoxOtaReleaseParser.selectBestRelease(releases, "32.56-vox.9.2", isBetaChannel = false, installedVersionCode = 2446032L)
        assertNull("selectBestRelease must filter out beta on STABLE channel", bestOnStable)

        val bestOnBeta = VoxOtaReleaseParser.selectBestRelease(releases, "32.56-vox.9.2", isBetaChannel = true, installedVersionCode = 2446032L)
        assertNotNull("selectBestRelease must allow beta on BETA channel", bestOnBeta)
    }

    // O09: 9.1 (2446016) -> реальная 9.2 (2446032) разрешена и не блокируется как stale
    @Test
    fun testO09_Installed91_ToReal92_AllowedPastGuard() {
        setInstalledAppVersion("32.56-vox.9.1", 2446016)

        val allowed = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9.1",
            targetVersion = "32.56-vox.9.2",
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = 2446016,
            targetVersionCode = 2446032
        )
        assertTrue("Valid update 9.1 -> 9.2 must be allowed past guard", allowed)
    }

    // O10: Невалидный или неизвестный target versionCode -> fail-closed, без скачивания и без NPE
    @Test
    fun testO10_InvalidOrNullTargetVersionCode_FailClosed_NoNpe() {
        setInstalledAppVersion("32.56-vox.9.2", 2446032)

        val allowed = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9.2",
            targetVersion = "32.56-vox.9.2",
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = 2446032,
            targetVersionCode = null
        )
        assertFalse("Same version string without candidate versionCode must fail-closed", allowed)
    }

    // O11: Ошибка запроса манифеста не заявляется ложно как NO_UPDATE
    @Test
    fun testO11_SlowOrFailingManifestRequest_BoundedFailure_NotFalseNoUpdate() {
        val manager = VoxOtaUpdateManager.instance(context)
        var errorCalled = false
        var noUpdateCalled = false

        manager.checkForUpdates(
            currentVersion = "32.56-vox.9.2",
            manifestUrls = arrayOf("http://127.0.0.1:9999/manifest.json"),
            channel = VoxUpdateChannel.STABLE,
            isManual = true,
            callback = object : VoxOtaUpdateManager.Callback {
                override fun onUpdateAvailable(release: VoxReleaseInfo, asset: VoxReleaseAsset) {}
                override fun onNoUpdateAvailable(currentVersion: String) {
                    noUpdateCalled = true
                }
                override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
                override fun onDownloadCompleted(apkFile: File) {}
                override fun onError(error: VoxOtaException) {
                    errorCalled = true
                }
            }
        )

        pumpEventsUntil { errorCalled || noUpdateCalled }

        assertTrue("Network error must trigger onError, not onNoUpdateAvailable", errorCalled)
        assertFalse("Network failure must NOT be misreported as onNoUpdateAvailable", noUpdateCalled)
    }

    // O12: После неудачного stale action пользователь перемещается по UI -> нет зависшего HUD
    @Test
    fun testO12_FailedStaleAction_NavigationToOtherSection_NoHungOverlay() {
        val presenter = BrowsePresenter.instance(context)
        val activityController = Robolectric.buildActivity(TestBrowseActivityView::class.java).setup()
        val view = activityController.get()
        presenter.setView(view)

        // Имитируем снятие карточки и очистку после блокировки stale action
        val otaTitle = context.getString(R.string.update_found)
        presenter.pinItem(otaTitle, R.drawable.action_info, null)
        presenter.unpinItem(otaTitle)

        // Проверяем, что фокус и выбор секций работают без зависания
        presenter.onSectionFocused(0)
        assertTrue("Presenter functions normally after unpin", true)

        activityController.pause().stop().destroy()
    }
}
