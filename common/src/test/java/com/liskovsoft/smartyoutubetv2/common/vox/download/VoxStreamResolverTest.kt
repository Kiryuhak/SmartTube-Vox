package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.mediaserviceinterfaces.MediaItemService
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class VoxStreamResolverTest {

    private class MockFormat(
        private val url: String? = "https://rr.googlevideo.com/videoplayback?id=test",
        private val mimeType: String? = "video/mp4; codecs=\"avc1.640028\"",
        private val height: Int = 1080,
        private val width: Int = 1920,
        private val bitrate: String? = "5000000",
        private val clen: String? = "100000000",
        private val itag: String? = "137",
        private val audioTrackId: String? = null,
        private val language: String? = null
    ) : MediaFormat {
        override fun getFormatType(): Int = MediaFormat.FORMAT_TYPE_DASH
        override fun getUrl(): String? = url
        override fun getMimeType(): String? = mimeType
        override fun getITag(): String? = itag
        override fun isDrc(): Boolean = false
        override fun getClen(): String? = clen
        override fun getBitrate(): String? = bitrate
        override fun getProjectionType(): String? = null
        override fun getXtags(): String? = null
        override fun getAudioTrackId(): String? = audioTrackId
        override fun getWidth(): Int = width
        override fun getHeight(): Int = height
        override fun getIndex(): String? = null
        override fun getInit(): String? = null
        override fun getFps(): String? = "30"
        override fun getLmt(): String? = null
        override fun getQualityLabel(): String? = "${height}p"
        override fun getFormat(): String? = null
        override fun isOtf(): Boolean = false
        override fun getOtfInitUrl(): String? = null
        override fun getOtfTemplateUrl(): String? = null
        override fun getLanguage(): String? = language
        override fun getTargetDurationSec(): Int = 0
        override fun getMaxDvrDurationSec(): Int = 0
        override fun getApproxDurationMs(): Int = 0
        override fun getQuality(): String? = null
        override fun getSignature(): String? = null
        override fun getAudioSamplingRate(): String? = null
        override fun getSourceUrl(): String? = null
        override fun getSegmentUrlList(): List<String>? = null
        override fun getGlobalSegmentList(): List<String>? = null
        override fun compareTo(other: MediaFormat?): Int = 0
    }

    private fun createMockFormatInfo(adaptive: List<MediaFormat>): MediaItemFormatInfo {
        return Proxy.newProxyInstance(
            MediaItemFormatInfo::class.java.classLoader,
            arrayOf(MediaItemFormatInfo::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getAdaptiveFormats" -> adaptive
                "getUrlFormats" -> emptyList<MediaFormat>()
                "containsMedia" -> true
                "containsDashFormats" -> true
                else -> null
            }
        } as MediaItemFormatInfo
    }

    private fun createMockService(formatInfo: MediaItemFormatInfo?): MediaItemService {
        return Proxy.newProxyInstance(
            MediaItemService::class.java.classLoader,
            arrayOf(MediaItemService::class.java)
        ) { _, method, _ ->
            if (method.name == "getFormatInfo") {
                formatInfo
            } else {
                null
            }
        } as MediaItemService
    }

    @Test
    fun testResolveVideoStream1080p() {
        val f1080 = MockFormat(height = 1080, width = 1920, itag = "137")
        val f720 = MockFormat(height = 720, width = 1280, itag = "136")
        val service = createMockService(createMockFormatInfo(listOf(f1080, f720)))
        val resolver = DefaultVoxStreamResolver { service }

        val resolved = resolver.resolveVideoStream("vid1", VoxQualityPreference.QUALITY_1080P)
        assertNotNull(resolved)
        assertEquals(1080, resolved.height)
        assertEquals(137, resolved.itag)
    }

    @Test
    fun testResolveVideoStream1440p() {
        val f1440 = MockFormat(
            height = 1440,
            width = 2560,
            itag = "271",
            mimeType = "video/webm; codecs=\"vp9\""
        )
        val f1080 = MockFormat(height = 1080, width = 1920, itag = "137")
        val service = createMockService(createMockFormatInfo(listOf(f1440, f1080)))
        val resolver = DefaultVoxStreamResolver { service }

        val resolved = resolver.resolveVideoStream("vid2", VoxQualityPreference.QUALITY_1440P)
        assertNotNull(resolved)
        assertEquals(1440, resolved.height)
        assertEquals(271, resolved.itag)
        assertTrue(resolved.codec.contains("vp9"))
    }

    @Test
    fun testResolveVideoStream2160pUhd() {
        val f2160 = MockFormat(
            height = 2160,
            width = 3840,
            itag = "313",
            mimeType = "video/webm; codecs=\"vp9\""
        )
        val f1080 = MockFormat(height = 1080, width = 1920, itag = "137")
        val service = createMockService(createMockFormatInfo(listOf(f2160, f1080)))
        val resolver = DefaultVoxStreamResolver { service }

        val resolved = resolver.resolveVideoStream("vid3", VoxQualityPreference.QUALITY_2160P)
        assertNotNull(resolved)
        assertEquals(2160, resolved.height)
        assertEquals(313, resolved.itag)
    }

    @Test
    fun testResolveVideoStreamThrowsQualityUnavailableWhenExplicitQualityMissing() {
        val f1080 = MockFormat(height = 1080, width = 1920, itag = "137")
        val f720 = MockFormat(height = 720, width = 1280, itag = "136")
        val service = createMockService(createMockFormatInfo(listOf(f1080, f720)))
        val resolver = DefaultVoxStreamResolver { service }

        // Request 4K (2160p) when best is 1080p -> must throw NO_FORMAT_2160P or QUALITY_UNAVAILABLE (no silent downgrade!)
        try {
            resolver.resolveVideoStream("vid4", VoxQualityPreference.QUALITY_2160P)
            org.junit.Assert.fail("Expected VoxDownloadException for NO_FORMAT_2160P")
        } catch (e: VoxDownloadException) {
            assertTrue(e.code == VoxDownloadErrorCode.NO_FORMAT_2160P || e.code == VoxDownloadErrorCode.QUALITY_UNAVAILABLE)
            assertTrue(e.message?.contains("2160p") == true)
        }

        // Auto request on same video resolves to best available (1080p)
        val autoResolved = resolver.resolveVideoStream("vid4", VoxQualityPreference.QUALITY_AUTO)
        assertNotNull(autoResolved)
        assertEquals(1080, autoResolved.height)
    }

    @Test(expected = VoxDownloadException::class)
    fun testResolveVideoStreamThrowsWhenNoVideoCandidates() {
        val audioOnly = MockFormat(
            mimeType = "audio/mp4",
            height = 0,
            width = 0,
            itag = "140"
        )
        val service = createMockService(createMockFormatInfo(listOf(audioOnly)))
        val resolver = DefaultVoxStreamResolver { service }

        resolver.resolveVideoStream("vid5", VoxQualityPreference.QUALITY_1080P)
    }

    @Test
    fun testResolveOriginalAudioSelectsBestNonDubbedAudio() {
        val ruAutoDub = MockFormat(
            mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
            itag = "140",
            audioTrackId = "ru.dubbed-auto",
            language = "ru (dubbed-auto)",
            bitrate = "128000"
        )
        val enOriginal = MockFormat(
            mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
            itag = "141",
            audioTrackId = "en.original",
            language = "en (original)",
            bitrate = "256000"
        )
        val service = createMockService(createMockFormatInfo(listOf(ruAutoDub, enOriginal)))
        val resolver = DefaultVoxStreamResolver { service }

        val resolved = resolver.resolveOriginalAudio("vid6")
        assertNotNull(resolved)
        assertEquals(141, resolved.itag)
    }

    @Test
    fun testResolveOriginalAudioSingleTrackSafety() {
        val singleDubbed = MockFormat(
            mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
            itag = "140",
            audioTrackId = "ru.dubbed-auto",
            language = "ru (dubbed-auto)",
            bitrate = "128000"
        )
        val service = createMockService(createMockFormatInfo(listOf(singleDubbed)))
        val resolver = DefaultVoxStreamResolver { service }

        // When only 1 track exists, fallback pool safely selects it rather than failing
        val resolved = resolver.resolveOriginalAudio("vid7")
        assertNotNull(resolved)
        assertEquals(140, resolved.itag)
    }

    @Test(expected = VoxDownloadException::class)
    fun testResolveVideoStreamThrowsWhenServiceUnavailable() {
        val resolver = DefaultVoxStreamResolver { null }
        resolver.resolveVideoStream("vid8", VoxQualityPreference.QUALITY_1080P)
    }

    @Test(expected = VoxDownloadException::class)
    fun testUrlSecurityValidationRejectsInsecureUrl() {
        val insecureFormat = MockFormat(url = "file:///etc/passwd")
        val service = createMockService(createMockFormatInfo(listOf(insecureFormat)))
        val resolver = DefaultVoxStreamResolver { service }

        resolver.resolveVideoStream("vid9", VoxQualityPreference.QUALITY_1080P)
    }
}
