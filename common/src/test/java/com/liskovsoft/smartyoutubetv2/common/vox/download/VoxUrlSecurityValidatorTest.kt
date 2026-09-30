package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoxUrlSecurityValidatorTest {

    @Test
    fun testValidHttpsCdnUrlsAllowed() {
        VoxUrlSecurityValidator.validateUrl("https://rr1---sn-4g5edn6s.googlevideo.com/videoplayback?id=123")
        VoxUrlSecurityValidator.validateUrl("https://manifest.googlevideo.com/api/manifest/dash/id/123")
        VoxUrlSecurityValidator.validateUrl("https://vtrans.yandex.net/audio/translation_123.mp3")
        VoxUrlSecurityValidator.validateUrl("https://browser.yandex.net/api/v1/translate")
        VoxUrlSecurityValidator.validateUrl("https://smarttube-vox-yandex-oauth-broker.amn2402.workers.dev/auth/device")
    }

    @Test
    fun testUnsafeSchemesRejected() {
        assertRejected("http://googlevideo.com/video")
        assertRejected("file:///etc/passwd")
        assertRejected("content://media/external/images/media/1")
        assertRejected("ftp://yandex.net/file.mp3")
        assertRejected("javascript:alert(1)")
        assertRejected("data:text/plain;base64,SGVsbG8=")
    }

    @Test
    fun testLocalAndPrivateIpsRejected() {
        assertRejected("https://localhost/video")
        assertRejected("https://127.0.0.1/video")
        assertRejected("https://192.168.1.100/video")
        assertRejected("https://10.0.0.1/video")
        assertRejected("https://172.16.0.1/video")
        assertRejected("https://169.254.1.1/video")
        assertRejected("https://0.0.0.0/video")
    }

    @Test
    fun testUntrustedHostsRejected() {
        assertRejected("https://malicious-site.com/video.mp4")
        assertRejected("https://evil-hacker.ru/audio.mp3")
    }

    private fun assertRejected(url: String) {
        try {
            VoxUrlSecurityValidator.validateUrl(url)
            fail("Expected VoxDownloadException for unsafe URL: $url")
        } catch (e: VoxDownloadException) {
            assertTrue(e.code == VoxDownloadErrorCode.INVALID_URL)
        }
    }
}
