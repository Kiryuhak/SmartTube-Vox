package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Состояния жизненного цикла сессии перевода Translation 2.0.
 */
enum class VoxTranslationSessionState {
    IDLE,
    STARTING,
    BUFFERING,
    TRANSLATING,
    PLAYING,
    DEGRADED,
    PAUSED,
    FAILED,
    STOPPED
}

/**
 * Режимы синхронизации и задержки сессии перевода.
 */
enum class VoxTranslationMode(val id: String, val titleRu: String) {
    AUTO("auto", "Авто"),
    LOW_LATENCY("low_latency", "Минимальная задержка"),
    STABLE("stable", "Стабильный перевод")
}

/**
 * Безопасные события для observability и локальной отладки (без PII и секретов).
 */
enum class VoxTranslationObservabilityEvent {
    VOX_TRANSLATION_SESSION_START,
    VOX_TRANSLATION_SESSION_STOP,
    VOX_TRANSLATION_SEGMENT_QUEUED,
    VOX_TRANSLATION_SEGMENT_DONE,
    VOX_TRANSLATION_SEGMENT_FAILED,
    VOX_TRANSLATION_BUFFER_LEVEL,
    VOX_TRANSLATION_BACKEND_LATENCY,
    VOX_TRANSLATION_FALLBACK,
    VOX_TRANSLATION_SESSION_CANCELLED
}

/**
 * Слушатель событий сессии перевода.
 */
interface VoxTranslationSessionListener {
    fun onStateChanged(oldState: VoxTranslationSessionState, newState: VoxTranslationSessionState)
    fun onFallbackChanged(fallbackActive: Boolean, reasonRu: String?)
    fun onError(error: VoxTranslationError)
    fun onObservabilityEvent(event: VoxTranslationObservabilityEvent, details: String)
}

/**
 * Сессия перевода Translation 2.0 (Foundation для VOD и будущей поддержки Live).
 * Не привязывает UI напрямую к бэкенду.
 * Управляет generationId токенами, предотвращая проигрывание устаревших сегментов после перемотки.
 */
