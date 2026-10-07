package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Тип сетевого транспорта бэкенда перевода.
 */
enum class VoxLiveBackendTransport(val id: String, val titleRu: String) {
    HTTP_POLLING("http_polling", "Периодический опрос HTTP (Polling)"),
    HTTP_CHUNKED("http_chunked", "Потоковый HTTP Chunked"),
    WEBSOCKET("websocket", "Полнодуплексный WebSocket"),
    GRPC("grpc", "gRPC потоковый транспорт"),
    NONE("none", "Отсутствует"),
    UNKNOWN("unknown", "Не определено");
}

/**
 * Причины блокировки Live Translation на уровне архитектуры бэкенда.
 */
enum class VoxLiveTranslationBlocker(val id: String, val titleRu: String) {
    NONE("none", "Блокировок нет"),
    BACKEND_REQUIRES_COMPLETE_MEDIA("backend_requires_complete_media", "Сервер требует завершённый VOD медиа-файл"),
    NO_STREAMING_ENDPOINT("no_streaming_endpoint", "Отсутствует серверный эндпоинт для потоковой передачи"),
    SEGMENT_API_ABSENT("segment_api_absent", "Серверный контракт не поддерживает сегментный API"),
    AUTHENTICATION_RESTRICTION("auth_restriction", "Ограничение авторизации для прямых эфиров");
}

/**
 * Флаги экспериментальных возможностей Live Translation.
 * Все экспериментальные функции по умолчанию СТРОГО ВЫКЛЮЧЕНЫ.
 */
object VoxLiveFeatureFlags {
    @JvmField
    var VOX_LIVE_TRANSLATION_EXPERIMENTAL: Boolean = false

    @JvmField
    var VOX_LIVE_AUDIO_CAPTURE_EXPERIMENTAL: Boolean = false

    @JvmField
    var VOX_LIVE_SECONDARY_AUDIO_EXPERIMENTAL: Boolean = false

    @JvmStatic
    fun resetToDefaults() {
        VOX_LIVE_TRANSLATION_EXPERIMENTAL = false
        VOX_LIVE_AUDIO_CAPTURE_EXPERIMENTAL = false
        VOX_LIVE_SECONDARY_AUDIO_EXPERIMENTAL = false
    }
}

/**
 * Модель сетевых протокольных возможностей бэкенда перевода (Protocol Result Model).
 */
