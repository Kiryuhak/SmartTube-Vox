package com.liskovsoft.smartyoutubetv2.common.vox.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxSsdpPacketTest {

    @Test
    fun testValidDialSearchTarget() {
        val dialSt = "urn:dial-multiscreen-org:service:dial:1"
        assertTrue(VoxSsdpProtocol.isSupportedSearchTarget(dialSt))
        assertTrue(VoxSsdpProtocol.isSupportedSearchTarget("ssdp:all"))
        assertTrue(VoxSsdpProtocol.isSupportedSearchTarget("upnp:rootdevice"))
        assertTrue(VoxSsdpProtocol.isSupportedSearchTarget("urn:dial-multiscreen-org:device:dial:1"))
    }

    @Test
    fun testUnrelatedSearchTargetIgnored() {
        assertFalse(VoxSsdpProtocol.isSupportedSearchTarget("urn:schemas-upnp-org:service:RenderingControl:1"))
        assertFalse(VoxSsdpProtocol.isSupportedSearchTarget("urn:schemas-upnp-org:service:AVTransport:1"))
        assertFalse(VoxSsdpProtocol.isSupportedSearchTarget("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertFalse(VoxSsdpProtocol.isSupportedSearchTarget("urn:google-com:service:cast:1"))
        assertFalse(VoxSsdpProtocol.isSupportedSearchTarget(null))
        assertFalse(VoxSsdpProtocol.isSupportedSearchTarget(""))
    }

    @Test
    fun testIsSsdpDiscovery() {
        val validSearch = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n"

        assertTrue(VoxSsdpProtocol.isDiscoveryRequest(validSearch))

        val notifyPacket = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n\r\n"
        assertFalse(VoxSsdpProtocol.isDiscoveryRequest(notifyPacket))

        val malformedPacket = "GARBAGE DATA"
        assertFalse(VoxSsdpProtocol.isDiscoveryRequest(malformedPacket))
    }

    @Test
    fun testExtractSearchTarget() {
        val search = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n"

        val st = VoxSsdpProtocol.extractSearchTarget(search)
        assertEquals("urn:dial-multiscreen-org:service:dial:1", st)

        val noSt = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n\r\n"
        assertNull(VoxSsdpProtocol.extractSearchTarget(noSt))
    }

    @Test
    fun testBuildResponse() {
        val response = VoxSsdpProtocol.buildResponse(
            localIp = "192.168.1.100",
            httpPort = 8081,
            deviceUdn = "uuid:test-1234",
            requestedSt = "urn:dial-multiscreen-org:service:dial:1"
        )

        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(response.contains("LOCATION: http://192.168.1.100:8081/dd.xml\r\n"))
        assertTrue(response.contains("ST: urn:dial-multiscreen-org:service:dial:1\r\n"))
        assertTrue(response.contains("USN: uuid:test-1234::urn:dial-multiscreen-org:service:dial:1\r\n"))
        assertTrue(response.contains("CACHE-CONTROL: max-age=1800\r\n"))
        assertTrue(response.endsWith("\r\n\r\n"))
    }
}
