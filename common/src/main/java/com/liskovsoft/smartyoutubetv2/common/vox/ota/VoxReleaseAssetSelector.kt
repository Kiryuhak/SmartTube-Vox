package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.io.File
import java.util.zip.ZipFile

/**
 * Селектор ассетов релиза SmartTube VOX с реализацией контракта «Ровно три APK»
 * и стратегии Sticky ABI для версий 9.3+.
 *
 * Контракт будущих релизов (Gate 3):
 * 1. SmartTube_vot_<VERSION>_arm64-v8a.apk
 * 2. SmartTube_vot_<VERSION>_armeabi-v7a.apk
 * 3. SmartTube_vot_<VERSION>_universal.apk
 * Сопутствующие не-APK файлы: smarttube_vox.json, SHA256SUMS.txt.
 * Публикация _x86.apk прекращена; эмуляторы и x86 TV обслуживаются universal APK.
 *
 * Стратегия Sticky ABI:
 * - После подтверждённого обновления сохраняется выбранный вариант (arm64-v8a | armeabi-v7a | universal).
 * - На следующем цикле обновления запрашивается тот же вариант:
 *   v7a -> v7a; arm64 -> arm64; universal -> universal.
 * - При отсутствии маркера (legacy 8/9.1/9.2) выполняется надёжное определение установленного варианта
 *   по составу нативных библиотек в sourceDir, либо безопасный fallback на universal APK.
 */
object VoxReleaseAssetSelector {

    private val IGNORED_EXTENSIONS = setOf(".wgt", ".txt", ".json", ".md", ".zip", ".tar.gz")

    private const val PREFS_NAME = "vox_ota_variant_prefs"
    private const val KEY_CONFIRMED_VARIANT = "confirmed_variant"
    private const val KEY_PENDING_VARIANT = "pending_variant"
    private const val KEY_PENDING_VERSION_CODE = "pending_version_code"

    enum class Variant(val id: String, val suffix: String) {
        ARM64_V8A("arm64-v8a", "_arm64-v8a.apk"),
        ARMEABI_V7A("armeabi-v7a", "_armeabi-v7a.apk"),
        UNIVERSAL("universal", "_universal.apk");

