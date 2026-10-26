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

@RunWith(RobolectricTestRunner::class)
class VoxUserJourneyRecorderTest {

    private lateinit var context: Context
    private lateinit var recorder: VoxUserJourneyRecorder

    @Before
    fun setUp() {
        VoxUserJourneyRecorder.resetForTests()
        context = RuntimeEnvironment.getApplication()
        recorder = VoxUserJourneyRecorder.instance(context)
        recorder.clear()
        recorder.clearCrashSnapshot()
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
        recorder.setMode(VoxUserJourneyMode.DETAILED)

        for (i in 1..250) {
            recorder.recordKeyAction(20 + (i % 4), 0, "TestActivity", "Card_$i", "op_$i")
        }

        // Bounded ring buffer must hold at most MAX_EVENTS = 200
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
}
