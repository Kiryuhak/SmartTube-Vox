package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.json.JSONArray
import org.json.JSONObject

/**
 * Парсер информации о релизах и обновлениях из GitHub API и JSON-манифестов.
 */
object VoxOtaReleaseParser {

    /**
     * Разбирает тело ответа сервера (GitHub Releases API или custom manifest).
     * Защищён от HTML 404 страниц и некорректного JSON.
     */
    @JvmStatic
    fun parseRelease(jsonString: String?): VoxReleaseInfo {
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
            val root = JSONObject(trimmed)

            // Вариант 1: GitHub Releases API объект
            if (root.has("tag_name") && root.has("assets")) {
                return parseGitHubRelease(root)
            }

            // Вариант 2: Custom SmartTube VOX manifest (smarttube_vox.json)
            if (root.has("version") && (root.has("assets") || root.has("downloadUrl"))) {
                return parseVoxManifest(root)
            }

            // Вариант 3: Classic SmartTube manifest (версии как ключи верхнего уровня)
            return parseClassicManifest(root)

        } catch (e: Exception) {
            if (e is VoxOtaException) throw e
            throw VoxOtaException(
                VoxOtaErrorCode.RELEASE_PARSE_FAILED,
                "Failed to parse release JSON: ${e.message}",
                e
            )
        }
    }

    private fun parseGitHubRelease(obj: JSONObject): VoxReleaseInfo {
        val tagName = obj.getString("tag_name")
        val versionName = tagName.removePrefix("v").removePrefix("V")
        val body = obj.optString("body", "")
        val changelog = parseChangelogText(body)

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
            sha256SumsUrl = sha256SumsUrl
        )
    }

    private fun parseVoxManifest(obj: JSONObject): VoxReleaseInfo {
        val versionName = obj.getString("version")
        val tagName = "v$versionName"
        val changelog = mutableListOf<String>()

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
            sha256SumsUrl = sha256SumsUrl
        )
    }

    private fun parseClassicManifest(root: JSONObject): VoxReleaseInfo {
        // Находим новейшую версию среди ключей (кроме "package")
        val keys = root.keys()
        var bestVersion: String? = null
        var bestObj: JSONObject? = null

        while (keys.hasNext()) {
            val key = keys.next()
            if (key.equals("package", ignoreCase = true)) continue
            val versionObj = root.optJSONObject(key) ?: continue

            if (bestVersion == null || VoxVersionComparator.isUpdateAvailable(bestVersion, key)) {
                bestVersion = key
                bestObj = versionObj
            }
        }

        if (bestVersion == null || bestObj == null) {
            throw VoxOtaException(VoxOtaErrorCode.RELEASE_PARSE_FAILED, "No valid release version found in manifest")
        }

        val changelog = mutableListOf<String>()
        val chArr = bestObj.optJSONArray("changelog_ru") ?: bestObj.optJSONArray("changelog")
        if (chArr != null) {
            for (i in 0 until chArr.length()) {
                changelog.add(chArr.getString(i))
            }
        }

        val downloadUrl = bestObj.optString("downloadUrl", "")
            .ifEmpty { root.optJSONObject("package")?.optString("downloadUrl", "") ?: "" }

        val assets = if (downloadUrl.isNotEmpty()) {
            listOf(VoxReleaseAsset("SmartTube_vot_$bestVersion.apk", downloadUrl))
        } else emptyList()

        return VoxReleaseInfo(
            tagName = "v$bestVersion",
            versionName = bestVersion,
            changelog = changelog,
            assets = assets
        )
    }

    private fun parseChangelogText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotBlank() }
    }
}
