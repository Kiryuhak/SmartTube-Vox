package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
class VoxUserJourneyRecorderTest {

    private lateinit var context: Context
    private lateinit var recorder: VoxUserJourneyRecorder

    @Before
    fun setUp() {
        VoxUserJourneyRecorder.resetForTests()
        context = RuntimeEnvironment.getApplication()
        recorder = VoxUserJourneyRecorder.instance(context)
        recorder.purgeAll()
        recorder.setMode(VoxUserJourneyMode.STANDARD)
        recorder.setIncludeContentDetails(false)
    }

    @Test
    fun testScenario1ModesAndPrivacyRedaction() {
        // Mode: OFF -> nothing recorded
        recorder.setMode(VoxUserJourneyMode.OFF)
        recorder.recordPlayback("PLAYBACK_START", "Rick Astley - Never Gonna Give You Up", "dQw4w9WgXcQ", 0L)
        assertEquals(0, recorder.getEventCount())

        // Mode: STANDARD -> recorded, but videoRef is strictly "redacted"
        recorder.setMode(VoxUserJourneyMode.STANDARD)
        recorder.recordPlayback("PLAYBACK_START", "Rick Astley - Never Gonna Give You Up", "dQw4w9WgXcQ", 1234L)
        assertEquals(1, recorder.getEventCount())
        val stdEvent = recorder.getEvents()[0]
        assertEquals("redacted", stdEvent.videoRef)
        assertEquals("STANDARD", stdEvent.privacyLevel)
        assertEquals("PLAYBACK_START", stdEvent.event)

        // Mode: DETAILED without content details -> videoRef still "redacted"
        recorder.setMode(VoxUserJourneyMode.DETAILED)
        recorder.setIncludeContentDetails(false)
        recorder.recordPlayback("PLAYBACK_SEEK", "Secret Video Title", "secretId123", 5000L)
        assertEquals(2, recorder.getEventCount())
        val detailedEventNoContent = recorder.getEvents()[1]
        assertEquals("redacted", detailedEventNoContent.videoRef)
        assertEquals("DETAILED", detailedEventNoContent.privacyLevel)

        // Mode: DETAILED with content details -> videoRef contains sanitized info
        recorder.setIncludeContentDetails(true)
        recorder.recordPlayback("PLAYBACK_STATE", "https://youtube.com/watch?v=dQw4w9WgXcQ&key=AIzaSySecretToken", "dQw4w9WgXcQ", 6000L)
        assertEquals(3, recorder.getEventCount())
        val detailedEventWithContent = recorder.getEvents()[2]
        assertFalse(detailedEventWithContent.videoRef.contains("AIzaSySecretToken"))
        assertTrue(detailedEventWithContent.videoRef.contains("dQw4w9WgXcQ"))
    }

    @Test
    fun testScenario2Ttl24hExpirationRevertsDetailedToStandard() {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences("vox_user_journey_prefs", Context.MODE_PRIVATE)

        // Set DETAILED mode with activation timestamp 25 hours ago
        recorder.setMode(VoxUserJourneyMode.DETAILED)
        val expiredTime = now - (25 * 3600 * 1000L)
        prefs.edit().putLong("user_journey_detailed_started_at", expiredTime).commit()

        // Next access should detect TTL expiration and revert to STANDARD
        val mode = recorder.getMode()
        assertEquals(VoxUserJourneyMode.STANDARD, mode)
    }

    @Test
    fun testScenario3BoundedRingBufferRollover200() {
        recorder.setMode(VoxUserJourneyMode.STANDARD)

        for (i in 1..250) {
            recorder.recordKeyAction(20 + (i % 4), 0, "TestActivity", "Card_$i", "op_$i")
        }

        // Bounded ring buffer in STANDARD mode must hold at most MAX_EVENTS_STANDARD = 200
        assertEquals(200, recorder.getEventCount())
        val events = recorder.getEvents()
        assertEquals(200, events.size)

        // First event in buffer should be sequence 51 (1..50 dropped)
        assertEquals(51L, events[0].sequence)
        assertEquals(250L, events[199].sequence)
    }

