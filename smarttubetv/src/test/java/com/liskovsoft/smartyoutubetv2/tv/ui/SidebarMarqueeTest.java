package com.liskovsoft.smartyoutubetv2.tv.ui;

import android.os.Looper;
import android.text.TextUtils;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.marqueetextviewcompat.HeaderMarqueeTextViewCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.LooperMode;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class SidebarMarqueeTest {
    @Test public void longLabelStartsAfterDelayAndResetsWhenFocusLeaves() {
        HeaderMarqueeTextViewCompat label = new HeaderMarqueeTextViewCompat(RuntimeEnvironment.getApplication());
        label.setText("Скачанные видео");
        assertEquals(TextUtils.TruncateAt.END, label.getEllipsize());
        label.setFocusedState(true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(799, TimeUnit.MILLISECONDS);
        assertEquals(TextUtils.TruncateAt.END, label.getEllipsize());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
        assertEquals(TextUtils.TruncateAt.MARQUEE, label.getEllipsize());
        assertTrue(label.isSelected());
        label.setFocusedState(false);
        assertEquals(TextUtils.TruncateAt.END, label.getEllipsize());
        assertFalse(label.isSelected());
        assertEquals(0, label.getScrollX());
        label.setSelected(true); // Активная секция без фокуса не запускает marquee.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        assertEquals(TextUtils.TruncateAt.END, label.getEllipsize());
    }
}
