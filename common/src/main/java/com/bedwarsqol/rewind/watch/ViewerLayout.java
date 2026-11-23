package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.gui.sheet.SheetFont;
import com.bedwarsqol.gui.sheet.TextMetrics;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.export.ExportRange;

import java.util.List;

/**
 * The viewer's bar (G36, G45, G51; TASK A9, A10; parity B11, Q1), in GUI px (su), as the mockup's {@code drawBar} lays
 * it out, at the bottom edge (the user's call after watching dev.102; it covers the hotbar only while it shows and
 * fades when the mouse rests, {@code BarFade}): a card {@value #EDGE} su from the screen's sides and {@value #GAP} su
 * above its bottom, {@value #BAR_H} su tall, holding the play/pause key, the timeline (a recess with marker icons and
 * the playhead), the time ({@code m:ss / m:ss}), the speed key and the camera key with its menu opening upward above
 * it (You, Free camera, then every player another player's view can ride, Phase 14); the free camera's speed bar and
 * the ridden player's name sit at the top of the screen ({@link #flySpeedTop()}, {@link #riddenNameTop()}).
 *
 * <p><b>Export mode</b> (Rewind Export TASK U3): {@link #export} adds the export range's two handles on the timeline
 * ({@link #handle}). There a press hits, top first: an open camera menu, a handle, the playhead knob (a timeline drag),
 * a marker. Without a range the layout is exactly the one above.
 *
 * <p>Times are microseconds from the game start, from 0 to the game's length (the timeline runs from the game start
 * to the recording's end, so its length equals the gallery's). Marker indexes are the order the markers were given in,
 * which for a {@link Timeline} is {@link Timeline#markers()}'s (time order), so they match {@code MarkerNav}'s.
 *
 * <p><b>Markers on one pixel.</b> An icon starts at least {@value #MARKER_STEP} su right of the icon before it (a
 * deterministic fan), and icons are kept inside the recess. Later icons are drawn over earlier ones and win the hit
 * test where they overlap, so each icon keeps at least {@value #MARKER_STEP} su of its own clickable edge.
 *
 * <p>{@link Pointer} turns presses and releases into actions: a marker jumps on release, a press on the timeline
 * starts a drag that previews and seeks only on release.
 */
public final class ViewerLayout {

    /** Every label on the bar, measured and drawn in this role. */
    public static final SheetFont FONT = SheetFont.ROW_LABEL;
    public static final float EDGE = 2f;
    public static final float BAR_H = 26f;
    public static final float GAP = 2f;
    public static final float KEY = 16f;
    public static final float STRIP_H = 10f;
    public static final float MARKER_STEP = 3f;
    public static final float MENU_ROW_H = 14f;
    /** The free camera's option: the menu's first row. */
    public static final int FREE = 0;
    /** Your view's option. */
    public static final int YOU = 1;
    /**
     * The camera key's fixed options, in menu order; the index is the camera ({@link #FREE}, {@link #YOU}). The
     * players follow from {@link #FIRST_PLAYER}.
     */
    public static final String[] CAMERA_LABELS = {"Free camera", "You"};
    /** Space a head or the camera icon takes before an option's label. */
    public static final float CAMERA_ICON = 14f;
    /** The camera option of the first player in the menu (Phase 14): Free camera, You, then the players. */
    public static final int FIRST_PLAYER = 2;
    /** The line top of the ridden player's name and the top of the free camera's speed bar. */
    public static final float TOP_LINE = 6f;
    private static final float[] SPEEDS = {0.25f, 0.5f, 1f, 2f, 4f};

    /** {@code HANDLE}: an export handle, index 0 the start, 1 the end (export mode only). */
    public enum Part { NONE, BAR, PLAY, STRIP, MARKER, SPEED, CAMERA, DROPDOWN, CAMERA_OPTION, HANDLE }

    /** What is under a point: a part and, for a marker or a camera option, its index (else -1). */
    public static final class Hit {
        public final Part part;
        public final int index;

        Hit(Part part, int index) {
            this.part = part;
            this.index = index;
        }

        public boolean same(Hit o) {
            return o != null && o.part == part && o.index == index;
        }

        @Override
        public String toString() {
            return part + (index >= 0 ? "#" + index : "");
        }
    }

