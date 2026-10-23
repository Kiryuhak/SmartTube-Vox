package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.net.Uri
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPlayerRecoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
class PlayerSourceClassificationTest {

    @Test
    fun testDnsResolutionFailureClassifiedAsDnsError() {
        val dnsEx = UnknownHostException("Unable to resolve host googlevideo.com")
        val code = VoxPlayerRecoveryPolicy.classifyPlayerSourceError(dnsEx, isLocalSource = false)
        assertEquals(VoxLogCode.PLAYER_DNS_RESOLUTION_ERROR, code)
        assertEquals("DNS_RESOLUTION_FAILED", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(dnsEx))
    }

    @Test
    fun testNestedDnsFailureInHttpExceptionClassifiedAsDnsError() {
        val dataSpec = DataSpec(Uri.parse("https://rr1---sn-2ohpa5-5x.googlevideo.com/videoplayback"))
        val dnsCause = UnknownHostException("no address associated with hostname")
        val httpEx = HttpDataSource.HttpDataSourceException(
            "Unable to connect to rr1---sn-2ohpa5-5x.googlevideo.com",
            dnsCause,
            dataSpec,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN
        )
        val code = VoxPlayerRecoveryPolicy.classifyPlayerSourceError(httpEx, isLocalSource = true)
        assertEquals(VoxLogCode.PLAYER_DNS_RESOLUTION_ERROR, code)
        assertEquals("DNS_RESOLUTION_FAILED", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(httpEx))
    }

    @Test
    fun testConnectionResetClassifiedAsConnectionReset() {
        val dataSpec = DataSpec(Uri.parse("https://googlevideo.com/videoplayback"))
        val resetEx = HttpDataSource.HttpDataSourceException(
            "Connection reset by peer",
            SocketException("Connection reset"),
            dataSpec,
            HttpDataSource.HttpDataSourceException.TYPE_READ
        )
        val code = VoxPlayerRecoveryPolicy.classifyPlayerSourceError(resetEx, isLocalSource = false)
        assertEquals(VoxLogCode.PLAYER_CONNECTION_RESET, code)
        assertEquals("CONNECTION_RESET", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(resetEx))
    }

    @Test
    fun testConnectTimeoutClassifiedAsConnectTimeout() {
        val dataSpec = DataSpec(Uri.parse("https://googlevideo.com/videoplayback"))
        val timeoutEx = HttpDataSource.HttpDataSourceException(
            "Failed to connect within timeout",
            ConnectException("Connection refused"),
            dataSpec,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN
        )
        val code = VoxPlayerRecoveryPolicy.classifyPlayerSourceError(timeoutEx, isLocalSource = false)
        assertEquals(VoxLogCode.PLAYER_CONNECT_TIMEOUT, code)
        assertEquals("CONNECT_TIMEOUT", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(timeoutEx))
    }

    @Test
    fun testReadTimeoutClassifiedAsReadTimeout() {
        val dataSpec = DataSpec(Uri.parse("https://googlevideo.com/videoplayback"))
        val readTimeoutEx = HttpDataSource.HttpDataSourceException(
            "Read timed out",
            SocketTimeoutException("Read timed out"),
            dataSpec,
            HttpDataSource.HttpDataSourceException.TYPE_READ
        )
        val code = VoxPlayerRecoveryPolicy.classifyPlayerSourceError(readTimeoutEx, isLocalSource = false)
        assertEquals(VoxLogCode.PLAYER_READ_TIMEOUT, code)
        assertEquals("READ_TIMEOUT", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(readTimeoutEx))
    }

    @Test
    fun testHttp403ForbiddenClassifiedAsHttpError() {
        val dataSpec = DataSpec(Uri.parse("https://googlevideo.com/videoplayback"))
        val http403 = HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            emptyMap(),
            dataSpec
        )
        val code = VoxPlayerRecoveryPolicy.classifyPlayerSourceError(http403, isLocalSource = false)
        assertEquals(VoxLogCode.PLAYER_HTTP_ERROR, code)
        assertEquals("HTTP_403_FORBIDDEN", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(http403))
    }

    @Test
    fun testTrueLocalFileExceptionClassifiedAsLocalSourceError() {
        val fileEx = IOException("Local file not found on disk")
        val code = VoxPlayerRecoveryPolicy.classifyPlayerSourceError(fileEx, isLocalSource = true)
        assertEquals(VoxLogCode.PLAYER_LOCAL_SOURCE_ERROR, code)
    }

    @Test
    fun testGenericSourceExceptionClassifiedAsSourceError() {
        val genericEx = IOException("Unrecognized stream format")
        val code = VoxPlayerRecoveryPolicy.classifyPlayerSourceError(genericEx, isLocalSource = false)
        assertEquals(VoxLogCode.PLAYER_SOURCE_ERROR, code)
    }

    @Test
    fun testRemoteNetworkErrorsNeverClassifiedAsLocalSourceError() {
        // Regression test for VOX-87B9400C:
        // Remote network errors must NEVER be classified as PLAYER_LOCAL_SOURCE_ERROR even if isLocalSource is true
        val dataSpec = DataSpec(Uri.parse("https://googlevideo.com/videoplayback"))
        val httpEx = HttpDataSource.HttpDataSourceException(
            "Connection failed",
            dataSpec,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN
        )
        val code = VoxPlayerRecoveryPolicy.classifyPlayerSourceError(httpEx, isLocalSource = true)
        assertNotEquals(VoxLogCode.PLAYER_LOCAL_SOURCE_ERROR, code)
    }
}
