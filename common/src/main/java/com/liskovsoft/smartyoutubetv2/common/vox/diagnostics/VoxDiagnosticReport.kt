package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import android.os.Build
import com.liskovsoft.smartyoutubetv2.common.vox.capability.TriStateCapability
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityManager
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityRiskEvaluator
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Неизменяемая модель диагностического отчёта SmartTube VOX (схема: vox-diagnostic-report-v2).
 * Безопасность: гарантированно не содержит персональных данных (PII), токенов авторизации,
 * паролей, cookies или конфиденциальных идентификаторов.
 */
data class VoxDiagnosticReport(
    val schema: String = SCHEMA_V3,
    val reportId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val appVersion: String,
    val appVersionCode: Int,
    val platform: String,
    val manufacturer: String,
    val model: String,
    val osName: String,
    val osVersion: String,
    val sdkInt: Int,
    val deviceTier: String,
    val videoCodecs: Map<String, String>,
    val audioCodecs: Map<String, String>,
    val display: Map<String, Any>,
    val currentPolicy: Map<String, Any>,
    val recommendedSettings: Map<String, Any>,
    val riskWarning: String? = null,
    val playbackStats: Map<String, Any>? = null,
    val livePlayback: Map<String, Any>? = null,
    val downloadDiagnostics: Map<String, Any>? = null,
    val translationDiagnostics: Map<String, Any>? = null,
    val startupProfile: Map<String, Any>? = null,
    val safeRecentEvents: List<VoxLogEvent> = emptyList(),
    val reportPurpose: String = PURPOSE_USER_MANUAL,
    val autoSubmitted: Boolean = false,
    val triggerEvent: String? = null,
    val channel: String? = null,
    val aggregationWindowMs: Long = 0L,
    val incidentSummary: Map<String, Any>? = null,
    val errorCategory: String? = null,
    val errorSignature: String? = null,
    val isSynthetic: Boolean = false
) {
    fun withCanonicalClassification(): VoxDiagnosticReport {
        val effectiveTrigger = triggerEvent ?: incidentSummary?.get("triggerCode")?.toString()
        val prominent = if (!effectiveTrigger.isNullOrBlank()) {
            safeRecentEvents.firstOrNull { it.code.equals(effectiveTrigger, ignoreCase = true) }
        } else {
            VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(safeRecentEvents)
        }
        val primaryCode = effectiveTrigger?.takeIf { it.isNotBlank() } ?: prominent?.code
        val cat = prominent?.category?.name ?: VoxDiagnosticsPolicy.inferCategoryFromCode(primaryCode) ?: errorCategory
        val sig = VoxDiagnosticsPolicy.buildCanonicalErrorSignature(
            errorCategory = cat,
            primaryCode = primaryCode,
            platform = platform,
            sdkInt = sdkInt,
            reportPurpose = reportPurpose
        )
        return copy(
            errorCategory = cat,
            errorSignature = sig
        )
    }

    companion object {
        const val SCHEMA_V1 = "vox-diagnostic-report-v1"
        const val SCHEMA_V2 = "vox-diagnostic-report-v2"
        const val SCHEMA_V3 = "vox-diagnostic-report-v3"
        const val MAX_REPORT_EVENTS = 50

        const val PURPOSE_AUTO_BETA = "AUTO_BETA"
        const val PURPOSE_USER_MANUAL = "USER_MANUAL"
        const val PURPOSE_EMULATOR_TEST = "EMULATOR_TEST"
        const val PURPOSE_TEST = "TEST"
        const val PURPOSE_UNIT_TEST = "UNIT_TEST"

        @JvmStatic
        fun wrapJsonValue(value: Any?): Any {
            return when (value) {
                null -> JSONObject.NULL
                is Map<*, *> -> {
                    val obj = JSONObject()
                    for ((k, v) in value) {
                        if (k != null) {
                            obj.put(k.toString(), wrapJsonValue(v))
                        }
                    }
                    obj
                }
                is Collection<*> -> {
                    val arr = JSONArray()
                    for (item in value) {
                        arr.put(wrapJsonValue(item))
                    }
                    arr
                }
                is Array<*> -> {
                    val arr = JSONArray()
                    for (item in value) {
                        arr.put(wrapJsonValue(item))
                    }
                    arr
                }
                is Number, is Boolean, is String, is JSONObject, is JSONArray -> value
                else -> value.toString()
            }
        }

        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            reportId: String? = null,
            includeEvents: Boolean = true,
            reportPurpose: String = PURPOSE_USER_MANUAL,
            autoSubmitted: Boolean = false,
            triggerEvent: String? = null,
            aggregationWindowMs: Long = 0L,
            incidentSummary: Map<String, Any>? = null
        ): VoxDiagnosticReport {
            val manager = VoxCompatibilityManager.instance(context)
            val profile = manager.getDeviceProfile(false)
            val policy = manager.getCodecPolicy()
            val recommended = manager.getRecommendedSettings(false)
            val risk = VoxCompatibilityRiskEvaluator.evaluate(policy, recommended, profile)

            val vCodecs = mutableMapOf<String, String>()
            for ((key, cap) in profile.videoCodecs) {
                val hw = if (cap.hardwareAccelerated) " [HW]" else ""
                val res = if (cap.maxWidth > 0 && cap.maxHeight > 0) " (${cap.maxWidth}x${cap.maxHeight}@${cap.maxFps}fps)" else ""
                vCodecs[key.uppercase()] = "${cap.capability.labelRu}$hw$res"
            }

            val aCodecs = mutableMapOf<String, String>()
            for ((key, cap) in profile.audioCodecs) {
                val dec = cap.decodeCapability.labelRu
                val pt = cap.passthroughCapability.labelRu
                val note = if (cap.decodeCapability != TriStateCapability.SUPPORTED && cap.passthroughCapability == TriStateCapability.SUPPORTED) {
                    " (Только passthrough)"
                } else ""
                aCodecs[key.uppercase()] = "Декод: $dec, Passthrough: $pt$note"
            }

            val displayMap = mapOf<String, Any>(
                "resolution" to "${profile.display.maxWidth}x${profile.display.maxHeight}",
                "refreshRateHz" to profile.display.maxFps,
                "hdr10" to profile.display.hdr10.name,
                "hlg" to profile.display.hlg.name,
                "hdr10Plus" to profile.display.hdr10Plus.name,
                "dolbyVision" to profile.display.dolbyVision.name
            )

            val currentPolicyMap = mapOf<String, Any>(
                "mode" to policy.mode.name,
                "maxQualityHeight" to policy.maxQualityHeight,
                "preferredVideoCodec" to policy.preferredVideoCodec.name,
                "preferredAudioCodec" to policy.preferredAudioCodec.name,
                "passthroughEnabled" to policy.passthroughEnabled
            )

            val recommendedMap = mapOf<String, Any>(
                "tier" to recommended.tier.name,
                "mode" to recommended.policyMode.name,
                "maxQualityHeight" to recommended.maxQualityHeight,
                "preferredVideoCodec" to recommended.preferredVideoCodec.name,
                "preferredAudioCodec" to recommended.preferredAudioCodec.name,
                "passthroughEnabled" to recommended.passthroughEnabled,
                "reasonCodes" to JSONArray(recommended.reasonCodes)
            )

            val pInfo = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (e: Exception) {
                null
            }
            val appVer = pInfo?.versionName ?: "32.56-vox.8-dev"
            val appCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo?.longVersionCode?.toInt() ?: 2446009
            } else {
                @Suppress("DEPRECATION")
                pInfo?.versionCode ?: 2446009
            }

            val recentEvents = if (includeEvents) {
                try {
                    VoxLogStore.instance(context).getRecentEvents(MAX_REPORT_EVENTS)
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val liveMetrics = try {
                if (com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLivePlaybackMonitor.hasActiveOrRecentLiveSession()) {
                    com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLivePlaybackMonitor.getMetricsSummary().toMap()
                } else null
            } catch (e: Exception) {
                null
            }

            val downloadDiag = try {
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context).getDiagnosticsSummary()
            } catch (e: Exception) {
                null
            }

            val playbackSummary = try {
                val tracker = VoxCompatibilityManager.instance(context).getPlaybackHealthTracker()
                if (tracker.getObservationCount() > 0) {
                    tracker.getHealthSummary().toMap()
                } else null
            } catch (e: Exception) {
                null
            }

            val transDiag = try {
                com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceTranslateController.instance()?.getTranslationDiagnosticsSummary()
            } catch (e: Exception) {
                null
            }

            val startupSummary = try {
                com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.getCurrentProfileSummary()
            } catch (e: Exception) {
                null
            }

            val stableReportId = reportId ?: ("VOX-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).uppercase(Locale.ROOT))
            val safePlatform = if (profile.platform == com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxPlatform.UNKNOWN) {
                "UNKNOWN_PLATFORM"
            } else {
                profile.platform.displayName
            }
            val syntheticFlag = (incidentSummary?.get("isSynthetic") == true) ||
                VoxDiagnosticsClient.isRunningInUnitTest() ||
                profile.model.contains("robolectric", ignoreCase = true)

            return VoxDiagnosticReport(
                schema = SCHEMA_V3,
                reportId = stableReportId,
                timestamp = System.currentTimeMillis(),
                appVersion = appVer,
                appVersionCode = if (appCode > 0) appCode else 2446009,
                platform = safePlatform,
                manufacturer = profile.manufacturer,
                model = profile.model,
                osName = profile.osName,
                osVersion = profile.osVersion,
                sdkInt = Build.VERSION.SDK_INT,
                deviceTier = recommended.tier.displayNameRu,
                videoCodecs = vCodecs,
                audioCodecs = aCodecs,
                display = displayMap,
                currentPolicy = currentPolicyMap,
                recommendedSettings = recommendedMap,
                riskWarning = risk?.messageRu,
                playbackStats = playbackSummary,
                livePlayback = liveMetrics,
                downloadDiagnostics = downloadDiag,
                translationDiagnostics = transDiag,
                startupProfile = startupSummary,
                safeRecentEvents = recentEvents,
                reportPurpose = reportPurpose,
                autoSubmitted = autoSubmitted,
                triggerEvent = triggerEvent,
                channel = com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannelManager.instance(context).getBuildChannel().id,
                aggregationWindowMs = aggregationWindowMs,
                incidentSummary = incidentSummary,
                isSynthetic = syntheticFlag
            ).withCanonicalClassification()
        }

        @JvmStatic
        fun jsonToMap(obj: JSONObject?): Map<String, Any>? {
            if (obj == null) return null
            val map = mutableMapOf<String, Any>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.opt(key)
                if (value != null && value != JSONObject.NULL) {
                    map[key] = when (value) {
                        is JSONObject -> jsonToMap(value) ?: emptyMap<String, Any>()
                        is JSONArray -> jsonToList(value)
                        else -> value
                    }
                }
            }
            return map
        }

        @JvmStatic
        fun jsonToList(arr: JSONArray?): List<Any> {
            if (arr == null) return emptyList()
            val list = mutableListOf<Any>()
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                if (item != null && item != JSONObject.NULL) {
                    list.add(when (item) {
                        is JSONObject -> jsonToMap(item) ?: emptyMap<String, Any>()
                        is JSONArray -> jsonToList(item)
                        else -> item
                    })
                }
            }
            return list
        }

        @JvmStatic
        fun fromJson(jsonStr: String): VoxDiagnosticReport? {
            return try {
                fromJson(JSONObject(jsonStr))
            } catch (e: Exception) {
                null
            }
        }

        @JvmStatic
        fun fromJson(root: JSONObject): VoxDiagnosticReport? {
            return try {
                val schema = root.optString("schema", SCHEMA_V3)
                val reportId = if (root.has("reportId") && !root.isNull("reportId")) root.optString("reportId") else null
                val timestamp = root.optLong("timestamp", System.currentTimeMillis())
                val appVersion = root.optString("appVersion", "32.56-vox.8")
                val appVersionCode = root.optInt("appVersionCode", 2446009)
                val platform = root.optString("platform", "Android TV")
                val manufacturer = root.optString("manufacturer", "unknown")
                val model = root.optString("model", "unknown")
                val osName = root.optString("osName", "Android")
                val osVersion = root.optString("osVersion", "")
                val sdkInt = root.optInt("sdkInt", 0)
                val deviceTier = root.optString("deviceTier", "UNKNOWN")

                val vCodecs = mutableMapOf<String, String>()
                root.optJSONObject("videoCodecs")?.let { vo ->
                    val it = vo.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        vCodecs[k] = vo.optString(k, "")
                    }
                }

                val aCodecs = mutableMapOf<String, String>()
                root.optJSONObject("audioCodecs")?.let { ao ->
                    val it = ao.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        aCodecs[k] = ao.optString(k, "")
                    }
                }

                val display = jsonToMap(root.optJSONObject("display")) ?: emptyMap()
                val currentPolicy = jsonToMap(root.optJSONObject("currentPolicy")) ?: emptyMap()
                val recommendedSettings = jsonToMap(root.optJSONObject("recommendedSettings")) ?: emptyMap()
                val riskWarning = if (root.has("riskWarning") && !root.isNull("riskWarning")) root.optString("riskWarning") else null

                val playbackStats = jsonToMap(root.optJSONObject("playbackStats"))
                val livePlayback = jsonToMap(root.optJSONObject("livePlayback"))
                val downloadDiagnostics = jsonToMap(root.optJSONObject("downloadDiagnostics"))
                val translationDiagnostics = jsonToMap(root.optJSONObject("translationDiagnostics"))
                val startupProfile = jsonToMap(root.optJSONObject("startupProfile"))

                val events = mutableListOf<VoxLogEvent>()
                root.optJSONArray("safeRecentEvents")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val evObj = arr.optJSONObject(i)
                        if (evObj != null) {
                            val ev = VoxLogEvent.fromJson(evObj)
                            if (ev != null) events.add(ev)
                        }
                    }
                }

                val reportPurpose = root.optString("reportPurpose", PURPOSE_USER_MANUAL)
                val autoSubmitted = root.optBoolean("autoSubmitted", false)
                val triggerEvent = if (root.has("triggerEvent") && !root.isNull("triggerEvent")) root.optString("triggerEvent") else null
                val channel = if (root.has("channel") && !root.isNull("channel")) root.optString("channel") else null
                val aggregationWindowMs = root.optLong("aggregationWindowMs", 0L)
                val incidentSummary = jsonToMap(root.optJSONObject("incidentSummary"))
                val errorCategory = if (root.has("errorCategory") && !root.isNull("errorCategory")) root.optString("errorCategory") else null
                val errorSignature = if (root.has("errorSignature") && !root.isNull("errorSignature")) root.optString("errorSignature") else null
                val isSynthetic = root.optBoolean("isSynthetic", false)

                VoxDiagnosticReport(
                    schema = schema,
                    reportId = reportId,
                    timestamp = timestamp,
                    appVersion = appVersion,
                    appVersionCode = appVersionCode,
                    platform = platform,
                    manufacturer = manufacturer,
                    model = model,
                    osName = osName,
                    osVersion = osVersion,
                    sdkInt = sdkInt,
                    deviceTier = deviceTier,
                    videoCodecs = vCodecs,
                    audioCodecs = aCodecs,
                    display = display,
                    currentPolicy = currentPolicy,
                    recommendedSettings = recommendedSettings,
                    riskWarning = riskWarning,
                    playbackStats = playbackStats,
                    livePlayback = livePlayback,
                    downloadDiagnostics = downloadDiagnostics,
                    translationDiagnostics = translationDiagnostics,
                    startupProfile = startupProfile,
                    safeRecentEvents = events,
                    reportPurpose = reportPurpose,
                    autoSubmitted = autoSubmitted,
                    triggerEvent = triggerEvent,
                    channel = channel,
                    aggregationWindowMs = aggregationWindowMs,
                    incidentSummary = incidentSummary,
                    errorCategory = errorCategory,
                    errorSignature = errorSignature,
                    isSynthetic = isSynthetic
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("schema", schema)
        if (reportId != null) root.put("reportId", reportId)
        root.put("timestamp", timestamp)
        root.put("reportPurpose", reportPurpose)
        root.put("autoSubmitted", autoSubmitted)
        if (triggerEvent != null) root.put("triggerEvent", triggerEvent)
        if (errorCategory != null) root.put("errorCategory", errorCategory)
        if (!errorSignature.isNullOrEmpty()) root.put("errorSignature", errorSignature)
        if (isSynthetic) root.put("isSynthetic", true)
        if (channel != null) root.put("channel", channel)
        if (aggregationWindowMs > 0) root.put("aggregationWindowMs", aggregationWindowMs)
        if (incidentSummary != null && incidentSummary.isNotEmpty()) {
            root.put("incidentSummary", wrapJsonValue(incidentSummary))
        }
        root.put("appVersion", appVersion)
        root.put("appVersionCode", appVersionCode)
        root.put("platform", platform)
        root.put("manufacturer", manufacturer)
        root.put("model", model)
        root.put("osName", osName)
        root.put("osVersion", osVersion)
        root.put("sdkInt", sdkInt)
        root.put("deviceTier", deviceTier)

        val vObj = JSONObject()
        videoCodecs.forEach { (k, v) -> vObj.put(k, v) }
        root.put("videoCodecs", vObj)

        val aObj = JSONObject()
        audioCodecs.forEach { (k, v) -> aObj.put(k, v) }
        root.put("audioCodecs", aObj)

        root.put("display", wrapJsonValue(display))
        root.put("currentPolicy", wrapJsonValue(currentPolicy))
        root.put("recommendedSettings", wrapJsonValue(recommendedSettings))

        if (riskWarning != null) {
            root.put("riskWarning", riskWarning)
        }
        if (playbackStats != null) {
            root.put("playbackStats", wrapJsonValue(playbackStats))
        }
        if (livePlayback != null) {
            root.put("livePlayback", wrapJsonValue(livePlayback))
        }
        if (downloadDiagnostics != null && downloadDiagnostics.isNotEmpty()) {
            root.put("downloadDiagnostics", wrapJsonValue(downloadDiagnostics))
        }
        if (translationDiagnostics != null && translationDiagnostics.isNotEmpty()) {
            root.put("translationDiagnostics", wrapJsonValue(translationDiagnostics))
        }
        if (startupProfile != null && startupProfile.isNotEmpty()) {
            root.put("startupProfile", wrapJsonValue(startupProfile))
        }

        if (safeRecentEvents.isNotEmpty()) {
            val evArray = JSONArray()
            for (ev in safeRecentEvents) {
                evArray.put(ev.toJson())
            }
            root.put("safeRecentEvents", evArray)
        }

        return root.toString(2)
    }

    fun toFormattedText(): String {
        val sb = StringBuilder()
        sb.append("=== SmartTube VOX — Диагностический отчёт ===\n\n")
        if (!reportId.isNullOrBlank()) {
            sb.append("ID отчёта: ").append(reportId).append("\n")
        }
        sb.append("Схема отчёта: ").append(schema).append("\n")
        sb.append("Назначение: ").append(reportPurpose).append("\n")
        if (channel != null) sb.append("Канал сборки: ").append(channel).append("\n")
        if (triggerEvent != null) sb.append("Событие-триггер: ").append(triggerEvent).append("\n")
        sb.append("Приложение: ").append(appVersion).append(" (").append(appVersionCode).append(")\n")
        sb.append("Платформа: ").append(platform).append("\n")
        sb.append("Производитель: ").append(manufacturer).append("\n")
        sb.append("Модель: ").append(model).append("\n")
        sb.append("Система: ").append(osName).append(" ").append(osVersion).append(" (API ").append(sdkInt).append(")\n")
        sb.append("Уровень устройства: ").append(deviceTier).append("\n\n")

        sb.append("--- ВИДЕОДЕКОДЕРЫ ---\n")
        for ((k, v) in videoCodecs) {
            sb.append(k).append(": ").append(v).append("\n")
        }
        sb.append("\n")

        sb.append("--- АУДИОДЕКОДЕРЫ И PASSTHROUGH ---\n")
        for ((k, v) in audioCodecs) {
            sb.append(k).append(": ").append(v).append("\n")
        }
        sb.append("\n")

        sb.append("--- ЭКРАН И HDR ---\n")
        display.forEach { (k, v) ->
            sb.append(k).append(": ").append(v).append("\n")
        }
        sb.append("\n")

        sb.append("--- РЕКОМЕНДУЕМЫЕ НАСТРОЙКИ VOX ---\n")
        recommendedSettings.forEach { (k, v) ->
            sb.append(k).append(": ").append(v).append("\n")
        }
        sb.append("\n")

        sb.append("--- АКТИВНЫЕ НАСТРОЙКИ ---\n")
        currentPolicy.forEach { (k, v) ->
            sb.append(k).append(": ").append(v).append("\n")
        }

        if (riskWarning != null) {
            sb.append("\n⚠️ ВНИМАНИЕ: ").append(riskWarning).append("\n")
        } else {
            sb.append("\n✓ Параметры полностью согласованы с возможностями устройства.\n")
        }

        if (incidentSummary != null && incidentSummary.isNotEmpty()) {
            sb.append("\n--- СВОДКА ИНЦИДЕНТА ---\n")
            incidentSummary.forEach { (k, v) ->
                sb.append(k).append(": ").append(v).append("\n")
            }
        }

        if (downloadDiagnostics != null && downloadDiagnostics.isNotEmpty()) {
            sb.append("\n--- ДИАГНОСТИКА СКАЧИВАНИЯ ---\n")
            downloadDiagnostics.forEach { (k, v) ->
                sb.append(k).append(": ").append(v).append("\n")
            }
        }

        if (translationDiagnostics != null && translationDiagnostics.isNotEmpty()) {
            sb.append("\n--- ДИАГНОСТИКА ПЕРЕВОДА ---\n")
            translationDiagnostics.forEach { (k, v) ->
                sb.append(k).append(": ").append(v).append("\n")
            }
        }

        if (startupProfile != null && startupProfile.isNotEmpty()) {
            sb.append("\n--- ПРОФИЛЬ ЗАПУСКА ВИДЕО ---\n")
            startupProfile.forEach { (k, v) ->
                sb.append(k).append(": ").append(v).append("\n")
            }
        }

        if (safeRecentEvents.isNotEmpty()) {
            sb.append("\n--- ПОСЛЕДНИЕ СОБЫТИЯ ЖУРНАЛА (").append(safeRecentEvents.size).append(") ---\n")
            val df = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            for (ev in safeRecentEvents.take(15)) {
                val t = df.format(Date(ev.timestamp))
                sb.append("• [").append(t).append("] [")
                    .append(ev.category.name).append("] ")
                    .append(ev.code).append(": ")
                    .append(ev.message).append("\n")
            }
        }

        return sb.toString()
    }
}
