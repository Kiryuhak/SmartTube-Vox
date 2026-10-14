package com.liskovsoft.smartyoutubetv2.common.vox.ota

/**
 * Каналы обновлений SmartTube VOX.
 */
enum class VoxUpdateChannel(val id: String, val titleRu: String, val descriptionRu: String) {
    STABLE("stable", "Стабильный", "Только проверенные стабильные релизы"),
    TEST("test", "Тестовый (Beta)", "Ранний доступ к новым функциям и исправлениям (возможна нестабильная работа)");

    companion object {
        @JvmStatic
        fun fromId(id: String?): VoxUpdateChannel {
            return if (id.equals("test", ignoreCase = true) || id.equals("beta", ignoreCase = true)) TEST else STABLE
        }
    }
}

/**
 * Канонические каналы сборки SmartTube VOX (Canonical Build Channels).
 */
enum class VoxBuildChannel(val id: String, val titleRu: String, val isPreRelease: Boolean) {
    DEV("dev", "Разработка (Dev)", true),
    ALPHA("alpha", "Альфа (Alpha)", true),
    BETA("beta", "Бета (Beta)", true),
    RC("rc", "Кандидат в релиз (RC)", true),
    STABLE("stable", "Стабильный (Stable)", false);

    companion object {
        @JvmStatic
        fun fromVersion(versionName: String?): VoxBuildChannel {
            if (versionName == null) return STABLE
            val lower = versionName.lowercase()
            return when {
                lower.contains("-dev") -> DEV
                lower.contains("-alpha") -> ALPHA
                lower.contains("-beta") || lower.contains("beta") -> BETA
                lower.contains("-rc") -> RC
                else -> STABLE
            }
        }
    }
}

/**
 * Стадии процесса проверки и установки обновления OTA.
 */
enum class VoxOtaStage(val id: String) {
    START("START"),
    DNS("DNS"),
    CONNECT("CONNECT"),
    TLS("TLS"),
    HTTP("HTTP"),
    PARSE("PARSE"),
    COMPARE("COMPARE"),
    RESULT("RESULT");
}

/**
 * Типизированные коды ошибок и результатов системы обновления OTA.
 */
enum class VoxOtaErrorCode(val userMessageRu: String) {
    NO_UPDATE("У вас установлена последняя версия"),
    UPDATE_AVAILABLE("Доступно обновление"),
    NETWORK_ERROR("Ошибка сети при проверке обновления"),
    DNS_ERROR("Ошибка DNS при подключении к серверу обновлений"),
    TIMEOUT("Превышено время ожидания ответа сервера обновлений"),
    TLS_ERROR("Ошибка защищенного соединения (TLS/SSL)"),
    HTTP_ERROR("Ошибка HTTP-сервера обновлений"),
    RATE_LIMIT("Сервер временно ограничил частоту проверок. Попробуйте позже."),
    RELEASE_NOT_FOUND("Данные обновления не найдены на сервере"),
    INVALID_RELEASE("Некорректный формат данных обновления"),
    VERSION_NOT_NEWER("У вас установлена последняя версия"),
    CHANNEL_MISMATCH("Обновление не соответствует выбранному каналу"),
    PARSE_ERROR("Ошибка обработки данных обновления"),
    NO_COMPATIBLE_ASSET("Не найден подходящий файл обновления для вашего устройства"),
    UNIVERSAL_ASSET_NOT_FOUND("Не найден универсальный пакет обновления."),
    ASSET_DOWNLOAD_FAILED("Не удалось скачать обновление"),
    HASH_MISMATCH("Ошибка проверки целостности обновления (не совпадает SHA-256)"),
    SIGNATURE_MISMATCH("Цифровая подпись обновления не совпадает с установленным приложением"),
    SIGNATURE_UNKNOWN("Не удалось проверить цифровую подпись обновления"),
    PACKAGE_MISMATCH("Пакет обновления не соответствует установленному приложению"),
    ARCH_MISMATCH("Пакет обновления несовместим с архитектурой процессора устройства"),
    DOWNLOAD_INCOMPLETE("Файл обновления загружен не полностью"),
    INVALID_CONTENT("Сервер вернул некорректный ответ вместо файла обновления"),
    APK_INVALID("Пакет обновления повреждён или не является APK"),
    INSTALL_PERMISSION("Для установки обновления требуется разрешение в настройках системы"),
    INSTALL_CANCELLED("Установка обновления была отменена"),
    INSTALL_FAILED("Не удалось запустить установку обновления"),
    STORAGE_LOW("Недостаточно свободного места на устройстве для обновления"),
    INSTALL_INTENT_FAILED("Не удалось открыть программу установки обновления"),
    PERMISSION_REQUIRED("Для установки обновления требуется разрешение"),
    UPDATE_CHECK_FAILED("Не удалось проверить наличие обновлений"),
    RELEASE_PARSE_FAILED("Не удалось обработать данные обновления"),
    VERSION_PARSE_FAILED("Не удалось определить версию приложения"),
    UNKNOWN("Не удалось обновить приложение");

