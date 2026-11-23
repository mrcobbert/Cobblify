package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.gui.sheet.TextMetrics;
import com.bedwarsqol.rewind.PruneRules;
import com.bedwarsqol.rewind.RecordingMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

/**
 * The gallery (G17, G39, G46; TASK A2, A3; mockup {@code drawGallery}): one row per closed recording, newest first,
 * with a star (keep forever), mode · map, WIN/LOSS or blank, the date, the length and a mini timeline of marker icons;
 * keys Watch (hero), Export (Rewind Export TASK U2: opens the game in the viewer's export mode), Delete (the second
 * press confirms; the armed key turns red) and Done.
 * Hypixel replay-viewer visits are never listed (coordinator amendment 2).
 *
 * <p>The model holds selection, the armed Delete and the scroll; it changes no file. Actions come back as an
 * {@link Intent} the screen carries out with {@link RecordingLibrary}, then reports with {@link #starred},
 * {@link #removed} or {@link #deleteFailed}. Any action other than a second Delete disarms Delete.
 *
 * <p>Keys: Up/Down select, Enter watches, Delete/Backspace delete (fresh presses only: a held key's repeats are
 * ignored, so the confirming press is a second physical one), Esc is Done. A double-click on a row (two clicks
 * on it within {@value #DOUBLE_CLICK_MS} ms) watches it. Done leads back to where the gallery was opened from: the
 * title screen, or the Esc menu of a playing session. A gallery reached because a recording could not be played is
 * opened with {@link Origin#TITLE} (its Done leads to the title screen, round 1 I3).
 */
public final class GalleryModel {

    public static final long DOUBLE_CLICK_MS = 400;
    public static final float CARD_W = 440f;
    public static final float ROW_H = 30f;
    public static final float FOOT_H = 1f + 8f + 20f + 8f;
    /** Space kept free around the card on a small screen. */
    public static final float MARGIN = 4f;

    static final int KEY_ESCAPE = 1, KEY_BACK = 14, KEY_RETURN = 28, KEY_NUMPADENTER = 156, KEY_UP = 200,
            KEY_DOWN = 208, KEY_DELETE = 211;

    public enum Origin { TITLE, PLAYING }

    public enum Done { TITLE_SCREEN, ESC_MENU }

    /** A marker icon on a row's mini timeline: where (0 = game start, 1 = end) and its kind. */
    public static final class Mark {
        public final float at;
        public final int kind;

        Mark(float at, int kind) {
            this.at = at;
            this.kind = kind;
        }
    }

    /** One row. */
    public static final class Entry {
        public final String id;
        public final String title;
        /** "WIN", "LOSS" or null. */
        public final String result;
        public final String when;
        public final String length;
        public final long startedAtMs;
        public final List<Mark> marks;
        public boolean starred;

        Entry(RecordingMeta m, long nowMs, TimeZone zone) {
            id = m.id;
            title = ModeLabels.title(m);
            result = ModeLabels.result(m);
            when = DateLabels.when(m.startedAtMs, nowMs, zone);
            length = DateLabels.length(m.lengthUs);
            startedAtMs = m.startedAtMs;
            starred = m.starred;
            List<Mark> marks = new ArrayList<Mark>();
            long start = m.gameStartUs, len = m.endUs - m.gameStartUs;
            if (m.markers != null && len > 0) {
                for (RecordingMeta.Marker mk : m.markers) {
                    if (mk == null || mk.tUs < start || mk.tUs > m.endUs) continue;
                    int kind = Timeline.kindOf(mk.kind);
                    if (kind > 0) marks.add(new Mark((float) ((double) (mk.tUs - start) / len), kind));
                }
            }
            Collections.sort(marks, new Comparator<Mark>() {
                @Override
                public int compare(Mark a, Mark b) {
                    return Float.compare(a.at, b.at);
                }
            });
            this.marks = Collections.unmodifiableList(marks);
        }
    }

    /** What the screen should do. */
    public static final class Intent {
        public enum Kind {
            NONE,
            /** Watch recording {@link #id}. */
            WATCH,
            /** Delete recording {@link #id} (already confirmed by the second press). */
            DELETE,
            /** Set recording {@link #id}'s star to {@link #starred}. */
            STAR,
            /** Leave to {@link #done}. */
            DONE,
            /** Open recording {@link #id} in the viewer's export mode. */
            EXPORT
        }