    /** {x, y, w, h} of the bar card, the play key, the camera key and the speed key. */
    public final float[] bar, play, camera, speed;
    /** The time text's span; the current time ends where {@code " / length"} starts. */
    public final float timeLeft, timeRight;
    /** The recess: its outer box starts at {@code stripX} (a black line, then the pit). */
    public final float stripX, stripW, stripY, stripH;

    private final TextMetrics metrics;
    private final long lengthUs;
    private final String total;
    private final float totalW;
    private final int[] kinds;
    private final float[] markerX, markerW;
    /** Camera menu: every option's label, the key's option and label, rows per column, columns, column width. */
    private final String[] options;
    private final int keyOption;
    private final String keyLabel;
    private final int menuRows, menuCols;
    private final float menuColW;
    /** Export mode: the range (null when off) and the playhead, whose knob a press then prefers to a marker. */
    private final ExportRange range;
    private final long playheadUs;

    private ViewerLayout(float screenW, float screenH, TextMetrics m, long lengthUs, long[] markerUs, int[] kinds) {
        this(screenW, screenH, m, lengthUs, markerUs, kinds, null, YOU);
    }

    private ViewerLayout(float screenW, float screenH, TextMetrics m, long lengthUs, long[] markerUs, int[] kinds,
                         String[] players, int cameraOption) {
        this.metrics = m;
        this.range = null;
        this.playheadUs = 0;
        this.lengthUs = Math.max(0L, lengthUs);
        float bx = EDGE, bw = screenW - 2 * EDGE, by = screenH - GAP - BAR_H;
        bar = new float[]{bx, by, bw, BAR_H};
        play = new float[]{bx + 5, by + 5, KEY, KEY};
        int np = players == null ? 0 : players.length;
        options = new String[CAMERA_LABELS.length + np];
        System.arraycopy(CAMERA_LABELS, 0, options, 0, CAMERA_LABELS.length);
        for (int i = 0; i < np; i++) options[FIRST_PLAYER + i] = players[i] == null ? "" : players[i];
        keyOption = cameraOption >= 0 && cameraOption < options.length ? cameraOption : YOU;
        // The key is as wide as its widest fixed label, so riding a player never moves the bar; a longer name is cut
        // to fit (whole characters), while the menu below grows to show it in full.
        float cw = 0;
        for (String s : CAMERA_LABELS) cw = Math.max(cw, m.width(s, FONT));
        keyLabel = fit(m, options[keyOption], cw);
        float camW = 7 + CAMERA_ICON + cw + 6 + 6 + 7;
        camera = new float[]{bx + bw - 5 - camW, by + 5, camW, KEY};
        float colW = camW;
        for (int i = FIRST_PLAYER; i < options.length; i++) {
            colW = Math.max(colW, 7 + CAMERA_ICON + m.width(options[i], FONT) + 7);
        }
        menuColW = colW;
        // the menu opens upward from 3 su above the bar and stops GAP below the screen's top; more rows, more columns
        menuRows = Math.max(CAMERA_LABELS.length,
                Math.min(options.length, (int) Math.floor((by - 3 - GAP - 8) / MENU_ROW_H)));
        menuCols = (options.length + menuRows - 1) / menuRows;
        float spW = m.width(speedLabel(0.25f), FONT) + 12;
        speed = new float[]{camera[0] - 4 - spW, by + 5, spW, KEY};
        String len = DateLabels.length(this.lengthUs);
        total = " / " + len;
        totalW = m.width(total, FONT);
        timeRight = speed[0] - 9;
        timeLeft = timeRight - (m.width(len.replaceAll("[0-9]", "0"), FONT) + totalW);
        stripX = bx + 5 + KEY + 7;
        stripW = timeLeft - 9 - stripX;
        stripY = by + 8;
        stripH = STRIP_H;

        int n = Math.min(markerUs.length, kinds.length);
        this.kinds = new int[n];
        markerX = new float[n];
        markerW = new float[n];
        float minX = stripX + 1, maxRight = stripX + stripW - 1;
        for (int i = 0; i < n; i++) {
            this.kinds[i] = kinds[i];
            markerW[i] = iconWidth(kinds[i]);
            float x = Math.round(xOf(markerUs[i]) - markerW[i] / 2f);
            if (i > 0) x = Math.max(x, markerX[i - 1] + MARKER_STEP);
            markerX[i] = Math.max(x, minX);
        }
        if (n > 0) {
            markerX[n - 1] = Math.min(markerX[n - 1], maxRight - markerW[n - 1]);
            for (int i = n - 2; i >= 0; i--) markerX[i] = Math.min(markerX[i], markerX[i + 1] - MARKER_STEP);
            for (int i = 0; i < n; i++) markerX[i] = Math.max(markerX[i], minX);
        }
    }

