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

    private static final List<String> ORNITHE_ONLY = Arrays.asList("zoom", "noHurtCam");

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
