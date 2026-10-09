package com.liskovsoft.smartyoutubetv2.common.vox.ota

/**
 * Стадии релиза SmartTube VOX.
 * Иерархия: DEV < ALPHA < BETA < RC < STABLE.
 */
enum class VoxReleaseStage(val level: Int) {
    DEV(0),
    ALPHA(1),
    BETA(2),
    RC(3),
    STABLE(4);
}

/**
 * Парсер и компаратор версий SmartTube VOX.
 *
 * Правила:
 * - 6.1 < 7 -> true (доступно обновление);
 * - 7 == 7 -> false (обновление не требуется);
 * - 8.1 < 8.2-beta.1 -> true (на тестовом канале);
 * - 8.1 стабильный игнорирует 8.2-beta.1 на стабильном канале;
 * - 8.2-beta.1 < 8.2-beta.2 < 8.2-rc.1 < 8.2 (финальный релиз);
 * - Защита от даунгрейда: более старая версия никогда не предлагается как обновление;
 * - Префиксы v, vox, vot поддерживаются прозрачно.
 */
object VoxVersionComparator {

    data class ParsedVersion(
        val major: Int,
        val minor: Int,
        val voxMajor: Int,
        val voxMinor: Int,
        val stage: VoxReleaseStage = VoxReleaseStage.STABLE,
        val stageNumber: Int = 0
    ) : Comparable<ParsedVersion> {

        val isDev: Boolean
            get() = stage == VoxReleaseStage.DEV

        val rcNumber: Int?
            get() = if (stage == VoxReleaseStage.RC) stageNumber else null

        val isPreRelease: Boolean
            get() = stage != VoxReleaseStage.STABLE

        override fun compareTo(other: ParsedVersion): Int {
            if (this.voxMajor != other.voxMajor) {
                return this.voxMajor.compareTo(other.voxMajor)
            }
            if (this.voxMinor != other.voxMinor) {
                return this.voxMinor.compareTo(other.voxMinor)
            }
            if (this.major != other.major) {
                return this.major.compareTo(other.major)
            }
            if (this.minor != other.minor) {
                return this.minor.compareTo(other.minor)
            }
            if (this.stage != other.stage) {
                return this.stage.level.compareTo(other.stage.level)
            }
            return this.stageNumber.compareTo(other.stageNumber)
        }
    }

    private fun parseStageTag(tagStr: String?): Pair<VoxReleaseStage, Int> {
        if (tagStr.isNullOrBlank()) return Pair(VoxReleaseStage.STABLE, 0)
        val clean = tagStr.lowercase().trim().removePrefix("-").removePrefix(".")
        return when {
            clean == "dev" -> Pair(VoxReleaseStage.DEV, 0)
            clean.startsWith("alpha") -> {
                val num = Regex("\\d+").find(clean)?.value?.toIntOrNull() ?: 1
                Pair(VoxReleaseStage.ALPHA, num)
            }
            clean.startsWith("beta") -> {
                val num = Regex("\\d+").find(clean)?.value?.toIntOrNull() ?: 1
                Pair(VoxReleaseStage.BETA, num)
            }
            clean.startsWith("rc") -> {
                val num = Regex("\\d+").find(clean)?.value?.toIntOrNull() ?: 1
                Pair(VoxReleaseStage.RC, num)
            }
            else -> Pair(VoxReleaseStage.STABLE, 0)
        }
    }

