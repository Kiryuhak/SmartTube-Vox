package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.PlaybackControlsRow.MultiAction;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * OFF = idle, PENDING = compact progress ring (preparing / real % / attaching),
 * ON = translation playing, ERROR = brief error state before returning to retryable OFF.
 */
public class VoiceTranslateAction extends MultiAction {
    public static final int INDEX_OFF = 0;
    public static final int INDEX_PENDING = 1;
    public static final int INDEX_ON = 2;
    public static final int INDEX_ERROR = 3;

    public static final int STAGE_IDLE = 0;
    public static final int STAGE_PREPARING = 1;
    public static final int STAGE_REAL_PROGRESS = 2;
    public static final int STAGE_ATTACHING = 3;
    public static final int STAGE_ACTIVE = 4;
    public static final int STAGE_ERROR = 5;

    private final Context mContext;
    private final String mBaseLabel;
    private final String[] mLabels;
    private final VoxProgressButtonDrawable mProgressDrawable;
    private int mProgressPercent = -1;
    private int mStage = STAGE_IDLE;
    private int mRemainingEtaSec = -1;

    public VoiceTranslateAction(Context context) {
        super(R.id.action_voice_translate);
        mContext = context;
        mBaseLabel = context.getString(com.liskovsoft.smartyoutubetv2.common.R.string.action_voice_translate);

        int voxAccentColor = androidx.core.content.ContextCompat.getColor(context, R.color.vox_accent);
        int orangeAccent = 0xFFFFA726; // Material Orange / Amber accent for WAITING state
        int errorColor = 0xFFFF5252;   // Red accent for ERROR state

        BitmapDrawable offDrawable = ActionHelpers.getBitmapDrawable(context, R.drawable.action_voice_translate);
        Bitmap baseBitmap = offDrawable != null ? offDrawable.getBitmap() : null;
        float density = context.getResources().getDisplayMetrics().density;
        int fallbackSizePx = Math.max(48, Math.round(48f * density));
        int intrinsicW = offDrawable != null && offDrawable.getIntrinsicWidth() > 0 ? offDrawable.getIntrinsicWidth() : fallbackSizePx;
        int intrinsicH = offDrawable != null && offDrawable.getIntrinsicHeight() > 0 ? offDrawable.getIntrinsicHeight() : fallbackSizePx;

        mProgressDrawable = new VoxProgressButtonDrawable(baseBitmap, intrinsicW, intrinsicH, density, voxAccentColor, orangeAccent);

        BitmapDrawable pendingDrawable = offDrawable == null ? null
                : ActionHelpers.createDrawable(context, offDrawable, orangeAccent);
        BitmapDrawable onDrawable = offDrawable == null ? null
                : ActionHelpers.createDrawable(context, offDrawable, voxAccentColor);
        BitmapDrawable errorDrawable = offDrawable == null ? null
                : ActionHelpers.createDrawable(context, offDrawable, errorColor);

        Drawable[] drawables = new Drawable[4];
        drawables[INDEX_OFF] = offDrawable;
        drawables[INDEX_PENDING] = pendingDrawable != null ? pendingDrawable : offDrawable;
        drawables[INDEX_ON] = onDrawable;
        drawables[INDEX_ERROR] = errorDrawable;
        setDrawables(drawables);

        mLabels = new String[4];
        mLabels[INDEX_OFF] = mBaseLabel;
        mLabels[INDEX_PENDING] = mBaseLabel;
        mLabels[INDEX_ON] = mBaseLabel;
        mLabels[INDEX_ERROR] = mBaseLabel;
        setLabels(mLabels);
        setIndex(INDEX_OFF);
    }

    public boolean updateProgressState(int progressPercent, int stage) {
        // HUD_VOT_PROGRESS = DISABLED: canonical right overlay is single source of truth.
        return false;
    }

    public void updatePendingLabel(int remainingTimeSec) {
        // HUD_VOT_PROGRESS = DISABLED: keep static button label in HUD.
    }