class VoxTranslationSession(
    val sessionId: String,
    var mode: VoxTranslationMode = VoxTranslationMode.AUTO,
    val capabilities: TranslationBackendCapabilities = YandexVotBackendCapabilities,
    val queue: VoxTranslationQueue = VoxTranslationQueue(),
    val buffer: VoxTranslatedBuffer = VoxTranslatedBuffer(),
    val retryPolicy: VoxTranslationRetryPolicy = VoxTranslationRetryPolicy()
) {
    private val lock = Any()
    private val listeners = mutableListOf<VoxTranslationSessionListener>()

    @Volatile
    private var state: VoxTranslationSessionState = VoxTranslationSessionState.IDLE

    @Volatile
    private var generationId: Long = 1L

    @Volatile
    private var fallbackActive: Boolean = false

    @Volatile
    private var lastError: VoxTranslationError? = null

    @Volatile
    private var currentVideoId: String? = null

    fun addListener(listener: VoxTranslationSessionListener) {
        synchronized(lock) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun removeListener(listener: VoxTranslationSessionListener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    fun getState(): VoxTranslationSessionState = state
    fun getGenerationId(): Long = generationId
    fun isFallbackActive(): Boolean = fallbackActive
    fun getLastError(): VoxTranslationError? = lastError
    fun getCurrentVideoId(): String? = currentVideoId

    /**
     * Запуск сессии перевода для видео.
     */
    fun start(videoId: String, initialPositionMs: Long = 0L) {
        synchronized(lock) {
            currentVideoId = videoId
            fallbackActive = false
            lastError = null
            generationId++
            queue.cancelAll()
            buffer.clear()

            transitionTo(VoxTranslationSessionState.STARTING)
            notifyObservability(VoxTranslationObservabilityEvent.VOX_TRANSLATION_SESSION_START, "gen=$generationId")
            transitionTo(VoxTranslationSessionState.BUFFERING)
        }
    }

    fun onBuffering() {
        synchronized(lock) {
            if (state != VoxTranslationSessionState.STOPPED && state != VoxTranslationSessionState.FAILED) {
                transitionTo(VoxTranslationSessionState.BUFFERING)
                notifyObservability(VoxTranslationObservabilityEvent.VOX_TRANSLATION_BUFFER_LEVEL, "bufferedAheadMs=${buffer.getBufferedAheadMs(0)}")
            }
        }
    }

    fun onTranslating() {
        synchronized(lock) {
            if (state != VoxTranslationSessionState.STOPPED && state != VoxTranslationSessionState.FAILED) {
                transitionTo(VoxTranslationSessionState.TRANSLATING)
            }
        }
    }

    fun onPlaying() {
        synchronized(lock) {
            if (state != VoxTranslationSessionState.STOPPED && state != VoxTranslationSessionState.FAILED) {
                transitionTo(VoxTranslationSessionState.PLAYING)
            }
        }
    }

    fun pause() {
        synchronized(lock) {
            if (state == VoxTranslationSessionState.PLAYING || state == VoxTranslationSessionState.TRANSLATING || state == VoxTranslationSessionState.BUFFERING) {
                transitionTo(VoxTranslationSessionState.PAUSED)
            }
        }
    }

    fun resume() {
        synchronized(lock) {
            if (state == VoxTranslationSessionState.PAUSED) {
                transitionTo(VoxTranslationSessionState.PLAYING)
            }
        }
    }

    /**
     * Обработка перемотки (Seek):
     * Повышает generationId, отбрасывает устаревшие сегменты очереди,
     * очищает буфер, чтобы предотвратить воспроизведение звука со старой позиции.
     */
    fun onSeek(newPositionMs: Long) {
        synchronized(lock) {
            generationId++
            val invalidated = queue.invalidateStale(generationId, newPositionMs)
            buffer.clear()
            notifyObservability(VoxTranslationObservabilityEvent.VOX_TRANSLATION_BUFFER_LEVEL, "seek to $newPositionMs, invalidated=$invalidated, gen=$generationId")
            if (state == VoxTranslationSessionState.PLAYING || state == VoxTranslationSessionState.PAUSED) {
                transitionTo(VoxTranslationSessionState.BUFFERING)
            }
        }
    }

    /**
     * Смена видео:
     * Полная отмена предыдущей сессии, сброс очереди, буфера и generationId.
     * Не допускает наложения аудио предыдущего ролика на следующее видео.
     */
    fun onVideoChanged(newVideoId: String) {
        synchronized(lock) {
            notifyObservability(VoxTranslationObservabilityEvent.VOX_TRANSLATION_SESSION_CANCELLED, "video changed")
            generationId++
            queue.cancelAll()
            buffer.clear()
            currentVideoId = newVideoId
            fallbackActive = false
            lastError = null
            transitionTo(VoxTranslationSessionState.IDLE)
        }
    }

    /**
     * Переключение на оригинальную звуковую дорожку при недоступности или сбое перевода.
     * Видео не останавливается.
     */
    fun fallbackToOriginal(reasonRu: String) {
        synchronized(lock) {
            fallbackActive = true
            transitionTo(VoxTranslationSessionState.DEGRADED)
            notifyObservability(VoxTranslationObservabilityEvent.VOX_TRANSLATION_FALLBACK, reasonRu)
            val listenersCopy = synchronized(lock) { listeners.toList() }
            for (listener in listenersCopy) {
                listener.onFallbackChanged(true, reasonRu)
            }
        }
    }

    /**
     * Восстановление перевода после возвращения буфера в норму.
     */
    fun restoreTranslation() {
        synchronized(lock) {
            fallbackActive = false
            if (state == VoxTranslationSessionState.DEGRADED) {
                transitionTo(VoxTranslationSessionState.PLAYING)
            }
            val listenersCopy = synchronized(lock) { listeners.toList() }
            for (listener in listenersCopy) {
                listener.onFallbackChanged(false, null)
            }
        }
    }

    /**
     * Ошибка сессии перевода.
     */
    fun fail(error: VoxTranslationError) {
        synchronized(lock) {
            lastError = error
            fallbackActive = true
            transitionTo(VoxTranslationSessionState.FAILED)
            notifyObservability(VoxTranslationObservabilityEvent.VOX_TRANSLATION_SEGMENT_FAILED, error.type.id)
            val listenersCopy = synchronized(lock) { listeners.toList() }
            for (listener in listenersCopy) {
                listener.onError(error)
                listener.onFallbackChanged(true, error.messageRu)
            }
        }
    }

    /**
     * Полная остановка сессии перевода и освобождение ресурсов.
     */
    fun stop() {
        synchronized(lock) {
            generationId++
            queue.cancelAll()
            buffer.clear()
            transitionTo(VoxTranslationSessionState.STOPPED)
            notifyObservability(VoxTranslationObservabilityEvent.VOX_TRANSLATION_SESSION_STOP, "gen=$generationId")
        }
    }

    /**
     * Извлечение безопасного диагностического снимка Translation 2.0 (Zero-Telemetry).
     */
    fun getDiagnosticsSnapshot(currentPosMs: Long = 0L): VoxTranslationDiagnosticsSnapshot {
        val capabilityStr = if (capabilities.supportsLiveSegments) "LIVE_SEGMENTS" else "VOD_ONLY"
        return VoxTranslationDiagnosticsSnapshot(
            mode = mode.id,
            backendCapability = capabilityStr,
            sessionState = state.name,
            queueDepth = queue.size(),
            bufferedMs = buffer.getBufferedAheadMs(currentPosMs),
            averageLatencyMs = buffer.getLastLatencyMs(),
            lastErrorCategory = lastError?.type?.id,
            fallbackActive = fallbackActive
        )
    }

    private fun transitionTo(newState: VoxTranslationSessionState) {
        if (state == newState) return
        val oldState = state
        state = newState
        val listenersCopy = synchronized(lock) { listeners.toList() }
        for (listener in listenersCopy) {
            listener.onStateChanged(oldState, newState)
        }
    }

    private fun notifyObservability(event: VoxTranslationObservabilityEvent, details: String) {
        val listenersCopy = synchronized(lock) { listeners.toList() }
        for (listener in listenersCopy) {
            listener.onObservabilityEvent(event, details)
        }
    }
}
