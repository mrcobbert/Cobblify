package com.bedwarsqol.rewind;

import com.bedwarsqol.feature.TeamUpgradeRows;
import com.bedwarsqol.hud.HudModel;
import com.bedwarsqol.hud.TextModules;

import java.util.Arrays;
import java.util.List;

/**
 * Rewind's HUD records ({@code TAG_HUD}, PLAN "Cost design"): what each Cobblify HUD item showed, handed over by the
 * live painters in {@code BedwarsHudRenderer} as they draw. This class never asks the HUD for anything; it only
 * compares what it is handed with what it wrote last.
 *
 * <p>Per module, only the first offer after a tick head counts. Values equal to the last record's (ints, strings by
 * {@code equals}, rows field by field) write nothing and keep the old references; different values, or a module
 * coming back on screen, write one record (encoded straight into a reused buffer) and keep the new references. A
 * module that was on screen and was not offered between two tick heads, while the HUD did draw in between
 * ({@link #frame}), is written once as off screen. So capture allocates nothing whether the values changed or not
 * (only a longer row list than ever before grows a kept array once).
 *
 * <p>Client thread only. Inert until the recorder wiring sets a {@link Sink}; any failure inside capture is reported
 * once to the sink ({@link Sink#failed}) and switches capture off, and the painter's draw goes on.
 */
public final class HudCapture {

    /** Where records go: the TRACK producer, through the recorder wiring. */
    public interface Sink {
        /** Appends one extension record {@code buf[0, len)} ({@code varint len | body}); false when there was no room. */
        boolean append(int tag, byte[] buf, int len);

        /**
         * Capture failed and is off until the next {@link #setSink}; {@code where} is {@code "hud"}. (Its own
         * signature, not {@code failed(Throwable)}: the recorder's sink also implements {@link ActionCapture.Sink},
         * whose {@code failed(Throwable)} reports "action".)
         */
        default void failed(String where, Throwable cause) {
        }
    }

    /** Receives each mouse press the Keystrokes HUD is told about: the mouse button, 0 left or 1 right. */
    public interface TapReceiver {
        void tap(int button);
    }

    private static final HudCapture SHARED = new HudCapture();

    /** The recorder's instance, the one the live painters hand their values to. */
    public static HudCapture shared() {
        return SHARED;
    }

    private static final int MODULES = RewindFormat.HUD_PING + 1;
    private static final int UNKNOWN = 0, ON = 1, OFF = 2;

    private final ExtCodec codec = new ExtCodec(1024);
    private Sink sink;
    private TapReceiver tapReceiver;

    private final boolean[] offered = new boolean[MODULES];
    private final int[] state = new int[MODULES];
    private boolean drew;

    // kept values of the last record, by module
    private final int[] intA = new int[MODULES];
    private final int[] intB = new int[MODULES];
    private final String[] text = new String[MODULES];
    private String[] rows = new String[16];
    private int rowCount;
    private String[] lineText = new String[16];
    private int[] lineKind = new int[16];
    private int lineCount;
    private TeamUpgradeRows.Row[] team = new TeamUpgradeRows.Row[16];
    private int teamCount;

    /** Starts capture into {@code sink} with a fresh segment (null: capture off). */
    public void setSink(Sink sink) {
        this.sink = sink;
        reset();
    }

    /** Whether a sink is set. */
    public boolean active() {
        return sink != null;
    }

    /** A new segment: every module is written again on its next offer. */
    public void reset() {
        for (int m = 0; m < MODULES; m++) {
            offered[m] = false;
            state[m] = UNKNOWN;
            text[m] = null;
        }
        drew = false;
        Arrays.fill(rows, null);
        Arrays.fill(lineText, null);
        Arrays.fill(team, null);
        rowCount = lineCount = teamCount = 0;
    }

    /** The recorder wiring's receiver of mouse presses (null: none). */
    public void setTapReceiver(TapReceiver receiver) {
        this.tapReceiver = receiver;
    }

    /** A mouse press the Keystrokes HUD flashes on: mouse button 0 (left) or 1 (right), as the event has it. */
    public void mousePress(int button) {
        TapReceiver r = tapReceiver;
        if (r != null) r.tap(button);
    }

