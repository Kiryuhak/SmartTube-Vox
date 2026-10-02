package com.liskovsoft.smartyoutubetv2.common.vox.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class VoxExternalLaunchValidatorTest {

    @Test
    fun testTrustedLocalIpv4Addresses() {
        // Loopback
        assertTrue(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("127.0.1.1")))

        // RFC 1918 Private ranges
        assertTrue(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("10.0.0.1")))
        assertTrue(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("10.254.254.254")))
        assertTrue(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("172.31.255.255")))
        assertTrue(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("192.168.1.100")))
        assertTrue(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("192.168.0.1")))

        // Link-Local
        assertTrue(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("169.254.10.20")))
    }

    @Test
    fun testRejectPublicWanAddresses() {
        assertFalse(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("1.1.1.1")))
        assertFalse(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("93.184.216.34")))
        assertFalse(VoxExternalLaunchValidator.isTrustedLocalAddress(InetAddress.getByName("172.32.0.1")))
        assertFalse(VoxExternalLaunchValidator.isTrustedLocalAddress(null))
    }

    @Test
    fun testVideoIdValidation() {
        assertTrue(VoxExternalLaunchValidator.isValidVideoId("dQw4w9WgXcQ"))
        assertTrue(VoxExternalLaunchValidator.isValidVideoId("UF8uR6Z6KLc"))
        assertTrue(VoxExternalLaunchValidator.isValidVideoId("a-B_1234567"))

        // Invalid IDs
        assertFalse(VoxExternalLaunchValidator.isValidVideoId(null))
        assertFalse(VoxExternalLaunchValidator.isValidVideoId(""))
        assertFalse(VoxExternalLaunchValidator.isValidVideoId("tooShort"))
        assertFalse(VoxExternalLaunchValidator.isValidVideoId("wayTooLongVideoIdentifier123"))
        assertFalse(VoxExternalLaunchValidator.isValidVideoId("../../../etc"))
        assertFalse(VoxExternalLaunchValidator.isValidVideoId("<script>123"))
        assertFalse(VoxExternalLaunchValidator.isValidVideoId("abc def ghi"))
    }

    @Test
    fun testPlaylistIdValidation() {
        assertTrue(VoxExternalLaunchValidator.isValidPlaylistId("PL1234567890abcdef"))
        assertTrue(VoxExternalLaunchValidator.isValidPlaylistId("RDCLAK5uy_k"))
        assertFalse(VoxExternalLaunchValidator.isValidPlaylistId("short"))
        assertFalse(VoxExternalLaunchValidator.isValidPlaylistId(null))
    }

    @Test
    fun testParseTimeToMs() {
        // Standard cases
        assertEquals(120_000L, VoxExternalLaunchValidator.parseTimeToMs("120"))
        assertEquals(120_000L, VoxExternalLaunchValidator.parseTimeToMs("120s"))
        assertEquals(135_000L, VoxExternalLaunchValidator.parseTimeToMs("2m15s"))
        assertEquals(3_723_000L, VoxExternalLaunchValidator.parseTimeToMs("1h2m3s"))
        assertEquals(12_345L, VoxExternalLaunchValidator.parseTimeToMs("12345ms"))

        // t=0 must be VALID (start of video)
        assertEquals(0L, VoxExternalLaunchValidator.parseTimeToMs("0"))
        assertEquals(0L, VoxExternalLaunchValidator.parseTimeToMs("0s"))

        // Invalid / edge cases
        assertEquals(-1L, VoxExternalLaunchValidator.parseTimeToMs(""))
        assertEquals(-1L, VoxExternalLaunchValidator.parseTimeToMs(null))
        assertEquals(-1L, VoxExternalLaunchValidator.parseTimeToMs("invalid"))
        assertEquals(-1L, VoxExternalLaunchValidator.parseTimeToMs("-10"))
        assertEquals(-1L, VoxExternalLaunchValidator.parseTimeToMs("-1s"))
        // Overflow guard: > 24h = unrealistic for YouTube
        assertEquals(-1L, VoxExternalLaunchValidator.parseTimeToMs("99999"))
        // Negative ms suffix
        assertEquals(-1L, VoxExternalLaunchValidator.parseTimeToMs("-500ms"))
    }


    @Test
    fun testExtractVideoId() {
        assertEquals("dQw4w9WgXcQ", VoxExternalLaunchValidator.extractVideoId("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", VoxExternalLaunchValidator.extractVideoId("v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", VoxExternalLaunchValidator.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("UF8uR6Z6KLc", VoxExternalLaunchValidator.extractVideoId("https://youtu.be/UF8uR6Z6KLc?t=10"))
        assertEquals("UF8uR6Z6KLc", VoxExternalLaunchValidator.extractVideoId("https://www.youtube.com/embed/UF8uR6Z6KLc"))
        assertNull(VoxExternalLaunchValidator.extractVideoId("https://malicious.com/attack"))
    }
}
