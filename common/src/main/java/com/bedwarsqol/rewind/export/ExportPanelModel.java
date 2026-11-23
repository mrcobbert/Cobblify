package com.bedwarsqol.rewind.export;

import com.bedwarsqol.gui.sheet.SheetFont;
import com.bedwarsqol.gui.sheet.TextMetrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The export panel in the viewer's export mode (G10, G12, G42; Rewind Export PLAN Solution 5, D2, D6, D8, review I5;
 * TASK U4, U5, U6, P9): five rows (Resolution, Frame Rate, Quality, Codec, Chat) and one Export key, in a card above
 * the bar at the right. No other text.
 *
 * <ul>
 *   <li>Values start from {@link ExportSettings} (the defaults the first time, then the values last exported with).</li>
 *   <li>What can be picked comes from the helper's probe ({@link Matrix}): a codec with no rows is not offered; a size
 *       with no rate, or a rate the encoder cannot open at the chosen size, cannot be picked; a stored value that
 *       cannot be had falls back to the nearest one that can. Until the probe arrives nothing can be exported.</li>
 *   <li>On the window route ({@link RenderRoute.Route#WINDOW}) Resolution offers only the window's own pixel size,
 *       rounded down to even, whatever it is, with the rates the probe listed for it; the stored resolution is kept
 *       for when off-screen rendering works.</li>
 *   <li>When nothing can be encoded at the export size the Export key is disabled and does nothing.</li>
 *   <li>The key: Export, then filling with the share of frames done, then Open Folder, or Failed in red (another
 *       export can be started from it). While it fills nothing but Esc does anything; Esc cancels. Otherwise Esc
 *       leaves export mode. A changed row makes the key read Export again.</li>
 * </ul>
 *
 * <p>The model holds the choice and the key's state; the screen carries out its {@link Intent}s. One thread.
 */
public final class ExportPanelModel {

    public enum Row { RESOLUTION, FRAME_RATE, QUALITY, CODEC, CHAT }

    public enum KeyState { EXPORT, DISABLED, FILLING, OPEN_FOLDER, FAILED }

    /** The panel's width without its label column: the options column. */
    public static final float OPTIONS_W = 169f;
    public static final float PAD = 9f;
    public static final float ROW_H = 22f;
    public static final float OPTION_H = 16f;
    public static final float TOGGLE_W = 28f, TOGGLE_H = 14f;
    public static final float FOOT_H = 1f + 8f + 20f + 8f;
    /** Space between the panel and the bar below it, as the camera menu keeps. */
    public static final float ABOVE_BAR = 3f;
    /** The panel's right edge sits this far from the screen's, as the bar's does. */
    public static final float EDGE = 2f;
    public static final SheetFont FONT = SheetFont.ROW_LABEL;

    private static final int KEY_ESCAPE = 1;
    private static final String[] RES_LABELS = {"720p", "1080p", "1440p", "4K"};
    private static final String[] QUALITY_LABELS = {"Standard", "High", "Max"};
    private static final String[] CODEC_LABELS = {"H.264", "HEVC"};

    /** What the helper's probe said the encoders open: {width, height, fps} rows per codec. */
    public static final class Matrix {
        private final List<int[]> h264;
        private final List<int[]> hevc;

        public Matrix(List<int[]> h264, List<int[]> hevc) {
            this.h264 = h264 == null ? Collections.<int[]>emptyList() : new ArrayList<int[]>(h264);
            this.hevc = hevc == null ? Collections.<int[]>emptyList() : new ArrayList<int[]>(hevc);
        }

        private List<int[]> rows(String codec) {
            return "hevc".equals(codec) ? hevc : h264;
        }

        /** The encoder for {@code codec} opens at this size and rate. */
        public boolean has(String codec, int w, int h, int fps) {
            for (int[] r : rows(codec)) {
                if (r.length >= 3 && r[0] == w && r[1] == h && r[2] == fps) return true;
            }
            return false;
        }

        /** It opens at this size at some rate. */
        public boolean hasSize(String codec, int w, int h) {
            for (int fps : ExportSettings.RATES) {
                if (has(codec, w, h, fps)) return true;
            }
            return false;
        }

        /** It opens at all. */
        public boolean any(String codec) {
            return !rows(codec).isEmpty();
        }
    }

    /** What an export uses: the pixel size, the rate, quality, codec and Chat; and what to remember. */
    public static final class Choice {
        private final int width, height, fps;
        private final ExportSettings settings;

        Choice(int width, int height, int fps, ExportSettings settings) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.settings = settings;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int fps() {
            return fps;
        }

        public String quality() {
            return settings.quality();
        }

        public String codec() {
            return settings.codec();
        }

        public boolean chat() {
            return settings.chat();
        }

        /** The values to remember as the last used (the window route keeps the stored resolution). */
        public ExportSettings settings() {
            return settings;
        }
    }

    /** What the screen should do. */
    public static final class Intent {
        public enum Kind {
            NONE,
            /** Start an export with {@link #choice}. */
            EXPORT,
            /** Cancel the running export. */
            CANCEL,
            /** Open the folder that holds the finished file. */
            OPEN_FOLDER,
            /** Leave export mode. */
            LEAVE
        }

        public final Kind kind;
        public final Choice choice;

        Intent(Kind kind, Choice choice) {
            this.kind = kind;
            this.choice = choice;
        }

        static final Intent NONE = new Intent(Kind.NONE, null);
    }

    /** What is under a point: an option ({@link #row}, {@link #index}), the Chat switch, the key, the panel, or nothing. */
    public static final class Hit {
        public enum Part { NONE, PANEL, OPTION, TOGGLE, KEY }

        public final Part part;
        public final Row row;
        public final int index;

        Hit(Part part, Row row, int index) {
            this.part = part;
            this.row = row;
            this.index = index;
        }

        public boolean same(Hit o) {
            return o != null && o.part == part && o.row == row && o.index == index;
        }
    }

    /** Where the panel and its parts are, in GUI px (su). */
    public static final class Layout {
        public final float x, y, w, h;
        /** Where the labels start and the options column starts. */
        public final float labelX, optionsX;
        private final int[] counts;

        Layout(float x, float y, float w, float h, float labelX, float optionsX, int[] counts) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.labelX = labelX;
            this.optionsX = optionsX;
            this.counts = counts;
        }

        /** The top of row {@code r}. */
        public float rowTop(Row r) {
            return y + 1 + 3 + r.ordinal() * ROW_H;
        }

        /** The line top of row {@code r}'s label (a 9 su line centred in the row). */
        public float labelTop(Row r) {
            return rowTop(r) + (ROW_H - 9f) / 2f;
        }

        /** The options shown in row {@code r}. */
        public int count(Row r) {
            return counts[r.ordinal()];
        }

        /**
         * {x, y, w, h} of option {@code i} in row {@code r}: the options share the column, neighbours overlapping by
         * their 1 su border (the sheet's option row).
         */
        public float[] option(Row r, int i) {
            int n = Math.max(1, counts[r.ordinal()]);
            float ow = (OPTIONS_W + (n - 1)) / n;
            return new float[]{optionsX + i * (ow - 1), rowTop(r) + (ROW_H - OPTION_H) / 2f, ow, OPTION_H};
        }

        /** {x, y, w, h} of the Chat switch, at the right of its row. */
        public float[] toggle() {
            return new float[]{optionsX + OPTIONS_W - TOGGLE_W, rowTop(Row.CHAT) + (ROW_H - TOGGLE_H) / 2f, TOGGLE_W,
                    TOGGLE_H};
        }

        /** The footer band's top (a black line, then the band). */
        public float footTop() {
            return y + h - 1 - FOOT_H;
        }

        /** {x, y, w, h} of the Export key. */
        public float[] key() {
            return new float[]{x + PAD, footTop() + 1 + 8, w - 2 * PAD, 20};
        }

        public Hit hit(float px, float py) {
            for (Row r : Row.values()) {
                if (r == Row.CHAT) {
                    if (in(toggle(), px, py)) return new Hit(Hit.Part.TOGGLE, Row.CHAT, -1);
                    continue;
                }
                for (int i = counts[r.ordinal()] - 1; i >= 0; i--) {
                    if (in(option(r, i), px, py)) return new Hit(Hit.Part.OPTION, r, i);
                }
            }
            if (in(key(), px, py)) return new Hit(Hit.Part.KEY, null, -1);
            if (px >= x && py >= y && px < x + w && py < y + h) return new Hit(Hit.Part.PANEL, null, -1);
            return new Hit(Hit.Part.NONE, null, -1);
        }

        private static boolean in(float[] r, float px, float py) {
            return px >= r[0] && py >= r[1] && px < r[0] + r[2] && py < r[1] + r[3];
        }
    }

    // ------------------------------------------------------------------ state

    /** What the player asked for (the stored settings, then each pick). */
    private int height;
    private int fps;
    private String quality;
    private String codec;
    private boolean chat;

    private Matrix matrix;
    private boolean window;
    private int windowW, windowH;
    private KeyState state = KeyState.EXPORT;
    private float fill;
    private Hit pressed;

    public ExportPanelModel(ExportSettings stored) {
        ExportSettings s = stored == null ? ExportSettings.defaults() : stored;
        height = s.height();
        fps = s.fps();
        quality = s.quality();
        codec = s.codec();
        chat = s.chat();
    }

    public static String label(Row r) {
        switch (r) {
            case RESOLUTION:
                return "Resolution";
            case FRAME_RATE:
                return "Frame Rate";
            case QUALITY:
                return "Quality";
            case CODEC:
                return "Codec";
            default:
                return "Chat";
        }
    }

    /** The helper's probe arrived (or changed). */
    public void probe(Matrix m) {
        matrix = m;
    }

    /** The render route chosen for this game session, and the window's pixel size (used on the window route). */
    public void route(RenderRoute.Route route, int windowW, int windowH) {
        window = route == RenderRoute.Route.WINDOW;
        this.windowW = Math.max(0, windowW) & ~1;
        this.windowH = Math.max(0, windowH) & ~1;
    }

    // ------------------------------------------------------------------ rows

    /** The values row {@code r} shows, in order. */
    public List<String> options(Row r) {
        switch (r) {
            case RESOLUTION:
                return window ? Collections.singletonList(windowW + "x" + windowH) : Arrays.asList(RES_LABELS);
            case FRAME_RATE: {
                List<String> out = new ArrayList<String>();
                for (int f : ExportSettings.RATES) out.add(Integer.toString(f));
                return out;
            }
            case QUALITY:
                return Arrays.asList(QUALITY_LABELS);
            case CODEC: {
                List<String> out = new ArrayList<String>();
                for (String c : offeredCodecs()) out.add(CODEC_LABELS[ExportSettings.indexOf(ExportSettings.CODECS, c)]);
                return out;
            }
            default:
                return Arrays.asList("On", "Off");
        }
    }

    /** Whether option {@code i} of row {@code r} can be picked now. */
    public boolean available(Row r, int i) {
        if (i < 0 || i >= options(r).size()) return false;
        String c = effectiveCodec();
        switch (r) {
            case RESOLUTION:
                if (c == null) return false;
                if (window) return matrix.hasSize(c, windowW, windowH);
                int h = ExportSettings.HEIGHTS[i];
                return matrix.hasSize(c, ExportSettings.width(h), h);
            case FRAME_RATE: {
                int[] size = effectiveSize(c);
                return size != null && matrix.has(c, size[0], size[1], ExportSettings.RATES[i]);
            }
            case CODEC:
                return true;
            default:
                return true;
        }
    }

    /** The index of the value row {@code r} shows chosen, or -1 when nothing can be had there. */
    public int chosen(Row r) {
        switch (r) {
            case RESOLUTION: {
                if (window) return effectiveSize(effectiveCodec()) == null ? -1 : 0;
                int[] size = effectiveSize(effectiveCodec());
                return size == null ? -1 : ExportSettings.indexOf(ExportSettings.HEIGHTS, size[1]);
            }
            case FRAME_RATE: {
                int f = effectiveFps(effectiveCodec());
                return f < 0 ? -1 : ExportSettings.indexOf(ExportSettings.RATES, f);
            }
            case QUALITY:
                return ExportSettings.indexOf(ExportSettings.QUALITIES, quality);
            case CODEC: {
                String c = effectiveCodec();
                return c == null ? -1 : offeredCodecs().indexOf(c);
            }
            default:
                return chat ? 0 : 1;
        }
    }

    /** Picks option {@code i} of row {@code r} when it can be picked; not while an export fills. */
    public void pick(Row r, int i) {
        if (state == KeyState.FILLING || !available(r, i)) return;
        switch (r) {
            case RESOLUTION:
                if (!window) height = ExportSettings.HEIGHTS[i];
                break;
            case FRAME_RATE:
                fps = ExportSettings.RATES[i];
                break;
            case QUALITY:
                quality = ExportSettings.QUALITIES[i];
                break;
            case CODEC:
                codec = offeredCodecs().get(i);
                break;
            default:
                chat = i == 0;
                break;
        }
        if (state == KeyState.OPEN_FOLDER || state == KeyState.FAILED) state = KeyState.EXPORT;
    }

    /** What an export would use now, or null when nothing can be encoded at the export size. */
    public Choice choice() {
        String c = effectiveCodec();
        int[] size = effectiveSize(c);
        int f = effectiveFps(c);
        if (size == null || f < 0) return null;
        int rememberedHeight = window ? height : size[1];
        return new Choice(size[0], size[1], f, ExportSettings.of(rememberedHeight, f, quality, c, chat));
    }

    // ------------------------------------------------------------------ resolving against the probe

    /** Codecs with any row, in list order. */
    private List<String> offeredCodecs() {
        List<String> out = new ArrayList<String>();
        if (matrix != null) {
            for (String c : ExportSettings.CODECS) {
                if (matrix.any(c)) out.add(c);
            }
        }
        if (out.isEmpty()) out.add(ExportSettings.CODECS[0]);
        return out;
    }

    /** The asked-for codec if the encoder has it, else the first one it has; null without a probe or rows. */
    private String effectiveCodec() {
        if (matrix == null) return null;
        if (matrix.any(codec)) return codec;
        for (String c : ExportSettings.CODECS) {
            if (matrix.any(c)) return c;
        }
        return null;
    }

    /** {w, h}: the window's size, or the asked-for preset or the nearest one with rows; null when none has rows. */
    private int[] effectiveSize(String c) {
        if (c == null) return null;
        if (window) return matrix.hasSize(c, windowW, windowH) ? new int[]{windowW, windowH} : null;
        int best = -1;
        for (int h : ExportSettings.HEIGHTS) {
            if (!matrix.hasSize(c, ExportSettings.width(h), h)) continue;
            if (best < 0 || Math.abs(h - height) < Math.abs(best - height)) best = h;
        }
        return best < 0 ? null : new int[]{ExportSettings.width(best), best};
    }

    /** The asked-for rate if the encoder opens it at the export size, else the nearest that it opens; -1 for none. */
    private int effectiveFps(String c) {
        int[] size = effectiveSize(c);
        if (size == null) return -1;
        int best = -1;
        for (int f : ExportSettings.RATES) {
            if (!matrix.has(c, size[0], size[1], f)) continue;
            if (best < 0 || Math.abs(f - fps) < Math.abs(best - fps)) best = f;
        }
        return best;
    }

    // ------------------------------------------------------------------ the key

    public KeyState keyState() {
        if (state == KeyState.FILLING) return state;
        if (choice() == null) return KeyState.DISABLED;
        return state;
    }

    /** The share of frames done while filling, 0 to 1. */
    public float fill() {
        return fill;
    }

    /** An export runs and has done {@code share} of its frames. */
    public void filling(float share) {
        state = KeyState.FILLING;
        fill = Math.max(0f, Math.min(1f, share));
    }

    /** The export finished: the key reads Open Folder. */
    public void done() {
        state = KeyState.OPEN_FOLDER;
        fill = 0f;
    }

    /** The export failed: the key turns red and reads Failed. */
    public void failed() {
        state = KeyState.FAILED;
        fill = 0f;
    }

    /** The export was cancelled (or export mode reopened): the key reads Export. */
    public void idle() {
        state = KeyState.EXPORT;
        fill = 0f;
    }

    /** A keyboard key (LWJGL code): Esc cancels a filling export, otherwise leaves export mode; others nothing. */
    public Intent key(int code) {
        if (code != KEY_ESCAPE) return Intent.NONE;
        return new Intent(state == KeyState.FILLING ? Intent.Kind.CANCEL : Intent.Kind.LEAVE, null);
    }

    /** The key was clicked. */
    private Intent pressKey() {
        switch (keyState()) {
            case EXPORT:
            case FAILED:
                return new Intent(Intent.Kind.EXPORT, choice());
            case OPEN_FOLDER:
                return new Intent(Intent.Kind.OPEN_FOLDER, null);
            default:
                return Intent.NONE;
        }
    }

    // ------------------------------------------------------------------ layout and mouse

    /**
     * Lays the panel out above the bar (whose top is at {@code barTop}) at the screen's right: a label column as wide
     * as the widest label plus 8 su, then the options column; five {@value #ROW_H} su rows, then the key's footer.
     */
    public Layout layout(float screenW, float barTop, TextMetrics m) {
        float labelW = 0;
        for (Row r : Row.values()) labelW = Math.max(labelW, m.width(label(r), FONT));
        labelW = (float) Math.ceil(labelW) + 8;
        float w = PAD + labelW + OPTIONS_W + PAD;
        float h = 1 + 3 + Row.values().length * ROW_H + 3 + FOOT_H + 1;
        float x = Math.round(screenW - EDGE - w);
        float y = Math.max(0, Math.round(barTop - ABOVE_BAR - h));
        int[] counts = new int[Row.values().length];
        for (Row r : Row.values()) counts[r.ordinal()] = r == Row.CHAT ? 0 : options(r).size();
        return new Layout(x, y, w, h, x + PAD, x + PAD + labelW, counts);
    }

    public void mouseDown(Layout l, float x, float y) {
        Hit h = l.hit(x, y);
        pressed = state == KeyState.FILLING || h.part == Hit.Part.NONE || h.part == Hit.Part.PANEL ? null : h;
    }

    /** A click fires when pressed and released over the same part. */
    public Intent mouseUp(Layout l, float x, float y) {
        Hit p = pressed;
        pressed = null;
        if (p == null || state == KeyState.FILLING || !p.same(l.hit(x, y))) return Intent.NONE;
        switch (p.part) {
            case OPTION:
                pick(p.row, p.index);
                return Intent.NONE;
            case TOGGLE:
                pick(Row.CHAT, chat ? 1 : 0);
                return Intent.NONE;
            case KEY:
                return pressKey();
            default:
                return Intent.NONE;
        }
    }

    /** The part held down (drawn sunk), or null. */
    public Hit pressed() {
        return pressed;
    }
}