    /** {@code o} in export mode: the same geometry with the range's handles. */
    private ViewerLayout(ViewerLayout o, long playheadUs, ExportRange range) {
        bar = o.bar;
        play = o.play;
        camera = o.camera;
        speed = o.speed;
        timeLeft = o.timeLeft;
        timeRight = o.timeRight;
        stripX = o.stripX;
        stripW = o.stripW;
        stripY = o.stripY;
        stripH = o.stripH;
        metrics = o.metrics;
        lengthUs = o.lengthUs;
        total = o.total;
        totalW = o.totalW;
        kinds = o.kinds;
        markerX = o.markerX;
        markerW = o.markerW;
        options = o.options;
        keyOption = o.keyOption;
        keyLabel = o.keyLabel;
        menuRows = o.menuRows;
        menuCols = o.menuCols;
        menuColW = o.menuColW;
        this.range = range;
        this.playheadUs = playheadUs;
    }

    /**
     * @param markerUs marker times from the game start, in time order
     * @param kinds    their {@code RewindFormat.MARKER_*} kinds
     */
    public static ViewerLayout of(float screenW, float screenH, TextMetrics m, long lengthUs, long[] markerUs,
                                  int[] kinds) {
        return new ViewerLayout(screenW, screenH, m, lengthUs, markerUs == null ? new long[0] : markerUs,
                kinds == null ? new int[0] : kinds);
    }

    /** The bar for a loaded recording's timeline: its length and markers, counted from the game start. */
    public static ViewerLayout of(float screenW, float screenH, TextMetrics m, Timeline tl) {
        return of(screenW, screenH, m, tl, null, YOU);
    }

    /**
     * A bar whose camera menu also lists {@code players} (Phase 14; menu order, null for none) and whose camera key
     * shows option {@code camera} ({@link #camera()}).
     */
    public static ViewerLayout of(float screenW, float screenH, TextMetrics m, long lengthUs, long[] markerUs,
                                  int[] kinds, String[] players, int camera) {
        return new ViewerLayout(screenW, screenH, m, lengthUs, markerUs == null ? new long[0] : markerUs,
                kinds == null ? new int[0] : kinds, players, camera);
    }

    /** {@link #of(float, float, TextMetrics, Timeline)} with the players in the camera menu. */
    public static ViewerLayout of(float screenW, float screenH, TextMetrics m, Timeline tl, String[] players,
                                  int camera) {
        List<Timeline.Marker> ms = tl.markers();
        long[] us = new long[ms.size()];
        int[] kinds = new int[ms.size()];
        for (int i = 0; i < us.length; i++) {
            us[i] = ms.get(i).timeUs - tl.gameStartUs();
            kinds[i] = ms.get(i).kind;
        }
        return of(screenW, screenH, m, tl.lengthUs(), us, kinds, players, camera);
    }

    /** The number of camera options: You, Free camera, then one per player. */
    public int cameraOptionCount() {
        return options.length;
    }

    /** Option {@code option}'s label: "You", "Free camera" or the player's name. */
    public String cameraLabel(int option) {
        return options[option];
    }

    /** The option the camera key shows ({@link #FREE}, {@link #YOU}, {@link #FIRST_PLAYER} + i player i). */
    public int camera() {
        return keyOption;
    }

    /** The camera key's label: its option's, a player's name cut to the key's width. */
    public String cameraKeyLabel() {
        return keyLabel;
    }

    /** The line top of the ridden player's name: centred at the top, where the free camera's speed bar sits. */
    public static float riddenNameTop() {
        return TOP_LINE;
    }

    /** The top of the free camera's speed bar ({@code RewindPainter.flySpeed}): at the top, centred. */
    public static float flySpeedTop() {
        return TOP_LINE;
    }

