package com.bedwarsqol.gui;

import com.bedwarsqol.gui.sheet.SheetSchema;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Runs in every build that compiles common/, against that tree's own {@link SettingsBindings}: Zoom and No Hurt Cam
 * are listed on Ornithe and hidden on Lunar and Forge.
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
        for (String id : new String[] {"zoom", "noHurtCam", "toggleSprint"}) {
            assertEquals(platform + " lists " + id, ornithe, SheetSchema.shows(values, id));
        }
    }
}