data class VoxLiveProtocolCapability(
    val capability: VoxLiveBackendCapability = VoxLiveBackendCapability.VOD_ONLY,
    val transport: VoxLiveBackendTransport = VoxLiveBackendTransport.HTTP_POLLING,
    val supportsSequentialSegments: Boolean = false,
    val supportsSessionContinuation: Boolean = false,
    val supportsIncrementalAudio: Boolean = false,
    val supportsCancellation: Boolean = true,
    val supportsReconnect: Boolean = true,
    val blocker: VoxLiveTranslationBlocker = VoxLiveTranslationBlocker.BACKEND_REQUIRES_COMPLETE_MEDIA
) {
    companion object {
        /**
         * Фактическое состояние текущего публичного бэкенда Яндекс VOT:
         * - VOD_ONLY
         * - HTTP_POLLING
         * - Отсутствие поддержки последовательных сегментов и сессионного стриминга
         */
        @JvmField
        val CURRENT_VOT_BACKEND = VoxLiveProtocolCapability(
            capability = VoxLiveBackendCapability.VOD_ONLY,
            transport = VoxLiveBackendTransport.HTTP_POLLING,
            supportsSequentialSegments = false,
            supportsSessionContinuation = false,
            supportsIncrementalAudio = false,
            supportsCancellation = true,
            supportsReconnect = true,
            blocker = VoxLiveTranslationBlocker.BACKEND_REQUIRES_COMPLETE_MEDIA
        )

        @JvmStatic
        fun mapFromCapability(cap: VoxLiveBackendCapability): VoxLiveProtocolCapability {
            return when (cap) {
                VoxLiveBackendCapability.VOD_ONLY -> CURRENT_VOT_BACKEND
                VoxLiveBackendCapability.LIVE_CHUNK_SUPPORTED -> VoxLiveProtocolCapability(
                    capability = VoxLiveBackendCapability.LIVE_CHUNK_SUPPORTED,
                    transport = VoxLiveBackendTransport.HTTP_CHUNKED,
                    supportsSequentialSegments = true,
                    supportsSessionContinuation = true,
                    supportsIncrementalAudio = true,
                    supportsCancellation = true,
                    supportsReconnect = true,
                    blocker = VoxLiveTranslationBlocker.NONE
                )
                VoxLiveBackendCapability.LIVE_URL_SUPPORTED -> VoxLiveProtocolCapability(
                    capability = VoxLiveBackendCapability.LIVE_URL_SUPPORTED,
                    transport = VoxLiveBackendTransport.HTTP_POLLING,
                    supportsSequentialSegments = false,
                    supportsSessionContinuation = true,
                    supportsIncrementalAudio = false,
                    supportsCancellation = true,
                    supportsReconnect = true,
                    blocker = VoxLiveTranslationBlocker.NONE
                )
                VoxLiveBackendCapability.UNKNOWN -> VoxLiveProtocolCapability(
                    capability = VoxLiveBackendCapability.UNKNOWN,
                    transport = VoxLiveBackendTransport.UNKNOWN,
                    supportsSequentialSegments = false,
                    supportsSessionContinuation = false,
                    supportsIncrementalAudio = false,
                    supportsCancellation = false,
                    supportsReconnect = false,
                    blocker = VoxLiveTranslationBlocker.NO_STREAMING_ENDPOINT
                )
            }
        }
    }
}

/**
 * Безопасные события сетевой инструментации протокола перевода (Zero-Telemetry).
 */
enum class VoxLiveProtocolEvent {
    VOX_LIVE_PROTO_REQUEST_START,
    VOX_LIVE_PROTO_REQUEST_END,
    VOX_LIVE_PROTO_HTTP_STATUS,
    VOX_LIVE_PROTO_TRANSPORT,
    VOX_LIVE_PROTO_OPERATION_ID_PRESENT,
    VOX_LIVE_PROTO_SESSION_ID_PRESENT,
    VOX_LIVE_PROTO_STREAMING_RESPONSE,
    VOX_LIVE_PROTO_POLL_COUNT,
    VOX_LIVE_PROTO_LATENCY,
    VOX_LIVE_PROTO_CANCEL,
    VOX_LIVE_PROTO_ERROR;
}

/**
 * Санитизированный логгер сетевых событий без сохранения секретов, токенов, cookies и URL.
 */
object VoxLiveProtocolLogger {
    interface Listener {
        fun onProtocolEvent(event: VoxLiveProtocolEvent, safeDetails: String)
    }

    private val listeners = mutableListOf<Listener>()

    @Synchronized
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun clearListeners() {
        listeners.clear()
    }

    @Synchronized
    fun log(event: VoxLiveProtocolEvent, safeDetails: String = "") {
        val sanitized = sanitize(safeDetails)
        for (listener in listeners) {
            listener.onProtocolEvent(event, sanitized)
        }
    }

    fun sanitize(input: String): String {
        return input
            .replace(Regex("https?://[^\\s]+"), "<URL_REDACTED>")
            .replace(Regex("(?i)token=[^\\s&]+"), "token=<REDACTED>")
            .replace(Regex("(?i)bearer\\s+[^\\s]+"), "bearer <REDACTED>")
            .replace(Regex("(?i)oauth\\s+[^\\s]+"), "oauth <REDACTED>")
            .replace(Regex("(?i)cookie=[^\\s;]+"), "cookie=<REDACTED>")
            .replace(Regex("(?i)password=[^\\s&]+"), "password=<REDACTED>")
    }
}