    private String buildPendingLabel() {
        if (mStage == STAGE_ATTACHING) {
            return mContext.getString(com.liskovsoft.smartyoutubetv2.common.R.string.vot_progress_attaching);
        }
        if (mProgressPercent >= 1 && mProgressPercent <= 100) {
            if (mRemainingEtaSec > 0) {
                int min = Math.max(1, (mRemainingEtaSec + 59) / 60);
                return "Готовим перевод — " + mProgressPercent + "% (~" + min + " мин)";
            }
            return "Готовим перевод — " + mProgressPercent + "%";
        }
        if (mRemainingEtaSec > 0) {
            int min = Math.max(1, (mRemainingEtaSec + 59) / 60);
            return mContext.getString(
                    com.liskovsoft.smartyoutubetv2.common.R.string.vot_pending_eta_short, min);
        }
        return mContext.getString(
                com.liskovsoft.smartyoutubetv2.common.R.string.vot_pending_long);
    }

    public void resetLabels() {
        mProgressPercent = -1;
        mStage = STAGE_IDLE;
        mRemainingEtaSec = -1;
        mProgressDrawable.setState(-1, STAGE_IDLE);
        mLabels[INDEX_OFF] = mBaseLabel;
        mLabels[INDEX_PENDING] = mBaseLabel;
        mLabels[INDEX_ON] = mBaseLabel;
        mLabels[INDEX_ERROR] = mBaseLabel;
        setLabels(mLabels);
    }

    public int getProgressPercent() {
        return mProgressPercent;
    }

    public int getStage() {
        return mStage;
    }

    /**
     * Compact in-button progress ring drawable that renders directly inside the HUD secondary action button
     * without layout thrashing or fixed screen coordinates.
     */
    public static final class VoxProgressButtonDrawable extends Drawable {
        private static final String[] PERCENT_STRINGS = new String[101];
        static {
            for (int i = 0; i <= 100; i++) {
                PERCENT_STRINGS[i] = i + "%";
            }
        }

        @Nullable
        private final Bitmap mBaseBitmap;
        private final int mIntrinsicWidth;
        private final int mIntrinsicHeight;
        private final int mAccentColor;
        private final int mPreparingColor;
        private final PorterDuffColorFilter mAccentFilter;
        private final PorterDuffColorFilter mPreparingFilter;
        private final Paint mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRingBounds = new RectF();
        private final Rect mSrcRect = new Rect();
        private final RectF mDstRect = new RectF();
        private final RectF mPillRect = new RectF();
        private final float mDensity;
        private final float mFontAscent;
        private final float mFontDescent;

        private int mPercent = -1;
        private int mStage = STAGE_PREPARING;
        private float mPhaseAngle = -90f;
        private long mLastInvalidateElapsedMs = 0L;

        public VoxProgressButtonDrawable(
                @Nullable Bitmap baseBitmap,
                int intrinsicWidth,
                int intrinsicHeight,
                float density,
                int accentColor,
                int preparingColor
        ) {
            mBaseBitmap = baseBitmap;
            mIntrinsicWidth = intrinsicWidth;
            mIntrinsicHeight = intrinsicHeight;
            mDensity = density > 0f ? density : 1f;
            mAccentColor = accentColor;
            mPreparingColor = preparingColor;
            mAccentFilter = new PorterDuffColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
            mPreparingFilter = new PorterDuffColorFilter(preparingColor, PorterDuff.Mode.SRC_IN);

            if (baseBitmap != null) {
                mSrcRect.set(0, 0, baseBitmap.getWidth(), baseBitmap.getHeight());
            }

            float strokePx = Math.max(2f, 2.4f * mDensity);
            mTrackPaint.setStyle(Paint.Style.STROKE);
            mTrackPaint.setStrokeWidth(strokePx);
            mTrackPaint.setColor(0x38FFFFFF);

            mArcPaint.setStyle(Paint.Style.STROKE);
            mArcPaint.setStrokeWidth(strokePx);
            mArcPaint.setStrokeCap(Paint.Cap.ROUND);
            mArcPaint.setColor(preparingColor);

            mBadgeBgPaint.setStyle(Paint.Style.FILL);
            mBadgeBgPaint.setColor(0xCC10141C);

            mTextPaint.setColor(0xFFFFFFFF);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            mTextPaint.setTextSize(10.5f * mDensity);

            Paint.FontMetrics fm = mTextPaint.getFontMetrics();
            mFontAscent = fm.ascent;
            mFontDescent = fm.descent;
        }

