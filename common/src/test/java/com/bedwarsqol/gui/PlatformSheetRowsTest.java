package com.bedwarsqol.gui;

import com.bedwarsqol.gui.sheet.SheetSchema;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Runs in every build that compiles common/, against that tree's own {@link SettingsBindings}: Zoom, No Hurt Cam,
 * Toggle Sprint, Dynamic FOV, Team Upgrades (with its Layout row), FPS, CPS, Ping, the per-item Background rows and
 * the Fullbright, Item Physics, 1.7 Animations and Particles modules are listed on Ornithe and hidden on Lunar and Forge.
 */
public class PlatformSheetRowsTest {

    private static boolean present(String className) {
        try {
            Class.forName(className, false, PlatformSheetRowsTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    public void zoomAndNoHurtCamAreListedOnlyOnOrnithe() {
        boolean ornithe = present("com.bedwarsqol.ornithe.ModEvents");
        boolean lunar = present("net.weavemc.api.ModInitializer");
        boolean forge = present("net.minecraftforge.common.MinecraftForge");
        assertEquals("exactly one platform on the test classpath", 1, (ornithe ? 1 : 0) + (lunar ? 1 : 0) + (forge ? 1 : 0));
        String platform = ornithe ? "ornithe" : lunar ? "lunar" : "forge";

        SettingsBindings values = new SettingsBindings();
        for (String id : new String[] {"zoom", "noHurtCam", "toggleSprint", "dynamicFov", "teamUpgrades",
                "teamUpgradesLayout", "teamUpgradesBg",
                "potionBg", "armorBg", "inventoryBg", "genTimersBg", "keystrokesBg", "mapInfoBg", "sessionBg",
                "scoreboardBg", "tabListBg", "chatBg",
                "fps", "cps", "ping", "fpsBg", "cpsBg", "pingBg", "fpsBrackets", "cpsBrackets", "pingBrackets",
                "fpsLabel", "cpsLabel", "pingLabel",
                "fullbright", "itemPhysics", "oldAnimations", "oaBlockHit", "oaItemPosition", "oaSmoothSneak",
                "oaHearts", "oaRedArmor", "particles", "ptAlwaysCrit", "ptHideOwnPotion", "ptHideBlockBreak",
                "ptMultiplier"}) {
            assertEquals(platform + " lists " + id, ornithe, SheetSchema.shows(values, id));
        }
    }

    /** Ornithe sizes the scoreboard and tab list as HUD items (corner resize), so its Settings page drops the rows. */
    @Test
    public void scoreboardAndTabListSizeRowsAreHiddenOnlyOnOrnithe() {
        boolean ornithe = present("com.bedwarsqol.ornithe.ModEvents");
        String platform = ornithe ? "ornithe" : present("net.weavemc.api.ModInitializer") ? "lunar" : "forge";
        SettingsBindings values = new SettingsBindings();
        for (String id : new String[] {"sbSize", "tabSize"}) {
            assertEquals(platform + " lists " + id, !ornithe, SheetSchema.shows(values, id));
        }
        assertEquals(platform + " lists hudSize", true, SheetSchema.shows(values, "hudSize"));
    }
}
