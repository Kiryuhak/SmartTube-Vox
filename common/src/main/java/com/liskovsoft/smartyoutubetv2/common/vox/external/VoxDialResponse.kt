package com.liskovsoft.smartyoutubetv2.common.vox.external

import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Формирование HTTP/DIAL ответов и XML-документов.
 */
object VoxDialResponse {

    fun buildDeviceDescriptionXml(
        deviceIdentity: VoxDeviceIdentity,
        serverBaseUrl: String
    ): String {
        val friendlyName = deviceIdentity.getFriendlyName()
        val manufacturer = deviceIdentity.getManufacturer()
        val modelName = deviceIdentity.getModelName()
        val modelNumber = deviceIdentity.getModelNumber()
        val udn = deviceIdentity.getUdn()

        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
    <specVersion>
        <major>1</major>
        <minor>0</minor>
    </specVersion>
    <device>
        <deviceType>urn:schemas-upnp-org:device:dial:1</deviceType>
        <friendlyName>$friendlyName</friendlyName>
        <manufacturer>$manufacturer</manufacturer>
        <modelName>$modelName</modelName>
        <modelNumber>$modelNumber</modelNumber>
        <UDN>$udn</UDN>
        <presentationURL>$serverBaseUrl</presentationURL>
        <serviceList>
            <service>
                <serviceType>urn:dial-multiscreen-org:service:dial:1</serviceType>
                <serviceId>urn:dial-multiscreen-org:serviceId:dial</serviceId>
                <controlURL>/apps</controlURL>
                <eventSubURL>/apps</eventSubURL>
                <SCPDURL>/apps</SCPDURL>
            </service>
        </serviceList>
    </device>
</root>""".trimIndent()
    }

    fun buildYouTubeAppStateXml(isRunning: Boolean): String {
        val state = if (isRunning) "running" else "stopped"
        return """<?xml version="1.0" encoding="UTF-8"?>
<service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="2.1">
    <name>YouTube</name>
    <options allowStop="true"/>
    <state>$state</state>
    <link rel="run" href="run"/>
</service>""".trimIndent()
    }

    fun sendXmlResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        xmlContent: String,
        additionalHeaders: Map<String, String> = emptyMap()
    ) {
        val bytes = xmlContent.toByteArray(StandardCharsets.UTF_8)
        val headerBuilder = StringBuilder()
        headerBuilder.append("HTTP/1.1 $statusCode $statusText\r\n")
        headerBuilder.append("Content-Type: application/xml; charset=utf-8\r\n")
        headerBuilder.append("Content-Length: ${bytes.size}\r\n")
        headerBuilder.append("Connection: close\r\n")
        headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
        headerBuilder.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
        headerBuilder.append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")

        for ((key, value) in additionalHeaders) {
            headerBuilder.append("$key: $value\r\n")
        }
        headerBuilder.append("\r\n")

        out.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    fun sendCreatedResponse(
        out: OutputStream,
        locationUrl: String
    ) {
        val header = "HTTP/1.1 201 Created\r\n" +
                "Location: $locationUrl\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Expose-Headers: Location\r\n" +
                "\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    fun sendSimpleResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        bodyText: String = ""
    ) {
        val bytes = bodyText.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        if (bytes.isNotEmpty()) {
            out.write(bytes)
        }
        out.flush()
    }

    fun sendOptionsResponse(out: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Allow: GET, POST, DELETE, OPTIONS\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Origin, Accept\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }
}
