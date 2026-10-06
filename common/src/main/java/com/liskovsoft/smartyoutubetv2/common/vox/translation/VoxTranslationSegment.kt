package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Статус жизненного цикла сегмента перевода.
 */
enum class VoxSegmentState {
    PENDING,
    QUEUED,
    TRANSLATING,
    READY,
    PLAYED,
    FAILED,
    DISCARDED
}

/**
 * Модель аудио-сегмента для потокового или модульного перевода.
 * Не сохраняет и не логирует защищенные токены или полные подписанные URL.
 */
data class VoxTranslationSegment(
    val segmentId: String,
    val sequence: Long,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val sourceDescriptor: String,
    val generationId: Long,
    var state: VoxSegmentState = VoxSegmentState.PENDING,
    var translatedDataRef: String? = null,
    val requestedAtMs: Long = System.currentTimeMillis(),
    var completedAtMs: Long = 0L,
    var error: VoxTranslationError? = null
) {
    val durationMs: Long get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0L)

    init {
        require(sourceEndMs >= sourceStartMs) { "sourceEndMs must be >= sourceStartMs" }
    }

    fun isStaleFor(currentGenerationId: Long, currentPosMs: Long): Boolean {
        if (generationId != currentGenerationId) return true
        // Сегмент уже полностью пройден воспроизведением на 30+ секунд назад
        return sourceEndMs < currentPosMs - 30_000L
    }

    /**
     * Безопасное строковое представление без раскрытия URL.
     */
    fun toSafeSummary(): String {
        return "Segment(id=$segmentId, seq=$sequence, span=[$sourceStartMs..$sourceEndMs], state=$state, gen=$generationId)"
    }
}
