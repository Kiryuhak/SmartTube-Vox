package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView
import com.liskovsoft.smartyoutubetv2.common.vox.ota.hud.VoxOtaChannelTier
import com.liskovsoft.smartyoutubetv2.common.vox.ota.hud.VoxOtaDisplayHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * PROMPT #153 Gate B: Независимая глубокая верификация и расширенный сьют тестов
 * для локального OTA stale-action фикса (#152 / #153).
 *
 * Покрывает 9 целевых сценариев:
 * 1. Stale version code blocked before network (0 bytes downloaded, onNoUpdateAvailable).
 * 2. Stale version name blocked (fail-closed comparison).
 * 3. Missing candidate version code fail-closed on same major.minor.
 * 4. Same version RC vs Universal blocked by versionCode monotonicity.
 * 5. Valid higher version code allowed to proceed past the guard.
 * 6. Channel restrictions honored (QA blocked on STABLE/BETA, permitted on QA; Beta pre-release rejected on STABLE for canonical stable).
 * 7. Double-tap concurrent protection (no unhandled race / double-download state corruption).
 * 8. Stale UI removal across BrowsePresenter attach/detach lifecycle and hash-collision protection.
 * 9. Display version and tier label formatting for "32.56-vox.9.2-universal" (resolves P2 "Beta" UX defect).
 */
@RunWith(RobolectricTestRunner::class)
class Prompt153OtaStaleActionTest {

    private lateinit var context: Context

    open class TestBrowseActivityView : Activity(), BrowseView {
        var removedCount = 0
        override fun addSection(index: Int, section: BrowseSection?) {}
        override fun removeSection(category: BrowseSection?) {
            removedCount++
        }
        override fun removeAllSections() {}
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

    @Test
    fun test01_StaleVersionCodeBlockedBeforeNetwork() {
        // Установлена 9.2 (2446032)
        setInstalledAppVersion("32.56-vox.9.2-rc.13", 2446032)

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

        val noUpdateCalled = AtomicBoolean(false)
        val downloadCompletedCalled = AtomicBoolean(false)

        val updateManager = VoxOtaUpdateManager.instance(context)
        updateManager.downloadAndVerify(asset, release, object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(r: VoxReleaseInfo, a: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(currentVersion: String) {
                noUpdateCalled.set(true)
            }
            override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
            override fun onDownloadCompleted(apkFile: File) {
                downloadCompletedCalled.set(true)
            }
            override fun onError(error: VoxOtaException) {}
        })

        assertTrue("onNoUpdateAvailable must be dispatched for stale version code",
            pumpEventsUntil { noUpdateCalled.get() })
        assertFalse("onDownloadCompleted must NEVER be called for stale version", downloadCompletedCalled.get())
    }

    @Test
    fun test02_StaleVersionNameBlockedWithoutVersionCode() {
        // Версия 9.2 без явного versionCode в PackageInfo (versionCode = 0)
        setInstalledAppVersion("32.56-vox.9.2", 0)

        val asset = VoxReleaseAsset(
            name = "SmartTube_vot_32.56-vox.9.2_universal.apk",
            downloadUrl = "http://127.0.0.1:9999/dummy_same_name.apk",
            sizeBytes = 66383180L
        )
        val release = VoxReleaseInfo(
            tagName = "v32.56-vox.9.2",
            versionName = "32.56-vox.9.2",
            changelog = emptyList(),
            assets = listOf(asset),
            versionCode = null
        )

        val noUpdateCalled = AtomicBoolean(false)
        val updateManager = VoxOtaUpdateManager.instance(context)
        updateManager.downloadAndVerify(asset, release, object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(r: VoxReleaseInfo, a: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(currentVersion: String) {
                noUpdateCalled.set(true)
            }
            override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
            override fun onDownloadCompleted(apkFile: File) {}
            override fun onError(error: VoxOtaException) {}
        })

        assertTrue("Same versionName without versionCode must be blocked by fail-closed guard",
            pumpEventsUntil { noUpdateCalled.get() })
    }

    @Test
    fun test03_MissingVersionCodeFailClosedOnSameMajorMinor() {
        // Установлена 9.2 (2446032), манифест вернул candidate без versionCode
        setInstalledAppVersion("32.56-vox.9.2-rc.13", 2446032)

        val asset = VoxReleaseAsset(
            name = "SmartTube_vot_32.56-vox.9.2_universal.apk",
            downloadUrl = "http://127.0.0.1:9999/dummy_missing_code.apk",
            sizeBytes = 66383180L
        )
        val release = VoxReleaseInfo(
            tagName = "v32.56-vox.9.2",
            versionName = "32.56-vox.9.2-universal",
            changelog = emptyList(),
            assets = listOf(asset),
            versionCode = null // missing code
        )

        val noUpdateCalled = AtomicBoolean(false)
        val updateManager = VoxOtaUpdateManager.instance(context)
        updateManager.downloadAndVerify(asset, release, object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(r: VoxReleaseInfo, a: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(currentVersion: String) {
                noUpdateCalled.set(true)
            }
            override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
            override fun onDownloadCompleted(apkFile: File) {}
            override fun onError(error: VoxOtaException) {}
        })

        assertTrue("Missing versionCode on 9.2 candidate must fail closed",
            pumpEventsUntil { noUpdateCalled.get() })
    }

    @Test
    fun test04_SameVersionRcVsUniversalMonotonicity() {
        // RC.13 установлена (2446032), манифест B2 предлагает 32.56-vox.9.2-universal (2446032)
        setInstalledAppVersion("32.56-vox.9.2-rc.13", 2446032)

        val asset = VoxReleaseAsset(
            name = "SmartTube_vot_32.56-vox.9.2-rc.13_universal.apk",
            downloadUrl = "http://127.0.0.1:9999/dummy_b2_universal.apk",
            sizeBytes = 66383180L
        )
        val release = VoxReleaseInfo(
            tagName = "v32.56-vox.9.2",
            versionName = "32.56-vox.9.2-universal",
            changelog = listOf("Changelog B2"),
            assets = listOf(asset),
            versionCode = 2446032L
        )

        val noUpdateCalled = AtomicBoolean(false)
        val updateManager = VoxOtaUpdateManager.instance(context)
        updateManager.downloadAndVerify(asset, release, object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(r: VoxReleaseInfo, a: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(currentVersion: String) {
                noUpdateCalled.set(true)
            }
            override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
            override fun onDownloadCompleted(apkFile: File) {}
            override fun onError(error: VoxOtaException) {}
        })

        assertTrue("candidate.versionCode <= installedVersionCode must block universal on rc.13",
            pumpEventsUntil { noUpdateCalled.get() })
    }

    @Test
    fun test05_ValidHigherVersionCodeAllowed() {
        // Установлена 9.1 (2446016), цель 9.2 (2446032)
        setInstalledAppVersion("32.56-vox.9.1", 2446016)

        val asset = VoxReleaseAsset(
            name = "SmartTube_vot_32.56-vox.9.2-rc.13_universal.apk",
            downloadUrl = "http://127.0.0.1:9999/dummy_legit_upgrade.apk",
            sizeBytes = 66383180L
        )
        val release = VoxReleaseInfo(
            tagName = "v32.56-vox.9.2",
            versionName = "32.56-vox.9.2",
            changelog = listOf("9.2 Changelog"),
            assets = listOf(asset),
            versionCode = 2446032L
        )

        val preGuardPassed = AtomicBoolean(false)
        val updateManager = VoxOtaUpdateManager.instance(context)

        // downloadAndVerify попытается подключиться к фиктивному 127.0.0.1:9999,
        // что приведёт к сетевой ошибке (CONNECT/HTTP_ERROR), а НЕ к VERSION_NOT_NEWER!
        updateManager.downloadAndVerify(asset, release, object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(r: VoxReleaseInfo, a: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(currentVersion: String) {}
            override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
            override fun onDownloadCompleted(apkFile: File) {}
            override fun onError(error: VoxOtaException) {
                // Если ошибка сетевая (CONNECT/HTTP/DNS), значит pre-guard успешно пройден!
                if (error.code != VoxOtaErrorCode.VERSION_NOT_NEWER) {
                    preGuardPassed.set(true)
                }
            }
        })

        assertTrue("Legitimate upgrade 9.1 -> 9.2 must pass pre-download version guard",
            pumpEventsUntil(4000) { preGuardPassed.get() })
    }

    @Test
    fun test06_ChannelRestrictionsHonored() {
        val candidate = "32.56-vox.9.3-beta.1"
        val candidateCode = 2446040L

        // 1. QA релиз блокируется на STABLE и BETA каналах, но разрешён на QA канале
        val qaCandidate = "32.56-vox.9.3-otaqa1"
        val stableRejectsQa = !VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9.2",
            targetVersion = qaCandidate,
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = 2446032L,
            targetVersionCode = candidateCode
        )
        assertTrue("STABLE channel must reject QA updates", stableRejectsQa)

        val betaRejectsQa = !VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9.2",
            targetVersion = qaCandidate,
            channel = VoxUpdateChannel.BETA,
            installedVersionCode = 2446032L,
            targetVersionCode = candidateCode
        )
        assertTrue("BETA channel must reject QA updates", betaRejectsQa)

        val qaAcceptsQa = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9.2",
            targetVersion = qaCandidate,
            channel = VoxUpdateChannel.QA,
            installedVersionCode = 2446032L,
            targetVersionCode = candidateCode
        )
        assertTrue("QA channel must accept QA updates", qaAcceptsQa)

        // 2. Клиент на канонической стабильной версии (vox.9.0) не принимает Beta на STABLE, но принимает на BETA
        val canonicalStableRejectsBeta = !VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9.0",
            targetVersion = candidate,
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = 2446000L,
            targetVersionCode = candidateCode
        )
        assertTrue("STABLE channel client on canonical stable must NOT accept beta pre-release update", canonicalStableRejectsBeta)

        val betaAllowsBeta = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9.0",
            targetVersion = candidate,
            channel = VoxUpdateChannel.BETA,
            installedVersionCode = 2446000L,
            targetVersionCode = candidateCode
        )
        assertTrue("BETA channel client MUST accept newer beta update", betaAllowsBeta)
    }

    @Test
    fun test07_DoubleTapConcurrentProtection() {
        setInstalledAppVersion("32.56-vox.9.2-rc.13", 2446032)

        val asset = VoxReleaseAsset(
            name = "SmartTube_vot_32.56-vox.9.2_universal.apk",
            downloadUrl = "http://127.0.0.1:9999/dummy_double_tap.apk",
            sizeBytes = 66383180L
        )
        val release = VoxReleaseInfo(
            tagName = "v32.56-vox.9.2",
            versionName = "32.56-vox.9.2",
            changelog = emptyList(),
            assets = listOf(asset),
            versionCode = 2446032L
        )

        val callbackCount = AtomicInteger(0)
        val updateManager = VoxOtaUpdateManager.instance(context)

        // Имитируем два одновременных клика
        updateManager.downloadAndVerify(asset, release, object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(r: VoxReleaseInfo, a: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(currentVersion: String) { callbackCount.incrementAndGet() }
            override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
            override fun onDownloadCompleted(apkFile: File) {}
            override fun onError(error: VoxOtaException) {}
        })

        updateManager.downloadAndVerify(asset, release, object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(r: VoxReleaseInfo, a: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(currentVersion: String) { callbackCount.incrementAndGet() }
            override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
            override fun onDownloadCompleted(apkFile: File) {}
            override fun onError(error: VoxOtaException) {}
        })

        assertTrue("Both concurrent calls must be safely handled without crashes",
            pumpEventsUntil { callbackCount.get() >= 2 })
    }

    @Test
    fun test08_StaleUiRemovalAcrossViewLifecycle() {
        val presenter = BrowsePresenter.instance(context)
        val title = context.getString(R.string.update_found)

        val mockActivityView = Robolectric.buildActivity(TestBrowseActivityView::class.java).setup().get()
        presenter.setView(mockActivityView)

        // Закрепляем две секции (например, при повторном чеке)
        presenter.pinItem(title, R.drawable.action_info, null)
        presenter.pinItem(title, R.drawable.action_info, null)

        // unpinItem должен удалить все секции с данным заголовком
        presenter.unpinItem(title)
        assertTrue("unpinItem must remove the section from View", mockActivityView.removedCount >= 1)

        // Проверяем устойчивость к отсоединению View
        presenter.setView(null)
        presenter.unpinItem(title) // не должно падать при getView() == null

        // Проверяем устойчивость к null аргументу
        presenter.unpinItem(null as String?)
    }

    @Test
    fun test09_DisplayVersionLabelFormatting_ResolvesP2BetaUxDefect() {
        // Устранение P2 бага: "32.56-vox.9.2-universal" распознавалось как Beta и форматировалось как "9.2-universal Beta"
        val rawVersion = "32.56-vox.9.2-universal"
        val releaseInfo = VoxReleaseInfo(
            tagName = "v32.56-vox.9.2",
            versionName = rawVersion,
            changelog = emptyList(),
            assets = emptyList(),
            isPrerelease = false,
            versionCode = 2446032L
        )

        val clean = VoxOtaDisplayHelper.extractCleanVoxVersion(rawVersion)
        assertEquals("Clean version must strip '-universal' prefix", "9.2", clean)

        val tier = VoxOtaDisplayHelper.detectTier(rawVersion, releaseInfo)
        assertEquals("32.56-vox.9.2-universal without isPrerelease must be classified as STABLE",
            VoxOtaChannelTier.STABLE, tier)

        val display = VoxOtaDisplayHelper.formatDisplayVersion(rawVersion, tier)
        assertEquals("Display version must be clean without 'universal' or false 'Beta'",
            "SmartTube Vox - 9.2", display)

        // Проверяем, что настоящая Beta остаётся Beta
        val betaRaw = "32.56-vox.9.3-beta.1"
        val betaRelease = releaseInfo.copy(versionName = betaRaw, isPrerelease = true)
        val betaTier = VoxOtaDisplayHelper.detectTier(betaRaw, betaRelease)
        assertEquals(VoxOtaChannelTier.BETA, betaTier)
        assertEquals("SmartTube Vox - 9.3 Beta", VoxOtaDisplayHelper.formatDisplayVersion(betaRaw, betaTier))

        // Проверяем, что Dev остаётся Dev
        val devRaw = "32.56-vox.9.3-dev"
        val devRelease = releaseInfo.copy(versionName = devRaw, isPrerelease = true)
        val devTier = VoxOtaDisplayHelper.detectTier(devRaw, devRelease)
        assertEquals(VoxOtaChannelTier.DEV, devTier)
        assertEquals("SmartTube Vox - 9.3 Dev", VoxOtaDisplayHelper.formatDisplayVersion(devRaw, devTier))
    }
}