    companion object {
        val OTA_NO_UPDATE get() = NO_UPDATE
        val OTA_RATE_LIMIT get() = RATE_LIMIT
        val OTA_NETWORK_ERROR get() = NETWORK_ERROR
        val OTA_HTTP_ERROR get() = HTTP_ERROR
        val OTA_DOWNLOAD_INCOMPLETE get() = DOWNLOAD_INCOMPLETE
        val OTA_INVALID_CONTENT get() = INVALID_CONTENT
        val OTA_APK_INVALID get() = APK_INVALID
        val OTA_SIGNATURE_MISMATCH get() = SIGNATURE_MISMATCH
        val OTA_SIGNATURE_UNKNOWN get() = SIGNATURE_UNKNOWN
        val OTA_PACKAGE_MISMATCH get() = PACKAGE_MISMATCH
        val OTA_ARCH_MISMATCH get() = ARCH_MISMATCH
        val OTA_INSTALL_PERMISSION get() = INSTALL_PERMISSION
        val OTA_INSTALL_CANCELLED get() = INSTALL_CANCELLED
        val OTA_INSTALL_FAILED get() = INSTALL_FAILED
        val OTA_UNIVERSAL_ASSET_NOT_FOUND get() = UNIVERSAL_ASSET_NOT_FOUND
        val OTA_UNKNOWN get() = UNKNOWN
    }
}

/**
 * Структурированный результат проверки обновлений OTA.
 */
data class VoxOtaResult(
    val code: VoxOtaErrorCode,
    val stage: VoxOtaStage = VoxOtaStage.RESULT,
    val httpStatus: Int? = null,
    val isRetryable: Boolean = false,
    val userMessage: String = code.userMessageRu,
    val diagnosticReason: String = "",
    val releaseInfo: VoxReleaseInfo? = null,
    val selectedAsset: VoxReleaseAsset? = null
) {
    val isSuccessful: Boolean
        get() = code == VoxOtaErrorCode.UPDATE_AVAILABLE || code == VoxOtaErrorCode.NO_UPDATE || code == VoxOtaErrorCode.VERSION_NOT_NEWER

    val isUpdateAvailable: Boolean
        get() = code == VoxOtaErrorCode.UPDATE_AVAILABLE
}

/**
 * Исключение обновления OTA с типизированным кодом и понятным описанием для пользователя.
 */
class VoxOtaException(
    val code: VoxOtaErrorCode,
    override val message: String,
    val stage: VoxOtaStage = VoxOtaStage.HTTP,
    val httpStatus: Int? = null,
    val isRetryable: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause) {

    constructor(code: VoxOtaErrorCode, message: String, cause: Throwable?) :
            this(code, message, VoxOtaStage.HTTP, null, false, cause)

    val userMessageRu: String
        get() = code.userMessageRu
}

/**
 * Описание ассета релиза GitHub.
 */
data class VoxReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L
)

/**
 * Описание найденного релиза SmartTube VOX.
 */
data class VoxReleaseInfo(
    val tagName: String,
    val versionName: String,
    val changelog: List<String>,
    val assets: List<VoxReleaseAsset>,
    val sha256SumsUrl: String? = null,
    val isPrerelease: Boolean = false
)