    /** The head of a client tick: modules on screen but not offered since the previous head go off screen. */
    public void tickHead() {
        if (sink == null) return;
        try {
            if (drew) {
                for (int m = 1; m < MODULES; m++) {
                    if (!offered[m] && state[m] == ON) {
                        codec.beginHud(m, 0);
                        if (!write()) return;
                        state[m] = OFF;
                    }
                }
            }
            for (int m = 0; m < MODULES; m++) offered[m] = false;
            drew = false;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** The live HUD drew this frame (its text pass ran). */
    public void frame() {
        if (sink != null) drew = true;
    }

    /** A module that stores only whether it was on screen (Potion, Armor, Inventory, Keystrokes). */
    public void offerShown(int module) {
        if (!first(module)) return;
        try {
            if (state[module] == ON) return;
            codec.beginHud(module, RewindFormat.HUD_FLAG_ON_SCREEN);
            if (write()) state[module] = ON;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** A gen timer: the seconds it showed, negative while unknown. */
    public void offerTimer(int module, int seconds) {
        if (!first(module)) return;
        try {
            if (state[module] == ON && intA[module] == seconds) return;
            codec.beginHud(module, RewindFormat.HUD_FLAG_ON_SCREEN).putVarInt(seconds);
            if (write()) {
                state[module] = ON;
                intA[module] = seconds;
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** FPS, CPS or Ping: the runs as the painter built them, before their Label and Brackets. */
    public void offerRuns(int module, List<TextModules.Seg> runs) {
        if (!first(module)) return;
        try {
            String first = null, second = null;
            for (int i = 0, n = runs.size(); i < n; i++) {
                TextModules.Seg s = runs.get(i);
                if (s.role != TextModules.Role.NUMBER) continue;
                if (first == null) first = s.text;
                else if (second == null) second = s.text;
            }
            if (module == RewindFormat.HUD_PING) {
                String shown = first == null ? "" : first;
                if (state[module] == ON && shown.equals(text[module])) return;
                codec.beginHud(module, RewindFormat.HUD_FLAG_ON_SCREEN).putString(shown);
                if (write()) {
                    state[module] = ON;
                    text[module] = shown;
                }
                return;
            }
            int a = number(first), b = number(second);
            boolean two = module == RewindFormat.HUD_CPS;
            if (state[module] == ON && intA[module] == a && (!two || intB[module] == b)) return;
            codec.beginHud(module, RewindFormat.HUD_FLAG_ON_SCREEN).putVarInt(a);
            if (two) codec.putVarInt(b);
            if (write()) {
                state[module] = ON;
                intA[module] = a;
                intB[module] = b;
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Map Info: its rows as drawn. */
    public void offerRows(int module, List<String> shown) {
        if (!first(module)) return;
        try {
            int n = shown.size();
            if (state[module] == ON && n == rowCount) {
                boolean same = true;
                for (int i = 0; i < n && same; i++) same = HudModel.same(rows[i], shown.get(i));
                if (same) return;
            }
            if (rows.length < n) rows = new String[Math.max(n, rows.length * 2)];
            for (int i = 0; i < n; i++) rows[i] = shown.get(i);
            for (int i = n; i < rowCount; i++) rows[i] = null;
            rowCount = n;
            codec.beginHud(module, RewindFormat.HUD_FLAG_ON_SCREEN);
            HudModel.putRows(codec, rows, n);
            if (write()) state[module] = ON;
            else state[module] = UNKNOWN;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Session Stats: its lines as drawn, read through {@code view}. */
    public <T> void offerLines(int module, List<T> lines, HudModel.LineView<? super T> view) {
        if (!first(module)) return;
        try {
            int n = lines.size();
            if (state[module] == ON && n == lineCount) {
                boolean same = true;
                for (int i = 0; i < n && same; i++) {
                    T l = lines.get(i);
                    same = view.kind(l) == lineKind[i] && HudModel.same(lineText[i], view.text(l));
                }
                if (same) return;
            }
            if (lineText.length < n) {
                int cap = Math.max(n, lineText.length * 2);
                lineText = new String[cap];
                lineKind = new int[cap];
            }
            for (int i = 0; i < n; i++) {
                T l = lines.get(i);
                lineText[i] = view.text(l);
                lineKind[i] = view.kind(l);
            }
            for (int i = n; i < lineCount; i++) lineText[i] = null;
            lineCount = n;
            codec.beginHud(module, RewindFormat.HUD_FLAG_ON_SCREEN);
            HudModel.putLines(codec, lineText, lineKind, n);
            if (write()) state[module] = ON;
            else state[module] = UNKNOWN;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Upgrades &amp; Traps: its rows as drawn. */
    public void offerTeamRows(int module, List<TeamUpgradeRows.Row> shown) {
        if (!first(module)) return;
        try {
            int n = shown.size();
            if (state[module] == ON && n == teamCount) {
                boolean same = true;
                for (int i = 0; i < n && same; i++) same = HudModel.sameRow(team[i], shown.get(i));
                if (same) return;
            }
            if (team.length < n) team = new TeamUpgradeRows.Row[Math.max(n, team.length * 2)];
            for (int i = 0; i < n; i++) team[i] = shown.get(i);
            for (int i = n; i < teamCount; i++) team[i] = null;
            teamCount = n;
            codec.beginHud(module, RewindFormat.HUD_FLAG_ON_SCREEN);
            HudModel.putTeamRows(codec, team, n);
            if (write()) state[module] = ON;
            else state[module] = UNKNOWN;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** True for the first offer of {@code module} since the last tick head, while capture is on. */
    private boolean first(int module) {
        if (sink == null || module <= 0 || module >= MODULES || offered[module]) return false;
        offered[module] = true;
        return true;
    }

    /** Ends the body begun on {@link #codec} and appends it; false (and capture off) when the sink had no room. */
    private boolean write() {
        int n = codec.end();
        Sink s = sink;
        if (s == null) return false;
        if (s.append(RewindFormat.TAG_HUD, codec.buffer(), n)) return true;
        fail(new IllegalStateException("no room for a HUD record"));
        return false;
    }

    private void fail(Throwable t) {
        Sink s = sink;
        sink = null;
        if (s != null) s.failed("hud", t);
    }

    /** A number run's value (digits with an optional minus), -1 when there is none. */
    private static int number(String s) {
        if (s == null || s.isEmpty()) return -1;
        int i = 0, v = 0;
        boolean neg = s.charAt(0) == '-';
        if (neg) i = 1;
        if (i >= s.length()) return -1;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
        }
        return neg ? -v : v;
    }
}
