package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

/** Gallery dates ("Today 21:42", "Yesterday 23:31", "Nov 12 19:04") and m:ss lengths. */
public class DateLabelsTest {

    private static final TimeZone TZ = TimeZone.getTimeZone("America/Los_Angeles");

    private static long at(TimeZone tz, int y, int mo, int d, int h, int mi, int s) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        return c.getTimeInMillis();
    }

    @Test
    public void todayYesterdayAndOlder() {
        long now = at(TZ, 2026, 11, 14, 22, 0, 0);
        assertEquals("Today 21:42", DateLabels.when(at(TZ, 2026, 11, 14, 21, 42, 59), now, TZ));
        assertEquals("Today 00:00", DateLabels.when(at(TZ, 2026, 11, 14, 0, 0, 0), now, TZ));
        assertEquals("Yesterday 23:31", DateLabels.when(at(TZ, 2026, 11, 13, 23, 31, 0), now, TZ));
        assertEquals("Yesterday 00:05", DateLabels.when(at(TZ, 2026, 11, 13, 0, 5, 0), now, TZ));
        assertEquals("Nov 12 19:04", DateLabels.when(at(TZ, 2026, 11, 12, 19, 4, 0), now, TZ));
        assertEquals("Jan 3 07:09", DateLabels.when(at(TZ, 2026, 1, 3, 7, 9, 0), now, TZ));
    }

    @Test
    public void calendarDaysNotTwentyFourHours() {
        // 00:30 today: a game at 23:50 the night before is "Yesterday", 40 minutes ago
        long now = at(TZ, 2026, 11, 14, 0, 30, 0);
        assertEquals("Yesterday 23:50", DateLabels.when(at(TZ, 2026, 11, 13, 23, 50, 0), now, TZ));
        // across a month and a year boundary
        long newYear = at(TZ, 2027, 1, 1, 9, 0, 0);
        assertEquals("Yesterday 22:00", DateLabels.when(at(TZ, 2026, 12, 31, 22, 0, 0), newYear, TZ));
        assertEquals("Dec 30 22:00", DateLabels.when(at(TZ, 2026, 12, 30, 22, 0, 0), newYear, TZ));
    }

    @Test
    public void localZoneDecidesTheDay() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        long now = at(utc, 2026, 11, 14, 3, 0, 0); // 19:00 the day before in Los Angeles
        long game = at(utc, 2026, 11, 14, 1, 0, 0);
        assertEquals("Today 01:00", DateLabels.when(game, now, utc));
        assertEquals("Today 17:00", DateLabels.when(game, now, TZ));
    }

    @Test
    public void aClockAheadOfNowStillReadsAsADate() {
        long now = at(TZ, 2026, 11, 14, 12, 0, 0);
        assertEquals("Today 13:00", DateLabels.when(at(TZ, 2026, 11, 14, 13, 0, 0), now, TZ));
        assertEquals("Nov 15 13:00", DateLabels.when(at(TZ, 2026, 11, 15, 13, 0, 0), now, TZ));
    }

    @Test
    public void lengthsRoundToTheNearestSecond() {
        assertEquals("0:00", DateLabels.length(0));
        assertEquals("0:00", DateLabels.length(499_999));
        assertEquals("0:01", DateLabels.length(500_000));
        assertEquals("1:02", DateLabels.length(62_000_000));
        assertEquals("6:47", DateLabels.length(406_600_000));
        assertEquals("6:47", DateLabels.length(407_400_000));
        assertEquals("1:00", DateLabels.length(59_600_000));
        assertEquals("120:00", DateLabels.length(7_200_000_000L));
        assertEquals("0:00", DateLabels.length(-5_000_000));
    }

    @Test
    public void theClockCountsWholeSeconds() {
        assertEquals("0:00", DateLabels.clock(999_999));
        assertEquals("0:01", DateLabels.clock(1_000_000));
        assertEquals("6:46", DateLabels.clock(406_999_999));
        assertEquals("0:00", DateLabels.clock(-1));
    }

    @Test
    public void theClockShowsTheLengthAtTheEnd() {
        long len = 406_600_000L; // 6:47 rounded
        assertEquals("6:46", DateLabels.position(406_500_000L, len));
        assertEquals("6:47", DateLabels.position(len, len));
        assertEquals("6:47", DateLabels.position(len + 50_000, len));
    }
}
