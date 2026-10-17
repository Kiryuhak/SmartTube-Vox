package com.liskovsoft.smartyoutubetv2.common.vot;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger;
import com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator;
import com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTranslationOverlayLayoutPolicy;

/**
 * The single canonical VOT status overlay. Its END edge never moves: the same view
 * changes width from a pill to a circle and expands back towards START.
 */
public class VotProgressOverlay {
    private static final String TAG = "VotProgressOverlay";

    // Kept stable for compatibility with existing callers and tests.
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
    public static final int STATE_ATTACHING = 10;

    public static final long COLLAPSE_DELAY_MS = 3500L;
    public static final long BUBBLE_AUTO_HIDE_DELAY_MS = 3500L;
    public static final long BUBBLE_READY_AUTO_HIDE_DELAY_MS = 2000L;
    public static final long SHOW_100_DURATION_MS = 320L;
    private static final long SHAPE_ANIMATION_MS = 250L;
    private static final long DISMISS_ERROR_MS = 3500L;
    private static final long WATCHDOG_MS = 90000L;

    private enum MotionState {
        HIDDEN, PREPARING, COLLAPSING, PROGRESS, FINALIZING_99,
        SHOWING_100, EXPANDING_READY, READY, ERROR, DISMISSING
    }

    private final Context mContext;
    private View mOverlayView;
    private View mExpandedContainer;
    private ProgressBar mSpinner;
    private ImageView mIcon;
    private TextView mTitle;
    private TextView mSubtitle;
    private TextView mTimer;
    private VotProgressRingView mRing;
    private ViewGroup mParentView;
    private ValueAnimator mWidthAnimator;
    private MotionState mMotionState = MotionState.HIDDEN;
    private int mCurrentState = STATE_IDLE;
    private int mRealProgressPercent = -1;
    private boolean mIsCollapsed;
    private int mCallbackGeneration;

    private final Runnable mAutoDismissRunnable = this::hide;
    private final Runnable mSafetyWatchdogRunnable = () -> {
        if (mMotionState == MotionState.PREPARING || mMotionState == MotionState.PROGRESS ||
                mMotionState == MotionState.FINALIZING_99) {
            Log.w(TAG, "VOT overlay watchdog dismissed stale state");
            dismissImmediately();
        }
    };

    public VotProgressOverlay(Context context) {
        mContext = context;
    }

    public int getState() { return mCurrentState; }
    public boolean isShown() { return mOverlayView != null && mOverlayView.getVisibility() == View.VISIBLE && mCurrentState != STATE_IDLE; }
    public boolean isCollapsed() { return mIsCollapsed; }
    public int getRealProgressPercent() { return mRealProgressPercent; }
    public boolean isCompactContainerVisible() { return mRing != null && mRing.getVisibility() == View.VISIBLE; }
    public boolean isExpandedContainerVisible() { return mExpandedContainer != null && mExpandedContainer.getVisibility() == View.VISIBLE; }

    public void resetRealProgress() {
        mRealProgressPercent = -1;
        if (mRing != null) mRing.setProgress(0);
    }

    public void showLookingUp(@Nullable Activity activity) {
        showPreparingText(activity, STATE_LOOKING_UP, getStringSafe(R.string.vot_progress_looking_up));
    }

    public void showLookupReady(@Nullable Activity activity) {
        // Cache hit follows the same short visual path; it must not claim UI-ready before audio attach.
        showPreparingText(activity, STATE_LOOKUP_READY, getStringSafe(R.string.vot_progress_preparing));
    }

    public void showPreparing(@Nullable Activity activity) {
        showPreparingText(activity, STATE_PREPARING, getStringSafe(R.string.vot_progress_preparing));
    }

    public void showStarting(@Nullable Activity activity) {
        showPreparingText(activity, STATE_ATTACHING, getStringSafe(R.string.vot_progress_starting));
    }

