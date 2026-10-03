package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun testSelectVideoFormatExactOrLowerMatching() {
        val formats = listOf(
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.640028\"", height = 1080),
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.4d401f\"", height = 720),
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.4d401e\"", height = 480)
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
    fun testSelectVideoFormatFallbackDownWhenHigherUnavailable() {
        // 4K requested, but highest available is 1080p
        val formats = listOf(
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.640028\"", height = 1080),
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.4d401f\"", height = 720)
        )

        val resAuto = VoxDownloadFallbackPolicy.selectVideoFormat(formats, VoxQualityPreference.QUALITY_AUTO)
        assertNotNull(resAuto)
        assertEquals(1080, resAuto!!.selected.height)

        val res1080 = VoxDownloadFallbackPolicy.selectVideoFormat(formats, VoxQualityPreference.QUALITY_1080P)
        assertNotNull(res1080)
        assertEquals(1080, res1080!!.selected.height)
    }

    @Test
    fun testSelectVideoFormatPrefersAvcOverVp9AndAv1() {
        val formats = listOf(
            TestMediaFormat(mimeType = "video/webm; codecs=\"vp9\"", height = 1080),
            TestMediaFormat(mimeType = "video/mp4; codecs=\"av01.0.08M.08\"", height = 1080),
            TestMediaFormat(mimeType = "video/mp4; codecs=\"avc1.640028\"", height = 1080)
        )

        val res = VoxDownloadFallbackPolicy.selectVideoFormat(formats, VoxQualityPreference.QUALITY_1080P)
        assertNotNull(res)
        assertTrue(res!!.selected.mimeType!!.contains("avc"))
    }

    @Test
    fun testSelectVideoFormatFallbackToVp9WhenAvcUnavailable() {
        val formats = listOf(
            TestMediaFormat(mimeType = "video/webm; codecs=\"vp9\"", height = 1080)
        )

        val res = VoxDownloadFallbackPolicy.selectVideoFormat(formats, VoxQualityPreference.QUALITY_1080P)
        assertNotNull(res)
        assertEquals(1080, res!!.selected.height)
        assertTrue(res.fallbackApplied)
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
