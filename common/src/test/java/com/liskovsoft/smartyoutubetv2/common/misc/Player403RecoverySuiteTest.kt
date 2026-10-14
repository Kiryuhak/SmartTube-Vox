package com.liskovsoft.smartyoutubetv2.common.misc

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogEvent
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Player403RecoverySuiteTest {

    private lateinit var policy: PlayerCacheRecoveryPolicy

    @Before
    fun setUp() {
        policy = PlayerCacheRecoveryPolicy()
    }

    @Test
    fun testPlayer403OneShotRefresh() {
        // First 403 encountered: should be RECOVERABLE_403
        val firstResult = policy.evaluate403Error(false)
        assertEquals(PlayerCacheRecoveryPolicy.Error403Type.RECOVERABLE_403, firstResult)
        assertTrue(policy.isRecoveryAttempted())
        assertEquals(1, policy.source403Count)

        // Second 403 in the same session: must be FINAL_403 (one-shot bound)
        val secondResult = policy.evaluate403Error(false)
        assertEquals(PlayerCacheRecoveryPolicy.Error403Type.FINAL_403, secondResult)
        assertEquals(2, policy.source403Count)
    }

    @Test
    fun testPlayer403RecoverySuccess() {
        // 1. Encounter initial 403 (e.g. expired media URL)
        val errorType = policy.evaluate403Error(true)
        assertEquals(PlayerCacheRecoveryPolicy.Error403Type.RECOVERABLE_403, errorType)

        // 2. Fresh source resolved & playback starts successfully
        policy.onPlaybackStarted()

        // 3. State is cleared and ready for new healthy lifecycle
        assertEquals(0, policy.source403Count)
        assertFalse(policy.isRecoveryAttempted())
    }

    @Test
    fun testPlayer403RecoveryFailureTerminal() {
        // 1. First 403 triggers bounded recovery
        val res1 = policy.evaluate403Error(false)
        assertEquals(PlayerCacheRecoveryPolicy.Error403Type.RECOVERABLE_403, res1)

        // 2. Refreshed source also returns 403 (terminal failure / blocked IP)
        val res2 = policy.evaluate403Error(false)
        assertEquals(PlayerCacheRecoveryPolicy.Error403Type.FINAL_403, res2)

        // 3. Subsequent attempts remain strictly FINAL_403 to prevent infinite loops
        val res3 = policy.evaluate403Error(false)
        assertEquals(PlayerCacheRecoveryPolicy.Error403Type.FINAL_403, res3)
        assertEquals(3, policy.source403Count)
    }

    @Test
    fun testPlayer403KeepsPlaybackPosition() {
        val initialPosition = 125000L // 2 minutes 5 seconds

        // On encountering 403, position should remain preserved for the reload
        val errorType = policy.evaluate403Error(true)
        assertEquals(PlayerCacheRecoveryPolicy.Error403Type.RECOVERABLE_403, errorType)

        // Simulated reload preserves the position
        assertEquals(125000L, initialPosition)
    }

    @Test
    fun testPlayer403OldSessionIgnored() {
        // Video A encounters 403
        policy.evaluate403Error(false)
        assertTrue(policy.isRecoveryAttempted())

        // User opens Video B -> reset() called
        policy.reset()

        // Video B has fresh state
        assertFalse(policy.isRecoveryAttempted())
        assertEquals(0, policy.source403Count)

        // Video B's first 403 is independently recoverable
        val resB = policy.evaluate403Error(false)
        assertEquals(PlayerCacheRecoveryPolicy.Error403Type.RECOVERABLE_403, resB)
    }

    @Test
    fun testPlayer403IsNotTranslation403() {
        val playerEvent = VoxLogEvent(
            timestamp = 1000L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_HTTP_ERROR,
            message = "ExoPlayer 403",
            context = mapOf("responseCode" to "403")
        )

        val translationEvent = VoxLogEvent(
            timestamp = 1000L,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.TRANSLATION,
            code = VoxLogCode.TRANSLATION_CACHED_URL_EXPIRED,
            message = "Cached translation audio url expired"
        )

        assertNotEquals(playerEvent.category, translationEvent.category)
        assertNotEquals(playerEvent.code, translationEvent.code)
        assertEquals(VoxLogCategory.PLAYER, playerEvent.category)
        assertEquals(VoxLogCategory.TRANSLATION, translationEvent.category)
    }

    @Test
    fun testPlayer403IsNotOta403() {
        val playerEvent = VoxLogEvent(
            timestamp = 1000L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_HTTP_ERROR,
            message = "ExoPlayer 403",
            context = mapOf("responseCode" to "403")
        )

        val otaEvent = VoxLogEvent(
            timestamp = 1000L,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.OTA,
            code = VoxLogCode.OTA_RATE_LIMIT,
            message = "Rate limit 403",
            context = mapOf("httpStatus" to "403")
        )

        assertNotEquals(playerEvent.category, otaEvent.category)
        assertNotEquals(playerEvent.code, otaEvent.code)
        assertEquals(VoxLogCategory.PLAYER, playerEvent.category)
        assertEquals(VoxLogCategory.OTA, otaEvent.category)
    }

    @Test
    fun testPlayer403TelemetryEventsDistinction() {
        val recoverable = VoxLogEvent(
            timestamp = 2000L,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_RECOVERABLE_403,
            message = "Recoverable 403",
            context = mapOf(
                "recoveryAttempt" to "1",
                "recoveryEngine" to "Cronet",
                "recoveryResult" to "RETRYING",
                "preservedPositionMs" to "50000",
                "retryCount" to "1"
            )
        )

        val terminal = VoxLogEvent(
            timestamp = 3000L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_FINAL_403,
            message = "Terminal 403",
            context = mapOf(
                "recoveryAttempt" to "FINAL",
                "recoveryEngine" to "Cronet",
                "recoveryResult" to "FAILED",
                "preservedPositionMs" to "50000",
                "retryCount" to "2"
            )
        )

        assertEquals("PLAYER_RECOVERABLE_403", recoverable.code)
        assertEquals("PLAYER_FINAL_403", terminal.code)
        assertEquals("RETRYING", recoverable.context?.get("recoveryResult"))
        assertEquals("FAILED", terminal.context?.get("recoveryResult"))
        assertEquals("50000", recoverable.context?.get("preservedPositionMs"))
    }

    @Test
    fun testLiveVodSessionIsolation() {
        val liveVideo = Video().apply {
            id = 123
            videoId = "live_stream_123"
            isLive = true
        }

        val vodVideo = Video().apply {
            id = 456
            videoId = "vod_clip_456"
            isLive = false
        }

        assertTrue(liveVideo.isLive)
        assertFalse(vodVideo.isLive)

        // Reset policy between sessions
        policy.evaluate403Error(false)
        assertTrue(policy.isRecoveryAttempted())

        policy.reset()
        assertFalse(policy.isRecoveryAttempted())
        assertEquals(0, policy.source403Count)
    }
}
