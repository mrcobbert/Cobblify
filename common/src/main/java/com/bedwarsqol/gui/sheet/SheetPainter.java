package com.bedwarsqol.gui.sheet;

import com.bedwarsqol.gui.sheet.SheetLayout.Frame;
import com.bedwarsqol.gui.sheet.SheetLayout.Node;
import com.bedwarsqol.gui.sheet.SheetLayout.T;

import static com.bedwarsqol.gui.sheet.SheetColors.*;

/**
 * Draws a laid-out sheet on a {@link Canvas}, colour for colour and line for line as the prototype's CSS paints it
 * (box-shadow lists paint their last entry first; every bevel line is a 1 su rect inside a 1 su black border).
 */
public final class SheetPainter {

    /** Per-frame inputs that are not layout: accent, pointer, focus, editing and motion. */
    public static final class Paint {
        public SheetColors.Shades accent = SheetColors.Shades.of(0xF0770F);
        public Node hover;
        public Node pressed;
        public Node focus;
        public boolean searchFocused;
        public boolean caretOn;
        /** The value field being typed in, and its text. */
        public String editing;
        public String editText = "";
        /** Drawn scroll in su (already on whole device px). */
        public float scroll;
        public SheetValues values;
        public SheetMotion motion;
        public long now;
    }

    /** Minecraft's text-field caret: an underscore 1 su after the text, 5 su wide and 1 su thick, under the baseline. */
    private static final float CARET_GAP = 1f;
    private static final float CARET_W = 5f;

    private SheetPainter() {
    }

    public static void paint(Canvas c, Frame f, Paint p) {
        float h = f.height;
        c.fill(0f, 0f, SheetLayout.WIDTH, h, GROUND);
        // header and category bands
        c.fill(1f, 0f, SheetLayout.WIDTH - 1f, SheetLayout.HEADER_H, BAND);
        c.fill(1f, SheetLayout.HEADER_H, SheetLayout.WIDTH - 1f, SheetLayout.NAV_H, BAND);
        c.fill(1f, SheetLayout.BODY_TOP - 1f, SheetLayout.WIDTH - 1f, 1f, BLACK);
        // body
        c.pushClip(1f, f.bodyTop, SheetLayout.WIDTH - 1f, f.bodyHeight);
        for (Node n : f.body) body(c, n, f.bodyTop - p.scroll, p);
        scrollbar(c, f, p);
        c.popClip();
        // footer band
        float fy = f.footerTop;
        c.fill(1f, fy, SheetLayout.WIDTH - 1f, h - fy, BAND);
        c.fill(1f, fy, SheetLayout.WIDTH - 1f, 1f, BLACK);
        for (Node n : f.chrome) chrome(c, n, p);
        // the bevelled left edge goes last: the bands, their black lines and the dividers all start 1 su in
        c.fill(0f, 0f, 1f, h, BLACK);
        c.fill(1f, 0f, 1f, h, EDGE_LIT);
        c.fill(2f, 0f, 1f, h, EDGE_STEP);
    }

    // ------------------------------------------------------------------ chrome

