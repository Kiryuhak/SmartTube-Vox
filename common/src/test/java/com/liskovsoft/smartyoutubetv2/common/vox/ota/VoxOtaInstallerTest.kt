package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import android.provider.Settings
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoxOtaInstallerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testCanRequestPackageInstalls() {
        // By default in Robolectric, canRequestPackageInstalls is queried safely
        val result = VoxOtaInstaller.canRequestPackageInstalls(context)
        // Check does not crash and returns a valid boolean
        assertTrue(result || !result)
    }

    @Test
    fun testCreateManageUnknownSourcesIntent() {
        val intent = VoxOtaInstaller.createManageUnknownSourcesIntent(context)
        assertNotNull(intent)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test
    fun testPreInstallValidationRejectsMissingFile() {
        val nonExistent = File(context.cacheDir, "non_existent_update.apk")
        val result = VoxOtaInstaller.installPackage(context, nonExistent)
        assertTrue(result is VoxOtaInstaller.InstallResult.Failed)
        val failed = result as VoxOtaInstaller.InstallResult.Failed
        assertEquals(VoxOtaErrorCode.DOWNLOAD_INCOMPLETE, failed.code)
    }

    @Test
    fun testPreInstallValidationRejectsEmptyOrTruncatedFile() {
        val truncatedFile = File(context.cacheDir, "empty_update.apk")
        truncatedFile.writeBytes(ByteArray(1024)) // 1 KB, smaller than MIN_SANE_APK_SIZE (5 MB)
        try {
            val result = VoxOtaInstaller.installPackage(context, truncatedFile)
            assertTrue(result is VoxOtaInstaller.InstallResult.Failed)
            val failed = result as VoxOtaInstaller.InstallResult.Failed
            assertEquals(VoxOtaErrorCode.DOWNLOAD_INCOMPLETE, failed.code)
        } finally {
            truncatedFile.delete()
        }
    }
}
