package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticsPrivacyAllowlistTest {

    @Test
    fun testCleanEventIsAllowed() {
        val event = VoxLogEvent(
            timestamp = System.currentTimeMillis(),
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = "PLAYER_DECODER_ERROR",
            message = "MediaCodec error c2.android.avc.decoder",
            context = mapOf("codec" to "avc", "width" to "1920", "height" to "1080")
        )

        assertTrue(VoxDiagnosticsPolicy.isEventAllowedByPrivacy(event))
    }

    @Test
    fun testEventWithForbiddenContextKeyIsRejected() {
        val eventWithPassword = VoxLogEvent(
            timestamp = System.currentTimeMillis(),
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.NETWORK,
            code = "AUTH_FAILED",
            message = "Failed to authenticate",
            context = mapOf("password" to "secret123")
        )
        assertFalse(VoxDiagnosticsPolicy.isEventAllowedByPrivacy(eventWithPassword))

        val eventWithToken = VoxLogEvent(
            timestamp = System.currentTimeMillis(),
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.YANDEX_AUTH,
            code = "TOKEN_EXPIRED",
            message = "Token expired",
            context = mapOf("access_token" to "y0_AgAAAA...")
        )
        assertFalse(VoxDiagnosticsPolicy.isEventAllowedByPrivacy(eventWithToken))

        val eventWithCookie = VoxLogEvent(
            timestamp = System.currentTimeMillis(),
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.NETWORK,
            code = "COOKIE_INVALID",
            message = "Invalid cookie",
            context = mapOf("cookie" to "SID=abc123xyz")
        )
        assertFalse(VoxDiagnosticsPolicy.isEventAllowedByPrivacy(eventWithCookie))
    }

    @Test
    fun testEventWithBearerOrSecretInMessageIsRejected() {
        val event = VoxLogEvent(
            timestamp = System.currentTimeMillis(),
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.NETWORK,
            code = "HTTP_500",
            message = "Authorization: Bearer ya29.a0AfH6SM..."
        )
        assertFalse(VoxDiagnosticsPolicy.isEventAllowedByPrivacy(event))
    }
}
