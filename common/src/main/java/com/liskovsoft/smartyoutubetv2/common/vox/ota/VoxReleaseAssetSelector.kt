package com.liskovsoft.smartyoutubetv2.common.vox.ota

/**
 * Селектор ассетов релиза для платформы Android.
 *
 * Требования:
 * - Игнорировать .wgt (Tizen) и .txt (SHA256SUMS);
 * - Сопоставлять ABI устройства: arm64-v8a, armeabi-v7a, x86;
 * - Резервный выбор (fallback): universal APK;
 * - Поиск файла контрольных сумм SHA256SUMS.txt.
 */
object VoxReleaseAssetSelector {

    private val IGNORED_EXTENSIONS = setOf(".wgt", ".txt", ".json", ".md", ".zip", ".tar.gz")

    /**
     * Выбирает APK ассет для OTA-обновления.
     *
     * Строгое правило SmartTube VOX 9.1:
     * Встроенный updater ВСЕГДА скачивает только UNIVERSAL APK.
     * Никакого автоматического выбора armeabi-v7a / arm64-v8a / x86.
     * Если universal APK отсутствует — возвращает null (ошибка OTA_UNIVERSAL_ASSET_NOT_FOUND).
     *
     * @param assets Список ассетов из релиза
     * @param primaryAbi Архитектура устройства (игнорируется в VOX 9.1 для OTA)
     * @return Выбранный universal ассет или null, если universal APK не найден
     */
    @JvmStatic
    @JvmOverloads
    fun selectAsset(assets: List<VoxReleaseAsset>, primaryAbi: String? = null): VoxReleaseAsset? {
        val apkAssets = assets.filter { asset ->
            val lower = asset.name.lowercase()
            lower.endsWith(".apk") && IGNORED_EXTENSIONS.none { lower.endsWith(it) && !lower.endsWith(".apk") }
        }

        if (apkAssets.isEmpty()) return null

        fun isUniversal(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith("_universal.apk") || lower.contains("universal")
        }

        fun hasVoxTag(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains("_vot_") || lower.contains("_vox_") || lower.contains("-vot") || lower.contains("-vox")
        }

        val universalApks = apkAssets.filter { isUniversal(it.name) }
        if (universalApks.isEmpty()) {
            return null
        }

        // При наличии нескольких universal APK приоритет отдаётся сборкам с тегом VOX / VOT
        return universalApks.maxByOrNull { if (hasVoxTag(it.name)) 1 else 0 }
    }

    /**
     * Находит файл контрольных сумм SHA256SUMS.txt среди ассетов.
     */
    @JvmStatic
    fun findSha256SumsAsset(assets: List<VoxReleaseAsset>): VoxReleaseAsset? {
        return assets.firstOrNull { asset ->
            val lower = asset.name.lowercase()
            lower == "sha256sums.txt" || lower.endsWith(".sha256") || lower == "sha256sums"
        }
    }
}
