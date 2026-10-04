package com.bedwarsqol.gui.sheet;

import com.bedwarsqol.gui.sheet.SheetSchema.Category;
import com.bedwarsqol.gui.sheet.SheetSchema.Kind;
import com.bedwarsqol.gui.sheet.SheetSchema.Module;
import com.bedwarsqol.gui.sheet.SheetSchema.Row;
import com.bedwarsqol.gui.sheet.SheetSchema.Section;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays the sheet out in sheet units (su), following the approved prototype's CSS ({@code src/shell.html}) box by
 * box: the header, category and footer bands, and the scrolling body of modules, wells and rows. The result is a
 * flat list of {@link Node}s that the painter draws and the screen hit-tests. Body nodes are in content coordinates
 * (y from the body's top, before scrolling). The sheet is 244 su wide with its 1 su left border at x = 0.
 */
public final class SheetLayout {

    public static final float WIDTH = 244f;
    /** 4 su of top padding over the 26 su header row, so the brand, search and close sit clear of the screen's top. */
    public static final float HEADER_H = 30f;
    public static final float NAV_H = 27f;
    public static final float BODY_TOP = HEADER_H + NAV_H;
    /** A category's key under its label: an option-style key, its 2 su ledge hanging into the band's padding. */
    public static final float CATEGORY_KEY_H = 8f;
    /** GUI Size: Small, Medium and Large, as steps from the size the screen gets at Medium. */
    private static final int[] GUI_SIZE_STEPS = {-1, 0, 1};
    /**
     * Screen height per step at Medium: 1080p gets 2 device px per unit, 1440p 3 and 2160p 4, so the 7 px-per-unit
     * capitals look about the same size on a typical monitor of each (22 to 28 arcminutes at arm's length).
     */
    static final float HEIGHT_PER_STEP = 540f;
    /** Below 2 the capitals are 7 device px, too small to read on any screen, so no GUI Size goes there. */
    private static final int MIN_READABLE = 2;
    /** The sheet never covers more than this share of the screen's width... */
    static final float MAX_SCREEN_SHARE = 0.45f;
    /** ...and always has room for this many closed modules between its bands. */
    static final float MIN_MODULES = 4f;
    /** A closed module's height, and roughly what the header, category and footer bands take together. */
    private static final float MODULE_H = 39f, BANDS_H = BODY_TOP + 38f;
    /** Right edge of a module row's content: the sheet's edge less its 9 su padding. */
    private static final float ROW_RIGHT = WIDTH - 9f;

    /**
     * Device px per sheet unit, from the screen alone: its height sets Medium ({@link #HEIGHT_PER_STEP}), Small and
     * Large are a step either side but never under {@link #MIN_READABLE}, then the unit drops until the sheet fits
     * ({@link #fits}). The game's GUI scale plays no part: players pick it for the HUD, and at Small or on a 4K screen it
     * would make the sheet unreadable. Always whole: Minecraft's font only draws crisp at whole device pixels per font
     * pixel, and a whole unit puts every edge on a pixel in both fonts.
     */
    public static int unit(int guiSize, int displayWidth, int displayHeight) {
        int step = GUI_SIZE_STEPS[Math.max(0, Math.min(GUI_SIZE_STEPS.length - 1, guiSize))];
        int medium = Math.max(MIN_READABLE, Math.round(displayHeight / HEIGHT_PER_STEP));
        int u = Math.max(MIN_READABLE, medium + step);
        while (u > 1 && !fits(u, displayWidth, displayHeight)) u--;
        return u;
    }

    /** Whether a sheet at {@code unit} keeps to its share of the screen's width and shows enough modules. */
    static boolean fits(int unit, int displayWidth, int displayHeight) {
        return WIDTH * unit <= MAX_SCREEN_SHARE * displayWidth
                && displayHeight / (float) unit - BANDS_H >= MIN_MODULES * MODULE_H;
    }

    public enum T {
        BRAND, VERSION, SEARCH, CLOSE, CATEGORY, HERO,
        RESULTS, DIVIDER, PATH, OPEN, NAME, DESC, MODULE_TOGGLE, WELL,
        LABEL, ROW_DESC, TOGGLE, OPTION, SWATCH, SLIDER, VALUE, KEY, BUTTON, MORE
    }

    public static final class Node {
        public final T type;
        public float x, y, w, h;
        /** Row, module or category id. */
        public String id;
        public String text = "";
        /** Option or swatch index; the category index. */
        public int index = -1;
        public SheetFont font;
        /** Line-box top and height for text nodes (su, same space as y). */
        public float lineTop, lineHeight;
        public boolean on, small, open, disabled, chosen;
        /** Drawn on a well (its backdrop is the well colour, not the sheet's). */
        public boolean well;
        /** Substring to highlight in this text (search), or null. */
        public String mark;
        public int color;

        Node(T type, float x, float y, float w, float h) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }

        /** Whether the screen reacts to this node under the cursor. */
        public boolean interactive() {
            switch (type) {
                case SEARCH: case CLOSE: case CATEGORY: case HERO: case MODULE_TOGGLE: case TOGGLE:
                case OPTION: case SWATCH: case SLIDER: case VALUE: case KEY: case BUTTON: case MORE:
                    return !disabled;
                case OPEN:
                    return open; // a leaf's header holds a spacer, not an open button
                default:
                    return false;
            }
        }
    }

    /** One laid-out frame. */
    public static final class Frame {
        public final float height;
        public final List<Node> chrome = new ArrayList<Node>();
        public final List<Node> body = new ArrayList<Node>();
        public float bodyTop = BODY_TOP, bodyHeight, contentHeight, footerTop;
        public Frame(float height) {
            this.height = height;
        }

        public float maxScroll() {
            return Math.max(0f, contentHeight - bodyHeight);
        }
    }

    private final TextMetrics tm;
    private final SheetValues values;
    private final SheetState state;

    public SheetLayout(TextMetrics tm, SheetValues values, SheetState state) {
        this.tm = tm;
        this.values = values;
        this.state = state;
    }

    // ------------------------------------------------------------------ frame

    public Frame layout(float height, String version) {
        Frame f = new Frame(height);
        List<Node> cats = categories();
        header(f, version, cats);
        f.chrome.addAll(cats);
        footer(f);
        f.bodyTop = BODY_TOP;
        f.bodyHeight = f.footerTop - BODY_TOP;
        f.contentHeight = state.searching() ? searchBody(f) : pageBody(f);
        return f;
    }

    /** The search field starts above this category's pane, so it lines up with the band below. */
    private static final String SEARCH_FROM = "hypixel";

    private void header(Frame f, String version, List<Node> cats) {
        // .top: flex, align-items center, gap 7, padding 0 5 0 10, height 26; content runs from x 11 to 239.
        // The name is pixel art: its left edge on the tabs' and its capitals centred in the 26 su row.
        float bw = SheetWordmark.width(tm.unit()), bh = SheetWordmark.height(tm.unit());
        Node brand = new Node(T.BRAND, 11f, 4f + 13f - bh / 2f, bw, bh);
        brand.text = "Cobblify";
        f.chrome.add(brand);
        // the version (and the search) is the modern font in both, as the name is the same pixel art in both: its
        // capitals start 1.5 su into the 6.5 su line box, 1 su above the name's
        Node ver = text(T.VERSION, version, SheetFont.VERSION, 11f + bw + 3f, brand.y - 1f - 1.5f, 6.5f, tm.width(version, SheetFont.VERSION));
        f.chrome.add(ver);
        // search: right-aligned before the close button, starting above the Hypixel pane
        float sx = ver.x + ver.w + 7f + 1f;
        for (Node c : cats) if (SEARCH_FROM.equals(c.id)) sx = Math.max(sx, c.x);
        float sr = 239f - 15f - 7f - 1f;
        Node search = new Node(T.SEARCH, sx, 4f + 5f, sr - sx, 16f);
        search.text = state.query;
        search.font = SheetFont.SEARCH;
        search.lineTop = 4f + 6f;
        search.lineHeight = 14f;
        f.chrome.add(search);
        // close: 15x15 at the right end, vertically centred
        f.chrome.add(new Node(T.CLOSE, 239f - 15f, 4f + 5.5f, 15f, 15f));
    }

    private List<Node> categories() {
        // .nav: padding 0 10 5, border-bottom 1; .cats: margin-top 2, gap 2, buttons flex 1 1 auto.
        List<Node> out = new ArrayList<Node>();
        List<Category> cats = SheetSchema.CATS;
        int n = cats.size();
        float[] base = new float[n];
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            base[i] = tm.width(cats.get(i).name, SheetFont.CATEGORY) + 2f;
            sum += base[i];
        }
        float left = 11f, right = WIDTH - 10f;
        float extra = (right - left - 2f * (n - 1) - sum) / n;
        float x = left;
        boolean searching = state.searching();
        for (int i = 0; i < n; i++) {
            float w = base[i] + extra;
            Node c = new Node(T.CATEGORY, x, BODY_TOP - NAV_H + 2f, w, 13f + CATEGORY_KEY_H);
            c.id = cats.get(i).id;
            c.index = i;
            c.text = cats.get(i).name;
            c.font = SheetFont.CATEGORY;
            c.lineTop = c.y + 1f;
            c.lineHeight = 9f;
            c.on = !searching && cats.get(i).id.equals(state.category);
            out.add(c);
            x += w + 2f;
        }
        return out;
    }

    /** The body text the prototype inherits (14 px, line-height 1.45): the strut of the footer's line box. */
    private static final SheetFont STRUT = new SheetFont(14f, 400);

    private void footer(Frame f) {
        // .foot: border-top 1, padding 7 10 8. Edit HUD is an inline-flex button in a block, so it sits in a line
        // box with the inherited strut, aligned on its text baseline; that line box is taller than the button.
        float lh = 14f * 1.45f;
        float sa = tm.ascent(STRUT), sd = tm.descent(STRUT);
        float strutAbove = sa + (lh - sa - sd) / 2f, strutBelow = lh - strutAbove;
        float ha = tm.ascent(SheetFont.HERO), hd = tm.descent(SheetFont.HERO);
        float lineTop = 1f + (16f - 9f) / 2f;
        float base = lineTop + (9f - ha - hd) / 2f + ha;
        float above = Math.max(strutAbove, base), below = Math.max(strutBelow, 20f - base);
        float footerH = 1f + 7f + above + below + 8f;
        f.footerTop = f.height - footerH;
        Node hero = new Node(T.HERO, 11f, f.footerTop + 1f + 7f + (above - base), WIDTH - 21f, 20f);
        hero.text = "Edit HUD";
        hero.font = SheetFont.HERO;
        f.chrome.add(hero);
    }

    // ------------------------------------------------------------------ body

    private float pageBody(Frame f) {
        Category c = SheetSchema.category(state.category);
        if (c == null) c = SheetSchema.CATS.get(0);
        float y = 0f;
        boolean first = true;
        if (c.isSettings()) {
            for (Section s : c.sections) {
                y = section(f, s, y, first, null, null, s.rows);
                first = false;
            }
        } else {
            for (Module m : c.modules) {
                if (!values.has(m.id)) continue;
                y = module(f, m, y, first, null, null, state.open.contains(m.id), visibleRows(m), false);
                first = false;
            }
        }
        return y;
    }

    private List<Row> visibleRows(Module m) {
        List<Row> out = new ArrayList<Row>();
        for (Row r : m.rows) if (values.has(r.id)) out.add(r);
        return out;
    }

    private float searchBody(Frame f) {
        String q = state.query.trim();
        List<SheetSearch.Hit> hits = SheetSearch.search(q, values);
        int count = SheetSearch.count(hits);
        String results = hits.isEmpty()
                ? "Nothing matches \"" + q + "\". Try a module or setting name."
                : count + (count == 1 ? " match for \"" : " matches for \"") + q + "\"";
        List<String> lines = wrap(results, SheetFont.RESULTS, ROW_RIGHT - 19f);
        for (int i = 0; i < lines.size(); i++) {
            Node rh = text(T.RESULTS, lines.get(i), SheetFont.RESULTS, 19f, 7f + 10f * i, 10f, 0f);
            rh.color = SheetColors.TEXT_MID;
            f.body.add(rh);
        }
        float y = 24f + 10f * (lines.size() - 1);
        for (SheetSearch.Hit h : hits) {
            if (h.module != null) {
                List<Row> rows = visibleRows(h.module);
                List<Row> shown = rows;
                boolean more = false;
                if (!h.rows.isEmpty() && !state.showAll.contains(h.module.id)) {
                    shown = new ArrayList<Row>();
                    for (Row r : rows) if (h.rows.contains(r)) shown.add(r);
                    more = shown.size() < rows.size();
                }
                boolean open = !h.rows.isEmpty() || state.open.contains(h.module.id);
                y = module(f, h.module, y, false, h.module.category().name, q, open, shown, more);
            } else {
                List<Row> shown = new ArrayList<Row>(h.rows);
                y = section(f, h.section, y, false, h.section.category().name, q, shown);
            }
        }
        return y;
    }

    /** A module block: divider, optional search path, header row, and its well when open. Returns the next y. */
    private float module(Frame f, Module m, float y, boolean first, String path, String q, boolean open,
                         List<Row> rows, boolean more) {
        boolean leaf = rows.isEmpty() && visibleRows(m).isEmpty();
        boolean isOpen = open && !leaf;
        // the module's text column, right of the chevron: the path, name, description and well all start here
        final float text = 19f;
        if (!first) {
            f.body.add(new Node(T.DIVIDER, 1f, y, WIDTH - 1f, 2f));
            y += 1f;
        }
        if (path != null) {
            Node p = text(T.PATH, path, SheetFont.PATH, text, y + 6f, 8f, 0f);
            p.color = SheetColors.TEXT_LO;
            f.body.add(p);
            y += 14f;
        }
        // .mh: min-height 33, padding-right 9; .open: padding 7 0 7 6 (bottom 6 when open); .mt: 12 + 2 + 10.
        // a description too long for one line, in Minecraft's font, wraps under itself, short of the toggle
        float targetW = ROW_RIGHT - 28f - 6f - 1f;
        List<String> desc = wrap(m.desc, SheetFont.DESC, 1f + targetW - text);
        float openH = 7f + 12f + 2f + 10f * desc.size() + (isOpen ? 6f : 7f);
        float rowH = Math.max(33f, openH);
        float openTop = y + (rowH - openH) / 2f;
        float nameTop = openTop + 7f, nameH = 12f, descTop = openTop + 7f + 12f + 2f;
        Node target = new Node(T.OPEN, 1f, openTop, targetW, openH);
        target.id = m.id;
        target.open = !leaf; // a leaf holds a spacer, not a button
        target.on = isOpen;
        // the name's line box, which the chevron lines up with
        target.lineTop = nameTop;
        target.lineHeight = nameH;
        f.body.add(target);
        Node name = text(T.NAME, m.name, SheetFont.NAME, text, nameTop, nameH, tm.width(m.name, SheetFont.NAME));
        name.mark = q;
        name.color = SheetColors.TEXT_HI;
        f.body.add(name);
        for (int i = 0; i < desc.size(); i++) {
            String line = desc.get(i);
            Node d = text(T.DESC, line, SheetFont.DESC, text, descTop + 10f * i, 10f, tm.width(line, SheetFont.DESC));
            d.mark = q;
            d.color = SheetColors.TEXT_MID;
            f.body.add(d);
        }
        // <button class="tg">: the prototype's `.sheet button {margin: 0}` outranks `.tg {margin: 2px 1px}`
        Node tg = new Node(T.MODULE_TOGGLE, ROW_RIGHT - 28f, y + (rowH - 14f) / 2f, 28f, 14f);
        tg.id = m.id;
        tg.on = values.on(m.id);
        f.body.add(tg);
        y += rowH;
        if (!isOpen) return y;
        boolean moduleOn = values.on(m.id);
        // .kids: margin 0 9 9 (the text column), padding 3 8 3, border 1
        float wy = y;
        float cy = wy + 1f + 3f;
        for (Row r : rows) cy = row(f, r, cy, text + 1f + 8f, WIDTH - 9f - 1f - 8f, true, !moduleOn, q);
        if (more) {
            int total = visibleRows(m).size();
            float lh = tm.normalLineHeight(SheetFont.MORE);
            Node mo = text(T.MORE, "Show all " + total + " settings in " + m.name, SheetFont.MORE, text + 1f + 8f, cy + 4f, lh,
                    tm.width("Show all " + total + " settings in " + m.name, SheetFont.MORE));
            mo.id = m.id;
            mo.y = cy;
            mo.h = 4f + lh + 2f;
            mo.color = SheetColors.TEXT_MID;
            f.body.add(mo);
            cy += mo.h;
        }
        float wellH = cy + 3f + 1f - wy;
        Node well = new Node(T.WELL, text, wy, WIDTH - 9f - text, wellH);
        f.body.add(f.body.indexOf(tg) + 1, well);
        return wy + wellH + 9f;
    }

    /**
     * A Settings-page section: divider, header with a spacer and no toggle, then its rows without a well. A section
     * without a name has no header; its first label then starts 7 su down, level with a module name.
     */
    private float section(Frame f, Section s, float y, boolean first, String path, String q, List<Row> rows) {
        if (!first) {
            f.body.add(new Node(T.DIVIDER, 1f, y, WIDTH - 1f, 2f));
            y += 1f;
        }
        if (path != null) {
            Node p = text(T.PATH, path, SheetFont.PATH, 19f, y + 6f, 8f, 0f);
            p.color = SheetColors.TEXT_LO;
            f.body.add(p);
            y += 14f;
        }
        if (s.name == null) {
            y += 2f;
        } else {
            float openH = 7f + 12f + 6f;
            float rowH = Math.max(33f, openH);
            float openTop = y + (rowH - openH) / 2f;
            Node name = text(T.NAME, s.name, SheetFont.NAME, 19f, openTop + 7f, 12f, tm.width(s.name, SheetFont.NAME));
            name.mark = q;
            name.color = SheetColors.TEXT_HI;
            f.body.add(name);
            y += rowH;
        }
        // .sect .kids: padding 0 9 6 18
        for (Row r : rows) y = row(f, r, y, 19f, WIDTH - 9f, false, false, q);
        return y + 6f;
    }

    /** One row: label (and its reason or hint), the control at the right, and a block control below. */
    private float row(Frame f, Row r, float y, float left, float right, boolean inWell, boolean moduleOff, String q) {
        float pad = inWell ? 4f : 5f;
        SheetFont lf = inWell ? SheetFont.WELL_LABEL : SheetFont.ROW_LABEL;
        float llh = inWell ? 10f : 11f;
        SheetFont df = inWell ? SheetFont.HINT : SheetFont.ROW_DESC;
        float dlh = inWell ? 9f : 10f, dgap = inWell ? 1f : 2f;
        boolean depOff = r.dep() != null && !values.on(r.dep());
        boolean disabled = moduleOff || depOff;
        String desc = inWell ? null : r.desc;
        if (!moduleOff && depOff) desc = r.reason();
        boolean capturing = r.id.equals(state.capture);
        boolean confirming = r.id.equals(state.confirm);
        if (r.kind == Kind.KEY && capturing) desc = "Esc cancels, Backspace clears";
        if (r.kind == Kind.ACTION && confirming) desc = "Press Confirm to clear them";

        // the control in the right column: its margin box (w, h) and border box offset inside it
        float cw = 0f, ch = 0f, mx = 0f, my = 0f, bw = 0f, bh = 0f;
        Node ctl = null;
        switch (r.kind) {
            case TOGGLE: {
                // a <button>: no margin (see the module toggle)
                boolean small = inWell;
                bw = small ? 23f : 28f;
                bh = small ? 12f : 14f;
                ctl = new Node(T.TOGGLE, 0, 0, bw, bh);
                ctl.small = small;
                ctl.on = values.on(r.id);
                break;
            }
            case SLIDER: {
                bw = 36f;
                bh = 14f;
                mx = 1f;
                ctl = new Node(T.VALUE, 0, 0, bw, bh);
                ctl.text = SheetFormat.number(r, values.number(r.id));
                ctl.font = SheetFont.FIELD;
                break;
            }
            case KEY: {
                String label = capturing ? "Press a key" : values.keyLabel(r.id);
                bw = Math.max(capturing ? 52f : 30f, tm.width(label, SheetFont.FIELD) + 10f);
                bh = 14f; // a <button>: no margin
                ctl = new Node(T.KEY, 0, 0, bw, bh);
                ctl.text = label;
                ctl.font = SheetFont.FIELD;
                ctl.on = capturing;
                break;
            }
            case ACTION: {
                String label = confirming ? "Confirm" : r.label();
                bw = tm.width(label, SheetFont.BUTTON) + 14f;
                bh = 16f;
                ctl = new Node(T.BUTTON, 0, 0, bw, bh);
                ctl.text = label;
                ctl.font = SheetFont.BUTTON;
                break;
            }
            default:
                break;
        }
        cw = bw + 2f * mx;
        ch = bh + 2f * my;
        // a description too long for one line, in Minecraft's font, wraps short of the control
        List<String> descLines = desc == null ? new ArrayList<String>() : wrap(desc, df, right - (ctl != null ? cw + 4f : 0f) - left);
        float txtH = 11f + (desc != null ? dgap + dlh * descLines.size() : 0f);
        float row1 = Math.max(txtH, ch);
        float top = y + pad;
        float txtTop = top + (row1 - txtH) / 2f;
        Node label = text(T.LABEL, r.name, lf, left, txtTop + (11f - llh) / 2f, llh, tm.width(r.name, lf));
        label.id = r.id;
        label.mark = q;
        label.disabled = disabled;
        label.color = disabled ? SheetColors.TEXT_LO : SheetColors.TEXT_HI;
        f.body.add(label);
        for (int i = 0; i < descLines.size(); i++) {
            String line = descLines.get(i);
            Node d = text(T.ROW_DESC, line, df, left, txtTop + 11f + dgap + dlh * i, dlh, tm.width(line, df));
            d.id = r.id;
            d.mark = q;
            d.color = disabled ? SheetColors.TEXT_LO : SheetColors.TEXT_MID;
            f.body.add(d);
        }
        if (ctl != null) {
            ctl.x = right - mx - bw;
            ctl.y = top + (row1 - ch) / 2f + my;
            if (ctl.type == T.BUTTON) ctl.y += 1f; // .ctl .b { top: 1px }: the face, not the ledge, centres
            ctl.id = r.id;
            ctl.disabled = disabled;
            ctl.well = inWell;
            f.body.add(ctl);
        }
        float blockH = 0f;
        float by = top + row1 + 5f;
        switch (r.kind) {
            case SEG: {
                String[] opts = r.options();
                String chosen = values.choice(r.id);
                int n = opts.length;
                float total = right - left;
                float w = (total + (n - 1)) / n;
                for (int i = 0; i < n; i++) {
                    Node o = new Node(T.OPTION, left + i * (w - 1f), by, w, 16f);
                    o.id = r.id;
                    o.index = i;
                    o.text = opts[i];
                    o.font = SheetFont.BUTTON;
                    o.chosen = opts[i].equals(chosen);
                    o.disabled = disabled;
                    o.well = inWell;
                    f.body.add(o);
                }
                blockH = 16f - 2f;
                break;
            }
            case SWATCH: {
                String[] opts = r.options();
                int[] colors = r.colors();
                String chosen = values.choice(r.id);
                // 15 su swatches 3 su apart, wrapping into as many rows as the palette needs
                int perRow = Math.max(1, (int) ((right - left - 1f + 3f) / 18f));
                int rows = (opts.length + perRow - 1) / perRow;
                for (int i = 0; i < opts.length; i++) {
                    float x = left + 1f + (i % perRow) * 18f;
                    Node s = new Node(T.SWATCH, x, by + (i / perRow) * 18f, 15f, 15f);
                    s.id = r.id;
                    s.index = i;
                    s.text = opts[i];
                    s.color = 0xFF000000 | colors[i];
                    s.chosen = opts[i].equals(chosen);
                    s.disabled = disabled;
                    s.well = inWell;
                    f.body.add(s);
                }
                blockH = rows * 18f - 3f - 2f;
                break;
            }
            case SLIDER: {
                Node s = new Node(T.SLIDER, left + 4f, by, right - left - 8f, 16f);
                s.id = r.id;
                s.disabled = disabled;
                s.well = inWell;
                f.body.add(s);
                blockH = 16f;
                break;
            }
            default:
                break;
        }
        float h = pad + row1 + (blockH > 0f ? 5f + blockH : 0f) + pad;
        return y + h;
    }

    /**
     * {@code s} as the lines it takes in {@code max} su: itself when it fits (always, in the modern font), else broken
     * at spaces, two lines balanced so neither leaves a word stranded.
     */
    List<String> wrap(String s, SheetFont font, float max) {
        List<String> lines = new ArrayList<String>();
        if (tm.width(s, font) <= max) {
            lines.add(s);
            return lines;
        }
        String[] words = s.split(" ");
        String line = "";
        for (String word : words) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && tm.width(next, font) > max) {
                lines.add(line);
                line = word;
            } else {
                line = next;
            }
        }
        lines.add(line);
        if (lines.size() != 2) return lines;
        // two lines: the break that keeps the longer one shortest
        float best = Math.max(tm.width(lines.get(0), font), tm.width(lines.get(1), font));
        for (int i = 1; i < words.length; i++) {
            String a = join(words, 0, i), b = join(words, i, words.length);
            float wa = tm.width(a, font), wb = tm.width(b, font);
            if (wa <= max && wb <= max && Math.max(wa, wb) < best) {
                best = Math.max(wa, wb);
                lines.set(0, a);
                lines.set(1, b);
            }
        }
        return lines;
    }

    private static String join(String[] words, int from, int to) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) b.append(' ');
            b.append(words[i]);
        }
        return b.toString();
    }

    private static Node text(T type, String s, SheetFont font, float x, float lineTop, float lineHeight, float w) {
        Node n = new Node(type, x, lineTop, w, lineHeight);
        n.text = s;
        n.font = font;
        n.lineTop = lineTop;
        n.lineHeight = lineHeight;
        return n;
    }

    /** The topmost interactive node under a point; body nodes are tested in content space. */
    public static Node hit(Frame f, float x, float y, float scroll) {
        for (int i = f.chrome.size() - 1; i >= 0; i--) {
            Node n = f.chrome.get(i);
            if (n.interactive() && n.contains(x, y)) return n;
        }
        if (y >= f.bodyTop && y < f.bodyTop + f.bodyHeight) {
            float cy = y - f.bodyTop + scroll;
            for (int i = f.body.size() - 1; i >= 0; i--) {
                Node n = f.body.get(i);
                if (n.interactive() && n.contains(x, cy)) return n;
            }
        }
        return null;
    }
}