    private void showPreparingText(@Nullable Activity activity, int state, String text) {
        if (!ensureAttached(activity)) return;
        cancelPendingCallbacks();
        mCurrentState = state;
        mMotionState = MotionState.PREPARING;
        mRealProgressPercent = -1;
        showExpandedContent(text, R.drawable.ic_vot_sparkles, false);
        setWidthImmediately(wideWidthPx());
        reveal();
        armWatchdog();
        VoxSafeLogger.d(
                VoxLogCategory.UI,
                VoxLogCode.VOT_UI_PREPARING,
                "VOT UI preparing overlay shown: " + text,
                null
        );
    }

    /** A real backend processing/poll event starts the pill-to-circle transition at 0%. */
    public void showProcessing(@Nullable Activity activity, int backendDerivedPercent) {
        if (!ensureAttached(activity)) return;
        mCurrentState = STATE_WAITING_WITH_ETA;
        updateRealProgressInternal(Math.max(0, Math.min(95, backendDerivedPercent)));
        collapseToProgress();
        reveal();
        armWatchdog();
    }

    public void showWaitingWithEta(@Nullable Activity activity, String ignored) {
        showProcessing(activity, Math.max(0, mRealProgressPercent));
    }

    public void showWaitingWithoutEta(@Nullable Activity activity) {
        showProcessing(activity, Math.max(0, mRealProgressPercent));
    }

    public void showDelayedWaiting(@Nullable Activity activity) {
        mCurrentState = STATE_ETA_EXPIRED_STILL_WAITING;
        showProcessing(activity, Math.max(0, mRealProgressPercent));
    }

    public void showStillWaiting(@Nullable Activity activity, String ignored) {
        showDelayedWaiting(activity);
    }

    /** Backend READY/audio preparation stays at 99 until audible playback is confirmed. */
    public void showAttaching(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        mCurrentState = STATE_ATTACHING;
        updateRealProgressInternal(99);
        mMotionState = MotionState.FINALIZING_99;
        collapseToProgress();
        reveal();
        armWatchdog();
    }

    public void showRetryWait(@Nullable Activity activity, int attempt, int maxAttempts, int ignored) {
        if (!ensureAttached(activity)) return;
        cancelPendingCallbacks();
        mCurrentState = STATE_RETRY;
        String title = formatStringSafe(R.string.vot_progress_retry_title, attempt, maxAttempts);
        showExpandedContent(title, R.drawable.ic_vot_retry, false);
        animateWidth(wideWidthPx(), null);
        reveal();
        armWatchdog();
    }

    /** Monotonic backend-derived progress only. Values above 99 require audio activation. */
    public void updateRealProgress(int percent) {
        int clamped = Math.max(0, Math.min(99, percent));
        if (clamped <= mRealProgressPercent) return;
        updateRealProgressInternal(clamped);
        if (mOverlayView != null) collapseToProgress();
    }

    private void updateRealProgressInternal(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        if (clamped < mRealProgressPercent) return;
        mRealProgressPercent = clamped;
        if (mRing != null) mRing.setProgress(clamped);
        VoxSafeLogger.d(
                VoxLogCategory.UI,
                VoxLogCode.VOT_UI_PROGRESS,
                "VOT UI progress: " + clamped + "%",
                null
        );
    }

    /** Called only after translated audio is attached, selected and reported active. */
    public void showActivated(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        if (mMotionState == MotionState.SHOWING_100 || mMotionState == MotionState.EXPANDING_READY ||
                mMotionState == MotionState.READY) return;
        cancelPendingCallbacks();
        mCurrentState = STATE_READY;
        mMotionState = MotionState.SHOWING_100;
        updateRealProgressInternal(100);
        showRingContent();
        setWidthImmediately(circleWidthPx());
        reveal();
        VoxSafeLogger.d(
                VoxLogCategory.UI,
                VoxLogCode.VOT_UI_100,
                "VOT UI reached 100% (audio active and confirmed)",
                null
        );
        final int generation = mCallbackGeneration;
        Utils.postDelayed(() -> {
            if (generation != mCallbackGeneration || mCurrentState != STATE_READY) return;
            mMotionState = MotionState.EXPANDING_READY;
            showExpandedContent(getStringSafe(R.string.vot_progress_ready), R.drawable.ic_vot_ready, false);
            animateWidth(wideWidthPx(), () -> {
                if (generation != mCallbackGeneration || mCurrentState != STATE_READY) return;
                mMotionState = MotionState.READY;
                VoxSafeLogger.d(
                        VoxLogCategory.UI,
                        VoxLogCode.VOT_UI_READY,
                        "VOT UI expanded to ready pill",
                        null
                );
                Utils.postDelayed(mAutoDismissRunnable, BUBBLE_READY_AUTO_HIDE_DELAY_MS);
            });
        }, SHOW_100_DURATION_MS);
    }

