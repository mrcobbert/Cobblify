package com.bedwarsqol.gui.sheet;

import static com.bedwarsqol.gui.sheet.SheetFixtures.find;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.bedwarsqol.gui.sheet.SheetLayout.Frame;
import com.bedwarsqol.gui.sheet.SheetLayout.Node;
import com.bedwarsqol.gui.sheet.SheetLayout.T;
import org.junit.Test;

/**
 * Geometry from the prototype's CSS, in sheet units at a 447 su tall sheet (894 px at GUI scale 2). The numbers are
 * the reference renders' pixel positions halved.
 */
public class SheetLayoutTest {

    private final SheetFixtures.Values values = new SheetFixtures.Values();
    private final SheetState state = new SheetState();

    private Frame layout() {
        return new SheetLayout(new SheetFixtures.Metrics(), values, state).layout(447f, "v0.17.0");
    }

    @Test
    public void nameIsTheWordmarkWithTheVersionBesideItsCapitals() {
        Frame f = layout();
        Node b = find(f, T.BRAND, null), v = find(f, T.VERSION, null);
        assertEquals(11f, b.x, 0f);
        assertEquals(63f, b.w, 0f);
        assertEquals(12f, b.y, 0f);
        assertEquals(10f, b.h, 0f);
        assertEquals(77f, v.x, 0f);
        // Inter's capitals start 1.5 su into the version's line box: 1 su above the name's
        assertEquals(b.y - 1f, v.lineTop + 1.5f, 0f);

        // with Minecraft's font the name and the version stay exactly as they are
        Frame mc = new SheetLayout(new SheetFixtures.MinecraftMetrics(2f), values, state).layout(447f, "v0.17.0");
        Node mb = find(mc, T.BRAND, null), mv = find(mc, T.VERSION, null);
        assertEquals(b.y, mb.y, 0f);
        assertEquals(b.w, mb.w, 0f);
        assertEquals(v.x, mv.x, 0f);
        assertEquals(v.w, mv.w, 0f);
        assertEquals(v.lineTop, mv.lineTop, 0f);
    }

    @Test
    public void bandsAndFooter() {
        Frame f = layout();
        // 4 su of top padding over the prototype's 26 su header
        assertEquals(57f, f.bodyTop, 0f);
        // Edit HUD sits in a line box with the inherited 14 px strut: the footer is 38.9 su, not 36
        assertEquals(447f - 38.9f, f.footerTop, 1e-4f);
        Node hero = find(f, T.HERO, null);
        assertEquals(f.footerTop + 10.9f, hero.y, 1e-4f);
        assertEquals(20f, hero.h, 0f);
        assertEquals(f.footerTop - 57f, f.bodyHeight, 1e-4f);
        Node close = find(f, T.CLOSE, null);
        assertEquals(224f, close.x, 0f);
        assertEquals(9.5f, close.y, 0f);
    }

    @Test
    public void categoriesShareTheBandWidth() {
        Frame f = layout();
        float left = Float.MAX_VALUE, right = 0f, sum = 0f;
        int n = 0;
        for (Node c : f.chrome) {
            if (c.type != T.CATEGORY) continue;
            left = Math.min(left, c.x);
            right = Math.max(right, c.x + c.w);
            sum += c.w;
            n++;
            assertEquals("hud".equals(c.id), c.on);
        }
        assertEquals(6, n);
        assertEquals(11f, left, 1e-4f);
        assertEquals(234f, right, 1e-3f);
        assertEquals(223f - 10f, sum, 1e-3f);
    }

    @Test
    public void closedModulesStackEvery39Units() {
        Frame f = layout();
        assertEquals(12f, find(f, T.MODULE_TOGGLE, "potion").y, 0f);
        assertEquals(12f + 39f, find(f, T.MODULE_TOGGLE, "armor").y, 0f);
        assertEquals(12f + 5 * 39f, find(f, T.MODULE_TOGGLE, "mapInfo").y, 0f);
        assertEquals(12f + 6 * 39f, find(f, T.MODULE_TOGGLE, "session").y, 0f);
        Node tg = find(f, T.MODULE_TOGGLE, "potion");
        assertEquals(207f, tg.x, 0f); // a <button>: no margin
        assertEquals(28f, tg.w, 0f);
    }

