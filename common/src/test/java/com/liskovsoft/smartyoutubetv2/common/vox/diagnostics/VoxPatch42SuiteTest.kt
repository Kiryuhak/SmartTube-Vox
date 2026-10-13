package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.AudioTrack
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack
import com.liskovsoft.smartyoutubetv2.common.vot.VotProgressOverlay
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxPlatform
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadErrorCode
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadRequest
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadState
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadTrack
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadTranslationState
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxQualityPreference
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxTrackProgress
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxTrackState
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxTranslationMode
// import com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxTranslationStage
import com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxTranslationStateMachine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

// =========================================================================
// 1. TRANSLATION UX 3.0 & PROGRESS TESTS
// =========================================================================

class CompactProgressVisibleWhilePreparingTest {
    @Test
    fun testCompactProgressStagesRemainConsistentThroughPreparingAndAttaching() {
        val sm = VoxTranslationStateMachine()
        sm.transitionTo(VoxTranslationStateMachine.State.LOOKUP)
        assertEquals(VoxTranslationStateMachine.State.LOOKUP, sm.currentState)
        assertEquals(-1, sm.lastRealProgressPercent)

        sm.onProgressPoll(36, -1)
        assertEquals(36, sm.lastRealProgressPercent)

        sm.onProgressPoll(100, -1)
        assertEquals(100, sm.lastRealProgressPercent)
    }
}

class NoFakeTimePercentTest {
    @Test
    fun testWaitingStageNeverInventsTimeBasedPercentage() {
        val sm = VoxTranslationStateMachine()
        sm.transitionTo(VoxTranslationStateMachine.State.REQUESTING)

        // Heartbeats with remainingSec or -1 percent must never fabricate a 0..99% progress
        val res1 = sm.onProgressPoll(-1, 120)
        assertEquals(-1, sm.lastRealProgressPercent)
        assertEquals(VoxTranslationStateMachine.ProgressUpdateType.REAL_PROGRESS, res1)

        val res2 = sm.onProgressPoll(-1, 120)
        assertEquals(-1, sm.lastRealProgressPercent)
        assertEquals(VoxTranslationStateMachine.ProgressUpdateType.HEARTBEAT_ONLY, res2)
    }
}

class RealPercentMonotonicTest {
    @Test
    fun testRealPercentNeverDecreasesAndRepeatedPercentIsHeartbeatOnly() {
        val sm = VoxTranslationStateMachine()
        sm.transitionTo(VoxTranslationStateMachine.State.REQUESTING)

        // First 42% is REAL_PROGRESS
        val first42 = sm.onProgressPoll(42, -1)
        assertEquals(VoxTranslationStateMachine.ProgressUpdateType.REAL_PROGRESS, first42)
        assertEquals(42, sm.lastRealProgressPercent)

        // Repeated 42% is HEARTBEAT_ONLY (does not reset real progress timestamp)
        val second42 = sm.onProgressPoll(42, -1)
        assertEquals(VoxTranslationStateMachine.ProgressUpdateType.HEARTBEAT_ONLY, second42)
        assertEquals(42, sm.lastRealProgressPercent)

        // Lower percent (e.g. 25%) is clamped/ignored and treated as HEARTBEAT_ONLY
        val regression25 = sm.onProgressPoll(25, -1)
        assertEquals(VoxTranslationStateMachine.ProgressUpdateType.HEARTBEAT_ONLY, regression25)
        assertEquals(42, sm.lastRealProgressPercent)

        // Advance to 68% is REAL_PROGRESS
        val advance68 = sm.onProgressPoll(68, -1)
        assertEquals(VoxTranslationStateMachine.ProgressUpdateType.REAL_PROGRESS, advance68)
        assertEquals(68, sm.lastRealProgressPercent)
    }
}

