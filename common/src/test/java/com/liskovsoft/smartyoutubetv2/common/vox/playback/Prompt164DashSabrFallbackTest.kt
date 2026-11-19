package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VideoLoaderController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
class Prompt164DashSabrFallbackTest {

    private lateinit var videoLoader: VideoLoaderController

    @Before
    fun setUp() {
        videoLoader = VideoLoaderController()
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        val field: Field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }

    private fun createMockFormatInfo(containsSabr: Boolean, isLive: Boolean): MediaItemFormatInfo {
        val invocationHandler = java.lang.reflect.InvocationHandler { _, method, _ ->
            when (method.name) {
                "containsSabrFormats" -> containsSabr
                "isLive" -> isLive
                "isLiveContent" -> isLive
                else -> null
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            MediaItemFormatInfo::class.java.classLoader,
            arrayOf(MediaItemFormatInfo::class.java),
            invocationHandler
        ) as MediaItemFormatInfo
    }

    @Test
    fun testCanFallbackToSabr_WhenConditionsMet_ReturnsTrue() {
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertTrue(videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenSabrAlreadyPlaying_ReturnsFalse() {
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", true)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse(videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenSabrCooldownActive_ReturnsFalse() {
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", true)

        assertFalse(videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenNoSabrFormats_ReturnsFalse() {
        val formatInfo = createMockFormatInfo(containsSabr = false, isLive = false)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse(videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenLiveStream_ReturnsFalse() {
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse(videoLoader.canFallbackToSabr())
    }

    @Test
    fun testActivateSabrFallback_EnablesForceSabrFallback() {
        assertFalse(videoLoader.isSabrFallbackActive)
        videoLoader.activateSabrFallback()
        assertTrue(videoLoader.isSabrFallbackActive)
    }

    @Test
    fun testOnNewVideo_DifferentVideo_ResetsFallbackAndCooldown() {
        videoLoader.activateSabrFallback()
        videoLoader.activateSabrCooldown()
        assertTrue(videoLoader.isSabrFallbackActive)

        setField(videoLoader, "mLastVideoId", "video_old_1")

        val newVideo = Video()
        newVideo.videoId = "video_new_2"
        videoLoader.onNewVideo(newVideo)

        assertFalse(videoLoader.isSabrFallbackActive)
    }
}
