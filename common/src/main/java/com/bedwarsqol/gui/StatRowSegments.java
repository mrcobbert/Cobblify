package com.bedwarsqol.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits a HUD stat row into the runs Lunar colours differently: {@code "Finals: 12 / FKDR: 3.00"}
 * is label {@code "Finals: "}, value {@code "12"}, separator {@code " / "}, label {@code "FKDR: "},
 * value {@code "3.00"}. A row with no {@code ": "} (a header) is one label run.
 *
 * <p>Platform-neutral: both {@code BedwarsHudRenderer} copies draw Session Stats and Height Limit
 * rows from these runs.
 */
public final class StatRowSegments {

    private StatRowSegments() {}

    public enum Kind { LABEL, VALUE, SEPARATOR }

    public static final class Segment {
        public final String text;
        public final Kind kind;

        Segment(String text, Kind kind) {
            this.text = text;
            this.kind = kind;
        }

        @Override
        public String toString() {
            return kind + "(" + text + ")";
        }
    }

    private static final String LABEL_END = ": ";
    private static final String SEPARATOR = " / ";

    public static List<Segment> split(String row) {
        if (row == null || row.isEmpty()) return Collections.emptyList();
        List<Segment> out = new ArrayList<>(5);
        int i = 0;
        while (i < row.length()) {
            int colon = row.indexOf(LABEL_END, i);
            if (colon < 0) {
                out.add(new Segment(row.substring(i), Kind.LABEL));
                break;
            }
            out.add(new Segment(row.substring(i, colon + LABEL_END.length()), Kind.LABEL));
            int valueStart = colon + LABEL_END.length();
            int slash = row.indexOf(SEPARATOR, valueStart);
            int valueEnd = slash < 0 ? row.length() : slash;
            if (valueEnd > valueStart) out.add(new Segment(row.substring(valueStart, valueEnd), Kind.VALUE));
            if (slash < 0) break;
            out.add(new Segment(SEPARATOR, Kind.SEPARATOR));
            i = slash + SEPARATOR.length();
        }
        return out;
    }
}