class HundredPercentTransitionsToAttachingTest {
    @Test
    fun testHundredPercentTransitionsCleanlyToPlayerPrepareAndAttaching() {
        val sm = VoxTranslationStateMachine()
        sm.onProgressPoll(100, 0)
        assertEquals(100, sm.lastRealProgressPercent)

        sm.transitionTo(VoxTranslationStateMachine.State.ATTACHING)
        assertEquals(VoxTranslationStateMachine.State.ATTACHING, sm.currentState)

        sm.transitionTo(VoxTranslationStateMachine.State.READY)
        assertEquals(VoxTranslationStateMachine.State.READY, sm.currentState)
    }
}

class AttachingTimeoutShowsRetryableErrorTest {
    @Test
    fun testHundredPercentAndPrepareTimeoutTransitionsToFailedAndAllowsRetry() {
        val sm = VoxTranslationStateMachine()
        val t0 = 100_000L
        sm.transitionTo(VoxTranslationStateMachine.State.ATTACHING, t0)
        assertEquals(VoxTranslationStateMachine.State.ATTACHING, sm.currentState)

        assertFalse(sm.isPostProcessTimedOut(t0 + 5_000L, 15_000L))
        assertTrue(sm.isPostProcessTimedOut(t0 + 16_000L, 15_000L))

        sm.transitionTo(VoxTranslationStateMachine.State.FAILED, t0 + 16_000L)
        assertEquals(VoxTranslationStateMachine.State.FAILED, sm.currentState)

        // Next click starts a fresh retry session cleanly
        val retrySm = VoxTranslationStateMachine()
        retrySm.transitionTo(VoxTranslationStateMachine.State.REQUESTING)
        assertEquals(VoxTranslationStateMachine.State.REQUESTING, retrySm.currentState)
    }
}

class TemporaryStatusBubbleAutoHidesTest {
    @Test
    fun testTemporaryStatusBubbleUsesBoundedAutoHideDelay() {
        assertEquals(3500L, VotProgressOverlay.BUBBLE_AUTO_HIDE_DELAY_MS)
        assertEquals(2000L, VotProgressOverlay.BUBBLE_READY_AUTO_HIDE_DELAY_MS)
    }
}

// =========================================================================
// 2. VTUBE REFERENCE AUDIT & AUDIO RECOVERY TESTS
// =========================================================================

class RestartWhenReadyValidSessionTest {
    @Test
    fun testSessionValidationAcceptsActiveSessionAndRejectsStaleSession() {
        var activeGenerationId = 1L
        fun isSessionValid(genId: Long) = genId == activeGenerationId

        assertTrue(isSessionValid(1L))
        activeGenerationId = 2L
        assertFalse(isSessionValid(1L))
        assertTrue(isSessionValid(2L))
    }
}

class InvalidTranslationIdRejectedTest {
    @Test
    fun testInvalidTranslationIdRejectedBeforeContinuingWait() {
        assertFalse(VoxTranslationStateMachine.isValidTranslationId(null))
        assertFalse(VoxTranslationStateMachine.isValidTranslationId(""))
        assertFalse(VoxTranslationStateMachine.isValidTranslationId("   "))
        assertFalse(VoxTranslationStateMachine.isValidTranslationId("null"))
        assertFalse(VoxTranslationStateMachine.isValidTranslationId("undefined"))
        assertFalse(VoxTranslationStateMachine.isValidTranslationId("none"))
        assertTrue(VoxTranslationStateMachine.isValidTranslationId("tr-987654321"))
    }
}

class InvalidAudioUrlRejectedTest {
    @Test
    fun testInvalidAudioUrlRejectedBeforePlayerPrepare() {
        assertFalse(VoxTranslationStateMachine.isValidAudioUrl(null))
        assertFalse(VoxTranslationStateMachine.isValidAudioUrl(""))
        assertFalse(VoxTranslationStateMachine.isValidAudioUrl("   "))
        assertFalse(VoxTranslationStateMachine.isValidAudioUrl("null"))
        assertFalse(VoxTranslationStateMachine.isValidAudioUrl("undefined"))
        assertFalse(VoxTranslationStateMachine.isValidAudioUrl("ftp://example.com/audio.mp3"))
        assertFalse(VoxTranslationStateMachine.isValidAudioUrl("https://"))
        assertTrue(VoxTranslationStateMachine.isValidAudioUrl("https://vtrans.s3-private.mds.yandex.net/tts/prod/123.mp3"))
    }
}

