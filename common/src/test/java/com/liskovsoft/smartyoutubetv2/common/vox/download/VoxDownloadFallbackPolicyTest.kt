package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxDownloadFallbackPolicyTest {

    private class TestMediaFormat(
        private val url: String? = "https://example.com/stream",
        private val mimeType: String? = "video/mp4; codecs=\"avc1.640028\"",
        private val height: Int = 1080,
        private val width: Int = 1920,
        private val bitrate: String? = "5000000",
        private val clen: String? = "100000000",
        private val itag: String? = "137"
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
        override fun getAudioTrackId(): String? = null
        override fun getWidth(): Int = width
        override fun getHeight(): Int = height
        override fun getIndex(): String? = null
        override fun getInit(): String? = null
        override fun getFps(): String? = "30"
        override fun getLmt(): String? = null
        override fun getQualityLabel(): String? = null
        override fun getFormat(): String? = null
        override fun isOtf(): Boolean = false
        override fun getOtfInitUrl(): String? = null
        override fun getOtfTemplateUrl(): String? = null
        override fun getLanguage(): String? = null

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

    @Test
    fun testResolutionInvariants() {
        // Standard 16:9
        assertEquals(2160, VoxDownloadFallbackPolicy.getEffectiveResolution(3840, 2160))
        assertEquals(1440, VoxDownloadFallbackPolicy.getEffectiveResolution(2560, 1440))
        assertEquals(1080, VoxDownloadFallbackPolicy.getEffectiveResolution(1920, 1080))
        assertEquals(720, VoxDownloadFallbackPolicy.getEffectiveResolution(1280, 720))

        // Portrait / Shorts (9:16)
        assertEquals(2160, VoxDownloadFallbackPolicy.getEffectiveResolution(2160, 3840))
        assertEquals(1440, VoxDownloadFallbackPolicy.getEffectiveResolution(1440, 2560))
        assertEquals(1080, VoxDownloadFallbackPolicy.getEffectiveResolution(1080, 1920))
        assertEquals(720, VoxDownloadFallbackPolicy.getEffectiveResolution(720, 1280))

        // Ultrawide / Cinematic (2.4:1 / 2.35:1)
        assertEquals(2160, VoxDownloadFallbackPolicy.getEffectiveResolution(3840, 1600))
        assertEquals(2160, VoxDownloadFallbackPolicy.getEffectiveResolution(3840, 1620))
        assertEquals(1440, VoxDownloadFallbackPolicy.getEffectiveResolution(2560, 1080))
        assertEquals(1080, VoxDownloadFallbackPolicy.getEffectiveResolution(1920, 800))
        assertEquals(720, VoxDownloadFallbackPolicy.getEffectiveResolution(1280, 540))

        // DCI 4K (1.9:1)
        assertEquals(2160, VoxDownloadFallbackPolicy.getEffectiveResolution(4096, 2160))

        // Height-only legacy formats
        assertEquals(2160, VoxDownloadFallbackPolicy.getEffectiveResolution(0, 2160))
        assertEquals(1440, VoxDownloadFallbackPolicy.getEffectiveResolution(0, 1440))
        assertEquals(1080, VoxDownloadFallbackPolicy.getEffectiveResolution(0, 1080))
        assertEquals(720, VoxDownloadFallbackPolicy.getEffectiveResolution(0, 720))
    }

    @Test
    fun testSelectVideoFormatExactMatching() {
        val formats = listOf(
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.640028\"", height = 1080, width = 1920),
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.4d401f\"", height = 720, width = 1280),
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.4d401e\"", height = 480, width = 854)
        )

        val res1080 = VoxDownloadFallbackPolicy.selectVideoFormat(formats, VoxQualityPreference.QUALITY_1080P)
        assertNotNull(res1080)
        assertEquals(1080, res1080!!.selected.height)
        assertFalse(res1080.fallbackApplied)

        val res720 = VoxDownloadFallbackPolicy.selectVideoFormat(formats, VoxQualityPreference.QUALITY_720P)
        assertNotNull(res720)
        assertEquals(720, res720!!.selected.height)
        assertFalse(res720.fallbackApplied)
    }

    @Test
    fun testExplicitQualityEliminatesSilentDowngrade() {
        // Only 1080p and 720p available
        val formats = listOf(
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.640028\"", height = 1080, width = 1920),
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.4d401f\"", height = 720, width = 1280)
        )

        // When 4K (2160p) is explicitly requested, MUST return null instead of silently downloading 1080p/720p!
        val res4k = VoxDownloadFallbackPolicy.selectVideoFormat(formats, VoxQualityPreference.QUALITY_2160P)
        assertNull("Explicit 4K request must not silently downgrade to 1080p/720p", res4k)

        // When 2K (1440p) is explicitly requested, MUST return null
        val res1440 = VoxDownloadFallbackPolicy.selectVideoFormat(formats, VoxQualityPreference.QUALITY_1440P)
        assertNull("Explicit 1440p request must not silently downgrade to 1080p/720p", res1440)

        // But QUALITY_AUTO selects best available (1080p) honestly with note
        val resAuto = VoxDownloadFallbackPolicy.selectVideoFormat(formats, VoxQualityPreference.QUALITY_AUTO)
        assertNotNull(resAuto)
        assertEquals(1080, resAuto!!.selected.height)
        assertEquals("Выбрано наилучшее доступное: 1080p", resAuto.note)
    }

    @Test
    fun testSelectVideoFormat4KVariants() {
        // Test widescreen 4K (3840x1600)
        val widescreen4k = listOf(
            TestMediaFormat(mimeType = "video/webm; codecs=\"vp9\"", height = 1600, width = 3840, itag = "313"),
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.640028\"", height = 1080, width = 1920)
        )
        val resWide = VoxDownloadFallbackPolicy.selectVideoFormat(widescreen4k, VoxQualityPreference.QUALITY_2160P)
        assertNotNull(resWide)
        assertEquals(1600, resWide!!.selected.height)
        assertEquals(3840, resWide.selected.width)

        // Test portrait 4K (2160x3840)
        val portrait4k = listOf(
            TestMediaFormat(mimeType = "video/webm; codecs=\"vp9\"", height = 3840, width = 2160, itag = "313"),
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.640028\"", height = 1080, width = 1920)
        )
        val resPort = VoxDownloadFallbackPolicy.selectVideoFormat(portrait4k, VoxQualityPreference.QUALITY_2160P)
        assertNotNull(resPort)
        assertEquals(3840, resPort!!.selected.height)
        assertEquals(2160, resPort.selected.width)

        // Test DCI 4K (4096x2160)
        val dci4k = listOf(
            TestMediaFormat(mimeType = "video/webm; codecs=\"vp9\"", height = 2160, width = 4096, itag = "313")
        )
        val resDci = VoxDownloadFallbackPolicy.selectVideoFormat(dci4k, VoxQualityPreference.QUALITY_2160P)
        assertNotNull(resDci)
        assertEquals(4096, resDci!!.selected.width)
    }

    @Test
    fun testSelectVideoFormatPrefersAvcOverVp9ForStandardRes() {
        val formats = listOf(
            TestMediaFormat(mimeType = "video/webm; codecs=\"vp9\"", height = 1080, width = 1920),
            TestMediaFormat(mimeType = "video/mp4; codecs=\"av01.0.08M.08\"", height = 1080, width = 1920),
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.640028\"", height = 1080, width = 1920)
        )

        val res = VoxDownloadFallbackPolicy.selectVideoFormat(formats, VoxQualityPreference.QUALITY_1080P)
        assertNotNull(res)
        assertTrue(res!!.selected.mimeType!!.contains("avc"))
    }

    @Test
    fun testSelectVideoFormatUhdPrefersVp9OverAv1() {
        val formats = listOf(
            TestMediaFormat(mimeType = "video/mp4; codecs=\"av01.0.12M.08\"", height = 2160, width = 3840, itag = "401"),
            TestMediaFormat(mimeType = "video/webm; codecs=\"vp9\"", height = 2160, width = 3840, itag = "313")
        )

        val res = VoxDownloadFallbackPolicy.selectVideoFormat(formats, VoxQualityPreference.QUALITY_2160P)
        assertNotNull(res)
        assertTrue(res!!.selected.mimeType!!.contains("vp9"))
    }

    @Test
    fun testSelectAudioFormatPrefersAacAndMaxBitrate() {
        val formats = listOf(
            TestMediaFormat(mimeType = "audio/webm; codecs=\"opus\"", bitrate = "160000", height = 0),
            TestMediaFormat(mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = "128000", height = 0),
            TestMediaFormat(mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = "64000", height = 0)
        )

        val res = VoxDownloadFallbackPolicy.selectAudioFormat(formats)
        assertNotNull(res)
        assertTrue(res!!.selected.mimeType!!.contains("mp4"))
        assertEquals("128000", res.selected.bitrate)
        assertFalse(res.fallbackApplied)
    }

    @Test
    fun testSelectAudioFormatFallbackToOpusWhenAacUnavailable() {
        val formats = listOf(
            TestMediaFormat(mimeType = "audio/webm; codecs=\"opus\"", bitrate = "160000", height = 0)
        )

        val res = VoxDownloadFallbackPolicy.selectAudioFormat(formats)
        assertNotNull(res)
        assertTrue(res!!.selected.mimeType!!.contains("opus"))
        assertTrue(res.fallbackApplied)
    }
}
