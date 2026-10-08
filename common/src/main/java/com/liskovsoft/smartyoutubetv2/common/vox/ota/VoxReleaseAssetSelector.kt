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
     * Выбирает наиболее подходящий APK ассет для архитектуры устройства.
     *
     * @param assets Список ассетов из релиза
     * @param primaryAbi Архитектура устройства (из DeviceHelpers или Build.SUPPORTED_ABIS)
     * @return Выбранный ассет или null, если подходящий APK не найден
     */
    @JvmStatic
    @JvmOverloads
    fun selectAsset(assets: List<VoxReleaseAsset>, primaryAbi: String? = null): VoxReleaseAsset? {
        val apkAssets = assets.filter { asset ->
            val lower = asset.name.lowercase()
            lower.endsWith(".apk") && IGNORED_EXTENSIONS.none { lower.endsWith(it) && !lower.endsWith(".apk") }
        }

        if (apkAssets.isEmpty()) return null

        val abi = primaryAbi?.lowercase() ?: ""

        val preferredKey = when {
            abi.contains("arm64") -> "arm64-v8a"
            abi.contains("armeabi") || abi.contains("armv7") || abi.contains("arm") -> "armeabi-v7a"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "x86"
            else -> null
        }

        // 1. Точное совпадение по ABI
        if (preferredKey != null) {
            val matched = apkAssets.firstOrNull { it.name.lowercase().contains(preferredKey) }
            if (matched != null) return matched

            // Для arm64 поддерживается совместимый запуск armeabi-v7a
            if (preferredKey == "arm64-v8a") {
                val armv7 = apkAssets.firstOrNull { it.name.lowercase().contains("armeabi-v7a") }
                if (armv7 != null) return armv7
            }
        }

        // 2. Резервный universal APK
        val universal = apkAssets.firstOrNull { it.name.lowercase().contains("universal") }
        if (universal != null) return universal

        // 3. Любой доступный APK
        return apkAssets.firstOrNull()
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
