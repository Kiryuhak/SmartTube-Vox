package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Режим задержки перевода прямого эфира.
 */
enum class VoxLiveDelayMode(val id: String, val titleRu: String, val descriptionRu: String) {
    AUTO("auto", "Автоматически (рекомендуется)", "Баланс между актуальностью эфира и устойчивостью звука (15–25 с)"),
    LOW_LATENCY("low_latency", "Минимальная задержка", "Минимальное отставание от прямого эфира (10–15 с)"),
    STABLE("stable", "Стабильный перевод", "Максимальный запас буфера для исключения заиканий (25–40 с)");

    companion object {
        fun fromId(id: String?): VoxLiveDelayMode {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

/**
 * Граничные значения задержки и порогов буферизации.
 */
data class VoxLiveDelayBounds(
    val mode: VoxLiveDelayMode,
    val minDelayMs: Long,
    val targetDelayMs: Long,
    val maxDelayMs: Long,
    val rebufferThresholdMs: Long,
    val resumeBufferThresholdMs: Long
)

/**
 * Политика динамической задержки и буферизации live-перевода SmartTube VOX.
 */
object VoxLiveTranslationDelayPolicy {

    private val BOUNDS_MAP = mapOf(
        VoxLiveDelayMode.LOW_LATENCY to VoxLiveDelayBounds(
            mode = VoxLiveDelayMode.LOW_LATENCY,
            minDelayMs = 8_000L,
            targetDelayMs = 12_000L,
            maxDelayMs = 25_000L,
            rebufferThresholdMs = 3_000L,
            resumeBufferThresholdMs = 7_000L
        ),
        VoxLiveDelayMode.AUTO to VoxLiveDelayBounds(
            mode = VoxLiveDelayMode.AUTO,
            minDelayMs = 12_000L,
            targetDelayMs = 18_000L,
            maxDelayMs = 35_000L,
            rebufferThresholdMs = 4_000L,
            resumeBufferThresholdMs = 10_000L
        ),
        VoxLiveDelayMode.STABLE to VoxLiveDelayBounds(
            mode = VoxLiveDelayMode.STABLE,
            minDelayMs = 20_000L,
            targetDelayMs = 30_000L,
            maxDelayMs = 60_000L,
            rebufferThresholdMs = 6_000L,
            resumeBufferThresholdMs = 15_000L
        )
    )

    @JvmStatic
    fun getBounds(mode: VoxLiveDelayMode): VoxLiveDelayBounds {
        return BOUNDS_MAP[mode] ?: BOUNDS_MAP.getValue(VoxLiveDelayMode.AUTO)
    }

    /**
     * Плавная адаптация целевой задержки воспроизведения.
     * Не допускает резких скачков назад или вперед.
     */
    @JvmStatic
    @JvmOverloads
    fun calculateNextDelay(
        currentDelayMs: Long,
        bufferAheadMs: Long,
        mode: VoxLiveDelayMode,
        consecutiveUnderruns: Int = 0
    ): Long {
        val bounds = getBounds(mode)
        var delay = if (currentDelayMs <= 0) bounds.targetDelayMs else currentDelayMs

        // При повторных сбоях/нехватке буфера ступенчато увеличиваем задержку
        if (consecutiveUnderruns > 0) {
            val penalty = consecutiveUnderruns * 4_000L
            delay = (delay + penalty).coerceAtMost(bounds.maxDelayMs)
            return delay
        }

        // Если запас переведенного звука падает ниже критического
        if (bufferAheadMs < bounds.rebufferThresholdMs) {
            delay = (delay + 3_000L).coerceAtMost(bounds.maxDelayMs)
        } else if (bufferAheadMs > bounds.resumeBufferThresholdMs + 10_000L && delay > bounds.targetDelayMs) {
            // Если транслятор стабильно опережает воспроизведение, плавно снижаем задержку к целевой
            delay = (delay - 1_000L).coerceAtLeast(bounds.targetDelayMs)
        }

        return delay.coerceIn(bounds.minDelayMs, bounds.maxDelayMs)
    }

    @JvmStatic
    fun shouldRebuffer(bufferAheadMs: Long, mode: VoxLiveDelayMode): Boolean {
        val bounds = getBounds(mode)
        return bufferAheadMs < bounds.rebufferThresholdMs
    }

    @JvmStatic
    fun canResumeAfterRebuffer(bufferAheadMs: Long, mode: VoxLiveDelayMode): Boolean {
        val bounds = getBounds(mode)
        return bufferAheadMs >= bounds.resumeBufferThresholdMs
    }
}
