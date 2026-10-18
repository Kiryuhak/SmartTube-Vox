package com.liskovsoft.smartyoutubetv2.common.vox.voice

import com.liskovsoft.smartyoutubetv2.common.R

object VoxVoiceId {
    const val STANDARD = "standard"
    const val LIVE = "live"
}

data class VoxVoiceActor(
    val id: String,
    val titleResId: Int,
    val descriptionResId: Int,
    val isLively: Boolean,
    val requiresAccount: Boolean
)

object VoxVoiceCatalog {
    val STANDARD_VOICE = VoxVoiceActor(
        id = VoxVoiceId.STANDARD,
        titleResId = R.string.vot_voice_standard_title,
        descriptionResId = R.string.vot_voice_standard_desc,
        isLively = false,
        requiresAccount = false
    )

    val LIVE_VOICE = VoxVoiceActor(
        id = VoxVoiceId.LIVE,
        titleResId = R.string.vot_voice_live_title,
        descriptionResId = R.string.vot_voice_live_desc,
        isLively = true,
        requiresAccount = true
    )

    val ALL_VOICES = listOf(STANDARD_VOICE, LIVE_VOICE)

    @JvmStatic
    fun getVoices(): List<VoxVoiceActor> = ALL_VOICES

    @JvmStatic
    fun getVoiceById(id: String?): VoxVoiceActor {
        if (id.equals(VoxVoiceId.LIVE, ignoreCase = true)) {
            return LIVE_VOICE
        }
        return STANDARD_VOICE
    }

    @JvmStatic
    fun getDefaultVoice(): VoxVoiceActor = STANDARD_VOICE
}
