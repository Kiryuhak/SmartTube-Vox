package com.liskovsoft.smartyoutubetv2.common.vox.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class VoxDialRequestParserTest {

    @Test
    fun testParseGetDeviceDesc() {
        val rawHttp = "GET /device-desc.xml HTTP/1.1\r\n" +
                "Host: 192.168.1.100:8081\r\n" +
                "Accept: */*\r\n" +
                "\r\n"

        val req = VoxDialRequestParser.parse(ByteArrayInputStream(rawHttp.toByteArray(StandardCharsets.UTF_8)))
        assertNotNull(req)
        assertEquals("GET", req!!.method)
        assertEquals("/device-desc.xml", req.path)
        assertEquals("192.168.1.100:8081", req.headers["host"])
    }

    @Test
    fun testParsePostYouTubeFormBody() {
        val body = "v=UF8uR6Z6KLc&t=45s"
        val rawHttp = "POST /apps/YouTube HTTP/1.1\r\n" +
                "Host: 192.168.1.100:8081\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "\r\n" +
                body

        val req = VoxDialRequestParser.parse(ByteArrayInputStream(rawHttp.toByteArray(StandardCharsets.UTF_8)))
        assertNotNull(req)
        assertEquals("POST", req!!.method)
        assertEquals("/apps/YouTube", req.path)
        assertEquals(body, req.body)

        val videoReq = VoxDialRequestParser.extractVideoParameters(req, "192.168.1.50")
        assertNotNull(videoReq)
        assertEquals("UF8uR6Z6KLc", videoReq!!.videoId)
        assertEquals(45_000L, videoReq.timeMs)
        assertEquals("192.168.1.50", videoReq.clientIp)
    }

    @Test
    fun testParsePostYouTubeQueryString() {
        val rawHttp = "POST /apps/YouTube?v=dQw4w9WgXcQ HTTP/1.1\r\n" +
                "Host: 192.168.1.100:8081\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n"

        val req = VoxDialRequestParser.parse(ByteArrayInputStream(rawHttp.toByteArray(StandardCharsets.UTF_8)))
        assertNotNull(req)
        assertEquals("POST", req!!.method)
        assertEquals("/apps/YouTube", req.path)
        assertEquals("v=dQw4w9WgXcQ", req.queryString)

        val videoReq = VoxDialRequestParser.extractVideoParameters(req, "127.0.0.1")
        assertNotNull(videoReq)
        assertEquals("dQw4w9WgXcQ", videoReq!!.videoId)
    }

    @Test
    fun testParseInvalidRequest() {
        val rawHttp = "INVALID REQUEST"
        val req = VoxDialRequestParser.parse(ByteArrayInputStream(rawHttp.toByteArray(StandardCharsets.UTF_8)))
        assertNull(req)
    }

    @Test
    fun testParseUnsupportedMethod() {
        val rawHttp = "PATCH /apps/YouTube HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        val req = VoxDialRequestParser.parse(ByteArrayInputStream(rawHttp.toByteArray(StandardCharsets.UTF_8)))
        assertNull(req)
    }

    @Test
    fun testRejectsMaliciousPayloads() {
        val dangerousBodies = listOf(
            "file:///etc/passwd",
            "javascript:alert(1)",
            "intent:#Intent;action=android.intent.action.VIEW;end",
            "content://telephony/siminfo",
            "../../../etc/hosts",
            "http://evil.com/payload.apk",
            "rm -rf /",
            "v=123" // Too short for valid YouTube ID (must be exactly 11 chars)
        )

        for (body in dangerousBodies) {
            val req = VoxHttpRequest(
                method = "POST",
                path = "/apps/YouTube",
                queryString = "",
                headers = emptyMap(),
                body = body
            )
            val extracted = VoxDialRequestParser.extractVideoParameters(req, "192.168.1.1")
            assertNull("Dangerous body '$body' must be rejected", extracted)
        }
    }

    @Test
    fun testTimestampsParsing() {
        val tests = mapOf(
            "v=UF8uR6Z6KLc&t=15" to 15_000L,
            "v=UF8uR6Z6KLc&t=15s" to 15_000L,
            "v=UF8uR6Z6KLc&t=1m30s" to 90_000L,
            "v=UF8uR6Z6KLc&t=1h2m3s" to 3_723_000L,
            "v=UF8uR6Z6KLc&start=30" to 30_000L,
            "v=UF8uR6Z6KLc&t=-10" to -1L
        )

        for ((body, expectedMs) in tests) {
            val req = VoxHttpRequest(
                method = "POST",
                path = "/apps/YouTube",
                queryString = "",
                headers = emptyMap(),
                body = body
            )
            val extracted = VoxDialRequestParser.extractVideoParameters(req, "192.168.1.1")
            assertNotNull(extracted)
            assertEquals("Timestamp mismatch for body '$body'", expectedMs, extracted!!.timeMs)
        }
    }
}