    @Test
    fun testScenario4CausalCorrelationAndMonotonicSequence() {
        recorder.setMode(VoxUserJourneyMode.DETAILED)
        val sessionId = recorder.startNewPlaybackSession()
        assertTrue(sessionId.isNotBlank())

        val opId = "op-dvr-seek-99"
        val parentEventId = "evt-input-101"

        recorder.recordPlayback(
            event = "DVR_SEEK_INTENT",
            videoTitle = "Live News",
            videoId = "live123",
            positionMs = 15000L,
            details = mapOf("targetPosMs" to 12000L),
            cause = "dpad_left",
            operationId = opId,
            parentEventId = parentEventId,
            status = "SEEK_DISPATCHED",
            durationMs = 45L
        )

        val events = recorder.getEvents()
        assertEquals(1, events.size)
        val ev = events[0]
        assertEquals(sessionId, ev.playbackSessionId)
        assertEquals(opId, ev.operationId)
        assertEquals(parentEventId, ev.parentEventId)
        assertEquals("SEEK_DISPATCHED", ev.status)
        assertEquals(45L, ev.durationMs)
        assertEquals(1L, ev.sequence)
        assertTrue(ev.elapsedMs >= 0)
    }

    @Test
    fun testScenario5PrivacySanitizationInDetails() {
        recorder.setMode(VoxUserJourneyMode.DETAILED)
        val dirtyUrl = "https://example.com/videoplayback?expire=12345&sig=MEQCICx981273981273918273&token=SECRET123"
        val dirtyHeader = "Bearer ya29.a0ARrdaM8SECRET_TOKEN_HERE"
        val dirtyEmail = "user.secret@gmail.com"

        recorder.recordVot(
            event = "VOT_REQUEST",
            stage = "REQUESTING",
            driftMs = 0L,
            details = mapOf(
                "url" to dirtyUrl,
                "auth" to dirtyHeader,
                "email" to dirtyEmail,
                "safeMetric" to 42
            )
        )

        val ev = recorder.getEvents()[0]
        val json = ev.toJson()
        val details = json.getJSONObject("details")

        // Sanitizer strips tokens, auth secrets, and emails
        assertFalse(details.getString("url").contains("sig=MEQC"))
        assertFalse(details.getString("url").contains("token=SECRET"))
        assertFalse(details.getString("auth").contains("SECRET_TOKEN_HERE"))
        assertFalse(details.getString("email").contains("user.secret@gmail.com"))
        assertEquals(42, details.getInt("safeMetric"))
    }

    @Test
    fun testScenario6CrashSnapshotSaveAndRecovery() {
        val testException = IllegalStateException("Simulated FATAL crash in ExoPlayer pipeline at frame 1024")
        recorder.recordPlayback("BEFORE_CRASH", "Video", "vid1", 1000L)

        // Save crash snapshot
        recorder.saveCrashSnapshot(testException)

        assertTrue(recorder.hasCrashSnapshot())
        val snapshotJson = recorder.getCrashSnapshot()
        assertNotNull(snapshotJson)

        val parsed = JSONObject(snapshotJson!!)
        assertEquals(2, parsed.getInt("schemaVersion"))
        assertEquals("CRASH", parsed.getString("category"))
        assertEquals("UNCAUGHT_EXCEPTION", parsed.getString("event"))
        assertEquals("Simulated FATAL crash in ExoPlayer pipeline at frame 1024", parsed.getString("cause"))
        val details = parsed.getJSONObject("details")
        assertTrue(details.getString("exceptionClass").contains("IllegalStateException"))
        assertTrue(details.getString("stackTrace").contains("Simulated FATAL crash"))

        // Clear crash snapshot
        recorder.clearCrashSnapshot()
        assertFalse(recorder.hasCrashSnapshot())
        assertNull(recorder.getCrashSnapshot())
    }

    @Test
    fun testScenario7DetailedModeHas500EventCapacity() {
        recorder.setMode(VoxUserJourneyMode.DETAILED)

        for (i in 1..600) {
            recorder.recordKeyAction(20 + (i % 4), 0, "TestActivity", "Card_$i", "op_$i")
        }

        // In DETAILED mode, ring buffer should retain up to MAX_EVENTS_DETAILED = 500 events
        assertEquals(500, recorder.getEventCount())
        val events = recorder.getEvents()
        assertEquals(500, events.size)

        // First event in buffer should be sequence 101 (1..100 dropped)
        assertEquals(101L, events[0].sequence)
        assertEquals(600L, events[499].sequence)
    }

    @Test
    fun testScenario8StandardModeKeeps200EventCapacity() {
        recorder.setMode(VoxUserJourneyMode.STANDARD)

        for (i in 1..300) {
            recorder.recordKeyAction(20 + (i % 4), 0, "TestActivity", "Card_$i", "op_$i")
        }

        assertEquals(200, recorder.getEventCount())
        val events = recorder.getEvents()
        assertEquals(200, events.size)

        assertEquals(101L, events[0].sequence)
        assertEquals(300L, events[199].sequence)
    }