    private static void chrome(Canvas c, Node n, Paint p) {
        switch (n.type) {
            case BRAND:
                // the same pixel art in both fonts, in each font's white
                SheetWordmark.paint(c, n.x, n.y + n.h / 2f, c.minecraftFont() ? SheetColors.minecraftText(TEXT_HI) : TEXT_HI);
                break;
            case VERSION:
                c.text(n.text, n.x, n.lineTop, n.lineHeight, n.font, TEXT_LO);
                break;
            case SEARCH:
                search(c, n, p);
                break;
            case CLOSE: {
                // Ore UI's close button: a key holding a bold pixel cross, lighter when hovered, sunk while pressed
                // (the cross sinks with the face, as a label does)
                boolean hover = n == p.hover;
                float cy;
                if (n == p.pressed) {
                    pressedBox(c, n.x, n.y + 2f, n.w, n.h - 2f, hover ? FACE_HOVER : FACE, SH, SH2);
                    cy = n.y + 5f;
                } else {
                    raisedBox(c, n.x, n.y, n.w, n.h, hover ? FACE_HOVER : FACE, EDGE, EDGE, LOWER, LOWER, DEPTH);
                    cy = n.y + 3f;
                }
                for (int i = 0; i < 6; i++) {
                    c.fill(n.x + 4f + i, cy + i, 2f, 2f, TEXT_HI);
                    c.fill(n.x + 9f - i, cy + i, 2f, 2f, TEXT_HI);
                }
                if (n == p.focus) outline(c, n.x, n.y, n.w, n.h, 1f);
                break;
            }
            case CATEGORY: {
                boolean hover = n == p.hover;
                int textCol = hover || n.on ? TEXT_HI : TEXT_MID;
                float tw = c.width(n.text, n.font);
                c.text(n.text, n.x + 1f + (n.w - 2f - tw) / 2f, n.lineTop, n.lineHeight, n.font, textCol);
                // the key under the label, drawn as an option is: raised, pressed while held, pressed in the accent
                // when it is the open category
                float ky = n.y + 13f, kh = SheetLayout.CATEGORY_KEY_H;
                SheetColors.Shades a = p.accent;
                if (n.on || n == p.pressed) {
                    int face = n.on ? a.base : hover ? FACE_HOVER : FACE;
                    if (n.on && hover) face = SheetColors.brightness(face, 1.06f);
                    pressedBox(c, n.x, ky + 2f, n.w, kh - 2f, face, n.on ? a.sh : SH, n.on ? a.sh2 : SH2);
                } else {
                    raisedBox(c, n.x, ky, n.w, kh, hover ? FACE_HOVER : FACE, EDGE, EDGE, LOWER, LOWER, DEPTH);
                }
                if (n == p.focus) outline(c, n.x, n.y, n.w, n.h, 1f);
                break;
            }
            case HERO:
                hero(c, n, p);
                break;
            default:
                break;
        }
    }

    private static void search(Canvas c, Node n, Paint p) {
        float x = n.x, y = n.y, w = n.w, h = n.h;
        // a recess lit from the top left: the light lip below it, the top and left walls in shadow, the bottom and
        // right walls catching the light
        c.fill(x, y + h, w, 1f, GROOVE);
        c.fill(x, y, w, h, BLACK);
        c.fill(x + 1f, y + 1f, w - 2f, h - 2f, PIT);
        c.fill(x + 1f, y + 1f, w - 2f, 1f, BLACK);
        c.fill(x + 1f, y + 2f, w - 2f, 1f, RECESS_SHADE);
        c.fill(x + 1f, y + 2f, 1f, h - 3f, RECESS_SHADE);
        c.fill(x + 2f, y + h - 2f, w - 3f, 1f, RECESS_LIT);
        c.fill(x + w - 2f, y + 3f, 1f, h - 5f, RECESS_LIT);
        // magnifier: viewBox 7, circle (2.9, 2.9) r 2.2 and a line (4.5, 4.5) -> (6.6, 6.6), stroke 1.2
        float ix = x + 1f + 4f, iy = y + 1f + (h - 2f - 7f) / 2f;
        c.ring(ix + 2.9f, iy + 2.9f, 2.2f, 1.2f, TEXT_LO);
        c.stroke(new float[]{ix + 4.5f, iy + 4.5f, ix + 6.6f, iy + 6.6f}, 1.2f, TEXT_LO);
        float tx = ix + 7f + 4f;
        float room = x + w - 1f - 4f - tx;
        c.pushClip(tx, y + 1f, room, h - 2f);
        // a query longer than the field scrolls left, keeping its end and the caret in view
        float tw = c.width(n.text, n.font);
        float sx = tx - Math.max(0f, tw + caretWidth(c, n.font) - room);
        // the hint shows only while the field is idle; focused, the caret alone marks it, as in a vanilla text field
        if (n.text.isEmpty() && !p.searchFocused) c.text("Search", tx, n.lineTop, n.lineHeight, n.font, TEXT_LO);
        else c.text(n.text, sx, n.lineTop, n.lineHeight, n.font, TEXT_HI);
        if (p.searchFocused && p.caretOn) caret(c, sx + tw, n.font, n.lineTop, n.lineHeight, p.accent.base);
        c.popClip();
    }

