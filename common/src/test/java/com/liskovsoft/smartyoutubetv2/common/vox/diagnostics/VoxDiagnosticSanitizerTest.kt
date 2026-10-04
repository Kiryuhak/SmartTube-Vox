package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoxDiagnosticSanitizerTest {

    @Test
    fun testSanitizeBearerToken() {
        val input = "Authorization: Bearer ya29.a0AfH6SMB_secretToken12345"
        val result = VoxDiagnosticSanitizer.sanitize(input)
        assertFalse(result.contains("ya29.a0AfH6SMB_secretToken12345"))
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun testSanitizeUrlTokensAndSignatures() {
        val url = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1710000000&ei=xyz&ip=203.0.113.195&id=123&itag=248&source=youtube&requiressl=yes&ratebypass=yes&live=1&sgoap=gir%3Dyes&sigh=mySignature12345&sig=abcdef123456"
        val result = VoxDiagnosticSanitizer.sanitizeUrl(url)
        assertFalse(result.contains("expire="))
        assertFalse(result.contains("mySignature12345"))
        assertFalse(result.contains("abcdef123456"))
        assertFalse(result.contains("203.0.113.195"))
        assertEquals("https://rr1---sn-abc.googlevideo.com/videoplayback", result)
    }

    @Test
    fun testSanitizeCookieAndAuthHeaders() {
        val header = "Cookie: session_id=xyz789; yandexuid=123456789\nAuthorization: Basic dXNlcjpwYXNz\nProxy-Authorization: Basic cHJveHk6cGFzcw=="
        val result = VoxDiagnosticSanitizer.sanitize(header)
        assertFalse(result.contains("xyz789"))
        assertFalse(result.contains("123456789"))
        assertFalse(result.contains("dXNlcjpwYXNz"))
        assertFalse(result.contains("cHJveHk6cGFzcw=="))
    }

    @Test
    fun testSanitizePasswordsAndApiKeys() {
        val line = "Connecting with password=mySuperSecretPassword123 proxy_password=pxPass client_secret=sec789 api_key=key_xyz"
        val result = VoxDiagnosticSanitizer.sanitize(line)
        assertFalse(result.contains("mySuperSecretPassword123"))
        assertFalse(result.contains("pxPass"))
        assertFalse(result.contains("sec789"))
        assertFalse(result.contains("key_xyz"))
        assertTrue(result.contains("password=[REDACTED]"))
    }

    @Test
    fun testSanitizeIpv4AndIpv6() {
        val log = "Device connected at 192.168.1.105:5555 and proxy 10.0.0.1 and public 8.8.8.8 and ipv6 2001:0db8:85a3:0000:0000:8a2e:0370:7334"
        val result = VoxDiagnosticSanitizer.sanitize(log)
        assertFalse(result.contains("192.168.1.105"))
        assertFalse(result.contains("10.0.0.1"))
        assertFalse(result.contains("8.8.8.8"))
        assertFalse(result.contains("2001:0db8:85a3"))
        assertTrue(result.contains("[IP_REDACTED]"))
    }

    @Test
    fun testSanitizeEmailMacAndDeviceSerial() {
        val log = "User email test.user@example.com with MAC 00:1A:2B:3C:4D:5E and android_id=9774d56d682e549c"
        val result = VoxDiagnosticSanitizer.sanitize(log)
        assertFalse(result.contains("test.user@example.com"))
        assertFalse(result.contains("00:1A:2B:3C:4D:5E"))
        assertFalse(result.contains("9774d56d682e549c"))
        assertTrue(result.contains("[EMAIL_REDACTED]"))
        assertTrue(result.contains("[MAC_REDACTED]"))
    }

    @Test
    fun testFalsePositivesPreserved() {
        val safeTerms = listOf(
            "AV1",
            "VP9",
            "EAC3",
            "AC3",
            "Opus",
            "AAC",
            "Android 14",
            "Samsung Tizen 8",
            "DOWNLOAD_HTTP_403",
            "PLAYER_INIT_FAILED",
            "PREMIUM_TV",
            "3840x2160@60fps",
            "HDR10",
            "Dolby Vision"
        )
        for (term in safeTerms) {
            val sanitized = VoxDiagnosticSanitizer.sanitize(term)
            assertEquals("Safe term '$term' should not be modified", term, sanitized)
        }
    }

    @Test
    fun testIsSafePayloadWithCleanJson() {
        val cleanJson = JSONObject().apply {
            put("schema", "vox-diagnostic-report-v2")
            put("platform", "Android TV")
            put("manufacturer", "TCL")
            put("model", "BeyondTV")
            put("sdkInt", 31)
            put("videoCodecs", JSONObject().apply {
                put("AVC", "Поддерживается [HW]")
                put("VP9", "Поддерживается [HW]")
                put("AV1", "Поддерживается [HW]")
            })
        }.toString()

        assertTrue(VoxDiagnosticSanitizer.isSafePayload(cleanJson))
    }

    @Test
    fun testIsSafePayloadRejectsDangerousKeys() {
        val dirtyJson = JSONObject().apply {
            put("schema", "vox-diagnostic-report-v2")
            put("access_token", "ya29.secret")
        }.toString()

        assertFalse(VoxDiagnosticSanitizer.isSafePayload(dirtyJson))

        val nestedDirtyJson = JSONObject().apply {
            put("schema", "vox-diagnostic-report-v2")
            put("auth", JSONObject().apply {
                put("token", "secret")
            })
        }.toString()

        assertFalse(VoxDiagnosticSanitizer.isSafePayload(nestedDirtyJson))
    }

    @Test
    fun testSanitizeJsonStripsForbiddenKeys() {
        val dirtyJson = JSONObject().apply {
            put("schema", "vox-diagnostic-report-v2")
            put("model", "MiBox4")
            put("access_token", "ya29.secret")
            put("cookie", "session_id=123")
            put("nested", JSONObject().apply {
                put("password", "p@ss")
                put("safeValue", "ok")
            })
        }.toString()

        val sanitized = VoxDiagnosticSanitizer.sanitizeJson(dirtyJson)
        val result = JSONObject(sanitized)

        assertEquals("vox-diagnostic-report-v2", result.getString("schema"))
        assertEquals("MiBox4", result.getString("model"))
        assertFalse(result.has("access_token"))
        assertFalse(result.has("cookie"))

        val nested = result.getJSONObject("nested")
        assertFalse(nested.has("password"))
        assertEquals("ok", nested.getString("safeValue"))
    }
}
