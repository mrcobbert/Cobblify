package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.sheet.SheetSchema;
import com.google.gson.Gson;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Fullbright, Item Physics, 1.7 Animations, Particles and Motion Blur: Visuals modules of the Ornithe sheet (Forge
 * and Lunar do not list them; see SheetPlatformOnlyTest), all off by default with their rows at the agreed defaults,
 * also for a config saved before they existed; every row reads and writes its {@link ClientSettings} field.
 */
public class SettingsBindingsVisualsTest {

    private static final String[] TOGGLES = {"fullbright", "itemPhysics", "oldAnimations", "oaBlockHit", "oaItemPosition",
            "oaSmoothSneak", "oaHearts", "oaRedArmor", "particles", "ptAlwaysCrit", "ptHideOwnPotion", "ptHideBlockBreak",
            "motionBlur"};

    private String savedDir;
    private ClientSettings savedConfig;

    @Before
    public void isolate() throws Exception {
        savedDir = System.getProperty("cobblify.test.configDir");
        savedConfig = BedwarsQol.config;
        File dir = Files.createTempDirectory("cobblify-visuals-test").toFile();
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
    public void listedOnOrnithe() {
        SettingsBindings values = new SettingsBindings();
        for (String id : TOGGLES) assertTrue(id, SheetSchema.shows(values, id));
        assertTrue(SheetSchema.shows(values, "ptMultiplier"));
        assertTrue(SheetSchema.shows(values, "mbStrength"));
    }

    @Test
    public void defaultsAlsoForAConfigSavedBeforeThem() {
        ClientSettings empty = new Gson().fromJson("{}", ClientSettings.class);
        ClientSettings old = new Gson().fromJson("{\"suppressEscMenu\":true}", ClientSettings.class);
        old.sanitize();
        for (ClientSettings c : new ClientSettings[]{new ClientSettings(), empty, old}) {
            assertFalse(c.fullbright);
            assertFalse(c.itemPhysics);
            assertFalse(c.oldAnimations);
            assertTrue(c.oaBlockHit);
            assertTrue(c.oaItemPosition);
            assertTrue(c.oaSmoothSneak);
            assertTrue(c.oaHearts);
            assertTrue(c.oaRedArmor);
            assertFalse(c.particles);
            assertTrue(c.ptAlwaysCrit);
            assertTrue(c.ptHideOwnPotion);
            assertFalse(c.ptHideBlockBreak);
            assertEquals(2, c.ptMultiplier);
            assertFalse(c.motionBlur);
            assertEquals(5, c.mbStrength);
        }
        SettingsBindings values = new SettingsBindings();
        assertFalse(values.on("fullbright"));
        assertTrue(values.on("oaRedArmor"));
        assertEquals("2x", values.choice("ptMultiplier"));
        assertFalse(values.on("motionBlur"));
        assertEquals(5.0, values.number("mbStrength"), 0);
    }

    @Test
    public void everyToggleReadsAndWritesItsSetting() {
        SettingsBindings values = new SettingsBindings();
        for (String id : TOGGLES) {
            boolean before = values.on(id);
            values.setOn(id, !before);
            assertEquals(id, !before, values.on(id));
            assertEquals(id, !before, field(BedwarsQol.config, id));
            values.setOn(id, before);
            assertEquals(id, before, values.on(id));
            assertEquals(id, before, field(BedwarsQol.config, id));
        }
    }

    @Test
    public void multiplierReadsAndWritesOneToThree() {
        SettingsBindings values = new SettingsBindings();
        values.setChoice("ptMultiplier", "3x");
        assertEquals(3, BedwarsQol.config.ptMultiplier);
        assertEquals("3x", values.choice("ptMultiplier"));
        values.setChoice("ptMultiplier", "1x");
        assertEquals(1, BedwarsQol.config.ptMultiplier);
        assertEquals("1x", values.choice("ptMultiplier"));
        values.setChoice("ptMultiplier", "9x");
        assertEquals("an unknown option changes nothing", 1, BedwarsQol.config.ptMultiplier);
        BedwarsQol.config.ptMultiplier = 7;
        assertEquals("3x", values.choice("ptMultiplier"));
        BedwarsQol.config.ptMultiplier = 0;
        assertEquals("1x", values.choice("ptMultiplier"));
    }

    @Test
    public void sanitizeClampsTheMultiplier() {
        ClientSettings c = new Gson().fromJson("{\"ptMultiplier\":9}", ClientSettings.class);
        c.sanitize();
        assertEquals(3, c.ptMultiplier);
        c = new Gson().fromJson("{\"ptMultiplier\":-1}", ClientSettings.class);
        c.sanitize();
        assertEquals(1, c.ptMultiplier);
    }

    @Test
    public void motionBlurStrengthReadsAndWritesOneToNine() {
        SettingsBindings values = new SettingsBindings();
        values.setNumber("mbStrength", 9);
        assertEquals(9, BedwarsQol.config.mbStrength);
        assertEquals(9.0, values.number("mbStrength"), 0);
        values.setNumber("mbStrength", 1);
        assertEquals(1, BedwarsQol.config.mbStrength);
        assertEquals(1.0, values.number("mbStrength"), 0);
        values.setNumber("mbStrength", 6.4);
        assertEquals("rounds to the step", 6, BedwarsQol.config.mbStrength);
        values.setNumber("mbStrength", 40);
        assertEquals("held to 1..9", 9, BedwarsQol.config.mbStrength);
        values.setNumber("mbStrength", -3);
        assertEquals(1, BedwarsQol.config.mbStrength);
        BedwarsQol.config.mbStrength = 12;
        assertEquals(9.0, values.number("mbStrength"), 0);
    }

    @Test
    public void sanitizeClampsTheMotionBlurStrength() {
        ClientSettings c = new Gson().fromJson("{\"mbStrength\":0}", ClientSettings.class);
        c.sanitize();
        assertEquals(1, c.mbStrength);
        c = new Gson().fromJson("{\"mbStrength\":12}", ClientSettings.class);
        c.sanitize();
        assertEquals(9, c.mbStrength);
        c = new Gson().fromJson("{\"mbStrength\":7,\"motionBlur\":true}", ClientSettings.class);
        c.sanitize();
        assertEquals(7, c.mbStrength);
        assertTrue(c.motionBlur);
    }

    private static boolean field(ClientSettings c, String id) {
        try {
            return c.getClass().getField(id).getBoolean(c);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no ClientSettings field " + id, e);
        }
    }
}
