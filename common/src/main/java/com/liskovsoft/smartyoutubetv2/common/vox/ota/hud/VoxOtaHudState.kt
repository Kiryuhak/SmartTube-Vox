package com.liskovsoft.smartyoutubetv2.common.vox.ota.hud

import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxReleaseAsset
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxReleaseInfo
import java.io.File
import java.util.Locale

enum class VoxOtaChannelTier {
    STABLE,
    BETA,
    DEV
}

enum class VoxOtaProgressPhase {
    DOWNLOADING,
    VERIFYING,
    PREPARING
}

enum class VoxOtaInstallStep {
    PREPARING,
    INSTALLING,
    FINALIZING
}

sealed class VoxOtaHudState {
    object Idle : VoxOtaHudState()

    data class Offer(
        val release: VoxReleaseInfo,
        val asset: VoxReleaseAsset,
        val tier: VoxOtaChannelTier,
        val displayVersion: String,
        val currentVersionText: String,
        val changelogBullets: List<String>
    ) : VoxOtaHudState()

    data class Confirm(
        val release: VoxReleaseInfo,
        val asset: VoxReleaseAsset,
        val displayVersion: String,
        val fileSizeText: String
    ) : VoxOtaHudState()

    data class Progress(
        val release: VoxReleaseInfo,
        val asset: VoxReleaseAsset,
        val displayVersion: String,
        val phase: VoxOtaProgressPhase,
        val percent: Int,
        val bytesRead: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Double,
        val isMinimized: Boolean = false
    ) : VoxOtaHudState()

    data class Complete(
        val release: VoxReleaseInfo,
        val asset: VoxReleaseAsset,
        val displayVersion: String,
        val apkFile: File
    ) : VoxOtaHudState()

    data class Installing(
        val release: VoxReleaseInfo,
        val asset: VoxReleaseAsset,
        val displayVersion: String,
        val apkFile: File,
        val percent: Int,
        val step: VoxOtaInstallStep
    ) : VoxOtaHudState()
}

object VoxOtaDisplayHelper {

    fun detectTier(versionName: String?, release: VoxReleaseInfo?): VoxOtaChannelTier {
        val lower = (versionName ?: release?.versionName ?: "").lowercase(Locale.ROOT)
        val withoutUniversal = lower.replace("-universal", "")
        return when {
            lower.contains("dev") -> VoxOtaChannelTier.DEV
            lower.contains("beta") || lower.contains("rc") || release?.isPrerelease == true -> VoxOtaChannelTier.BETA
            withoutUniversal.contains("-vox.9.") && !withoutUniversal.endsWith("-vox.9.0") && !withoutUniversal.endsWith("-vox.9") && !withoutUniversal.endsWith("-vox.9.1") && !withoutUniversal.endsWith("-vox.9.2") -> VoxOtaChannelTier.BETA
            else -> VoxOtaChannelTier.STABLE
        }
    }

    fun cleanVersionName(raw: String?): String {
        if (raw.isNullOrBlank()) return "32.71-vox.10"
        var clean = raw.trim()
        if (clean.startsWith("v", ignoreCase = true) && clean.length > 1 && clean[1].isDigit()) {
            clean = clean.substring(1)
        }
        val uniIdx = clean.indexOf("-universal", ignoreCase = true)
        if (uniIdx >= 0) clean = clean.substring(0, uniIdx)
        val arm64Idx = clean.indexOf("-arm64-v8a", ignoreCase = true)
        if (arm64Idx >= 0) clean = clean.substring(0, arm64Idx)
        val arm32Idx = clean.indexOf("-armeabi-v7a", ignoreCase = true)
        if (arm32Idx >= 0) clean = clean.substring(0, arm32Idx)
        val qaIdx = clean.indexOf("-otaqa", ignoreCase = true)
        if (qaIdx >= 0) clean = clean.substring(0, qaIdx)
        return clean.ifBlank { "32.71-vox.10" }
    }

    fun extractCleanVoxVersion(raw: String?): String {
        return cleanVersionName(raw)
    }

    fun formatDisplayVersion(versionName: String?, tier: VoxOtaChannelTier): String {
        val clean = cleanVersionName(versionName)
        val baseDisplay = if (clean.startsWith("SmartTube VOX", ignoreCase = true)) clean else "SmartTube VOX $clean"
        return when (tier) {
            VoxOtaChannelTier.STABLE -> baseDisplay
            VoxOtaChannelTier.BETA -> if (baseDisplay.contains("Beta", ignoreCase = true) || baseDisplay.contains("rc", ignoreCase = true)) baseDisplay else "$baseDisplay Beta"
            VoxOtaChannelTier.DEV -> if (baseDisplay.contains("Dev", ignoreCase = true)) baseDisplay else "$baseDisplay Dev"
        }
    }

    fun formatCurrentVersion(currentRaw: String?): String {
        val clean = cleanVersionName(currentRaw)
        return "Текущая версия: $clean"
    }

    fun getDefaultBullets(tier: VoxOtaChannelTier): List<String> {
        return when (tier) {
            VoxOtaChannelTier.STABLE -> listOf(
                "• Улучшения стабильности",
                "• Исправления ошибок",
                "• Новые функции"
            )
            VoxOtaChannelTier.BETA -> listOf(
                "• Новые функции (Beta)",
                "• Улучшения перевода",
                "• Исправления ошибок"
            )
            VoxOtaChannelTier.DEV -> listOf(
                "• Экспериментальные функции",
                "• Улучшения производительности",
                "• Исправления ошибок"
            )
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1000) {
            String.format(Locale.US, "%.1f ГБ", mb / 1024.0)
        } else {
            String.format(Locale.US, "%d МБ", Math.round(mb))
        }
    }

    fun formatSpeed(bytesPerSec: Double): String {
        if (bytesPerSec <= 0.0) return "0,0 МБ/с"
        val mbPerSec = bytesPerSec / (1024.0 * 1024.0)
        return if (mbPerSec >= 0.1) {
            String.format(Locale.forLanguageTag("ru"), "%.1f МБ/с", mbPerSec)
        } else {
            val kbPerSec = bytesPerSec / 1024.0
            String.format(Locale.forLanguageTag("ru"), "%d КБ/с", Math.round(kbPerSec))
        }
    }

    fun formatProgressSize(bytesRead: Long, totalBytes: Long): String {
        val readMb = bytesRead / (1024 * 1024)
        return if (totalBytes > 0L) {
            val totalMb = totalBytes / (1024 * 1024)
            "$readMb МБ / $totalMb МБ"
        } else {
            "$readMb МБ"
        }
    }
}
