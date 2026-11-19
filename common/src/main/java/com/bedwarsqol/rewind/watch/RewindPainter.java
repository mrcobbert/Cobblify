package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.gui.sheet.Canvas;
import com.bedwarsqol.gui.sheet.SheetColors;
import com.bedwarsqol.gui.sheet.SheetPainter;
import com.bedwarsqol.gui.sheet.TextMetrics;
import com.bedwarsqol.rewind.RewindFormat;

import java.util.ArrayList;
import java.util.List;

import static com.bedwarsqol.gui.sheet.SheetColors.BAND;
import static com.bedwarsqol.gui.sheet.SheetColors.BLACK;
import static com.bedwarsqol.gui.sheet.SheetColors.DEPTH;
import static com.bedwarsqol.gui.sheet.SheetColors.EDGE;
import static com.bedwarsqol.gui.sheet.SheetColors.FACE;
import static com.bedwarsqol.gui.sheet.SheetColors.FACE_HOVER;
import static com.bedwarsqol.gui.sheet.SheetColors.GROOVE;
import static com.bedwarsqol.gui.sheet.SheetColors.GROUND;
import static com.bedwarsqol.gui.sheet.SheetColors.KNOB;
import static com.bedwarsqol.gui.sheet.SheetColors.KNOB_DEPTH;
import static com.bedwarsqol.gui.sheet.SheetColors.KNOB_EDGE;
import static com.bedwarsqol.gui.sheet.SheetColors.KNOB_HOVER;
import static com.bedwarsqol.gui.sheet.SheetColors.KNOB_LOWER;
import static com.bedwarsqol.gui.sheet.SheetColors.LOWER;
import static com.bedwarsqol.gui.sheet.SheetColors.PIT;
import static com.bedwarsqol.gui.sheet.SheetColors.RIM;
import static com.bedwarsqol.gui.sheet.SheetColors.SH;
import static com.bedwarsqol.gui.sheet.SheetColors.SH2;
import static com.bedwarsqol.gui.sheet.SheetColors.TEXT_HI;
import static com.bedwarsqol.gui.sheet.SheetColors.TEXT_LO;
import static com.bedwarsqol.gui.sheet.SheetColors.TEXT_MID;
import static com.bedwarsqol.gui.sheet.SheetColors.WELL_TOP;

/**
 * Draws Rewind's own screens in the Cobblify sheet style (G49, G51) on a {@link Canvas}, as the mockup
 * {@code .ai/mockups/rewind.html} paints them: the loading card, the viewer bar with its camera menu, the free
 * camera's speed bar, the Esc menu and the gallery. No text but the labels: titles, times, key labels.
 *
 * <p>World dimming behind the Esc menu and gallery, and the title-screen backdrop, are the screens' vanilla calls. The
 * one thing a canvas cannot draw is a skin: {@link #faces} says where the screen draws your face over the head frame.
 */
public final class RewindPainter {

    /** Delete's armed key (GuiTheme.Accent.RED). */
    public static final SheetColors.Shades RED = SheetColors.Shades.of(0xE5484D);
    /** A starred game's star. */
    public static final int STAR_ON = 0xFFFFAA00;
    /** Both bed icons: R1 markers carry no team colour (format gap 2). */
    public static final int BED = 0xFFFF5555;
    private static final int DEATH = 0xFFFF5555;
    private static final int BED_FRAME = 0xFF9C7246;
    private static final float LINE = 9f;

