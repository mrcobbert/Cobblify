package com.bedwarsqol.gui.sheet;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SheetPainterTest {

    private final SheetFixtures.Values values = new SheetFixtures.Values();
    private final SheetState state = new SheetState();

    private SheetLayout.Frame layout() {
        return new SheetLayout(new SheetFixtures.Metrics(), values, state).layout(447f, "v0.17.0");
    }

    private static SheetFixtures.Recorder paint(SheetLayout.Frame f, SheetPainter.Paint p) {
        SheetFixtures.Recorder c = new SheetFixtures.Recorder();
        SheetPainter.paint(c, f, p);
        return c;
    }

    /** Whether some fill is exactly this rect in this colour. */
    private static boolean filled(SheetFixtures.Recorder c, float x, float y, float w, float h, int colour) {
        for (int i = 0; i < c.fills.size(); i++) {
            float[] r = c.fills.get(i);
            if (r[0] == x && r[1] == y && r[2] == w && r[3] == h && c.colours.get(i) == colour) return true;
        }
        return false;
    }

    @Test
    public void openCategoryKeyIsPressedInTheAccentAndTheOthersAreRaised() {
        SheetLayout.Frame f = layout();
        SheetPainter.Paint p = new SheetPainter.Paint();
        SheetFixtures.Recorder c = paint(f, p);
        SheetLayout.Node hud = SheetFixtures.find(f, SheetLayout.T.CATEGORY, "hud");
        SheetLayout.Node combat = SheetFixtures.find(f, SheetLayout.T.CATEGORY, "combat");
        float key = 13f;
        // pressed: sunk 2 su, the accent face below its two shadow lines
        assertTrue(filled(c, hud.x + 2f, hud.y + key + 2f + 3f, hud.w - 3f, SheetLayout.CATEGORY_KEY_H - 2f - 4f, p.accent.base));
        assertTrue(filled(c, hud.x + 1f, hud.y + key + 2f + 1f, hud.w - 2f, 1f, p.accent.sh));
        // raised: the lit top line and the 2 su dark ledge of an 8 su key
        assertTrue(filled(c, combat.x + 1f, combat.y + key + 1f, combat.w - 2f, 1f, SheetColors.EDGE));
        assertTrue(filled(c, combat.x + 1f, combat.y + key + SheetLayout.CATEGORY_KEY_H - 3f, combat.w - 2f, 2f, SheetColors.DEPTH));
        assertEquals(13f + SheetLayout.CATEGORY_KEY_H, combat.h, 0f);
    }

    @Test
    public void searchIsARecessLitFromTheTopLeft() {
        SheetLayout.Frame f = layout();
        SheetFixtures.Recorder c = paint(f, new SheetPainter.Paint());
        SheetLayout.Node s = SheetFixtures.find(f, SheetLayout.T.SEARCH, null);
        assertTrue("lip below", filled(c, s.x, s.y + s.h, s.w, 1f, SheetColors.GROOVE));
        assertTrue("top wall", filled(c, s.x + 1f, s.y + 2f, s.w - 2f, 1f, SheetColors.RECESS_SHADE));
        assertTrue("left wall", filled(c, s.x + 1f, s.y + 2f, 1f, s.h - 3f, SheetColors.RECESS_SHADE));
        assertTrue("bottom wall", filled(c, s.x + 2f, s.y + s.h - 2f, s.w - 3f, 1f, SheetColors.RECESS_LIT));
        assertTrue("right wall", filled(c, s.x + s.w - 2f, s.y + 3f, 1f, s.h - 5f, SheetColors.RECESS_LIT));
    }

    @Test
    public void focusedSearchShowsMinecraftsUnderscoreCaretAndNoFocusBox() {
        SheetLayout.Frame f = layout();
        SheetPainter.Paint p = new SheetPainter.Paint();
        p.searchFocused = true;
        p.caretOn = true;
        SheetFixtures.Recorder c = paint(f, p);
        SheetLayout.Node s = SheetFixtures.find(f, SheetLayout.T.SEARCH, null);
        // empty and focused: no hint, the caret at the text's start (the recorder's baseline is the line top)
        for (Object[] t : c.texts) assertTrue("no hint while focused", !"Search".equals(t[0]));
        int caret = -1;
        for (int i = 0; i < c.fills.size(); i++) {
            float[] r = c.fills.get(i);
            if (r[2] == 5f && r[3] == 1f && c.colours.get(i) == p.accent.base && r[1] == s.lineTop) caret = i;
        }
        assertTrue("an underscore caret", caret >= 0);
        // no white focus ring around the field
        for (int i = 0; i < c.fills.size(); i++) {
            float[] r = c.fills.get(i);
            boolean ringTop = r[0] == s.x - 2f && r[1] == s.y - 2f && c.colours.get(i) == SheetColors.TEXT_HI;
            assertTrue("no focus ring", !ringTop);
        }
        p.caretOn = false;
        SheetFixtures.Recorder off = paint(f, p);
        assertEquals("the caret blinks off", c.fills.size() - 1, off.fills.size());
    }

    @Test
    public void idleSearchShowsItsHint() {
        SheetFixtures.Recorder c = paint(layout(), new SheetPainter.Paint());
        boolean hint = false;
        for (Object[] t : c.texts) hint |= "Search".equals(t[0]);
        assertTrue(hint);
    }

    @Test
    public void moduleChevronPointsRightWhileClosedAndDownWhileOpen() {
        values.setOn("potion", true);
        state.open.add("potion");
        SheetLayout.Frame f = layout();
        SheetFixtures.Recorder c = paint(f, new SheetPainter.Paint());
        SheetLayout.Node potion = null, armor = null;
        for (SheetLayout.Node n : f.body) {
            if (n.type != SheetLayout.T.OPEN) continue;
            if ("potion".equals(n.id)) potion = n;
            if ("armor".equals(n.id)) armor = n;
        }
        // closed: 6 rows of 1.5 su strokes (3 device px at 2 px per su) stepping right to a two-pixel tip, then back,
        // centred where Minecraft's 7 px one sat
        float ix = armor.x + 6.5f, iy = armor.y + f.bodyTop + 10.5f;
        assertTrue(filled(c, ix + 1f, iy, 1.5f, 1f, SheetColors.TEXT_MID));
        assertTrue("the tip", filled(c, ix + 3f, iy + 2f, 1.5f, 1f, SheetColors.TEXT_MID));
        assertTrue("the tip", filled(c, ix + 3f, iy + 3f, 1.5f, 1f, SheetColors.TEXT_MID));
        assertTrue(filled(c, ix + 1f, iy + 5f, 1.5f, 1f, SheetColors.TEXT_MID));
        // open: the same strokes stepping down
        ix = potion.x + 6.5f;
        iy = potion.y + f.bodyTop + 10.5f;
        assertTrue(filled(c, ix, iy + 1f, 1f, 1.5f, SheetColors.TEXT_MID));
        assertTrue("the tip", filled(c, ix + 2f, iy + 3f, 1f, 1.5f, SheetColors.TEXT_MID));
        assertTrue("the tip", filled(c, ix + 3f, iy + 3f, 1f, 1.5f, SheetColors.TEXT_MID));
        assertEquals(19f, SheetFixtures.find(f, SheetLayout.T.NAME, null).x, 0f);
    }

    @Test
    public void closeIsAKeyThatSinksWhilePressed() {
        SheetLayout.Frame f = layout();
        SheetLayout.Node x = SheetFixtures.find(f, SheetLayout.T.CLOSE, null);
        SheetFixtures.Recorder raised = paint(f, new SheetPainter.Paint());
        assertTrue(filled(raised, x.x + 1f, x.y + 1f, x.w - 2f, 1f, SheetColors.EDGE));
        assertTrue("the cross", filled(raised, x.x + 4f, x.y + 3f, 2f, 2f, SheetColors.TEXT_HI));
        SheetPainter.Paint p = new SheetPainter.Paint();
        p.pressed = x;
        SheetFixtures.Recorder down = paint(f, p);
        assertTrue(filled(down, x.x + 1f, x.y + 2f + 1f, x.w - 2f, 1f, SheetColors.SH));
        assertTrue("the cross sinks with the face", filled(down, x.x + 4f, x.y + 5f, 2f, 2f, SheetColors.TEXT_HI));
    }

    @Test
    public void nameIsTheWordmarkInPixelsNotText() {
        SheetLayout.Frame f = layout();
        SheetFixtures.Recorder c = paint(f, new SheetPainter.Paint());
        for (Object[] t : c.texts) assertTrue(!"Cobblify".equals(t[0]));
        SheetLayout.Node b = SheetFixtures.find(f, SheetLayout.T.BRAND, null);
        // C's top-left stem pixel at the name's corner, over its shadow one pixel down and right
        assertTrue(filled(c, b.x, b.y, 6f, 1f, SheetColors.TEXT_HI));
        assertTrue(filled(c, b.x + 1f, b.y + 1f, 6f, 1f, SheetColors.textShadow(SheetColors.TEXT_HI)));
    }

    @Test
    public void leftEdgeIsABevelPaintedLastOverEveryBand() {
        SheetLayout.Frame f = new SheetLayout(new SheetFixtures.Metrics(), new SheetFixtures.Values(), new SheetState())
                .layout(447f, "v0.17.0");
        SheetFixtures.Recorder c = new SheetFixtures.Recorder();
        SheetPainter.Paint p = new SheetPainter.Paint();
        SheetPainter.paint(c, f, p);
        List<float[]> fills = c.fills;
        int n = fills.size();
        int[] colours = {SheetColors.BLACK, SheetColors.EDGE_LIT, SheetColors.EDGE_STEP};
        for (int i = 0; i < 3; i++) {
            float[] r = fills.get(n - 3 + i);
            assertEquals("line " + i + " x", i, r[0], 0f);
            assertEquals("line " + i + " width", 1f, r[2], 0f);
            assertEquals("line " + i + " top", 0f, r[1], 0f);
            assertEquals("line " + i + " height", f.height, r[3], 0f);
            assertEquals("line " + i + " colour", colours[i], (int) c.colours.get(n - 3 + i));
        }
        // nothing reaches past the sheet's left edge onto the world
        for (float[] r : fills) assertTrue(r[0] >= 0f);
    }

    private SheetFixtures.Recorder paintMinecraft(SheetPainter.Paint p) {
        SheetFixtures.MinecraftMetrics m = new SheetFixtures.MinecraftMetrics(1f);
        SheetLayout.Frame f = new SheetLayout(m, values, state).layout(447f, "v0.17.0");
        SheetFixtures.Recorder c = new SheetFixtures.Recorder(m);
        SheetPainter.paint(c, f, p);
        return c;
    }

    @Test
    public void minecraftsFontKeepsTheWordmarkInItsWhite() {
        SheetFixtures.Recorder c = paintMinecraft(new SheetPainter.Paint());
        for (Object[] t : c.texts) assertTrue(!"Cobblify".equals(t[0]));
        assertTrue(c.colours.contains(0xFFFFFFFF));
        assertTrue(c.colours.contains(0xFF3F3F3F));
    }

    @Test
    public void minecraftsFontCaretIsItsOwnUnderscoreButSearchKeepsTheModernOne() {
        SheetPainter.Paint p = new SheetPainter.Paint();
        p.searchFocused = true;
        p.caretOn = true;
        // the search stays in the modern font, so its caret is the modern bar
        SheetFixtures.Recorder c = paintMinecraft(p);
        for (Object[] t : c.texts) assertTrue(!"_".equals(t[0]));
        // a value field being typed in is Minecraft's font, with its underscore
        state.category = "combat";
        values.setOn("handPos", true);
        state.open.add("handPos");
        SheetFixtures.MinecraftMetrics m = new SheetFixtures.MinecraftMetrics(1f);
        SheetLayout.Frame f = new SheetLayout(m, values, state).layout(447f, "v0.17.0");
        SheetLayout.Node value = null;
        for (SheetLayout.Node n : f.body) if (n.type == SheetLayout.T.VALUE) value = n;
        p = new SheetPainter.Paint();
        p.caretOn = true;
        p.editing = value.id;
        p.editText = "1.2";
        c = new SheetFixtures.Recorder(m);
        SheetPainter.paint(c, f, p);
        int caret = -1;
        for (int i = 0; i < c.texts.size(); i++) if ("_".equals(c.texts.get(i)[0])) caret = i;
        assertTrue(caret >= 0);
        assertEquals(p.accent.base, (int) c.textColours.get(caret));
    }

    @Test
    public void minecraftsChevronIsShadowedAndLevelWithTheNamesCapitals() {
        SheetPainter.Paint p = new SheetPainter.Paint();
        SheetFixtures.MinecraftMetrics m = new SheetFixtures.MinecraftMetrics(1f);
        SheetLayout.Frame f = new SheetLayout(m, values, state).layout(447f, "v0.17.0");
        SheetFixtures.Recorder c = new SheetFixtures.Recorder(m);
        SheetPainter.paint(c, f, p);
        SheetLayout.Node armor = null;
        for (SheetLayout.Node n : f.body) if (n.type == SheetLayout.T.OPEN && "armor".equals(n.id)) armor = n;
        float top = c.baseline(SheetFont.NAME, armor.lineTop + f.bodyTop, armor.lineHeight) - 7f;
        // the recorder's baseline is the line top: the first stroke in vanilla grey, and its shadow a font pixel off
        // (1.5 font px rounds to 2 device px at 1 px per su)
        int grey = SheetColors.minecraftText(SheetColors.TEXT_MID);
        assertEquals(0xFFAAAAAA, grey);
        assertTrue(filled(c, armor.x + 6.5f + 1f, top, 2f, 1f, grey));
        assertTrue(filled(c, armor.x + 6.5f + 2f, top + 1f, 2f, 1f, SheetColors.textShadow(grey)));
    }
}