    /**
     * The blinking underscore vanilla Minecraft draws at the end of a text field, after the text at {@code x}; where
     * the text is Minecraft's font it is the font's own underscore, as the game draws it.
     */
    private static void caret(Canvas c, float x, SheetFont font, float lineTop, float lineHeight, int colour) {
        if (c.pixel(font) > 0f) c.text("_", x, lineTop, lineHeight, font, colour);
        else c.fill(x + CARET_GAP, c.baseline(font, lineTop, lineHeight), CARET_W, 1f, colour);
    }

    private static float caretWidth(Canvas c, SheetFont font) {
        return c.pixel(font) > 0f ? c.width("_", font) : CARET_GAP + CARET_W;
    }

    private static void hero(Canvas c, Node n, Paint p) {
        hero(c, n.x, n.y, n.w, n.h, n.text, n.font, p.accent, n == p.hover, n == p.pressed);
        if (n == p.focus) outline(c, n.x, n.y, n.w, n.h, 1f);
    }

    /** The accent button (Edit HUD in the footer; Reset All in Edit HUD): raised, or sunk 2 su while pressed. */
    public static void hero(Canvas c, float x, float y, float w, float h, String text, SheetFont font,
                            SheetColors.Shades a, boolean hover, boolean down) {
        float k = hover ? 1.06f : 1f;
        if (down) {
            float py = y + 2f, ph = h - 2f;
            pressedBox(c, x, py, w, ph, bright(a.base, k), bright(a.sh, k), bright(a.sh2, k));
            // the label sinks with the face, so it sits where it did when raised (the prototype dropped it 1 su
            // further, which put descenders on the bottom border)
            textCentred(c, text, font, x, py + 1f, w, h - 2f - 2f, bright(a.on, k));
        } else {
            raisedBox(c, x, y, w, h, bright(a.base, k), bright(a.hl, k), bright(a.hl, k), bright(a.ln, k),
                    bright(a.ln, k), bright(a.dp, k));
            textCentred(c, text, font, x, y + 1f, w, h - 2f - 2f, bright(a.on, k));
        }
    }

    private static int bright(int argb, float k) {
        return k == 1f ? argb : SheetColors.brightness(argb, k);
    }

    // ------------------------------------------------------------------ body

