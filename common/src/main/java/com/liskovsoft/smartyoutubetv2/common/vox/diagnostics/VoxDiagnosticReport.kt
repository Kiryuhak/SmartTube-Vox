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
    val incidentSummary: Map<String, Any>? = null
) {
    companion object {
        const val SCHEMA_V1 = "vox-diagnostic-report-v1"
        const val SCHEMA_V2 = "vox-diagnostic-report-v2"
        const val SCHEMA_V3 = "vox-diagnostic-report-v3"
        const val MAX_REPORT_EVENTS = 50

        const val PURPOSE_AUTO_BETA = "AUTO_BETA"
        const val PURPOSE_USER_MANUAL = "USER_MANUAL"
        const val PURPOSE_EMULATOR_TEST = "EMULATOR_TEST"

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
                com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLivePlaybackMonitor.getMetricsSummary().toMap()
            } catch (e: Exception) {
                null
            }

            val downloadDiag = try {
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context).getDiagnosticsSummary()
            } catch (e: Exception) {
                null
            }

            val playbackSummary = try {
                VoxCompatibilityManager.instance(context).getPlaybackHealthTracker().getHealthSummary().toMap()
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

            return VoxDiagnosticReport(
                schema = SCHEMA_V3,
                reportId = reportId,
                timestamp = System.currentTimeMillis(),
                appVersion = appVer,
                appVersionCode = if (appCode > 0) appCode else 2446009,
                platform = profile.platform.displayName,
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
                incidentSummary = incidentSummary
            )
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
