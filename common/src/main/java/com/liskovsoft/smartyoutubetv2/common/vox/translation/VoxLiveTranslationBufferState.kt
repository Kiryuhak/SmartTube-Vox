package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Фаза жизненного цикла воспроизведения live-перевода.
 */
enum class VoxLivePlaybackPhase(val titleRu: String) {
    IDLE("Неактивен"),
    HOLDING_INITIAL_BUFFER("Подготовка перевода…"),
    PLAYING_TRANSLATED("Перевод активен"),
    REBUFFERING("Буферизация перевода…"),
    FALLBACK_ORIGINAL("Оригинальный звук"),
    STOPPED("Остановлен");
}

/**
 * Неизменяемое состояние буфера перевода прямого эфира.
 */
data class VoxLiveTranslationBufferState(
    val phase: VoxLivePlaybackPhase = VoxLivePlaybackPhase.IDLE,
    val liveEdgePositionMs: Long = 0L,
    val playbackPositionMs: Long = 0L,
    val translatedAudioReadyUntilMs: Long = 0L,
    val translationQueueDepth: Int = 0,
    val translationLatencyMs: Long = 0L,
    val networkLatencyMs: Long = 0L,
    val mode: VoxLiveDelayMode = VoxLiveDelayMode.AUTO,
    val targetDelayMs: Long = 18_000L,
    val consecutiveUnderruns: Int = 0,
    val statusMessage: String? = null
) {
    /**
     * Запас переведённого аудио впереди текущей позиции воспроизведения (мс).
     */
    val bufferAheadMs: Long
        get() = (translatedAudioReadyUntilMs - playbackPositionMs).coerceAtLeast(0L)

    /**
     * Текущее отставание от реального края прямого эфира (мс).
     */
    val currentDelayMs: Long
        get() = (liveEdgePositionMs - playbackPositionMs).coerceAtLeast(0L)

    /**
     * Достаточен ли буфер для старта или продолжения воспроизведения.
     */
    fun hasSufficientBuffer(): Boolean {
        return VoxLiveTranslationDelayPolicy.canResumeAfterRebuffer(bufferAheadMs, mode)
    }

    /**
     * Находится ли буфер в состоянии критического истощения.
     */
    fun isBufferCritical(): Boolean {
        return VoxLiveTranslationDelayPolicy.shouldRebuffer(bufferAheadMs, mode)
    }

    /**
     * Человекочитаемая строка статуса для оверлея / HUD.
     */
    fun formatHudStatus(): String {
        return when (phase) {
            VoxLivePlaybackPhase.IDLE -> "Перевести"
            VoxLivePlaybackPhase.HOLDING_INITIAL_BUFFER -> "Подготовка перевода…"
            VoxLivePlaybackPhase.PLAYING_TRANSLATED -> {
                val sec = (currentDelayMs / 1000).coerceAtLeast(1)
                "Перевод активен · задержка ${sec} с"
            }
            VoxLivePlaybackPhase.REBUFFERING -> "Буферизация перевода…"
            VoxLivePlaybackPhase.FALLBACK_ORIGINAL -> "Оригинальный звук"
            VoxLivePlaybackPhase.STOPPED -> "Перевод остановлен"
        }
    }
}