    private static void body(Canvas c, Node n, float dy, Paint p) {
        float y = n.y + dy;
        switch (n.type) {
            case RESULTS:
            case PATH:
                c.text(n.text, n.x, n.lineTop + dy, n.lineHeight, n.font, n.color);
                break;
            case DIVIDER:
                c.fill(n.x, y, n.w, 1f, BLACK);
                c.fill(n.x, y + 1f, n.w, 1f, GROOVE);
                break;
            case OPEN:
                if (n.open) {
                    int col = n == p.hover ? TEXT_HI : TEXT_MID;
                    // a pixel chevron, 6 px long with 1.5 px strokes, beside the name: right while the module is
                    // closed, down while it is open. Its pixels are 1 su, or in Minecraft's font the font's own,
                    // centred where the 1 su one sits and on the name's capitals, in the font's colours
                    float q = 1f, ix = n.x + 9.5f - CHEVRON / 2f, iy = y + 7f + 6.5f - CHEVRON / 2f;
                    if (c.minecraftFont()) {
                        col = SheetColors.minecraftText(col);
                        q = c.pixel(SheetFont.NAME);
                        ix = n.x + 9.5f - CHEVRON / 2f * q;
                        // level with the capitals, on the font's pixel grid
                        iy = c.baseline(SheetFont.NAME, n.lineTop + dy, n.lineHeight) - 7f * q + (7 - CHEVRON) / 2 * q;
                    }
                    // in Minecraft's font it is shadowed as the text beside it is
                    if (c.minecraftFont()) chevron(c, ix + q, iy + q, q, n.on, SheetColors.textShadow(col));
                    chevron(c, ix, iy, q, n.on, col);
                    if (n == p.focus) outline(c, n.x, y, n.w, n.h, -1f);
                }
                break;
            case NAME:
            case DESC:
            case LABEL:
            case ROW_DESC:
                marked(c, n, dy, n.color);
                break;
            case MODULE_TOGGLE:
                toggle(c, n, y, false, GROUND, p);
                break;
            case TOGGLE:
                toggle(c, n, y, n.small, n.small ? WELL : GROUND, p);
                break;
            case WELL:
                c.fill(n.x, y + 1f, n.w, n.h, GROOVE);
                c.fill(n.x, y, n.w, n.h, BLACK);
                c.fill(n.x + 1f, y + 1f, n.w - 2f, n.h - 2f, WELL);
                c.fill(n.x + 1f, y + 1f, n.w - 2f, 1f, WELL_TOP);
                break;
            case VALUE:
            case KEY:
                field(c, n, y, p);
                break;
            case BUTTON:
                button(c, n, y, p);
                break;
            case OPTION:
                option(c, n, y, p);
                break;
            case SWATCH:
                swatch(c, n, y, p);
                break;
            case SLIDER:
                slider(c, n, y, p);
                break;
            case MORE: {
                int col = n == p.hover ? TEXT_HI : n.color;
                float lt = n.lineTop + dy;
                c.text(n.text, n.x, lt, n.lineHeight, n.font, col);
                float base = c.baseline(n.font, lt, n.lineHeight);
                if (c.minecraftFont()) {
                    // Minecraft's underline: a font pixel under the descenders, shadowed as the text is
                    float px = c.pixel(n.font);
                    c.fill(n.x + px, base + 2f * px, n.w, px, SheetColors.textShadow(col));
                    c.fill(n.x, base + px, n.w, px, col);
                } else {
                    float thick = Math.max(1f, Math.round(140f / 2048f * n.font.size * c.unit())) / c.unit();
                    c.fill(n.x, base + 2f, n.w, thick, col);
                }
                if (n == p.focus) outline(c, n.x, y, n.w, n.h, 1f);
                break;
            }
            default:
                break;
        }
    }

    /** The chevron's length in pixels: one short of Minecraft's 7 px {@code >}, its tip two pixels wide. */
    private static final int CHEVRON = 6;

    /**
     * The chevron in pixels of {@code q} su, its strokes stepping out to the tip and back, 1.5 px thick rounded to
     * whole device pixels.
     */
    private static void chevron(Canvas c, float x, float y, float q, boolean down, int col) {
        float t = Math.max(1f, Math.round(1.5f * q * c.unit())) / c.unit();
        for (int i = 0; i < CHEVRON; i++) {
            int step = Math.min(i, CHEVRON - 1 - i);
            if (down) c.fill(x + q * i, y + q * (1 + step), q, t, col);
            else c.fill(x + q * (1 + step), y + q * i, t, q, col);
        }
    }

    /** Text with the search match on a face-coloured background (CSS {@code mark}, padding 0 0.5). */
    private static void marked(Canvas c, Node n, float dy, int color) {
        float lt = n.lineTop + dy;
        int at = SheetSearch.markAt(n.text, n.mark);
        if (at < 0) {
            c.text(n.text, n.x, lt, n.lineHeight, n.font, color);
            return;
        }
        int end = at + n.mark.trim().length();
        String pre = n.text.substring(0, at), mid = n.text.substring(at, end), post = n.text.substring(end);
        float wp = c.width(pre, n.font), wm = c.width(mid, n.font);
        float[] ca = c.contentArea(n.font, lt, n.lineHeight);
        c.fill(n.x + wp, ca[0], wm + 1f, ca[1] - ca[0], FACE);
        c.text(pre, n.x, lt, n.lineHeight, n.font, color);
        c.text(mid, n.x + wp + 0.5f, lt, n.lineHeight, n.font, TEXT_HI);
        c.text(post, n.x + wp + 1f + wm, lt, n.lineHeight, n.font, color);
    }

    // ------------------------------------------------------------------ controls