    /** "0.25x", "0.5x", "1x", "2x", "4x". */
    public static String speedLabel(float speed) {
        for (float s : SPEEDS) {
            if (s == speed) {
                return s == 0.25f ? "0.25x" : s == 0.5f ? "0.5x" : ((int) s) + "x";
            }
        }
        return (speed == (int) speed ? Integer.toString((int) speed) : Float.toString(speed)) + "x";
    }

    /** An icon's width: 5 su for the marker key's flag, 7 for the rest. */
    public static float iconWidth(int kind) {
        return kind == RewindFormat.MARKER_KEY ? 5f : 7f;
    }

    public long lengthUs() {
        return lengthUs;
    }

    // ------------------------------------------------------------------ export mode

    /**
     * This bar in export mode with {@code range}'s handles, the playhead at {@code playheadUs} (both from the game
     * start); {@code range} null gives this layout unchanged.
     */
    public ViewerLayout export(long playheadUs, ExportRange range) {
        return range == null ? this : new ViewerLayout(this, playheadUs, range);
    }

    public boolean exportMode() {
        return range != null;
    }

    /** The export range, or null outside export mode. */
    public ExportRange range() {
        return range;
    }

    /**
     * {x, y, w, h} of handle 0 (the range start) or 1 (its end): an 8 su wide hit rect centred on its time, 4 su above
     * and below the recess (mockup {@code drawBar}).
     */
    public float[] handle(int which) {
        long us = range == null ? 0 : which == 0 ? range.startUs() : range.endUs();
        return new float[]{xOf(us) - 4, stripY - 4, 8, stripH + 8};
    }

    // ------------------------------------------------------------------ time and x

    private float inner() {
        return Math.max(0f, stripW - 2);
    }

    /** The playhead's x for a time. */
    public float xOf(long us) {
        if (lengthUs <= 0) return stripX + 1;
        long t = Math.max(0L, Math.min(lengthUs, us));
        return (float) (stripX + 1 + inner() * ((double) t / lengthUs));
    }

    /** The time under x, clamped to [0, length]. */
    public long timeAt(float x) {
        float inner = inner();
        if (lengthUs <= 0 || inner <= 0) return 0L;
        double f = (x - stripX - 1) / inner;
        if (f <= 0) return 0L;
        if (f >= 1) return lengthUs;
        return Math.round(f * lengthUs);
    }

    /** The playhead knob {x, y, w, h} (the sheet slider's knob, 8 x 16). */
    public float[] knob(long us) {
        float x = xOf(us);
        return new float[]{x - 4, bar[1] + 5, 8, KEY};
    }

    // ------------------------------------------------------------------ labels

    /** The current time's label: whole seconds, the length at the end. */
    public String timeLabel(long us) {
        return DateLabels.position(us, lengthUs);
    }

    /** {@code " / m:ss"}. */
    public String totalLabel() {
        return total;
    }

    /** Where the current time's label starts (it ends where the total starts). */
    public float timeX(long us) {
        return totalX() - metrics.width(timeLabel(us), FONT);
    }

    public float totalX() {
        return timeRight - totalW;
    }

    /** The line top of the time labels (a 9 su line box centred in the keys' row). */
    public float timeTop() {
        return bar[1] + 5 + 3.5f;
    }

    // ------------------------------------------------------------------ markers

    public int markerCount() {
        return kinds.length;
    }

    public int markerKind(int i) {
        return kinds[i];
    }

    /** {x, y, w, h} of marker i's icon (7 or 5 wide, 7 tall, on the recess's first pit row). */
    public float[] markerIcon(int i) {
        return new float[]{markerX[i], stripY + 1, markerW[i], 7};
    }

    /** {x, y, w, h} of marker i's hover face and hit rect. */
    public float[] markerHit(int i) {
        return new float[]{markerX[i] - 1, stripY - 1, markerW[i] + 2, stripH + 2};
    }

    // ------------------------------------------------------------------ camera menu

    /**
     * The camera menu's card {x, y, w, h}, opening upward above the camera key, its right edge over the key's. One
     * column as wide as the key or the longest name; when the rows would reach the screen's top, further rows open
     * another column to the left.
     */
    public float[] dropdown() {
        int rows = Math.min(options.length, menuRows);
        float w = menuCols * menuColW;
        float x = Math.max(EDGE, camera[0] + camera[2] - w);
        float h = 1 + 3 + MENU_ROW_H * rows + 3 + 1;
        return new float[]{x, bar[1] - 3 - h, w, h};
    }

