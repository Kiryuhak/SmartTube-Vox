package com.liskovsoft.smartyoutubetv2.common.vox.ota

/**
 * Типизированные коды ошибок системы обновления OTA.
 */
enum class VoxOtaErrorCode(val userMessageRu: String) {
    UPDATE_CHECK_FAILED("Не удалось проверить наличие обновлений"),
    RELEASE_PARSE_FAILED("Не удалось обработать данные обновления"),
    VERSION_PARSE_FAILED("Не удалось определить версию приложения"),
    NO_COMPATIBLE_ASSET("Не найден подходящий файл обновления для вашего устройства"),
    ASSET_DOWNLOAD_FAILED("Не удалось скачать обновление"),
    HASH_MISMATCH("Ошибка проверки целостности обновления (не совпадает SHA-256)"),
    SIGNATURE_MISMATCH("Ошибка проверки цифровой подписи обновления"),
    INSTALL_INTENT_FAILED("Не удалось открыть программу установки обновления"),
    PERMISSION_REQUIRED("Для установки обновления требуется разрешение"),
    NETWORK_ERROR("Ошибка сети при проверке обновления"),
    UNKNOWN("Не удалось обновить приложение");
}

/**
 * Исключение обновления OTA с типизированным кодом и понятным описанием для пользователя.
 */
class VoxOtaException(
    val code: VoxOtaErrorCode,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
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
    val sha256SumsUrl: String? = null
)
