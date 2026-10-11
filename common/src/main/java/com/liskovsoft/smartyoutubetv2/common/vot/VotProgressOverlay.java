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

/**
 * SmartTube VOX — Translation Motion UI 2.0.
 *
 * Provides a modern, responsive, subtitle-safe overlay for voice translation:
 * 1. Initial State (~4.5s): Large horizontal glass-style pill ("Подготавливаем перевод…")
 * 2. Spatial Continuity Collapse: Smooth scale/alpha/translation bounds animation into a compact circular indicator.
 * 3. Compact Preparing State: Centered Hourglass icon with subtle circular ring animation (NO fake %).
 * 4. Real Progress State: Displays actual 1..100% only when the backend provider gives verified progress.
 * 5. Ready State: Green checkmark, green accent, brief "Перевод готов" confirmation (~1.5-2.5s), smooth fade out.
 * 6. Error/Timeout UX: Compact failure icon ('!'), clear failure text, bounded auto-dismiss.
 * 7. Hard State Watchdog: Prevents overlay from getting stuck indefinitely in preparing state.
 */
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
    public static final int STATE_LOOKING_UP = 8;
    public static final int STATE_LOOKUP_READY = 9;

    public static final long COLLAPSE_DELAY_MS = 4500L;
    private static final long DISMISS_DELAY_READY_MS = 2000L;
    private static final long DISMISS_DELAY_ERROR_MS = 3500L;
    private static final long OVERLAY_WATCHDOG_TIMEOUT_MS = 45000L; // Max 45s before safety dismiss

    private final Context mContext;
    private View mOverlayView;
    private View mExpandedContainer;
    private View mCompactContainer;
    private ProgressBar mSpinner;
    private ImageView mIcon;
    private TextView mTitle;
    private TextView mSubtitle;
    private TextView mTimer;

    // Compact motion elements
    private ProgressBar mCompactSpinner;
    private ImageView mCompactIcon;
    private TextView mCompactPercent;

    private int mCurrentState = STATE_IDLE;
    private ViewGroup mParentView;
    private boolean mIsCollapsed = false;
    private int mRealProgressPercent = -1;
    private long mLastProgressUpdateTime = 0L;

    private final Runnable mAutoDismissRunnable = this::hide;
    private final Runnable mCollapseRunnable = this::performCollapseAnimation;
    private final Runnable mSafetyWatchdogRunnable = () -> {
        if (mCurrentState == STATE_PREPARING || mCurrentState == STATE_LOOKING_UP ||
                mCurrentState == STATE_WAITING_WITH_ETA || mCurrentState == STATE_ETA_EXPIRED_STILL_WAITING) {
            Log.w(TAG, "VOT overlay watchdog triggered: stuck in preparing state for > %d ms", OVERLAY_WATCHDOG_TIMEOUT_MS);
            dismissImmediately();
        }
    };

    public VotProgressOverlay(Context context) {
        mContext = context;
    }

    public int getState() {
        return mCurrentState;
    }

    public boolean isShown() {
        return mOverlayView != null && mOverlayView.getVisibility() == View.VISIBLE && mCurrentState != STATE_IDLE;
    }

    public boolean isCollapsed() {
        return mIsCollapsed;
    }

    public int getRealProgressPercent() {
        return mRealProgressPercent;
    }

    public boolean isCompactContainerVisible() {
        return mCompactContainer != null && mCompactContainer.getVisibility() == View.VISIBLE;
    }

    public boolean isExpandedContainerVisible() {
        return mExpandedContainer != null && mExpandedContainer.getVisibility() == View.VISIBLE;
    }

    private void cancelTimers() {
        Utils.removeCallbacks(mAutoDismissRunnable);
        Utils.removeCallbacks(mCollapseRunnable);
        Utils.removeCallbacks(mSafetyWatchdogRunnable);
    }

    private void armSafetyWatchdog() {
        Utils.removeCallbacks(mSafetyWatchdogRunnable);
        Utils.postDelayed(mSafetyWatchdogRunnable, OVERLAY_WATCHDOG_TIMEOUT_MS);
    }

    public void showLookingUp(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        cancelTimers();
        mCurrentState = STATE_LOOKING_UP;
        mRealProgressPercent = -1;
        expandMode();
        showSpinnerMode();
        setTitle(getStringSafe(R.string.vot_progress_looking_up));
        setSubtitle(getStringSafe(R.string.vot_progress_subtitle_default));
        hideTimer();
        fadeIn();
        Utils.postDelayed(mCollapseRunnable, COLLAPSE_DELAY_MS);
        armSafetyWatchdog();
    }

    public void showLookupReady(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        cancelTimers();
        mCurrentState = STATE_LOOKUP_READY;
        expandMode();
        showIconMode(R.drawable.ic_vot_ready);
        setTitle(getStringSafe(R.string.vot_progress_lookup_ready));
        hideSubtitle();
        hideTimer();
        fadeIn();
        Utils.postDelayed(mAutoDismissRunnable, DISMISS_DELAY_READY_MS);
    }

    public void showPreparing(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        cancelTimers();
        mCurrentState = STATE_PREPARING;
        mRealProgressPercent = -1;
        expandMode();
        showSpinnerMode();
        setTitle(getStringSafe(R.string.vot_progress_preparing));
        setSubtitle(getStringSafe(R.string.vot_progress_subtitle_default));
        hideTimer();
        fadeIn();
        Utils.postDelayed(mCollapseRunnable, COLLAPSE_DELAY_MS);
        armSafetyWatchdog();
    }

    public void showStarting(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        cancelTimers();
        mCurrentState = STATE_PREPARING;
        mRealProgressPercent = -1;
        expandMode();
        showSpinnerMode();
        setTitle(getStringSafe(R.string.vot_progress_starting));
        setSubtitle(getStringSafe(R.string.vot_progress_subtitle_default));
        hideTimer();
        fadeIn();
        Utils.postDelayed(mCollapseRunnable, COLLAPSE_DELAY_MS);
        armSafetyWatchdog();
    }

    public void showWaitingWithEta(@Nullable Activity activity, String timeRemainingFormatted) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_WAITING_WITH_ETA;
        armSafetyWatchdog();

        if (mIsCollapsed) {
            // Keep compact circle with hourglass, update compact state
            updateCompactView();
        } else {
            showSpinnerMode();
            setTitle(getStringSafe(R.string.vot_progress_waiting_title));
            String etaText = formatStringSafe(R.string.vot_progress_waiting_eta_format, timeRemainingFormatted);
            setSubtitle(etaText.isEmpty() ? getStringSafe(R.string.vot_progress_subtitle_default) : etaText);
            setTimer("~ " + timeRemainingFormatted);
            fadeIn();
        }
    }

    public void showWaitingWithoutEta(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_PREPARING;
        armSafetyWatchdog();

        if (mIsCollapsed) {
            updateCompactView();
        } else {
            showSpinnerMode();
            setTitle(getStringSafe(R.string.vot_progress_waiting_title));
            setSubtitle(getStringSafe(R.string.vot_progress_subtitle_default));
            hideTimer();
            fadeIn();
        }
    }

    public void showDelayedWaiting(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_ETA_EXPIRED_STILL_WAITING;
        armSafetyWatchdog();

        if (mIsCollapsed) {
            updateCompactView();
        } else {
            showSpinnerMode();
            setTitle(getStringSafe(R.string.vot_progress_delayed));
            setSubtitle(getStringSafe(R.string.vot_progress_subtitle_default));
            hideTimer();
            fadeIn();
        }
    }

    public void showAttaching(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_PREPARING;
        armSafetyWatchdog();

        if (mIsCollapsed) {
            updateCompactView();
        } else {
            showSpinnerMode();
            setTitle(getStringSafe(R.string.vot_progress_attaching));
            hideSubtitle();
            hideTimer();
            fadeIn();
        }
    }

    public void showStillWaiting(@Nullable Activity activity, String timeElapsedFormatted) {
        showDelayedWaiting(activity);
    }

    public void showRetryWait(@Nullable Activity activity, int attempt, int maxAttempts, int secondsRemaining) {
        if (!ensureAttached(activity)) return;
        Utils.removeCallbacks(mAutoDismissRunnable);
        mCurrentState = STATE_RETRY;
        armSafetyWatchdog();

        expandMode();
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

    /**
     * Updates real verified progress (1..100%) from backend.
     * Prevents fake progress and throttles UI updates.
     */
    public void updateRealProgress(int percent) {
        if (percent < 0 || percent > 100) return;
        if (percent < mRealProgressPercent && percent > 0) return; // Monotonic only

        long now = System.currentTimeMillis();
        if (now - mLastProgressUpdateTime < 150L && percent != 100) {
            return; // Throttle UI redraw rate
        }
        mLastProgressUpdateTime = now;
        mRealProgressPercent = percent;
        armSafetyWatchdog();

        if (mIsCollapsed) {
            updateCompactView();
        }
    }

    public void showReady(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        cancelTimers();
        mCurrentState = STATE_READY;

        if (mIsCollapsed) {
            // In compact mode, show green checkmark on circle
            if (mCompactIcon != null) {
                mCompactIcon.setImageDrawable(ContextCompat.getDrawable(mContext, R.drawable.ic_vot_ready));
                mCompactIcon.setVisibility(View.VISIBLE);
            }
            if (mCompactPercent != null) mCompactPercent.setVisibility(View.GONE);
            if (mCompactSpinner != null) mCompactSpinner.setVisibility(View.GONE);
            if (mCompactContainer != null) {
                mCompactContainer.animate().cancel();
                mCompactContainer.setVisibility(View.VISIBLE);
                mCompactContainer.setAlpha(1f);
            }
        } else {
            showIconMode(R.drawable.ic_vot_ready);
            setTitle(getStringSafe(R.string.vot_progress_ready));
            setSubtitle(getStringSafe(R.string.vot_progress_subtitle_ready));
            hideTimer();
            fadeIn();
        }

        Utils.postDelayed(mAutoDismissRunnable, DISMISS_DELAY_READY_MS);
    }

    public void showActivated(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        cancelTimers();
        mCurrentState = STATE_READY;

        if (mIsCollapsed) {
            if (mCompactIcon != null) {
                mCompactIcon.setImageDrawable(ContextCompat.getDrawable(mContext, R.drawable.ic_vot_ready));
                mCompactIcon.setVisibility(View.VISIBLE);
            }
            if (mCompactPercent != null) mCompactPercent.setVisibility(View.GONE);
            if (mCompactSpinner != null) mCompactSpinner.setVisibility(View.GONE);
            if (mCompactContainer != null) {
                mCompactContainer.animate().cancel();
                mCompactContainer.setVisibility(View.VISIBLE);
                mCompactContainer.setAlpha(1f);
            }
        } else {
            showIconMode(R.drawable.ic_vot_ready);
            setTitle(getStringSafe(R.string.vot_translation_activated));
            hideSubtitle();
            hideTimer();
            fadeIn();
        }

        Utils.postDelayed(mAutoDismissRunnable, DISMISS_DELAY_READY_MS);
    }

    public void showError(@Nullable Activity activity) {
        showError(activity, null);
    }

    public void showError(@Nullable Activity activity, @Nullable String errorMessage) {
        if (!ensureAttached(activity)) return;
        cancelTimers();
        mCurrentState = STATE_ERROR;

        expandMode();
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
        cancelTimers();
        mCurrentState = STATE_TIMEOUT;

        expandMode();
        showIconMode(R.drawable.ic_vot_timeout);
        setTitle(getStringSafe(R.string.vot_progress_timeout));
        hideSubtitle();
        hideTimer();
        fadeIn();
        Utils.postDelayed(mAutoDismissRunnable, DISMISS_DELAY_ERROR_MS);
    }

    /**
     * Executes smooth spatial collapse animation from expanded pill to compact circular indicator.
     */
    public void performCollapseAnimation() {
        if (mExpandedContainer == null || mCompactContainer == null) return;
        if (mCurrentState != STATE_PREPARING && mCurrentState != STATE_LOOKING_UP &&
                mCurrentState != STATE_WAITING_WITH_ETA && mCurrentState != STATE_ETA_EXPIRED_STILL_WAITING) {
            return;
        }

        mIsCollapsed = true;
        updateCompactView();

        // Spatial continuity: animate expanded pill shrinking to the right, compact circle expanding
        mExpandedContainer.animate().cancel();
        mExpandedContainer.animate()
                .alpha(0f)
                .scaleX(0.7f)
                .scaleY(0.7f)
                .setDuration(350)
                .withEndAction(() -> {
                    if (mExpandedContainer != null) {
                        mExpandedContainer.setVisibility(View.GONE);
                    }
                })
                .start();

        mCompactContainer.animate().cancel();
        mCompactContainer.setScaleX(0.7f);
        mCompactContainer.setScaleY(0.7f);
        mCompactContainer.setAlpha(0f);
        mCompactContainer.setVisibility(View.VISIBLE);
        mCompactContainer.animate()
                .alpha(1f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(350)
                .start();
    }

    private void expandMode() {
        mIsCollapsed = false;
        if (mExpandedContainer != null) {
            mExpandedContainer.animate().cancel();
            mExpandedContainer.setScaleX(1f);
            mExpandedContainer.setScaleY(1f);
            mExpandedContainer.setAlpha(1f);
            mExpandedContainer.setVisibility(View.VISIBLE);
        }
        if (mCompactContainer != null) {
            mCompactContainer.animate().cancel();
            mCompactContainer.setVisibility(View.GONE);
            mCompactContainer.setAlpha(0f);
        }
    }

    private void updateCompactView() {
        if (mCompactContainer == null) return;

        if (mRealProgressPercent >= 1 && mRealProgressPercent <= 100) {
            // Real percentage mode
            if (mCompactIcon != null) mCompactIcon.setVisibility(View.GONE);
            if (mCompactPercent != null) {
                mCompactPercent.setText(mRealProgressPercent + "%");
                mCompactPercent.setVisibility(View.VISIBLE);
            }
            if (mCompactSpinner != null) {
                mCompactSpinner.setVisibility(View.VISIBLE);
            }
        } else {
            // Preparation mode: Hourglass in center, subtle spinning ring
            if (mCompactPercent != null) mCompactPercent.setVisibility(View.GONE);
            if (mCompactIcon != null) {
                mCompactIcon.setImageDrawable(ContextCompat.getDrawable(mContext, R.drawable.ic_vot_hourglass));
                mCompactIcon.setVisibility(View.VISIBLE);
            }
            if (mCompactSpinner != null) {
                mCompactSpinner.setVisibility(View.VISIBLE);
            }
        }
    }

    public void hide() {
        cancelTimers();
        mCurrentState = STATE_IDLE;
        mIsCollapsed = false;
        mRealProgressPercent = -1;
        if (mOverlayView != null) {
            com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.release(
                    mOverlayView,
                    com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.StatusOwner.TRANSLATION);
            mOverlayView.animate().cancel();
            mOverlayView.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction(() -> {
                        if (mOverlayView != null) {
                            mOverlayView.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    public void dismissImmediately() {
        cancelTimers();
        mCurrentState = STATE_IDLE;
        mIsCollapsed = false;
        mRealProgressPercent = -1;
        if (mOverlayView != null) {
            com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.release(
                    mOverlayView,
                    com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.StatusOwner.TRANSLATION);
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
        mExpandedContainer = null;
        mCompactContainer = null;
        mParentView = null;
        mSpinner = null;
        mIcon = null;
        mTitle = null;
        mSubtitle = null;
        mTimer = null;
        mCompactSpinner = null;
        mCompactIcon = null;
        mCompactPercent = null;
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
            if (com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.shouldSuppressTranslationToast()
                    || !com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.claim(mOverlayView,
                    com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.StatusOwner.TRANSLATION)) {
                mOverlayView.animate().cancel();
                mOverlayView.setVisibility(View.GONE);
                mOverlayView.setAlpha(0f);
                return;
            }
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
        if (activity == null || activity.isFinishing()) return false;
        if (mOverlayView != null && mOverlayView.getParent() != findTargetContainer(activity)) destroy();
        if (mOverlayView != null && mOverlayView.getParent() != null) {
            mOverlayView.bringToFront();
            return true;
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

            int playbackRootId = activity.getResources().getIdentifier("playback_fragment_root", "id", activity.getPackageName());
            boolean isPlayback = (playbackRootId != 0 && (targetContainer.getId() == playbackRootId || targetContainer.findViewById(playbackRootId) != null));
            int bottomMarginDp = com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTranslationOverlayLayoutPolicy.calculateBottomMarginDp(isPlayback, false);
            int bottomMargin = dpToPx(bottomMarginDp);
            int overscanMargin = dpToPx(com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTranslationOverlayLayoutPolicy.DEFAULT_OVERSCAN_MARGIN_DP);

            if (targetContainer instanceof FrameLayout) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                lp.rightMargin = overscanMargin;
                lp.bottomMargin = bottomMargin;
                mOverlayView.setLayoutParams(lp);
            } else if (targetContainer instanceof LinearLayout) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.gravity = Gravity.END;
                lp.bottomMargin = bottomMargin;
                mOverlayView.setLayoutParams(lp);
            } else {
                ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.rightMargin = overscanMargin;
                lp.bottomMargin = bottomMargin;
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

    public void updateControlsVisibility(boolean isControlsShown) {
        if (mOverlayView != null && mOverlayView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mOverlayView.getLayoutParams();
            boolean isPlayback = mParentView != null;
            int bottomMarginDp = com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTranslationOverlayLayoutPolicy.calculateBottomMarginDp(isPlayback, isControlsShown);
            int newMargin = dpToPx(bottomMarginDp);
            if (lp.bottomMargin != newMargin) {
                lp.bottomMargin = newMargin;
                mOverlayView.setLayoutParams(lp);
            }
        }
    }

    private void bindViews(View root) {
        mOverlayView = root;
        mExpandedContainer = root.findViewById(R.id.vot_progress_expanded_container);
        mCompactContainer = root.findViewById(R.id.vot_progress_compact_container);
        mSpinner = root.findViewById(R.id.vot_progress_spinner);
        mIcon = root.findViewById(R.id.vot_progress_icon);
        mTitle = root.findViewById(R.id.vot_progress_text);
        mSubtitle = root.findViewById(R.id.vot_progress_subtitle);
        mTimer = root.findViewById(R.id.vot_progress_timer);

        mCompactSpinner = root.findViewById(R.id.vot_progress_compact_spinner);
        mCompactIcon = root.findViewById(R.id.vot_progress_compact_icon);
        mCompactPercent = root.findViewById(R.id.vot_progress_compact_percent);
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
