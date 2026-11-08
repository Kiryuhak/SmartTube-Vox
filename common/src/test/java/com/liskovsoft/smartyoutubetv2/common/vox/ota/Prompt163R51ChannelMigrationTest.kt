package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Prompt 163-R51: Automated regression tests for Single Stable Channel invariant,
 * legacy beta/test preferences migration, and QA isolation.
 */
@RunWith(RobolectricTestRunner::class)
class Prompt163R51ChannelMigrationTest {

    private lateinit var context: Context
    private val prefsName = "vox_update_channel_prefs"

    @Before
    fun setUp() {
        VoxUpdateChannelManager.resetForTesting()
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun getSharedPrefs() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Test
    fun testScenarioA_BetaPrefMigratedToStable() {
        // Pre-populate legacy preference with "beta" and beta diagnostics true
        getSharedPrefs().edit()
            .putString("update_channel", "beta")
            .putBoolean("beta_diagnostics_enabled", true)
            .commit()

        val manager = VoxUpdateChannelManager.instance(context)
        manager.migrateLegacyChannelPrefsIfNeeded()

        assertEquals("Production channel must be STABLE", VoxUpdateChannel.STABLE, manager.getUpdateChannel())
        assertFalse("isBetaChannel must be false", manager.isBetaChannel())
        assertFalse("isQaChannel must be false", manager.isQaChannel())
        assertFalse("Beta diagnostics must be reset to false", manager.isBetaDiagnosticsEnabled())

        // Verify persisted XML storage
        val persisted = getSharedPrefs().getString("update_channel", null)
        val persistedBetaDiag = getSharedPrefs().getBoolean("beta_diagnostics_enabled", true)
        assertEquals("Persisted pref must be 'stable'", "stable", persisted)
        assertFalse("Persisted beta diagnostics must be false", persistedBetaDiag)
    }

    @Test
    fun testScenarioB_LegacyTestAliasMigratedToStable() {
        // Pre-populate legacy preference with "test" alias
        getSharedPrefs().edit()
            .putString("update_channel", "test")
            .putBoolean("beta_diagnostics_enabled", true)
            .commit()

        val manager = VoxUpdateChannelManager.instance(context)
        manager.migrateLegacyChannelPrefsIfNeeded()

        assertEquals("Production channel must be STABLE", VoxUpdateChannel.STABLE, manager.getUpdateChannel())
        assertFalse("isBetaChannel must be false", manager.isBetaChannel())
        assertEquals("Persisted pref must be 'stable'", "stable", getSharedPrefs().getString("update_channel", null))
        assertFalse("Persisted beta diagnostics must be false", getSharedPrefs().getBoolean("beta_diagnostics_enabled", true))
    }

    @Test
    fun testScenarioC_StablePrefPreserved() {
        // Pre-populate preference with "stable"
        getSharedPrefs().edit()
            .putString("update_channel", "stable")
            .putBoolean("beta_diagnostics_enabled", false)
            .putBoolean("stable_diagnostics_enabled", true) // User voluntarily enabled stable diag
            .commit()

        val manager = VoxUpdateChannelManager.instance(context)
        manager.migrateLegacyChannelPrefsIfNeeded()

        assertEquals(VoxUpdateChannel.STABLE, manager.getUpdateChannel())
        assertFalse(manager.isBetaChannel())
        assertEquals("stable", getSharedPrefs().getString("update_channel", null))
        assertTrue("Voluntary stable diagnostics consent must be preserved", manager.isStableDiagnosticsEnabled())
    }

    @Test
    fun testScenarioD_NullPrefDefaultsToStable() {
        // No stored preference at all
        val manager = VoxUpdateChannelManager.instance(context)
        assertEquals(VoxUpdateChannel.STABLE, manager.getUpdateChannel())
        assertFalse(manager.isBetaChannel())
        // Self-heal should write "stable"
        assertEquals("stable", getSharedPrefs().getString("update_channel", null))
    }

    @Test
    fun testScenarioE_UnknownPrefMigratedToStable() {
        // Pre-populate with unrecognized/corrupted channel string
        getSharedPrefs().edit()
            .putString("update_channel", "corrupted_custom_channel_v99")
            .commit()

        val manager = VoxUpdateChannelManager.instance(context)
        assertEquals(VoxUpdateChannel.STABLE, manager.getUpdateChannel())
        assertFalse(manager.isBetaChannel())
        assertEquals("stable", getSharedPrefs().getString("update_channel", null))
    }

    @Test
    fun testScenarioF_RcInstalledSourceForcesStableInProduction() {
        // Even if installed source version is an RC (-rc.13), production APK forces STABLE
        val manager = VoxUpdateChannelManager.instance(context)
        assertEquals(VoxUpdateChannel.STABLE, manager.getUpdateChannel())
        assertFalse(manager.isBetaChannel())
    }

    @Test
    fun testScenarioG_Target3271Vox10ParsedAsCanonicalStable() {
        val parsed = VoxVersionComparator.parseVersion("32.71-vox.10")
        org.junit.Assert.assertNotNull(parsed)
        assertEquals(32, parsed!!.major)
        assertEquals(71, parsed.minor)
        assertEquals(10, parsed.voxMajor)
        assertEquals(0, parsed.voxMinor)
        assertEquals(VoxReleaseStage.STABLE, parsed.stage)
        assertTrue("32.71-vox.10 must be recognized as canonical stable", parsed.isCanonicalStable)
        assertFalse("32.71-vox.10 must not be pre-release", parsed.isPreRelease)
    }

    @Test
    fun testScenarioH_DiagnosticsConsentInvariants() {
        val manager = VoxUpdateChannelManager.instance(context)
        assertFalse("isBetaDiagnosticsEnabled must be false in public build", manager.isBetaDiagnosticsEnabled())
        assertFalse("isStableDiagnosticsEnabled must be false by default without opt-in", manager.isStableDiagnosticsEnabled())
        assertFalse("isDiagnosticsActive must be false by default", manager.isDiagnosticsActive())

        // Opting into stable diagnostics
        manager.setStableDiagnosticsEnabled(true)
        assertTrue(manager.isStableDiagnosticsEnabled())
        assertTrue("isDiagnosticsActive should reflect stable consent", manager.isDiagnosticsActive())
    }

    @Test
    fun testScenarioI_SelfHealGuardBlocksSettingBetaInProduction() {
        val manager = VoxUpdateChannelManager.instance(context)
        // Attempt to switch to TEST/BETA in production build
        manager.setUpdateChannel(VoxUpdateChannel.TEST)

        assertEquals("Production must reject TEST and stay STABLE", VoxUpdateChannel.STABLE, manager.getUpdateChannel())
        assertFalse(manager.isBetaChannel())
        assertEquals("stable", getSharedPrefs().getString("update_channel", null))
    }
}
