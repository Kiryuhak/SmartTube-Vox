package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.content.Context
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.ErrorFixerController
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
        setField(videoLoader, "mContext", context)
        setField(videoLoader, "mIsDashPlaying", true)
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

    private fun getField(obj: Any, fieldName: String): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field: Field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj)
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
        containsDash: Boolean = true,
        isLiveContent: Boolean = false
    ): MediaItemFormatInfo {
        val invocationHandler = java.lang.reflect.InvocationHandler { _, method, _ ->
            when (method.name) {
                "containsSabrFormats" -> containsSabr
                "containsDashFormats" -> containsDash
                "isLive" -> isLive
                "isLiveContent" -> isLiveContent
                "isUnplayable" -> isUnplayable
                "isPlayable" -> !isUnplayable
                "containsUrlFormats" -> false
                "containsHlsUrl" -> false
                "getStartTimeMs" -> 0L
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
    fun testCanFallbackToSabr_WhenDashNotPlaying_ReturnsFalse() {
        // If DASH is not the active transport (e.g. HLS or URL stream), fallback must be rejected
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsDashPlaying", false)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse("Inactive DASH playback must reject DASH->SABR fallback", videoLoader.canFallbackToSabr())
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
    fun testCanFallbackToSabr_WhenLiveContent_ReturnsFalse() {
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true, isLiveContent = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse("isLiveContent must be excluded from SABR fallback", videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenVideoObjectIsLive_ReturnsFalse() {
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true)
        val liveVideo = Video()
        liveVideo.isLive = true

        val testLoader = object : VideoLoaderController() {
            override fun getVideo(): Video {
                return liveVideo
            }
        }
        setField(testLoader, "mContext", context)
        setField(testLoader, "mIsDashPlaying", true)
        setField(testLoader, "mFormatInfo", formatInfo)
        setField(testLoader, "mIsSabrPlaying", false)
        setField(testLoader, "mSabrCooldownActive", false)

        assertFalse("Live video object must be excluded from SABR fallback", testLoader.canFallbackToSabr())
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
    fun testCanFallbackToSabr_WhenContextNull_ReturnsFalse() {
        setField(videoLoader, "mContext", null)
        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse("Null context must safely reject fallback without assuming AUTO", videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenFormatInfoNullWithContext_ReturnsFalseWithoutThrowing() {
        setField(videoLoader, "mFormatInfo", null)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse("Null format info with attached context must safely return false without NPE", videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenExplicitDashPreference_ReturnsFalse() {
        PlayerTweaksData.instance(context).videoTransportPreference = PlayerTweaksData.VIDEO_TRANSPORT_DASH

        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsSabrPlaying", false)
        setField(videoLoader, "mSabrCooldownActive", false)

        assertFalse("Explicit DASH transport must NOT fall back to SABR automatically", videoLoader.canFallbackToSabr())
    }

    @Test
    fun testCanFallbackToSabr_WhenExplicitSabrPreference_ReturnsFalse() {
        PlayerTweaksData.instance(context).videoTransportPreference = PlayerTweaksData.VIDEO_TRANSPORT_SABR

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

        val formatInfo = createMockFormatInfo(containsSabr = true, isLive = false, isUnplayable = false, containsDash = true)
        setField(videoLoader, "mFormatInfo", formatInfo)
        setField(videoLoader, "mIsDashPlaying", true)
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

    @Test
    fun testErrorFixerController_OnSourceRefreshRequested_SetsCooldownIfSabrWasPlaying() {
        val errorFixer = ErrorFixerController()
        setField(errorFixer, "mContext", context)
        setField(errorFixer, "mVideoLoaderController", videoLoader)

        // Simulate SABR was active
        setField(videoLoader, "mIsSabrPlaying", true)
        assertFalse(getField(videoLoader, "mSabrCooldownActive") as Boolean)

        // Trigger onSourceRefreshRequested via recovery handler reflection
        val handler = getField(errorFixer, "mRecoveryActionHandler") as VoxPlayerRecoveryController.PlayerRecoveryActionHandler
        handler.onSourceRefreshRequested(1000L, true, 1L)

        // Verify cooldown was activated to prevent SABR ping-pong
        assertTrue("Cooldown must be activated when SABR stream refresh is requested", getField(videoLoader, "mSabrCooldownActive") as Boolean)
    }

    @Test
    fun testErrorFixerController_OnEngineSwitchRequested_SetsCooldownIfSabrWasPlaying() {
        val errorFixer = ErrorFixerController()
        setField(errorFixer, "mContext", context)
        setField(errorFixer, "mVideoLoaderController", videoLoader)

        // Simulate SABR was active
        setField(videoLoader, "mIsSabrPlaying", true)
        assertFalse(getField(videoLoader, "mSabrCooldownActive") as Boolean)

        // Trigger onEngineSwitchRequested via recovery handler reflection
        val handler = getField(errorFixer, "mRecoveryActionHandler") as VoxPlayerRecoveryController.PlayerRecoveryActionHandler
        handler.onEngineSwitchRequested(1, 1000L, true, 1L)

        // Verify cooldown was activated
        assertTrue("Cooldown must be activated when engine switch is requested for active SABR", getField(videoLoader, "mSabrCooldownActive") as Boolean)
    }
}
