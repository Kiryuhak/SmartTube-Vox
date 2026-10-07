package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoxDownloadDiagnosticsTest {

    @Test
    fun testDownloadDiagnosticsSerializationAndPrivacy() {
        val downloadDiag = mapOf<String, Any>(
            "lastStage" to "PACKAGING",
            "lastErrorCategory" to "MUX_FAILED",
            "lastOperation" to "PACKAGING",
            "retryCount" to 1,
            "completedVideoBytes" to 15420000L,
            "completedAudioBytes" to 1240000L,
            "translatedAudioPresent" to true,
            "packagingStarted" to true,
            "packagingCompleted" to false,
            "finalizeCompleted" to false,
            "freeStorageMb" to 4250L
        )

        val report = VoxDiagnosticReport(
            appVersion = "32.56-vox.8-dev",
            appVersionCode = 2446009,
            platform = "Android TV",
            manufacturer = "DuneHD",
            model = "Pro Vision 4K",
            osName = "Android",
            osVersion = "11",
            sdkInt = 30,
            deviceTier = "Стандартный",
            videoCodecs = mapOf("VP9" to "Поддерживается [HW]"),
            audioCodecs = mapOf("AC3" to "Декод: Поддерживается"),
            display = mapOf("resolution" to "1920x1080", "refreshRateHz" to 59),
            currentPolicy = mapOf("mode" to "MAX_QUALITY", "maxQualityHeight" to 2160),
            recommendedSettings = mapOf("maxQualityHeight" to 1080),
            downloadDiagnostics = downloadDiag
        )

        val jsonString = report.toJson()
        val jsonObj = JSONObject(jsonString)

        assertTrue("Should contain downloadDiagnostics", jsonObj.has("downloadDiagnostics"))
        val ddObj = jsonObj.getJSONObject("downloadDiagnostics")
        assertEquals("PACKAGING", ddObj.getString("lastStage"))
        assertEquals("MUX_FAILED", ddObj.getString("lastErrorCategory"))
        assertEquals("PACKAGING", ddObj.getString("lastOperation"))
        assertEquals(1, ddObj.getInt("retryCount"))
        assertEquals(15420000L, ddObj.getLong("completedVideoBytes"))
        assertEquals(4250L, ddObj.getLong("freeStorageMb"))
        assertFalse(ddObj.getBoolean("packagingCompleted"))

        // Strict Zero-Telemetry Privacy Audits
        assertFalse("Must NOT contain YouTube URLs", jsonString.contains("googlevideo.com"))
        assertFalse("Must NOT contain YouTube URLs", jsonString.contains("youtu.be"))
        assertFalse("Must NOT contain auth tokens", jsonString.contains("access_token"))
        assertFalse("Must NOT contain oauth tokens", jsonString.contains("oauth"))
        assertFalse("Must NOT contain cookies", jsonString.contains("cookie"))
        assertFalse("Must NOT contain user file paths", jsonString.contains("/storage/emulated/0"))
        assertFalse("Must NOT contain user video titles", jsonString.contains("Super Video Title"))

        val textReport = report.toFormattedText()
        assertTrue("Formatted text should mention download diagnostics", textReport.contains("--- ДИАГНОСТИКА СКАЧИВАНИЯ ---"))
        assertTrue("Formatted text should show stage", textReport.contains("lastStage: PACKAGING"))
    }

    @Test
    fun testLogEventContainsRichSafeDiagnosticFields() {
        val contextMap = mapOf(
            "stage" to "PACKAGING",
            "errorCategory" to "MUX_FAILED",
            "reason" to "OUTPUT_STREAM_WRITE_FAILED",
            "operation" to "PACKAGING",
            "retryCount" to "1",
            "videoBytesDownloaded" to "5000000",
            "audioBytesDownloaded" to "300000",
            "translationBytesDownloaded" to "250000",
            "freeStorageMb" to "3200",
            "outputContainer" to "mkv",
            "temporaryFileCount" to "3"
        )

        val event = VoxLogEvent(
            timestamp = System.currentTimeMillis(),
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.DOWNLOAD,
            code = VoxLogCode.DOWNLOAD_FAILED,
            message = "Не удалось собрать итоговый файл",
            context = contextMap
        )

        val json = event.toJson()
        assertEquals(VoxLogCode.DOWNLOAD_FAILED, json.getString("code"))
        val ctx = json.getJSONObject("context")
        assertEquals("PACKAGING", ctx.getString("stage"))
        assertEquals("MUX_FAILED", ctx.getString("errorCategory"))
        assertEquals("OUTPUT_STREAM_WRITE_FAILED", ctx.getString("reason"))
        assertEquals("3200", ctx.getString("freeStorageMb"))
        assertEquals("3", ctx.getString("temporaryFileCount"))

        // Privacy check
        val eventStr = json.toString()
        assertFalse(eventStr.contains("https://"))
        assertFalse(eventStr.contains("/data/user/"))
    }
}
