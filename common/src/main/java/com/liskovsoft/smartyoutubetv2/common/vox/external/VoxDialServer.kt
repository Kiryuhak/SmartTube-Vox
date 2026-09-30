package com.liskovsoft.smartyoutubetv2.common.vox.external

import com.liskovsoft.sharedutils.mylogger.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Легковесный HTTP/DIAL сервер на Kotlin для приёма команд запуска видео из локальной сети.
 */
class VoxDialServer(
    private val deviceIdentity: VoxDeviceIdentity,
    private val preferredPort: Int = 8081,
    private val onVideoLaunch: (VoxExternalVideoRequest) -> Boolean,
    private val onVideoStop: () -> Unit = {},
    private val isVideoRunningProvider: () -> Boolean = { true }
) {

    companion object {
        private val TAG = VoxDialServer::class.java.simpleName
        private const val SOCKET_TIMEOUT_MS = 5000
        private const val MAX_PORT_ATTEMPTS = 10
    }

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val isRunning = AtomicBoolean(false)
    private var boundPort: Int = 0

    fun start(): Boolean {
        if (isRunning.get()) {
            VoxLog.d(TAG, "DIAL HTTP server already running on port $boundPort")
            return true
        }

        var port = preferredPort
        var bound = false

        if (port == 0) {
            try {
                val s = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                }
                serverSocket = s
                boundPort = s.localPort
                bound = true
            } catch (e: Exception) {
                VoxLog.e(TAG, "Failed to bind to dynamic port: ${e.message}")
            }
        } else {
            for (i in 0 until MAX_PORT_ATTEMPTS) {
                try {
                    val s = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(port))
                    }
                    serverSocket = s
                    boundPort = s.localPort
                    bound = true
                    break
                } catch (e: IOException) {
                    VoxLog.w(TAG, "Port $port is in use, trying next port: ${e.message}")
                    port++
                }
            }
        }

        if (!bound || serverSocket == null) {
            VoxLog.e(TAG, "Failed to bind DIAL HTTP server to port $preferredPort")
            return false
        }

        isRunning.set(true)
        executor = Executors.newCachedThreadPool { r ->
            val t = Thread(r, "VoxDialWorker-${boundPort}")
            t.isDaemon = true
            t
        }

        val acceptThread = Thread({
            VoxLog.i(TAG, "DIAL HTTP server started on port $boundPort")
            while (isRunning.get() && serverSocket != null && !serverSocket!!.isClosed) {
                try {
                    val clientSocket = serverSocket!!.accept()
                    clientSocket.soTimeout = SOCKET_TIMEOUT_MS
                    executor?.execute {
                        handleClient(clientSocket)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        VoxLog.e(TAG, "Error accepting client connection: ${e.message}")
                    }
                }
            }
            VoxLog.i(TAG, "DIAL HTTP server accept loop terminated")
        }, "VoxDialAcceptor-$boundPort")

        acceptThread.isDaemon = true
        acceptThread.start()

        return true
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return

        VoxLog.i(TAG, "Stopping DIAL HTTP server...")
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            VoxLog.w(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null

        try {
            executor?.shutdownNow()
        } catch (e: Exception) {
            VoxLog.w(TAG, "Error shutting down executor: ${e.message}")
        }
        executor = null
    }

    fun isRunning(): Boolean = isRunning.get()

    fun getPort(): Int = boundPort

    private fun handleClient(socket: Socket) {
        try {
            val remoteAddr = socket.inetAddress
            val clientIp = remoteAddr?.hostAddress ?: ""

            // RFC 1918 / Local security filter
            if (!VoxExternalLaunchValidator.isTrustedLocalAddress(remoteAddr)) {
                VoxLog.w(TAG, "Rejected untrusted client IP: $clientIp")
                socket.getOutputStream().use { out ->
                    VoxDialResponse.sendSimpleResponse(out, 403, "Forbidden", "Forbidden: Local network access only")
                }
                return
            }

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val request = VoxDialRequestParser.parse(input)
            if (request == null) {
                VoxDialResponse.sendSimpleResponse(output, 400, "Bad Request", "Malformed HTTP request")
                return
            }

            if (request.isPayloadTooLarge) {
                VoxLog.w(TAG, "Request payload too large from $clientIp")
                VoxDialResponse.sendSimpleResponse(output, 413, "Payload Too Large", "Payload Too Large")
                return
            }

            val localIp = socket.localAddress?.hostAddress ?: "127.0.0.1"
            val serverBaseUrl = "http://$localIp:$boundPort"
            val appUrlHeader = "$serverBaseUrl/apps/"

            when {
                request.method == "OPTIONS" -> {
                    VoxDialResponse.sendOptionsResponse(output)
                }

                // UPnP Device Description
                request.method == "GET" && (request.path == "/dd.xml" || request.path == "/device-desc.xml") -> {
                    val xml = VoxDialResponse.buildDeviceDescriptionXml(deviceIdentity, serverBaseUrl)
                    val headers = mapOf("Application-URL" to appUrlHeader)
                    VoxDialResponse.sendXmlResponse(output, 200, "OK", xml, headers)
                }

                // DIAL YouTube App Query
                request.method == "GET" && (request.path == "/apps/YouTube" || request.path == "/apps/YouTube/") -> {
                    val xml = VoxDialResponse.buildYouTubeAppStateXml(isVideoRunningProvider())
                    VoxDialResponse.sendXmlResponse(output, 200, "OK", xml)
                }

                // DIAL YouTube Launch
                request.method == "POST" && (request.path == "/apps/YouTube" || request.path == "/apps/YouTube/") -> {
                    val videoReq = VoxDialRequestParser.extractVideoParameters(request, clientIp)
                    if (videoReq != null) {
                        VoxLog.i(TAG, "Received external video launch: videoId=${videoReq.videoId}, timeMs=${videoReq.timeMs}, from=$clientIp")
                        val accepted = onVideoLaunch(videoReq)
                        if (accepted) {
                            val runLocation = "$serverBaseUrl/apps/YouTube/run"
                            VoxDialResponse.sendCreatedResponse(output, runLocation)
                        } else {
                            VoxDialResponse.sendSimpleResponse(output, 500, "Internal Server Error", "Could not start video")
                        }
                    } else {
                        VoxLog.w(TAG, "Failed to parse video parameters from DIAL POST: body='${request.body}', query='${request.queryString}'")
                        VoxDialResponse.sendSimpleResponse(output, 400, "Bad Request", "Missing or invalid video ID")
                    }
                }

                // DIAL YouTube App Instance Status / Stop
                request.path.startsWith("/apps/YouTube/run") -> {
                    when (request.method) {
                        "GET" -> {
                            val xml = VoxDialResponse.buildYouTubeAppStateXml(isVideoRunningProvider())
                            VoxDialResponse.sendXmlResponse(output, 200, "OK", xml)
                        }
                        "DELETE" -> {
                            VoxLog.i(TAG, "Received DIAL stop request from $clientIp")
                            onVideoStop()
                            VoxDialResponse.sendSimpleResponse(output, 200, "OK")
                        }
                        else -> {
                            VoxDialResponse.sendSimpleResponse(output, 405, "Method Not Allowed")
                        }
                    }
                }

                // Basic Health/Status check
                request.method == "GET" && (request.path == "/status" || request.path == "/health") -> {
                    VoxDialResponse.sendSimpleResponse(output, 200, "OK", "SmartTube VOX DIAL Server Active")
                }

                else -> {
                    VoxDialResponse.sendSimpleResponse(output, 404, "Not Found", "Endpoint not found")
                }
            }
        } catch (e: SocketTimeoutException) {
            // Socket timed out normally, ignore
        } catch (e: Exception) {
            VoxLog.w(TAG, "Error handling DIAL client: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore socket close exception
            }
        }
    }
}