    /**
     * A raised face: border, face, then (last box-shadow first) right, left, top, the lower band and the depth. Each
     * pixel is filled once, with the layer the browser shows on top, so the face also fades cleanly under an alpha.
     */
    static void raisedBox(Canvas c, float x, float y, float w, float h, int face, int top, int left, int right,
                          int lower, int depth) {
        ring(c, x, y, w, h, BLACK);
        c.fill(x + 1f, y + 1f, w - 2f, 1f, top);
        c.fill(x + 1f, y + 2f, 1f, h - 6f, left);
        c.fill(x + 2f, y + 2f, w - 4f, h - 6f, face);
        c.fill(x + w - 2f, y + 2f, 1f, h - 6f, right);
        c.fill(x + 1f, y + h - 4f, w - 2f, 1f, lower);
        c.fill(x + 1f, y + h - 3f, w - 2f, 2f, depth);
    }

    /**
     * A pressed face (already sunk): border, face, the left shadow, the 2 su top shadow, then its dark first line;
     * each pixel filled once, as {@link #raisedBox} is.
     */
    static void pressedBox(Canvas c, float x, float y, float w, float h, int face, int sh, int sh2) {
        ring(c, x, y, w, h, BLACK);
        c.fill(x + 1f, y + 1f, w - 2f, 1f, sh);
        c.fill(x + 1f, y + 2f, w - 2f, 1f, sh2);
        c.fill(x + 1f, y + 3f, 1f, h - 4f, sh2);
        c.fill(x + 2f, y + 3f, w - 3f, h - 4f, face);
    }

    /** Centre one line of text (line-height 1) in a content box. */
    private static void textCentred(Canvas c, String s, SheetFont f, float x, float top, float w, float h, int color) {
        float tw = c.width(s, f);
        c.text(s, x + (w - tw) / 2f, top + (h - f.size) / 2f, f.size, f, color);
    }

    private static void button(Canvas c, Node n, float y, Paint p) {
        if (n.disabled) c.beginDisabled(backdrop(n));
        boolean down = !n.disabled && n == p.pressed;
        boolean hover = !n.disabled && n == p.hover;
        if (down) {
            pressedBox(c, n.x, y + 2f, n.w, n.h - 2f, hover ? FACE_HOVER : FACE, SH, SH2);
            textCentred(c, n.text, n.font, n.x, y + 2f + 1f, n.w, n.h - 2f - 2f, TEXT_HI); // sunk with the face
        } else {
            raisedBox(c, n.x, y, n.w, n.h, hover ? FACE_HOVER : FACE, EDGE, EDGE, LOWER, LOWER, DEPTH);
            textCentred(c, n.text, n.font, n.x, y + 1f, n.w, n.h - 2f - 2f, TEXT_HI);
        }
        if (n.disabled) c.endDisabled();
        if (n == p.focus) outline(c, n.x, y, n.w, n.h, 1f);
    }

    private static void option(Canvas c, Node n, float y, Paint p) {
        if (n.disabled) c.beginDisabled(backdrop(n));
        boolean down = n.chosen || (!n.disabled && n == p.pressed);
        boolean hover = !n.disabled && n == p.hover;
        SheetColors.Shades a = p.accent;
        if (down) {
            int face = n.chosen ? a.base : hover ? FACE_HOVER : FACE;
            if (n.chosen && hover) face = SheetColors.brightness(face, 1.06f);
            int sh = n.chosen ? a.sh : SH, sh2 = n.chosen ? a.sh2 : SH2;
            pressedBox(c, n.x, y + 2f, n.w, n.h - 2f, face, sh, sh2);
            int col = n.chosen ? a.on : hover ? TEXT_HI : TEXT_MID;
            textCentred(c, n.text, n.font, n.x, y + 2f + 1f, n.w, n.h - 2f - 2f, col); // sunk with the face
        } else {
            raisedBox(c, n.x, y, n.w, n.h, hover ? FACE_HOVER : FACE, EDGE, EDGE, LOWER, LOWER, DEPTH);
            textCentred(c, n.text, n.font, n.x, y + 1f, n.w, n.h - 2f - 2f, hover ? TEXT_HI : TEXT_MID);
        }
        if (n.disabled) c.endDisabled();
        if (n == p.focus) outline(c, n.x, y, n.w, n.h, 1f);
    }

