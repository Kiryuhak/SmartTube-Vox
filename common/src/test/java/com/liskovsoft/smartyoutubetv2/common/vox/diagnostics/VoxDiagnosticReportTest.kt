package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
class VoxDiagnosticReportTest {

    @Test
    fun testDiagnosticReportCreationAndJsonSchemaV2() {
        val context = RuntimeEnvironment.getApplication()
        val store = VoxLogStore.instance(context)
        store.clearLogs()

        // Populate 50 safe events
        for (i in 1..50) {
            store.addEvent(
                VoxLogEvent(
                    timestamp = System.currentTimeMillis() + i,
                    level = VoxLogLevel.INFO,
                    category = VoxLogCategory.DOWNLOAD,
                    code = "DOWNLOAD_STEP_$i",
                    message = "Safe download step $i"
                )
            )
        }

        val report = VoxDiagnosticReport.create(context, "VOX-TEST01", true)

        assertNotNull(report)
        assertEquals(VoxDiagnosticReport.SCHEMA_V3, report.schema)
        assertEquals("VOX-TEST01", report.reportId)
        assertTrue(report.appVersion.isNotBlank())
        assertTrue(report.appVersionCode > 0)
        assertTrue(report.platform.isNotBlank())
        assertNotNull(report.videoCodecs)
        assertNotNull(report.audioCodecs)
        assertNotNull(report.display)
        assertNotNull(report.currentPolicy)
        assertNotNull(report.recommendedSettings)
        assertEquals(50, report.safeRecentEvents.size)

        val jsonString = report.toJson()
        val json = JSONObject(jsonString)
        assertEquals(VoxDiagnosticReport.SCHEMA_V3, json.getString("schema"))
        assertEquals("VOX-TEST01", json.getString("reportId"))
        assertEquals(report.appVersion, json.getString("appVersion"))
        assertTrue(json.has("videoCodecs"))
        assertTrue(json.has("audioCodecs"))
        assertTrue(json.has("display"))
        assertTrue(json.has("currentPolicy"))
        assertTrue(json.has("recommendedSettings"))
        assertTrue(json.has("safeRecentEvents"))
        assertEquals(50, json.getJSONArray("safeRecentEvents").length())

        // Guaranteed privacy: No PII, no credentials
        assertTrue(VoxDiagnosticSanitizer.isSafePayload(jsonString))
    }

    @Test
    fun testFormattedTextOutput() {
        val context = RuntimeEnvironment.getApplication()
        val report = VoxDiagnosticReport.create(context, "VOX-TEST02")
        val formatted = report.toFormattedText()

        assertTrue(formatted.contains("=== SmartTube VOX — Диагностический отчёт ==="))
        assertTrue(formatted.contains("ID отчёта: VOX-TEST02"))
        assertTrue(formatted.contains("--- ВИДЕОДЕКОДЕРЫ ---"))
        assertTrue(formatted.contains("--- АУДИОДЕКОДЕРЫ И PASSTHROUGH ---"))
        assertTrue(formatted.contains("--- ЭКРАН И HDR ---"))
        assertTrue(formatted.contains("--- РЕКОМЕНДУЕМЫЕ НАСТРОЙКИ VOX ---"))
    }

    @Test
    fun testProductionReportExcludesSyntheticFixturesAndRawUuids() {
        val context = RuntimeEnvironment.getApplication()
        val store = VoxLogStore.instance(context)
        store.clearLogs()

        store.addEvent(
            VoxLogEvent(
                timestamp = System.currentTimeMillis() + 1,
                level = VoxLogLevel.ERROR,
                category = VoxLogCategory.DOWNLOAD,
                code = VoxLogCode.DOWNLOAD_STATE_INCONSISTENT,
                message = "Download invariant failed for p37-resume-rick-20261006"
            )
        )
        store.addEvent(
            VoxLogEvent(
                timestamp = System.currentTimeMillis() + 2,
                level = VoxLogLevel.INFO,
                category = VoxLogCategory.DOWNLOAD,
                code = "DOWNLOAD_STEP",
                message = "Legitimate download step"
            )
        )

        val report = VoxDiagnosticReport.create(context, "VOX-PROD-TEST", true)
        val json = report.toJson()

        assertNull("Production report top-level error should not be synthetic p37 fixture", report.errorCategory)
        assertFalse("Production report top-level signature must not contain synthetic fixture", report.errorSignature?.contains("p37") == true)
        assertFalse("Production report top-level signature must not contain synthetic download error", report.errorSignature?.contains("DOWNLOAD") == true)
        assertTrue("Production report must contain real events", json.contains("Legitimate download step"))
    }
}