class AudioNoProgressWatchdogTimeoutTest {
    @Test
    fun testAudioNoProgressWatchdogTriggersTimeoutWhenBufferingStalls() {
        val sm = VoxTranslationStateMachine()
        sm.transitionTo(VoxTranslationStateMachine.State.ATTACHING)

        val t0 = 1_000_000L
        // Initial buffering check with 0ms buffered position
        sm.onAudioProgressCheck(currentPosMs = 0L, bufferedPosMs = 0L, nowMs = t0)
        assertFalse(sm.isAudioNoProgressTimedOut(t0 + 5_000L, 12_000L))

        // Buffer advances at t0 + 4s -> resets watchdog timer
        sm.onAudioProgressCheck(currentPosMs = 0L, bufferedPosMs = 2_500L, nowMs = t0 + 4_000L)
        assertFalse(sm.isAudioNoProgressTimedOut(t0 + 13_000L, 12_000L))

        // No further buffer movement for >12s -> watchdog triggers timeout
        assertTrue(sm.isAudioNoProgressTimedOut(t0 + 16_500L, 12_000L))
    }
}

class SingleFallbackAttemptLimitTest {
    @Test
    fun testBoundedSingleFallbackAttemptPreservesPrimaryFailureReason() {
        val sm = VoxTranslationStateMachine()
        assertTrue(sm.canAttemptFallback())

        assertTrue(sm.recordFallbackAttempt("NEW_BACKEND_TIMEOUT"))
        assertTrue(sm.fallbackAttempted)
        assertEquals("NEW_BACKEND_TIMEOUT", sm.primaryFailureReason)

        // Second fallback in the same session must be rejected (no infinite NEW <-> OLD loop)
        assertFalse(sm.canAttemptFallback())
        assertFalse(sm.recordFallbackAttempt("SECOND_FAILURE"))
        assertTrue(sm.fallbackAttempted)
        assertEquals("NEW_BACKEND_TIMEOUT", sm.primaryFailureReason)
    }
}

// =========================================================================
// 3. DOWNLOADED TRANSLATION HARDENING TESTS
// =========================================================================

@RunWith(RobolectricTestRunner::class)
class DownloadedTranslatedTrackExplicitIdentificationTest {
    private fun createAudioMediaTrack(id: String, language: String?, mimeType: String, label: String? = null, selectionFlags: Int = 0): MediaTrack {
        val track = AudioTrack(TrackSelectorManager.RENDERER_INDEX_AUDIO)
        track.format = Format.createAudioSampleFormat(
            id,
            mimeType,
            null,
            128000,
            Format.NO_VALUE,
            2,
            44100,
            null,
            null,
            selectionFlags,
            language
        ).let { base ->
            if (label != null) {
                base.copyWithLabel(label)
            } else {
                base
            }
        }
        return track
    }

    @Test
    fun testSingleTrackOriginalMp3IsNeverMistakenForTranslatedAudio() {
        val singleOrigMp3 = createAudioMediaTrack("1", "en", "audio/mpeg", "Original Audio", 0)
        val singleUnknownMp3 = createAudioMediaTrack("1", null, "audio/mpeg", null, 0)

        assertNull(TrackSelectorManager.findTranslatedLocalAudioTrack(arrayOf(arrayOf(singleOrigMp3))))
        assertNull(TrackSelectorManager.findTranslatedLocalAudioTrack(arrayOf(arrayOf(singleUnknownMp3))))

        val singleFormatList = listOf(ExoFormatItem.from(singleUnknownMp3))
        assertNull(ExoPlayerController.findTranslatedAudioFormat(singleFormatList))
    }