    private static final String[] ART_DEATH = {"##...##", "###.###", ".#####.", "..###..", ".#####.", "###.###", "##...##"};
    private static final String[] ART_FINAL = {".#####.", "#######", "#oo#oo#", "#oo#oo#", "###.###", ".#####.", ".#.#.#."};
    private static final String[] ART_BED = {".......", ".......", "wwbbbbb", "wwbbbbb", "ttttttt", "t.....t", "......."};
    private static final String[] ART_OWN_BED = {".......", "....b..", "ww.bbbb", "wwb.bbb", "ttt.ttt", "t.....t", "......."};
    private static final String[] ART_FLAG = {"#yyyy", "#yyyy", "#yyy.", "#....", "#....", "#....", "#...."};
    private static final String[] ART_PLAY = {"#....", "##...", "###..", "####.", "###..", "##...", "#...."};
    private static final String[] ART_PAUSE = {"##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##"};
    private static final String[] ART_STAR = {"....#....", "...###...", "...###...", "#########", ".#######.", "..#####..",
            "..#####..", ".###.###.", ".##...##."};
    private static final String[] ART_CAMERA = {"..###...", "########", "###..###", "##....##", "###..###", "########"};

    private RewindPainter() {
    }

    // ------------------------------------------------------------------ the loading card (G49)

    /** The loading card's {x, y, w, h}: as wide as the title + 40 su, at least 168. */
    public static float[] loadingCard(TextMetrics m, float screenW, float screenH, String label) {
        float w = Math.max(m.width(label, ViewerLayout.FONT) + 40, 168), h = 1 + 13 + 9 + 11 + 20 + 13 + 1;
        return new float[]{Math.round((screenW - w) / 2f), Math.round((screenH - h) / 2f), w, h};
    }

    /** The opaque loading screen: the card with mode · map and a key that fills by {@code progress} (0-1). */
    public static void loading(Canvas c, float screenW, float screenH, String label, float progress,
                               SheetColors.Shades accent) {
        c.fill(0, 0, screenW, screenH, WELL_TOP);
        float[] r = loadingCard(c, screenW, screenH, label);
        float x = r[0], y = r[1], w = r[2];
        card(c, x, y, w, r[3]);
        text(c, label, x + (w - c.width(label, ViewerLayout.FONT)) / 2f, y + 1 + 13, TEXT_HI);
        float kx = x + 14, ky = y + 1 + 13 + 9 + 11, kw = w - 28;
        raisedFace(c, kx, ky, kw, 20, false);
        float p = Math.max(0f, Math.min(1f, progress));
        if (p > 0) {
            c.pushClip(kx, ky, Math.round(kw * p), 20);
            raisedAccent(c, kx, ky, kw, 20, accent, 1f);
            c.popClip();
        }
    }

    // ------------------------------------------------------------------ the viewer bar (G36)

    /** What the bar shows this frame. */
    public static final class BarState {
        public boolean playing;
        /** The playhead from the game start (the drag preview while dragging). */
        public long timeUs;
        public float speed = 1f;
        /**
         * The camera option shown on the key: 0 your view, 1 free camera ({@link ViewerLayout#CAMERA_LABELS}),
         * {@link ViewerLayout#FIRST_PLAYER} + i the i-th player's view; as the layout's {@code camera()}.
         */
        public int camera;
        /** Each listed player's team colour (ARGB), in menu order; null or missing entries: white. */
        public int[] playerColours;
        public boolean menuOpen;
        public ViewerLayout.Hit hover;
        public ViewerLayout.Hit pressed;
        /** The mouse is over the playhead knob. */
        public boolean knobHover;
        public boolean dragging;
        /** The marker last jumped to ({@code MarkerNav}'s cursor), drawn highlighted; -1 for none. */
        public int cursorMarker = -1;
        /** The bar's fade ({@code BarFade}); nothing is drawn below 0.004. */
        public float alpha = 1f;
        public SheetColors.Shades accent = SheetColors.Shades.of(0xC0DCFF);
    }

    private static boolean is(ViewerLayout.Hit h, ViewerLayout.Part part, int index) {
        return h != null && h.part == part && h.index == index;
    }

