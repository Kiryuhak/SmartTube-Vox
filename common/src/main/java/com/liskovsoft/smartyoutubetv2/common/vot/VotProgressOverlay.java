package com.liskovsoft.smartyoutubetv2.common.vot;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

public class VotProgressOverlay {
    private static final String TAG = "VotProgressOverlay";

    public static final int STATE_IDLE = 0;
    public static final int STATE_PREPARING = 1;
    public static final int STATE_WAITING_WITH_ETA = 2;
    public static final int STATE_ETA_EXPIRED_STILL_WAITING = 3;
    public static final int STATE_READY = 4;
    public static final int STATE_ERROR = 5;
    public static final int STATE_TIMEOUT = 6;
    public static final int STATE_RETRY = 7;

    private static final long DISMISS_DELAY_READY_MS = 2000L;
    private static final long DISMISS_DELAY_ERROR_MS = 3500L;

    private final Context mContext;
    private View mOverlayView;
    private ProgressBar mSpinner;
    private ImageView mIcon;
    private TextView mTitle;
    private TextView mSubtitle;
    private TextView mTimer;
    private int mCurrentState = STATE_IDLE;
    private ViewGroup mParentView;

    private final Runnable mAutoDismissRunnable = this::hide;

    public VotProgressOverlay(Context context) {
        mContext = context;
    }

    public int getState() {
        return mCurrentState;
    }

    public boolean isShown() {
        return mOverlayView != null && mOverlayView.getVisibility() == View.VISIBLE && mCurrentState != STATE_IDLE;
    }

    public void showPreparing(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_PREPARING;
        showSpinnerMode();
        setTitle(getStringSafe(R.string.vot_progress_preparing));
        setSubtitle(getStringSafe(R.string.vot_progress_subtitle_default));
        hideTimer();
        fadeIn();
    }

    public void showStarting(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_PREPARING;
        showSpinnerMode();
        setTitle(getStringSafe(R.string.vot_progress_starting));
        setSubtitle(getStringSafe(R.string.vot_progress_subtitle_default));
        hideTimer();
        fadeIn();
    }

