package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Утилита для глубокой санитизации и очистки диагностических данных перед логированием,
 * экспортом или отправкой.
 * Гарантирует удаление любых токенов, ключей, паролей, cookies, заголовков авторизации и приватных IP.
 */
object VoxDiagnosticSanitizer {

    private val BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9_\\-\\.]+")
    private val TOKEN_KEY_VAL_PATTERN = Pattern.compile("(?i)\\b(access_token|refresh_token|client_secret|client_id|device_code|token|auth_token|sessionId|session_token)=[^&\\s\"']+")
    private val URL_AUTH_PARAM_PATTERN = Pattern.compile("(?i)([?&](?:key|sig|signature|auth|token|session|oauth_token)=)[^&\\s\"']+")
    private val AUTH_HEADER_PATTERN = Pattern.compile("(?i)(Authorization|Cookie|Set-Cookie):\\s*[^\\r\\n]+")
    private val PASSWORD_PATTERN = Pattern.compile("(?i)\\b(password|passwd|pwd|secret)\\s*[:=]\\s*[^\\s,;\"']+")
    private val PRIVATE_IPV4_PATTERN = Pattern.compile("\\b(?:192\\.168\\.\\d{1,3}\\.\\d{1,3}|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|172\\.(?:1[6-9]|2[0-9]|3[0-1])\\.\\d{1,3}\\.\\d{1,3})\\b")

    private val FORBIDDEN_JSON_KEYS = setOf(
        "password",
        "passwd",
        "pwd",
        "secret",
        "token",
        "access_token",
        "refresh_token",
        "auth_token",
        "device_code",
        "authorization",
        "cookie",
        "cookies",
        "session",
        "session_id",
        "sessionid"
    )

    @JvmStatic
    fun sanitize(input: String?): String {
        if (input.isNullOrBlank()) return ""

        var result: String = input
        result = BEARER_PATTERN.matcher(result).replaceAll("Bearer [REDACTED]")
        result = TOKEN_KEY_VAL_PATTERN.matcher(result).replaceAll("$1=[REDACTED]")
        result = URL_AUTH_PARAM_PATTERN.matcher(result).replaceAll("$1[REDACTED]")
        result = AUTH_HEADER_PATTERN.matcher(result).replaceAll("$1: [REDACTED]")
        result = PASSWORD_PATTERN.matcher(result).replaceAll("$1=[REDACTED]")
        result = PRIVATE_IPV4_PATTERN.matcher(result).replaceAll("[LOCAL_IP]")

        return result
    }

    @JvmStatic
    fun isSafePayload(jsonString: String?): Boolean {
        if (jsonString.isNullOrBlank()) return false
        return try {
            val json = JSONObject(jsonString)
            !containsForbiddenKeys(json)
        } catch (e: Exception) {
            false
        }
    }

    private fun containsForbiddenKeys(obj: JSONObject): Boolean {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (FORBIDDEN_JSON_KEYS.contains(key.lowercase())) {
                return true
            }
            val value = obj.opt(key)
            if (value is JSONObject) {
                if (containsForbiddenKeys(value)) return true
            } else if (value is JSONArray) {
                for (i in 0 until value.length()) {
                    val elem = value.opt(i)
                    if (elem is JSONObject && containsForbiddenKeys(elem)) return true
                }
            }
        }
        return false
    }

    @JvmStatic
    fun sanitizeJson(jsonString: String?): String {
        if (jsonString.isNullOrBlank()) return "{}"
        val sanitizedRaw = sanitize(jsonString)
        return try {
            val json = JSONObject(sanitizedRaw)
            stripForbiddenKeysRecursive(json)
            json.toString()
        } catch (e: Exception) {
            sanitizedRaw
        }
    }

    private fun stripForbiddenKeysRecursive(obj: JSONObject) {
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            if (FORBIDDEN_JSON_KEYS.contains(key.lowercase())) {
                obj.remove(key)
                continue
            }
            val value = obj.opt(key)
            if (value is JSONObject) {
                stripForbiddenKeysRecursive(value)
            } else if (value is JSONArray) {
                for (i in 0 until value.length()) {
                    val elem = value.opt(i)
                    if (elem is JSONObject) {
                        stripForbiddenKeysRecursive(elem)
                    }
                }
            }
        }
    }
}