    /** Manual-ready compatibility path; production auto-activation uses showActivated(). */
    public void showReady(@Nullable Activity activity) {
        showActivated(activity);
    }

    public void showError(@Nullable Activity activity) { showError(activity, null); }

    public void showError(@Nullable Activity activity, @Nullable String errorMessage) {
        if (!ensureAttached(activity)) return;
        cancelPendingCallbacks();
        mCurrentState = STATE_ERROR;
        mMotionState = MotionState.ERROR;
        String message = errorMessage == null || errorMessage.isEmpty()
                ? getStringSafe(R.string.vot_progress_error) : errorMessage;
        showExpandedContent(message, R.drawable.ic_vot_error, false);
        animateWidth(wideWidthPx(), null);
        reveal();
        Utils.postDelayed(mAutoDismissRunnable, DISMISS_ERROR_MS);
    }

    public void showTimeout(@Nullable Activity activity) {
        if (!ensureAttached(activity)) return;
        mCurrentState = STATE_TIMEOUT;
        showError(activity, getStringSafe(R.string.vot_progress_timeout));
        mCurrentState = STATE_TIMEOUT;
    }

    public void hideBubbleKeepState() {
        // Processing must stay visible; only terminal confirmations auto-dismiss.
        if (mMotionState == MotionState.READY || mMotionState == MotionState.ERROR) hide();
    }

    public void performCollapseAnimation() {
        collapseToProgress();
    }

    private void collapseToProgress() {
        if (mOverlayView == null) return;
        if (mIsCollapsed && (mWidthAnimator == null || !mWidthAnimator.isRunning())) {
            showRingContent();
            return;
        }
        mMotionState = MotionState.COLLAPSING;
        if (mExpandedContainer != null) {
            mExpandedContainer.animate().cancel();
            mExpandedContainer.animate().alpha(0f).setDuration(140L).start();
        }
        animateWidth(circleWidthPx(), () -> {
            mIsCollapsed = true;
            mMotionState = mRealProgressPercent >= 99 ? MotionState.FINALIZING_99 : MotionState.PROGRESS;
            showRingContent();
        });
    }

    private void showRingContent() {
        if (mExpandedContainer != null) {
            mExpandedContainer.animate().cancel();
            mExpandedContainer.setVisibility(View.GONE);
            mExpandedContainer.setAlpha(0f);
        }
        if (mRing != null) {
            mRing.setProgress(Math.max(0, mRealProgressPercent));
            mRing.setVisibility(View.VISIBLE);
            mRing.setAlpha(1f);
        }
        mIsCollapsed = true;
    }

    private void showExpandedContent(String text, int iconRes, boolean spinner) {
        if (mRing != null) mRing.setVisibility(View.GONE);
        if (mExpandedContainer != null) {
            mExpandedContainer.animate().cancel();
            mExpandedContainer.setVisibility(View.VISIBLE);
            mExpandedContainer.setAlpha(1f);
        }
        if (mSpinner != null) mSpinner.setVisibility(spinner ? View.VISIBLE : View.GONE);
        if (mIcon != null) {
            mIcon.setImageDrawable(ContextCompat.getDrawable(mContext, iconRes));
            mIcon.setVisibility(spinner ? View.GONE : View.VISIBLE);
        }
        if (mTitle != null) mTitle.setText(text);
        if (mSubtitle != null) mSubtitle.setVisibility(View.GONE);
        if (mTimer != null) mTimer.setVisibility(View.GONE);
        mIsCollapsed = false;
    }

