package com.bedwarsqol.hud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The FPS, CPS and Ping modules, as the approved sandbox draws them: the number in white, the unit in the accent, CPS
 * as {@code left | right} with a 1 px gray divider that casts a shadow like text. With its Background on, a module is
 * an 18 px tall box that hugs the text with 4 px either side; off, it is the bare text. Brackets ({@code [150 FPS]}) are
 * the module's own switch, independent of its Background ({@link #bracketed}).
 * Geometry is in module units (multiply by the draw scale); widths come from the font through {@link Advance}.
 */
public final class TextModules {

    /** Box padding either side of the text, box height, and the text's line top in the box. */
    public static final int HPAD = 4;
    public static final int BOX_H = 18;
    public static final int TEXT_DY = 5;
    /** Height of the bare text (Background off). */
    public static final int BARE_H = 9;
    /** Space either side of the CPS divider. */
    public static final int LINE_GAP = 3;
    public static final int NUMBER = 0xFFFFFFFF;
    public static final int BRACKET = 0xFFFFFFFF;
    public static final int DIVIDER = 0xFFAAAAAA;
    /** The divider's shadow: its colour darkened as Minecraft darkens a text shadow. */
    public static final int DIVIDER_SHADOW = 0xFF2A2A2A;

    /** Example values for the editor and the default layout. */
    public static final int EXAMPLE_FPS = 150;
    public static final int EXAMPLE_CPS_LEFT = 9;
    public static final int EXAMPLE_CPS_RIGHT = 3;
    public static final String EXAMPLE_PING = "27";

    public enum Role { NUMBER, UNIT, DIVIDER, BRACKET }

    /** One run of a module's text: a number, a unit, or the CPS divider (no text). */
    public static final class Seg {
        public final String text;
        public final Role role;

        Seg(String text, Role role) {
            this.text = text;
            this.role = role;
        }
    }

    /** A run placed at {@code x} from the text origin; the divider also has its rect and shadow {x1, y1, x2, y2}. */
    public static final class Placed {
        public final Seg seg;
        public final float x;
        public final float[] rect;
        public final float[] shadow;

        Placed(Seg seg, float x, float[] rect, float[] shadow) {
            this.seg = seg;
            this.x = x;
            this.rect = rect;
            this.shadow = shadow;
        }
    }

    /** A font's advance width of a string at scale 1, trailing column included (as {@code getStringWidth}). */
    public interface Advance {
        float of(String s);
    }

    private TextModules() {
    }

    public static List<Seg> fps(int fps) {
        return segs(new Seg(Integer.toString(fps), Role.NUMBER), new Seg(" FPS", Role.UNIT));
    }

    public static List<Seg> cps(int left, int right) {
        return segs(new Seg(Integer.toString(left), Role.NUMBER), new Seg("", Role.DIVIDER),
                new Seg(Integer.toString(right), Role.NUMBER), new Seg(" CPS", Role.UNIT));
    }

    public static List<Seg> ping(String ms) {
        return segs(new Seg(ms, Role.NUMBER), new Seg(" ms", Role.UNIT));
    }

    /** The runs between white brackets: {@code [150 FPS]}. */
    public static List<Seg> bracketed(List<Seg> segs) {
        List<Seg> out = new ArrayList<Seg>(segs.size() + 2);
        out.add(new Seg("[", Role.BRACKET));
        out.addAll(segs);
        out.add(new Seg("]", Role.BRACKET));
        return Collections.unmodifiableList(out);
    }

    private static List<Seg> segs(Seg... s) {
        return Collections.unmodifiableList(Arrays.asList(s));
    }

    /**
     * Ping's number: the player's own tab-list latency, or {@code --} in singleplayer, without a tab entry, or while the
     * server reports none ({@code ms <= 0}).
     */
    public static String pingText(boolean singleplayer, boolean hasInfo, int ms) {
        return singleplayer || !hasInfo || ms <= 0 ? "--" : Integer.toString(ms);
    }

    /** Advance of the runs: each text's advance, the divider {@code LINE_GAP + 1 + LINE_GAP}. */
    public static float segWidth(List<Seg> segs, Advance adv) {
        float w = 0f;
        for (Seg s : segs) w += s.role == Role.DIVIDER ? LINE_GAP + 1 + LINE_GAP : adv.of(s.text);
        return w;
    }

    /** Width of the box with its Background on: the text without its trailing column, plus padding both sides. */
    public static float boxWidth(List<Seg> segs, Advance adv, float trailing) {
        return segWidth(segs, adv) - trailing + 2 * HPAD;
    }

    /** Width of the bare text (Background off), brackets included when the runs have them. */
    public static float bareWidth(List<Seg> segs, Advance adv) {
        return segWidth(segs, adv);
    }

    /**
     * Where each run goes, from the text origin. The divider sits 2 px into its gap (the glyph before it already left a
     * 1 px column), 8 px tall, with its shadow 1 px right and down.
     */
    public static List<Placed> layout(List<Seg> segs, Advance adv) {
        List<Placed> out = new ArrayList<Placed>(segs.size());
        float cx = 0f;
        for (Seg s : segs) {
            if (s.role == Role.DIVIDER) {
                float d = cx + LINE_GAP - 1;
                out.add(new Placed(s, cx, new float[]{d, 0, d + 1, 8}, new float[]{d + 1, 1, d + 2, 9}));
                cx += LINE_GAP + 1 + LINE_GAP;
            } else {
                out.add(new Placed(s, cx, null, null));
                cx += adv.of(s.text);
            }
        }
        return out;
    }
}