    @Test
    fun testScenario9PinnedBookmarksNotEvicted() {
        recorder.setMode(VoxUserJourneyMode.DETAILED)

        // Critical session start event (pinned)
        recorder.recordPlayback(
            event = "PLAYBACK_START",
            cause = "user_click_video",
            videoTitle = "Important Video",
            videoId = "vid100",
            positionMs = 0L
        )

        // Critical decoder error event (pinned)
        recorder.recordError(
            category = "DECODER",
            errorClass = "MediaCodecAudioTrackException",
            message = "Audio track initialization failed"
        )

        // Now flood 600 ordinary key actions to evict regular events from ring buffer
        for (i in 1..600) {
            recorder.recordKeyAction(20 + (i % 4), 0, "Activity", "Item_$i", "nav_$i")
        }

        // Ring buffer has 500 events
        assertEquals(500, recorder.getEventCount())

        // Pinned bookmarks MUST retain the session start and decoder error
        val pinned = recorder.getPinnedBookmarks()
        assertTrue(pinned.size >= 2)
        val hasStart = pinned.any { it.event == "PLAYBACK_START" }
        val hasError = pinned.any { it.category == "DECODER" && it.event == "ERROR" }
        assertTrue("Session start must be retained in pinned bookmarks", hasStart)
        assertTrue("Decoder error must be retained in pinned bookmarks", hasError)
    }

    @Test
    fun testScenario10AtomicWriteHandlesJsonCorruption() {
        val journeyFile = File(context.filesDir, "vox_user_journey.json")

        // Write completely malformed JSON to simulate disk corruption / partial write
        FileOutputStream(journeyFile).use { fos ->
            fos.write("{malformed JSON content [[[".toByteArray(StandardCharsets.UTF_8))
        }

        // Re-initialize recorder — must not throw and must recover cleanly with empty state
        VoxUserJourneyRecorder.resetForTests()
        val recoveredRecorder = VoxUserJourneyRecorder.instance(context)
        assertEquals(0, recoveredRecorder.getEventCount())
        assertEquals(0, recoveredRecorder.getPinnedBookmarks().size)

        // Should be able to record new events normally after corruption recovery
        recoveredRecorder.recordKeyAction("DPAD_CENTER", "recovered_action")
        assertEquals(1, recoveredRecorder.getEventCount())
    }

    @Test
    fun testScenario11PurgeAllClearsEverything() {
        recorder.setMode(VoxUserJourneyMode.DETAILED)
        recorder.recordPlayback("PLAYBACK_START", "Title", "vid1", 0L)
        recorder.saveCrashSnapshot(RuntimeException("Test Crash"))

        assertTrue(recorder.getEventCount() > 0)
        assertTrue(recorder.hasCrashSnapshot())

        recorder.purgeAll()

        assertEquals(0, recorder.getEventCount())
        assertEquals(0, recorder.getPinnedBookmarks().size)
        assertFalse(recorder.hasCrashSnapshot())
        assertFalse(recorder.hadAbnormalExit())
    }

    @Test
    fun testScenario12HadAbnormalExitDetectedOnNextSession() {
        // Simulate a crash occurring in a session
        recorder.saveCrashSnapshot(IllegalStateException("Simulated crash before shutdown"))
        assertTrue(recorder.hasCrashSnapshot())

        // Next boot: re-create recorder instance
        VoxUserJourneyRecorder.resetForTests()
        val nextSessionRecorder = VoxUserJourneyRecorder.instance(context)

        // Must detect that the previous session had an abnormal exit
        assertTrue(nextSessionRecorder.hadAbnormalExit())
        assertTrue(nextSessionRecorder.hasCrashSnapshot())

        // Cleaning up
        nextSessionRecorder.clearCrashSnapshot()
    }

    @Test
    fun testScenario13OffModePurgesOnActivation() {
        recorder.setMode(VoxUserJourneyMode.DETAILED)
        recorder.recordPlayback("PLAYBACK_START", "Title", "vid1", 0L)
        recorder.recordKeyAction("DPAD_DOWN", "nav")
        assertTrue(recorder.getEventCount() >= 2)

        // Switching mode to OFF must automatically purge all collected events
        recorder.setMode(VoxUserJourneyMode.OFF)
        assertEquals(0, recorder.getEventCount())
        assertEquals(0, recorder.getPinnedBookmarks().size)

        // Subsequent actions in OFF mode must not record anything
        recorder.recordKeyAction("DPAD_UP", "ignored")
        assertEquals(0, recorder.getEventCount())
    }
}
