package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoxHeartbeatManagerTest {

    private lateinit var context: Context
    private lateinit var heartbeatManager: VoxHeartbeatManager
    private lateinit var lifecycleManager: VoxDiagnosticsLifecycleManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        heartbeatManager = VoxHeartbeatManager.getInstance(context)
        heartbeatManager.resetForTesting()
        lifecycleManager = VoxDiagnosticsLifecycleManager.instance(context)
        lifecycleManager.resetForTest()
    }

    @Test
    fun testHeartbeatSendsWhenDue() {
        // Initial state: lastSent is 0 -> due immediately
        heartbeatManager.notifyUserActivity()

        val payload = VoxHeartbeatManager.lastSentPayloadForTesting
        assertNotNull("Payload must be dispatched when due", payload)

        val json = JSONObject(payload!!)
        assertTrue("Must include installationId", json.has("installationId"))
        assertNotNull(json.getString("installationId"))
        assertEquals("Android TV", json.getString("platform"))
        assertTrue("Must include timestamp", json.has("timestamp"))
        assertTrue(heartbeatManager.getLastHeartbeatSentMs() > 0L)
    }

    @Test
    fun testHeartbeatThrottledWithin30Minutes() {
        val now = System.currentTimeMillis()
        heartbeatManager.setLastHeartbeatSentMs(now - (10 * 60 * 1000L)) // 10 minutes ago (< 30 min)
        VoxHeartbeatManager.lastSentPayloadForTesting = null

        heartbeatManager.notifyUserActivity()

        assertNull("Heartbeat must be suppressed when < 30 min since last sending", VoxHeartbeatManager.lastSentPayloadForTesting)
    }

    @Test
    fun testHeartbeatBlockedWhenUserOptedOut() {
        lifecycleManager.setUserExplicitOptOut(true)
        VoxHeartbeatManager.lastSentPayloadForTesting = null

        heartbeatManager.notifyUserActivity()

        assertNull("Heartbeat must NEVER be sent when user explicitly opted out", VoxHeartbeatManager.lastSentPayloadForTesting)
    }

    @Test
    fun testHeartbeatResumesWhenUserOptInRestored() {
        lifecycleManager.setUserExplicitOptOut(true)
        heartbeatManager.notifyUserActivity()
        assertNull(VoxHeartbeatManager.lastSentPayloadForTesting)

        // Restore opt-in
        lifecycleManager.setUserExplicitOptOut(false)
        heartbeatManager.notifyUserActivity()

        assertNotNull("Heartbeat must resume when user opts back in", VoxHeartbeatManager.lastSentPayloadForTesting)
    }

    @Test
    fun testHeartbeatDispatchesAfter30MinutesElapsed() {
        val now = System.currentTimeMillis()
        // 31 minutes ago (> 30 min)
        heartbeatManager.setLastHeartbeatSentMs(now - (31 * 60 * 1000L))
        VoxHeartbeatManager.lastSentPayloadForTesting = null

        heartbeatManager.notifyUserActivity()

        assertNotNull("Heartbeat must be dispatched when > 30 min since last sending", VoxHeartbeatManager.lastSentPayloadForTesting)
        assertTrue(heartbeatManager.getLastHeartbeatSentMs() >= now)
    }

    @Test
    fun testHeartbeatIndependentOfDiagnosticsCycleExpiration() {
        val now = System.currentTimeMillis()
        // Start diagnostics cycle 100 hours ago (> 72 hours standard cycle expiration)
        lifecycleManager.onAppStartup("32.56-vox.9.2-rc.1", now - (100 * 3600 * 1000L))
        assertTrue("Diagnostics cycle must be expired after 100 hours", !lifecycleManager.isAutoDiagnosticsPermitted(now))

        // Heartbeat should still dispatch cleanly if 30 minutes elapsed and user did not opt out
        heartbeatManager.setLastHeartbeatSentMs(now - (35 * 60 * 1000L))
        VoxHeartbeatManager.lastSentPayloadForTesting = null

        heartbeatManager.notifyUserActivity()

        assertNotNull("Heartbeat must dispatch even after 72h/120h diagnostics cycle expires", VoxHeartbeatManager.lastSentPayloadForTesting)
    }

    @Test
    fun testHeartbeatInterval30MinutesAndDiagnosticsInterval6Hours() {
        assertEquals("Heartbeat interval must be strictly 30 minutes", 30 * 60 * 1000L, VoxHeartbeatManager.HEARTBEAT_INTERVAL_MS)
        assertEquals("Standard diagnostics interval must be strictly 6 hours", 6 * 60 * 60 * 1000L, VoxDiagnosticsLifecycleManager.STANDARD_INTERVAL_MS)
        assertEquals("Extended diagnostics interval must be strictly 12 hours", 12 * 60 * 60 * 1000L, VoxDiagnosticsLifecycleManager.EXTENDED_INTERVAL_MS)
    }

    @Test
    fun testSeparateAnalyticsOptOut() {
        // 1. Separate analytics opt-out disables heartbeat even if error diagnostics is active
        heartbeatManager.setAnalyticsOptedOut(true)
        lifecycleManager.setUserExplicitOptOut(false)
        VoxHeartbeatManager.lastSentPayloadForTesting = null

        heartbeatManager.notifyUserActivity()
        assertNull("Heartbeat must be suppressed when analytics opt-out is set", VoxHeartbeatManager.lastSentPayloadForTesting)

        // 2. Re-enabling analytics restores heartbeat
        heartbeatManager.setAnalyticsOptedOut(false)
        heartbeatManager.notifyUserActivity()
        assertNotNull("Heartbeat must dispatch after analytics opt-out is cleared", VoxHeartbeatManager.lastSentPayloadForTesting)
    }
}
