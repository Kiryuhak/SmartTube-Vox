package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoxDiagnosticsLifecycleTest {

    private lateinit var context: Context
    private lateinit var manager: VoxDiagnosticsLifecycleManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = VoxDiagnosticsLifecycleManager.instance(context)
        manager.resetForTest()
    }

    @Test
    fun testFirstInstallStartsStandardCycle72h() {
        val startTime = 1000000L
        manager.onAppStartup("9.2.01", startTime)

        assertTrue("Auto diagnostics should be active after first install", manager.isAutoDiagnosticsActive())
        assertFalse("Extended mode should be false by default", manager.isExtendedModeEnabled())
        assertEquals(startTime, manager.getCycleStartedAt())
        assertEquals("9.2.01", manager.getLastAppVersion())

        // At 1 hour, permitted
        assertTrue(manager.isAutoDiagnosticsPermitted(startTime + 3_600_000L))

        // Check periodic interval: initial submission permitted (lastSubmission == 0)
        assertTrue(manager.canSendPeriodic(startTime + 1000L))

        // After submission, next send requires 6h interval
        manager.setLastSubmissionTimestamp(startTime + 1000L)
        assertFalse(manager.canSendPeriodic(startTime + 3_600_000L)) // only 1h passed
        assertTrue(manager.canSendPeriodic(startTime + 6 * 3_600_000L + 2000L)) // 6h+ passed

        // At 71 hours, still permitted
        assertTrue(manager.isAutoDiagnosticsPermitted(startTime + 71 * 3_600_000L))

        // At 72 hours, expired and disabled
        assertFalse(manager.isAutoDiagnosticsPermitted(startTime + 72 * 3_600_000L + 1L))
        assertFalse("Auto diagnostics must be inactive after 72h expiration", manager.isAutoDiagnosticsActive())
    }

    @Test
    fun testAppRestartDoesNotResetCycleTimer() {
        val startTime = 1000000L
        manager.onAppStartup("9.2.01", startTime)
        assertEquals(startTime, manager.getCycleStartedAt())

        // App reboots 10 hours later
        manager.onAppStartup("9.2.01", startTime + 10 * 3_600_000L)
        assertEquals("cycleStartedAt must NOT be reset on same-version restart", startTime, manager.getCycleStartedAt())

        // Check expiration at 72 hours from original start
        assertFalse(manager.isAutoDiagnosticsPermitted(startTime + 73 * 3_600_000L))
    }

    @Test
    fun testNewVersionStartsFreshCycle() {
        val startTime = 1000000L
        manager.onAppStartup("9.1.00", startTime)
        assertEquals("9.1.00", manager.getLastAppVersion())

        val updateTime = startTime + 100 * 3_600_000L // 100h later (v9.1 already expired)
        assertFalse(manager.isAutoDiagnosticsPermitted(updateTime))

        // App updated to 9.2.01
        manager.onAppStartup("9.2.01", updateTime)
        assertEquals("9.2.01", manager.getLastAppVersion())
        assertEquals("Fresh cycle start time must be update time", updateTime, manager.getCycleStartedAt())
        assertTrue("Auto diagnostics reactivated for new app version", manager.isAutoDiagnosticsActive())
    }

    @Test
    fun testExtendedModeDuration120hFromOriginalStart() {
        val startTime = 1000000L
        manager.onAppStartup("9.2.01", startTime)

        // At 24h, user enables Extended Diagnostics
        val enableTime = startTime + 24 * 3_600_000L
        val enabled = manager.setExtendedMode(true, enableTime)
        assertTrue("Enabling extended mode should succeed within 120h", enabled)
        assertTrue(manager.isExtendedModeEnabled())
        assertEquals(12 * 3_600_000L, manager.getCurrentIntervalMs())
        assertEquals("Original cycleStartedAt must be preserved", startTime, manager.getCycleStartedAt())

        // At 75h: standard (72h) would be expired, but extended (120h) is still active
        assertTrue("Extended mode must keep auto diagnostics active at 75h", manager.isAutoDiagnosticsPermitted(startTime + 75 * 3_600_000L))

        // At 120h + 1s: expired
        val expireTime = startTime + 120 * 3_600_000L + 1000L
        assertFalse("Extended mode must expire after 120h from version cycle start", manager.isAutoDiagnosticsPermitted(expireTime))
        assertFalse(manager.isAutoDiagnosticsActive())
        assertFalse("Extended toggle must be cleared after expiration", manager.isExtendedModeEnabled())
    }

    @Test
    fun testExtendedModeCannotBeEnabledAfter120hExpired() {
        val startTime = 1000000L
        manager.onAppStartup("9.2.01", startTime)

        // At 121h, user attempts to enable extended mode
        val lateAttempt = startTime + 121 * 3_600_000L
        val enabled = manager.setExtendedMode(true, lateAttempt)
        assertFalse("Enabling extended mode after 120h total duration must fail", enabled)
        assertFalse(manager.isExtendedModeEnabled())
        assertFalse(manager.isAutoDiagnosticsActive())
    }

    @Test
    fun testUserExplicitOptOutOverridesAll() {
        val startTime = 1000000L
        manager.onAppStartup("9.2.01", startTime)
        assertTrue(manager.isAutoDiagnosticsPermitted(startTime + 1000L))

        // User explicitly opts out
        manager.setUserExplicitOptOut(true, startTime + 2000L)
        assertTrue(manager.isUserOptedOut())
        assertFalse(manager.isAutoDiagnosticsActive())
        assertFalse(manager.isAutoDiagnosticsPermitted(startTime + 3000L))

        // Next app launch of new version does NOT re-enable if user opted out
        manager.onAppStartup("9.3.00", startTime + 10000L)
        assertFalse("App update must not override explicit user opt-out", manager.isAutoDiagnosticsPermitted(startTime + 11000L))
    }
}
