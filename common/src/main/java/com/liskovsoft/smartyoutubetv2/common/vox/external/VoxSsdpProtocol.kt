package com.liskovsoft.smartyoutubetv2.common.vox.external

/**
 * Протокольная логика SSDP для DIAL (поиск и формирование ответов).
 */
object VoxSsdpProtocol {

    const val SSDP_MULTICAST_IP = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val BUFFER_SIZE = 4096
    const val DIAL_ST = "urn:dial-multiscreen-org:service:dial:1"

    /**
     * Проверяет, является ли UDP-сообщение валидным M-SEARCH SSDP запросом.
     */
    fun isDiscoveryRequest(data: String): Boolean {
        val upper = data.uppercase()
        return upper.startsWith("M-SEARCH") && upper.contains("MAN: \"SSDP:DISCOVER\"")
    }

    /**
     * Извлекает значение заголовка ST (Search Target) из SSDP-запроса.
     */
    fun extractSearchTarget(data: String): String? {
        val lines = data.split("\r\n")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.uppercase().startsWith("ST:")) {
                return trimmed.substring(3).trim()
            }
        }
        return null
    }

    /**
     * Проверяет, соответствует ли целевой тип (ST) поддерживаемым DIAL/UPnP сервисам.
     */
    fun isSupportedSearchTarget(st: String?): Boolean {
        if (st.isNullOrBlank()) return false
        val lower = st.trim().lowercase()
        return lower == "ssdp:all" ||
                lower == "upnp:rootdevice" ||
                lower == DIAL_ST.lowercase() ||
                lower.startsWith("urn:dial-multiscreen-org:service:dial") ||
                lower.startsWith("urn:dial-multiscreen-org:device:dial")
    }

    /**
     * Формирует Unicast SSDP ответ на M-SEARCH.
     */
    fun buildResponse(
        localIp: String,
        httpPort: Int,
        deviceUdn: String,
        requestedSt: String
    ): String {
        val locationUrl = "http://$localIp:$httpPort/dd.xml"
        val effectiveSt = if (requestedSt.equals("ssdp:all", ignoreCase = true)) DIAL_ST else requestedSt
        val usn = "$deviceUdn::$effectiveSt"

        return "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "EXT:\r\n" +
                "LOCATION: $locationUrl\r\n" +
                "SERVER: Android/1.0 UPnP/1.0 SmartTube-VOX/5.0\r\n" +
                "ST: $effectiveSt\r\n" +
                "USN: $usn\r\n" +
                "BOOTID.UPNP.ORG: 1\r\n" +
                "CONFIGID.UPNP.ORG: 1\r\n" +
                "\r\n"
    }
}
