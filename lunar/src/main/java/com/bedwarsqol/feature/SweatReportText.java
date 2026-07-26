package com.bedwarsqol.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Condenses SweatReport into the smallest useful party-chat payload: one line, hard-capped.
 * Prefer naming the sweatiest players over team-by-team bursts.
 */
public final class SweatReportText {

    /** Strict maximum outgoing content lines (excludes the {@code /pc } prefix). */
    public static final int MAX_LINES = 1;

    /** 1.8.9 truncates outgoing chat around 100 chars; leave room for {@code /pc }. */
    public static final int MAX_CONTENT = 96;

    private SweatReportText() {}

    /** One ranked player entry used to build the condensed line. */
    public static final class Entry {
        public final String team;
        public final boolean ours;
        public final String name;
        public final String value; // "12.3", "nick", "new", "?"
        public final double fkdr;
        public final boolean known;

        public Entry(String team, boolean ours, String name, String value, double fkdr, boolean known) {
            this.team = team;
            this.ours = ours;
            this.name = name;
            this.value = value;
            this.fkdr = fkdr;
            this.known = known;
        }
    }

    /**
     * Build at most {@link #MAX_LINES} plain-text lines (no {@code /pc } prefix) from ranked entries.
     * Sweatiest known players first; our-team members are eligible but not forced to the front.
     */
    public static List<String> condense(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptyList();

        List<Entry> ranked = new ArrayList<Entry>(entries);
        Collections.sort(ranked, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                return Double.compare(sortKey(b), sortKey(a));
            }
        });

        List<String> pieces = new ArrayList<String>();
        for (Entry e : ranked) {
            String team = e.team + (e.ours ? "/US" : "");
            pieces.add(team + " " + e.name + " " + e.value);
        }

        // Pack as many leading pieces as fit, always leaving room for a ", +N" trailer when needed.
        int used = 0;
        StringBuilder sb = new StringBuilder("Sweats: ");
        while (used < pieces.size()) {
            String piece = (used == 0 ? "" : ", ") + pieces.get(used);
            int remainAfter = pieces.size() - used - 1;
            int needTrailer = remainAfter > 0 ? (2 + 1 + digitLen(remainAfter)) : 0; // ", +N"
            if (sb.length() + piece.length() + needTrailer > MAX_CONTENT) break;
            sb.append(piece);
            used++;
        }

        int remaining = pieces.size() - used;
        if (used == 0) {
            // First name alone is huge — hard trim.
            String piece = pieces.get(0);
            int room = MAX_CONTENT - sb.length();
            if (room <= 0) return Collections.emptyList();
            sb.append(piece.length() <= room ? piece : piece.substring(0, room));
            used = 1;
            remaining = pieces.size() - 1;
        }
        if (remaining > 0) {
            String trailer = ", +" + remaining;
            while (sb.length() + trailer.length() > MAX_CONTENT && used > 1) {
                // Peel the last included name so the trailer fits.
                int lastComma = sb.lastIndexOf(", ");
                if (lastComma <= "Sweats: ".length()) break;
                sb.setLength(lastComma);
                used--;
                remaining = pieces.size() - used;
                trailer = ", +" + remaining;
            }
            if (sb.length() + trailer.length() <= MAX_CONTENT) {
                sb.append(trailer);
            }
        }

        String line = sb.toString();
        if (line.length() > MAX_CONTENT) line = line.substring(0, MAX_CONTENT);
        List<String> out = new ArrayList<String>(1);
        out.add(line);
        return out;
    }

    public static String fmt1(double d) {
        return String.format(Locale.US, "%.1f", d);
    }

    private static int digitLen(int n) {
        if (n < 10) return 1;
        if (n < 100) return 2;
        return 3;
    }

    private static double sortKey(Entry e) {
        return e.known ? e.fkdr : Double.NEGATIVE_INFINITY;
    }
}
