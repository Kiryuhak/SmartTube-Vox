package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Трёхзначное состояние поддержки возможности перевода (Tri-state capability).
 */
enum class TranslationCapabilityState(val id: String, val titleRu: String) {
    SUPPORTED("supported", "Поддерживается"),
    UNSUPPORTED("unsupported", "Не поддерживается"),
    UNKNOWN("unknown", "Не определено");

    val isSupported: Boolean get() = this == SUPPORTED
}

/**
 * Контракт аппаратных и серверных возможностей бэкенда перевода (Translation Backend Contract).
 */
interface TranslationBackendCapabilities {
    val supportsVod: Boolean
    val supportsLiveSegments: Boolean
    val supportsSessionContinuation: Boolean
    val supportsStreamingResponse: Boolean
    val maxSegmentDurationMs: Long
    val supportsAuth: Boolean
    val supportsAnonymous: Boolean
    val supportsVoiceMode: Boolean

    val vodCapability: TranslationCapabilityState
        get() = if (supportsVod) TranslationCapabilityState.SUPPORTED else TranslationCapabilityState.UNSUPPORTED

    val liveCapability: TranslationCapabilityState
        get() = if (supportsLiveSegments) TranslationCapabilityState.SUPPORTED else TranslationCapabilityState.UNSUPPORTED
}

/**
 * Фактические возможности текущего бэкенда Яндекс VOT.
 * Честно отражает текущий статус: VOD_ONLY (потоковые live-сегменты не поддерживаются публичным API).
 */
object YandexVotBackendCapabilities : TranslationBackendCapabilities {
    override val supportsVod: Boolean = true
    override val supportsLiveSegments: Boolean = false // Честный статус: VOD_ONLY
    override val supportsSessionContinuation: Boolean = false
    override val supportsStreamingResponse: Boolean = false
    override val maxSegmentDurationMs: Long = 0L
    override val supportsAuth: Boolean = true
    override val supportsAnonymous: Boolean = true
    override val supportsVoiceMode: Boolean = true // Стандартный голос и «Живой голос» (Lively)

    @JvmStatic
    fun asSummaryString(): String {
        return "YandexVOT(VOD=SUPPORTED, LIVE=UNSUPPORTED[VOD_ONLY], Auth=SUPPORTED, VoiceMode=SUPPORTED)"
    }
}