    /**
     * Разбирает строку версии в структурированный объект.
     * Примеры: "32.56-vox.8.2-beta.1", "32.56-vox.8.1", "32.56-vox.7", "32.56-vot.6.1", "32.56-vox.8-dev", "32.56-vox.8-rc1", "v32.56-vox.7", "7", "6.1"
     */
    @JvmStatic
    fun parseVersion(versionStr: String?): ParsedVersion? {
        if (versionStr.isNullOrBlank()) return null
        val clean = versionStr.trim().removePrefix("v").removePrefix("V")

        // Регулярное выражение для полных версий: 32.56-vox.8.2-beta.1, 32.56-vox.7, 32.56-vox.8-dev или 32.56-vox.8-rc1
        val fullPattern = Regex("(\\d+)\\.(\\d+)-(?:vox|vot)\\.(\\d+)(?:\\.(\\d+))?(?:-([a-zA-Z0-9.]+))?", RegexOption.IGNORE_CASE)
        val fullMatch = fullPattern.find(clean)
        if (fullMatch != null) {
            val major = fullMatch.groupValues[1].toIntOrNull() ?: 0
            val minor = fullMatch.groupValues[2].toIntOrNull() ?: 0
            val voxMajor = fullMatch.groupValues[3].toIntOrNull() ?: 0
            val voxMinor = fullMatch.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val rawTag = fullMatch.groupValues[5].takeIf { it.isNotEmpty() }
            val (stage, stageNum) = parseStageTag(rawTag)
            return ParsedVersion(major, minor, voxMajor, voxMinor, stage, stageNum)
        }

        // Регулярное выражение для vox/vot без базовой версии: vox.8.2-beta.1, vox.7, vox.8-dev или vox.8-rc1
        val voxPattern = Regex("(?:vox|vot)\\.(\\d+)(?:\\.(\\d+))?(?:-([a-zA-Z0-9.]+))?", RegexOption.IGNORE_CASE)
        val voxMatch = voxPattern.find(clean)
        if (voxMatch != null) {
            val voxMajor = voxMatch.groupValues[1].toIntOrNull() ?: 0
            val voxMinor = voxMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val rawTag = voxMatch.groupValues[3].takeIf { it.isNotEmpty() }
            val (stage, stageNum) = parseStageTag(rawTag)
            return ParsedVersion(32, 56, voxMajor, voxMinor, stage, stageNum)
        }

        // Простой формат: 8.2-beta.1 или 7 или 6.1 или 8-dev или 8-rc1
        val simplePattern = Regex("(\\d+)(?:\\.(\\d+))?(?:-([a-zA-Z0-9.]+))?", RegexOption.IGNORE_CASE)
        val simpleMatch = simplePattern.find(clean)
        if (simpleMatch != null) {
            val voxMajor = simpleMatch.groupValues[1].toIntOrNull() ?: 0
            val voxMinor = simpleMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val rawTag = simpleMatch.groupValues[3].takeIf { it.isNotEmpty() }
            val (stage, stageNum) = parseStageTag(rawTag)
            return ParsedVersion(32, 56, voxMajor, voxMinor, stage, stageNum)
        }

        return null
    }

    /**
     * Сравнивает две версии. Возвращает:
     * < 0 если v1 < v2
     * = 0 если v1 == v2
     * > 0 если v1 > v2
     */
    @JvmStatic
    fun compare(v1: String?, v2: String?): Int {
        val p1 = parseVersion(v1) ?: throw VoxOtaException(VoxOtaErrorCode.VERSION_PARSE_FAILED, "Cannot parse current version: $v1")
        val p2 = parseVersion(v2) ?: throw VoxOtaException(VoxOtaErrorCode.VERSION_PARSE_FAILED, "Cannot parse target version: $v2")
        return p1.compareTo(p2)
    }

    /**
     * Проверяет, доступно ли обновление targetVersion для currentVersion.
     * Учитывает политику каналов:
     * - На стабильном канале pre-release сборки (dev/beta/rc) игнорируются для пользователей стабильной ветки.
     * - На тестовом канале доступны любые более новые версии (pre-release и stable).
     * - Защита от даунгрейда: более старая версия никогда не предлагается как обновление.
     */
    @JvmStatic
    @JvmOverloads
    fun isUpdateAvailable(currentVersion: String?, targetVersion: String?, isBetaChannel: Boolean = false): Boolean {
        val curr = parseVersion(currentVersion) ?: return false
        val target = parseVersion(targetVersion) ?: return false

        // Правило защиты от даунгрейда
        if (curr >= target) {
            return false
        }

        // Правило канала: стабильная версия не должна автоматически обновляться на pre-release (dev/beta/rc) через стабильный канал
        if (!isBetaChannel && target.isPreRelease && !curr.isPreRelease) {
            return false
        }

        return true
    }
}
