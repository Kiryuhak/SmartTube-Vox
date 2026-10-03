package com.liskovsoft.smartyoutubetv2.common.vox.capability

/**
 * Трёхпозиционная модель поддержки возможностей устройства (Tri-state capability).
 * ВАЖНО: UNKNOWN != UNSUPPORTED. Неизвестное состояние не означает отсутствие поддержки.
 */
enum class TriStateCapability(val value: String, val labelRu: String, val labelEn: String) {
    SUPPORTED("supported", "Поддерживается", "Supported"),
    UNSUPPORTED("unsupported", "Не поддерживается", "Unsupported"),
    UNKNOWN("unknown", "Неизвестно", "Unknown");

    fun isSupported(): Boolean = this == SUPPORTED
    fun isUnsupported(): Boolean = this == UNSUPPORTED
    fun isUnknown(): Boolean = this == UNKNOWN

    companion object {
        @JvmStatic
        fun fromValue(value: String?): TriStateCapability {
            return values().firstOrNull { it.value.equals(value, ignoreCase = true) } ?: UNKNOWN
        }

        @JvmStatic
        fun fromBoolean(supported: Boolean?): TriStateCapability {
            return when (supported) {
                true -> SUPPORTED
                false -> UNSUPPORTED
                null -> UNKNOWN
            }
        }
    }
}
