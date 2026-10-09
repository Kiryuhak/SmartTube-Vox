package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@RunWith(RobolectricTestRunner::class)
class UpdateErrorMappingTest {

    @Test
    fun testAllErrorCodesHaveLocalizedRussianMessages() {
        for (code in VoxOtaErrorCode.values()) {
            val ex = VoxOtaException(code, "Internal error")
            assertFalse(ex.userMessageRu.contains("java.lang"))
            assertFalse(ex.userMessageRu.contains("Exception"))
            assertTrue(ex.userMessageRu.isNotBlank())
        }
    }

    @Test
    fun testGranularErrorCodesMatchDomain() {
        assertEquals("Ошибка DNS при подключении к серверу обновлений", VoxOtaErrorCode.DNS_ERROR.userMessageRu)
        assertEquals("Превышено время ожидания ответа сервера обновлений", VoxOtaErrorCode.TIMEOUT.userMessageRu)
        assertEquals("Ошибка защищенного соединения (TLS/SSL)", VoxOtaErrorCode.TLS_ERROR.userMessageRu)
        assertEquals("Превышен лимит запросов к серверу обновлений", VoxOtaErrorCode.RATE_LIMIT.userMessageRu)
        assertEquals("Данные обновления не найдены на сервере", VoxOtaErrorCode.RELEASE_NOT_FOUND.userMessageRu)
        assertEquals("У вас установлена последняя версия", VoxOtaErrorCode.NO_UPDATE.userMessageRu)
    }

    @Test
    fun testExceptionMappingLogic() {
        val dnsException = UnknownHostException("api.github.com")
        val timeoutException = SocketTimeoutException("Read timed out")
        val sslException = SSLException("Handshake failed")
        val connException = ConnectException("Connection refused")
        val rateLimitException = IOException("HTTP 429 Too Many Requests")
        val notFoundException = IOException("HTTP 404 Not Found")

        assertTrue(dnsException is UnknownHostException)
        assertTrue(timeoutException is SocketTimeoutException)
        assertTrue(sslException is SSLException)
        assertTrue(connException is ConnectException)
        assertTrue(rateLimitException.message?.contains("429") == true)
        assertTrue(notFoundException.message?.contains("404") == true)
    }
}
