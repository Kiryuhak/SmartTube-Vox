package com.liskovsoft.smartyoutubetv2.common.vox.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxSsdpPacketTest {

    @Test
    fun testValidDialSearchTarget() {
        val dialSt = "urn:dial-multiscreen-org:service:dial:1"
        assertTrue(isTargetMatch(dialSt))
        assertTrue(isTargetMatch("ssdp:all"))
        assertTrue(isTargetMatch("upnp:rootdevice"))
        assertTrue(isTargetMatch("urn:dial-multiscreen-org:device:dial:1"))
    }

    @Test
    fun testUnrelatedSearchTargetIgnored() {
        assertFalse(isTargetMatch("urn:schemas-upnp-org:service:RenderingControl:1"))
        assertFalse(isTargetMatch("urn:schemas-upnp-org:service:AVTransport:1"))
        assertFalse(isTargetMatch("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertFalse(isTargetMatch("urn:google-com:service:cast:1"))
    }

    @Test
    fun testIsSsdpDiscovery() {
        val validSearch = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n"

        assertTrue(isSSDPDiscovery(validSearch))

        val invalidSearch = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n\r\n"
        assertFalse(isSSDPDiscovery(invalidSearch))
    }

    @Test
    fun testExtractSearchTarget() {
        val search = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n"

        val st = extractSearchTarget(search)
        assertEquals("urn:dial-multiscreen-org:service:dial:1", st)
    }

    private fun isSSDPDiscovery(data: String): Boolean {
        val upper = data.uppercase()
        return upper.startsWith("M-SEARCH") && upper.contains("MAN: \"SSDP:DISCOVER\"")
    }

    private fun extractSearchTarget(data: String): String? {
        val lines = data.split("\r\n")
        for (line in lines) {
            if (line.uppercase().startsWith("ST:")) {
                return line.substring(3).trim()
            }
        }
        return null
    }

    private fun isTargetMatch(st: String): Boolean {
        val lower = st.lowercase()
        return lower == "ssdp:all" ||
                lower == "upnp:rootdevice" ||
                lower == VoxSsdpResponder.DIAL_ST.lowercase() ||
                lower.startsWith("urn:dial-multiscreen-org:service:dial") ||
                lower.startsWith("urn:dial-multiscreen-org:device:dial")
    }
}
