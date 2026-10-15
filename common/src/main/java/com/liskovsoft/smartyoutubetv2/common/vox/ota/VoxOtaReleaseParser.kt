package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.json.JSONArray
import org.json.JSONObject

/**
 * Парсер информации о релизах и обновлениях из GitHub API и JSON-манифестов.
 *
 * Поддерживает:
 * - Выбор LATEST ALLOWED VERSION (прямые skip-обновления без промежуточных шагов);
 * - Строгое разделение каналов (Stable видит только 9, 10, 11; Beta видит 9.1...9.x, 10...);
 * - Монотонность versionCode (candidate.versionCode > installed.versionCode);
 * - Защиту от даунгрейда;
 * - Извлечение versionCode из JSON-полей и текста changelog.
 */
object VoxOtaReleaseParser {

    /**
     * Разбирает тело ответа сервера (GitHub Releases API или custom manifest).
     * Защищён от HTML 404 страниц и некорректного JSON.
     */
    @JvmStatic
    @JvmOverloads
    fun parseRelease(
        jsonString: String?,
        isBetaChannel: Boolean = false,
        currentVersion: String? = null,
        installedVersionCode: Long = 0L
    ): VoxReleaseInfo {
        if (jsonString.isNullOrBlank()) {
            throw VoxOtaException(VoxOtaErrorCode.RELEASE_PARSE_FAILED, "Empty release response")
        }

        val trimmed = jsonString.trim()
        if (trimmed.startsWith("<") || trimmed.contains("<!DOCTYPE", ignoreCase = true)) {
            throw VoxOtaException(
                VoxOtaErrorCode.RELEASE_PARSE_FAILED,
                "Received HTML page instead of JSON release data (HTTP 404/Error)"
            )
        }

        try {
            // Вариант 0: Массив релизов GitHub API
            if (trimmed.startsWith("[")) {
                val allReleases = parseAllReleases(trimmed)
                if (allReleases.isEmpty()) {
                    throw VoxOtaException(VoxOtaErrorCode.RELEASE_NOT_FOUND, "Release list is empty")
                }

                val best = selectBestRelease(allReleases, currentVersion, isBetaChannel, installedVersionCode)
                if (best != null) {
                    return best
                }

                // Если не найден подходящий релиз в списке
                val channelName = if (isBetaChannel) "beta" else "stable"
                throw VoxOtaException(
                    VoxOtaErrorCode.NO_UPDATE,
                    "No compatible update found in release list for version: $currentVersion, channel: $channelName"
                )
            }

            val root = JSONObject(trimmed)

            // Вариант 1: GitHub Releases API объект (одиночный релиз)
            if (root.has("tag_name") && root.has("assets")) {
                val release = parseGitHubRelease(root)
                val parsed = VoxVersionComparator.parseVersion(release.versionName)
                val isPre = release.isPrerelease || (parsed != null && parsed.isPreRelease)

                if (!isBetaChannel && isPre) {
                    throw VoxOtaException(
                        VoxOtaErrorCode.CHANNEL_MISMATCH,
                        "Release is pre-release but requested stable channel"
                    )
                }
                return release
            }

            // Вариант 2: Custom SmartTube VOX manifest (smarttube_vox.json)
            if (root.has("version") && (root.has("assets") || root.has("downloadUrl"))) {
                return parseVoxManifest(root)
            }

            // Вариант 3: Classic SmartTube manifest (версии как ключи верхнего уровня)
            return parseClassicManifest(root, isBetaChannel, currentVersion, installedVersionCode)

        } catch (e: Exception) {
            if (e is VoxOtaException) throw e
            throw VoxOtaException(
                VoxOtaErrorCode.RELEASE_PARSE_FAILED,
                "Failed to parse release JSON: ${e.message}",
                VoxOtaStage.PARSE,
                null,
                false,
                e
            )
        }
    }