    /** {x, y, w, h} of the menu's row i (option i): down the first column, then the next. */
    public float[] dropdownRow(int i) {
        float[] d = dropdown();
        int col = i / menuRows, row = i % menuRows;
        return new float[]{d[0] + 1 + col * menuColW, d[1] + 4 + MENU_ROW_H * row, menuColW - 2, MENU_ROW_H};
    }

    /** The longest prefix of {@code s} (whole characters) no wider than {@code w}. */
    private static String fit(TextMetrics m, String s, float w) {
        if (m.width(s, FONT) <= w) return s;
        int n = s.length();
        while (n > 0 && m.width(s.substring(0, n), FONT) > w) n--;
        return s.substring(0, n);
    }

    // ------------------------------------------------------------------ hit test

    private static boolean in(float[] r, float x, float y) {
        return x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3];
    }

    /** Whether the point is over the bar (the bar then stays visible, {@code BarFade}). */
    public boolean over(float x, float y) {
        return in(bar, x, y);
    }

    /** The topmost part under the point; the camera menu counts only while it is open. */
    public Hit hit(float x, float y, boolean menuOpen) {
        if (menuOpen) {
            for (int i = 0; i < options.length; i++) {
                if (in(dropdownRow(i), x, y)) return new Hit(Part.CAMERA_OPTION, i);
            }
            if (in(dropdown(), x, y)) return new Hit(Part.DROPDOWN, -1);
        }
        if (range != null) {
            // the handle whose time is nearer the point wins where the two overlap
            float[] a = handle(0), b = handle(1);
            boolean ina = in(a, x, y), inb = in(b, x, y);
            if (ina && inb) {
                return new Hit(Part.HANDLE, Math.abs(x - (b[0] + 4)) < Math.abs(x - (a[0] + 4)) ? 1 : 0);
            }
            if (ina) return new Hit(Part.HANDLE, 0);
            if (inb) return new Hit(Part.HANDLE, 1);
            if (in(knob(playheadUs), x, y)) return new Hit(Part.STRIP, -1);
        }
        for (int i = kinds.length - 1; i >= 0; i--) {
            if (in(markerHit(i), x, y)) return new Hit(Part.MARKER, i);
        }
        if (in(new float[]{stripX - 1, bar[1] + 4, stripW + 2, 18}, x, y)) return new Hit(Part.STRIP, -1);
        if (in(play, x, y)) return new Hit(Part.PLAY, -1);
        if (in(speed, x, y)) return new Hit(Part.SPEED, -1);
        if (in(camera, x, y)) return new Hit(Part.CAMERA, -1);
        if (in(bar, x, y)) return new Hit(Part.BAR, -1);
        return new Hit(Part.NONE, -1);
    }

    // ------------------------------------------------------------------ pointer

    /** What a press or release asks the viewer to do. */
    public static final class Action {
        public enum Kind {
            NONE,
            /** Close the camera menu (a press outside it). */
            CLOSE_MENU,
            PLAY_PAUSE,
            /** Speed key clicked: one step up; at 4x it wraps to 0.25x (mockup). */
            SPEED_UP,
            /** Speed key right-clicked: one step down. */
            SPEED_DOWN,
            /** Camera key clicked: open or close its menu. */
            CAMERA_MENU,
            /**
             * Camera option {@link #index} chosen ({@link #FREE}, {@link #YOU}, {@link #FIRST_PLAYER} + i the i-th
             * player's view); the menu closes.
             */
            CAMERA,
            /** Jump to marker {@link #index} ({@code MarkerNav}). */
            MARKER,
            /** Seek to {@link #timeUs} from the game start (a timeline click or the end of a drag). */
            SEEK
        }

        public final Kind kind;
        public final int index;
        public final long timeUs;

        Action(Kind kind, int index, long timeUs) {
            this.kind = kind;
            this.index = index;
            this.timeUs = timeUs;
        }

        static final Action NONE = new Action(Kind.NONE, -1, 0);
        static final Action CLOSE_MENU = new Action(Kind.CLOSE_MENU, -1, 0);

        static Action of(Kind k) {
            return new Action(k, -1, 0);
        }
    }

    /**
     * The mouse on the bar: keys and markers fire when pressed and released over the same part; a press on the
     * timeline starts a drag whose position is only a preview until release, which seeks. In export mode a press on a
     * handle drags it: {@link #range()} follows the mouse under {@link ExportRange}'s rules and nothing seeks.
     */
    public static final class Pointer {
        private Hit pressed;
        private boolean dragging;
        private long previewUs;
        private ExportRange range;
        private int handle = -1;

        /**
         * A button goes down at (x, y). {@code right} is the right button (it only steps the speed down). Returns
         * {@link Action.Kind#CLOSE_MENU} when the press closes an open camera menu (a press on a key still works on
         * release), {@link Action.Kind#SPEED_DOWN} for a right-click on the speed key, else nothing yet.
         */
        public Action down(ViewerLayout l, float x, float y, boolean menuOpen, boolean right) {
            Hit h = l.hit(x, y, menuOpen);
            pressed = null;
            dragging = false;
            handle = -1;
            boolean inMenu = h.part == Part.CAMERA_OPTION || h.part == Part.DROPDOWN || h.part == Part.CAMERA;
            Action pre = menuOpen && !inMenu ? Action.CLOSE_MENU : Action.NONE;
            if (right) {
                return h.part == Part.SPEED ? Action.of(Action.Kind.SPEED_DOWN) : pre;
            }
            switch (h.part) {
                case HANDLE:
                    if (range == null) range = l.range();
                    handle = h.index;
                    return pre;
                case STRIP:
                    dragging = true;
                    previewUs = l.timeAt(x);
                    return pre;
                case PLAY:
                case SPEED:
                case CAMERA:
                case MARKER:
                case CAMERA_OPTION:
                    pressed = h;
                    return pre;
                default:
                    return pre;
            }
        }

        /** The mouse moved while a button is down. */
        public void move(ViewerLayout l, float x) {
            if (dragging) previewUs = l.timeAt(x);
            if (handle >= 0 && range != null) range = range.drag(handle, l.timeAt(x));
        }

        /** The button comes up at (x, y). */
        public Action up(ViewerLayout l, float x, float y) {
            if (handle >= 0) {
                if (range != null) range = range.drag(handle, l.timeAt(x));
                handle = -1;
                return Action.NONE;
            }
            if (dragging) {
                dragging = false;
                return new Action(Action.Kind.SEEK, -1, l.timeAt(x));
            }
            Hit p = pressed;
            pressed = null;
            if (p == null || !p.same(l.hit(x, y, p.part == Part.CAMERA_OPTION))) return Action.NONE;
            switch (p.part) {
                case PLAY:
                    return Action.of(Action.Kind.PLAY_PAUSE);
                case SPEED:
                    return Action.of(Action.Kind.SPEED_UP);
                case CAMERA:
                    return Action.of(Action.Kind.CAMERA_MENU);
                case MARKER:
                    return new Action(Action.Kind.MARKER, p.index, 0);
                case CAMERA_OPTION:
                    return new Action(Action.Kind.CAMERA, p.index, 0);
                default:
                    return Action.NONE;
            }
        }

        /** Whether a timeline drag is in progress (the bar stays visible; the playhead shows {@link #previewUs}). */
        public boolean dragging() {
            return dragging;
        }

        /** The dragged playhead's time; meaningful while {@link #dragging}. */
        public long previewUs() {
            return previewUs;
        }

        /** The part held down (drawn sunk), or null. */
        public Hit pressed() {
            return pressed;
        }

        /** Enters export mode with {@code r} (null leaves it); a handle drag in progress is dropped. */
        public void range(ExportRange r) {
            range = r;
            handle = -1;
        }

        /** The export range as the handles have set it; null outside export mode. */
        public ExportRange range() {
            return range;
        }

        /** The handle being dragged (0 start, 1 end; drawn lit), or -1. */
        public int handle() {
            return handle;
        }

        /** Forgets a press, e.g. when the screen loses the mouse. */
        public void cancel() {
            pressed = null;
            dragging = false;
            handle = -1;
        }
    }
}
