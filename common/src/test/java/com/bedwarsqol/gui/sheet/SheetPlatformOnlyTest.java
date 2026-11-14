package com.bedwarsqol.gui.sheet;

import com.bedwarsqol.gui.sheet.SheetLayout.Frame;
import com.bedwarsqol.gui.sheet.SheetLayout.Node;
import com.bedwarsqol.gui.sheet.SheetLayout.T;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Zoom and No Hurt Cam are Combat modules of the Ornithe build only. Lunar's {@code SettingsBindings.has} lists its
 * own ids and Forge's accepts every schema id, and neither overrides {@link SheetValues#hasPlatformOnly}, so both
 * must keep hiding them; only a tree that opts in lists them. This runs in every build that compiles common/.
 */
public class SheetPlatformOnlyTest {

    private static final List<String> ORNITHE_ONLY = Arrays.asList("zoom", "noHurtCam", "toggleSprint", "dynamicFov");

    /** Forge's rule: every schema id (the fixture's has() accepts everything but Lunar's alert modules), no opt-in. */
    private final Tree acceptsEverything = new Tree(null, false);

    /** A tree over the fixture's values with its own has() and opt-in rules. */
    private static final class Tree implements SheetValues {
        private final SheetFixtures.Values base = new SheetFixtures.Values();
        private final Set<String> ids;
        private final boolean optsIn;

        /** {@code ids} null: every schema id, as Forge's and Ornithe's has(). */
        Tree(Set<String> ids, boolean optsIn) {
            this.ids = ids;
            this.optsIn = optsIn;
        }

        @Override public boolean has(String id) { return ids == null ? base.has(id) : ids.contains(id); }
        @Override public boolean hasPlatformOnly(String id) { return optsIn; }
        @Override public boolean on(String id) { return base.on(id); }
        @Override public void setOn(String id, boolean on) { base.setOn(id, on); }
        @Override public String choice(String id) { return base.choice(id); }
        @Override public void setChoice(String id, String option) { base.setChoice(id, option); }
        @Override public double number(String id) { return base.number(id); }
        @Override public void setNumber(String id, double value) { base.setNumber(id, value); }
        @Override public String keyLabel(String id) { return base.keyLabel(id); }
        @Override public void run(String id) { base.run(id); }
        @Override public void save() { base.save(); }
    }

    /** Lunar's rule: an explicit id list (its SettingsBindings.IDS, Combat part and a few others). */
    private static Tree lunar() {
        return new Tree(new HashSet<String>(Arrays.asList(
                "inventory", "genTimers", "session", "mapInfo", "handPos", "handX", "noEsc", "tabHF",
                "incAlert", "bedAlert", "bgAlert", "incKey", "stats", "nickUtils", "tagUtils", "partyJoin",
                "hudSize", "sbSize", "tabSize", "guiSize", "font", "accent")), false);
    }

    @Test
    public void zoomAndNoHurtCamAreCombatModulesMarkedPlatformOnly() {
        for (String id : ORNITHE_ONLY) {
            SheetSchema.Module m = SheetSchema.module(id);
            assertEquals(id, "combat", m.category().id);
            assertTrue(id, SheetSchema.platformOnly(id));
        }
        assertEquals("Zoom", SheetSchema.module("zoom").name);
        assertEquals("No Hurt Cam", SheetSchema.module("noHurtCam").name);
        // the rest of the schema is unaffected
        assertFalse(SheetSchema.platformOnly("noEsc"));
        assertFalse(SheetSchema.platformOnly("handX"));
        assertFalse(SheetSchema.platformOnly("accent"));
    }

    @Test
    public void hiddenOnATreeWhoseHasAcceptsEverySchemaId() {
        for (String id : ORNITHE_ONLY) {
            assertTrue(acceptsEverything.has(id));
            assertFalse(id, SheetSchema.shows(acceptsEverything, id));
        }
        assertTrue(SheetSchema.shows(acceptsEverything, "noEsc"));
        assertFalse(combatModules(acceptsEverything).contains("zoom"));
        assertFalse(combatModules(acceptsEverything).contains("noHurtCam"));
        assertTrue(SheetSearch.search("zoom", acceptsEverything).isEmpty());
        assertTrue(SheetSearch.search("hurt cam", acceptsEverything).isEmpty());
    }

    @Test
    public void hiddenOnLunar() {
        Tree lunar = lunar();
        for (String id : ORNITHE_ONLY) assertFalse(id, SheetSchema.shows(lunar, id));
        List<String> combat = combatModules(lunar);
        assertEquals(Arrays.asList("handPos", "noEsc"), combat);
        assertTrue(SheetSearch.search("zoom", lunar).isEmpty());
    }

