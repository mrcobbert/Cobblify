package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.sheet.SheetSchema;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.HudModuleState;
import com.bedwarsqol.hud.HudModules;
import com.bedwarsqol.hud.HudPlacement;
import com.google.gson.Gson;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Ornithe sheet lists Team Upgrades under HUD with its Layout row; off and Compact by default (also for a
 * config saved before it existed); the rows write the settings; Edit HUD knows the module; the layout fields
 * round-trip, sanitize and follow HUD Size like Map Info's.
 */
public class SettingsBindingsTeamUpgradesTest {

    private String savedDir;
    private ClientSettings savedConfig;

    @Before
    public void isolate() throws Exception {
        savedDir = System.getProperty("cobblify.test.configDir");
        savedConfig = BedwarsQol.config;
        File dir = Files.createTempDirectory("cobblify-team-upgrades-test").toFile();
        System.setProperty("cobblify.test.configDir", dir.getAbsolutePath());
        BedwarsQol.config = new ClientSettings();
    }

    @After
    public void restore() {
        if (savedDir == null) System.clearProperty("cobblify.test.configDir");
        else System.setProperty("cobblify.test.configDir", savedDir);
        BedwarsQol.config = savedConfig;
    }

    @Test
    public void ornitheListsTeamUpgradesAndItsLayoutRow() {
        SettingsBindings values = new SettingsBindings();
        assertTrue(SheetSchema.shows(values, "teamUpgrades"));
        assertTrue(SheetSchema.shows(values, "teamUpgradesLayout"));
        assertArrayEquals(new String[]{"Compact", "Full"}, SheetSchema.row("teamUpgradesLayout").options());
    }

    @Test
    public void offAndCompactByDefault() {
        ClientSettings fresh = new ClientSettings();
        assertFalse(fresh.teamUpgradesEnabled);
        assertEquals(ClientSettings.TEAM_UPGRADES_COMPACT, fresh.teamUpgradesLayout);
        assertEquals(HudPlacement.AUTO, fresh.teamUpgradesHudAnchor);
        SettingsBindings values = new SettingsBindings();
        assertFalse(values.on("teamUpgrades"));
        assertEquals("Compact", values.choice("teamUpgradesLayout"));
        ClientSettings old = new Gson().fromJson("{\"suppressEscMenu\":true}", ClientSettings.class);
        old.sanitize();
        assertFalse(old.teamUpgradesEnabled);
        assertEquals(ClientSettings.TEAM_UPGRADES_COMPACT, old.teamUpgradesLayout);
        assertEquals(HudPlacement.AUTO, old.teamUpgradesHudAnchor);
    }

    @Test
    public void theRowsWriteTheSettings() {
        SettingsBindings values = new SettingsBindings();
        values.setOn("teamUpgrades", true);
        assertTrue(BedwarsQol.config.teamUpgradesEnabled);
        assertTrue(values.on("teamUpgrades"));
        values.setChoice("teamUpgradesLayout", "Full");
        assertEquals(ClientSettings.TEAM_UPGRADES_FULL, BedwarsQol.config.teamUpgradesLayout);
        assertEquals("Full", values.choice("teamUpgradesLayout"));
        values.setChoice("teamUpgradesLayout", "Compact");
        assertEquals(ClientSettings.TEAM_UPGRADES_COMPACT, BedwarsQol.config.teamUpgradesLayout);
    }

    @Test
    public void editHudKnowsTheModule() {
        String id = BedwarsHudRenderer.TEAM_UPGRADES_HUD;
        assertEquals("teamupgrades", id);
        assertEquals("teamUpgrades", SettingsBindings.moduleForHud(id));
        assertTrue(HudModules.ids().contains(id));
        assertEquals("Team Upgrades", HudModules.title(id));

        ClientSettings c = BedwarsQol.config;
        HudModules.set(c, id, new HudModuleState(12, -7, 4, 1.25f, true));
        assertEquals(12, c.teamUpgradesHudX);
        assertEquals(-7, c.teamUpgradesHudY);
        assertEquals(4, c.teamUpgradesHudAnchor);
        assertEquals(1.25f, c.teamUpgradesHudScale, 0f);
        assertTrue(c.teamUpgradesEnabled);
        assertEquals(new HudModuleState(12, -7, 4, 1.25f, true), HudModules.get(c, id));
        HudModuleState reset = HudModules.defaults(c, id);
        assertEquals(HudPlacement.AUTO, reset.anchor);
        assertTrue(reset.enabled);
    }

    @Test
    public void layoutFieldsRoundTripSanitizeAndFollowHudSize() {
        ClientSettings s = new ClientSettings();
        s.teamUpgradesEnabled = true;
        s.teamUpgradesLayout = ClientSettings.TEAM_UPGRADES_FULL;
        s.teamUpgradesHudX = 30;
        s.teamUpgradesHudY = 40;
        s.teamUpgradesHudAnchor = 3;
        s.teamUpgradesHudScale = 1.5f;
        Gson gson = new Gson();
        ClientSettings back = gson.fromJson(gson.toJson(s), ClientSettings.class);
        back.sanitize();
        assertTrue(back.teamUpgradesEnabled);
        assertEquals(ClientSettings.TEAM_UPGRADES_FULL, back.teamUpgradesLayout);
        assertEquals(30, back.teamUpgradesHudX);
        assertEquals(40, back.teamUpgradesHudY);
        assertEquals(3, back.teamUpgradesHudAnchor);
        assertEquals(1.5f, back.teamUpgradesHudScale, 0f);

        ClientSettings bad = gson.fromJson("{\"teamUpgradesLayout\":7,\"teamUpgradesHudAnchor\":99,"
                + "\"teamUpgradesHudScale\":40.0}", ClientSettings.class);
        bad.sanitize();
        assertEquals(ClientSettings.TEAM_UPGRADES_COMPACT, bad.teamUpgradesLayout);
        assertEquals(8, bad.teamUpgradesHudAnchor);
        assertEquals(bad.defaultTextSizeScale(), bad.teamUpgradesHudScale, 0f);

        ClientSettings sized = new ClientSettings();
        sized.sanitize();
        sized.setHudSize(2);
        assertEquals(sized.heightLimitHudScale, sized.teamUpgradesHudScale, 0f);
        assertEquals(1.5f, sized.teamUpgradesHudScale, 0f);
    }
}
