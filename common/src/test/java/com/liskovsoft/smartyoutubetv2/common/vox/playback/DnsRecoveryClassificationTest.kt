package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class DnsRecoveryClassificationTest {

    @Test
    fun testIsDnsFailureDetection() {
        assertTrue(VoxLiveRecoveryClassificationPolicy.isDnsFailure(UnknownHostException("googlevideo.com")))
        assertTrue(VoxLiveRecoveryClassificationPolicy.isDnsFailure(IOException("Unable to resolve host \"rr1---sn.googlevideo.com\"")))
        assertTrue(VoxLiveRecoveryClassificationPolicy.isDnsFailure(IOException("Wrapper", UnknownHostException("sub.host"))))

        assertFalse(VoxLiveRecoveryClassificationPolicy.isDnsFailure(ConnectException("Connection refused")))
        assertFalse(VoxLiveRecoveryClassificationPolicy.isDnsFailure(SocketTimeoutException("Read timed out")))
        assertFalse(VoxLiveRecoveryClassificationPolicy.isDnsFailure(null))
    }

    @Test
    fun testIsNetworkFailureDetection() {
        assertTrue(VoxLiveRecoveryClassificationPolicy.isNetworkFailure(UnknownHostException()))
        assertTrue(VoxLiveRecoveryClassificationPolicy.isNetworkFailure(ConnectException()))
        assertTrue(VoxLiveRecoveryClassificationPolicy.isNetworkFailure(SocketTimeoutException()))
        assertTrue(VoxLiveRecoveryClassificationPolicy.isNetworkFailure(SSLException("Handshake failed")))

        assertFalse(VoxLiveRecoveryClassificationPolicy.isNetworkFailure(IllegalStateException("Bad state")))
        assertFalse(VoxLiveRecoveryClassificationPolicy.isNetworkFailure(null))
    }

    @Test
    fun testClassificationRuleHierarchy() {
        // Behind live window takes precedence
        assertEquals(
            VoxLogCode.BEHIND_LIVE_WINDOW,
            VoxLiveRecoveryClassificationPolicy.classify(
                error = null,
                isBehindLiveWindow = true
            )
        )

        // Target expired
        assertEquals(
            VoxLogCode.SEEK_TARGET_EXPIRED,
            VoxLiveRecoveryClassificationPolicy.classify(
                error = null,
                isTargetExpired = true
            )
        )

        // Proved DNS error
        assertEquals(
            VoxLogCode.NETWORK_DNS,
            VoxLiveRecoveryClassificationPolicy.classify(
                error = UnknownHostException("rr1.googlevideo.com")
            )
        )

        // Connect error
        assertEquals(
            VoxLogCode.NETWORK_CONNECT,
            VoxLiveRecoveryClassificationPolicy.classify(
                error = ConnectException("Connection refused")
            )
        )

        // Timeout
        assertEquals(
            VoxLogCode.NETWORK_TIMEOUT,
            VoxLiveRecoveryClassificationPolicy.classify(
                error = SocketTimeoutException("Read timeout")
            )
        )

        // 404 segment not found
        assertEquals(
            VoxLogCode.SEGMENT_NOT_FOUND,
            VoxLiveRecoveryClassificationPolicy.classify(
                error = null,
                httpStatusCode = 404
            )
        )

        // 503 manifest stale
        assertEquals(
            VoxLogCode.MANIFEST_STALE,
            VoxLiveRecoveryClassificationPolicy.classify(
                error = null,
                httpStatusCode = 503
            )
        )
    }

    @Test
    fun testUserFacingStringResolutionNeverUsesInternalDnsExceptOnConfirmedDns() {
        // DNS failure shows vox_fixing_dns
        assertEquals(
            R.string.vox_fixing_dns,
            VoxLiveRecoveryClassificationPolicy.resolveUserFacingMessageRes(VoxLogCode.NETWORK_DNS)
        )

        // Connection/Timeout failures show vox_fixing_connection ("Восстанавливаем соединение…")
        assertEquals(
            R.string.vox_fixing_connection,
            VoxLiveRecoveryClassificationPolicy.resolveUserFacingMessageRes(VoxLogCode.NETWORK_CONNECT)
        )
        assertEquals(
            R.string.vox_fixing_connection,
            VoxLiveRecoveryClassificationPolicy.resolveUserFacingMessageRes(VoxLogCode.NETWORK_TIMEOUT)
        )
        assertEquals(
            R.string.vox_fixing_connection,
            VoxLiveRecoveryClassificationPolicy.resolveUserFacingMessageRes(VoxLogCode.NETWORK_ERROR)
        )

        // Seek expired shows return to live prompt
        assertEquals(
            R.string.vox_live_return_to_live_prompt,
            VoxLiveRecoveryClassificationPolicy.resolveUserFacingMessageRes(VoxLogCode.BEHIND_LIVE_WINDOW)
        )
        assertEquals(
            R.string.vox_live_return_to_live_prompt,
            VoxLiveRecoveryClassificationPolicy.resolveUserFacingMessageRes(VoxLogCode.SEEK_TARGET_EXPIRED)
        )

        // Generic stall shows vox_fixing_playback ("Восстанавливаем воспроизведение…")
        assertEquals(
            R.string.vox_fixing_playback,
            VoxLiveRecoveryClassificationPolicy.resolveUserFacingMessageRes(VoxLogCode.BUFFER_UNDERRUN)
        )
        assertEquals(
            R.string.vox_fixing_playback,
            VoxLiveRecoveryClassificationPolicy.resolveUserFacingMessageRes(VoxLogCode.UNKNOWN_LIVE_STALL)
        )
    }
}