    @Test
    public void listedWhenTheTreeOptsIn() {
        Tree ornithe = new Tree(null, true);
        for (String id : ORNITHE_ONLY) assertTrue(id, SheetSchema.shows(ornithe, id));
        List<String> combat = combatModules(ornithe);
        assertTrue(combat.contains("zoom"));
        assertTrue(combat.contains("noHurtCam"));
        assertEquals(1, SheetSearch.search("zoom", ornithe).size());
        assertEquals("noHurtCam", SheetSearch.search("hurt cam", ornithe).get(0).module.id);
    }

    /** Background row id, its module and the HUD item it switches (Ornithe's per-item Background). */
    private static final String[][] BACKGROUND_ROWS = {
            {"potionBg", "potion", "potion"},
            {"armorBg", "armor", "armor"},
            {"inventoryBg", "inventory", "inventory"},
            {"genTimersBg", "genTimers", "diamondtimer"},
            {"keystrokesBg", "keystrokes", "keystrokes"},
            {"mapInfoBg", "mapInfo", "heightlimit"},
            {"sessionBg", "session", "sessionstats"},
    };

    @Test
    public void backgroundRowsArePlatformOnlyRowsOfSharedModules() {
        for (String[] b : BACKGROUND_ROWS) {
            SheetSchema.Row r = SheetSchema.row(b[0]);
            assertEquals(b[0], SheetSchema.Kind.TOGGLE, r.kind);
            assertEquals(b[0], "Background", r.name);
            assertEquals(b[0], b[1], r.module().id);
            assertEquals(b[0] + " is the first row of its module", r, SheetSchema.module(b[1]).rows.get(0));
            assertEquals(b[0], b[2], r.hudItem());
            assertEquals(b[0], b[2], SheetSchema.hudItem(b[0]));
            assertTrue(b[0], SheetSchema.platformOnly(b[0]));
            // the module itself is shared, and so are its other rows
            assertFalse(b[1], SheetSchema.platformOnly(b[1]));
            assertEquals(null, SheetSchema.hudItem(b[1]));
        }
        assertFalse(SheetSchema.platformOnly("potionInGame"));
        assertEquals(null, SheetSchema.hudItem("potionInGame"));
        assertEquals(null, SheetSchema.hudItem("nonsense"));
        assertEquals(null, SheetSchema.row("potionInGame").hudItem());
    }

    @Test
    public void backgroundRowsHiddenOnForgeAndLunarListedOnOrnithe() {
        Tree lunar = lunar();
        Tree ornithe = new Tree(null, true);
        for (String[] b : BACKGROUND_ROWS) {
            assertFalse(b[0], SheetSchema.shows(acceptsEverything, b[0]));
            assertFalse(b[0], SheetSchema.shows(lunar, b[0]));
            assertTrue(b[0], SheetSchema.shows(ornithe, b[0]));
        }
        assertTrue("the shared module still shows on Forge", SheetSchema.shows(acceptsEverything, "potion"));
        assertTrue(SheetSchema.shows(acceptsEverything, "potionInGame"));
        assertTrue(SheetSearch.search("background", acceptsEverything).isEmpty());
        assertTrue(SheetSearch.search("background", lunar).isEmpty());
        // on Ornithe the search finds exactly the Background rows
        Set<String> found = new HashSet<String>();
        for (SheetSearch.Hit h : SheetSearch.search("background", ornithe)) {
            for (SheetSchema.Row r : h.rows) found.add(r.id);
        }
        Set<String> expected = new HashSet<String>();
        for (SheetSchema.Category c : SheetSchema.CATS) {
            for (SheetSchema.Module m : c.modules) for (SheetSchema.Row r : m.rows) if (r.hudItem() != null) expected.add(r.id);
        }
        for (String[] b : BACKGROUND_ROWS) assertTrue(b[0], expected.contains(b[0]));
        assertEquals(expected, found);
    }

    @Test
    public void keystrokesNoLongerPromisesCps() {
        SheetSchema.Module k = SheetSchema.module("keystrokes");
        assertEquals("WASD, mouse buttons, spacebar", k.desc);
        assertFalse(k.desc.toLowerCase().contains("cps"));
        assertFalse(k.kw.toLowerCase().contains("cps"));
        for (SheetSchema.Row r : k.rows) assertFalse(r.id, r.name.toLowerCase().contains("cps"));
    }

