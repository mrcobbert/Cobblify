package com.bedwarsqol.hud;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.gui.render.GuiTheme;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * PLAN Phase 4 (review I2): the HUD painters, the HUD font and the overlay placement read their settings through
 * {@link HudSettings#active}, so inside {@link HudSettings#with} the font choice, a measured width and the text scale
 * follow the copy even while the player's own settings say another font, and follow the player's again after.
 */
public class HudSettingsTest {

    private ClientSettings saved;
    private FakeClient client;
    private ClientSettings global;

    @Before
    public void setUp() throws Exception {
        saved = BedwarsQol.config;
        global = new ClientSettings();
        BedwarsQol.config = global;
        client = new FakeClient(1280, 720, 2); // GUI scale 2: two device px per GUI px
    }

    @After
    public void tearDown() throws Exception {
        client.close();
        BedwarsQol.config = saved;
    }

    private static ClientSettings font(String token) {
        ClientSettings c = new ClientSettings();
        c.guiFont = token;
        return c;
    }

    /** What the font reports right now: {minecraft ? 1 : 0, width of "Kills: 3" at 0.8, hudScale(0.3)}. */
    private static float[] measure() {
        return new float[]{BedwarsQolFont.minecraft() ? 1f : 0f,
                BedwarsQolFont.width("Kills: 3", 0.8f, BedwarsQolFont.Weight.REGULAR), BedwarsQolFont.hudScale(0.3f)};
    }

    @Test
    public void theScopeDecidesTheFontWhileTheGlobalSaysAnother() {
        global.guiFont = GuiTheme.FONT_MINECRAFT;
        float[] outside = measure();
        // Minecraft's font: 8 chars x 6 px at the whole step round(0.8 x 2) / 2 = 1.0; hudScale at least 1 device px
        assertEquals(1f, outside[0], 0f);
        assertEquals(48f, outside[1], 1e-4f);
        assertEquals(0.5f, outside[2], 1e-6f);

        ClientSettings copy = font(GuiTheme.FONT_MODERN);
        final AtomicReference<float[]> inside = new AtomicReference<float[]>();
        HudSettings.with(copy, () -> {
            assertSame(copy, HudSettings.active());
            inside.set(measure());
        });
        // the modern font: no snapping, measured at 0.8 exactly (its atlas is not loadable here, so the fallback
        // measures with the client's font at the unsnapped scale)
        assertEquals(0f, inside.get()[0], 0f);
        assertEquals(48f * 0.8f, inside.get()[1], 1e-4f);
        assertEquals(0.3f, inside.get()[2], 1e-6f);

        assertSame(global, HudSettings.active());
        assertEquals(outside[1], measure()[1], 0f);
        assertEquals(outside[2], measure()[2], 0f);
    }

    @Test
    public void andTheOtherWayRound() {
        global.guiFont = GuiTheme.FONT_MODERN;
        float[] outside = measure();
        assertEquals(0f, outside[0], 0f);
        final AtomicReference<float[]> inside = new AtomicReference<float[]>();
        HudSettings.with(font(GuiTheme.FONT_MINECRAFT), () -> inside.set(measure()));
        assertEquals(1f, inside.get()[0], 0f);
        assertNotEquals(outside[1], inside.get()[1], 1e-4f);
        assertEquals(0.5f, inside.get()[2], 1e-6f);
        assertEquals(0f, measure()[0], 0f);
    }

    @Test
    public void theGlobalIsBackWhenTheBodyThrows() {
        global.guiFont = GuiTheme.FONT_MINECRAFT;
        try {
            HudSettings.with(font(GuiTheme.FONT_MODERN), () -> {
                assertFalse(BedwarsQolFont.minecraft());
                throw new IllegalStateException("painter failed");
            });
            fail("the body's exception is not swallowed");
        } catch (IllegalStateException expected) {
            // ok
        }
        assertSame(global, HudSettings.active());
        assertTrue(BedwarsQolFont.minecraft());
        assertEquals(0.5f, BedwarsQolFont.hudScale(0.3f), 1e-6f);
    }

    @Test
    public void scopesNestAndOtherThreadsSeeTheGlobal() throws Exception {
        ClientSettings outer = font(GuiTheme.FONT_MODERN), inner = font(GuiTheme.FONT_MINECRAFT);
        final AtomicReference<ClientSettings> otherThread = new AtomicReference<ClientSettings>();
        HudSettings.with(outer, () -> {
            HudSettings.with(inner, () -> assertSame(inner, HudSettings.active()));
            assertSame(outer, HudSettings.active());
            Thread t = new Thread(() -> otherThread.set(HudSettings.active()));
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        assertSame(global, otherThread.get());
        assertSame(global, HudSettings.active());
    }

    @Test
    public void overlayScaleReadsTheActiveSettings() {
        global.scoreboardHudScale = 1.5f;
        global.scoreboardHudEnabled = true;
        ClientSettings copy = new ClientSettings();
        copy.scoreboardHudScale = 0.75f;
        copy.scoreboardHudEnabled = false;
        assertEquals(1.5f, OverlayScale.scoreboardScale(), 0f);
        HudSettings.with(copy, () -> {
            assertEquals(0.75f, OverlayScale.scoreboardScale(), 0f);
            assertFalse(OverlayScale.scoreboardShown());
        });
        assertTrue(OverlayScale.scoreboardShown());
    }

    // ------------------------------------------------------------------ scan

    /** No reader of the global settings under {@code com/bedwarsqol/hud} or in the HUD font, but the accessor. */
    @Test
    public void onlyTheAccessorReadsTheGlobalSettings() throws IOException {
        File main = mainRoot();
        List<File> files = new ArrayList<File>();
        collect(new File(main, "com/bedwarsqol/hud"), files);
        files.add(new File(main, "com/bedwarsqol/gui/render/BedwarsQolFont.java"));
        assertTrue(files.size() > 5);
        List<String> hits = new ArrayList<String>();
        for (File f : files) {
            if (f.getName().equals("HudSettings.java")) continue;
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String[] lines = src.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("BedwarsQol.config") || lines[i].matches("\\s*import\\s+com\\.bedwarsqol\\.BedwarsQol\\s*;\\s*")) {
                    hits.add(f.getName() + ":" + (i + 1));
                }
            }
        }
        assertTrue("global settings read outside HudSettings: " + hits, hits.isEmpty());
    }

    static File mainRoot() {
        for (String p : new String[]{"src/main/java", "ornithe/src/main/java"}) {
            File f = new File(p);
            if (new File(f, "com/bedwarsqol/hud/BedwarsHudRenderer.java").isFile()) return f;
        }
        throw new AssertionError("ornithe/src/main/java not found from " + new File(".").getAbsolutePath());
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collect(k, out);
            else if (k.getName().endsWith(".java")) out.add(k);
        }
    }
}
