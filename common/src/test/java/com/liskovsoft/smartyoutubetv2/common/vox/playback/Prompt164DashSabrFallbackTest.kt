package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.content.Context
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VideoLoaderController
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
class Prompt164DashSabrFallbackTest {

    private lateinit var videoLoader: VideoLoaderController
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        videoLoader = VideoLoaderController()
        // Reset tweaks preference to AUTO by default
        PlayerTweaksData.instance(context).videoTransportPreference = PlayerTweaksData.VIDEO_TRANSPORT_AUTO
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field: Field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(obj, value)
                return
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException(fieldName)
    }

    private fun createMockFormatInfo(
        containsSabr: Boolean,
        isLive: Boolean,
        isUnplayable: Boolean = false,
        containsDash: Boolean = true
    ): MediaItemFormatInfo {
        val invocationHandler = java.lang.reflect.InvocationHandler { _, method, _ ->
            when (method.name) {
                "containsSabrFormats" -> containsSabr
                "containsDashFormats" -> containsDash
                "isLive" -> isLive
                "isLiveContent" -> isLive
                "isUnplayable" -> isUnplayable
                "isPlayable" -> !isUnplayable
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
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertTrue(videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenSabrAlreadyPlaying_ReturnsFalse() {
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", true)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse(videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenSabrCooldownActive_ReturnsFalse() {
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", true)

        assertFalse(videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenNoSabrFormats_ReturnsFalse() {
        val formatInfo = createMockFormatInfo(containsSabr = false, isLive = false, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse(videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenNoDashFormats_ReturnsFalse() {
        // If the stream is not DASH (e.g. pure HLS or URL list), DASH -> SABR fallback does not apply
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = false)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse("Absence of DASH formats must prevent DASH->SABR fallback", videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenLiveStream_ReturnsFalse() {
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = true, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse(videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenVideoUnplayable_ReturnsFalse() {
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = true, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse(videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenFormatInfoNull_ReturnsFalse() {
        setField(videoLoader, "mFormatInfo", null)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse(videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenExplicitDashPreference_ReturnsFalse() {
        PlayerTweaksData.instance(context).videoTransportPreference = PlayerTweaksData.VIDEO_TRANSPORT_DASH
        setField(videoLoader, "mContext", context)

        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse("Explicit DASH transport must NOT fall back to SABR automatically", videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenExplicitSabrPreference_ReturnsFalse() {
        PlayerTweaksData.instance(context).videoTransportPreference = PlayerTweaksData.VIDEO_TRANSPORT_SABR
        setField(videoLoader, "mContext", context)

        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse("Explicit SABR transport does not need fallback check", videoLoader.canFallbackToSabr())
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

        assertFalse("Fallback flag must be reset on new video", videoLoader.isSabrFallbackActive)

        // Also verify cooldown was reset by checking canFallbackToSabr on the new video
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        assertTrue("Cooldown must be reset on new video, making fallback eligible again", videoLoader.canFallbackToSabr())
    }

    @Test
    fun testOnNewVideo_SameVideo_PreservesFallback() {
        videoLoader.activateSabrFallback()
        assertTrue(videoLoader.isSabrFallbackActive)

        setField(videoLoader, "mLastVideoId", "video_same_1")

        val sameVideo = Video()
        sameVideo.videoId = "video_same_1"
        videoLoader.onNewVideo(sameVideo)

        assertTrue(videoLoader.isSabrFallbackActive)
    }

    @Test
    fun testPingPongPrevention_WhenCooldownActive_FallbackBlocked() {
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)

        assertTrue(videoLoader.canFallbackToSabr())

        videoLoader.activateSabrCooldown()

        assertFalse("Cooldown must strictly block fallback to SABR", videoLoader.canFallbackToSabr())
    }
}