    public static void bar(Canvas c, ViewerLayout l, BarState s) {
        if (s.alpha <= 0.004f) return;
        SheetColors.Shades acc = s.accent;
        c.pushAlpha(s.alpha);
        float bx = l.bar[0], by = l.bar[1], bw = l.bar[2], bh = l.bar[3];
        card(c, bx, by, bw, bh);

        // play / pause
        String[] icon = s.playing ? ART_PAUSE : ART_PLAY;
        boolean playHover = is(s.hover, ViewerLayout.Part.PLAY, -1);
        if (is(s.pressed, ViewerLayout.Part.PLAY, -1) && playHover) {
            pressedBox(c, l.play[0], by + 7, 16, 14, FACE_HOVER, SH, SH2);
            pix(c, icon, TEXT_HI, l.play[0] + 6, by + 7 + 3, false);
        } else {
            raisedFace(c, l.play[0], l.play[1], 16, 16, playHover);
            pix(c, icon, TEXT_HI, l.play[0] + 6, l.play[1] + 3, false); // 1 su above, 2 below, as a key's label
        }

        // camera key: the current camera, a chevron
        float camX = l.camera[0], camW = l.camera[2];
        boolean camHover = is(s.hover, ViewerLayout.Part.CAMERA, -1);
        boolean camDown = (is(s.pressed, ViewerLayout.Part.CAMERA, -1) && camHover) || s.menuOpen;
        float cy = camDown ? by + 8 : by + 7; // the head frame fills the raised face, under its top edge
        if (camDown) pressedBox(c, camX, by + 7, camW, 14, camHover ? FACE_HOVER : FACE, SH, SH2);
        else raisedFace(c, camX, by + 5, camW, 16, camHover);
        String keyLabel = s.camera == l.camera() ? l.cameraKeyLabel() // a long name cut to the key
                : s.camera >= 0 && s.camera < l.cameraOptionCount() ? l.cameraLabel(s.camera)
                : ViewerLayout.CAMERA_LABELS[0];
        cameraOption(c, s, s.camera, keyLabel, camX + 7, cy, 10, acc);
        chevron(c, camX + camW - 13, cy + 3, true, TEXT_HI);

        // speed key
        boolean spHover = is(s.hover, ViewerLayout.Part.SPEED, -1);
        button(c, l.speed[0], l.speed[1], l.speed[2], l.speed[3], ViewerLayout.speedLabel(s.speed), spHover,
                spHover && is(s.pressed, ViewerLayout.Part.SPEED, -1));

        // time: current / length
        text(c, l.timeLabel(s.timeUs), l.timeX(s.timeUs), l.timeTop(), TEXT_HI);
        text(c, l.totalLabel(), l.totalX(), l.timeTop(), TEXT_MID);

        // the timeline: recess, played part, markers, playhead
        float sx = l.stripX, sw = l.stripW, sy = l.stripY, sh = l.stripH;
        c.fill(sx - 1, sy - 1, sw + 2, sh + 2, RIM);
        c.fill(sx, sy, sw, sh, BLACK);
        c.fill(sx + 1, sy + 1, sw - 2, sh - 2, PIT);
        float xt = l.xOf(s.timeUs), played = xt - sx - 1;
        if (played > 0) {
            c.fill(sx + 1, sy + 1, played, sh - 2, acc.dp);
            c.fill(sx + 1, sy + 1, played, 1, acc.base);
        }
        for (int i = 0; i < l.markerCount(); i++) {
            float[] ic = l.markerIcon(i);
            if (i == s.cursorMarker) c.fill(ic[0] - 1, sy, ic[2] + 2, sh, acc.base);
            else if (is(s.hover, ViewerLayout.Part.MARKER, i)) c.fill(ic[0] - 1, sy, ic[2] + 2, sh, FACE_HOVER);
            markerIcon(c, l.markerKind(i), ic[0], ic[1]);
        }
        float[] k = l.knob(s.timeUs);
        raisedBox(c, k[0], k[1], k[2], k[3], s.knobHover || s.dragging ? KNOB_HOVER : KNOB, KNOB_EDGE, KNOB_EDGE,
                KNOB_LOWER, KNOB_LOWER, KNOB_DEPTH);

        // the camera menu, above its key: You, Free camera, then a row per player
        if (s.menuOpen) {
            float[] d = l.dropdown();
            card(c, d[0], d[1], d[2], d[3]);
            for (int i = 0; i < l.cameraOptionCount(); i++) {
                float[] r = l.dropdownRow(i);
                if (is(s.hover, ViewerLayout.Part.CAMERA_OPTION, i)) c.fill(r[0], r[1], r[2], r[3], FACE);
                if (i == s.camera) c.fill(r[0], r[1] + 2, 2, r[3] - 4, acc.base);
                cameraOption(c, s, i, l.cameraLabel(i), r[0] + 6, r[1] + 2, 10, acc);
            }
        }
        c.popAlpha();
    }

