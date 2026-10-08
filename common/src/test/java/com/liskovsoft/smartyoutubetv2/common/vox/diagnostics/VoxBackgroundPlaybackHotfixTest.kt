package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxBackgroundPlaybackHotfixTest {

    @Test
    fun testAllBackgroundLogCodesPresent() {
        val backgroundCodes = listOf(
            VoxLogCode.BACKGROUND_PLAYBACK_REQUESTED,
            VoxLogCode.BACKGROUND_PLAYBACK_ALLOWED,
            VoxLogCode.BACKGROUND_PLAYBACK_BLOCKED,
            VoxLogCode.BACKGROUND_AUDIO_ONLY_ENTER,
            VoxLogCode.BACKGROUND_AUDIO_ONLY_EXIT,
            VoxLogCode.BACKGROUND_PLAYER_CONTINUED,
            VoxLogCode.BACKGROUND_PLAYER_PAUSED,
            VoxLogCode.BACKGROUND_SERVICE_STARTED,
            VoxLogCode.BACKGROUND_SERVICE_STOPPED,
            VoxLogCode.BACKGROUND_AUDIO_FOCUS_GAIN,
            VoxLogCode.BACKGROUND_AUDIO_FOCUS_LOSS,
            VoxLogCode.BACKGROUND_MEDIASESSION_ACTIVE,
            VoxLogCode.BACKGROUND_MEDIASESSION_RELEASED
        )

        assertEquals("Must contain all 13 background diagnostic codes", 13, backgroundCodes.size)
        backgroundCodes.forEach { code ->
            assertNotNull(code)
            assertTrue(code.startsWith("BACKGROUND_"))
        }

        assertNotNull(VoxLogCategory.BACKGROUND)
        assertEquals("Фоновый режим", VoxLogCategory.BACKGROUND.displayNameRu)
    }

    @Test
    fun testAllOtaLogCodesPresent() {
        val otaCodes = listOf(
            VoxLogCode.OTA_CHECK_STARTED,
            VoxLogCode.OTA_RELEASE_FOUND,
            VoxLogCode.OTA_VERSION_COMPARED,
            VoxLogCode.OTA_ASSET_SELECTED,
            VoxLogCode.OTA_DOWNLOAD_STARTED,
            VoxLogCode.OTA_DOWNLOAD_COMPLETED,
            VoxLogCode.OTA_HASH_VERIFIED,
            VoxLogCode.OTA_SIGNATURE_VERIFIED,
            VoxLogCode.OTA_INSTALL_REQUESTED,
            VoxLogCode.OTA_FAILED
        )

        assertEquals("Must contain all 10 OTA diagnostic codes", 10, otaCodes.size)
        otaCodes.forEach { code ->
            assertNotNull(code)
            assertTrue(code.startsWith("OTA_"))
        }

        assertNotNull(VoxLogCategory.OTA)
        assertEquals("Обновление ПО", VoxLogCategory.OTA.displayNameRu)
    }

    @Test
    fun testDownloadAndPlayerNewLogCodes() {
        assertNotNull(VoxLogCode.DOWNLOAD_PACKAGING_FAILED)
        assertNotNull(VoxLogCode.DOWNLOAD_FINALIZE_FAILED)
        assertNotNull(VoxLogCode.PLAYER_HTTP_ERROR)
        assertNotNull(VoxLogCode.PLAYER_LOCAL_SOURCE_ERROR)
    }

    @Test
    fun testBackgroundFailureSnapshotLogging() {
        // Must execute cleanly without exceptions
        VoxSafeLogger.logBackgroundFailureSnapshot(
            reason = "decoder error without surface",
            playerState = "STATE_BUFFERING",
            playWhenReady = true,
            isPlaying = false,
            audioFocusState = "HAVE_FOCUS",
            serviceState = "RUNNING",
            mediaSessionState = "ACTIVE",
            backgroundEnabled = true,
            audioOnlyEnabled = true
        )
    }
}
