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
        return when {
            lower.contains("dev") -> VoxOtaChannelTier.DEV
            lower.contains("beta") || lower.contains("rc") || release?.isPrerelease == true -> VoxOtaChannelTier.BETA
            lower.contains("-vox.9.") && !lower.endsWith("-vox.9.0") && !lower.endsWith("-vox.9") -> VoxOtaChannelTier.BETA
            else -> VoxOtaChannelTier.STABLE
        }
    }

    fun extractCleanVoxVersion(raw: String?): String {
        if (raw.isNullOrBlank()) return "9.1"
        var clean = raw.trim()
        val voxIdx = clean.indexOf("-vox.")
        if (voxIdx >= 0) {
            clean = clean.substring(voxIdx + 5)
        } else if (clean.startsWith("v", ignoreCase = true)) {
            clean = clean.substring(1)
        }
        val qaIdx = clean.indexOf("-otaqa")
        if (qaIdx >= 0) clean = clean.substring(0, qaIdx)
        val betaIdx = clean.indexOf("-beta")
        if (betaIdx >= 0) clean = clean.substring(0, betaIdx)
        val devIdx = clean.indexOf("-dev")
        if (devIdx >= 0) clean = clean.substring(0, devIdx)
        val rcIdx = clean.indexOf("-rc")
        if (rcIdx >= 0) clean = clean.substring(0, rcIdx)
        return clean.ifBlank { "9.1" }
    }

    fun formatDisplayVersion(versionName: String?, tier: VoxOtaChannelTier): String {
        val clean = extractCleanVoxVersion(versionName)
        return when (tier) {
            VoxOtaChannelTier.STABLE -> "SmartTube Vox - $clean"
            VoxOtaChannelTier.BETA -> "SmartTube Vox - $clean Beta"
            VoxOtaChannelTier.DEV -> "SmartTube Vox - $clean Dev"
        }
    }

    fun formatCurrentVersion(currentRaw: String?): String {
        val clean = extractCleanVoxVersion(currentRaw)
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
        if (bytes <= 0L) return "368 МБ"
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
        val totalMb = if (totalBytes > 0L) totalBytes / (1024 * 1024) else 368L
        return "$readMb МБ / $totalMb МБ"
    }
}
