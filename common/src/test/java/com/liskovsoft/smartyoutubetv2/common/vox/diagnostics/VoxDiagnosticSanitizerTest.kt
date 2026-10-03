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
    fun testSanitizeUrlTokens() {
        val url = "https://example.com/stream.mpd?key=secret123&token=abc456&sig=sig789"
        val result = VoxDiagnosticSanitizer.sanitize(url)
        assertFalse(result.contains("secret123"))
        assertFalse(result.contains("abc456"))
        assertFalse(result.contains("sig789"))
        assertTrue(result.contains("key=[REDACTED]"))
    }

    @Test
    fun testSanitizeCookieAndAuthHeaders() {
        val header = "Cookie: session_id=xyz789; yandexuid=123456789\nAuthorization: Basic dXNlcjpwYXNz"
        val result = VoxDiagnosticSanitizer.sanitize(header)
        assertFalse(result.contains("xyz789"))
        assertFalse(result.contains("123456789"))
        assertFalse(result.contains("Basic dXNlcjpwYXNz"))
    }

    @Test
    fun testSanitizePassword() {
        val line = "Connecting with password=mySuperSecretPassword123"
        val result = VoxDiagnosticSanitizer.sanitize(line)
        assertFalse(result.contains("mySuperSecretPassword123"))
        assertTrue(result.contains("password=[REDACTED]"))
    }

    @Test
    fun testSanitizePrivateIpv4() {
        val log = "Device connected at 192.168.1.105:5555 and proxy 10.0.0.1"
        val result = VoxDiagnosticSanitizer.sanitize(log)
        assertFalse(result.contains("192.168.1.105"))
        assertFalse(result.contains("10.0.0.1"))
        assertTrue(result.contains("[LOCAL_IP]"))
    }

    @Test
    fun testIsSafePayloadWithCleanJson() {
        val cleanJson = JSONObject().apply {
            put("schema", "vox-diagnostic-report-v1")
            put("platform", "Android TV")
            put("manufacturer", "TCL")
            put("model", "BeyondTV")
            put("sdkInt", 31)
            put("videoCodecs", JSONObject().apply {
                put("AVC", "Поддерживается [HW]")
                put("VP9", "Поддерживается [HW]")
            })
        }.toString()

        assertTrue(VoxDiagnosticSanitizer.isSafePayload(cleanJson))
    }

    @Test
    fun testIsSafePayloadRejectsDangerousKeys() {
        val dirtyJson = JSONObject().apply {
            put("schema", "vox-diagnostic-report-v1")
            put("access_token", "ya29.secret")
        }.toString()

        assertFalse(VoxDiagnosticSanitizer.isSafePayload(dirtyJson))

        val nestedDirtyJson = JSONObject().apply {
            put("schema", "vox-diagnostic-report-v1")
            put("auth", JSONObject().apply {
                put("token", "secret")
            })
        }.toString()

        assertFalse(VoxDiagnosticSanitizer.isSafePayload(nestedDirtyJson))
    }

    @Test
    fun testSanitizeJsonStripsForbiddenKeys() {
        val dirtyJson = JSONObject().apply {
            put("schema", "vox-diagnostic-report-v1")
            put("model", "MiBox4")
            put("access_token", "ya29.secret")
            put("cookie", "session_id=123")
        }.toString()

        val cleaned = VoxDiagnosticSanitizer.sanitizeJson(dirtyJson)
        val cleanedObj = JSONObject(cleaned)

        assertEquals("vox-diagnostic-report-v1", cleanedObj.getString("schema"))
        assertEquals("MiBox4", cleanedObj.getString("model"))
        assertFalse(cleanedObj.has("access_token"))
        assertFalse(cleanedObj.has("cookie"))
    }
}