    private void animateWidth(int targetWidth, @Nullable Runnable endAction) {
        if (mOverlayView == null) return;
        if (mWidthAnimator != null) mWidthAnimator.cancel();
        int startWidth = mOverlayView.getWidth() > 0 ? mOverlayView.getWidth() : currentLayoutWidth();
        if (startWidth == targetWidth) {
            setWidthImmediately(targetWidth);
            if (endAction != null) endAction.run();
            return;
        }
        mWidthAnimator = ValueAnimator.ofInt(startWidth, targetWidth);
        mWidthAnimator.setDuration(SHAPE_ANIMATION_MS);
        mWidthAnimator.setInterpolator(new DecelerateInterpolator());
        mWidthAnimator.addUpdateListener(animation -> setWidthImmediately((Integer) animation.getAnimatedValue()));
        mWidthAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;
            @Override public void onAnimationCancel(Animator animation) { mCancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!mCancelled && endAction != null) endAction.run();
            }
        });
        mWidthAnimator.start();
    }

    private void setWidthImmediately(int width) {
        if (mOverlayView == null) return;
        ViewGroup.LayoutParams lp = mOverlayView.getLayoutParams();
        if (lp != null && lp.width != width) {
            lp.width = width;
            lp.height = overlayHeightPx();
            mOverlayView.setLayoutParams(lp);
        }
    }

    private int currentLayoutWidth() {
        ViewGroup.LayoutParams lp = mOverlayView != null ? mOverlayView.getLayoutParams() : null;
        return lp != null && lp.width > 0 ? lp.width : wideWidthPx();
    }

    private void reveal() {
        if (mOverlayView == null) return;
        if (VoxTransientStatusCoordinator.shouldSuppressTranslationToast() ||
                !VoxTransientStatusCoordinator.claim(mOverlayView, VoxTransientStatusCoordinator.StatusOwner.TRANSLATION)) {
            return;
        }
        mOverlayView.bringToFront();
        mOverlayView.animate().cancel();
        if (mOverlayView.getVisibility() != View.VISIBLE) {
            mOverlayView.setAlpha(0f);
            mOverlayView.setVisibility(View.VISIBLE);
        }
        mOverlayView.animate().alpha(1f).setDuration(180L).start();
    }

    public void hide() {
        cancelPendingCallbacks();
        mCurrentState = STATE_IDLE;
        mMotionState = MotionState.DISMISSING;
        VoxSafeLogger.d(
                VoxLogCategory.UI,
                VoxLogCode.VOT_UI_HIDDEN,
                "VOT UI overlay hidden",
                null
        );
        if (mOverlayView == null) return;
        VoxTransientStatusCoordinator.release(mOverlayView, VoxTransientStatusCoordinator.StatusOwner.TRANSLATION);
        mOverlayView.animate().cancel();
        mOverlayView.animate().alpha(0f).setDuration(200L).withEndAction(() -> {
            if (mOverlayView != null && mCurrentState == STATE_IDLE) {
                mOverlayView.setVisibility(View.GONE);
                mMotionState = MotionState.HIDDEN;
            }
        }).start();
    }

    public void dismissImmediately() {
        cancelPendingCallbacks();
        mCurrentState = STATE_IDLE;
        mMotionState = MotionState.HIDDEN;
        mRealProgressPercent = -1;
        mIsCollapsed = false;
        VoxSafeLogger.d(
                VoxLogCategory.UI,
                VoxLogCode.VOT_UI_HIDDEN,
                "VOT UI overlay dismissed immediately",
                null
        );
        if (mOverlayView != null) {
            VoxTransientStatusCoordinator.release(mOverlayView, VoxTransientStatusCoordinator.StatusOwner.TRANSLATION);
            mOverlayView.animate().cancel();
            mOverlayView.setVisibility(View.GONE);
            mOverlayView.setAlpha(0f);
        }
    }

    public void destroy() {
        dismissImmediately();
        if (mOverlayView != null && mParentView != null) mParentView.removeView(mOverlayView);
        mOverlayView = null;
        mParentView = null;
        mExpandedContainer = null;
        mSpinner = null;
        mIcon = null;
        mTitle = null;
        mSubtitle = null;
        mTimer = null;
        mRing = null;
    }

    private void cancelPendingCallbacks() {
        mCallbackGeneration++;
        Utils.removeCallbacks(mAutoDismissRunnable);
        Utils.removeCallbacks(mSafetyWatchdogRunnable);
        if (mWidthAnimator != null) {
            mWidthAnimator.cancel();
            mWidthAnimator = null;
        }
    }

    private void armWatchdog() {
        Utils.removeCallbacks(mSafetyWatchdogRunnable);
        Utils.postDelayed(mSafetyWatchdogRunnable, WATCHDOG_MS);
    }

    private boolean ensureAttached(@Nullable Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        ViewGroup target = findTargetContainer(activity);
        if (target == null) return false;
        if (mOverlayView != null && mOverlayView.getParent() != target) destroy();
        if (mOverlayView != null && mOverlayView.getParent() != null) return true;
        try {
            View existing = target.findViewById(R.id.vot_progress_root);
            if (existing != null) {
                bindViews(existing);
                mParentView = target;
                return true;
            }
            View root = LayoutInflater.from(mContext).inflate(R.layout.vot_progress_overlay, target, false);
            bindViews(root);
            root.setFocusable(false);
            root.setFocusableInTouchMode(false);
            root.setClickable(false);
            int endMargin = dpToPx(VoxTranslationOverlayLayoutPolicy.fixedVotEndMarginDp());
            int bottomMargin = dpToPx(VoxTranslationOverlayLayoutPolicy.fixedVotBottomMarginDp());
            if (target instanceof FrameLayout) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(wideWidthPx(), overlayHeightPx());
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                lp.setMarginEnd(endMargin);
                lp.bottomMargin = bottomMargin;
                root.setLayoutParams(lp);
            } else if (target instanceof LinearLayout) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wideWidthPx(), overlayHeightPx());
                lp.gravity = Gravity.END;
                lp.setMarginEnd(endMargin);
                lp.bottomMargin = bottomMargin;
                root.setLayoutParams(lp);
            } else {
                ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(wideWidthPx(), overlayHeightPx());
                lp.setMarginEnd(endMargin);
                lp.bottomMargin = bottomMargin;
                root.setLayoutParams(lp);
            }
            root.setVisibility(View.GONE);
            root.setAlpha(0f);
            target.addView(root);
            root.bringToFront();
            mParentView = target;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to inflate VOT progress overlay: %s", e.getMessage());
            return false;
        }
    }

    /** HUD visibility deliberately does not move the fixed side anchor. */
    public void updateControlsVisibility(boolean ignored) {
        // Position is invariant by design; the fixed bottom margin is safe for both HUD states.
    }

    private void bindViews(View root) {
        mOverlayView = root;
        mExpandedContainer = root.findViewById(R.id.vot_progress_expanded_container);
        mSpinner = root.findViewById(R.id.vot_progress_spinner);
        mIcon = root.findViewById(R.id.vot_progress_icon);
        mTitle = root.findViewById(R.id.vot_progress_text);
        mSubtitle = root.findViewById(R.id.vot_progress_subtitle);
        mTimer = root.findViewById(R.id.vot_progress_timer);
        mRing = root.findViewById(R.id.vot_progress_ring);
    }

    private ViewGroup findTargetContainer(Activity activity) {
        int rootId = activity.getResources().getIdentifier("playback_fragment_root", "id", activity.getPackageName());
        if (rootId != 0) {
            View root = activity.findViewById(rootId);
            if (root instanceof ViewGroup) return (ViewGroup) root;
        }
        View content = activity.findViewById(android.R.id.content);
        return content instanceof ViewGroup ? (ViewGroup) content : null;
    }

    private int wideWidthPx() { return mContext.getResources().getDimensionPixelSize(R.dimen.vot_overlay_wide_width); }
    private int circleWidthPx() { return mContext.getResources().getDimensionPixelSize(R.dimen.vot_overlay_height); }
    private int overlayHeightPx() { return mContext.getResources().getDimensionPixelSize(R.dimen.vot_overlay_height); }
    private int dpToPx(int dp) { return Math.round(dp * mContext.getResources().getDisplayMetrics().density); }

    private String getStringSafe(int resId) {
        try { return mContext.getString(resId); } catch (Exception e) { return ""; }
    }

    private String formatStringSafe(int resId, Object... args) {
        try { return mContext.getString(resId, args); } catch (Exception e) { return ""; }
    }
}