    /**
     * Where the screen draws faces (8 x 8, the skin's face and hat layers) over the head frames this bar drew, as
     * {x, y, 8, 8, option}: option 0 is your face, {@link ViewerLayout#FIRST_PLAYER} + i the i-th player's. On the
     * camera key when it shows your view or a player's, and on the menu's rows for you and every player when the menu
     * is open.
     */
    public static List<float[]> faces(ViewerLayout l, BarState s) {
        List<float[]> out = new ArrayList<float[]>();
        if (s.alpha <= 0.004f) return out;
        boolean camDown = (is(s.pressed, ViewerLayout.Part.CAMERA, -1) && is(s.hover, ViewerLayout.Part.CAMERA, -1))
                || s.menuOpen;
        if (s.camera != 1) {
            out.add(new float[]{l.camera[0] + 7 + 1, (camDown ? l.bar[1] + 8 : l.bar[1] + 7) + 1, 8, 8, s.camera});
        }
        if (s.menuOpen) {
            for (int i = 0; i < l.cameraOptionCount(); i++) {
                if (i == 1) continue;
                float[] r = l.dropdownRow(i);
                out.add(new float[]{r[0] + 6 + 1, r[1] + 2 + 1, 8, 8, i});
            }
        }
        return out;
    }

    /** Player option {@code option}'s team colour, white when unknown. */
    private static int playerColour(BarState s, int option) {
        int i = option - ViewerLayout.FIRST_PLAYER;
        return s.playerColours != null && i >= 0 && i < s.playerColours.length ? s.playerColours[i] : TEXT_HI;
    }

    /**
     * One camera option: a head frame (your view in the accent, a player in their team's colour) or the camera icon
     * (free camera), then its label (a player's name in their team's colour).
     */
    private static void cameraOption(Canvas c, BarState s, int option, String label, float x, float y, float h,
                                     SheetColors.Shades acc) {
        int colour = TEXT_HI;
        if (option == 1) {
            pix(c, ART_CAMERA, TEXT_HI, x + 1, y + (h - 6) / 2f, false);
        } else if (option >= ViewerLayout.FIRST_PLAYER) {
            colour = playerColour(s, option);
            c.fill(x, y + (h - 10) / 2f, 10, 10, colour);
        } else {
            c.fill(x, y + (h - 10) / 2f, 10, 10, acc.base);
        }
        text(c, label, x + ViewerLayout.CAMERA_ICON, y + (h - LINE) / 2f, colour);
    }

    /**
     * Another player's view (Phase 14): their name in their team's colour, centred at the top
     * ({@link ViewerLayout#riddenNameTop()}), whether or not the bar shows.
     */
    public static void riddenName(Canvas c, float screenW, String name, int colour) {
        if (name == null || name.isEmpty()) return;
        text(c, name, Math.round((screenW - c.width(name, ViewerLayout.FONT)) / 2f), ViewerLayout.riddenNameTop(),
                colour);
    }

