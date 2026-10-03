package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.marqueetextviewcompat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;

import com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxSidebarMarqueePolicy;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

/**
 * MarqueeTextView used in browse section headers with delayed start and focus policy
 */
public class HeaderMarqueeTextViewCompat extends MarqueeTextViewCompat {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mStartScrollRunnable = this::superUpdateMarquee;

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
        ViewUtil.applyMarqueeRtlParams(this, true);
        setMarqueeSpeedFactor(VoxSidebarMarqueePolicy.MARQUEE_SPEED_FACTOR);
    }

    @Override
    protected void updateMarquee() {
        mHandler.removeCallbacks(mStartScrollRunnable);

        boolean active = (isFocused() || isSelected());
        if (active) {
            mHandler.postDelayed(mStartScrollRunnable, VoxSidebarMarqueePolicy.MARQUEE_START_DELAY_MS);
        } else {
            super.updateMarquee();
        }
    }

    private void superUpdateMarquee() {
        super.updateMarquee();
    }

    @Override
    protected void onDetachedFromWindow() {
        mHandler.removeCallbacks(mStartScrollRunnable);
        super.onDetachedFromWindow();
    }
}