    /**
     * Разбирает JSON-массив релизов GitHub API в список объектов VoxReleaseInfo.
     */
    @JvmStatic
    fun parseAllReleases(jsonString: String?): List<VoxReleaseInfo> {
        if (jsonString.isNullOrBlank()) return emptyList()
        val trimmed = jsonString.trim()
        if (!trimmed.startsWith("[")) {
            return try {
                listOf(parseRelease(trimmed))
            } catch (e: Exception) {
                emptyList()
            }
        }

        val result = mutableListOf<VoxReleaseInfo>()
        val array = JSONArray(trimmed)
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optBoolean("draft", false)) continue
            if (item.has("tag_name") && item.has("assets")) {
                try {
                    result.add(parseGitHubRelease(item))
                } catch (e: Exception) {
                    // skip malformed release entry
                }
            }
        }
        return result
    }

    /**
     * Выбирает НАИЛУЧШИЙ разрешённый релиз (LATEST ALLOWED VERSION) с поддержкой direct skip.
     * Не требует последовательной установки промежуточных версий.
     */
    @JvmStatic
    @JvmOverloads
    fun selectBestRelease(
        releases: List<VoxReleaseInfo>,
        currentVersion: String?,
        isBetaChannel: Boolean = false,
        installedVersionCode: Long = 0L
    ): VoxReleaseInfo? {
        val candidates = releases.filter { release ->
            val parsed = VoxVersionComparator.parseVersion(release.versionName) ?: return@filter false

            // Правило стабильного канала: стабильный канал принимает только стабильные релизы (без pre-release флага и voxMinor == 0)
            if (!isBetaChannel && (release.isPrerelease || parsed.isPreRelease)) {
                return@filter false
            }

            // Проверка, что релиз новее установленного (если текущая версия известна)
            if (currentVersion != null) {
                if (!VoxVersionComparator.isUpdateAvailable(currentVersion, release.versionName, isBetaChannel)) {
                    return@filter false
                }
            }

            // Инвариант монотонности versionCode: candidate.versionCode > installedVersionCode
            if (installedVersionCode > 0L && release.versionCode != null && release.versionCode <= installedVersionCode) {
                return@filter false
            }

            true
        }

        // Выбираем релиз с максимальной версией
        return candidates.maxWithOrNull(Comparator { r1, r2 ->
            // Если у обоих релизов задан versionCode, сначала сравниваем его
            if (r1.versionCode != null && r2.versionCode != null && r1.versionCode != r2.versionCode) {
                return@Comparator r1.versionCode.compareTo(r2.versionCode)
            }
            val p1 = VoxVersionComparator.parseVersion(r1.versionName)
            val p2 = VoxVersionComparator.parseVersion(r2.versionName)
            if (p1 != null && p2 != null) {
                return@Comparator p1.compareTo(p2)
            }
            r1.versionName.compareTo(r2.versionName)
        })
    }

    private fun parseGitHubRelease(obj: JSONObject): VoxReleaseInfo {
        val tagName = obj.getString("tag_name")
        val versionName = tagName.removePrefix("v").removePrefix("V")
        val body = obj.optString("body", "")
        val changelog = parseChangelogText(body)
        val isPrerelease = obj.optBoolean("prerelease", false)

        val versionCode = if (obj.has("versionCode")) {
            obj.optLong("versionCode", 0L).takeIf { it > 0L }
        } else {
            val match = Regex("""(?:versionCode|code)[:=\s]+(\d+)""", RegexOption.IGNORE_CASE).find(body)
            match?.groupValues?.get(1)?.toLongOrNull()
        }

        val assetsArray = obj.getJSONArray("assets")
        val assets = mutableListOf<VoxReleaseAsset>()
        var sha256SumsUrl: String? = null

        for (i in 0 until assetsArray.length()) {
            val assetObj = assetsArray.getJSONObject(i)
            val name = assetObj.getString("name")
            val downloadUrl = assetObj.getString("browser_download_url")
            val size = assetObj.optLong("size", 0L)
            assets.add(VoxReleaseAsset(name, downloadUrl, size))

            if (name.equals("SHA256SUMS.txt", ignoreCase = true) || name.endsWith(".sha256", ignoreCase = true)) {
                sha256SumsUrl = downloadUrl
            }
        }

        return VoxReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            changelog = changelog,
            assets = assets,
            sha256SumsUrl = sha256SumsUrl,
            isPrerelease = isPrerelease,
            versionCode = versionCode
        )
    }

    private fun parseVoxManifest(obj: JSONObject): VoxReleaseInfo {
        val versionName = obj.getString("version")
        val tagName = "v$versionName"
        val changelog = mutableListOf<String>()

        val isPrerelease = obj.optBoolean("prerelease", false) ||
                (VoxVersionComparator.parseVersion(versionName)?.isPreRelease ?: false)

        val versionCode = if (obj.has("versionCode")) {
            obj.optLong("versionCode", 0L).takeIf { it > 0L }
        } else null

        if (obj.has("changelog")) {
            val arr = obj.optJSONArray("changelog")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    changelog.add(arr.getString(i))
                }
            } else {
                changelog.addAll(parseChangelogText(obj.optString("changelog", "")))
            }
        }

        val assets = mutableListOf<VoxReleaseAsset>()
        var sha256SumsUrl: String? = null

        if (obj.has("assets")) {
            val arr = obj.getJSONArray("assets")
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val name = a.getString("name")
                val url = a.getString("downloadUrl")
                val size = a.optLong("size", 0L)
                assets.add(VoxReleaseAsset(name, url, size))
                if (name.equals("SHA256SUMS.txt", ignoreCase = true)) {
                    sha256SumsUrl = url
                }
            }
        } else if (obj.has("downloadUrl")) {
            assets.add(VoxReleaseAsset("SmartTube_vot_universal.apk", obj.getString("downloadUrl")))
        }

        return VoxReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            changelog = changelog,
            assets = assets,
            sha256SumsUrl = sha256SumsUrl,
            isPrerelease = isPrerelease,
            versionCode = versionCode
        )
    }

    private fun parseClassicManifest(
        root: JSONObject,
        isBetaChannel: Boolean = false,
        currentVersion: String? = null,
        installedVersionCode: Long = 0L
    ): VoxReleaseInfo {
        val keys = root.keys()
        val releases = mutableListOf<VoxReleaseInfo>()

        while (keys.hasNext()) {
            val key = keys.next()
            if (key.equals("package", ignoreCase = true)) continue
            val versionObj = root.optJSONObject(key) ?: continue

            val changelog = mutableListOf<String>()
            val chArr = versionObj.optJSONArray("changelog_ru") ?: versionObj.optJSONArray("changelog")
            if (chArr != null) {
                for (i in 0 until chArr.length()) {
                    changelog.add(chArr.getString(i))
                }
            }

            val downloadUrl = versionObj.optString("downloadUrl", "")
                .ifEmpty { root.optJSONObject("package")?.optString("downloadUrl", "") ?: "" }

            val assets = if (downloadUrl.isNotEmpty()) {
                listOf(VoxReleaseAsset("SmartTube_vot_$key.apk", downloadUrl))
            } else emptyList()

            val versionCode = versionObj.optLong("versionCode", 0L).takeIf { it > 0L }
            val parsed = VoxVersionComparator.parseVersion(key)
            val isPrerelease = versionObj.optBoolean("prerelease", false) || (parsed?.isPreRelease ?: false)

            releases.add(
                VoxReleaseInfo(
                    tagName = "v$key",
                    versionName = key,
                    changelog = changelog,
                    assets = assets,
                    isPrerelease = isPrerelease,
                    versionCode = versionCode
                )
            )
        }

        if (releases.isEmpty()) {
            throw VoxOtaException(VoxOtaErrorCode.RELEASE_PARSE_FAILED, "No valid release version found in manifest")
        }

        val best = selectBestRelease(releases, currentVersion, isBetaChannel, installedVersionCode)
        if (best != null) {
            return best
        }

        // Если currentVersion не задан, возвращаем максимальный по каналу
        val fallback = releases.filter { !isBetaChannel && !it.isPrerelease || isBetaChannel }
            .maxWithOrNull(Comparator { r1, r2 ->
                val p1 = VoxVersionComparator.parseVersion(r1.versionName)
                val p2 = VoxVersionComparator.parseVersion(r2.versionName)
                if (p1 != null && p2 != null) p1.compareTo(p2) else r1.versionName.compareTo(r2.versionName)
            })

        return fallback ?: releases.first()
    }

    private fun parseChangelogText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotBlank() }
    }
}
