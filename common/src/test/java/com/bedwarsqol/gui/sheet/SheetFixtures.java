package com.bedwarsqol.gui.sheet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Test doubles: the prototype's default values, and text metrics with Inter's vertical metrics at GUI scale 2. */
final class SheetFixtures {

    private SheetFixtures() {
    }

    /** Every glyph is half the font size wide; ascent and descent round in device px at 2 px per su. */
    static final class Metrics implements TextMetrics {
        @Override
        public float unit() {
            return 2f;
        }

        @Override
        public float width(String s, SheetFont font) {
            return s.length() * font.size * 0.5f;
        }

        @Override
        public float ascent(SheetFont font) {
            return Math.round(1984f / 2048f * font.size * 2f) / 2f;
        }

        @Override
        public float descent(SheetFont font) {
            return Math.round(494f / 2048f * font.size * 2f) / 2f;
        }

        @Override
        public float normalLineHeight(SheetFont font) {
            return ascent(font) + descent(font);
        }

        @Override
        public boolean minecraftFont() {
            return false;
        }

        @Override
        public float pixel(SheetFont font) {
            return 0f;
        }
    }

    /**
     * Minecraft's font at {@code unit} device px per su, as the canvas sizes it: a whole number of device px per font
     * px, never over one su; 6 font px a character (4 a space), an ascent of 7.5 and a descent of 1.5.
     */
    static final class MinecraftMetrics implements TextMetrics {
        private final float unit;
        /** Roles that keep the modern font measure as {@link Metrics} does. */
        private final Metrics modern = new Metrics();

        MinecraftMetrics(float unit) {
            this.unit = unit;
        }

        @Override
        public float unit() {
            return unit;
        }

        @Override
        public float width(String s, SheetFont font) {
            if (font.modern) return modern.width(s, font);
            float w = 0f;
            for (int i = 0; i < s.length(); i++) w += s.charAt(i) == ' ' ? 4f : 6f;
            return w * pixel(font);
        }

        @Override
        public float ascent(SheetFont font) {
            if (font.modern) return modern.ascent(font);
            return 7.5f * pixel(font);
        }

        @Override
        public float descent(SheetFont font) {
            if (font.modern) return modern.descent(font);
            return 1.5f * pixel(font);
        }

        @Override
        public float normalLineHeight(SheetFont font) {
            if (font.modern) return modern.normalLineHeight(font);
            return 9f * pixel(font);
        }

        @Override
        public boolean minecraftFont() {
            return true;
        }

        @Override
        public float pixel(SheetFont font) {
            if (font.modern) return 0f;
            return Math.max(1, (int) Math.floor(unit + 1e-3f)) / unit;
        }
    }

    /** A canvas that records each fill as {x, y, w, h} and its colour, and the text drawn; the rest is a no-op. */
    static final class Recorder implements Canvas {
        final java.util.List<float[]> fills = new java.util.ArrayList<float[]>();
        final java.util.List<Integer> colours = new java.util.ArrayList<Integer>();
        /** Text drawn: the string, then its x and line top; its colour in {@link #textColours}. */
        final java.util.List<Object[]> texts = new java.util.ArrayList<Object[]>();
        final java.util.List<Integer> textColours = new java.util.ArrayList<Integer>();
        private final TextMetrics m;

        Recorder() {
            this(new Metrics());
        }

        Recorder(TextMetrics m) {
            this.m = m;
        }

