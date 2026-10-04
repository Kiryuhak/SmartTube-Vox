package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Статус фрагмента перевода прямого эфира.
 */
enum class VoxLiveChunkStatus {
    PENDING,
    TRANSLATING,
    READY,
    PLAYED,
    FAILED
}

/**
 * Структурированный сегмент звука прямого эфира.
 */
data class VoxLiveTranslationChunk(
    val sequenceNumber: Long,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val translatedAudioUri: String? = null,
    val status: VoxLiveChunkStatus = VoxLiveChunkStatus.PENDING,
    val durationMs: Long = sourceEndMs - sourceStartMs
) {
    init {
        require(sourceEndMs >= sourceStartMs) { "sourceEndMs must be >= sourceStartMs" }
    }
}
