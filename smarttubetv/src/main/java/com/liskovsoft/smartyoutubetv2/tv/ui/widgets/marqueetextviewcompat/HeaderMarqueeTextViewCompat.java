package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.marqueetextviewcompat;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * Safe, lightweight MarqueeTextView for Leanback sidebar headers.
 * Uses standard Android TextView marquee without any custom canvas drawing or child views.
 */
public class HeaderMarqueeTextViewCompat extends AppCompatTextView {
    private static final long MARQUEE_START_DELAY_MS = 800L;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mIsActive = false;

    private final Runnable mStartMarqueeRunnable = () -> {
        if (mIsActive) {
            setEllipsize(TextUtils.TruncateAt.MARQUEE);
            super.setSelected(true);
        }
    };

    public HeaderMarqueeTextViewCompat(Context context) {
        super(context);
        init();
    }

    public HeaderMarqueeTextViewCompat(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public HeaderMarqueeTextViewCompat(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setMarqueeRepeatLimit(-1); // marquee_forever
        setHorizontalFadingEdgeEnabled(true);
    }

    @Override
    public void setSelected(boolean selected) {
        updateMarqueeState(selected || isFocused());
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        updateMarqueeState(focused || isSelected());
    }

    private void updateMarqueeState(boolean active) {
        mIsActive = active;
        mHandler.removeCallbacks(mStartMarqueeRunnable);

        if (active) {
            // Delayed start: show static/ellipsized first, start marquee after delay if still focused
            mHandler.postDelayed(mStartMarqueeRunnable, MARQUEE_START_DELAY_MS);
        } else {
            // Immediate reset on focus loss
            setEllipsize(TextUtils.TruncateAt.END);
            super.setSelected(false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mHandler.removeCallbacks(mStartMarqueeRunnable);
        super.onDetachedFromWindow();
    }
}
