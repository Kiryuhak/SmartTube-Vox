package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData
import com.liskovsoft.smartyoutubetv2.common.vot.VotAudioTrackHelper
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxPatch31RegressionTest {

    @Test
    fun testBackgroundPlaybackStateMachineStates() {
        val states = VoxBackgroundPlaybackState.values()
        assertEquals(8, states.size)
        assertTrue(states.contains(VoxBackgroundPlaybackState.IDLE))
        assertTrue(states.contains(VoxBackgroundPlaybackState.REQUESTED))
        assertTrue(states.contains(VoxBackgroundPlaybackState.ENTERING_AUDIO_ONLY))
        assertTrue(states.contains(VoxBackgroundPlaybackState.ACTIVE))
        assertTrue(states.contains(VoxBackgroundPlaybackState.FOCUS_LOST_TEMPORARY))
        assertTrue(states.contains(VoxBackgroundPlaybackState.RESTORING))
        assertTrue(states.contains(VoxBackgroundPlaybackState.EXITING))
        assertTrue(states.contains(VoxBackgroundPlaybackState.FAILED))
    }

    @Test
    fun testNewDiagnosticLogCodesExist() {
        assertNotNull(VoxLogCode.BACKGROUND_UI_DETACHED)
        assertEquals("BACKGROUND_UI_DETACHED", VoxLogCode.BACKGROUND_UI_DETACHED)

        assertNotNull(VoxLogCode.BACKGROUND_MEDIA_NOTIFICATION_CREATED)
        assertEquals("BACKGROUND_MEDIA_NOTIFICATION_CREATED", VoxLogCode.BACKGROUND_MEDIA_NOTIFICATION_CREATED)

        assertNotNull(VoxLogCode.TRANSLATION_UNSUPPORTED_SOURCE_LANGUAGE)
        assertEquals("TRANSLATION_UNSUPPORTED_SOURCE_LANGUAGE", VoxLogCode.TRANSLATION_UNSUPPORTED_SOURCE_LANGUAGE)
    }

    @Test
    fun testDownloadButtonConstantIntegration() {
        val downloadMask = PlayerTweaksData.PLAYER_BUTTON_DOWNLOAD
        assertEquals(1 shl 29, downloadMask)

        // Must be present in default mask
        val defaultMask = PlayerTweaksData.PLAYER_BUTTON_DEFAULT
        assertTrue((defaultMask and downloadMask) != 0)

        // Translation button must also be present in default mask
        val translateMask = PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE
        assertTrue((defaultMask and translateMask) != 0)
    }

    @Test
    fun testTargetLanguagesAndUnsupportedArabic() {
        assertTrue(VotAudioTrackHelper.isArabicLang("ar"))
        assertTrue(VotAudioTrackHelper.isArabicLang("ar-SA"))
        assertTrue(VotAudioTrackHelper.isArabicLang("ar-EG"))
        assertFalse(VotAudioTrackHelper.isArabicLang("ru"))
        assertFalse(VotAudioTrackHelper.isArabicLang("uk"))

        assertTrue(VotAudioTrackHelper.isUkrainianLang("uk"))
        assertTrue(VotAudioTrackHelper.isUkrainianLang("uk-UA"))
        assertFalse(VotAudioTrackHelper.isUkrainianLang("ru"))
    }

    @Test
    fun testCodecPolicyPrioritizesPreferredVideoCodecInAutoMode() {
        assertNotNull(VoxLogCode.TRACK_SELECTION_DECISION)
        assertEquals("TRACK_SELECTION_DECISION", VoxLogCode.TRACK_SELECTION_DECISION)

        val policy = com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCodecPolicy(
            mode = com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCodecPolicyMode.AUTO,
            preferredVideoCodec = com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxVideoCodecPreference.VP9
        )

        val videoCodecs = mapOf(
            "av1" to com.liskovsoft.smartyoutubetv2.common.vox.capability.VideoCodecCapability("av1", com.liskovsoft.smartyoutubetv2.common.vox.capability.TriStateCapability.SUPPORTED, true, 3840, 2160, 60),
            "vp9" to com.liskovsoft.smartyoutubetv2.common.vox.capability.VideoCodecCapability("vp9", com.liskovsoft.smartyoutubetv2.common.vox.capability.TriStateCapability.SUPPORTED, true, 3840, 2160, 60),
            "avc" to com.liskovsoft.smartyoutubetv2.common.vox.capability.VideoCodecCapability("avc", com.liskovsoft.smartyoutubetv2.common.vox.capability.TriStateCapability.SUPPORTED, true, 1920, 1080, 60)
        )
        val profile = com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxDeviceProfile(
            1, com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxPlatform.ANDROID_TV,
            "KTC", "KTC 4K SMART TV PH", "Android", "14",
            videoCodecs, emptyMap(),
            com.liskovsoft.smartyoutubetv2.common.vox.capability.DisplayCapability(),
            com.liskovsoft.smartyoutubetv2.common.vox.capability.AudioOutputCapability(),
            0L
        )

        val result = policy.selectVideoCodec(listOf("av1", "vp9", "avc"), profile)
        assertEquals("vp9", result.selected)
        assertFalse(result.isFallback)
    }

    @Test
    fun testMediaTrackUpdateCodecWeights() {
        com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack.updateCodecWeights("vp9")
        val vp9Weight = com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack.getCodecWeight("vp9")
        val av1Weight = com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack.getCodecWeight("av01")
        val avcWeight = com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack.getCodecWeight("avc")

        assertTrue(vp9Weight > av1Weight)
        assertTrue(vp9Weight > avcWeight)

        // Reset
        com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack.updateCodecWeights("auto")
    }
}