    /** The free camera's speed bar (G43; mockup {@code drawFly}): 10 segments, {@code level} lit. */
    public static void flySpeed(Canvas c, float screenW, float screenH, int level, float alpha,
                                SheetColors.Shades accent) {
        if (alpha <= 0.004f) return;
        int n = 10;
        float segW = 8, w = n * (segW + 1) + 1, x = Math.round((screenW - w) / 2f), y = ViewerLayout.flySpeedTop();
        c.pushAlpha(alpha);
        c.fill(x - 1, y - 1, w + 2, 10, RIM);
        c.fill(x, y, w, 8, BLACK);
        c.fill(x + 1, y + 1, w - 2, 6, PIT);
        for (int i = 0; i < Math.max(0, Math.min(n, level)); i++) {
            c.fill(x + 1 + i * (segW + 1), y + 1, segW, 6, accent.base);
            c.fill(x + 1 + i * (segW + 1), y + 1, segW, 1, accent.hl);
        }
        c.popAlpha();
    }

    // ------------------------------------------------------------------ the Esc menu (G40)

    public static void escMenu(Canvas c, EscMenuModel.Layout l, EscMenuModel.Key hover, EscMenuModel.Key pressed,
                               SheetColors.Shades accent) {
        float[] card = l.card();
        card(c, card[0], card[1], card[2], card[3]);
        for (EscMenuModel.Key k : EscMenuModel.Key.values()) {
            float[] r = l.key(k);
            boolean h = k == hover, d = h && k == pressed;
            if (EscMenuModel.hero(k)) {
                SheetPainter.hero(c, r[0], r[1], r[2], r[3], EscMenuModel.label(k), ViewerLayout.FONT, accent, h, d);
            } else {
                button(c, r[0], r[1], r[2], r[3], EscMenuModel.label(k), h, d);
            }
        }
    }

    // ------------------------------------------------------------------ the gallery (G39, G46)

    public static void gallery(Canvas c, GalleryModel m, GalleryModel.Layout l, GalleryModel.Hit hover,
                               SheetColors.Shades accent) {
        c.fill(l.x, l.y, l.w, l.h, BLACK);
        c.fill(l.x + 1, l.y + 1, l.w - 2, l.h - 2, GROUND);
        List<GalleryModel.Entry> entries = m.entries();
        for (int slot = 0; slot < l.rows; slot++) {
            int index = l.first + slot;
            GalleryModel.Entry e = entries.get(index);
            float[] r = l.row(slot);
            float rx = r[0], ry = r[1], rw = r[2];
            if (slot > 0) divider(c, rx, ry - 1, rw);
            float[] st = l.star(slot);
            boolean starHover = hover != null && hover.part == GalleryModel.Hit.Part.STAR && hover.row == index;
            pix(c, ART_STAR, e.starred ? STAR_ON : starHover ? TEXT_LO : FACE, st[0] + 2, st[1] + 2, e.starred);
            float top = l.labelTop(slot);
            c.pushClip(l.titleX, ry, l.titleW, GalleryModel.ROW_H);
            text(c, e.title, l.titleX, top, TEXT_HI);
            c.popClip();
            if (e.result != null) text(c, e.result, l.resultX, top, ModeLabels.resultColour(e.result));
            text(c, e.when, l.dateX, top, TEXT_MID);
            text(c, e.length, l.lengthRight - c.width(e.length, ViewerLayout.FONT), top, TEXT_MID);
            float[] mt = l.miniTimeline(slot);
            float mx = mt[0], my = mt[1], mw = mt[2];
            c.fill(mx, my, mw, 3, BLACK);
            c.fill(mx + 1, my + 1, mw - 2, 1, RIM);
            for (GalleryModel.Mark mk : e.marks) {
                float w = ViewerLayout.iconWidth(mk.kind);
                markerIcon(c, mk.kind, Math.round(mx + 1 + (mw - 2) * mk.at - w / 2f), my - 2);
            }
            if (index == m.selected()) ring(c, rx, ry, rw, GalleryModel.ROW_H, TEXT_HI);
        }
        float fy = l.y + 1 + l.bodyH();
        c.fill(l.x + 1, fy, l.w - 2, GalleryModel.FOOT_H, BAND);
        c.fill(l.x + 1, fy, l.w - 2, 1, BLACK);
        boolean none = entries.isEmpty();
        GalleryModel.Hit p = m.pressed();
        boolean wh = !none && part(hover, GalleryModel.Hit.Part.WATCH);
        if (none) c.beginDisabled(BAND);
        SheetPainter.hero(c, l.watch[0], l.watch[1], l.watch[2], l.watch[3], "Watch", ViewerLayout.FONT, accent, wh,
                wh && part(p, GalleryModel.Hit.Part.WATCH));
        if (none) c.endDisabled();
        boolean dh = !none && part(hover, GalleryModel.Hit.Part.DELETE);
        boolean dd = dh && part(p, GalleryModel.Hit.Part.DELETE);
        if (m.armed()) {
            SheetPainter.hero(c, l.delete[0], l.delete[1], l.delete[2], l.delete[3], "Delete", ViewerLayout.FONT, RED,
                    dh, dd);
        } else {
            if (none) c.beginDisabled(BAND);
            button(c, l.delete[0], l.delete[1], l.delete[2], l.delete[3], "Delete", dh, dd);
            if (none) c.endDisabled();
        }
        boolean oh = part(hover, GalleryModel.Hit.Part.DONE);
        button(c, l.done[0], l.done[1], l.done[2], l.done[3], "Done", oh, oh && part(p, GalleryModel.Hit.Part.DONE));
    }

