package com.liskovsoft.smartyoutubetv2.common.misc;

import com.liskovsoft.sharedutils.helpers.DateHelper;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

@RunWith(RobolectricTestRunner.class)
public class DateHelperTest {

    @Test
    public void testMidnightFormatHasTwoDigits() {
        Locale defaultLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            Calendar cal = Calendar.getInstance(TimeZone.getDefault(), Locale.GERMANY);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 9);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            String shortTime = DateHelper.toShortTime(cal.getTimeInMillis());
            assertTrue("Midnight time must format as 00:09 in 24h format, got: " + shortTime, shortTime.contains("00:09"));
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }
}
