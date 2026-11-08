package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/**
 * Prompt 163-R52: Regression suite for Stable channel cold restart and update check behavior.
 * Verifies that when a Stable client queries endpoints containing only older Beta/RC releases
 * (e.g. 9.2-rc.13), the result is cleanly treated as NO_UPDATE ("У вас установлена последняя версия")
 * rather than surfacing a false CHANNEL_MISMATCH error dialog/toast.
 * Also verifies that genuine network, rate limit, parse, and integrity errors remain unmasked.
 */
@RunWith(RobolectricTestRunner::class)
class Prompt163R52ChannelMismatchTest {

    private lateinit var context: Context

    private val singleRcReleaseJson = """
        {
          "tag_name": "v32.56-vox.9.2-rc.13",
          "name": "SmartTube VOX 32.56-vox.9.2-rc.13",
          "prerelease": true,
          "draft": false,
          "body": "RC release notes",
          "assets": [
            {
              "name": "smarttube_vox_universal.apk",
              "browser_download_url": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.9.2-rc.13/smarttube_vox_universal.apk",
              "size": 25000000
            }
          ]
        }
    """.trimIndent()

    private val releasesListWithOnlyRcJson = """
        [
          {
            "tag_name": "v32.56-vox.9.2-rc.13",
            "name": "SmartTube VOX 32.56-vox.9.2-rc.13",
            "prerelease": true,
            "draft": false,
            "assets": [
              {
                "name": "smarttube_vox_universal.apk",
                "browser_download_url": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.9.2-rc.13/smarttube_vox_universal.apk",
                "size": 25000000
              }
            ]
          }
        ]
    """.trimIndent()

