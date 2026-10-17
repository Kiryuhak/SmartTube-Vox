package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.common.R;

/** A small, non-focusable TV progress ring used by the canonical VOT overlay. */
public final class VotProgressRingView extends View {
    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mArcBounds = new RectF();
    private int mProgress;

    public VotProgressRingView(Context context) {
        this(context, null);
    }

    public VotProgressRingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        mTrackPaint.setStyle(Paint.Style.STROKE);
        mTrackPaint.setStrokeWidth(4f * density);
        mTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        mTrackPaint.setColor(0x334AA3DF);
        mProgressPaint.setStyle(Paint.Style.STROKE);
        mProgressPaint.setStrokeWidth(4f * density);
        mProgressPaint.setStrokeCap(Paint.Cap.ROUND);
        mProgressPaint.setColor(ContextCompat.getColor(context, R.color.vox_accent));
        mTextPaint.setColor(ContextCompat.getColor(context, R.color.vox_text_primary));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setTextSize(14f * getResources().getDisplayMetrics().scaledDensity);
        mTextPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        setFocusable(false);
        setClickable(false);
    }

    public void setProgress(int progress) {
        int clamped = Math.max(0, Math.min(100, progress));
        if (mProgress == clamped) return;
        mProgress = clamped;
        invalidate();
    }

    public int getProgress() {
        return mProgress;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = mProgressPaint.getStrokeWidth() / 2f + 1f;
        mArcBounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawArc(mArcBounds, 0f, 360f, false, mTrackPaint);
        if (mProgress > 0) {
            canvas.drawArc(mArcBounds, -90f, 360f * mProgress / 100f, false, mProgressPaint);
        }
        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(mProgress + "%", getWidth() / 2f, baseline, mTextPaint);
    }
}