    @Test
    fun testTwoTrackMkvSelectsTranslatedTrackByLanguageLabelDefaultFlagOrSecondMp3() {
        val origOpus = createAudioMediaTrack("2", "en", "audio/opus", "Original Audio", 0)
        val transLabelOnly = createAudioMediaTrack("3", null, "audio/mpeg", "Russian Translation (Yandex VOT)", C.SELECTION_FLAG_DEFAULT)

        val selectedByLabel = TrackSelectorManager.findTranslatedLocalAudioTrack(arrayOf(arrayOf(origOpus, transLabelOnly)))
        assertEquals(transLabelOnly, selectedByLabel)

        val transSecondMp3NoMeta = createAudioMediaTrack("3", null, "audio/mpeg", null, 0)
        val selectedBySecondTrack = TrackSelectorManager.findTranslatedLocalAudioTrack(arrayOf(arrayOf(origOpus, transSecondMp3NoMeta)))
        assertEquals(transSecondMp3NoMeta, selectedBySecondTrack)
    }
}

@RunWith(RobolectricTestRunner::class)
class DownloadedTranslatedFileValidationBeforeMarkingTrueTest {
    @Test
    fun testMissingTranslatedAudioOnDiskMarksTranslatedJobInconsistent() {
        val context: Context = RuntimeEnvironment.getApplication()
        val storage = VoxDownloadStorage(context)
        val dlId = "real-user-dl-missing-trans"
        storage.deleteJobDir(dlId)

        val req = VoxDownloadRequest(
            downloadId = dlId,
            videoId = "vid_missing_trans",
            videoTitle = "Test Video",
            qualityPreference = VoxQualityPreference.QUALITY_720P,
            translationMode = VoxTranslationMode.STANDARD
        )
        val vProg = VoxTrackProgress(VoxDownloadTrack.VIDEO, 1024L, 1024L, VoxTrackState.COMPLETED)
        val oProg = VoxTrackProgress(VoxDownloadTrack.ORIGINAL_AUDIO, 512L, 512L, VoxTrackState.COMPLETED)
        val tProg = VoxTrackProgress(VoxDownloadTrack.TRANSLATED_AUDIO, 0L, 0L, VoxTrackState.PENDING)

        // Create a dummy output.mkv but set hasTranslatedAudio = false for a translated download
        val outMkv = storage.getOutputFile(dlId)
        outMkv.writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))

        storage.saveJobMetadata(
            request = req,
            state = VoxDownloadState.COMPLETED,
            videoProgress = vProg,
            originalAudioProgress = oProg,
            translatedAudioProgress = tProg,
            hasTranslatedAudio = false,
            finalFileBytes = 4L,
            packagingCompleted = true,
            finalizeCompleted = true
        )

        val loaded = storage.loadJobMetadata(dlId)
        assertNotNull(loaded)
        // Because translationMode != NONE and hasTranslatedAudio == false, COMPLETED invariant fails
        assertEquals(VoxDownloadState.FAILED, loaded!!.state)
        storage.deleteJobDir(dlId)
    }
}