    public void showWaitingWithEta(@Nullable Activity activity, String timeRemainingFormatted) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_WAITING_WITH_ETA;
        showSpinnerMode();
        setTitle(getStringSafe(R.string.vot_progress_waiting_title));
        setSubtitle(getStringSafe(R.string.vot_progress_subtitle_default));
        setTimer("~ " + timeRemainingFormatted);
        fadeIn();
    }

    public void showStillWaiting(@Nullable Activity activity, String timeElapsedFormatted) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_ETA_EXPIRED_STILL_WAITING;
        showSpinnerMode();
        setTitle(getStringSafe(R.string.vot_progress_waiting_title));
        setSubtitle(getStringSafe(R.string.vot_progress_subtitle_default));
        setTimer("+" + timeElapsedFormatted);
        fadeIn();
    }

    public void showRetryWait(@Nullable Activity activity, int attempt, int maxAttempts, int secondsRemaining) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_RETRY;
        showIconMode(R.drawable.ic_vot_retry);
        String title = formatStringSafe(R.string.vot_progress_retry_title, attempt, maxAttempts);
        if (title.isEmpty()) {
            title = "Повторная попытка " + attempt + "/" + maxAttempts;
        }
        setTitle(title);
        setSubtitle(getStringSafe(R.string.vot_progress_subtitle_default));
        String timer = formatStringSafe(R.string.vot_progress_retry_countdown, secondsRemaining);
        if (timer.isEmpty()) {
            timer = "через " + secondsRemaining + " с";
        }
        setTimer(timer);
        fadeIn();
    }

    public void showReady(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_READY;
        showIconMode(R.drawable.ic_vot_ready);
        setTitle(getStringSafe(R.string.vot_progress_ready));
        setSubtitle(getStringSafe(R.string.vot_progress_subtitle_ready));
        hideTimer();
        fadeIn();
        Utils.postDelayed(mAutoDismissRunnable, DISMISS_DELAY_READY_MS);
    }

    public void showError(@Nullable Activity activity) {
        showError(activity, null);
    }

    public void showError(@Nullable Activity activity, @Nullable String errorMessage) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_ERROR;
        showIconMode(R.drawable.ic_vot_error);
        if (errorMessage != null && !errorMessage.isEmpty()) {
            setTitle(errorMessage);
        } else {
            setTitle(getStringSafe(R.string.vot_progress_error));
        }
        hideSubtitle();
        hideTimer();
        fadeIn();
        Utils.postDelayed(mAutoDismissRunnable, DISMISS_DELAY_ERROR_MS);
    }

    public void showTimeout(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_TIMEOUT;
        showIconMode(R.drawable.ic_vot_timeout);
        setTitle(getStringSafe(R.string.vot_progress_timeout));
        hideSubtitle();
        hideTimer();
        fadeIn();
        Utils.postDelayed(mAutoDismissRunnable, DISMISS_DELAY_ERROR_MS);
    }

    public void hide() {
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_IDLE;
        if (mOverlayView != null) {
            mOverlayView.animate().cancel();
            mOverlayView.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        if (mOverlayView != null) {
                            mOverlayView.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    public void dismissImmediately() {
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_IDLE;
        if (mOverlayView != null) {
            mOverlayView.animate().cancel();
            mOverlayView.setVisibility(View.GONE);
            mOverlayView.setAlpha(0f);
        }
    }

    public void destroy() {
        dismissImmediately();
        if (mOverlayView != null && mParentView != null) {
            mParentView.removeView(mOverlayView);
        }
        mOverlayView = null;
        mParentView = null;
        mSpinner = null;
        mIcon = null;
        mTitle = null;
        mSubtitle = null;
        mTimer = null;
    }

    private void showSpinnerMode() {
        if (mSpinner != null) mSpinner.setVisibility(View.VISIBLE);
        if (mIcon != null) mIcon.setVisibility(View.GONE);
    }

    private void showIconMode(int iconResId) {
        if (mSpinner != null) mSpinner.setVisibility(View.GONE);
        if (mIcon != null) {
            mIcon.setImageDrawable(ContextCompat.getDrawable(mContext, iconResId));
            mIcon.setVisibility(View.VISIBLE);
        }
    }

    private void setTitle(String text) {
        if (mTitle != null) {
            mTitle.setText(text);
        }
    }

    private void setSubtitle(String text) {
        if (mSubtitle != null) {
            if (text != null && !text.isEmpty()) {
                mSubtitle.setText(text);
                mSubtitle.setVisibility(View.VISIBLE);
            } else {
                mSubtitle.setVisibility(View.GONE);
            }
        }
    }

    private void hideSubtitle() {
        if (mSubtitle != null) {
            mSubtitle.setVisibility(View.GONE);
        }
    }

    private void setTimer(String text) {
        if (mTimer != null) {
            mTimer.setText(text);
            mTimer.setVisibility(View.VISIBLE);
        }
    }

    private void hideTimer() {
        if (mTimer != null) {
            mTimer.setVisibility(View.GONE);
        }
    }

    private void fadeIn() {
        if (mOverlayView != null) {
            mOverlayView.bringToFront();
            mOverlayView.animate().cancel();
            if (mOverlayView.getVisibility() != View.VISIBLE) {
                mOverlayView.setAlpha(0f);
                mOverlayView.setVisibility(View.VISIBLE);
            }
            mOverlayView.animate().alpha(1f).setDuration(200).start();
        }
    }

    private boolean ensureAttached(@Nullable Activity activity) {
        if (mOverlayView != null && mOverlayView.getParent() != null) {
            mOverlayView.bringToFront();
            return true;
        }
        if (activity == null || activity.isFinishing()) {
            return false;
        }

        ViewGroup targetContainer = findTargetContainer(activity);
        if (targetContainer == null) {
            Log.w(TAG, "Cannot attach overlay: no target container found");
            return false;
        }

        try {
            View existing = targetContainer.findViewById(R.id.vot_progress_root);
            if (existing != null) {
                bindViews(existing);
                mParentView = targetContainer;
                mOverlayView.bringToFront();
                return true;
            }

            LayoutInflater inflater = LayoutInflater.from(mContext);
            mOverlayView = inflater.inflate(R.layout.vot_progress_overlay, targetContainer, false);
            bindViews(mOverlayView);

            mOverlayView.setFocusable(false);
            mOverlayView.setFocusableInTouchMode(false);
            mOverlayView.setClickable(false);

            if (targetContainer instanceof FrameLayout) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                lp.rightMargin = dpToPx(48);
                lp.bottomMargin = dpToPx(88);
                mOverlayView.setLayoutParams(lp);
            } else if (targetContainer instanceof LinearLayout) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.gravity = Gravity.END;
                lp.bottomMargin = dpToPx(88);
                mOverlayView.setLayoutParams(lp);
            } else {
                ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.rightMargin = dpToPx(48);
                lp.bottomMargin = dpToPx(88);
                mOverlayView.setLayoutParams(lp);
            }

            mOverlayView.setVisibility(View.GONE);
            mOverlayView.setAlpha(0f);

            targetContainer.addView(mOverlayView);
            mOverlayView.bringToFront();
            mParentView = targetContainer;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to inflate VOT progress overlay: %s", e.getMessage());
            return false;
        }
    }

    private void bindViews(View root) {
        mOverlayView = root;
        mSpinner = root.findViewById(R.id.vot_progress_spinner);
        mIcon = root.findViewById(R.id.vot_progress_icon);
        mTitle = root.findViewById(R.id.vot_progress_text);
        mSubtitle = root.findViewById(R.id.vot_progress_subtitle);
        mTimer = root.findViewById(R.id.vot_progress_timer);
    }

    private ViewGroup findTargetContainer(@Nullable Activity activity) {
        if (activity == null) {
            return null;
        }
        int rootId = activity.getResources().getIdentifier("playback_fragment_root", "id", activity.getPackageName());
        if (rootId != 0) {
            View root = activity.findViewById(rootId);
            if (root instanceof ViewGroup) {
                return (ViewGroup) root;
            }
        }
        View content = activity.findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            return (ViewGroup) content;
        }
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decor instanceof ViewGroup) {
            return (ViewGroup) decor;
        }
        return null;
    }

    private String getStringSafe(int resId) {
        if (mContext == null || resId == 0) return "";
        try {
            return mContext.getString(resId);
        } catch (Exception e) {
            return "";
        }
    }

    private String formatStringSafe(int resId, Object... args) {
        if (mContext == null || resId == 0) return "";
        try {
            return mContext.getString(resId, args);
        } catch (Exception e) {
            return "";
        }
    }

    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                mContext.getResources().getDisplayMetrics()
        );
    }

    // --- Helpers for regression tests ---

    public String getTitleText() {
        return mTitle != null && mTitle.getText() != null ? mTitle.getText().toString() : "";
    }

    public String getSubtitleText() {
        return mSubtitle != null && mSubtitle.getText() != null ? mSubtitle.getText().toString() : "";
    }

    public String getTimerText() {
        return mTimer != null && mTimer.getText() != null ? mTimer.getText().toString() : "";
    }

    public boolean isSpinnerVisible() {
        return mSpinner != null && mSpinner.getVisibility() == View.VISIBLE;
    }

    public boolean isIconVisible() {
        return mIcon != null && mIcon.getVisibility() == View.VISIBLE;
    }

    public boolean isTimerVisible() {
        return mTimer != null && mTimer.getVisibility() == View.VISIBLE;
    }
}