        public final Kind kind;
        public final String id;
        public final boolean starred;
        public final Done done;

        Intent(Kind kind, String id, boolean starred, Done done) {
            this.kind = kind;
            this.id = id;
            this.starred = starred;
            this.done = done;
        }

        static final Intent NONE = new Intent(Kind.NONE, null, false, null);
    }

    /** Where the card and its parts are on a screen, in GUI px (su). Rows are counted from the first visible one. */
    public static final class Layout {
        public final float x, y, w, h;
        /** Rows shown, and the index of the entry in the first one. */
        public final int rows, first;
        public final float titleX, titleW, resultX, dateX, lengthRight;
        /** {x, y, w, h} of the footer keys. */
        public final float[] watch, export, delete, done;

        Layout(float x, float y, float w, float h, int rows, int first, float titleX, float titleW, float resultX,
               float dateX, float lengthRight) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.rows = rows;
            this.first = first;
            this.titleX = titleX;
            this.titleW = titleW;
            this.resultX = resultX;
            this.dateX = dateX;
            this.lengthRight = lengthRight;
            float ky = y + h - 1 - FOOT_H + 1 + 8;
            watch = new float[]{x + 11, ky, 72, 20};
            export = new float[]{x + 11 + 72 + 4, ky, 64, 20};
            delete = new float[]{x + 11 + 72 + 4 + 64 + 4, ky, 64, 20};
            done = new float[]{x + w - 11 - 64, ky, 64, 20};
        }

        /** {x, y, w, h} of visible row {@code slot} (a divider line sits above every row but the first). */
        public float[] row(int slot) {
            return new float[]{x + 1, y + 1 + slot * (ROW_H + 1), w - 2, ROW_H};
        }

        /** The star's hit rect in row {@code slot}; the 9 x 9 icon is at its x + 2, y + 2. */
        public float[] star(int slot) {
            float[] r = row(slot);
            return new float[]{r[0] + 9 - 2, r[1] + 4 - 2, 13, 13};
        }

        /** The mini timeline of row {@code slot}: {x, y, w} of its 3 su groove. */
        public float[] miniTimeline(int slot) {
            float[] r = row(slot);
            return new float[]{r[0] + 26, r[1] + 20, r[2] - 26 - 12};
        }

        /** The line top of row {@code slot}'s labels. */
        public float labelTop(int slot) {
            return row(slot)[1] + 5;
        }

        /** The body's height (rows and dividers, or one empty row). */
        public float bodyH() {
            return h - 2 - FOOT_H;
        }

        public Hit hit(float px, float py) {
            for (int s = 0; s < rows; s++) {
                if (in(star(s), px, py)) return new Hit(Hit.Part.STAR, first + s);
                if (in(row(s), px, py)) return new Hit(Hit.Part.ROW, first + s);
            }
            if (in(watch, px, py)) return new Hit(Hit.Part.WATCH, -1);
            if (in(export, px, py)) return new Hit(Hit.Part.EXPORT, -1);
            if (in(delete, px, py)) return new Hit(Hit.Part.DELETE, -1);
            if (in(done, px, py)) return new Hit(Hit.Part.DONE, -1);
            if (px >= x && py >= y && px < x + w && py < y + h) return new Hit(Hit.Part.CARD, -1);
            return new Hit(Hit.Part.NONE, -1);
        }

