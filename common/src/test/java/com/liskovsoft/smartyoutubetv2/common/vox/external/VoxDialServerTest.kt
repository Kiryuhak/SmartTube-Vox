package com.liskovsoft.smartyoutubetv2.common.vox.external

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VoxDialServerTest {

    private class FakeContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private lateinit var deviceIdentity: VoxDeviceIdentity
    private lateinit var server: VoxDialServer
    private val launchedRequest = AtomicReference<VoxExternalVideoRequest?>()
    private val stopRequested = AtomicBoolean(false)

    @Before
    fun setUp() {
        val context = FakeContext()
        deviceIdentity = VoxDeviceIdentity(context, "test-uuid-12345")

        server = VoxDialServer(
            deviceIdentity = deviceIdentity,
            preferredPort = 0,
            onVideoLaunch = { req ->
                launchedRequest.set(req)
                true
            },
            onVideoStop = {
                stopRequested.set(true)
            },
            isVideoRunningProvider = { true }
        )
        assertTrue(server.start())
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun testGetDeviceDescriptionXml() {
        val port = server.getPort()
        val url = URL("http://127.0.0.1:$port/dd.xml")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000

        assertEquals(200, conn.responseCode)
        val appUrlHeader = conn.getHeaderField("Application-URL")
        assertNotNull(appUrlHeader)
        assertTrue(appUrlHeader.contains("/apps/"))

        val content = conn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(content.contains("<friendlyName>SmartTube VOX</friendlyName>"))
        assertTrue(content.contains("urn:dial-multiscreen-org:service:dial:1"))
        assertTrue(content.contains("uuid:test-uuid-12345"))
    }

    @Test
    fun testGetYouTubeAppState() {
        val port = server.getPort()
        val url = URL("http://127.0.0.1:$port/apps/YouTube")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000

        assertEquals(200, conn.responseCode)
        val content = conn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(content.contains("<name>YouTube</name>"))
        assertTrue(content.contains("<state>running</state>"))
    }

    @Test
    fun testPostYouTubeLaunch() {
        val port = server.getPort()
        val url = URL("http://127.0.0.1:$port/apps/YouTube")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        val body = "v=dQw4w9WgXcQ&t=60s"
        OutputStreamWriter(conn.outputStream).use { it.write(body) }

        assertEquals(201, conn.responseCode)
        val location = conn.getHeaderField("Location")
        assertNotNull(location)
        assertTrue(location.contains("/apps/YouTube/run"))

        val req = launchedRequest.get()
        assertNotNull(req)
        assertEquals("dQw4w9WgXcQ", req!!.videoId)
        assertEquals(60_000L, req.timeMs)
    }

    @Test
    fun testDeleteYouTubeStop() {
        val port = server.getPort()
        val url = URL("http://127.0.0.1:$port/apps/YouTube/run")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = 3000

        assertEquals(200, conn.responseCode)
        assertTrue(stopRequested.get())
    }

    @Test
    fun testPostOversizedBody() {
        val port = server.getPort()
        // Send raw HTTP request with Content-Length exceeding MAX_BODY_SIZE (65536)
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            val out = socket.getOutputStream()
            val rawRequest = "POST /apps/YouTube HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$port\r\n" +
                    "Content-Type: application/x-www-form-urlencoded\r\n" +
                    "Content-Length: 100000\r\n" +
                    "\r\n"
            out.write(rawRequest.toByteArray(StandardCharsets.UTF_8))
            out.flush()

            val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            val statusLine = reader.readLine()
            assertNotNull(statusLine)
            assertTrue("Expected 413 response line, got: $statusLine", statusLine!!.contains("413"))
        }
    }


    @Test
    fun testPostMissingVideoId() {
        val port = server.getPort()
        val url = URL("http://127.0.0.1:$port/apps/YouTube")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = "t=30&list=somePlaylist"  // no video ID
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        assertEquals(400, conn.responseCode)
    }

    @Test
    fun testPostWithZeroTimestamp() {
        val port = server.getPort()
        val url = URL("http://127.0.0.1:$port/apps/YouTube")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = "v=dQw4w9WgXcQ&t=0"
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        assertEquals(201, conn.responseCode)
        val req = launchedRequest.get()
        assertNotNull(req)
        // t=0 is valid: should be 0ms
        assertEquals(0L, req!!.timeMs)
    }

    @Test
    fun testIdempotentStart() {
        // Calling start() again on a running server must return true without error
        assertTrue(server.start())
        assertTrue(server.isRunning())
    }

    @Test
    fun testUnknownEndpoint() {
        val port = server.getPort()
        val url = URL("http://127.0.0.1:$port/apps/Netflix")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        assertEquals(404, conn.responseCode)
    }
}
