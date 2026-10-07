package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.json.JSONObject

/**
 * Режим маршрутизации аудиопотоков при работе вторичного плеера (Section 56).
 */
enum class VoxAudioRoutingMode(val id: String, val titleRu: String) {
    /**
     * По умолчанию: играет только оригинальный звук ExoPlayer, вторичный плеер замьючен (vol = 0).
     */
    ORIGINAL_ONLY("original_only", "Только оригинальный звук"),

    /**
     * Экспериментальный тестовый режим: вторичный плеер на 100%, оригинальный звук замьючен.
     */
    SECONDARY_ONLY("secondary_only", "Только вторичный поток перевода"),

    /**
     * Тестовый режим отладки микширования: вторичный плеер на 100%, оригинальный звук приглушен (20%).
     */
    MIX_DEBUG("mix_debug", "Микширование: оригинальный приглушен + вторичный");
}

/**
 * Действия контроллера синхронизации по PTS (Section 58).
 */
enum class VoxLivePtsSyncAction(val id: String, val descriptionRu: String) {
    /**
     * Рассинхронизация в пределах нормы (|drift| <= 150ms).
     */
    PLAY("play", "Воспроизведение в синхроне"),

    /**
     * Вторичный звук опережает мастер-плеер (drift > 150ms): ожидание/приостановка.
     */
    WAIT("wait", "Ожидание отстающего видео"),

    /**
     * Вторичный звук отстает от мастер-плеера (-500ms <= drift < -150ms): пропуск отстающих фреймов.
     */
    DROP("drop", "Пропуск отстающих аудиофреймов"),

    /**
     * Критическая рассинхронизация (|drift| > 500ms): жесткий сброс привязки времени.
     */
    REANCHOR("reanchor", "Сброс привязки таймлайна (Re-anchor)"),

    /**
     * Опустошение буфера или ошибка вторичного плеера: бесшовный откат на оригинальный звук.
     */
    FALLBACK("fallback", "Бесшовный откат на оригинальный звук");
}

/**
 * Решение контроллера синхронизации по PTS.
 */
data class VoxLivePtsSyncDecision(
    val action: VoxLivePtsSyncAction,
    val driftMs: Long,
    val reason: String
)

/**
 * Отдельный аудиофрейм, захваченный из декодера ExoPlayer.
 */
data class VoxLiveCapturedBuffer(
    val data: ByteArray,
    val ptsUs: Long,
    val durationUs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int,
    val generation: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VoxLiveCapturedBuffer
        if (!data.contentEquals(other.data)) return false
        if (ptsUs != other.ptsUs) return false
        if (durationUs != other.durationUs) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (encoding != other.encoding) return false
        if (generation != other.generation) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + ptsUs.hashCode()
        result = 31 * result + durationUs.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + encoding
        result = 31 * result + generation.hashCode()
        return result
    }
}

/**
 * Склеенный аудиофрагмент целевой длительности (~2-3 сек) для отправки в шлюз или на перевод.
 */
data class VoxLiveAudioFragment(
    val sequence: Long,
    val generation: Long,
    val startPtsUs: Long,
    val endPtsUs: Long,
    val durationUs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int,
    val data: ByteArray,
    val frameCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VoxLiveAudioFragment
        if (sequence != other.sequence) return false
        if (generation != other.generation) return false
        if (startPtsUs != other.startPtsUs) return false
        if (endPtsUs != other.endPtsUs) return false
        if (durationUs != other.durationUs) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (encoding != other.encoding) return false
        if (!data.contentEquals(other.data)) return false
        if (frameCount != other.frameCount) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sequence.hashCode()
        result = 31 * result + generation.hashCode()
        result = 31 * result + startPtsUs.hashCode()
        result = 31 * result + endPtsUs.hashCode()
        result = 31 * result + durationUs.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + encoding
        result = 31 * result + data.contentHashCode()
        result = 31 * result + frameCount
        return result
    }
}

/**
 * Слушатель событий точки захвата живого аудио (Audio Tap).
 */
interface VoxLiveAudioTapListener {
    fun onCapturedBuffer(buffer: VoxLiveCapturedBuffer)
    fun onDiscontinuity(oldGeneration: Long, newGeneration: Long, newBasePtsUs: Long)
}

/**
 * Диагностика синхронизации и захвата живого аудио (Section 59).
 * Zero-Telemetry: не содержит аудиоданных, заголовков или персональной информации.
 */
data class VoxLiveAudioSyncDiagnostics(
    val captureActive: Boolean = false,
    val routingMode: VoxAudioRoutingMode = VoxAudioRoutingMode.ORIGINAL_ONLY,
    val generation: Long = 1L,
    val lastPtsUs: Long = 0L,
    val currentDriftMs: Long = 0L,
    val maxDriftMs: Long = 0L,
    val underrunCount: Int = 0,
    val reanchorCount: Int = 0,
    val droppedFrames: Int = 0,
    val totalCapturedFragments: Long = 0L,
    val totalCapturedBytes: Long = 0L,
    val fallbackActive: Boolean = false
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "captureActive" to captureActive,
            "routingMode" to routingMode.id,
            "generation" to generation,
            "lastPtsUs" to lastPtsUs,
            "currentDriftMs" to currentDriftMs,
            "maxDriftMs" to maxDriftMs,
            "underrunCount" to underrunCount,
            "reanchorCount" to reanchorCount,
            "droppedFrames" to droppedFrames,
            "totalCapturedFragments" to totalCapturedFragments,
            "totalCapturedBytes" to totalCapturedBytes,
            "fallbackActive" to fallbackActive
        )
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        for ((key, value) in toMap()) {
            json.put(key, value)
        }
        return json
    }
}