    @Test
    public void openModuleGetsAWell() {
        values.setOn("potion", true);
        state.open.add("potion");
        Frame f = layout();
        Node well = find(f, T.WELL, null);
        assertNotNull(well);
        // the text column sits right of the chevron
        assertEquals(19f, well.x, 0f);
        assertEquals(37f, well.y, 0f);
        assertEquals(216f, well.w, 0f);
        assertEquals(28f, well.h, 0f);
        Node tg = find(f, T.TOGGLE, "potionInGame");
        assertTrue(tg.small);
        assertEquals(203f, tg.x, 0f);
        assertEquals(74f, find(f, T.DIVIDER, null).y, 0f);
    }

    @Test
    public void dependentRowsAreDisabledWithTheirReason() {
        values.setOn("session", true);
        state.category = "hud";
        state.open.add("session");
        Frame f = layout();
        Node key = find(f, T.KEY, "sessionKey");
        assertTrue(key.disabled);
        assertEquals("None", key.text);
        Node reason = find(f, T.ROW_DESC, "sessionKey");
        assertEquals("Turn on Hold Key to Show", reason.text);
        assertFalse(find(f, T.BUTTON, "sessionReset").disabled);
        assertNull("sub-setting descriptions are hidden", find(f, T.ROW_DESC, "sessionHold"));
    }

    @Test
    public void rowsOfAModuleThatIsOffAreDisabledWithoutNotice() {
        state.category = "combat";
        state.open.add("tnt");
        Frame f = layout();
        for (Node n : f.body) if (n.type == T.OPTION) assertTrue(n.disabled);
        assertNull(find(f, T.ROW_DESC, "tntRadius"));
        Node chosen = null;
        for (Node n : f.body) if (n.type == T.OPTION && n.chosen) chosen = n;
        assertEquals("10", chosen.text);
    }

    @Test
    public void optionRowsShareBordersAndSlidersFillTheWell() {
        values.setOn("blockOverlay", true);
        state.category = "visuals";
        state.open.add("blockOverlay");
        Frame f = layout();
        Node first = null, last = null;
        for (Node n : f.body) {
            if (n.type != T.OPTION || !"boStyle".equals(n.id)) continue;
            if (first == null) first = n;
            last = n;
        }
        assertEquals(28f, first.x, 0f);
        assertEquals(226f, last.x + last.w, 1e-4f);
        assertEquals((198f + 2f) / 3f, first.w, 1e-4f);
        // the overlay takes the accent: no palette, only a White switch
        for (Node n : f.body) assertFalse(n.type == T.SWATCH);
        assertNotNull(find(f, T.TOGGLE, "boWhite"));
    }

    @Test
    public void ignoreBlankLinesComesBeforeTimeBasedStacking() {
        state.category = "chat";
        state.open.add("chatStack");
        Frame f = layout();
        assertTrue(find(f, T.LABEL, "stackBlanks").y < find(f, T.LABEL, "stackTime").y);
        assertTrue(find(f, T.LABEL, "stackTime").y < find(f, T.LABEL, "stackWindow").y);
    }

    /** At one device px per su, where Minecraft's secondary text is as big as its body text. */
    private Frame minecraftLayout() {
        return new SheetLayout(new SheetFixtures.MinecraftMetrics(1f), values, state).layout(447f, "v0.17.0");
    }