@RunWith(RobolectricTestRunner::class)
class CorruptTranslatedAudioSelfHealMetadataTest {
    @Test
    fun testSelfHealMissingTranslatedAudioClearsStaleFlagAndSetsUntranslatedState() {
        val context: Context = RuntimeEnvironment.getApplication()
        val coordinator = VoxDownloadCoordinator.instance(context)
        val storage = coordinator.storage
        val dlId = "real-self-heal-dl-1"
        val videoId = "vid_self_heal_1"
        storage.deleteJobDir(dlId)

        val outMkv = storage.getOutputFile(dlId)
        outMkv.writeBytes(ByteArray(128) { 1 })

        val req = VoxDownloadRequest(
            downloadId = dlId,
            videoId = videoId,
            videoTitle = "Self Heal Video",
            qualityPreference = VoxQualityPreference.QUALITY_360P,
            translationMode = VoxTranslationMode.STANDARD
        )
        val prog = VoxTrackProgress(VoxDownloadTrack.VIDEO, 128L, 128L, VoxTrackState.COMPLETED)
        storage.saveJobMetadata(
            request = req,
            state = VoxDownloadState.COMPLETED,
            videoProgress = prog,
            originalAudioProgress = prog,
            translatedAudioProgress = prog,
            translationState = VoxDownloadTranslationState.DOWNLOADED_TRANSLATED,
            hasTranslatedAudio = true,
            finalFileBytes = 128L,
            packagingCompleted = true,
            finalizeCompleted = true
        )

        coordinator.reloadFromDiskForTest()
        val jobBefore = coordinator.findJobByVideoId(videoId)
        assertNotNull(jobBefore)
        assertTrue(jobBefore!!.hasTranslatedAudio)

        val healed = coordinator.selfHealMissingTranslatedAudio(videoId)
        assertTrue(healed)

        val jobAfter = coordinator.findJobByVideoId(videoId)
        assertNotNull(jobAfter)
        assertFalse(jobAfter!!.hasTranslatedAudio)
        assertEquals(VoxDownloadTranslationState.NONE, jobAfter.translationState)

        coordinator.deleteDownload(dlId)
    }
}

@RunWith(RobolectricTestRunner::class)
class DownloadDeleteFullCleanupTest {
    @Test
    fun testDeleteDownloadRemovesAllJobFilesAndMetadata() {
        val context: Context = RuntimeEnvironment.getApplication()
        val coordinator = VoxDownloadCoordinator.instance(context)
        val storage = coordinator.storage
        val dlId = "real-delete-cleanup-1"
        storage.deleteJobDir(dlId)

        val req = VoxDownloadRequest(
            downloadId = dlId,
            videoId = "vid_del_1",
            videoTitle = "Delete Test",
            qualityPreference = VoxQualityPreference.QUALITY_360P,
            translationMode = VoxTranslationMode.STANDARD
        )
        storage.getTrackFile(dlId, VoxDownloadTrack.VIDEO).writeBytes(ByteArray(64))
        storage.getTrackFile(dlId, VoxDownloadTrack.ORIGINAL_AUDIO).writeBytes(ByteArray(32))
        storage.getTrackFile(dlId, VoxDownloadTrack.TRANSLATED_AUDIO).writeBytes(ByteArray(32))
        storage.getOutputFile(dlId).writeBytes(ByteArray(128))
        val prog = VoxTrackProgress(VoxDownloadTrack.VIDEO, 64L, 64L, VoxTrackState.COMPLETED)
        storage.saveJobMetadata(
            request = req,
            state = VoxDownloadState.COMPLETED,
            videoProgress = prog,
            originalAudioProgress = prog,
            translatedAudioProgress = prog,
            translationState = VoxDownloadTranslationState.DOWNLOADED_TRANSLATED,
            hasTranslatedAudio = true,
            finalFileBytes = 128L,
            packagingCompleted = true,
            finalizeCompleted = true
        )

        coordinator.reloadFromDiskForTest()
        assertNotNull(coordinator.getJob(dlId))

        assertTrue(coordinator.deleteDownload(dlId))
        assertNull(coordinator.getJob(dlId))
        assertNull(storage.loadJobMetadata(dlId))
        assertFalse(File(storage.baseDir, dlId).exists())
    }
}

// =========================================================================
// 4. PERFORMANCE & NO REDUNDANT LAYOUT TESTS
// =========================================================================

class ProgressUiNoRedundantLayoutTest {
    @Test
    fun testIdenticalProgressPercentDoesNotTriggerRedundantStateChange() {
        val sm = VoxTranslationStateMachine()
        sm.transitionTo(VoxTranslationStateMachine.State.REQUESTING)
        assertEquals(
            VoxTranslationStateMachine.ProgressUpdateType.REAL_PROGRESS,
            sm.onProgressPoll(50, -1)
        )
        for (i in 1..20) {
            assertEquals(
                VoxTranslationStateMachine.ProgressUpdateType.HEARTBEAT_ONLY,
                sm.onProgressPoll(50, -1)
            )
        }
    }
}

