package com.bedwarsqol.hud;

import com.bedwarsqol.feature.TeamUpgradeRows;
import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * The values of a Rewind HUD record ({@code RewindFormat.TAG_HUD}): what each Cobblify HUD item showed, written by
 * the recorder from what the live painters handed over and read back by playback. Shared by both so the layout is
 * defined once.
 *
 * <p>Values after the record's module id and flags, by module (only a record with the on-screen flag carries them;
 * an off-screen record ends after its flags):
 * <ul>
 *   <li>FPS: varint fps. CPS: varint left, varint right. Ping: string, the number text as shown ({@code 27},
 *       {@code --}).</li>
 *   <li>Diamond and emerald timer: varint seconds to the next spawn, negative while unknown ({@code --}).</li>
 *   <li>Map Info: varint n, n strings, each row as shown.</li>
 *   <li>Session Stats: varint n, n x (u8 kind {@link #LINE_ROW} or {@link #LINE_HEADER}, string text).</li>
 *   <li>Upgrades &amp; Traps: varint n, n x (u8 kind, string label, string value, string right, u8 style, u8 alpha
 *       0-255), the {@link TeamUpgradeRows.Row} fields; kind and style are the fixed {@code TEAM_KIND_*} and
 *       {@code TEAM_STYLE_*} ids. Decoding skips a row of unknown kind and draws an unknown style as NORMAL.</li>
 *   <li>Potion, Armor, Inventory, Keystrokes: nothing (playback has the effects, armour and inventory; Keystrokes
 *       comes from the INPUT and TAP records).</li>
 * </ul>
 * Encoding writes characters straight into the {@link ExtCodec} buffer, so it allocates nothing.
 */
public final class HudModel {

    /** Session Stats line kinds. */
    public static final int LINE_ROW = 0;
    public static final int LINE_HEADER = 1;

    /** Value shapes, by module ({@link #shape}). */
    public static final int SHAPE_NONE = 0;
    public static final int SHAPE_INT = 1;
    public static final int SHAPE_INT2 = 2;
    public static final int SHAPE_TEXT = 3;
    public static final int SHAPE_ROWS = 4;
    public static final int SHAPE_LINES = 5;
    public static final int SHAPE_TEAM = 6;

    /*
     * Upgrades & Traps row ids as stored. Fixed numbers, not enum ordinals, so reordering or adding a constant in
     * TeamUpgradeRows never changes what a recording means. The values equal the ordinals the first v2 build wrote.
     */
    public static final int TEAM_KIND_HEADER = 0;
    public static final int TEAM_KIND_UPGRADE = 1;
    public static final int TEAM_KIND_TRAP = 2;
    public static final int TEAM_KIND_NEXT = 3;
    public static final int TEAM_KIND_NONE = 4;
    public static final int TEAM_STYLE_NORMAL = 0;
    public static final int TEAM_STYLE_DIM = 1;
    public static final int TEAM_STYLE_FIRED = 2;

    private HudModel() {
    }

    /** What module {@code module} ({@code RewindFormat.HUD_*}) stores; -1 for an id that is not a HUD module. */
    public static int shape(int module) {
        switch (module) {
            case RewindFormat.HUD_POTION:
            case RewindFormat.HUD_ARMOR:
            case RewindFormat.HUD_INVENTORY:
            case RewindFormat.HUD_KEYSTROKES:
                return SHAPE_NONE;
            case RewindFormat.HUD_FPS:
            case RewindFormat.HUD_DIAMOND_TIMER:
            case RewindFormat.HUD_EMERALD_TIMER:
                return SHAPE_INT;
            case RewindFormat.HUD_CPS:
                return SHAPE_INT2;
            case RewindFormat.HUD_PING:
                return SHAPE_TEXT;
            case RewindFormat.HUD_HEIGHT_LIMIT:
                return SHAPE_ROWS;
            case RewindFormat.HUD_SESSION_STATS:
                return SHAPE_LINES;
            case RewindFormat.HUD_TEAM_UPGRADES:
                return SHAPE_TEAM;
            default:
                return -1;
        }
    }

    /** A Session Stats line as the painter built it, read without knowing the painter's own line type. */
    public interface LineView<T> {
        String text(T line);

        /** {@link #LINE_ROW} or {@link #LINE_HEADER}. */
        int kind(T line);
    }

    // ------------------------------------------------------------------ encode (after codec.beginHud)

    /** Map Info: {@code rows[0, n)}. */
    public static void putRows(ExtCodec c, String[] rows, int n) {
        c.putVarInt(n);
        for (int i = 0; i < n; i++) {
            c.putString(rows[i]);
        }
    }

    /** Session Stats: {@code text[i]} with {@code kinds[i]}, {@code i < n}. */
    public static void putLines(ExtCodec c, String[] text, int[] kinds, int n) {
        c.putVarInt(n);
        for (int i = 0; i < n; i++) {
            c.putU8(kinds[i]);
            c.putString(text[i]);
        }
    }

    /** Upgrades &amp; Traps: {@code rows[0, n)}. */
    public static void putTeamRows(ExtCodec c, TeamUpgradeRows.Row[] rows, int n) {
        c.putVarInt(n);
        for (int i = 0; i < n; i++) {
            TeamUpgradeRows.Row r = rows[i];
            c.putU8(teamKindId(r.kind));
            c.putString(r.label);
            c.putString(r.value);
            c.putString(r.right);
            c.putU8(teamStyleId(r.style));
            c.putU8(alpha8(r.alpha));
        }
    }

    /** The stored id of a row kind. */
    public static int teamKindId(TeamUpgradeRows.Kind kind) {
        switch (kind) {
            case HEADER: return TEAM_KIND_HEADER;
            case UPGRADE: return TEAM_KIND_UPGRADE;
            case TRAP: return TEAM_KIND_TRAP;
            case NEXT: return TEAM_KIND_NEXT;
            case NONE: return TEAM_KIND_NONE;
            default: throw new IllegalArgumentException("row kind " + kind);
        }
    }

    /** The row kind of a stored id; null for an id this build does not know. */
    public static TeamUpgradeRows.Kind teamKind(int id) {
        switch (id) {
            case TEAM_KIND_HEADER: return TeamUpgradeRows.Kind.HEADER;
            case TEAM_KIND_UPGRADE: return TeamUpgradeRows.Kind.UPGRADE;
            case TEAM_KIND_TRAP: return TeamUpgradeRows.Kind.TRAP;
            case TEAM_KIND_NEXT: return TeamUpgradeRows.Kind.NEXT;
            case TEAM_KIND_NONE: return TeamUpgradeRows.Kind.NONE;
            default: return null;
        }
    }

    /** The stored id of a row style. */
    public static int teamStyleId(TeamUpgradeRows.Style style) {
        switch (style) {
            case NORMAL: return TEAM_STYLE_NORMAL;
            case DIM: return TEAM_STYLE_DIM;
            case FIRED: return TEAM_STYLE_FIRED;
            default: throw new IllegalArgumentException("row style " + style);
        }
    }

    /** The row style of a stored id; null for an id this build does not know. */
    public static TeamUpgradeRows.Style teamStyle(int id) {
        switch (id) {
            case TEAM_STYLE_NORMAL: return TeamUpgradeRows.Style.NORMAL;
            case TEAM_STYLE_DIM: return TeamUpgradeRows.Style.DIM;
            case TEAM_STYLE_FIRED: return TeamUpgradeRows.Style.FIRED;
            default: return null;
        }
    }

    /** A row's alpha as stored: 0-255, as the painter rounds it for a fading row. */
    public static int alpha8(float alpha) {
        return Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
    }

    // ------------------------------------------------------------------ compare

    /** Strings equal by content (null equals null). */
    public static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Two rows that store the same bytes. */
    public static boolean sameRow(TeamUpgradeRows.Row a, TeamUpgradeRows.Row b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.kind == b.kind && a.style == b.style && alpha8(a.alpha) == alpha8(b.alpha)
                && a.label.equals(b.label) && a.value.equals(b.value) && a.right.equals(b.right);
    }

    // ------------------------------------------------------------------ decode (playback)

    /** A decoded Session Stats line. */
    public static final class Line {
        public final int kind;
        public final String text;

        public Line(int kind, String text) {
            this.kind = kind;
            this.text = text == null ? "" : text;
        }

        public boolean header() {
            return kind == LINE_HEADER;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Line && ((Line) o).kind == kind && ((Line) o).text.equals(text);
        }

        @Override
        public int hashCode() {
            return text.hashCode() * 31 + kind;
        }

        @Override
        public String toString() {
            return kind + "|" + text;
        }
    }

    /** One HUD record, decoded. Lists are empty for the shapes that do not use them. */
    public static final class Values {
        public int module;
        public boolean onScreen;
        /** FPS, a timer's seconds, CPS left. */
        public int a;
        /** CPS right. */
        public int b;
        /** Ping's number text. */
        public String text = "";
        public final List<String> rows = new ArrayList<String>();
        public final List<Line> lines = new ArrayList<Line>();
        public final List<TeamUpgradeRows.Row> team = new ArrayList<TeamUpgradeRows.Row>();
    }

    /** Decodes a HUD body (without its length prefix). A malformed body throws {@link RewindFormat.FormatException}. */
    public static Values decode(byte[] body, int off, int len) {
        return decode(new ExtCodec.Hud().read(body, off, len));
    }

    /** The values of a read HUD record. */
    public static Values decode(ExtCodec.Hud hud) {
        Values v = new Values();
        v.module = hud.module;
        v.onScreen = hud.onScreen();
        int shape = shape(hud.module);
        if (shape < 0) throw new RewindFormat.FormatException("hud module " + hud.module);
        if (!v.onScreen) return v;
        RewindFormat.Cursor c = hud.values();
        switch (shape) {
            case SHAPE_INT:
                v.a = c.varInt();
                break;
            case SHAPE_INT2:
                v.a = c.varInt();
                v.b = c.varInt();
                break;
            case SHAPE_TEXT:
                v.text = c.string(RewindFormat.MAX_STRING_BYTES);
                break;
            case SHAPE_ROWS: {
                int n = count(c);
                for (int i = 0; i < n; i++) v.rows.add(c.string(RewindFormat.MAX_STRING_BYTES));
                break;
            }
            case SHAPE_LINES: {
                int n = count(c);
                for (int i = 0; i < n; i++) {
                    int kind = c.u8();
                    if (kind != LINE_ROW && kind != LINE_HEADER) throw new RewindFormat.FormatException("line kind");
                    v.lines.add(new Line(kind, c.string(RewindFormat.MAX_STRING_BYTES)));
                }
                break;
            }
            case SHAPE_TEAM: {
                int n = count(c);
                for (int i = 0; i < n; i++) {
                    // an id a later build added is not an error: an unknown kind skips its row, an unknown style
                    // draws NORMAL (code review 1, O5)
                    TeamUpgradeRows.Kind kind = teamKind(c.u8());
                    String label = c.string(RewindFormat.MAX_STRING_BYTES);
                    String value = c.string(RewindFormat.MAX_STRING_BYTES);
                    String right = c.string(RewindFormat.MAX_STRING_BYTES);
                    TeamUpgradeRows.Style style = teamStyle(c.u8());
                    int alpha = c.u8();
                    if (kind == null) continue;
                    if (style == null) style = TeamUpgradeRows.Style.NORMAL;
                    v.team.add(new TeamUpgradeRows.Row(kind, label, value, right, style, alpha / 255f));
                }
                break;
            }
            default:
                break;
        }
        return v;
    }

    /** A list length no larger than the bytes left (each element takes at least one). */
    private static int count(RewindFormat.Cursor c) {
        return c.length(c.remaining());
    }
}