    private static boolean part(GalleryModel.Hit h, GalleryModel.Hit.Part part) {
        return h != null && h.part == part;
    }

    // ------------------------------------------------------------------ marker icons

    /** A marker's 7 x 7 (key: 5 x 7) pixel-art icon at (x, y), with Minecraft's shadow one pixel down-right. */
    public static void markerIcon(Canvas c, int kind, float x, float y) {
        switch (kind) {
            case RewindFormat.MARKER_DEATH:
                pix(c, ART_DEATH, new char[]{'#'}, new int[]{DEATH}, x, y, true);
                break;
            case RewindFormat.MARKER_FINAL_KILL:
                pix(c, ART_FINAL, new char[]{'#', 'o'}, new int[]{0xFFFFFFFF, 0xFF2A2A2A}, x, y, true);
                break;
            case RewindFormat.MARKER_BED_BROKEN:
                pix(c, ART_BED, new char[]{'w', 'b', 't'}, new int[]{0xFFFFFFFF, BED, BED_FRAME}, x, y, true);
                break;
            case RewindFormat.MARKER_BED_LOST:
                pix(c, ART_OWN_BED, new char[]{'w', 'b', 't'}, new int[]{0xFFFFFFFF, BED, BED_FRAME}, x, y, true);
                break;
            case RewindFormat.MARKER_KEY:
                pix(c, ART_FLAG, new char[]{'#', 'y'}, new int[]{0xFFC6C6C6, 0xFFFFFF55}, x, y, true);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ sheet pieces (as SheetPainter draws them)

    private static void text(Canvas c, String s, float x, float lineTop, int colour) {
        if (s == null || s.isEmpty()) return;
        c.text(s, x, lineTop, LINE, ViewerLayout.FONT, colour);
    }

    private static void textCentred(Canvas c, String s, float x, float top, float w, float h, int colour) {
        text(c, s, x + (w - c.width(s, ViewerLayout.FONT)) / 2f, top + (h - LINE) / 2f, colour);
    }

    /** The toast's surface: black ring, band fill, a groove line along the top. */
    private static void card(Canvas c, float x, float y, float w, float h) {
        c.fill(x, y, w, h, BLACK);
        c.fill(x + 1, y + 1, w - 2, h - 2, BAND);
        c.fill(x + 1, y + 1, w - 2, 1, GROOVE);
    }

    private static void divider(Canvas c, float x, float y, float w) {
        c.fill(x, y, w, 1, BLACK);
        c.fill(x, y + 1, w, 1, GROOVE);
    }

    private static void ring(Canvas c, float x, float y, float w, float h, int colour) {
        c.fill(x, y, w, 1, colour);
        c.fill(x, y + h - 1, w, 1, colour);
        c.fill(x, y + 1, 1, h - 2, colour);
        c.fill(x + w - 1, y + 1, 1, h - 2, colour);
    }

    private static void raisedBox(Canvas c, float x, float y, float w, float h, int face, int top, int left,
                                  int right, int lower, int depth) {
        ring(c, x, y, w, h, BLACK);
        c.fill(x + 1, y + 1, w - 2, 1, top);
        c.fill(x + 1, y + 2, 1, h - 6, left);
        c.fill(x + 2, y + 2, w - 4, h - 6, face);
        c.fill(x + w - 2, y + 2, 1, h - 6, right);
        c.fill(x + 1, y + h - 4, w - 2, 1, lower);
        c.fill(x + 1, y + h - 3, w - 2, 2, depth);
    }

    private static void pressedBox(Canvas c, float x, float y, float w, float h, int face, int sh, int sh2) {
        ring(c, x, y, w, h, BLACK);
        c.fill(x + 1, y + 1, w - 2, 1, sh);
        c.fill(x + 1, y + 2, w - 2, 1, sh2);
        c.fill(x + 1, y + 3, 1, h - 4, sh2);
        c.fill(x + 2, y + 3, w - 3, h - 4, face);
    }

    private static void raisedFace(Canvas c, float x, float y, float w, float h, boolean hover) {
        raisedBox(c, x, y, w, h, hover ? FACE_HOVER : FACE, EDGE, EDGE, LOWER, LOWER, DEPTH);
    }

    private static void raisedAccent(Canvas c, float x, float y, float w, float h, SheetColors.Shades a, float k) {
        raisedBox(c, x, y, w, h, bright(a.base, k), bright(a.hl, k), bright(a.hl, k), bright(a.ln, k),
                bright(a.ln, k), bright(a.dp, k));
    }

    private static int bright(int argb, float k) {
        return k == 1f ? argb : SheetColors.brightness(argb, k);
    }

    /** A sheet key: raised, sunk 2 su while held. */
    private static void button(Canvas c, float x, float y, float w, float h, String label, boolean hover,
                               boolean down) {
        if (down) {
            pressedBox(c, x, y + 2, w, h - 2, hover ? FACE_HOVER : FACE, SH, SH2);
            textCentred(c, label, x, y + 3, w, h - 4, TEXT_HI);
        } else {
            raisedFace(c, x, y, w, h, hover);
            textCentred(c, label, x, y + 1, w, h - 4, TEXT_HI);
        }
    }

    /** The sheet's 6 su chevron; up = mirrored. */
    private static void chevron(Canvas c, float x, float y, boolean up, int colour) {
        for (int i = 0; i < 6; i++) {
            int step = Math.min(i, 5 - i);
            c.fill(x + i, y + (up ? 3 - step : 1 + step), 1, 1, colour);
        }
    }

    private static void pix(Canvas c, String[] rows, int colour, float x, float y, boolean shadow) {
        pix(c, rows, new char[]{'#'}, new int[]{colour}, x, y, shadow);
    }

    /** Pixel art: one 1 su fill per lit pixel; with {@code shadow}, a quarter-brightness copy one down-right first. */
    private static void pix(Canvas c, String[] rows, char[] keys, int[] colours, float x, float y, boolean shadow) {
        for (int pass = shadow ? 0 : 1; pass < 2; pass++) {
            float d = pass == 0 ? 1 : 0;
            for (int r = 0; r < rows.length; r++) {
                for (int col = 0; col < rows[r].length(); col++) {
                    char ch = rows[r].charAt(col);
                    for (int k = 0; k < keys.length; k++) {
                        if (keys[k] == ch) {
                            int colour = pass == 0 ? SheetColors.textShadow(colours[k]) : colours[k];
                            c.fill(x + col + d, y + r + d, 1, 1, colour);
                        }
                    }
                }
            }
        }
    }
}