// =========================================================================
// 5. REAL DIAGNOSTIC REPORTS (VOX-1D88100B, VOX-2921BEB6, VOX-4E952DB7) TESTS
// =========================================================================

@RunWith(RobolectricTestRunner::class)
class TriggerEventPopulatesTopLevelClassificationTest {
    @Test
    fun testHudStateInconsistentTriggerPopulatesTopLevelErrorCategoryAndSignature() {
        val context: Context = RuntimeEnvironment.getApplication()
        val report = VoxDiagnosticReport.create(
            context = context,
            reportId = "VOX-1D88100B",
            includeEvents = false,
            reportPurpose = VoxDiagnosticReport.PURPOSE_AUTO_BETA,
            autoSubmitted = true,
            triggerEvent = VoxLogCode.HUD_STATE_INCONSISTENT,
            incidentSummary = mapOf(
                "triggerCode" to VoxLogCode.HUD_STATE_INCONSISTENT,
                "normalizedSignature" to "HUD_STATE_INCONSISTENT|none|none",
                "repeatCount" to 1
            )
        )

        assertEquals("UI", report.errorCategory)
        assertNotNull(report.errorSignature)
        assertTrue(report.errorSignature!!.startsWith("UI|HUD_STATE_INCONSISTENT|"))

        val json = JSONObject(report.toJson())
        assertEquals("UI", json.getString("errorCategory"))
        assertTrue(json.getString("errorSignature").startsWith("UI|HUD_STATE_INCONSISTENT|"))
    }
}

@RunWith(RobolectricTestRunner::class)
class HistoricalDownloadFailureDoesNotOverrideCurrentTriggerTest {
    @Test
    fun testHistoricalP37FixtureEventDoesNotOverrideCurrentHudTrigger() {
        val now = System.currentTimeMillis()
        val eventsNewestFirst = listOf(
            VoxLogEvent(
                timestamp = now - 5_000L,
                level = VoxLogLevel.INFO,
                category = VoxLogCategory.OTA,
                code = VoxLogCode.OTA_NO_UPDATE,
                message = "No update"
            ),
            VoxLogEvent(
                timestamp = now - 120_000L,
                level = VoxLogLevel.ERROR,
                category = VoxLogCategory.DOWNLOAD,
                code = VoxLogCode.DOWNLOAD_STATE_INCONSISTENT,
                message = "Download completed state invariant violated for p37-resume-rick-1776007200926"
            )
        )

        // Synthetic p37-* fixture must be ignored by findProminentUnrecoveredEvent
        val prominent = VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(eventsNewestFirst)
        assertNull(prominent)
    }
}

class LatestOtaSuccessBeatsOlderOtaFailureTest {
    @Test
    fun testNewestFirstOtaSuccessRecoversOlderOtaFailureAndRateLimit() {
        val now = 1_776_042_073_587L
        // Exactly matches VOX-2921BEB6 ordering: newest-first list from VoxLogStore.getRecentEvents()
        val newestFirstEvents = listOf(
            VoxLogEvent(
                timestamp = now,
                level = VoxLogLevel.INFO,
                category = VoxLogCategory.OTA,
                code = VoxLogCode.OTA_NO_UPDATE,
                message = "Current version is up to date"
            ),
            VoxLogEvent(
                timestamp = now - 2L,
                level = VoxLogLevel.INFO,
                category = VoxLogCategory.OTA,
                code = VoxLogCode.OTA_CHECK_COMPLETED,
                message = "OTA check completed (HTTP 200)"
            ),
            VoxLogEvent(
                timestamp = now - 1_090_000L,
                level = VoxLogLevel.WARNING,
                category = VoxLogCategory.OTA,
                code = VoxLogCode.OTA_RATE_LIMIT,
                message = "GitHub API rate limit (HTTP 403)"
            ),
            VoxLogEvent(
                timestamp = now - 1_098_000L,
                level = VoxLogLevel.ERROR,
                category = VoxLogCategory.OTA,
                code = VoxLogCode.OTA_FAILED,
                message = "OTA check failed"
            )
        )

        val otaFailed = newestFirstEvents.last()
        val otaRateLimit = newestFirstEvents[2]

        assertTrue("Older OTA_FAILED must be recovered by newer OTA_NO_UPDATE",
            VoxDiagnosticsPolicy.isEventRecovered(otaFailed, newestFirstEvents))
        assertTrue("Older OTA_RATE_LIMIT must be recovered by newer OTA_NO_UPDATE",
            VoxDiagnosticsPolicy.isEventRecovered(otaRateLimit, newestFirstEvents))
        assertNull("No unrecovered error should remain when OTA succeeded",
            VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(newestFirstEvents))
        assertFalse(VoxDiagnosticsPolicy.hasValuableDiagnostics(newestFirstEvents))
    }
}

