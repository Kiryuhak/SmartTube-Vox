package com.liskovsoft.smartyoutubetv2.common.vox.capability

import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Локальный трекер здоровья воспроизведения (Auto Setup 2.0).
 * Агрегирует статистику последних сессий через скользящее окно (Rolling Window),
 * строго разделяет сетевые проблемы и сбои декодеров и формирует мягкие рекомендации.
 */
class VoxPlaybackHealthTracker(private val votData: VotData? = null) {

    private val observations = CopyOnWriteArrayList<VoxPlaybackSessionObservation>()
    private val maxWindowSize = 20

    init {
        loadPersistedAggregates()
    }

    @Synchronized
    private fun loadPersistedAggregates() {
        if (votData == null) return
        val rawJson = votData.playbackHealthAggregateJson
        if (rawJson.isNullOrBlank()) return

        try {
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("sessions") ?: return
            observations.clear()
            for (i in 0 until arr.length()) {
                val obs = VoxPlaybackSessionObservation.fromJson(arr.getJSONObject(i))
                if (obs != null) {
                    observations.add(obs)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading playback aggregates: ${e.message}")
        }
    }

    @Synchronized
    private fun persistAggregates() {
        if (votData == null) return
        try {
            val root = JSONObject()
            val arr = JSONArray()
            for (obs in observations) {
                arr.put(obs.toJson())
            }
            root.put("sessions", arr)
            votData.playbackHealthAggregateJson = root.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving playback aggregates: ${e.message}")
        }
    }

    /**
     * Регистрирует завершённую сессию воспроизведения в скользящее окно.
     */
    @Synchronized
    fun recordSession(observation: VoxPlaybackSessionObservation) {
        observations.add(observation)
        // Скользящее окно (вытеснение старейших при > maxWindowSize)
        while (observations.size > maxWindowSize) {
            observations.removeAt(0)
        }
        persistAggregates()
    }

    /**
     * Сбрасывает накопленную статистику воспроизведения.
     */
    @Synchronized
    fun reset() {
        observations.clear()
        votData?.clearPlaybackHealthAggregate()
    }

    /**
     * Возвращает текущее количество накопленных сессий.
     */
    fun getObservationCount(): Int = observations.size

    /**
     * Вычисляет диагностическую сводку для безопасного отчета.
     */
    fun getHealthSummary(): VoxPlaybackHealthSummary {
        val list = observations.toList()
        if (list.isEmpty()) {
            return VoxPlaybackHealthSummary(confidence = VoxPlaybackHealthConfidence.LOW)
        }

        var liveCount = 0
        var vodCount = 0
        var totalRebuffers = 0
        var totalDropped = 0
        var totalDurationMs = 0L
        var maxStableHeight = 0
        val codecSuccessMap = mutableMapOf<String, Int>()

        for (obs in list) {
            if (obs.isLive) liveCount++ else vodCount++
            totalRebuffers += obs.rebufferCount
            totalDropped += obs.droppedFrames
            totalDurationMs += obs.playbackDurationMs

            if (obs.rebufferCount == 0 && obs.decoderInitFailures == 0) {
                if (obs.selectedHeight > maxStableHeight) {
                    maxStableHeight = obs.selectedHeight
                }
                val cur = codecSuccessMap[obs.selectedCodec] ?: 0
                codecSuccessMap[obs.selectedCodec] = cur + 1
            }
        }

        val durationMinutes = if (totalDurationMs > 0) totalDurationMs / 60000f else 1f
        val durationSeconds = if (totalDurationMs > 0) totalDurationMs / 1000f else 1f

        val rebufferRate = totalRebuffers / durationMinutes
        val droppedRate = totalDropped / durationSeconds

        val confidence = when {
            totalDurationMs >= 900_000L && list.size >= 5 -> VoxPlaybackHealthConfidence.HIGH
            totalDurationMs >= 300_000L && list.size >= 3 -> VoxPlaybackHealthConfidence.MEDIUM
            else -> VoxPlaybackHealthConfidence.LOW
        }

        val bestCodec = codecSuccessMap.maxByOrNull { it.value }?.key ?: "auto"

        return VoxPlaybackHealthSummary(
            sampleCount = list.size,
            liveSampleCount = liveCount,
            vodSampleCount = vodCount,
            bestStableHeight = maxStableHeight,
            stableVideoCodec = bestCodec,
            droppedFrameRate = droppedRate,
            rebufferRate = rebufferRate,
            confidence = confidence
        )
    }

    /**
     * Вычисляет адаптивную рекомендацию Auto Setup 2.0.
     */
    fun getRecommendation(
        profile: VoxDeviceProfile,
        currentPolicy: VoxCodecPolicy,
        isManualOverride: Boolean = false
    ): VoxAutoSetupRecommendation {
        val list = observations.toList()
        val reasonCodes = mutableListOf<String>()

        // 1. Проверка минимальной выборки (минимум 3 сессии и 5 минут суммарного просмотра)
        val totalDurationMs = list.sumOf { it.playbackDurationMs }
        val sampleCount = list.size

        if (sampleCount < 3 || totalDurationMs < 300_000L) {
            reasonCodes.add(VoxAutoSetupReasonCode.INSUFFICIENT_SAMPLES)
            if (isManualOverride) {
                reasonCodes.add(VoxAutoSetupReasonCode.MANUAL_OVERRIDE)
            }
            return VoxAutoSetupRecommendation(
                recommendedResolution = currentPolicy.maxQualityHeight,
                recommendedCodec = currentPolicy.preferredVideoCodec,
                confidence = VoxPlaybackHealthConfidence.LOW,
                health = VoxPlaybackHealth.UNKNOWN,
                issueType = VoxPlaybackIssueType.NONE,
                reasonCodes = reasonCodes,
                userExplanationRu = "Накоплено недостаточно данных для персональной автонастройки (требуется от 5 минут просмотра).",
                isManualOverrideActive = isManualOverride
            )
        }

        val confidence = if (totalDurationMs >= 900_000L && sampleCount >= 5) {
            VoxPlaybackHealthConfidence.HIGH
        } else {
            VoxPlaybackHealthConfidence.MEDIUM
        }

        // 2. Анализ VOD сессий отдельно от Live сессий
        val vodSessions = list.filter { !it.isLive }
        val targetSessions = if (vodSessions.isNotEmpty()) vodSessions else list

        var decoderErrors = 0
        var totalRebuffers = 0
        var totalRebufferMs = 0L
        var totalDropped = 0
        var totalTargetDurationMs = 0L

        var sessionsAt4k = 0
        var rebuffersAt4k = 0

        for (s in targetSessions) {
            decoderErrors += s.decoderInitFailures
            totalRebuffers += s.rebufferCount
            totalRebufferMs += s.totalRebufferMs
            totalDropped += s.droppedFrames
            totalTargetDurationMs += s.playbackDurationMs

            if (s.selectedHeight >= 2160) {
                sessionsAt4k++
                rebuffersAt4k += s.rebufferCount
            }
        }

        val targetSeconds = if (totalTargetDurationMs > 0) totalTargetDurationMs / 1000f else 1f
        val droppedFps = totalDropped / targetSeconds

        // 3. Классификация типа проблемы: Network vs Decoder
        val issueType: VoxPlaybackIssueType
        if (decoderErrors > 0 || droppedFps > 3.0f) {
            issueType = VoxPlaybackIssueType.DECODER_LIMITED
            reasonCodes.add(VoxAutoSetupReasonCode.DECODER_LIMITED)
        } else if (totalRebuffers > 0) {
            issueType = VoxPlaybackIssueType.NETWORK_LIMITED
            reasonCodes.add(VoxAutoSetupReasonCode.NETWORK_LIMITED)
        } else {
            issueType = VoxPlaybackIssueType.NONE
        }

        // 4. Оценка общего уровня здоровья воспроизведения
        val health: VoxPlaybackHealth = when {
            decoderErrors > 0 || droppedFps > 5.0f || totalRebuffers >= 4 -> VoxPlaybackHealth.POOR
            totalRebuffers in 2..3 || droppedFps in 1.0f..5.0f || totalRebufferMs > 6000L -> VoxPlaybackHealth.DEGRADED
            totalRebuffers == 1 || droppedFps in 0.2f..1.0f -> VoxPlaybackHealth.GOOD
            else -> VoxPlaybackHealth.EXCELLENT
        }

        // 5. Специальный разбор DuneHD Case (экран 1080p, аппаратный декодер 4K)
        val isDuneHdCase = profile.has4kHardwareDecode() && profile.display.maxHeight in 1..1080
        val isManual4k = isManualOverride && currentPolicy.maxQualityHeight >= 2160

        if (isDuneHdCase && isManual4k) {
            reasonCodes.add(VoxAutoSetupReasonCode.MANUAL_OVERRIDE)
            if (rebuffersAt4k >= 2 || (sessionsAt4k > 0 && rebuffersAt4k > 0 && health == VoxPlaybackHealth.POOR)) {
                // Повторные буферизации при 4K -> мягко рекомендуем 1080p/Авто, НЕ переключая молча
                reasonCodes.add(VoxAutoSetupReasonCode.RUNTIME_4K_REBUFFER)
                return VoxAutoSetupRecommendation(
                    recommendedResolution = 1080,
                    recommendedCodec = currentPolicy.preferredVideoCodec,
                    confidence = confidence,
                    health = health,
                    issueType = issueType,
                    reasonCodes = reasonCodes,
                    userExplanationRu = "При 2160p были обнаружены повторные буферизации. Рекомендуется 1080p для более стабильного воспроизведения.",
                    isManualOverrideActive = true
                )
            } else {
                // Воспроизведение 4K стабильно -> сохраняем ручной выбор пользователя!
                reasonCodes.add(VoxAutoSetupReasonCode.RUNTIME_4K_STABLE)
                return VoxAutoSetupRecommendation(
                    recommendedResolution = 2160,
                    recommendedCodec = currentPolicy.preferredVideoCodec,
                    confidence = confidence,
                    health = VoxPlaybackHealth.EXCELLENT,
                    issueType = VoxPlaybackIssueType.NONE,
                    reasonCodes = reasonCodes,
                    userExplanationRu = "Аппаратное декодирование 4K с даунскейлом работает стабильно без задержек.",
                    isManualOverrideActive = true
                )
            }
        }

        // 6. Проверка ошибок конкретных кодеков (AV1 -> VP9)
        val av1Errors = targetSessions.count { it.selectedCodec.equals("av1", true) && it.decoderInitFailures > 0 }
        val recommendedCodec = if (av1Errors > 0) {
            reasonCodes.add(VoxAutoSetupReasonCode.AV1_DECODER_ERROR)
            VoxVideoCodecPreference.VP9
        } else {
            val vp9Stable = targetSessions.any { it.selectedCodec.equals("vp9", true) && it.decoderInitFailures == 0 && it.rebufferCount == 0 }
            if (vp9Stable) {
                reasonCodes.add(VoxAutoSetupReasonCode.VP9_STABLE)
            }
            currentPolicy.preferredVideoCodec
        }

        // 7. Определение рекомендуемого разрешения
        val recommendedRes: Int
        if (issueType == VoxPlaybackIssueType.DECODER_LIMITED && currentPolicy.maxQualityHeight > 1080) {
            recommendedRes = 1080
        } else if (health == VoxPlaybackHealth.EXCELLENT && profile.has4kHardwareDecode() && profile.display.maxHeight >= 2160) {
            reasonCodes.add(VoxAutoSetupReasonCode.RUNTIME_4K_STABLE)
            recommendedRes = 2160
        } else {
            recommendedRes = if (currentPolicy.maxQualityHeight > 0) currentPolicy.maxQualityHeight else 1080
        }

        if (isManualOverride) {
            reasonCodes.add(VoxAutoSetupReasonCode.MANUAL_OVERRIDE)
        }

        val explanation = when (health) {
            VoxPlaybackHealth.EXCELLENT -> "Воспроизведение работает отлично, видеодекодер и сеть полностью справляются."
            VoxPlaybackHealth.GOOD -> "Воспроизведение стабильное с минимальными единичными задержками."
            VoxPlaybackHealth.DEGRADED -> if (issueType == VoxPlaybackIssueType.NETWORK_LIMITED) {
                "Обнаружены задержки буферизации из-за скорости сети. Рекомендуется режим «Автоматически»."
            } else {
                "Обнаружен пропуск кадров видеодекодером. Рекомендуется снизить максимальное разрешение."
            }
            VoxPlaybackHealth.POOR -> "Обнаружены частые сбои воспроизведения. Рекомендуется автоматический выбор параметров."
            VoxPlaybackHealth.UNKNOWN -> "Сбор статистики продолжается."
        }

        return VoxAutoSetupRecommendation(
            recommendedResolution = recommendedRes,
            recommendedCodec = recommendedCodec,
            confidence = confidence,
            health = health,
            issueType = issueType,
            reasonCodes = reasonCodes,
            userExplanationRu = explanation,
            isManualOverrideActive = isManualOverride
        )
    }

    companion object {
        private const val TAG = "VoxPlaybackHealthTracker"
    }
}
