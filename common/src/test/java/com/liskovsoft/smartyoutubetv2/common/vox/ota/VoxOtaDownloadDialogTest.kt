package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.app.Activity
import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class VoxOtaDownloadDialogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        VoxOtaUpdateManager.resetInstanceForTesting()
    }

    @Test
    fun testCancelDownloadFlagAndCleanup() {
        val manager = VoxOtaUpdateManager.instance(context)
        val cacheDir = context.cacheDir
        val tmpFile = File(cacheDir, "update.apk.tmp")
        tmpFile.writeText("partial-download-bytes")
        assertTrue(tmpFile.exists())

        manager.cancelDownload()
        assertTrue(manager.isDownloadCancelled())
        assertFalse("Temp download file must be removed on cancel", tmpFile.exists())
    }

    @Test
    fun testFindActivityTraversesContextWrapper() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val wrapped = android.content.ContextWrapper(activity)
        val doubleWrapped = android.content.ContextWrapper(wrapped)

        val found = VoxOtaDownloadDialog.findActivity(doubleWrapped)
        assertEquals(activity, found)
    }

    @Test
    fun testHistoricalEventsIgnoredByIncidentClassifier() {
        val oldEvent = VoxLogEvent(
            timestamp = System.currentTimeMillis() - 100000L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_HTTP_ERROR,
            message = "Old HTTP 403 error",
            context = mapOf("appVersion" to "32.56-vox.8"),
            isHistorical = true
        )

        val currentEvent = VoxLogEvent(
            timestamp = System.currentTimeMillis() - 1000L,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.OTA,
            code = VoxLogCode.OTA_RATE_LIMIT,
            message = "Rate limited on current version",
            context = mapOf("appVersion" to "32.56-vox.9.1"),
            isHistorical = false
        )

        val prominent = VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(listOf(oldEvent, currentEvent))
        assertNotNull(prominent)
        assertEquals("Historical error should be ignored in favor of active current warning", currentEvent.code, prominent!!.code)
    }

    @Test
    fun testLogEventSerializationPreservesHistoricalFlag() {
        val event = VoxLogEvent(
            timestamp = 12345678L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_FINAL_403,
            message = "Terminal 403",
            isHistorical = true
        )

        val json = event.toJson()
        assertTrue(json.optBoolean("isHistorical"))

        val deserialized = VoxLogEvent.fromJson(json)
        assertNotNull(deserialized)
        assertTrue(deserialized!!.isHistorical)
        assertEquals(event.code, deserialized.code)
    }
}