        companion object {
            @JvmStatic
            fun fromId(id: String?): Variant? {
                if (id == null) return null
                return values().firstOrNull { it.id.equals(id, ignoreCase = true) }
            }
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun getConfirmedVariant(context: Context): Variant? {
        val id = getPrefs(context).getString(KEY_CONFIRMED_VARIANT, null)
        return Variant.fromId(id)
    }

    @JvmStatic
    fun setConfirmedVariant(context: Context, variant: Variant) {
        getPrefs(context).edit().putString(KEY_CONFIRMED_VARIANT, variant.id).apply()
        VoxSafeLogger.info(
            VoxLogCategory.OTA,
            VoxLogCode.OTA_VARIANT_PERSISTED,
            "Зафиксирован подтверждённый вариант ABI: ${variant.id}",
            mapOf("variant" to variant.id)
        )
    }

    @JvmStatic
    fun getPendingVariant(context: Context): Variant? {
        val id = getPrefs(context).getString(KEY_PENDING_VARIANT, null)
        return Variant.fromId(id)
    }

    @JvmStatic
    fun getPendingVersionCode(context: Context): Long {
        return getPrefs(context).getLong(KEY_PENDING_VERSION_CODE, 0L)
    }

    @JvmStatic
    fun setPendingVariant(context: Context, variant: Variant?, targetVersionCode: Long) {
        getPrefs(context).edit().apply {
            if (variant != null) {
                putString(KEY_PENDING_VARIANT, variant.id)
                putLong(KEY_PENDING_VERSION_CODE, targetVersionCode)
            } else {
                remove(KEY_PENDING_VARIANT)
                remove(KEY_PENDING_VERSION_CODE)
            }
            apply()
        }
    }

    /**
     * Атомарная фиксация варианта на старте приложения: если установленная версия
     * достигла или превысила pendingVersionCode, подтверждаем variant.
     */
    @JvmStatic
    fun commitPendingVariantIfUpgraded(context: Context, installedVersionCode: Long) {
        val pending = getPendingVariant(context) ?: return
        val pendingVc = getPendingVersionCode(context)
        if (installedVersionCode > 0 && pendingVc > 0 && installedVersionCode >= pendingVc) {
            setConfirmedVariant(context, pending)
            setPendingVariant(context, null, 0L)
            VoxSafeLogger.info(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_VARIANT_PERSISTED,
                "Успешное обновление подтверждено на старте, зафиксирован вариант: ${pending.id}",
                mapOf("installedVersionCode" to installedVersionCode.toString(), "variant" to pending.id)
            )
        }
    }

    /**
     * Определяет вариант ABI фактически установленного APK приложения (по sourceDir).
     */
    @JvmStatic
    fun detectInstalledVariant(context: Context): Variant {
        try {
            val sourceDir = context.applicationInfo.sourceDir
            if (!sourceDir.isNullOrBlank()) {
                val file = File(sourceDir)
                if (file.exists() && file.isFile) {
                    ZipFile(file).use { zip ->
                        var hasArm64 = false
                        var hasArmV7 = false
                        var hasX86 = false
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val name = entries.nextElement().name
                            if (name.startsWith("lib/arm64-v8a/")) hasArm64 = true
                            if (name.startsWith("lib/armeabi-v7a/")) hasArmV7 = true
                            if (name.startsWith("lib/x86/") || name.startsWith("lib/x86_64/")) hasX86 = true
                        }
                        if (hasX86 || (hasArm64 && hasArmV7)) {
                            return Variant.UNIVERSAL
                        }
                        if (hasArm64 && !hasArmV7) {
                            return Variant.ARM64_V8A
                        }
                        if (hasArmV7 && !hasArm64) {
                            return Variant.ARMEABI_V7A
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            VoxSafeLogger.warn(
                VoxLogCategory.OTA,
                VoxLogCode.OTA_VARIANT_DETECTION_FALLBACK,
                "Не удалось определить установленный вариант по sourceDir, fallback universal",
                mapOf("error" to (e.message ?: e.javaClass.simpleName))
            )
        }
        return Variant.UNIVERSAL
    }

    /**
     * Выбирает оптимальный APK ассет для OTA обновления с учётом Sticky ABI стратегии.
     *
     * @param assets Список ассетов релиза
     * @param preferredVariant Запрашиваемый/сохранённый вариант (arm64-v8a | armeabi-v7a | universal)
     * @param primaryAbi Первичная архитектура целевого устройства (или Build.SUPPORTED_ABIS[0])
     * @return Выбранный ассет или null при отсутствии подходящего APK
     */
    @JvmStatic
    fun selectAsset(
        assets: List<VoxReleaseAsset>,
        preferredVariant: Variant?,
        primaryAbi: String?
    ): VoxReleaseAsset? {
        val apkAssets = assets.filter { asset ->
            val lower = asset.name.lowercase()
            lower.endsWith(".apk") && IGNORED_EXTENSIONS.none { lower.endsWith(it) && !lower.endsWith(".apk") }
        }

        if (apkAssets.isEmpty()) return null

        fun isUniversal(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith("_universal.apk") || lower.contains("universal")
        }

        fun isArm64(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith("_arm64-v8a.apk") || lower.contains("arm64-v8a")
        }

        fun isArmV7(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith("_armeabi-v7a.apk") || lower.contains("armeabi-v7a")
        }

        fun hasVoxTag(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains("_vot_") || lower.contains("_vox_") || lower.contains("-vot") || lower.contains("-vox")
        }

        val arm64Asset = apkAssets.filter { isArm64(it.name) }.maxByOrNull { if (hasVoxTag(it.name)) 1 else 0 }
        val armV7Asset = apkAssets.filter { isArmV7(it.name) }.maxByOrNull { if (hasVoxTag(it.name)) 1 else 0 }
        val universalAsset = apkAssets.filter { isUniversal(it.name) }.maxByOrNull { if (hasVoxTag(it.name)) 1 else 0 }

        val resolvedAbi = (primaryAbi ?: Build.CPU_ABI ?: "").lowercase()

        // Ветка 1: Если запрошен ARM64-V8A
        if (preferredVariant == Variant.ARM64_V8A) {
            val deviceSupports64 = resolvedAbi.contains("arm64") ||
                    (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) })
            if (deviceSupports64 && arm64Asset != null) {
                return arm64Asset
            }
            // Fallback на universal, если arm64 не найден или неподдерживается
            return universalAsset ?: armV7Asset
        }

        // Ветка 2: Если запрошен ARMEABI-V7A
        if (preferredVariant == Variant.ARMEABI_V7A) {
            val deviceSupportsV7 = resolvedAbi.contains("arm") ||
                    (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.any { it.contains("arm", ignoreCase = true) })
            if (deviceSupportsV7 && armV7Asset != null) {
                return armV7Asset
            }
            // Fallback на universal
            return universalAsset ?: arm64Asset
        }

        // Ветка 3: Если запрошен UNIVERSAL
        if (preferredVariant == Variant.UNIVERSAL) {
            return universalAsset ?: arm64Asset ?: armV7Asset
        }

        // Ветка 4: Нет сохранённого варианта (legacy миграция 8/9.1/9.2 или fresh install)
        // Для старых версий и x86 эмуляторов строго universal APK
        return universalAsset
    }

    /**
     * Совместимый метод для старых вызовов без preferredVariant.
     */
    @JvmStatic
    fun selectAsset(assets: List<VoxReleaseAsset>, primaryAbi: String?): VoxReleaseAsset? {
        return selectAsset(assets, null, primaryAbi)
    }

    /**
     * Совместимый вызов без параметров ABI (выбирает universal APK).
     */
    @JvmStatic
    fun selectAsset(assets: List<VoxReleaseAsset>): VoxReleaseAsset? {
        return selectAsset(assets, null, null)
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
