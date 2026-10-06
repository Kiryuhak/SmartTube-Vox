package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.complexcardview;

import android.content.Context;
import android.view.View;
import android.widget.RelativeLayout;

import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28)
public class VoxBadgeLayoutTest {
    @Test public void allFourBadgesHaveSeparateCornersOnSmallCard() {
        Context context = RuntimeEnvironment.getApplication();
        ComplexImageView card = new ComplexImageView(context);
        card.setLayoutParams(new RelativeLayout.LayoutParams(240, 135));
        card.setMainImageDimensions(240, 135);
        card.setQualityBadge("1080p");
        card.setLocalMarker(true, true);
        card.setAgeBadge("12+");
        card.setBadgeText("1:05");
        card.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(135, View.MeasureSpec.EXACTLY));
        card.layout(0, 0, 240, 135);

        View quality = card.findViewById(R.id.quality_badge);
        View marker = card.findViewById(R.id.local_badge_icon);
        View age = card.findViewById(R.id.age_badge);
        View duration = card.findViewById(R.id.extra_text_badge);
        assertEquals(View.VISIBLE, quality.getVisibility());
        assertEquals(View.VISIBLE, marker.getVisibility());
        assertEquals(View.VISIBLE, age.getVisibility());
        assertEquals(View.VISIBLE, duration.getVisibility());
        assertTrue(quality.getTop() < marker.getTop());
        assertTrue(quality.getBottom() <= marker.getTop());
        assertTrue(age.getLeft() < duration.getLeft());
        assertTrue(age.getBottom() <= 135);
        assertTrue(duration.getBottom() <= 135);
    }

    @Test public void missingAgeOrQualityLeavesTheirCornersFree() {
        ComplexImageView card = new ComplexImageView(RuntimeEnvironment.getApplication());
        card.setQualityBadge(null);
        card.setAgeBadge(null);
        card.setLocalMarker(true, false);
        assertEquals(View.GONE, card.findViewById(R.id.quality_badge).getVisibility());
        assertEquals(View.GONE, card.findViewById(R.id.age_badge).getVisibility());
        assertEquals(View.GONE, card.findViewById(R.id.top_badges_gap).getVisibility());
        assertEquals(View.VISIBLE, card.findViewById(R.id.local_badge_icon).getVisibility());
    }
}
