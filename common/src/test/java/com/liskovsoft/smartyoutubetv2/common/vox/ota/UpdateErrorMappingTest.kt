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
        assertEquals("Сервер временно ограничил частоту проверок. Попробуйте позже.", VoxOtaErrorCode.RATE_LIMIT.userMessageRu)
        assertEquals("Данные обновления не найдены на сервере", VoxOtaErrorCode.RELEASE_NOT_FOUND.userMessageRu)
        assertEquals("У вас установлена последняя версия", VoxOtaErrorCode.NO_UPDATE.userMessageRu)
    }

    @Test
    fun testExceptionMappingLogic() {
        val dnsEx = VoxOtaUpdateManager.classifyException(UnknownHostException("api.github.com"))
        assertEquals(VoxOtaErrorCode.DNS_ERROR, dnsEx.code)
        assertEquals(VoxOtaStage.DNS, dnsEx.stage)
        assertTrue(dnsEx.isRetryable)

        val timeoutEx1 = VoxOtaUpdateManager.classifyException(SocketTimeoutException("Read timed out"))
        assertEquals(VoxOtaErrorCode.TIMEOUT, timeoutEx1.code)
        assertEquals(VoxOtaStage.CONNECT, timeoutEx1.stage)
        assertTrue(timeoutEx1.isRetryable)

        val timeoutEx2 = VoxOtaUpdateManager.classifyException(java.util.concurrent.TimeoutException("Future timed out"))
        assertEquals(VoxOtaErrorCode.TIMEOUT, timeoutEx2.code)
        assertTrue(timeoutEx2.isRetryable)

        val sslEx = VoxOtaUpdateManager.classifyException(SSLException("Handshake failed"))
        assertEquals(VoxOtaErrorCode.TLS_ERROR, sslEx.code)
        assertEquals(VoxOtaStage.TLS, sslEx.stage)
        assertFalse(sslEx.isRetryable)

        val connEx = VoxOtaUpdateManager.classifyException(ConnectException("Connection refused"))
        assertEquals(VoxOtaErrorCode.NETWORK_ERROR, connEx.code)
        assertEquals(VoxOtaStage.CONNECT, connEx.stage)
        assertTrue(connEx.isRetryable)

        val rateLimitEx = VoxOtaUpdateManager.classifyException(IOException("HTTP 429 Too Many Requests"))
        assertEquals(VoxOtaErrorCode.RATE_LIMIT, rateLimitEx.code)
        assertTrue(rateLimitEx.isRetryable)

        val notFoundEx = VoxOtaUpdateManager.classifyException(IOException("HTTP 404 Not Found"))
        assertEquals(VoxOtaErrorCode.RELEASE_NOT_FOUND, notFoundEx.code)
        assertFalse(notFoundEx.isRetryable)
    }
}
