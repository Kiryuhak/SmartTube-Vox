package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.annotation.TargetApi;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.fragment.app.Fragment;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerEngine;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.misc.BackgroundPlaybackService;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;

/**
 * Loads PlaybackFragment and delegates input from a game controller.
 * <br>
 * For more information on game controller capabilities with leanback, review the
 * <a href="https://developer.android.com/training/game-controllers/controller-input.html">docs</href>.
 */
public class PlaybackActivity extends LeanbackActivity {
    private static final String TAG = PlaybackActivity.class.getSimpleName();
    private static final float GAMEPAD_TRIGGER_INTENSITY_ON = 0.5f;
    // Off-condition slightly smaller for button debouncing.
    private static final float GAMEPAD_TRIGGER_INTENSITY_OFF = 0.45f;
    private boolean gamepadTriggerPressed = false;
    private PlaybackFragment mPlaybackFragment;
    private boolean mIsBackgroundAudioOnlyActive = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_playback);
        Fragment fragment =
                getSupportFragmentManager().findFragmentByTag(getString(R.string.playback_tag));
        if (fragment instanceof PlaybackFragment) {
            mPlaybackFragment = (PlaybackFragment) fragment;
        }
    }

    @Override
    protected void initTheme() {
        int playerThemeResId = MainUIData.instance(this).getColorScheme().playerThemeResId;
        if (playerThemeResId > 0) {
            setTheme(playerThemeResId);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mPlaybackFragment != null) {
            mPlaybackFragment.onDispatchKeyEvent(event);
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mPlaybackFragment != null) {
            mPlaybackFragment.onDispatchTouchEvent(event);
        }

        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (mPlaybackFragment != null) {
            mPlaybackFragment.onDispatchGenericMotionEvent(event);
        }

        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
            mPlaybackFragment.skipToNext();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            mPlaybackFragment.skipToPrevious();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            mPlaybackFragment.rewind();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            mPlaybackFragment.fastForward();
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        // This method will handle gamepad events.
        if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > GAMEPAD_TRIGGER_INTENSITY_ON
                && !gamepadTriggerPressed) {
            mPlaybackFragment.rewind();
            gamepadTriggerPressed = true;
        } else if (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > GAMEPAD_TRIGGER_INTENSITY_ON
                && !gamepadTriggerPressed) {
            mPlaybackFragment.fastForward();
            gamepadTriggerPressed = true;
        } else if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) < GAMEPAD_TRIGGER_INTENSITY_OFF
                && event.getAxisValue(MotionEvent.AXIS_RTRIGGER) < GAMEPAD_TRIGGER_INTENSITY_OFF) {
            gamepadTriggerPressed = false;
        } else if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0 && event.getAction() == MotionEvent.ACTION_SCROLL) {
            // mouse wheel handling
            Utils.volumeUp(this, getPlaybackView(), event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    // For N devices that support it, not "officially"
    // More: https://medium.com/s23nyc-tech/drop-in-android-video-exoplayer2-with-picture-in-picture-e2d4f8c1eb30
    @TargetApi(24)
    @SuppressWarnings("deprecation")
    private void enterPipMode() {
        // NOTE: When exiting PIP mode onPause is called immediately after onResume

        // Also, avoid enter pip on stop!
        // More info: https://developer.android.com/guide/topics/ui/picture-in-picture#continuing_playback

        if (Helpers.isPictureInPictureSupported(this)) {
            if (wannaEnterToPip()) {
                Log.d(TAG, "Entering PIP mode...");

                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        PictureInPictureParams.Builder params = new PictureInPictureParams.Builder();
                        enterPictureInPictureMode(params.build());
                    } else {
                        enterPictureInPictureMode();
                    }
                } catch (Exception e) {
                    // Device doesn't support picture-in-picture mode
                    Log.e(TAG, e.getMessage());
                }
            }
        }
    }

    /**
     * BACK pressed, PIP player's button pressed
     */
    @Override
    public void finish() {
        Log.d(TAG, "Finishing activity...");

        //if (isBackgroundBackEnabled()) {
        //    mPlaybackFragment.blockEngine(true);
        //}

        // NOTE: When exiting PIP mode onPause is called immediately after onResume

        // Also, avoid enter pip on stop!
        // More info: https://developer.android.com/guide/topics/ui/picture-in-picture#continuing_playback

        if (mIsBackgroundAudioOnlyActive) {
            exitBackgroundAudioOnly();
        }

        if (wannaEnterToPip() && !skipPip()) {
            enterPipMode(); // NOTE: without this call app will hangs when pressing on PIP button
        }

        if (doNotDestroy() && !skipPip()) {
            mPlaybackFragment.blockEngine(true);
            // Ensure to opening this activity when the user is returning to the app
            getViewManager().blockTop(this);
            getViewManager().startParentView(this);
        } else {
            if (getPlayerTweaksData().isKeepFinishedActivityEnabled()) {
                //moveTaskToBack(true); // Don't do this or you'll have problems when player overlaps other apps (e.g. casting)
                getViewManager().startParentView(this);

                // Player with TextureView keeps running in background because onStop() fired with huge delay (~5sec).
                mPlaybackFragment.maybeReleasePlayer();
            } else {
                super.finish();
            }
        }
    }

    @Override
    public void finishReally() {
        if (mIsBackgroundAudioOnlyActive) {
            exitBackgroundAudioOnly();
        }
        BackgroundPlaybackService.stop(this);
        super.finishReally();

        mPlaybackFragment.onFinish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mIsBackgroundAudioOnlyActive) {
            exitBackgroundAudioOnly();
        }
    }

    @Override
    protected void onDestroy() {
        if (mIsBackgroundAudioOnlyActive) {
            exitBackgroundAudioOnly();
        }
        unregisterAudioFocusListener();
        BackgroundPlaybackService.stop(this);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        boolean hasDialogBug = AppDialogPresenter.instance(this).isDialogShown() && Build.VERSION.SDK_INT <= 23;
        boolean isScreenOff = getPlayerData().getBackgroundMode() != PlayerData.BACKGROUND_MODE_DEFAULT && Utils.isHardScreenOff(this);

        if (hasDialogBug || isScreenOff) {
            mPlaybackFragment.blockEngine(true);
        }

        // Run the code before the contained fragment
        super.onPause();
    }

    @SuppressWarnings("deprecation")
    private void enterBackgroundPlayMode() {
        if (Build.VERSION.SDK_INT >= 21 && Build.VERSION.SDK_INT < 26) {
            if (Build.VERSION.SDK_INT == 21) {
                // Playback pause fix?
                mPlaybackFragment.showOverlay(true);
            }

            if (mPlaybackFragment.isPlaying()) {
                // Argument equals true to notify the system that the activity
                // wishes to be visible behind other translucent activities
                if (!requestVisibleBehind(true)) {
                    // App-specific method to stop playback and release resources
                    // because call to requestVisibleBehind(true) failed
                    mPlaybackFragment.onDestroy();
                }
            } else {
                // Argument equals false because the activity is not playing
                requestVisibleBehind(false);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onVisibleBehindCanceled() {
        // App-specific method to stop playback and release resources
        mPlaybackFragment.onDestroy();
        super.onVisibleBehindCanceled();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);

        mPlaybackFragment.onPIPChanged(isInPictureInPictureMode);
    }

    /**
     * HOME or BACK pressed
     */
    @Override
    public void onUserLeaveHint() {
        // Check that user not open dialog/search activity instead of really leaving the activity
        // Activity may be overlapped by the dialog, back is pressed or new view started
        if (isBackPressed() || isFinishing() || getViewManager().isNewViewPending() || getGeneralData().getBackgroundPlaybackShortcut() == GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_BACK) {
            return;
        }

        int backgroundMode = getPlayerData().getBackgroundMode();
        VoxSafeLogger.info(
                VoxLogCategory.BACKGROUND,
                VoxLogCode.BACKGROUND_PLAYBACK_REQUESTED,
                "Запрошен переход в фоновый режим по нажатию Home (mode=" + backgroundMode + ")"
        );

        if (backgroundMode == PlayerData.BACKGROUND_MODE_DEFAULT) {
            VoxSafeLogger.info(
                    VoxLogCategory.BACKGROUND,
                    VoxLogCode.BACKGROUND_PLAYBACK_BLOCKED,
                    "Фоновое воспроизведение отключено в настройках, остановка плеера"
            );
            if (mPlaybackFragment != null && mPlaybackFragment.isPlaying()) {
                mPlaybackFragment.onPause();
                VoxSafeLogger.info(
                        VoxLogCategory.BACKGROUND,
                        VoxLogCode.BACKGROUND_PLAYER_PAUSED,
                        "Плеер приостановлен при уходе в фон"
                );
            }
            return;
        }

        VoxSafeLogger.info(
                VoxLogCategory.BACKGROUND,
                VoxLogCode.BACKGROUND_PLAYBACK_ALLOWED,
                "Фоновое воспроизведение разрешено (mode=" + backgroundMode + ")"
        );

        switch (backgroundMode) {
            case PlayerData.BACKGROUND_MODE_PLAY_BEHIND:
                enterBackgroundPlayMode();
                break;
            case PlayerData.BACKGROUND_MODE_PIP:
                enterPipMode();
                if (doNotDestroy()) {
                    mPlaybackFragment.blockEngine(true);
                    getViewManager().blockTop(this);
                }
                break;
            case PlayerData.BACKGROUND_MODE_SOUND:
                enterBackgroundAudioOnly();
                break;
        }
    }

    private AudioManager.OnAudioFocusChangeListener mAudioFocusListener;

    private void enterBackgroundAudioOnly() {
        if (mPlaybackFragment == null) return;
        mIsBackgroundAudioOnlyActive = true;
        VoxSafeLogger.info(
                VoxLogCategory.BACKGROUND,
                VoxLogCode.BACKGROUND_AUDIO_ONLY_ENTER,
                "Вход в режим воспроизведения Только аудио"
        );
        mPlaybackFragment.blockEngine(true);
        getViewManager().blockTop(this);
        // Отключаем видеопоток, чтобы ExoPlayer не застревал в BUFFERING без Surface
        mPlaybackFragment.setVideoTrackEnabled(false);
        // Регистрируем управление AudioFocus для отслеживания событий системы
        registerAudioFocusListener();
        // Запускаем службу фонового воспроизведения для удержания приоритета процесса
        BackgroundPlaybackService.start(this);
        VoxSafeLogger.info(
                VoxLogCategory.BACKGROUND,
                VoxLogCode.BACKGROUND_PLAYER_CONTINUED,
                "Воспроизведение звука продолжено в фоновом режиме"
        );
    }

    private void exitBackgroundAudioOnly() {
        if (!mIsBackgroundAudioOnlyActive) return;
        mIsBackgroundAudioOnlyActive = false;
        unregisterAudioFocusListener();
        VoxSafeLogger.info(
                VoxLogCategory.BACKGROUND,
                VoxLogCode.BACKGROUND_AUDIO_ONLY_EXIT,
                "Выход из режима Только аудио, возврат на передний план"
        );
        if (mPlaybackFragment != null) {
            mPlaybackFragment.setVideoTrackEnabled(true);
            mPlaybackFragment.blockEngine(false);
        }
        BackgroundPlaybackService.stop(this);
    }

    private void registerAudioFocusListener() {
        if (mAudioFocusListener == null) {
            mAudioFocusListener = focusChange -> {
                if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                    VoxSafeLogger.info(
                            VoxLogCategory.BACKGROUND,
                            VoxLogCode.BACKGROUND_AUDIO_FOCUS_GAIN,
                            "Audio focus gained in background"
                    );
                } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                           focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                           focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    VoxSafeLogger.warn(
                            VoxLogCategory.BACKGROUND,
                            VoxLogCode.BACKGROUND_AUDIO_FOCUS_LOSS,
                            "Audio focus lost in background (focusChange=" + focusChange + ")"
                    );
                }
            };
        }
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            int res = am.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                VoxSafeLogger.info(
                        VoxLogCategory.BACKGROUND,
                        VoxLogCode.BACKGROUND_AUDIO_FOCUS_GAIN,
                        "Audio focus granted for background playback"
                );
            }
        }
    }

    private void unregisterAudioFocusListener() {
        if (mAudioFocusListener != null) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.abandonAudioFocus(mAudioFocusListener);
            }
        }
    }

    public boolean isInPipMode() {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }

        return isInPictureInPictureMode();
    }

    public PlaybackView getPlaybackView() {
        return mPlaybackFragment;
    }

    private boolean skipPip() {
        return isBackPressed() && getGeneralData().getBackgroundPlaybackShortcut() == GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME;
    }

    private boolean isEngineBlocked() {
        return mPlaybackFragment != null && mPlaybackFragment.isEngineBlocked();
    }

    @TargetApi(24)
    private boolean wannaEnterToPip() {
        boolean isPip = getPlayerData().getBackgroundMode() == PlayerData.BACKGROUND_MODE_PIP;
        return isPip && !isInPictureInPictureMode();
    }

    private boolean doNotDestroy() {
        sIsInPipMode = isInPipMode();
        boolean isBackground = getPlayerData().getBackgroundMode() == PlayerData.BACKGROUND_MODE_SOUND ||
                getPlayerData().getBackgroundMode() == PlayerData.BACKGROUND_MODE_PIP ||
                isEngineBlocked();
        return sIsInPipMode || isBackground;
    }

    //private boolean isBackgroundBackEnabled() {
    //    return getGeneralData().getBackgroundPlaybackShortcut() == GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_BACK ||
    //            getGeneralData().getBackgroundPlaybackShortcut() == GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME_BACK;
    //}
}
