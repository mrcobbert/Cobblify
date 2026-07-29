package com.bedwarsqol.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds the SweatReport party-chat payload: one line per team, sweatiest team first, each line
 * prefixed with the team's average FKDR over its known players. Lines are plain text (Hypixel
 * strips § from player chat) and are paced by {@link OutgoingChatPolicy}, so the line count is
 * capped — solo/doubles lobbies have eight teams and would otherwise hold party chat for ~20s.
 */
public final class SweatReportText {

    /** Strict maximum outgoing lines per report; extra teams are reported as a {@code +N teams} tail. */
    public static final int MAX_LINES = 4;

    /** 1.8.9 truncates outgoing chat around 100 chars; leave room for {@code /pc }. */
    public static final int MAX_CONTENT = 96;

    private SweatReportText() {}

    /** One player's display within a team line. */
    public static final class Entry {
        public final String name;
        public final String value; // "12.3", "nick", "new", "err", "..."
        public final double fkdr;
        public final boolean known;

        public Entry(String name, String value, double fkdr, boolean known) {
            this.name = name;
            this.value = value;
            this.fkdr = fkdr;
            this.known = known;
        }
    }

    /** One team's line input. {@code label} already carries any {@code (US)} marker. */
    public static final class Team {
        public final String label;
        public final List<Entry> members;

        public Team(String label, List<Entry> members) {
            this.label = label;
            this.members = members == null ? Collections.<Entry>emptyList() : members;
        }
    }

    /**
     * Build the report: teams ordered by average FKDR (teams with no known player sort last),
     * at most {@link #MAX_LINES} lines, no {@code /pc } prefix.
     */
    public static List<String> lines(List<Team> teams) {
        if (teams == null || teams.isEmpty()) return Collections.emptyList();

        List<Team> ranked = new ArrayList<Team>();
        for (Team t : teams) {
            if (t != null && !t.members.isEmpty()) ranked.add(t);
        }
        if (ranked.isEmpty()) return Collections.emptyList();

        Collections.sort(ranked, new Comparator<Team>() {
            public int compare(Team a, Team b) {
                return Double.compare(sortKey(average(b)), sortKey(average(a)));
            }
        });

        int kept = Math.min(ranked.size(), MAX_LINES);
        List<String> out = new ArrayList<String>(kept);
        for (int i = 0; i < kept; i++) {
            out.add(line(ranked.get(i)));
        }
        int dropped = ranked.size() - kept;
        if (dropped > 0) {
            out.set(kept - 1, withTeamsTail(out.get(kept - 1), dropped));
        }
        return out;
    }

    /** One team's line: {@code LABEL(avg): name val, name val, +N}. */
    static String line(Team team) {
        Double avg = average(team);
        StringBuilder sb = new StringBuilder(
                team.label + "(" + (avg == null ? "?" : fmt1(avg)) + "): ");

        List<Entry> members = new ArrayList<Entry>(team.members);
        Collections.sort(members, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                return Double.compare(sortKey(b), sortKey(a));
            }
        });

        int i = 0;
        for (; i < members.size(); i++) {
            Entry m = members.get(i);
            String piece = (i == 0 ? "" : ", ") + m.name + " " + m.value;
            int remainAfter = members.size() - i - 1;
            int needTrailer = remainAfter > 0 ? (2 + 1 + digitLen(remainAfter)) : 0; // ", +N"
            if (sb.length() + piece.length() + needTrailer > MAX_CONTENT) break;
            sb.append(piece);
        }
        if (i < members.size()) {
            if (sb.charAt(sb.length() - 1) != ' ') sb.append(", ");
            sb.append('+').append(members.size() - i);
        }

        String content = sb.toString();
        return content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
    }

    /** Append the dropped-team marker to the last line, trimming it only if there is no room. */
    private static String withTeamsTail(String line, int dropped) {
        String tail = ", +" + dropped + " teams";
        if (line.length() + tail.length() <= MAX_CONTENT) return line + tail;
        int room = MAX_CONTENT - tail.length();
        return room <= 0 ? line : line.substring(0, room) + tail;
    }

    /** A team's mean FKDR over its known players, or null when none resolved. */
    static Double average(Team team) {
        double sum = 0;
        int n = 0;
        for (Entry m : team.members) {
            if (m.known) { sum += m.fkdr; n++; }
        }
        return n == 0 ? null : Double.valueOf(sum / n);
    }

    public static String fmt1(double d) {
        return String.format(Locale.US, "%.1f", d);
    }

    private static int digitLen(int n) {
        if (n < 10) return 1;
        if (n < 100) return 2;
        return 3;
    }

    private static double sortKey(Double avg) {
        return avg == null ? Double.NEGATIVE_INFINITY : avg.doubleValue();
    }

    /** Unknown players sort last. */
    private static double sortKey(Entry e) {
        return e.known ? e.fkdr : Double.NEGATIVE_INFINITY;
    }
}