    /** The flat colour under a control: the well's, or the sheet's on the Settings page and module rows. */
    private static int backdrop(Node n) {
        return n.well ? WELL : GROUND;
    }

    private static void toggle(Canvas c, Node n, float y, boolean small, int backdrop, Paint p) {
        if (n.disabled) c.beginDisabled(backdrop);
        float x = n.x, w = n.w, h = n.h;
        float t = p.motion == null ? (n.on ? 1f : 0f) : p.motion.toggle(n.id, n.on, p.now);
        float fade = p.motion == null ? t : p.motion.toggleFade(n.id, n.on, p.now);
        SheetColors.Shades a = p.accent;
        c.fill(x - 1f, y - 1f, w + 2f, h + 2f, RIM);
        c.fill(x, y, w, h, BLACK);
        c.fill(x + 1f, y + 1f, w - 2f, h - 2f, PIT);
        float fw = small ? 10f : 13f;
        if (fade > 0f) {
            c.pushAlpha(fade);
            c.fill(x + 1f, y + 1f, fw, h - 2f, a.base);
            c.fill(x + 1f, y + h - 2f, fw, 1f, a.ln);
            c.fill(x + 1f, y + 1f, fw, 1f, a.hl);
            if (small) c.fill(x + 1f + 4.5f, y + 1f + 2.5f, 1f, 5f, a.on);
            else c.fill(x + 1f + 6f, y + 1f + 3f, 1f, 6f, a.on);
            c.popAlpha();
        }
        if (fade < 1f) {
            c.pushAlpha(1f - fade);
            float ow = small ? 4f : 5f, oh = small ? 5f : 6f, inset = small ? 2.5f : 3f;
            float ox = x + w - 1f - inset - ow, oy = y + 1f + inset;
            c.fill(ox, oy, ow, 1f, TEXT_LO);
            c.fill(ox, oy + oh - 1f, ow, 1f, TEXT_LO);
            c.fill(ox, oy + 1f, 1f, oh - 2f, TEXT_LO);
            c.fill(ox + ow - 1f, oy + 1f, 1f, oh - 2f, TEXT_LO);
            c.popAlpha();
        }
        float kw = small ? 12f : 14f, kh = small ? 16f : 18f, travel = small ? 11f : 13f;
        float kx = x + 1f - 1f + travel * t;
        boolean hover = !n.disabled && n == p.hover;
        raisedBox(c, kx, y - 1f, kw, kh, hover ? KNOB_HOVER : KNOB, KNOB_EDGE, KNOB_EDGE, KNOB_LOWER, KNOB_LOWER, KNOB_DEPTH);
        if (n.disabled) c.endDisabled();
        if (n == p.focus) outline(c, x, y, w, h, 1f);
    }

    private static void field(Canvas c, Node n, float y, Paint p) {
        if (n.disabled) c.beginDisabled(backdrop(n));
        float x = n.x, w = n.w, h = n.h;
        c.fill(x - 1f, y - 1f, w + 2f, h + 2f, RIM);
        c.fill(x, y, w, h, BLACK);
        c.fill(x + 1f, y + 1f, w - 2f, h - 2f, PIT);
        c.fill(x + 1f, y + 1f, w - 2f, 1f, BLACK);
        float lt = y + 1f + (h - 2f - n.font.size) / 2f;
        boolean editing = n.type == T.VALUE && n.id.equals(p.editing);
        String s = editing ? p.editText : n.text;
        float tw = c.width(s, n.font);
        // while typing, the right-aligned value makes room for the caret after it
        float caretRoom = editing ? caretWidth(c, n.font) : 0f;
        float tx = n.type == T.VALUE ? x + w - 1f - 4f - tw - caretRoom : x + (w - tw) / 2f;
        c.text(s, tx, lt, n.font.size, n.font, TEXT_HI);
        if (editing && p.caretOn) caret(c, tx + tw, n.font, lt, n.font.size, p.accent.base);
        if (n.disabled) c.endDisabled();
        if (n.on || editing || n == p.focus) outline(c, x, y, w, h, 1f);
    }