    @Test
    public void inTheModernFontEveryDescriptionStaysOneLine() {
        for (SheetSchema.Category c : SheetSchema.CATS) {
            state.category = c.id;
            for (SheetSchema.Module m : c.modules) state.open.add(m.id);
            Frame f = layout();
            for (SheetSchema.Module m : c.modules) {
                if (!values.has(m.id)) continue;
                int lines = 0;
                for (Node n : f.body) if (n.type == T.DESC && n.text.equals(m.desc)) lines++;
                assertEquals(m.name, 1, lines);
            }
        }
    }

    @Test
    public void inMinecraftsFontALongDescriptionWrapsInTwoBalancedLinesAndTheRowGrows() {
        // the fixture's 6 su characters are wider than the real font's, so this one wraps there
        state.category = "chat";
        Frame one = layout();
        Frame f = minecraftLayout();
        Node first = null, second = null;
        for (int i = 0; i < f.body.size(); i++) {
            Node n = f.body.get(i);
            if (n.type == T.DESC && n.text.startsWith("Collapse")) {
                first = n;
                second = f.body.get(i + 1);
            }
        }
        assertEquals("Collapse repeats", first.text);
        assertEquals("into one (xN) line", second.text);
        assertEquals(T.DESC, second.type);
        assertEquals(first.lineTop + 10f, second.lineTop, 0f);
        Node target = find(f, T.OPEN, "chatStack");
        assertTrue(second.x + second.w <= target.x + target.w);
        // the row grows a line, and is still one open target
        assertEquals(find(one, T.OPEN, "chatStack").h + 10f, target.h, 0f);
    }

    @Test
    public void minecraftsNameAndDescriptionKeepTheModernSpacing() {
        Frame modern = layout();
        Frame mc = new SheetLayout(new SheetFixtures.MinecraftMetrics(2f), values, state).layout(447f, "v0.17.0");
        for (Frame f : new Frame[]{modern, mc}) {
            Node name = null, desc = null;
            for (Node n : f.body) if (n.type == T.NAME && "Armor".equals(n.text)) name = n;
            for (Node n : f.body) if (n.type == T.DESC && "Equipped armor type".equals(n.text)) desc = n;
            assertEquals(name.lineTop + 14f, desc.lineTop, 0f);
            assertEquals(12f, name.lineHeight, 0f);
            assertEquals(10f, desc.lineHeight, 0f);
            assertEquals("the chevron lines up with the name", name.lineTop, find(f, T.OPEN, "armor").lineTop, 0f);
        }
    }

    @Test
    public void inMinecraftsFontAHintWrapsShortOfItsControl() {
        values.setOn("session", true);
        values.setOn("sessionHold", true);
        state.open.add("session");
        state.capture = "sessionKey";
        Frame f = minecraftLayout();
        Node key = find(f, T.KEY, "sessionKey");
        int lines = 0;
        for (Node n : f.body) {
            if (n.type != T.ROW_DESC || !"sessionKey".equals(n.id)) continue;
            lines++;
            assertTrue(n.text, n.x + n.w <= key.x - 4f);
        }
        assertEquals(2, lines);
    }

    @Test
    public void minecraftsFontNeverDrawsASheetUnitUnderADevicePixel() {
        assertEquals(1.4f, SheetLayout.unit(2, 0, false), 1e-6f);
        assertEquals(1.4f, SheetLayout.unit(2, 0, true), 1e-6f);
        assertEquals(0.7f, SheetLayout.unit(1, 0, false), 1e-6f);
        assertEquals(1f, SheetLayout.unit(1, 0, true), 0f);
    }

    @Test
    public void accentSwatchesWrapTwelveToARow() {
        state.category = "settings";
        Frame f = layout();
        java.util.List<Node> sw = new java.util.ArrayList<Node>();
        for (Node n : f.body) if (n.type == T.SWATCH) sw.add(n);
        assertEquals(39, sw.size());
        assertEquals(sw.get(0).y, sw.get(11).y, 0f);
        assertEquals(sw.get(0).x, sw.get(12).x, 0f);
        assertEquals(sw.get(0).y + 18f, sw.get(12).y, 0f);
        assertEquals(sw.get(0).x + 11 * 18f, sw.get(11).x, 0f);
        assertTrue("the last column ends inside the row", sw.get(11).x + 15f <= SheetLayout.WIDTH - 9f);
        assertEquals(sw.get(0).y + 3 * 18f, sw.get(38).y, 0f);
    }

