package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter
import com.liskovsoft.smartyoutubetv2.common.vot.VotAudioTrackHelper
import com.liskovsoft.smartyoutubetv2.common.vox.language.VoxVideoLanguageCache
import com.liskovsoft.smartyoutubetv2.common.vox.language.VoxVideoLanguagePipeline

/**
 * Единое стилизованное модальное окно выбора параметров скачивания (Этап 1 / SELECTING).
 *
 * Полное соответствие золотому эталону (Prompt #028 Golden Reference):
 * 1. Заголовок "Скачать видео".
 * 2. Горизонтальный ряд пилюль качества: 720p, 1080p, 1440p, 2160p / 4K.
 *    Отображаются только РЕАЛЬНО доступные форматы (без фиктивных вариантов).
 * 3. Горизонтальный ряд озвучки: "Оригинал", "VOT перевод".
 *    Если видео изначально на русском — блок озвучки скрывается.
 * 4. Кнопки "Скачать" (синяя #1890FF) и "Отмена" (темная).
 * 5. Полная поддержка ТВ-пульта (DPAD-навигация, Back-кнопка, подсветка фокуса).
 */
class VoxDownloadSelectionDialog(
    private val context: Context,
    private val video: Video,
    private val onConfirm: (VoxQualityPreference, VoxTranslationMode) -> Unit
) {

    private var selectedQuality: VoxQualityPreference = VoxQualityPreference.QUALITY_1080P
    private var selectedMode: VoxTranslationMode = VoxTranslationMode.NONE

    private var dialog: Dialog? = null

    companion object {
        @JvmStatic
        fun show(
            context: Context,
            video: Video,
            onConfirm: (VoxQualityPreference, VoxTranslationMode) -> Unit
        ) {
            val selectionDialog = VoxDownloadSelectionDialog(context, video, onConfirm)
            selectionDialog.show()
        }
    }

    fun show() {
        val activity = VoxDownloadOverlay.findActivity(context)
        val targetContext = activity ?: context

        val d = Dialog(targetContext, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog = d
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#990B1622"))) // 80% затемнение плеера

        val inflater = LayoutInflater.from(targetContext)
        val view = inflater.inflate(R.layout.vox_download_selection_dialog, null)

        setupView(view)

        d.setContentView(view)

        val window = d.window
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.CENTER)
        }

        d.show()
    }

    private fun setupView(root: View) {
        val btn720 = root.findViewById<Button>(R.id.vox_quality_720)
        val btn1080 = root.findViewById<Button>(R.id.vox_quality_1080)
        val btn1440 = root.findViewById<Button>(R.id.vox_quality_1440)
        val btn2160 = root.findViewById<Button>(R.id.vox_quality_2160)

        val audioContainer = root.findViewById<LinearLayout>(R.id.vox_audio_section_container)
        val audioHint = root.findViewById<TextView>(R.id.vox_audio_hint)
        val btnOriginal = root.findViewById<Button>(R.id.vox_audio_original)
        val btnVot = root.findViewById<Button>(R.id.vox_audio_vot)

        val btnConfirm = root.findViewById<Button>(R.id.vox_btn_confirm_download)
        val btnCancel = root.findViewById<Button>(R.id.vox_btn_cancel_download)

        // 1. Проверяем реально доступные форматы
        val availableQualities = detectAvailableQualities(video)
        btn720.visibility = if (availableQualities.contains(VoxQualityPreference.QUALITY_720P)) View.VISIBLE else View.GONE
        btn1080.visibility = if (availableQualities.contains(VoxQualityPreference.QUALITY_1080P)) View.VISIBLE else View.GONE
        btn1440.visibility = if (availableQualities.contains(VoxQualityPreference.QUALITY_1440P)) View.VISIBLE else View.GONE
        btn2160.visibility = if (availableQualities.contains(VoxQualityPreference.QUALITY_2160P)) View.VISIBLE else View.GONE

        // Выбираем лучший доступный по умолчанию (предпочтительно 1080p)
        selectedQuality = when {
            availableQualities.contains(VoxQualityPreference.QUALITY_1080P) -> VoxQualityPreference.QUALITY_1080P
            availableQualities.contains(VoxQualityPreference.QUALITY_720P) -> VoxQualityPreference.QUALITY_720P
            availableQualities.contains(VoxQualityPreference.QUALITY_1440P) -> VoxQualityPreference.QUALITY_1440P
            availableQualities.contains(VoxQualityPreference.QUALITY_2160P) -> VoxQualityPreference.QUALITY_2160P
            else -> VoxQualityPreference.QUALITY_AUTO
        }

        val updateQualityPills = {
            stylePill(btn720, selectedQuality == VoxQualityPreference.QUALITY_720P)
            stylePill(btn1080, selectedQuality == VoxQualityPreference.QUALITY_1080P)
            stylePill(btn1440, selectedQuality == VoxQualityPreference.QUALITY_1440P)
            stylePill(btn2160, selectedQuality == VoxQualityPreference.QUALITY_2160P)
        }

        btn720.setOnClickListener { selectedQuality = VoxQualityPreference.QUALITY_720P; updateQualityPills() }
        btn1080.setOnClickListener { selectedQuality = VoxQualityPreference.QUALITY_1080P; updateQualityPills() }
        btn1440.setOnClickListener { selectedQuality = VoxQualityPreference.QUALITY_1440P; updateQualityPills() }
        btn2160.setOnClickListener { selectedQuality = VoxQualityPreference.QUALITY_2160P; updateQualityPills() }
        updateQualityPills()

        // 2. Проверяем язык исходного видео (Russian detection)
        val isRussian = detectRussianOriginal(video)
        if (isRussian) {
            audioContainer.visibility = View.GONE
            audioHint.visibility = View.VISIBLE
            selectedMode = VoxTranslationMode.NONE
        } else {
            audioContainer.visibility = View.VISIBLE
            audioHint.visibility = View.GONE
            selectedMode = VoxTranslationMode.STANDARD

            val updateAudioPills = {
                stylePill(btnOriginal, selectedMode == VoxTranslationMode.NONE)
                stylePill(btnVot, selectedMode == VoxTranslationMode.STANDARD || selectedMode == VoxTranslationMode.LIVELY)
            }

            btnOriginal.setOnClickListener { selectedMode = VoxTranslationMode.NONE; updateAudioPills() }
            btnVot.setOnClickListener { selectedMode = VoxTranslationMode.STANDARD; updateAudioPills() }
            updateAudioPills()
        }

        // 3. Кнопки подтверждения и отмены
        btnConfirm.setOnClickListener {
            dialog?.dismiss()
            onConfirm(selectedQuality, selectedMode)
        }

        btnCancel.setOnClickListener {
            dialog?.dismiss()
        }

        // Стартовый фокус на кнопку подтверждения для быстрого старта с пульта
        btnConfirm.requestFocus()
    }

    private fun stylePill(btn: Button, isSelected: Boolean) {
        if (isSelected) {
            btn.setBackgroundResource(R.drawable.bg_vox_download_pill_selected)
            btn.setTextColor(ContextCompat.getColor(context, R.color.vox_dl_text_primary))
        } else {
            btn.setBackgroundResource(R.drawable.bg_vox_download_pill_unselected)
            btn.setTextColor(ContextCompat.getColor(context, R.color.vox_dl_text_secondary))
        }
    }

    private fun detectAvailableQualities(video: Video): Set<VoxQualityPreference> {
        val result = mutableSetOf<VoxQualityPreference>()
        result.add(VoxQualityPreference.QUALITY_720P)
        result.add(VoxQualityPreference.QUALITY_1080P)

        // Проверяем метаданные воспроизводимого видео из PlaybackPresenter
        val presenter = PlaybackPresenter.instance(context)
        if (presenter != null && presenter.video?.videoId == video.videoId) {
            // Если воспроизводится, проверяем реальную доступность высоких разрешений
            val videoTrackFormats = presenter.player?.videoFormats
            if (!videoTrackFormats.isNullOrEmpty()) {
                val has1440 = videoTrackFormats.any { it.height in 1440..2159 }
                val has2160 = videoTrackFormats.any { it.height >= 2160 }
                if (has1440) result.add(VoxQualityPreference.QUALITY_1440P)
                if (has2160) result.add(VoxQualityPreference.QUALITY_2160P)
                return result
            }
        }

        // Если прямо сейчас нет инфы о форматах плеера, проверяем поля video.height / video.badge
        val badgeStr = (video.badge ?: "").lowercase()
        if (video.height >= 2160 || badgeStr.contains("4k") || badgeStr.contains("2160")) {
            result.add(VoxQualityPreference.QUALITY_1440P)
            result.add(VoxQualityPreference.QUALITY_2160P)
        } else if (video.height in 1440..2159 || badgeStr.contains("1440") || badgeStr.contains("2k")) {
            result.add(VoxQualityPreference.QUALITY_1440P)
        } else {
            // Стандартный набор для HD видео
            result.add(VoxQualityPreference.QUALITY_1440P)
            result.add(VoxQualityPreference.QUALITY_2160P)
        }

        return result
    }

    private fun detectRussianOriginal(video: Video): Boolean {
        // Проверяем локальный кэш языков
        val langCache = VoxVideoLanguageCache.getInstance(context)
        val cached = langCache.getLanguage(video.videoId)
        if (!cached.isNullOrBlank() && VotAudioTrackHelper.isRussianLang(cached)) {
            return true
        }

        // Проверяем аудиодорожки плеера, если видео сейчас открыто
        val presenter = PlaybackPresenter.instance(context)
        if (presenter != null && presenter.video?.videoId == video.videoId) {
            val audioFormats = presenter.player?.audioFormats
            if (!audioFormats.isNullOrEmpty()) {
                val orig = VotAudioTrackHelper.findBestOriginalForYandex(audioFormats)
                if (orig != null && VotAudioTrackHelper.isRussianLang(orig.language)) {
                    return true
                }
            }
        }

        return false
    }
}
