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

    private val hourMs = 3_600_000L
    private val minuteMs = 60_000L

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = VoxDiagnosticsLifecycleManager.instance(context)
        manager.resetForTest()
    }

    @Test
    fun testEightDaySequentialLifecycleBoundaries() {
        val t0 = 1_000_000_000L
        manager.onAppStartup("32.56-vox.9.2-rc.11", t0)

        // 1. At T0: Phase 1 active, 6h interval, not extended mode
        assertTrue("Auto diagnostics should be active at T0", manager.isAutoDiagnosticsActive())
        assertTrue("Auto diagnostics permitted at T0", manager.isAutoDiagnosticsPermitted(t0))
        assertEquals(t0, manager.getCycleStartedAt())
        assertEquals("32.56-vox.9.2-rc.11", manager.getLastAppVersion())
        assertEquals(6 * hourMs, manager.getCurrentIntervalMs(t0))
        assertFalse("Extended mode is false in Phase 1", manager.isExtendedModeEnabled(t0))

        // Initial periodic check: lastSubmission == 0 -> allowed
        assertTrue(manager.canSendPeriodic(t0))
        manager.setLastSubmissionTimestamp(t0)

        // 2. Boundary: T0 + 5h 59m (Phase 1, cannot send periodic yet)
        val t5h59m = t0 + 5 * hourMs + 59 * minuteMs
        assertTrue(manager.isAutoDiagnosticsPermitted(t5h59m))
        assertEquals(6 * hourMs, manager.getCurrentIntervalMs(t5h59m))
        assertFalse("Cannot send periodic before 6h interval elapsed", manager.canSendPeriodic(t5h59m))

        // 3. Boundary: T0 + 6h (Phase 1, can send next periodic)
        val t6h = t0 + 6 * hourMs
        assertTrue(manager.isAutoDiagnosticsPermitted(t6h))
        assertTrue("Can send periodic at 6h", manager.canSendPeriodic(t6h))
        manager.setLastSubmissionTimestamp(t6h)

        // 4. Boundary: T0 + 71h 59m (end of Phase 1)
        val t71h59m = t0 + 71 * hourMs + 59 * minuteMs
        assertTrue(manager.isAutoDiagnosticsPermitted(t71h59m))
        assertEquals(6 * hourMs, manager.getCurrentIntervalMs(t71h59m))
        assertFalse(manager.isExtendedModeEnabled(t71h59m))

        // 5. Boundary: T0 + 72h (transition to Phase 2: stability control, 12h interval)
        val t72h = t0 + 72 * hourMs
        assertTrue(manager.isAutoDiagnosticsPermitted(t72h))
        assertEquals(12 * hourMs, manager.getCurrentIntervalMs(t72h))
        assertTrue("Extended mode is true in Phase 2", manager.isExtendedModeEnabled(t72h))
        // Since last submission was at 6h, can send at 72h
        assertTrue(manager.canSendPeriodic(t72h))
        manager.setLastSubmissionTimestamp(t72h)

        // In Phase 2, interval is 12h: at 72h + 6h = 78h cannot send yet
        val t78h = t72h + 6 * hourMs
        assertFalse("In Phase 2, 6h is not enough for interval", manager.canSendPeriodic(t78h))

        // 6. Boundary: T0 + 84h (72h + 12h -> second submission in Phase 2 permitted)
        val t84h = t0 + 84 * hourMs
        assertTrue("At 84h (12h after 72h), periodic submission permitted", manager.canSendPeriodic(t84h))
        manager.setLastSubmissionTimestamp(t84h)

        // 7. Boundary: T0 + 191h 59m (last minute of Phase 2 / 8 days)
        val t191h59m = t0 + 191 * hourMs + 59 * minuteMs
        assertTrue(manager.isAutoDiagnosticsPermitted(t191h59m))
        assertEquals(12 * hourMs, manager.getCurrentIntervalMs(t191h59m))
        assertTrue(manager.isExtendedModeEnabled(t191h59m))

        // 8. Boundary: T0 + 192h (exact expiration moment)
        val t192h = t0 + 192 * hourMs
        assertFalse("At 192h (8 days), cycle expires and isAutoDiagnosticsPermitted must be false", manager.isAutoDiagnosticsPermitted(t192h))
        assertFalse("isAutoDiagnosticsActive must become false after 192h", manager.isAutoDiagnosticsActive())
        assertFalse("Extended mode is false after cycle expiration", manager.isExtendedModeEnabled(t192h))
        assertFalse("Cannot send periodic after expiration", manager.canSendPeriodic(t192h))

        // 9. Boundary: T0 + 192h + 1m
        val t192h1m = t0 + 192 * hourMs + 1 * minuteMs
        assertFalse("Past 192h, auto diagnostics remains disabled", manager.isAutoDiagnosticsPermitted(t192h1m))
    }

    @Test
    fun testAppRestartDoesNotResetCycleTimer() {
        val t0 = 1_000_000_000L
        manager.onAppStartup("32.56-vox.9.2-rc.11", t0)
        assertEquals(t0, manager.getCycleStartedAt())

        // App reboots 48 hours later (Phase 1)
        manager.onAppStartup("32.56-vox.9.2-rc.11", t0 + 48 * hourMs)
        assertEquals("cycleStartedAt must NOT be reset on same-version restart", t0, manager.getCycleStartedAt())
        assertTrue(manager.isAutoDiagnosticsPermitted(t0 + 48 * hourMs))

        // App reboots 100 hours later (Phase 2)
        manager.onAppStartup("32.56-vox.9.2-rc.11", t0 + 100 * hourMs)
        assertEquals("cycleStartedAt must NOT be reset", t0, manager.getCycleStartedAt())
        assertTrue(manager.isAutoDiagnosticsPermitted(t0 + 100 * hourMs))
        assertEquals(12 * hourMs, manager.getCurrentIntervalMs(t0 + 100 * hourMs))

        // App reboots 200 hours later (Expired)
        manager.onAppStartup("32.56-vox.9.2-rc.11", t0 + 200 * hourMs)
        assertFalse("Auto diagnostics must remain expired on reboot past 192h", manager.isAutoDiagnosticsPermitted(t0 + 200 * hourMs))
    }

    @Test
    fun testNewVersionStartsFreshCycle() {
        val t0 = 1_000_000_000L
        manager.onAppStartup("32.56-vox.9.2-rc.10", t0)
        assertEquals("32.56-vox.9.2-rc.10", manager.getLastAppVersion())

        val updateTime = t0 + 200 * hourMs // 200h later (v10 expired)
        assertFalse(manager.isAutoDiagnosticsPermitted(updateTime))

        // App updated to RC.11
        manager.onAppStartup("32.56-vox.9.2-rc.11", updateTime)
        assertEquals("32.56-vox.9.2-rc.11", manager.getLastAppVersion())
        assertEquals("Fresh cycle start time must be update time", updateTime, manager.getCycleStartedAt())
        assertTrue("Auto diagnostics reactivated for new app version", manager.isAutoDiagnosticsActive())
        assertTrue("Permitted at start of fresh cycle", manager.isAutoDiagnosticsPermitted(updateTime))
        assertEquals(6 * hourMs, manager.getCurrentIntervalMs(updateTime))
    }

    @Test
    fun testUserExplicitOptOutOverridesAll() {
        val t0 = 1_000_000_000L
        manager.onAppStartup("32.56-vox.9.2-rc.11", t0)
        assertTrue(manager.isAutoDiagnosticsPermitted(t0 + 1000L))

        // User explicitly opts out
        manager.setUserExplicitOptOut(true, t0 + 2000L)
        assertTrue(manager.isUserOptedOut())
        assertFalse(manager.isAutoDiagnosticsActive())
        assertFalse(manager.isAutoDiagnosticsPermitted(t0 + 3000L))
        assertFalse(manager.canSendPeriodic(t0 + 3000L))

        // App update must NOT override user explicit opt-out
        manager.onAppStartup("32.56-vox.9.2-rc.12", t0 + 10000L)
        assertFalse("App update must not override explicit user opt-out", manager.isAutoDiagnosticsPermitted(t0 + 11000L))

        // User changes mind and re-enables
        manager.setUserExplicitOptOut(false, t0 + 12000L)
        assertFalse(manager.isUserOptedOut())
        assertTrue(manager.isAutoDiagnosticsActive())
        assertTrue(manager.isAutoDiagnosticsPermitted(t0 + 13000L))
    }
}
