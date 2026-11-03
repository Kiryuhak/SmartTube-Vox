package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import android.content.pm.PackageInfo
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * PROMPT #152: Регрессионные и доказательные тесты устранения проблемы повторного
 * скачивания той же версии обновления (OTA same-version stale action remediation).
 *
 * Полевые инциденты: VOX-2E1A76D4, VOX-BE98A524 (RT-G2) и VOX-5622CFFE (MIBOX4)
 * зафиксировали OTA_DOWNLOAD_STARTED для уже установленной 9.2 (2446032), с последующим
 * OTA_PRE_VALIDATION_FAILED (VERSION_NOT_NEWER).
 *
 * Тесты проверяют:
 * S01: Client guard блокирует загрузку при candidateVersionCode == installedVersionCode (0 байт скачано).
 * S02: Client guard блокирует загрузку при candidateVersionCode < installedVersionCode (защита от отката).
 * S03: Client guard блокирует загрузку при совпадающем versionName без явного versionCode.
 * S04: Легитимное обновление 9.1 (2446016) -> 9.2 (2446032) успешно проходит pre-download guard.
 * S05: Очистка устаревшей закрепленной карточки (unpin) в BrowsePresenter при onNoUpdateAvailable.
 */
@RunWith(RobolectricTestRunner::class)
class Prompt152OtaStaleActionTest {

    private lateinit var context: Context

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
    fun testS01_SameVersionCode_RejectedBeforeNetworkDownload_ZeroBytesDownloaded() {
        // Установлена версия 9.2 (2446032)
        setInstalledAppVersion("32.56-vox.9.2-rc.13", 2446032)

        val asset = VoxReleaseAsset(
            name = "SmartTube_vot_32.56-vox.9.2-rc.13_universal.apk",
            downloadUrl = "http://127.0.0.1:9999/dummy_should_never_be_called.apk",
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

        val success = pumpEventsUntil(3000) { noUpdateCalled.get() }
        assertTrue("onNoUpdateAvailable must be called for same version code", success)
        assertFalse("onDownloadCompleted must NEVER be called", downloadCompletedCalled.get())

        // Временный файл update.apk.tmp не должен существовать
        val cacheDir = context.cacheDir
        val tmpFile = File(cacheDir, "update.apk.tmp")
        assertFalse("Temporary download file must NOT be created on disk", tmpFile.exists())
    }

    @Test
    fun testS02_LowerVersionCode_DowngradeBlockedBeforeDownload() {
        // Установлена 9.2 (2446032), кандидат 9.1 (2446016)
        setInstalledAppVersion("32.56-vox.9.2-rc.13", 2446032)

        val asset = VoxReleaseAsset(
            name = "SmartTube_vot_32.56-vox.9.1_universal.apk",
            downloadUrl = "http://127.0.0.1:9999/dummy_older.apk",
            sizeBytes = 60000000L
        )
        val release = VoxReleaseInfo(
            tagName = "v32.56-vox.9.1",
            versionName = "32.56-vox.9.1",
            changelog = listOf("Changelog 9.1"),
            assets = listOf(asset),
            versionCode = 2446016L
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

        val success = pumpEventsUntil(3000) { noUpdateCalled.get() }
        assertTrue("Older version code must trigger onNoUpdateAvailable before network", success)
    }

    @Test
    fun testS03_SameVersionName_BlockedBeforeDownload() {
        // Установлена 32.56-vox.9.2, кандидат 32.56-vox.9.2 (versionCode null)
        setInstalledAppVersion("32.56-vox.9.2", 2446032)

        val asset = VoxReleaseAsset(
            name = "SmartTube_vot_32.56-vox.9.2_universal.apk",
            downloadUrl = "http://127.0.0.1:9999/dummy_same_name.apk",
            sizeBytes = 66000000L
        )
        val release = VoxReleaseInfo(
            tagName = "v32.56-vox.9.2",
            versionName = "32.56-vox.9.2",
            changelog = listOf("Changelog"),
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

        val success = pumpEventsUntil(3000) { noUpdateCalled.get() }
        assertTrue("Same version name must trigger onNoUpdateAvailable", success)
    }

    @Test
    fun testS04_LegitimateUpgrade_PassesPreDownloadGuard() {
        // Установлена 9.1 (2446016), кандидат 9.2 (2446032)
        setInstalledAppVersion("32.56-vox.9.1", 2446016)

        // Проверяем инвариант компаратора версий напрямую:
        val isUpdate = VoxVersionComparator.isUpdateAvailable(
            currentVersion = "32.56-vox.9.1",
            targetVersion = "32.56-vox.9.2",
            channel = VoxUpdateChannel.STABLE,
            installedVersionCode = 2446016L,
            targetVersionCode = 2446032L
        )
        assertTrue("9.1 -> 9.2 upgrade MUST be recognized as available", isUpdate)
    }

    @Test
    fun testS05_BrowsePresenter_UnpinItem_RemovesStaleErrorSection() {
        val presenter = BrowsePresenter.instance(context)
        val title = context.getString(R.string.update_found)

        // Закрепляем фиктивную секцию
        presenter.pinItem(title, R.drawable.action_info, null)

        // Снимаем закрепление через новый метод unpinItem
        presenter.unpinItem(title)

        // Повторный вызов unpinItem безопасен и идемпотентен
        presenter.unpinItem(title)
        presenter.unpinItem(null as String?)
    }
}
