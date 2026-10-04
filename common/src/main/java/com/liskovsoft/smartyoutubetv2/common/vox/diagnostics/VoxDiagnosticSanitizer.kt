package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Утилита для глубокой санитизации и очистки диагностических данных перед логированием,
 * сохранением на диск, экспортом в буфер обмена или отправкой разработчикам.
 * 
 * Гарантирует удаление любых токенов, ключей, паролей, cookies, заголовков авторизации,
 * подписанных query-параметров, IP-адресов, email, MAC-адресов и уникальных идентификаторов устройства.
 */
object VoxDiagnosticSanitizer {

    private val BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9_\\-\\.]+")
    private val TOKEN_KEY_VAL_PATTERN = Pattern.compile("(?i)\\b(access_token|refresh_token|id_token|client_secret|client_id|device_code|user_code|api_key|apikey|private_key|token|auth_token|sessionId|session_token|session_id)\\s*[:=]\\s*[^&\\s\"',;]+")
    private val URL_AUTH_PARAM_PATTERN = Pattern.compile("(?i)([?&](?:key|sig|signature|auth|token|session|oauth_token|expire|expires|sparams)=)[^&\\s\"',;]+")
    private val AUTH_HEADER_PATTERN = Pattern.compile("(?i)(Authorization|Cookie|Set-Cookie|Proxy-Authorization):\\s*[^\\r\\n]+")
    private val PASSWORD_PATTERN = Pattern.compile("(?i)\\b(password|passwd|pwd|secret|proxy_password|proxypassword)\\s*[:=]\\s*[^\\s,;\"']+")
    
    // IP Patterns: Private & Public IPv4, and IPv6
    private val IPV4_PATTERN = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b")
    private val IPV6_PATTERN = Pattern.compile("(?i)\\b(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}\\b|\\b(?:[0-9a-f]{1,4}:){1,7}:\\b|\\b:(?::[0-9a-f]{1,4}){1,7}\\b")

    // Sensitive identifiers
    private val EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+")
    private val MAC_PATTERN = Pattern.compile("(?i)\\b(?:[0-9A-F]{2}[:-]){5}[0-9A-F]{2}\\b")
    private val ANDROID_ID_PATTERN = Pattern.compile("(?i)\\b(android_id|ssaid|serial|deviceId|device_id)\\s*[:=]\\s*[a-zA-Z0-9]{8,}\\b")

    // Full URL query stripping pattern: https://domain/path?query... -> https://domain/path
    private val FULL_URL_QUERY_PATTERN = Pattern.compile("(https?://[^\\s?\"'<>]+)\\?[^\\s\"'<>]+")

    private val FORBIDDEN_JSON_KEYS = setOf(
        "password",
        "passwd",
        "pwd",
        "secret",
        "token",
        "access_token",
        "refresh_token",
        "id_token",
        "auth_token",
        "device_code",
        "user_code",
        "client_secret",
        "api_key",
        "apikey",
        "private_key",
        "authorization",
        "proxy_authorization",
        "proxy_password",
        "proxypassword",
        "cookie",
        "cookies",
        "set-cookie",
        "session",
        "session_id",
        "sessionid",
        "account_id",
        "email",
        "android_id",
        "ssaid",
        "serial",
        "mac"
    )

    @JvmStatic
    fun sanitize(input: String?): String {
        if (input.isNullOrBlank()) return ""

        var result: String = input
        
        // 1. Strip URL query parameters first (e.g. YouTube media streams with signed signatures/tokens)
        result = FULL_URL_QUERY_PATTERN.matcher(result).replaceAll("$1")

        // 2. Redact auth headers & bearer tokens
        result = AUTH_HEADER_PATTERN.matcher(result).replaceAll("$1: [REDACTED]")
        result = BEARER_PATTERN.matcher(result).replaceAll("Bearer [REDACTED]")

        // 3. Redact key-value secrets & tokens
        result = TOKEN_KEY_VAL_PATTERN.matcher(result).replaceAll("$1=[REDACTED]")
        result = PASSWORD_PATTERN.matcher(result).replaceAll("$1=[REDACTED]")
        result = URL_AUTH_PARAM_PATTERN.matcher(result).replaceAll("$1[REDACTED]")
        result = ANDROID_ID_PATTERN.matcher(result).replaceAll("$1=[REDACTED]")

        // 4. Redact emails & MAC addresses
        result = EMAIL_PATTERN.matcher(result).replaceAll("[EMAIL_REDACTED]")
        result = MAC_PATTERN.matcher(result).replaceAll("[MAC_REDACTED]")

        // 5. Redact IP addresses
        result = IPV4_PATTERN.matcher(result).replaceAll("[IP_REDACTED]")
        result = IPV6_PATTERN.matcher(result).replaceAll("[IP_REDACTED]")

        return result
    }

    @JvmStatic
    fun sanitizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val sanitized = sanitize(url)
        // Strip any remaining query string
        val queryIdx = sanitized.indexOf('?')
        return if (queryIdx != -1) sanitized.substring(0, queryIdx) else sanitized
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