        public void setState(int percent, int stage) {
            boolean changed = (mPercent != percent) || (mStage != stage);
            mPercent = percent;
            mStage = stage;
            if (changed) {
                mPhaseAngle = (mPhaseAngle + 45f) % 360f;
                mLastInvalidateElapsedMs = SystemClock.elapsedRealtime();
                invalidateSelf();
            }
        }

        public void stepActivityPhase() {
            long now = SystemClock.elapsedRealtime();
            if (now - mLastInvalidateElapsedMs < 220L) {
                return;
            }
            mLastInvalidateElapsedMs = now;
            mPhaseAngle = (mPhaseAngle + 45f) % 360f;
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float minDim = Math.min(b.width(), b.height());
            float inset = Math.max(2f, 2.5f * mDensity);
            float radius = (minDim / 2f) - inset;
            mRingBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);

            boolean hasRealPercent = mPercent >= 1 && mPercent <= 100 && mStage != STAGE_ATTACHING;

            // 1. Draw VOX glyph (slightly smaller / dimmed when % is displayed so icon remains recognizable)
            if (mBaseBitmap != null && !mBaseBitmap.isRecycled()) {
                float iconScale = hasRealPercent ? 0.50f : 0.62f;
                float iconHalf = (minDim * iconScale) / 2f;
                float iconCenterY = hasRealPercent ? (cy - minDim * 0.12f) : cy;
                mDstRect.set(cx - iconHalf, iconCenterY - iconHalf, cx + iconHalf, iconCenterY + iconHalf);
                PorterDuffColorFilter filter = (mStage == STAGE_ATTACHING || hasRealPercent) ? mAccentFilter : mPreparingFilter;
                mIconPaint.setColorFilter(filter);
                mIconPaint.setAlpha(hasRealPercent ? 185 : 235);
                canvas.drawBitmap(mBaseBitmap, mSrcRect, mDstRect, mIconPaint);
            }

            // 2. Draw subtle perimeter ring track
            canvas.drawOval(mRingBounds, mTrackPaint);

            // 3. Draw stage-specific progress / activity arc
            if (mStage == STAGE_ATTACHING) {
                mArcPaint.setColor(mAccentColor);
                canvas.drawArc(mRingBounds, mPhaseAngle, 120f, false, mArcPaint);
                canvas.drawArc(mRingBounds, (mPhaseAngle + 180f) % 360f, 120f, false, mArcPaint);
            } else if (hasRealPercent) {
                mArcPaint.setColor(mAccentColor);
                float sweep = Math.max(12f, (360f * mPercent) / 100f);
                canvas.drawArc(mRingBounds, -90f, sweep, false, mArcPaint);

                // 4. Draw compact real % label inside lower half of button ring (zero allocation)
                String pctText = (mPercent >= 0 && mPercent <= 100) ? PERCENT_STRINGS[mPercent] : (mPercent + "%");
                float textY = cy + (minDim * 0.24f);
                float textW = mTextPaint.measureText(pctText);
                float pillPadX = 3f * mDensity;
                float pillTop = textY + mFontAscent - mDensity;
                float pillBottom = textY + mFontDescent + mDensity;
                mPillRect.set(cx - textW / 2f - pillPadX, pillTop, cx + textW / 2f + pillPadX, pillBottom);
                canvas.drawRoundRect(mPillRect, 4f * mDensity, 4f * mDensity, mBadgeBgPaint);
                canvas.drawText(pctText, cx, textY, mTextPaint);
            } else {
                mArcPaint.setColor(mPreparingColor);
                canvas.drawArc(mRingBounds, mPhaseAngle, 110f, false, mArcPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            mIconPaint.setAlpha(alpha);
            mTrackPaint.setAlpha(alpha);
            mArcPaint.setAlpha(alpha);
            mTextPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            mIconPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return mIntrinsicWidth;
        }

        @Override
        public int getIntrinsicHeight() {
            return mIntrinsicHeight;
        }
    }
}
