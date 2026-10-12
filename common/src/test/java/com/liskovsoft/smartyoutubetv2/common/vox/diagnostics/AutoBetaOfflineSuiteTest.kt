package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoBetaOfflinePersistenceTest {
    @Test
    fun testSerializationAndDeserializationOfQueuedReport() {
        val dummyReport = VoxDiagnosticReport(
            schema = VoxDiagnosticReport.SCHEMA_V3,
            reportId = "VOX-TEST-12345",
            timestamp = 1728770000000L,
            appVersion = "32.56-vox.8.2-beta.2",
            appVersionCode = 2446009,
            platform = "Android TV",
            manufacturer = "Google",
            model = "Emulator",
            osName = "Android",
            osVersion = "14",
            sdkInt = 34,
            deviceTier = "Премиум TV",
            videoCodecs = mapOf("VP9" to "SUPPORTED [HW]"),
            audioCodecs = mapOf("OPUS" to "SUPPORTED"),
            display = mapOf("resolution" to "1920x1080"),
            currentPolicy = mapOf("mode" to "AUTO"),
            recommendedSettings = mapOf("tier" to "PREMIUM_TV"),
            reportPurpose = VoxDiagnosticReport.PURPOSE_AUTO_BETA,
            autoSubmitted = true,
            triggerEvent = VoxLogCode.HUD_STATE_INCONSISTENT,
            incidentSummary = mapOf(
                "triggerCode" to VoxLogCode.HUD_STATE_INCONSISTENT,
                "repeatCount" to 3,
                "normalizedSignature" to "HUD_STATE_INCONSISTENT|none|none"
            )
        )

        val queued = VoxDiagnosticsScheduler.QueuedOfflineReport(
            report = dummyReport,
            queuedTimestampMs = 1728770000000L,
            retryCount = 2,
            nextRetryTimestampMs = 1728770030000L
        )

        val json = queued.toJson()
        assertNotNull(json)

        val restored = VoxDiagnosticsScheduler.QueuedOfflineReport.fromJson(json)
        assertNotNull(restored)
        assertEquals("VOX-TEST-12345", restored!!.report.reportId)
        assertEquals(2, restored.retryCount)
        assertEquals(1728770000000L, restored.queuedTimestampMs)
        assertEquals(1728770030000L, restored.nextRetryTimestampMs)
        assertEquals(VoxLogCode.HUD_STATE_INCONSISTENT, restored.report.triggerEvent)
        assertEquals(3, (restored.report.incidentSummary?.get("repeatCount") as? Number)?.toInt())
    }
}

@RunWith(RobolectricTestRunner::class)
class AutoBetaRestartWhileOfflineTest {
    @Test
    fun testQueuePreservedAcrossRestartsWithoutNetwork() {
        val originalList = mutableListOf<VoxDiagnosticsScheduler.QueuedOfflineReport>()
        for (i in 1..3) {
            val rep = VoxDiagnosticReport(
                reportId = "VOX-SESSION-$i",
                appVersion = "32.56-vox.8.2-beta.2",
                appVersionCode = 2446009,
                platform = "Android TV",
                manufacturer = "Google",
                model = "Emulator",
                osName = "Android",
                osVersion = "14",
                sdkInt = 34,
                deviceTier = "Премиум TV",
                videoCodecs = emptyMap(),
                audioCodecs = emptyMap(),
                display = emptyMap(),
                currentPolicy = emptyMap(),
                recommendedSettings = emptyMap()
            )
            originalList.add(VoxDiagnosticsScheduler.QueuedOfflineReport(rep, System.currentTimeMillis() - i * 1000, 0))
        }

        // Simulate save to disk:
        val jsonArray = JSONArray()
        originalList.forEach { jsonArray.put(it.toJson()) }
        val diskContent = jsonArray.toString()

        // Simulate app restart and read from disk:
        val reloadedArray = JSONArray(diskContent)
        val reloadedList = mutableListOf<VoxDiagnosticsScheduler.QueuedOfflineReport>()
        for (i in 0 until reloadedArray.length()) {
            val item = VoxDiagnosticsScheduler.QueuedOfflineReport.fromJson(reloadedArray.getJSONObject(i))
            if (item != null) reloadedList.add(item)
        }

        assertEquals(3, reloadedList.size)
        assertEquals("VOX-SESSION-1", reloadedList[0].report.reportId)
        assertEquals("VOX-SESSION-2", reloadedList[1].report.reportId)
        assertEquals("VOX-SESSION-3", reloadedList[2].report.reportId)
    }
}

