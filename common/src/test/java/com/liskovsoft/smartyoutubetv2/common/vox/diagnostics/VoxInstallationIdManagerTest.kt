package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class VoxInstallationIdManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        VoxInstallationIdManager.resetInstanceForTesting()
        val prefs = context.getSharedPreferences(VoxInstallationIdManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testInstallationIdGeneratesValidUuidOnFirstCall() {
        val manager = VoxInstallationIdManager.getInstance(context)
        val id = manager.getInstallationId()

        assertNotNull(id)
        assertFalse(id.isBlank())
        // Verify UUID format (8-4-4-4-12 hex chars)
        val parsed = UUID.fromString(id)
        assertEquals(4, parsed.version())
    }

    @Test
    fun testInstallationIdPersistsAcrossCalls() {
        val manager = VoxInstallationIdManager.getInstance(context)
        val id1 = manager.getInstallationId()
        val id2 = manager.getInstallationId()

        assertEquals("Subsequent calls must return identical installationId", id1, id2)

        // Verify it is physically written to SharedPreferences
        val prefs = context.getSharedPreferences(VoxInstallationIdManager.PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(VoxInstallationIdManager.KEY_INSTALLATION_ID, null)
        assertEquals(id1, stored)
    }

    @Test
    fun testInstallationIdResetGeneratesNewId() {
        val manager = VoxInstallationIdManager.getInstance(context)
        val initialId = manager.getInstallationId()

        val newId = manager.resetInstallationId()

        assertNotEquals("Reset must create a distinct ID", initialId, newId)
        assertEquals(newId, manager.getInstallationId())

        val prefs = context.getSharedPreferences(VoxInstallationIdManager.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(newId, prefs.getString(VoxInstallationIdManager.KEY_INSTALLATION_ID, null))
    }

    @Test
    fun testDiagnosticReportIncludesInstallationIdInJson() {
        val report = VoxDiagnosticReport(
            schema = VoxDiagnosticReport.SCHEMA_V3,
            reportId = "VOX-TEST-INSTALL-01",
            appVersion = "32.56-vox.9.2",
            appVersionCode = 2446009,
            platform = "Android TV",
            manufacturer = "Nvidia",
            model = "Shield TV Pro",
            osName = "Android",
            osVersion = "11",
            sdkInt = 30,
            deviceTier = "HIGH",
            videoCodecs = emptyMap(),
            audioCodecs = emptyMap(),
            display = emptyMap(),
            currentPolicy = emptyMap(),
            recommendedSettings = emptyMap(),
            installationId = "inst-uuid-fixed-test"
        )

        val jsonStr = report.toJson()
        assertTrue(jsonStr.contains("inst-uuid-fixed-test"))

        val parsed = VoxDiagnosticReport.fromJson(jsonStr)
        assertNotNull(parsed)
        assertEquals("inst-uuid-fixed-test", parsed?.installationId)
    }

    @Test
    fun testDiagnosticReportBackwardCompatibilityWithoutInstallationId() {
        // Legacy JSON without installationId field
        val legacyJson = JSONObject().apply {
            put("schema", VoxDiagnosticReport.SCHEMA_V2)
            put("reportId", "VOX-LEGACY-99")
            put("appVersion", "32.56-vox.9.1")
            put("appVersionCode", 2446009)
            put("platform", "Android TV")
            put("manufacturer", "Sony")
            put("model", "BRAVIA 4K")
            put("osName", "Android")
            put("osVersion", "9")
            put("sdkInt", 28)
            put("deviceTier", "HIGH")
        }

        val parsed = VoxDiagnosticReport.fromJson(legacyJson)
        assertNotNull(parsed)
        assertNull("Missing installationId in legacy JSON must gracefully evaluate to null", parsed?.installationId)
    }
}