    private val newerStableReleaseJson = """
        {
          "tag_name": "v32.71-vox.11",
          "name": "SmartTube VOX 32.71-vox.11",
          "prerelease": false,
          "draft": false,
          "body": "Stable release notes",
          "assets": [
            {
              "name": "smarttube_vox_32.71-vox.11_universal.apk",
              "browser_download_url": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.11/smarttube_vox_32.71-vox.11_universal.apk",
              "size": 26000000
            }
          ]
        }
    """.trimIndent()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        VoxUpdateChannelManager.resetForTesting()
        VoxOtaUpdateManager.resetInstanceForTesting()
    }

    @Test
    fun testParserThrowsChannelMismatchForSingleRcOnStableChannel() {
        try {
            VoxOtaReleaseParser.parseRelease(singleRcReleaseJson, VoxUpdateChannel.STABLE, "32.71-vox.10", 2461010L)
            fail("Expected VoxOtaException with CHANNEL_MISMATCH for pre-release on STABLE channel")
        } catch (e: VoxOtaException) {
            assertEquals("Error code must be CHANNEL_MISMATCH", VoxOtaErrorCode.CHANNEL_MISMATCH, e.code)
        }
    }

    @Test
    fun testParserAllowsSingleRcOnBetaChannel() {
        val release = VoxOtaReleaseParser.parseRelease(singleRcReleaseJson, VoxUpdateChannel.BETA, "32.56-vox.9.1", 2446016L)
        assertNotNull(release)
        assertEquals("v32.56-vox.9.2-rc.13", release.tagName)
        assertTrue(release.isPrerelease)
    }

    @Test
    fun testParserReturnsNoUpdateForReleasesListWithOnlyRcOnStableChannel() {
        try {
            VoxOtaReleaseParser.parseRelease(releasesListWithOnlyRcJson, VoxUpdateChannel.STABLE, "32.71-vox.10", 2461010L)
            fail("Expected VoxOtaException with NO_UPDATE when no release matches STABLE channel")
        } catch (e: VoxOtaException) {
            assertEquals("Releases array without stable candidate must throw NO_UPDATE", VoxOtaErrorCode.NO_UPDATE, e.code)
        }
    }

    @Test
    fun testVersionComparatorRejectsOldRcAndDowngradeForStable() {
        // Installed 32.71-vox.10 vs candidate 32.56-vox.9.2-rc.13
        assertFalse(
            "Stable client must reject older RC candidate",
            VoxVersionComparator.isUpdateAvailable("32.71-vox.10", "32.56-vox.9.2-rc.13", VoxUpdateChannel.STABLE, 2461010L, 2446032L)
        )
    }

    @Test
    fun testVersionComparatorDoesNotOfferSameInstalledVersionAgain() {
        // Installed 32.71-vox.10 vs candidate 32.71-vox.10
        assertFalse(
            "Installed version must never be re-offered as an update",
            VoxVersionComparator.isUpdateAvailable("32.71-vox.10", "32.71-vox.10", VoxUpdateChannel.STABLE, 2461010L, 2461010L)
        )
    }

    @Test
    fun testVersionComparatorAcceptsNewerStableVersion() {
        // Installed 32.71-vox.10 (2461010) vs candidate 32.71-vox.11 (2461011)
        assertTrue(
            "Newer canonical stable version must be offered as update",
            VoxVersionComparator.isUpdateAvailable("32.71-vox.10", "32.71-vox.11", VoxUpdateChannel.STABLE, 2461010L, 2461011L)
        )
    }

    @Test
    fun testUpdateManagerChannelMismatchTranslatesToNoUpdateCallback() {
        val manager = VoxOtaUpdateManager.instance(context)
        var noUpdateCalled = false
        var errorCalled = false
        var returnedVersion: String? = null

        val callback = object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(release: VoxReleaseInfo, asset: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(currentVersion: String) {
                noUpdateCalled = true
                returnedVersion = currentVersion
            }
            override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
            override fun onDownloadCompleted(apkFile: java.io.File) {}
            override fun onError(error: VoxOtaException) {
                errorCalled = true
            }
        }

        // Simulate handleError when CHANNEL_MISMATCH exception is caught
        val channelMismatchEx = VoxOtaException(VoxOtaErrorCode.CHANNEL_MISMATCH, "Release is pre-release but requested stable channel")
        manager.handleErrorForTesting(channelMismatchEx, "32.71-vox.10", "stable", 100L, callback)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertTrue("CHANNEL_MISMATCH must trigger onNoUpdateAvailable", noUpdateCalled)
        assertFalse("CHANNEL_MISMATCH must NOT trigger onError", errorCalled)
        assertEquals("32.71-vox.10", returnedVersion)
    }

    @Test
    fun testUpdateManagerRealErrorsNotMaskedAsNoUpdate() {
        val manager = VoxOtaUpdateManager.instance(context)

        val realErrors = listOf(
            VoxOtaErrorCode.NETWORK_ERROR,
            VoxOtaErrorCode.RATE_LIMIT,
            VoxOtaErrorCode.RELEASE_PARSE_FAILED,
            VoxOtaErrorCode.HASH_MISMATCH,
            VoxOtaErrorCode.PACKAGE_MISMATCH
        )

        for (errCode in realErrors) {
            var noUpdateCalled = false
            var errorCalled = false
            var capturedError: VoxOtaException? = null

            val callback = object : VoxOtaUpdateManager.Callback {
                override fun onUpdateAvailable(release: VoxReleaseInfo, asset: VoxReleaseAsset) {}
                override fun onNoUpdateAvailable(currentVersion: String) {
                    noUpdateCalled = true
                }
                override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
                override fun onDownloadCompleted(apkFile: java.io.File) {}
                override fun onError(error: VoxOtaException) {
                    errorCalled = true
                    capturedError = error
                }
            }

            val ex = VoxOtaException(errCode, "Simulated $errCode")
            manager.handleErrorForTesting(ex, "32.71-vox.10", "stable", 100L, callback)
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            assertFalse("Real error $errCode must NOT trigger onNoUpdateAvailable", noUpdateCalled)
            assertTrue("Real error $errCode must trigger onError", errorCalled)
            assertEquals(errCode, capturedError?.code)
        }
    }
}