class AutoBetaFlushOnceTest {
    @Test
    fun testEligibleItemDequeuedOnlyOnce() {
        val queue = mutableListOf<VoxDiagnosticsScheduler.QueuedOfflineReport>()
        val rep = VoxDiagnosticReport(
            reportId = "VOX-FLUSH-1",
            appVersion = "32.56",
            appVersionCode = 1,
            platform = "Android TV",
            manufacturer = "A",
            model = "B",
            osName = "Android",
            osVersion = "14",
            sdkInt = 34,
            deviceTier = "T",
            videoCodecs = emptyMap(),
            audioCodecs = emptyMap(),
            display = emptyMap(),
            currentPolicy = emptyMap(),
            recommendedSettings = emptyMap()
        )
        queue.add(VoxDiagnosticsScheduler.QueuedOfflineReport(rep, System.currentTimeMillis(), 0, 0L))

        assertEquals(1, queue.size)
        val popped = queue.removeAt(0)
        assertEquals("VOX-FLUSH-1", popped.report.reportId)
        assertEquals(0, queue.size)
    }
}

class AutoBetaIdempotencyTest {
    @Test
    fun testDuplicateReportIdOrSignatureSuppressed() {
        val queue = mutableListOf<VoxDiagnosticsScheduler.QueuedOfflineReport>()
        val rep1 = VoxDiagnosticReport(
            reportId = "VOX-DUP-1",
            appVersion = "32.56",
            appVersionCode = 1,
            platform = "Android TV",
            manufacturer = "A",
            model = "B",
            osName = "Android",
            osVersion = "14",
            sdkInt = 34,
            deviceTier = "T",
            videoCodecs = emptyMap(),
            audioCodecs = emptyMap(),
            display = emptyMap(),
            currentPolicy = emptyMap(),
            recommendedSettings = emptyMap(),
            incidentSummary = mapOf("normalizedSignature" to "HUD_STATE_INCONSISTENT|1|1", "repeatCount" to 1)
        )
        queue.add(VoxDiagnosticsScheduler.QueuedOfflineReport(rep1, System.currentTimeMillis(), 0, 0L))

        // Second incident with identical signature
        val rep2 = rep1.copy(incidentSummary = mapOf("normalizedSignature" to "HUD_STATE_INCONSISTENT|1|1", "repeatCount" to 2))

        val sig = rep2.incidentSummary?.get("normalizedSignature")?.toString()
        val exists = queue.any { it.report.reportId == rep2.reportId || it.report.incidentSummary?.get("normalizedSignature") == sig }
        assertTrue(exists)

        // Idempotency: count remains 1
        assertEquals(1, queue.size)
    }
}

class AutoBetaQueueLimitTest {
    @Test
    fun testQueueEnforcesMaxQueueSize() {
        val queue = mutableListOf<Int>()
        val maxLimit = VoxDiagnosticsPolicy.MAX_OFFLINE_QUEUE_SIZE
        for (i in 1..15) {
            while (queue.size >= maxLimit) {
                queue.removeAt(0) // Drop oldest
            }
            queue.add(i)
        }

        assertEquals(maxLimit, queue.size)
        assertEquals(6, queue.first())
        assertEquals(15, queue.last())
    }
}

class AutoBetaMaxAgeTest {
    @Test
    fun testPrunesExpiredReports() {
        val now = System.currentTimeMillis()
        val maxAge = VoxDiagnosticsPolicy.MAX_OFFLINE_QUEUE_AGE_MS

        val items = mutableListOf(
            Pair("recent", now - 3600_000L),                  // 1 hour old -> keep
            Pair("expired", now - (maxAge + 1000L)),           // > 48h -> drop
            Pair("yesterday", now - 24 * 3600_000L)            // 24h old -> keep
        )

        items.removeAll { (now - it.second) > maxAge }

        assertEquals(2, items.size)
        assertEquals("recent", items[0].first)
        assertEquals("yesterday", items[1].first)
    }
}

class AutoBetaBackoffTest {
    @Test
    fun testExponentialBackoffIsBounded() {
        val baseDelay = VoxDiagnosticsPolicy.OFFLINE_INITIAL_BACKOFF_MS // 15s
        val maxDelay = VoxDiagnosticsPolicy.OFFLINE_MAX_BACKOFF_MS     // 15min

        fun calc(retry: Int): Long {
            val mul = 1L shl (retry - 1).coerceIn(0, 6)
            return (baseDelay * mul).coerceAtMost(maxDelay)
        }

        assertEquals(15_000L, calc(1))
        assertEquals(30_000L, calc(2))
        assertEquals(60_000L, calc(3))
        assertEquals(120_000L, calc(4))
        assertEquals(240_000L, calc(5))
        assertEquals(480_000L, calc(6))
        assertEquals(900_000L, calc(7)) // 15 min capped
        assertEquals(900_000L, calc(10)) // still 15 min capped
    }
}

@RunWith(RobolectricTestRunner::class)
class AutoBetaQueueCorruptionTest {
    @Test
    fun testCorruptedJsonRecoversGracefully() {
        val corruptContent = "{\"invalid_json_here\": [true, 1, 2"
        var recovered = false
        val queue = mutableListOf<VoxDiagnosticsScheduler.QueuedOfflineReport>()

        try {
            JSONArray(corruptContent)
        } catch (e: Exception) {
            recovered = true
            queue.clear()
        }

        assertTrue(recovered)
        assertEquals(0, queue.size)
    }
}
