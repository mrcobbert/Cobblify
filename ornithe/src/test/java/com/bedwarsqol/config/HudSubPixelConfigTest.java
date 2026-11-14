package com.bedwarsqol.config;

import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.HudModuleState;
import com.bedwarsqol.hud.HudModules;
import com.google.gson.Gson;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Sub-pixel HUD positions: the int {@code ...HudX/Y} fields keep the rounded position (what an older build stores and
 * reads), and the remainder lives in {@link ClientSettings#hudSubPixel}, a map older builds ignore on read and drop on
 * save. A config without the map loads exactly where it was.
 */
public class HudSubPixelConfigTest {

    private static final Gson GSON = new Gson();
    private static final String POTION = BedwarsHudRenderer.POTION_HUD;
    private static final String KEYS = BedwarsHudRenderer.KEYSTROKES_HUD;

    /** A release/0.19.0 config: moved modules, no hudSubPixel. */
    private static final String OLD_JSON = "{\"settingsVersion\":5,\"potionStatusEnabled\":true,\"potionHudX\":37,"
            + "\"potionHudY\":51,\"potionHudAnchor\":0,\"potionHudScale\":1.0,\"keystrokesEnabled\":true,"
            + "\"keystrokesHudX\":-12,\"keystrokesHudY\":-30,\"keystrokesHudAnchor\":8,\"guiAccent\":\"red\"}";

    /** What an older build deserializes: the int fields only (Gson ignores every other key). */
    static final class OlderBuild {
        int potionHudX;
        int potionHudY;
        int potionHudAnchor;
        int keystrokesHudX;
        int keystrokesHudY;
        int keystrokesHudAnchor;
        String guiAccent;
    }

    private String savedDir;
    private File dir;

    @Before
    public void isolate() throws Exception {
        savedDir = System.getProperty("cobblify.test.configDir");
        dir = Files.createTempDirectory("cobblify-hud-subpixel-test").toFile();
        System.setProperty("cobblify.test.configDir", dir.getAbsolutePath());
    }

    @After
    public void restore() {
        if (savedDir == null) System.clearProperty("cobblify.test.configDir");
        else System.setProperty("cobblify.test.configDir", savedDir);
    }

    private static ClientSettings load(String json) {
        ClientSettings c = GSON.fromJson(json, ClientSettings.class);
        c.sanitize();
        return c;
    }

    @Test
    public void oldConfigLoadsWithTheSameIntsAndNoFraction() {
        ClientSettings c = load(OLD_JSON);
        assertNotNull(c.hudSubPixel);
        assertTrue(c.hudSubPixel.isEmpty());
        HudModuleState potion = HudModules.get(c, POTION);
        assertEquals(37, potion.x);
        assertEquals(51, potion.y);
        assertEquals(0, potion.anchor);
        assertEquals(0f, potion.fracX, 0f);
        assertEquals(0f, potion.fracY, 0f);
        HudModuleState keys = HudModules.get(c, KEYS);
        assertEquals(-12, keys.x);
        assertEquals(-30, keys.y);
        assertEquals(8, keys.anchor);
    }

    @Test
    public void fractionIsSavedBesideTheRoundedIntAndRoundTrips() {
        ClientSettings c = load(OLD_JSON);
        HudModules.set(c, POTION, HudModules.get(c, POTION).withExactPosition(10.4f, 51.75f, 0));
        assertEquals(10, c.potionHudX);
        assertEquals(52, c.potionHudY);
        assertEquals(0.4f, c.hudSubPixel.get(POTION)[0], 1e-6f);
        assertEquals(-0.25f, c.hudSubPixel.get(POTION)[1], 1e-6f);

        ClientSettings back = load(GSON.toJson(c));
        HudModuleState s = HudModules.get(back, POTION);
        assertEquals(10, s.x);
        assertEquals(52, s.y);
        assertEquals(10.4f, s.exactX(), 1e-5f);
        assertEquals(51.75f, s.exactY(), 1e-5f);
    }

    @Test
    public void aFractionAboveOneHalfRoundsTheIntUp() {
        ClientSettings c = new ClientSettings();
        HudModules.set(c, POTION, HudModules.get(c, POTION).withExactPosition(10.6f, 3f, 0));
        assertEquals(11, c.potionHudX);
        assertEquals(-0.4f, c.hudSubPixel.get(POTION)[0], 1e-6f);
        assertEquals(0f, c.hudSubPixel.get(POTION)[1], 0f);
        assertEquals(10.6f, HudModules.get(c, POTION).exactX(), 1e-5f);
    }

    @Test
    public void aWholePositionStoresNoEntry() {
        ClientSettings c = new ClientSettings();
        HudModules.set(c, POTION, HudModules.get(c, POTION).withExactPosition(10.4f, 3f, 0));
        assertTrue(c.hudSubPixel.containsKey(POTION));
        HudModules.set(c, POTION, HudModules.get(c, POTION).withExactPosition(12f, 3f, 0));
        assertFalse(c.hudSubPixel.containsKey(POTION));
        HudModules.set(c, POTION, HudModules.get(c, POTION).withExactPosition(10.4f, 3f, 0));
        HudModules.set(c, POTION, HudModules.get(c, POTION).withPosition(0, 0, -1)); // a reset
        assertFalse(c.hudSubPixel.containsKey(POTION));
        HudModules.set(c, "nonsense", new HudModuleState(1, 1, 0, 1f, true, true, 0.25f, 0f));
        assertFalse("only ids HudModules knows get an entry", c.hudSubPixel.containsKey("nonsense"));
    }

    @Test
    public void anOlderBuildReadsTheWholePartsAndDropsTheMap() {
        ClientSettings c = load(OLD_JSON);
        HudModules.set(c, POTION, HudModules.get(c, POTION).withExactPosition(37.333f, 50.6f, 0));
        HudModules.set(c, KEYS, HudModules.get(c, KEYS).withExactPosition(-12.5f, -30f, 8));
        String json = GSON.toJson(c);
        assertTrue(json, json.contains("\"hudSubPixel\""));

        OlderBuild old = GSON.fromJson(json, OlderBuild.class); // no JsonSyntaxException: the ints stay ints
        assertEquals(37, old.potionHudX);
        assertEquals(51, old.potionHudY);
        assertEquals(-12, old.keystrokesHudX);
        assertEquals(-30, old.keystrokesHudY);
        assertEquals(8, old.keystrokesHudAnchor);
        assertEquals("red", old.guiAccent);
        assertFalse("the older build's save has no map", GSON.toJson(old).contains("hudSubPixel"));

        ClientSettings again = load(GSON.toJson(old)); // and the new build reads that back at the whole spot
        assertEquals(37f, HudModules.get(again, POTION).exactX(), 0f);
        assertEquals(51f, HudModules.get(again, POTION).exactY(), 0f);
    }

    @Test
    public void badEntriesAreSanitizedAway() {
        ClientSettings nulled = load("{\"hudSubPixel\":null}");
        assertNotNull(nulled.hudSubPixel);
        assertTrue(nulled.hudSubPixel.isEmpty());

        ClientSettings bad = load("{\"hudSubPixel\":{\"potion\":[NaN,0],\"armor\":[0.9,0],\"inventory\":[1],"
                + "\"keystrokes\":[0.25,-0.5],\"heightlimit\":null,\"sessionstats\":[0,Infinity],"
                + "\"diamondtimer\":[0.5,0.1],\"someFutureItem\":[0.125,0]}}");
        Map<String, float[]> m = bad.hudSubPixel;
        assertFalse(m.containsKey("potion"));
        assertFalse(m.containsKey("armor"));
        assertFalse(m.containsKey("inventory"));
        assertFalse(m.containsKey("heightlimit"));
        assertFalse(m.containsKey("sessionstats"));
        assertTrue("a value at the edge of the range is kept", m.containsKey("diamondtimer"));
        assertTrue(m.containsKey("keystrokes"));
        assertTrue("an id a newer build wrote is kept", m.containsKey("someFutureItem"));
        assertEquals(0f, HudModules.get(bad, POTION).fracX, 0f);
        assertEquals(0.25f, HudModules.get(bad, KEYS).fracX, 0f);

        String json = GSON.toJson(bad); // Gson cannot write NaN; sanitize took it out first
        assertFalse(json, json.contains("NaN"));
    }

    @Test
    public void undoSnapshotKeepsFractions() {
        ClientSettings c = new ClientSettings();
        HudModules.set(c, POTION, HudModules.get(c, POTION).withExactPosition(20.25f, 7.75f, 0));
        Map<String, HudModuleState> before = HudModules.snapshot(c);
        HudModules.set(c, POTION, HudModules.get(c, POTION).withExactPosition(40f, 7f, 0));
        assertNull(c.hudSubPixel.get(POTION));
        HudModules.restore(c, before);
        assertEquals(20.25f, HudModules.get(c, POTION).exactX(), 0f);
        assertEquals(7.75f, HudModules.get(c, POTION).exactY(), 0f);
        assertEquals(before, HudModules.snapshot(c));
    }

    @Test
    public void settingsManagerSavesAndLoadsTheFraction() {
        ClientSettings c = new ClientSettings();
        HudModules.set(c, KEYS, HudModules.get(c, KEYS).withExactPosition(-12.5f, -30.25f, 8));
        SettingsManager.save(c);
        assertTrue(new File(dir, "cobblify.json").isFile());
        HudModuleState s = HudModules.get(SettingsManager.load(), KEYS);
        assertEquals(-12.5f, s.exactX(), 0f);
        assertEquals(-30.25f, s.exactY(), 0f);
        assertEquals(8, s.anchor);
    }
}