        private static boolean in(float[] r, float px, float py) {
            return px >= r[0] && py >= r[1] && px < r[0] + r[2] && py < r[1] + r[3];
        }
    }

    /** What is under a point; {@link #row} is the entry index for ROW and STAR, else -1. */
    public static final class Hit {
        public enum Part { NONE, CARD, ROW, STAR, WATCH, EXPORT, DELETE, DONE }

        public final Part part;
        public final int row;

        Hit(Part part, int row) {
            this.part = part;
            this.row = row;
        }

        public boolean same(Hit o) {
            return o != null && o.part == part && o.row == row;
        }
    }

    private final Origin origin;
    private final List<Entry> entries = new ArrayList<Entry>();
    private int selected;
    private boolean armed;
    private int first;
    private int visible = Integer.MAX_VALUE;
    private int lastClickRow = -1;
    private long lastClickMs;
    private Hit pressed;

    /**
     * @param metas the library's recordings (any order); unclosed, unnamed and replay-viewer ones are left out
     */
    public GalleryModel(Origin origin, List<RecordingMeta> metas, long nowMs, TimeZone zone) {
        this.origin = origin;
        if (metas != null) {
            for (RecordingMeta m : metas) {
                if (listed(m)) entries.add(new Entry(m, nowMs, zone));
            }
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.startedAtMs != b.startedAtMs) return a.startedAtMs > b.startedAtMs ? -1 : 1;
                return b.id.compareTo(a.id);
            }
        });
    }

    /** Whether a recording gets a row: closed, with a valid id, and not a replay-viewer visit. */
    public static boolean listed(RecordingMeta m) {
        return m != null && m.complete && PruneRules.isValidId(m.id) && !ModeLabels.isReplay(m);
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** The selected entry's index (0 when the list is empty). */
    public int selected() {
        return selected;
    }

    public boolean armed() {
        return armed;
    }

    public Done doneTarget() {
        return origin == Origin.PLAYING ? Done.ESC_MENU : Done.TITLE_SCREEN;
    }

    public int firstRow() {
        return first;
    }

    // ------------------------------------------------------------------ actions

    public Intent watch() {
        armed = false;
        if (entries.isEmpty()) return Intent.NONE;
        return new Intent(Intent.Kind.WATCH, entries.get(selected).id, false, null);
    }

    /** First press arms, the second confirms (the screen deletes, then calls {@link #removed} or {@link #deleteFailed}). */
    public Intent delete() {
        if (entries.isEmpty()) {
            armed = false;
            return Intent.NONE;
        }
        if (!armed) {
            armed = true;
            return Intent.NONE;
        }
        return new Intent(Intent.Kind.DELETE, entries.get(selected).id, false, null);
    }

    /** Export the selected game (opens it in the viewer's export mode); nothing when the list is empty. */
    public Intent export() {
        armed = false;
        if (entries.isEmpty()) return Intent.NONE;
        return new Intent(Intent.Kind.EXPORT, entries.get(selected).id, false, null);
    }

    public Intent done() {
        armed = false;
        return new Intent(Intent.Kind.DONE, null, false, doneTarget());
    }

    /** A click on row {@code index}: selects it; a second click on it within {@value #DOUBLE_CLICK_MS} ms watches. */
    public Intent clickRow(int index, long nowMs) {
        armed = false;
        if (index < 0 || index >= entries.size()) return Intent.NONE;
        boolean dbl = index == lastClickRow && nowMs - lastClickMs < DOUBLE_CLICK_MS;
        select(index);
        if (dbl) {
            lastClickRow = -1;
            return new Intent(Intent.Kind.WATCH, entries.get(index).id, false, null);
        }
        lastClickRow = index;
        lastClickMs = nowMs;
        return Intent.NONE;
    }

    /** A click on row {@code index}'s star: asks for the opposite star; does not select. */
    public Intent clickStar(int index) {
        armed = false;
        if (index < 0 || index >= entries.size()) return Intent.NONE;
        Entry e = entries.get(index);
        return new Intent(Intent.Kind.STAR, e.id, !e.starred, null);
    }

    /** A keyboard key (LWJGL code), a fresh press. */
    public Intent key(int code, long nowMs) {
        return key(code, false, nowMs);
    }

    /**
     * A keyboard key (LWJGL code); {@code repeat}: a key-repeat event of a held key. Delete and Backspace act on fresh
     * presses only, so holding one never confirms the armed Delete or arms the next row (code review 1, I1); held
     * arrows still move the selection.
     */
    public Intent key(int code, boolean repeat, long nowMs) {
        if (repeat && (code == KEY_DELETE || code == KEY_BACK)) {
            return Intent.NONE;
        }
        switch (code) {
            case KEY_UP:
                armed = false;
                select(selected - 1);
                return Intent.NONE;
            case KEY_DOWN:
                armed = false;
                select(selected + 1);
                return Intent.NONE;
            case KEY_RETURN:
            case KEY_NUMPADENTER:
                return watch();
            case KEY_DELETE:
            case KEY_BACK:
                return delete();
            case KEY_ESCAPE:
                return done();
            default:
                return Intent.NONE;
        }
    }

    /** The mouse wheel: {@code rows} down (negative = up). */
    public void scroll(int rows) {
        first += rows;
        clampFirst();
    }

    // ------------------------------------------------------------------ results from the library

    /** The library confirmed a star change. */
    public void starred(String id, boolean on) {
        for (Entry e : entries) {
            if (e.id.equals(id)) e.starred = on;
        }
    }

    /** The library deleted a recording. */
    public void removed(String id) {
        armed = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(id)) {
                entries.remove(i);
                if (i < selected) selected--;
                break;
            }
        }
        select(selected);
        lastClickRow = -1;
    }

    /** The library refused a delete (the file is open or locked): Delete disarms, the row stays. */
    public void deleteFailed() {
        armed = false;
    }

    private void select(int index) {
        selected = entries.isEmpty() ? 0 : Math.max(0, Math.min(entries.size() - 1, index));
        if (selected < first) first = selected;
        if (visible > 0 && selected >= first + visible) first = selected - visible + 1;
        clampFirst();
    }

    private void clampFirst() {
        int v = visible == Integer.MAX_VALUE ? entries.size() : visible;
        first = Math.max(0, Math.min(Math.max(0, entries.size() - v), first));
    }

    // ------------------------------------------------------------------ layout and mouse

    /**
     * Lays the card out on a screen and fits the rows shown to its height (the selection stays visible). The mockup's
     * columns at 440 su: title at 26, result at 168, date at 212, the length ending 12 su before the right edge; on a
     * narrower screen the result and date columns move left so the date never reaches the length.
     */
    public Layout layout(float screenW, float screenH, TextMetrics m) {
        float w = Math.min(CARD_W, screenW - 2 * MARGIN);
        int fit = (int) Math.floor((screenH - 2 * MARGIN - 2 - FOOT_H + 1) / (ROW_H + 1));
        int v = Math.max(1, fit);
        if (v != visible) {
            visible = v;
            select(selected); // a new screen size: bring the selection into view
        } else {
            clampFirst();     // otherwise keep the wheel's scroll
        }
        int rows = Math.min(entries.size(), visible);
        float bodyH = rows == 0 ? ROW_H : rows * ROW_H + (rows - 1);
        float h = 1 + bodyH + FOOT_H + 1;
        float x = Math.round((screenW - w) / 2f), y = Math.round((screenH - h) / 2f);
        float rw = w - 2;
        float lenColW = m.width("00:00", ViewerLayout.FONT);
        float dateColW = m.width("Yesterday 00:00", ViewerLayout.FONT);
        float dateOff = Math.min(212, rw - 12 - lenColW - 8 - dateColW);
        float resultOff = Math.min(168, dateOff - 44);
        float titleOff = 26;
        return new Layout(x, y, w, h, rows, first, x + 1 + titleOff, resultOff - titleOff - 4, x + 1 + resultOff,
                x + 1 + dateOff, x + 1 + rw - 12);
    }

    public void mouseDown(Layout l, float x, float y) {
        Hit h = l.hit(x, y);
        pressed = h.part == Hit.Part.NONE || h.part == Hit.Part.CARD ? null : h;
    }

    /** A click fires when pressed and released over the same part. */
    public Intent mouseUp(Layout l, float x, float y, long nowMs) {
        Hit p = pressed;
        pressed = null;
        if (p == null || !p.same(l.hit(x, y))) return Intent.NONE;
        switch (p.part) {
            case ROW:
                return clickRow(p.row, nowMs);
            case STAR:
                return clickStar(p.row);
            case WATCH:
                return watch();
            case EXPORT:
                return export();
            case DELETE:
                return delete();
            case DONE:
                return done();
            default:
                return Intent.NONE;
        }
    }

    /** The part held down (a key drawn sunk), or null. */
    public Hit pressed() {
        return pressed;
    }
}