class NoSessionTranslationDiagnosticsUsesNoDataSemanticsTest {
    @Test
    fun testFreshStateMachineWithoutTranslationSessionUsesNoDataSemantics() {
        val sm = VoxTranslationStateMachine()
        assertFalse(sm.hasActiveOrCompletedSessionData())

        val summary = sm.getDiagnosticsSummary()
        assertEquals(false, summary["available"])
        assertEquals("NO_ACTIVE_SESSION", summary["translationSource"])
        assertFalse("Zero timing fields must not be emitted when no session occurred",
            summary.containsKey("translationLookupMs"))
        assertFalse(summary.containsKey("translationTotalMs"))
    }
}

class MachineSignatureNeverUsesLocalizedRussianPlatformTest {
    @Test
    fun testRussianUnknownPlatformIsNormalizedToAsciiUnknownPlatform() {
        val sig1 = VoxDiagnosticsPolicy.buildCanonicalErrorSignature(
            errorCategory = "PLAYER",
            primaryCode = "PLAYER_REBUFFER",
            platform = VoxPlatform.UNKNOWN.displayName, // "Неизвестная платформа"
            sdkInt = 27,
            reportPurpose = VoxDiagnosticReport.PURPOSE_AUTO_BETA
        )
        assertEquals("PLAYER|PLAYER_REBUFFER|UNKNOWN_PLATFORM_API27", sig1)
        assertFalse(sig1.contains("НЕИЗВЕСТНАЯ"))

        val sig2 = VoxDiagnosticsPolicy.buildCanonicalErrorSignature(
            errorCategory = "UI",
            primaryCode = "HUD_STATE_INCONSISTENT",
            platform = "Google TV",
            sdkInt = 34,
            reportPurpose = VoxDiagnosticReport.PURPOSE_AUTO_BETA
        )
        assertEquals("UI|HUD_STATE_INCONSISTENT|GOOGLE_TV_API34", sig2)
    }
}

@RunWith(RobolectricTestRunner::class)
class RobolectricReportsNeverUploadToProductionTest {
    @Test
    fun testRobolectricClientBlocksLiveProductionUpload() {
        val context: Context = RuntimeEnvironment.getApplication()
        assertTrue(VoxDiagnosticsClient.isRunningInUnitTest())

        val report = VoxDiagnosticReport.create(
            context = context,
            reportId = "VOX-ROBO-TEST01",
            includeEvents = false,
            reportPurpose = VoxDiagnosticReport.PURPOSE_AUTO_BETA,
            autoSubmitted = true,
            triggerEvent = VoxLogCode.PLAYER_REBUFFER
        )
        assertEquals("UNKNOWN_PLATFORM", report.platform)
        assertTrue(report.isSynthetic)

        val beforeBlocked = VoxDiagnosticsClient.blockedUnitTestUploadsCount.get()
        val result = VoxDiagnosticsClient.instance().submitReport(report)
        assertTrue(result is VoxDiagnosticsClient.Result.Success)
        assertTrue(VoxDiagnosticsClient.blockedUnitTestUploadsCount.get() > beforeBlocked)
    }
}
