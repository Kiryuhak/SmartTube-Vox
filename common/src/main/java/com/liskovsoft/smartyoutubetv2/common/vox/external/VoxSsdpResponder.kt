package com.liskovsoft.smartyoutubetv2.common.vox.external

import android.content.Context
import android.net.wifi.WifiManager
import com.liskovsoft.sharedutils.mylogger.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSDP (Simple Service Discovery Protocol) ответчик на UDP 239.255.255.250:1900 для обнаружения устройства в сети (DIAL).
 */
class VoxSsdpResponder(
    private val context: Context,
    private val deviceIdentity: VoxDeviceIdentity,
    private val httpPortProvider: () -> Int
) {

    companion object {
        private val TAG = VoxSsdpResponder::class.java.simpleName
        private const val SSDP_MULTICAST_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val BUFFER_SIZE = 4096
        const val DIAL_ST = "urn:dial-multiscreen-org:service:dial:1"
    }

    private var multicastSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val isRunning = AtomicBoolean(false)
    private var listenerThread: Thread? = null

    fun start(): Boolean {
        if (isRunning.get()) return true

        try {
            acquireMulticastLock()

            val groupAddress = InetAddress.getByName(SSDP_MULTICAST_IP)
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SSDP_PORT))
                timeToLive = 4
            }

            // Присоединяемся к группе на доступных сетевых интерфейсах
            joinGroupOnInterfaces(socket, groupAddress)

            multicastSocket = socket
            isRunning.set(true)

            listenerThread = Thread({
                listenLoop(socket)
            }, "VoxSsdpListener").apply {
                isDaemon = true
                start()
            }

            VoxLog.i(TAG, "SSDP Responder started on $SSDP_MULTICAST_IP:$SSDP_PORT")
            return true
        } catch (e: Exception) {
            VoxLog.w(TAG, "Failed to start SSDP responder (expected in restricted environments/emulators): ${e.message}")
            releaseMulticastLock()
            return false
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return

        VoxLog.i(TAG, "Stopping SSDP responder...")
        try {
            multicastSocket?.close()
        } catch (e: Exception) {
            VoxLog.w(TAG, "Error closing SSDP socket: ${e.message}")
        }
        multicastSocket = null

        releaseMulticastLock()
    }

    fun isRunning(): Boolean = isRunning.get()

    private fun listenLoop(socket: MulticastSocket) {
        val buffer = ByteArray(BUFFER_SIZE)

        while (isRunning.get() && !socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)

                val clientAddr = packet.address
                if (!VoxExternalLaunchValidator.isTrustedLocalAddress(clientAddr)) {
                    continue
                }

                val data = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                if (isSSDPDiscovery(data)) {
                    val st = extractSearchTarget(data) ?: DIAL_ST
                    if (isTargetMatch(st)) {
                        sendSsdpResponse(packet.address, packet.port, st)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    VoxLog.d(TAG, "SSDP receive error: ${e.message}")
                }
            }
        }
        VoxLog.i(TAG, "SSDP listener loop finished")
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
                lower == DIAL_ST.lowercase() ||
                lower.startsWith("urn:dial-multiscreen-org:service:dial") ||
                lower.startsWith("urn:dial-multiscreen-org:device:dial")
    }

    private fun sendSsdpResponse(targetAddress: InetAddress, targetPort: Int, requestedSt: String) {
        val httpPort = httpPortProvider()
        if (httpPort <= 0) return

        val localIp = findBestLocalIpForTarget(targetAddress)
        val locationUrl = "http://$localIp:$httpPort/dd.xml"
        val udn = deviceIdentity.getUdn()
        val effectiveSt = if (requestedSt.equals("ssdp:all", ignoreCase = true)) DIAL_ST else requestedSt
        val usn = "$udn::$effectiveSt"

        val response = "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "EXT:\r\n" +
                "LOCATION: $locationUrl\r\n" +
                "SERVER: Android/1.0 UPnP/1.0 SmartTube-VOX/5.0\r\n" +
                "ST: $effectiveSt\r\n" +
                "USN: $usn\r\n" +
                "BOOTID.UPNP.ORG: 1\r\n" +
                "CONFIGID.UPNP.ORG: 1\r\n" +
                "\r\n"

        try {
            val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
            val sendPacket = DatagramPacket(responseBytes, responseBytes.size, targetAddress, targetPort)
            DatagramSocket().use { unicastSocket ->
                unicastSocket.send(sendPacket)
            }
            VoxLog.d(TAG, "Sent SSDP response to $targetAddress:$targetPort -> $locationUrl")
        } catch (e: Exception) {
            VoxLog.w(TAG, "Failed to send SSDP unicast response: ${e.message}")
        }
    }

    private fun joinGroupOnInterfaces(socket: MulticastSocket, group: InetAddress) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isUp && !iface.isLoopback && iface.supportsMulticast()) {
                    try {
                        socket.joinGroup(InetSocketAddress(group, SSDP_PORT), iface)
                    } catch (e: Exception) {
                        // ignore interface specific join errors
                    }
                }
            }
        } catch (e: Exception) {
            VoxLog.w(TAG, "Error joining multicast group on interfaces: ${e.message}")
        }
    }

    private fun findBestLocalIpForTarget(target: InetAddress): String {
        try {
            DatagramSocket().use { s ->
                s.connect(target, SSDP_PORT)
                val local = s.localAddress?.hostAddress
                if (!local.isNullOrBlank() && local != "0.0.0.0") {
                    return local
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return "127.0.0.1"
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("VoxSsdpMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            VoxLog.w(TAG, "Could not acquire WiFi MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            // ignore
        }
        multicastLock = null
    }
}
