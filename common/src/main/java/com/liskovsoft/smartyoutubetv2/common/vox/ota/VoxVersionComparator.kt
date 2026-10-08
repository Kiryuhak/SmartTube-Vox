package com.liskovsoft.smartyoutubetv2.common.vox.ota

/**
 * Парсер и компаратор версий SmartTube VOX.
 *
 * Правила:
 * - 6.1 < 7 -> true (доступно обновление);
 * - 7 == 7 -> false (обновление не требуется);
 * - 7 stable не должен обновляться на 8-dev через стабильный канал;
 * - префиксы v, vox, vot поддерживаются прозрачно.
 */
object VoxVersionComparator {

    data class ParsedVersion(
        val major: Int,
        val minor: Int,
        val voxMajor: Int,
        val voxMinor: Int,
        val isDev: Boolean
    ) : Comparable<ParsedVersion> {
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
            // Если версии равны по числам: релиз новее dev
            if (this.isDev && !other.isDev) return -1
            if (!this.isDev && other.isDev) return 1
            return 0
        }
    }

    /**
     * Разбирает строку версии в структурированный объект.
     * Примеры: "32.56-vox.6.1", "32.56-vox.7", "32.56-vot.6.1", "32.56-vox.8-dev", "v32.56-vox.7", "7", "6.1"
     */
    @JvmStatic
    fun parseVersion(versionStr: String?): ParsedVersion? {
        if (versionStr.isNullOrBlank()) return null
        val clean = versionStr.trim().removePrefix("v").removePrefix("V")

        // Регулярное выражение для полных версий: 32.56-vox.7 или 32.56-vot.6.1-dev
        val fullPattern = Regex("(\\d+)\\.(\\d+)-(?:vox|vot)\\.(\\d+)(?:\\.(\\d+))?(-dev)?", RegexOption.IGNORE_CASE)
        val fullMatch = fullPattern.find(clean)
        if (fullMatch != null) {
            val major = fullMatch.groupValues[1].toIntOrNull() ?: 0
            val minor = fullMatch.groupValues[2].toIntOrNull() ?: 0
            val voxMajor = fullMatch.groupValues[3].toIntOrNull() ?: 0
            val voxMinor = fullMatch.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val isDev = fullMatch.groupValues[5].isNotEmpty()
            return ParsedVersion(major, minor, voxMajor, voxMinor, isDev)
        }

        // Регулярное выражение для vox/vot без базовой версии: vox.7 или vot.6.1
        val voxPattern = Regex("(?:vox|vot)\\.(\\d+)(?:\\.(\\d+))?(-dev)?", RegexOption.IGNORE_CASE)
        val voxMatch = voxPattern.find(clean)
        if (voxMatch != null) {
            val voxMajor = voxMatch.groupValues[1].toIntOrNull() ?: 0
            val voxMinor = voxMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val isDev = voxMatch.groupValues[3].isNotEmpty()
            return ParsedVersion(32, 56, voxMajor, voxMinor, isDev)
        }

        // Простой формат: 7 или 6.1 или 8-dev
        val simplePattern = Regex("(\\d+)(?:\\.(\\d+))?(-dev)?")
        val simpleMatch = simplePattern.find(clean)
        if (simpleMatch != null) {
            val voxMajor = simpleMatch.groupValues[1].toIntOrNull() ?: 0
            val voxMinor = simpleMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val isDev = simpleMatch.groupValues[3].isNotEmpty()
            return ParsedVersion(32, 56, voxMajor, voxMinor, isDev)
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
     * Учитывает политику каналов: на стабильном канале dev-сборки игнорируются.
     */
    @JvmStatic
    @JvmOverloads
    fun isUpdateAvailable(currentVersion: String?, targetVersion: String?, isBetaChannel: Boolean = false): Boolean {
        val curr = parseVersion(currentVersion) ?: return false
        val target = parseVersion(targetVersion) ?: return false

        // Правило: 7 stable не должен автоматически обновляться на 8-dev через стабильный канал
        if (!isBetaChannel && target.isDev && !curr.isDev) {
            return false
        }

        return curr < target
    }
}