    @Test
    public void settingsPageIsOneSectionWithoutHeaders() {
        state.category = "settings";
        Frame f = layout();
        int rows = 0;
        for (Node n : f.body) if (n.type == T.LABEL) rows++;
        assertEquals(6, rows); // HUD Size, Scoreboard Size, Tab List Size, GUI Size, Font, Accent
        assertNull(find(f, T.LABEL, "display"));
        assertTrue("Font sits under GUI Size", find(f, T.LABEL, "font").y > find(f, T.LABEL, "guiSize").y);
        assertTrue(find(f, T.LABEL, "font").y < find(f, T.LABEL, "accent").y);
        assertNull("no section header", find(f, T.NAME, null));
        assertNull("no divider", find(f, T.DIVIDER, null));
        assertEquals("level with a module name", 7f, find(f, T.LABEL, "hudSize").lineTop, 0f);
    }

    @Test
    public void searchFieldEndsAtTheCloseButtonAndStartsAboveHypixel() {
        Frame f = layout();
        Node search = find(f, T.SEARCH, null);
        Node hypixel = null;
        for (Node c : f.chrome) if (c.type == T.CATEGORY && "hypixel".equals(c.id)) hypixel = c;
        assertEquals(hypixel.x, search.x, 1e-4f);
        assertEquals(216f, search.x + search.w, 1e-4f);
    }

    @Test
    public void searchShowsPathsMatchedRowsAndShowAll() {
        state.query = "sound";
        Frame f = layout();
        assertEquals("3 matches for \"sound\"", f.body.get(0).text);
        assertNotNull(find(f, T.TOGGLE, "notifyMention"));
        assertNotNull(find(f, T.TOGGLE, "notifyInc"));
        assertNotNull(find(f, T.TOGGLE, "tagSound"));
        assertNull(find(f, T.TOGGLE, "tagUrchin"));
        Node more = find(f, T.MORE, "tagUtils");
        assertEquals("Show all 6 settings in Tag Utils", more.text);
        for (Node c : f.chrome) if (c.type == T.CATEGORY) assertFalse(c.on);
        state.showAll.add("tagUtils");
        assertNotNull(find(layout(), T.TOGGLE, "tagUrchin"));
    }

    @Test
    public void lunarListsOnlyItsModules() {
        values.missing.clear();
        for (String id : new String[]{"chatStack", "chatNotify", "chatUnlimited", "chatKeep", "chatCopy", "chatLong",
                "stackTime", "stackBlanks", "stackWindow", "notifyMention", "notifyInc"}) values.missing.add(id);
        state.category = "chat";
        Frame f = layout();
        assertEquals(12f, find(f, T.MODULE_TOGGLE, "incAlert").y, 0f);
        assertEquals(12f + 39f, find(f, T.MODULE_TOGGLE, "incKey").y, 0f);
        assertNull(find(f, T.OPEN, "chatStack"));
    }

    @Test
    public void hitTestUsesTheDrawnScroll() {
        Frame f = layout();
        Node n = SheetLayout.hit(f, 220f, 53f + 12f + 7f, 0f);
        assertEquals(T.MODULE_TOGGLE, n.type);
        assertEquals("potion", n.id);
        n = SheetLayout.hit(f, 220f, 53f + 12f + 7f, 39f);
        assertEquals("armor", n.id);
        assertEquals(T.OPEN, SheetLayout.hit(f, 100f, 53f + 20f, 0f).type);
        state.category = "combat";
        Frame combat = layout();
        assertNull("a leaf module's header is not an open target", SheetLayout.hit(combat, 100f, 53f + 2 * 39f + 20f, 0f));
    }
}