    private static void swatch(Canvas c, Node n, float y, Paint p) {
        if (n.disabled) c.beginDisabled(backdrop(n));
        boolean hover = !n.disabled && n == p.hover;
        float k = hover ? 1.08f : 1f;
        int col = bright(n.color, k);
        if (n.chosen) {
            float sy = y + 2f, sh = n.h - 2f;
            pressedBox(c, n.x, sy, n.w, sh, col, bright(SheetColors.pressSh(n.color), k),
                    bright(SheetColors.pressSh2(n.color), k));
        } else {
            c.fill(n.x, y, n.w, n.h, BLACK);
            c.fill(n.x + 1f, y + 1f, n.w - 2f, n.h - 2f, col);
            c.fill(n.x + 1f, y + 1f, 1f, n.h - 2f, 0x4DFFFFFF);
            c.fill(n.x + 1f, y + 1f, n.w - 2f, 1f, 0x73FFFFFF);
            c.fill(n.x + 1f, y + n.h - 3f, n.w - 2f, 2f, 0x73000000);
        }
        if (n.disabled) c.endDisabled();
        if (n == p.focus) outline(c, n.x, y, n.w, n.h, 1f);
    }

    private static void slider(Canvas c, Node n, float y, Paint p) {
        if (n.disabled) c.beginDisabled(backdrop(n));
        SheetSchema.Row r = SheetSchema.row(n.id);
        double v = p.values != null ? p.values.number(n.id) : r.min();
        float frac = (float) SheetFormat.fraction(r, v);
        float x = n.x, w = n.w;
        float ty = y + 5f;
        c.fill(x - 1f, ty - 1f, w + 2f, 8f, RIM);
        c.fill(x, ty, w, 6f, BLACK);
        c.fill(x + 1f, ty + 1f, w - 2f, 4f, PIT);
        float inner = w - 2f;
        float from, to;
        if (r.min() >= 0) {
            from = 0f;
            to = frac;
        } else {
            float zero = (float) SheetFormat.fraction(r, 0);
            from = Math.min(frac, zero);
            to = Math.max(frac, zero);
        }
        if (to > from) {
            c.fill(x + 1f + from * inner, ty + 1f, (to - from) * inner, 4f, p.accent.base);
            c.fill(x + 1f + from * inner, ty + 1f, (to - from) * inner, 1f, p.accent.hl);
        }
        boolean hover = !n.disabled && (n == p.hover || n == p.pressed);
        raisedBox(c, x + frac * w - 4f, y + 1f, 8f, 16f, hover ? KNOB_HOVER : KNOB, KNOB_EDGE, KNOB_EDGE,
                KNOB_LOWER, KNOB_LOWER, KNOB_DEPTH);
        if (n.disabled) c.endDisabled();
        if (n == p.focus) outline(c, n.x, y, n.w, n.h, 1f);
    }

    private static void scrollbar(Canvas c, Frame f, Paint p) {
        float max = f.maxScroll();
        if (max <= 0f) return;
        float x = SheetLayout.WIDTH - 4f, top = f.bodyTop, h = f.bodyHeight;
        c.fill(x, top, 4f, h, BAND);
        float th = Math.max(10f, h * h / f.contentHeight);
        float ty = top + (h - th) * (p.scroll / max);
        c.fill(x, ty, 4f, th, BLACK);
        c.fill(x + 1f, ty + 1f, 2f, th - 2f, THUMB);
    }

    // ------------------------------------------------------------------ outlines

    /** A 1 su outline {@code offset} su outside the box (CSS {@code outline} with {@code outline-offset}). */
    private static void outline(Canvas c, float x, float y, float w, float h, float offset) {
        ring(c, x - offset - 1f, y - offset - 1f, w + 2f * (offset + 1f), h + 2f * (offset + 1f), TEXT_HI);
    }

    /** A 1 su frame whose outer edge is the given rect. */
    private static void ring(Canvas c, float x, float y, float w, float h, int color) {
        c.fill(x, y, w, 1f, color);
        c.fill(x, y + h - 1f, w, 1f, color);
        c.fill(x, y + 1f, 1f, h - 2f, color);
        c.fill(x + w - 1f, y + 1f, 1f, h - 2f, color);
    }
}
