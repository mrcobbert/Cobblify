package com.bedwarsqol.rewind.watch;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Dates and durations as Rewind shows them: "Today 21:42", "Yesterday 23:31", "Nov 12 19:04" (English month names,
 * 24-hour clock, the local zone, calendar days); game lengths as m:ss rounded to the nearest second; the playhead as
 * m:ss in whole seconds (a clock), which reads the length once it reaches the end.
 */
public final class DateLabels {

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
            "Nov", "Dec"};

    private DateLabels() {
    }

    /** When a game started ({@code wallMs}), seen at {@code nowMs} in {@code zone}. */
    public static String when(long wallMs, long nowMs, TimeZone zone) {
        Calendar at = Calendar.getInstance(zone);
        at.setTimeInMillis(wallMs);
        Calendar today = Calendar.getInstance(zone);
        today.setTimeInMillis(nowMs);
        String time = two(at.get(Calendar.HOUR_OF_DAY)) + ":" + two(at.get(Calendar.MINUTE));
        if (sameDay(at, today)) return "Today " + time;
        Calendar yesterday = (Calendar) today.clone();
        yesterday.add(Calendar.DAY_OF_MONTH, -1);
        if (sameDay(at, yesterday)) return "Yesterday " + time;
        return MONTHS[at.get(Calendar.MONTH)] + " " + at.get(Calendar.DAY_OF_MONTH) + " " + time;
    }

    /** A game length, m:ss rounded to the nearest second (half a second rounds up). */
    public static String length(long us) {
        return mss(Math.max(0L, (us + 500_000L) / 1_000_000L));
    }

    /** A clock position, m:ss in whole elapsed seconds. */
    public static String clock(long us) {
        return mss(Math.max(0L, us) / 1_000_000L);
    }

    /** The playhead at {@code us} of a game {@code lengthUs} long: {@link #clock}, or {@link #length} at the end. */
    public static String position(long us, long lengthUs) {
        return us >= lengthUs ? length(lengthUs) : clock(us);
    }

    private static String mss(long seconds) {
        return (seconds / 60) + ":" + two((int) (seconds % 60));
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : Integer.toString(v);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