    @Test
    public void textModulesArePlatformOnlyHudModules() {
        List<String> hud = new ArrayList<String>();
        for (SheetSchema.Module m : SheetSchema.category("hud").modules) hud.add(m.id);
        int k = hud.indexOf("keystrokes");
        assertEquals(Arrays.asList("keystrokes", "fps", "cps", "ping", "mapInfo"), hud.subList(k, k + 5));
        assertEquals("FPS", SheetSchema.module("fps").name);
        assertEquals("CPS", SheetSchema.module("cps").name);
        assertEquals("Ping", SheetSchema.module("ping").name);
        Tree lunar = lunar();
        Tree ornithe = new Tree(null, true);
        for (String id : new String[]{"fps", "cps", "ping"}) {
            assertTrue(id, SheetSchema.platformOnly(id));
            SheetSchema.Row bg = SheetSchema.module(id).rows.get(0);
            assertEquals(id + "Bg", bg.id);
            assertEquals(id, bg.hudItem());
            assertEquals("Background", bg.name);
            assertEquals("See-through box behind it", bg.desc);
            for (String row : new String[]{id, id + "Bg"}) {
                assertFalse(row, SheetSchema.shows(acceptsEverything, row));
                assertFalse(row, SheetSchema.shows(lunar, row));
                assertTrue(row, SheetSchema.shows(ornithe, row));
            }
        }
        assertTrue(SheetSearch.search("fps", acceptsEverything).isEmpty());
        assertTrue(SheetSearch.search("fps", lunar).isEmpty());
        assertEquals("fps", SheetSearch.search("fps", ornithe).get(0).module.id);
    }

    /**
     * Background module id, its name, its category, the module it follows and the HUD item it switches: the
     * scoreboard, tab list and chat have no on/off of their own, so their Background is a module whose toggle is it.
     */
    private static final String[][] OVERLAY_BACKGROUNDS = {
            {"scoreboardBg", "Scoreboard Background", "visuals", "tabHF", "scoreboard"},
            {"tabListBg", "Tab List Background", "visuals", "scoreboardBg", "tablist"},
            {"chatBg", "Chat Background", "chat", "chatLong", "chat"},
    };

    @Test
    public void overlayBackgroundsArePlatformOnlyModulesCarryingTheirItem() {
        for (String[] b : OVERLAY_BACKGROUNDS) {
            SheetSchema.Module m = SheetSchema.module(b[0]);
            assertTrue(b[0], m != null);
            assertEquals(b[0], b[1], m.name);
            assertEquals(b[0], b[2], m.category().id);
            List<SheetSchema.Module> mods = m.category().modules;
            assertEquals(b[0] + " follows " + b[3], mods.indexOf(SheetSchema.module(b[3])) + 1, mods.indexOf(m));
            assertTrue(b[0] + " is a leaf: its toggle is the Background", m.rows.isEmpty());
            assertEquals(b[0], b[4], m.hudItem());
            assertEquals(b[0], b[4], SheetSchema.hudItem(b[0]));
            assertTrue(b[0], SheetSchema.platformOnly(b[0]));
        }
        // the modules they follow are untouched
        assertFalse(SheetSchema.platformOnly("tabHF"));
        assertEquals(null, SheetSchema.hudItem("tabHF"));
        assertEquals(null, SheetSchema.module("chatLong").hudItem());
    }

    @Test
    public void overlayBackgroundsHiddenOnForgeAndLunarListedOnOrnithe() {
        Tree lunar = lunar();
        Tree ornithe = new Tree(null, true);
        Set<String> forgeHits = new HashSet<String>(), lunarHits = new HashSet<String>(), ornitheHits = new HashSet<String>();
        for (SheetSearch.Hit h : SheetSearch.search("background", acceptsEverything)) if (h.module != null) forgeHits.add(h.module.id);
        for (SheetSearch.Hit h : SheetSearch.search("background", lunar)) if (h.module != null) lunarHits.add(h.module.id);
        for (SheetSearch.Hit h : SheetSearch.search("background", ornithe)) if (h.module != null) ornitheHits.add(h.module.id);
        for (String[] b : OVERLAY_BACKGROUNDS) {
            assertFalse(b[0], SheetSchema.shows(acceptsEverything, b[0]));
            assertFalse(b[0], SheetSchema.shows(lunar, b[0]));
            assertTrue(b[0], SheetSchema.shows(ornithe, b[0]));
            assertFalse(b[0], forgeHits.contains(b[0]));
            assertFalse(b[0], lunarHits.contains(b[0]));
            assertTrue("search finds " + b[0] + " on Ornithe", ornitheHits.contains(b[0]));
        }
    }

    /** The module toggles the Combat page draws, in order. */
    private static List<String> combatModules(SheetValues values) {
        SheetState state = new SheetState();
        state.category = "combat";
        Frame f = new SheetLayout(new SheetFixtures.Metrics(), values, state).layout(447f, "v0");
        List<String> ids = new ArrayList<String>();
        for (Node n : f.body) if (n.type == T.MODULE_TOGGLE) ids.add(n.id);
        return ids;
    }
}