        @Override public float unit() { return m.unit(); }
        @Override public void fill(float x, float y, float w, float h, int argb) {
            fills.add(new float[]{x, y, w, h});
            colours.add(argb);
        }
        @Override public void stroke(float[] points, float width, int argb) { }
        @Override public void ring(float cx, float cy, float r, float width, int argb) { }
        @Override public void text(String s, float x, float lineTop, float lineHeight, SheetFont font, int argb) {
            texts.add(new Object[]{s, x, lineTop});
            textColours.add(argb);
        }
        @Override public float baseline(SheetFont font, float lineTop, float lineHeight) { return lineTop; }
        @Override public float[] contentArea(SheetFont font, float lineTop, float lineHeight) { return new float[]{lineTop, lineTop}; }
        @Override public void pushClip(float x, float y, float w, float h) { }
        @Override public void popClip() { }
        @Override public void pushAlpha(float a) { }
        @Override public void popAlpha() { }
        @Override public void beginDisabled(int backdrop) { }
        @Override public void endDisabled() { }
        @Override public float width(String s, SheetFont font) { return m.width(s, font); }
        @Override public float ascent(SheetFont font) { return m.ascent(font); }
        @Override public float descent(SheetFont font) { return m.descent(font); }
        @Override public float normalLineHeight(SheetFont font) { return m.normalLineHeight(font); }
        @Override public boolean minecraftFont() { return m.minecraftFont(); }
        @Override public float pixel(SheetFont font) { return m.pixel(font); }
    }

    /** The prototype's defaults ({@code app.js defaults()}); {@code missing} ids are absent, as on Lunar. */
    static final class Values implements SheetValues {
        final Map<String, Object> v = new HashMap<String, Object>();
        final Set<String> missing = new HashSet<String>();
        final Map<String, Integer> keys = new HashMap<String, Integer>();
        int saves, runs;

        Values() {
            for (String id : ("potion armor inventory genTimers keystrokes session mapInfo potionInGame armorInGame "
                    + "inventoryInGame genTimersInGame keystrokesInGame mapInfoInGame sessionHold handPos tnt noEsc "
                    + "blockOverlay boSee boWhite tabHF").split(" ")) v.put(id, false);
            for (String id : ("chatStack stackTime stackBlanks chatNotify notifyMention notifyInc chatUnlimited chatKeep "
                    + "chatCopy chatLong incKey stats statsNametag statsTab statsHover statsChat statsRank statsReport "
                    + "nickUtils nickNotify autoDenick tagUtils tagUrchin tagSeraph tagTab tagNametag tagChat tagSound "
                    + "autoGg partyJoin").split(" ")) v.put(id, true);
            v.put("handX", 0.0);
            v.put("handY", 0.0);
            v.put("handZ", 0.0);
            v.put("handScale", 1.0);
            v.put("stackWindow", 5.0);
            v.put("tntRadius", "10");
            v.put("boStyle", "Both");
            v.put("boOpacity", "Medium");
            v.put("hudSize", "Medium");
            v.put("sbSize", "Large");
            v.put("tabSize", "Large");
            v.put("guiSize", "Large");
            v.put("font", "Modern");
            v.put("accent", "Orange");
            missing.add("incAlert");
        }

        @Override
        public boolean has(String id) {
            return !missing.contains(id);
        }

        @Override
        public boolean on(String id) {
            Object o = v.get(id);
            return o instanceof Boolean && (Boolean) o;
        }

        @Override
        public void setOn(String id, boolean on) {
            v.put(id, on);
        }

        @Override
        public String choice(String id) {
            return String.valueOf(v.get(id));
        }

        @Override
        public void setChoice(String id, String option) {
            v.put(id, option);
        }

        @Override
        public double number(String id) {
            Object o = v.get(id);
            return o instanceof Double ? (Double) o : 0;
        }

        @Override
        public void setNumber(String id, double value) {
            v.put(id, value);
        }

        @Override
        public String keyLabel(String id) {
            Integer k = keys.get(id);
            return k == null || k == 0 ? "None" : "Key" + k;
        }

        @Override
        public void run(String id) {
            runs++;
        }

        @Override
        public void save() {
            saves++;
        }
    }

    static SheetLayout.Node find(SheetLayout.Frame f, SheetLayout.T type, String id) {
        for (SheetLayout.Node n : f.body) if (n.type == type && (id == null || id.equals(n.id))) return n;
        for (SheetLayout.Node n : f.chrome) if (n.type == type && (id == null || id.equals(n.id))) return n;
        return null;
    }
}
