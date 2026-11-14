package com.bedwarsqol.hud;

import com.bedwarsqol.config.ClientSettings;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every HUD module's state carries its Background switch through {@link HudModules}; the two gen timers share one; a
 * module reset keeps it; Edit HUD's undo snapshot includes it. The scoreboard, chat and tab list (cycle B's overlays)
 * are background-capable without being HUD modules; any other id is ignored.
 */
public class HudModulesBackgroundTest {

    private static boolean isTimer(String id) {
        return BedwarsHudRenderer.DIAMOND_TIMER_HUD.equals(id) || BedwarsHudRenderer.EMERALD_TIMER_HUD.equals(id);
    }

    @Test
    public void everyModuleCarriesItsBackground() {
        for (String id : HudModules.ids()) {
            ClientSettings c = new ClientSettings();
            HudModuleState before = HudModules.get(c, id);
            assertTrue(id, before.background);
            assertTrue(id, HudModules.background(c, id));

            HudModules.set(c, id, before.withBackground(false));
            assertFalse(id, c.hudBackground(id));
            assertFalse(id, HudModules.get(c, id).background);
            assertFalse(id, HudModules.background(c, id));
            assertEquals("only the Background changed", before.withBackground(false), HudModules.get(c, id));
            for (String other : HudModules.ids()) {
                if (other.equals(id) || (isTimer(id) && isTimer(other))) continue;
                assertTrue(id + " leaves " + other, HudModules.background(c, other));
            }

            HudModules.setBackground(c, id, true);
            assertTrue(id, HudModules.background(c, id));
            assertTrue("On stores nothing", c.hudBackgrounds.isEmpty());
        }
    }

    @Test
    public void genTimersShareOneBackground() {
        ClientSettings c = new ClientSettings();
        HudModules.setBackground(c, BedwarsHudRenderer.EMERALD_TIMER_HUD, false);
        assertFalse(HudModules.background(c, BedwarsHudRenderer.DIAMOND_TIMER_HUD));
        assertFalse(HudModules.background(c, BedwarsHudRenderer.EMERALD_TIMER_HUD));
        HudModules.set(c, BedwarsHudRenderer.DIAMOND_TIMER_HUD,
                HudModules.get(c, BedwarsHudRenderer.DIAMOND_TIMER_HUD).withBackground(true));
        assertTrue(HudModules.background(c, BedwarsHudRenderer.DIAMOND_TIMER_HUD));
        assertTrue(HudModules.background(c, BedwarsHudRenderer.EMERALD_TIMER_HUD));
    }

    @Test
    public void resetKeepsBackground() {
        ClientSettings c = new ClientSettings();
        c.potionHudX = 40;
        c.potionHudAnchor = 0;
        c.potionStatusEnabled = true;
        HudModules.setBackground(c, BedwarsHudRenderer.POTION_HUD, false);
        HudModuleState reset = HudModules.defaults(c, BedwarsHudRenderer.POTION_HUD);
        assertEquals(HudPlacement.AUTO, reset.anchor);
        assertTrue(reset.enabled);
        assertFalse("a reset is about place and size, not the Background", reset.background);
        assertTrue(HudModules.defaults(c, BedwarsHudRenderer.ARMOR_HUD).background);
    }

    @Test
    public void snapshotRestoreIncludeBackground() {
        ClientSettings c = new ClientSettings();
        Map<String, HudModuleState> before = HudModules.snapshot(c);
        HudModules.setBackground(c, BedwarsHudRenderer.KEYSTROKES_HUD, false);
        HudModules.setBackground(c, BedwarsHudRenderer.DIAMOND_TIMER_HUD, false);
        Map<String, HudModuleState> after = HudModules.snapshot(c);
        assertFalse("undo sees a Background change", before.equals(after));
        assertFalse(after.get(BedwarsHudRenderer.KEYSTROKES_HUD).background);
        assertFalse(after.get(BedwarsHudRenderer.EMERALD_TIMER_HUD).background);

        HudModules.restore(c, before);
        for (String id : HudModules.ids()) assertTrue(id, HudModules.background(c, id));
        HudModules.restore(c, after);
        assertFalse(HudModules.background(c, BedwarsHudRenderer.KEYSTROKES_HUD));
        assertFalse(HudModules.background(c, BedwarsHudRenderer.DIAMOND_TIMER_HUD));
        assertFalse(HudModules.background(c, BedwarsHudRenderer.EMERALD_TIMER_HUD));
        assertTrue(HudModules.background(c, BedwarsHudRenderer.POTION_HUD));
    }

    @Test
    public void overlayIdsAreBackgroundCapable() {
        List<String> expected = new ArrayList<String>(HudModules.ids());
        expected.addAll(HudBackground.OVERLAY_IDS);
        assertEquals(expected, HudModules.backgroundIds());
        for (String id : HudBackground.OVERLAY_IDS) {
            assertFalse(id + " is not a HUD module", HudModules.ids().contains(id));
            ClientSettings c = new ClientSettings();
            assertTrue(id, HudModules.background(c, id));
            HudModules.setBackground(c, id, false);
            assertFalse(id, HudModules.background(c, id));
            assertFalse(id, c.hudBackground(id));
            assertEquals(1, c.hudBackgrounds.size());
            HudModules.setBackground(c, id, true);
            assertTrue(id, HudModules.background(c, id));
            assertTrue(c.hudBackgrounds.isEmpty());
        }
    }

    @Test
    public void unknownIdIsIgnored() {
        ClientSettings c = new ClientSettings();
        HudModules.setBackground(c, "nonsense", false);
        assertTrue(c.hudBackgrounds.isEmpty());
        assertTrue(HudModules.background(c, "nonsense"));
        HudModules.setBackground(c, null, false);
        assertTrue(c.hudBackgrounds.isEmpty());
        assertTrue(HudModules.background(c, null));
    }
}
