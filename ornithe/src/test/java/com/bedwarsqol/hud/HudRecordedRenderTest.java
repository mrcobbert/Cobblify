package com.bedwarsqol.hud;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.KeyFade;
import com.bedwarsqol.feature.TeamUpgradeRows;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.rewind.HudCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * PLAN Phase 4, the entry point Phase 15 calls: {@link BedwarsHudRenderer#renderRecorded} runs the HUD's draw with the
 * given source and the given settings in effect, and leaves the live source and the player's settings in place after,
 * also when a painter throws. A fake source that has nothing to show keeps the draw clear of GL.
 */
public class HudRecordedRenderTest {

    /** A source with nothing to show that notes what was asked and what was in effect then. */
    static class Fake implements HudSource {
        final List<String> asked = new ArrayList<String>();
        ClientSettings activeWhenAsked;
        Boolean minecraftFontWhenAsked;
        RuntimeException failWith;

        private void note(String what) {
            asked.add(what);
            activeWhenAsked = HudSettings.active();
            minecraftFontWhenAsked = BedwarsQolFont.minecraft();
            if (failWith != null) throw failWith;
        }

        @Override public boolean shows(String id) { asked.add("shows " + id); return true; }
        @Override public boolean inGame() { note("inGame"); return true; }
        @Override public boolean heightLimitVisible(ClientSettings cfg, boolean preview) { note("heightLimitVisible"); return false; }
        @Override public boolean sessionVisible(ClientSettings cfg, boolean preview) { note("sessionVisible"); return false; }
        @Override public List<BedwarsHudRenderer.PotionEntry> potionEntries(Minecraft mc) { note("potionEntries"); return Collections.emptyList(); }
        @Override public ItemStack currentLeggings(Minecraft mc) { note("currentLeggings"); return null; }
        @Override public ItemStack[] miniInventorySlots(Minecraft mc) { note("miniInventorySlots"); return new ItemStack[27]; }
        @Override public int timerValue(boolean diamond) { note("timerValue"); return -1; }
        @Override public List<String> heightLimitRows(Minecraft mc) { note("heightLimitRows"); return Collections.emptyList(); }
        @Override public List<TeamUpgradeRows.Row> teamUpgradesRows(TeamUpgradeRows.Layout layout) { note("teamUpgradesRows"); return Collections.emptyList(); }
        @Override public List<BedwarsHudRenderer.Line> sessionLines() { note("sessionLines"); return Collections.emptyList(); }
        @Override public List<TextModules.Seg> textRuns(Minecraft mc, String id) { note("textRuns"); return Collections.emptyList(); }
        @Override public boolean[] keyStates(Minecraft mc) { note("keyStates"); return new boolean[7]; }
        @Override public KeyFade keyFade(int cap) { return new KeyFade(120); }
        @Override public long now() { return 0L; }
    }

    private ClientSettings saved;
    private ClientSettings global;

    @Before
    public void setUp() {
        saved = BedwarsQol.config;
        global = new ClientSettings();
        global.guiFont = GuiTheme.FONT_MINECRAFT;
        BedwarsQol.config = global;
    }

    @After
    public void tearDown() {
        BedwarsQol.config = saved;
        HudCapture.shared().setSink(null);
    }

    /** Recorded settings: the modern font, only items whose draw stops before GL when there is nothing to show. */
    private static ClientSettings recorded() {
        ClientSettings c = new ClientSettings();
        c.guiFont = GuiTheme.FONT_MODERN;
        c.potionStatusEnabled = true;
        c.potionInGameOnly = true;
        c.armorTypeEnabled = false;
        c.inventoryHudEnabled = false;
        c.genTimersEnabled = false;
        c.keystrokesEnabled = false;
        c.teamUpgradesEnabled = true;
        c.heightLimitEnabled = true;
        for (String id : new String[]{BedwarsHudRenderer.FPS_HUD, BedwarsHudRenderer.CPS_HUD, BedwarsHudRenderer.PING_HUD}) {
            HudModuleState s = HudModules.get(c, id);
            HudModules.set(c, id, new HudModuleState(s.x, s.y, s.anchor, s.scale, false));
        }
        return c;
    }

    @Test
    public void drawsFromTheGivenSourceWithTheGivenSettings() {
        ClientSettings rec = recorded();
        Fake fake = new Fake();
        BedwarsHudRenderer.renderRecorded(null, rec, fake);
        assertTrue(fake.asked.toString(), fake.asked.contains("inGame"));          // Potion's In Game Only
        assertTrue(fake.asked.toString(), fake.asked.contains("potionEntries"));
        assertTrue(fake.asked.toString(), fake.asked.contains("heightLimitVisible"));
        assertTrue(fake.asked.toString(), fake.asked.contains("teamUpgradesRows"));
        assertTrue(fake.asked.toString(), fake.asked.contains("shows " + BedwarsHudRenderer.PING_HUD));
        assertSame("the recorded settings are in effect while it draws", rec, fake.activeWhenAsked);
        assertFalse("and so is their font", fake.minecraftFontWhenAsked);
        // after: the live source and the player's settings
        assertSame(LiveHudSource.INSTANCE, BedwarsHudRenderer.source());
        assertSame(global, HudSettings.active());
        assertTrue(BedwarsQolFont.minecraft());

        Fake post = new Fake();
        BedwarsHudRenderer.renderRecordedPost(null, rec, post);
        assertTrue(post.asked.toString(), post.asked.contains("sessionVisible"));
        assertSame(rec, post.activeWhenAsked);
        assertSame(LiveHudSource.INSTANCE, BedwarsHudRenderer.source());
    }

    @Test
    public void theLiveSourceAndSettingsAreBackWhenAPainterThrows() {
        Fake fake = new Fake();
        fake.failWith = new IllegalStateException("source failed");
        try {
            BedwarsHudRenderer.renderRecorded(null, recorded(), fake);
            fail("the failure reaches the caller");
        } catch (IllegalStateException expected) {
            // ok
        }
        assertSame(LiveHudSource.INSTANCE, BedwarsHudRenderer.source());
        assertSame(global, HudSettings.active());
    }

    /**
     * Keystrokes, drawn from a recording at a stored spot: the draw passes the point where a live draw hands the
     * recorder its on-screen flag (the source is asked for the held keys right after it), and nothing is handed.
     */
    @Test
    public void aRecordingBeingDrawnHandsNothingToTheRecorder() throws Exception {
        final int[] records = {0};
        HudCapture.shared().setSink(new HudCapture.Sink() {
            @Override
            public boolean append(int tag, byte[] buf, int len) {
                records[0]++;
                return true;
            }
        });
        HudCapture.shared().tickHead();
        HudCapture.shared().frame();
        ClientSettings rec = recorded();
        rec.potionStatusEnabled = false;
        rec.teamUpgradesEnabled = false;
        rec.heightLimitEnabled = false;
        rec.keystrokesEnabled = true;
        rec.keystrokesInGameOnly = true;
        rec.keystrokesHudAnchor = 0; // a stored spot: no default layout (which measures example items)
        Fake fake = new Fake() {
            @Override
            public boolean[] keyStates(Minecraft mc) {
                asked.add("keyStates");
                throw new IllegalStateException("stop before GL");
            }
        };
        try (FakeClient client = new FakeClient(1280, 720, 2)) {
            BedwarsHudRenderer.renderRecorded(client.mc, rec, fake);
            fail("stopped at the held keys");
        } catch (IllegalStateException expected) {
            assertEquals("stop before GL", expected.getMessage());
        }
        assertTrue(fake.asked.toString(), fake.asked.contains("keyStates"));
        assertEquals(0, records[0]);
    }

    @Test
    public void nothingIsDrawnWithoutSettingsOrSource() {
        Fake fake = new Fake();
        BedwarsHudRenderer.renderRecorded(null, null, fake);
        BedwarsHudRenderer.renderRecorded(null, recorded(), null);
        assertTrue(fake.asked.isEmpty());
    }
}
