package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.KeyFade;
import com.bedwarsqol.feature.TeamUpgradeRows;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.HudModel;
import com.bedwarsqol.hud.HudModuleState;
import com.bedwarsqol.hud.HudModules;
import com.bedwarsqol.hud.HudPlacement;
import com.bedwarsqol.hud.HudSettings;
import com.bedwarsqol.hud.HudSource;
import com.bedwarsqol.hud.RecordedHudSource;
import com.bedwarsqol.hud.TextModules;
import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.HudCapture;
import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.item.ItemStack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Phase 15 (TASK D5, A11's Keystrokes part; PLAN "Cobblify HUD as recorded"): in your view of a v2 recording the
 * Cobblify HUD is drawn by the live painters ({@link BedwarsHudRenderer#renderRecorded}) from a
 * {@link RecordedHudSource}: the recorded values, in the recorded settings (place, size, font, accent, background),
 * hidden while the recorded Tab list or F3 hid it, never in the free camera, another player's view or the clean view,
 * and never for a v1 recording. Keystrokes flashes on TAP records, a click shorter than a frame included. Nothing
 * reaches a live tracker or the recorder.
 *
 * <p>Painters that draw are replaced by a spy (no GL here); the font test runs the real entry point with a source
 * that shows nothing, so the draw stops before GL. All HUD text below is synthetic.
 */
public class HudPlaybackTest {

    private static final long MS = 1_000L;
    private static final int LMB = 5, RMB = 6;

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

    // ------------------------------------------------------------------ fixtures

    private static int hk(int item, int field) {
        return RewindFormat.settingHudKey(item, field);
    }

    /** A v2 recording's extension records in segment 0 (synthetic). */
    private static final class Rec {
        private final ExtCodec codec = new ExtCodec();
        private final ExtRecords.Builder b = new ExtRecords.Builder();

        /** SETTINGS: key, value pairs (Integer, Boolean, Float or String values). */
        Rec settings(long us, Object... kv) {
            codec.beginSettings();
            for (int i = 0; i < kv.length; i += 2) {
                int key = (Integer) kv[i];
                Object v = kv[i + 1];
                if (v instanceof String) codec.settingString(key, (String) v);
                else if (v instanceof Float) codec.settingFloat(key, (Float) v);
                else if (v instanceof Boolean) codec.settingInt(key, (Boolean) v ? 1 : 0);
                else codec.settingInt(key, (Integer) v);
            }
            b.addEncoded(0, us, RewindFormat.TAG_SETTINGS, codec.buffer(), codec.endSettings());
            return this;
        }

        /** The settings every draw needs: the modern font and a few HUD items. */
        Rec baseSettings(long us) {
            return settings(us, RewindFormat.SETTING_GUI_FONT, GuiTheme.FONT_MODERN,
                    RewindFormat.SETTING_GUI_ACCENT, "pale-green");
        }

        private Rec hud(long us, int n) {
            b.addEncoded(0, us, RewindFormat.TAG_HUD, codec.buffer(), n);
            return this;
        }

        Rec shown(long us, int module) {
            return hud(us, codec.beginHud(module, RewindFormat.HUD_FLAG_ON_SCREEN).end());
        }

        Rec off(long us, int module) {
            return hud(us, codec.beginHud(module, 0).end());
        }

        Rec number(long us, int module, int a) {
            return hud(us, codec.beginHud(module, RewindFormat.HUD_FLAG_ON_SCREEN).putVarInt(a).end());
        }

        Rec cps(long us, int left, int right) {
            return hud(us, codec.beginHud(RewindFormat.HUD_CPS, RewindFormat.HUD_FLAG_ON_SCREEN).putVarInt(left)
                    .putVarInt(right).end());
        }

        Rec ping(long us, String text) {
            return hud(us, codec.beginHud(RewindFormat.HUD_PING, RewindFormat.HUD_FLAG_ON_SCREEN).putString(text).end());
        }

        Rec rows(long us, String... rows) {
            codec.beginHud(RewindFormat.HUD_HEIGHT_LIMIT, RewindFormat.HUD_FLAG_ON_SCREEN);
            HudModel.putRows(codec, rows, rows.length);
            return hud(us, codec.end());
        }

        Rec lines(long us, String[] text, int[] kinds) {
            codec.beginHud(RewindFormat.HUD_SESSION_STATS, RewindFormat.HUD_FLAG_ON_SCREEN);
            HudModel.putLines(codec, text, kinds, text.length);
            return hud(us, codec.end());
        }

        Rec team(long us, TeamUpgradeRows.Row... rows) {
            codec.beginHud(RewindFormat.HUD_TEAM_UPGRADES, RewindFormat.HUD_FLAG_ON_SCREEN);
            HudModel.putTeamRows(codec, rows, rows.length);
            return hud(us, codec.end());
        }

        Rec input(long us, int keys) {
            b.addEncoded(0, us, RewindFormat.TAG_INPUT, codec.buffer(), codec.input(keys));
            return this;
        }

        ExtTrack track() {
            return ExtTrack.of(b.build());
        }
    }

    /** What the painters were handed. */
    private static class Spy implements HudPlayback.Painters {
        int texts, posts;
        ClientSettings settings;
        HudSource source;
        RuntimeException failWith;

        @Override
        public void text(Minecraft mc, ClientSettings s, HudSource src) {
            texts++;
            settings = s;
            source = src;
            if (failWith != null) throw failWith;
        }

        @Override
        public void post(Minecraft mc, ClientSettings s, HudSource src) {
            posts++;
            settings = s;
            source = src;
        }
    }

    private static boolean drawn(HudPlayback hp, Spy spy, ViewState v, ExtTrack t, long us) {
        int before = spy.texts;
        hp.draw(null, false, v, t, 0, us);
        return spy.texts > before;
    }

    private static String text(List<TextModules.Seg> runs) {
        StringBuilder b = new StringBuilder();
        for (TextModules.Seg s : runs) b.append(s.text).append('|').append(s.role).append(';');
        return b.toString();
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static KeyFade liveFade(int cap) throws Exception {
        Field f = BedwarsHudRenderer.class.getDeclaredField("KS_FADES");
        f.setAccessible(true);
        return ((KeyFade[]) f.get(null))[cap];
    }

    private static byte[] tapBody(int button) {
        ExtCodec c = new ExtCodec();
        return ExtRecords.body(c.buffer(), c.tap(button));
    }

    private static float capLevel(RecordedHudSource src, int cap) {
        return src.keyFade(cap).level(src.keyStates(null)[cap], src.now());
    }

    // ------------------------------------------------------------------ D5: recorded values

    /** Every item that was on screen draws with the recorded values; one that went off screen does not. */
    @Test
    public void everyItemThatWasOnScreenIsDrawnWithItsRecordedValues() throws Exception {
        TeamUpgradeRows.Row trap = new TeamUpgradeRows.Row(TeamUpgradeRows.Kind.TRAP, "1 Example Trap", "", "",
                TeamUpgradeRows.Style.NORMAL, 1f);
        ExtTrack t = new Rec().baseSettings(10 * MS)
                .number(100 * MS, RewindFormat.HUD_FPS, 144)
                .cps(100 * MS, 7, 3)
                .ping(100 * MS, "27")
                .number(100 * MS, RewindFormat.HUD_DIAMOND_TIMER, 23)
                .number(100 * MS, RewindFormat.HUD_EMERALD_TIMER, -1)
                .rows(100 * MS, "Map: Example", "Height Limit: 100", "Distance: 7")
                .lines(100 * MS, new String[]{"Game", "Kills: 3"}, new int[]{HudModel.LINE_HEADER, HudModel.LINE_ROW})
                .team(100 * MS, trap)
                .shown(100 * MS, RewindFormat.HUD_POTION)
                .shown(100 * MS, RewindFormat.HUD_ARMOR)
                .shown(100 * MS, RewindFormat.HUD_INVENTORY)
                .shown(100 * MS, RewindFormat.HUD_KEYSTROKES)
                .off(150 * MS, RewindFormat.HUD_EMERALD_TIMER)
                .number(300 * MS, RewindFormat.HUD_FPS, 120)
                .track();
        Spy spy = new Spy();
        HudPlayback hp = new HudPlayback(spy);
        ViewState v = new ViewState();

        assertTrue(drawn(hp, spy, v, t, 50 * MS));
        RecordedHudSource src = (RecordedHudSource) spy.source;
        assertSame(hp.source(), src);
        assertFalse("nothing on screen before its first record", src.shows(BedwarsHudRenderer.FPS_HUD));

        assertTrue(drawn(hp, spy, v, t, 120 * MS));
        for (String id : new String[]{BedwarsHudRenderer.FPS_HUD, BedwarsHudRenderer.CPS_HUD, BedwarsHudRenderer.PING_HUD,
                BedwarsHudRenderer.DIAMOND_TIMER_HUD, BedwarsHudRenderer.EMERALD_TIMER_HUD,
                BedwarsHudRenderer.HEIGHT_LIMIT_HUD, BedwarsHudRenderer.SESSION_HUD, BedwarsHudRenderer.TEAM_UPGRADES_HUD,
                BedwarsHudRenderer.POTION_HUD, BedwarsHudRenderer.ARMOR_HUD, BedwarsHudRenderer.INVENTORY_HUD,
                BedwarsHudRenderer.KEYSTROKES_HUD}) {
            assertTrue(id + " was on screen", src.shows(id));
        }
        assertEquals(text(TextModules.fps(144)), text(src.textRuns(null, BedwarsHudRenderer.FPS_HUD)));
        assertEquals(text(TextModules.cps(7, 3)), text(src.textRuns(null, BedwarsHudRenderer.CPS_HUD)));
        assertEquals(text(TextModules.ping("27")), text(src.textRuns(null, BedwarsHudRenderer.PING_HUD)));
        assertEquals(23, src.timerValue(true));
        assertEquals(-1, src.timerValue(false));
        assertEquals(Arrays.asList("Map: Example", "Height Limit: 100", "Distance: 7"), src.heightLimitRows(null));
        List<?> lines = src.sessionLines();
        assertEquals(2, lines.size());
        assertEquals("Game", field(lines.get(0), "primary"));
        assertEquals("a header draws in the header weight", BedwarsQolFont.Weight.BOLD, field(lines.get(0), "weight"));
        assertEquals("Kills: 3", field(lines.get(1), "primary"));
        assertEquals(BedwarsQolFont.Weight.REGULAR, field(lines.get(1), "weight"));
        List<TeamUpgradeRows.Row> team = src.teamUpgradesRows(TeamUpgradeRows.Layout.COMPACT);
        assertEquals(1, team.size());
        assertTrue(HudModel.sameRow(trap, team.get(0)));
        assertTrue("In Game Only: it drew in the game", src.inGame());
        assertTrue(src.heightLimitVisible(spy.settings, false));
        assertTrue(src.sessionVisible(spy.settings, false));

        assertTrue(drawn(hp, spy, v, t, 200 * MS));
        assertFalse("the emerald timer went off screen", src.shows(BedwarsHudRenderer.EMERALD_TIMER_HUD));
        assertTrue(drawn(hp, spy, v, t, 310 * MS));
        assertEquals("a later record", text(TextModules.fps(120)), text(src.textRuns(null, BedwarsHudRenderer.FPS_HUD)));

        hp.draw(null, true, v, t, 0, 310 * MS);
        assertEquals("the post pass (Session Stats over everything)", 1, spy.posts);
        assertSame(src, spy.source);
    }

    /** Potion, Armor and Inventory recompute from the camera: no player, nothing to show (the source asks no tracker). */
    @Test
    public void potionArmorAndInventoryComeFromTheCamera() {
        RecordedHudSource src = new RecordedHudSource();
        assertTrue(src.potionEntries(null).isEmpty());
        assertNull(src.currentLeggings(null));
        ItemStack[] slots = src.miniInventorySlots(null);
        assertEquals(27, slots.length);
        for (ItemStack s : slots) assertNull(s);
    }

    /**
     * The recorded place, size, background, options and accent are in effect, whatever today's settings say; a
     * setting changed mid-game is followed from its record on, the others keep their values.
     */
    @Test
    public void theRecordedPlaceSizeAccentAndBackgroundAreInEffectWhateverTodaysSettingsSay() {
        global.fpsHudX = 7;
        global.fpsHudAnchor = 0;
        global.guiAccent = "red";
        global.teamUpgradesLayout = ClientSettings.TEAM_UPGRADES_COMPACT;
        int fps = RewindFormat.HUD_FPS;
        ExtTrack t = new Rec()
                .settings(10 * MS, RewindFormat.SETTING_GUI_FONT, GuiTheme.FONT_MODERN,
                        RewindFormat.SETTING_GUI_ACCENT, "pale-green",
                        hk(fps, RewindFormat.HUD_FIELD_ENABLED), true,
                        hk(fps, RewindFormat.HUD_FIELD_X), -30,
                        hk(fps, RewindFormat.HUD_FIELD_Y), 12,
                        hk(fps, RewindFormat.HUD_FIELD_ANCHOR), 2,
                        hk(fps, RewindFormat.HUD_FIELD_SCALE), 1.5f,
                        hk(fps, RewindFormat.HUD_FIELD_BACKGROUND), false,
                        hk(fps, RewindFormat.HUD_FIELD_SUB_X), 0.25f,
                        hk(fps, RewindFormat.HUD_FIELD_SUB_Y), 0f,
                        hk(fps, RewindFormat.HUD_FIELD_OPTION_A), true,
                        hk(fps, RewindFormat.HUD_FIELD_OPTION_B), 1,
                        hk(RewindFormat.HUD_POTION, RewindFormat.HUD_FIELD_IN_GAME_ONLY), true,
                        hk(RewindFormat.HUD_TEAM_UPGRADES, RewindFormat.HUD_FIELD_OPTION_A),
                        ClientSettings.TEAM_UPGRADES_FULL,
                        hk(RewindFormat.HUD_DIAMOND_TIMER, RewindFormat.HUD_FIELD_BACKGROUND), false,
                        hk(RewindFormat.HUD_EMERALD_TIMER, RewindFormat.HUD_FIELD_BACKGROUND), false,
                        hk(RewindFormat.HUD_KEYSTROKES, RewindFormat.HUD_FIELD_ANCHOR), HudPlacement.AUTO)
                .number(20 * MS, fps, 60)
                .settings(500 * MS, hk(fps, RewindFormat.HUD_FIELD_X), -40)
                .track();
        Spy spy = new Spy();
        HudPlayback hp = new HudPlayback(spy);
        assertTrue(drawn(hp, spy, new ViewState(), t, 100 * MS));
        ClientSettings c = spy.settings;
        assertNotNull(c);
        assertTrue("a copy, never the player's", c != global);
        assertEquals(GuiTheme.FONT_MODERN, c.guiFont);
        assertEquals("pale-green", c.guiAccent);
        HudModuleState s = HudModules.get(c, BedwarsHudRenderer.FPS_HUD);
        assertEquals(-30, s.x);
        assertEquals(12, s.y);
        assertEquals(2, s.anchor);
        assertEquals(1.5f, s.scale, 0f);
        assertTrue(s.enabled);
        assertFalse("its Background was off", s.background);
        assertEquals(0.25f, s.fracX, 0f);
        assertTrue(HudModules.brackets(c, BedwarsHudRenderer.FPS_HUD));
        assertEquals(TextModules.Label.of(1), HudModules.label(c, BedwarsHudRenderer.FPS_HUD));
        assertTrue(c.potionInGameOnly);
        assertEquals(ClientSettings.TEAM_UPGRADES_FULL, c.teamUpgradesLayout);
        assertFalse(HudModules.background(c, BedwarsHudRenderer.DIAMOND_TIMER_HUD));
        assertFalse(HudModules.background(c, BedwarsHudRenderer.EMERALD_TIMER_HUD));
        assertEquals(HudPlacement.AUTO, c.keystrokesHudAnchor);

        assertTrue(drawn(hp, spy, new ViewState(), t, 600 * MS));
        assertEquals("the mid-game change", -40, spy.settings.fpsHudX);
        assertEquals("the others kept", 12, spy.settings.fpsHudY);
        assertEquals(GuiTheme.FONT_MODERN, spy.settings.guiFont);

        // today's settings untouched
        assertEquals(7, global.fpsHudX);
        assertEquals(0, global.fpsHudAnchor);
        assertEquals("red", global.guiAccent);
        assertEquals(GuiTheme.FONT_MINECRAFT, global.guiFont);
        assertSame(global, HudSettings.active());
    }

    /** No SETTINGS record (not a v2 HUD recording): nothing to draw with. */
    @Test
    public void nothingIsDrawnBeforeTheSettingsSnapshot() {
        ExtTrack t = new Rec().number(20 * MS, RewindFormat.HUD_FPS, 60).track();
        Spy spy = new Spy();
        assertFalse(drawn(new HudPlayback(spy), spy, new ViewState(), t, 100 * MS));
    }

    /**
     * PLAN Phase 15 (review I2): a recording made with the modern font, watched with Minecraft's font set today,
     * draws and measures with the modern font, and today's font is back after the draw.
     */
    @Test
    public void recordedFontModernWithTheLiveSettingMinecraftDrawsAndMeasuresWithModern() throws Exception {
        final float[] modern;
        final float[] minecraft;
        try (Client client = new Client(1280, 720, 2)) {
            global.guiFont = GuiTheme.FONT_MODERN;
            modern = measure();
            global.guiFont = GuiTheme.FONT_MINECRAFT;
            minecraft = measure();
            assertNotEquals("the two fonts measure differently here", modern[1], minecraft[1], 1e-4f);

            ExtTrack t = new Rec().baseSettings(10 * MS).number(20 * MS, RewindFormat.HUD_FPS, 60).track();
            final List<float[]> seen = new ArrayList<float[]>();
            final List<ClientSettings> active = new ArrayList<ClientSettings>();
            HudPlayback hp = new HudPlayback(new HudPlayback.Painters() {
                @Override
                public void text(Minecraft mc, ClientSettings s, HudSource src) {
                    // the real entry point, with a source that shows nothing (the draw stops before GL)
                    BedwarsHudRenderer.renderRecorded(mc, s, new ShowsNothing(src) {
                        @Override
                        public boolean shows(String id) {
                            seen.add(measure());
                            active.add(HudSettings.active());
                            return false;
                        }
                    });
                }

                @Override
                public void post(Minecraft mc, ClientSettings s, HudSource src) {
                }
            });
            assertTrue(hp.draw(client.mc, false, new ViewState(), t, 0, 100 * MS));
            assertFalse("the painters asked", seen.isEmpty());
            for (float[] m : seen) {
                assertEquals("draws with the modern font", 0f, m[0], 0f);
                assertEquals("measures with the modern font", modern[1], m[1], 1e-4f);
                assertEquals("scales as the modern font", modern[2], m[2], 1e-6f);
            }
            assertEquals(GuiTheme.FONT_MODERN, active.get(0).guiFont);
            assertTrue(active.get(0) != global);
            assertSame("today's settings after", global, HudSettings.active());
            float[] after = measure();
            assertEquals(1f, after[0], 0f);
            assertEquals(minecraft[1], after[1], 0f);
        }
    }

    /** {minecraft ? 1 : 0, width of "Kills: 3" at 0.8, hudScale(0.3)} with the settings in effect now. */
    private static float[] measure() {
        return new float[]{BedwarsQolFont.minecraft() ? 1f : 0f,
                BedwarsQolFont.width("Kills: 3", 0.8f, BedwarsQolFont.Weight.REGULAR), BedwarsQolFont.hudScale(0.3f)};
    }

    // ------------------------------------------------------------------ D5: when it shows

    @Test
    public void hiddenWhileTheRecordedTabListOrF3HidItAndShownAgainUnderF5() {
        ExtTrack t = new Rec().baseSettings(10 * MS).number(20 * MS, RewindFormat.HUD_FPS, 60).track();
        Spy spy = new Spy();
        HudPlayback hp = new HudPlayback(spy);
        ViewState v = new ViewState();
        assertTrue(drawn(hp, spy, v, t, 100 * MS));
        v.recorded(0, RewindFormat.VIEW_TAB, RewindFormat.SCREEN_NONE);
        assertFalse("the recorded Tab list", drawn(hp, spy, v, t, 100 * MS));
        v.recorded(0, RewindFormat.VIEW_F3, RewindFormat.SCREEN_NONE);
        assertFalse("F3", drawn(hp, spy, v, t, 100 * MS));
        hp.draw(null, true, v, t, 0, 100 * MS);
        assertEquals("the post pass hides too", 0, spy.posts);
        v.cyclePerspective();
        assertTrue("F5 overrides the recorded view", drawn(hp, spy, v, t, 100 * MS));
        v.selectYou();
        v.recorded(0, 0, RewindFormat.SCREEN_CHEST);
        assertTrue("under a recorded window, as live", drawn(hp, spy, v, t, 100 * MS));
    }

    @Test
    public void notDrawnInTheFreeCameraAnotherPlayersViewOrTheCleanView() {
        ExtTrack t = new Rec().baseSettings(10 * MS).number(20 * MS, RewindFormat.HUD_FPS, 60).track();
        Spy spy = new Spy();
        HudPlayback hp = new HudPlayback(spy);
        ViewState v = new ViewState();
        v.selectFree();
        assertFalse("free camera", drawn(hp, spy, v, t, 100 * MS));
        v.ride(12);
        assertFalse("another player's view", drawn(hp, spy, v, t, 100 * MS));
        v.selectYou();
        v.toggleClean();
        assertFalse("clean view", drawn(hp, spy, v, t, 100 * MS));
        v.toggleClean();
        assertTrue("the next press shows it again", drawn(hp, spy, v, t, 100 * MS));
        assertEquals(1, spy.texts);
    }

    @Test
    public void aV1RecordingDrawsNoCobblifyHud() {
        Spy spy = new Spy();
        HudPlayback hp = new HudPlayback(spy);
        assertFalse(drawn(hp, spy, new ViewState(), ExtTrack.EMPTY, 100 * MS));
        assertFalse("v2 without HUD records", drawn(hp, spy, new ViewState(),
                new Rec().baseSettings(10 * MS).input(20 * MS, 0).track(), 100 * MS));
        assertFalse(drawn(hp, spy, new ViewState(), null, 100 * MS));
        assertEquals(0, spy.texts);
    }

    /** A painter that throws does not reach the game; the next frame draws again. */
    @Test
    public void aPainterFailureStaysInsidePlayback() {
        ExtTrack t = new Rec().baseSettings(10 * MS).number(20 * MS, RewindFormat.HUD_FPS, 60).track();
        Spy spy = new Spy();
        spy.failWith = new IllegalStateException("painter failed");
        HudPlayback hp = new HudPlayback(spy);
        assertTrue(drawn(hp, spy, new ViewState(), t, 100 * MS));
        assertTrue(drawn(hp, spy, new ViewState(), t, 120 * MS));
        assertEquals(2, spy.texts);
        assertSame(global, HudSettings.active());
    }

    // ------------------------------------------------------------------ A11 / D5: Keystrokes

    /**
     * Review round 2 I1: frames at 100 and 116 ms, a press at 105 and release at 110, the event processed at 115:
     * INPUT never saw it held, TAP@115 is the only trace. The LMB cap is lit at 116 and dark at 236 (120 ms fade, as
     * the live one).
     */
    @Test
    public void keystrokesFlashOnATapShorterThanAFrame() throws Exception {
        ExtTrack t = new Rec().baseSettings(10 * MS).shown(20 * MS, RewindFormat.HUD_KEYSTROKES).input(20 * MS, 0)
                .input(300 * MS, RewindFormat.INPUT_LMB | RewindFormat.INPUT_W).input(400 * MS, 0).track();
        Spy spy = new Spy();
        HudPlayback hp = new HudPlayback(spy);
        ViewState v = new ViewState();
        RecordedHudSource src = hp.source();
        assertTrue(drawn(hp, spy, v, t, 100 * MS));
        assertEquals(0f, capLevel(src, LMB), 0f);

        byte[] tap = tapBody(RewindFormat.TAP_LMB);
        hp.timed(RewindFormat.TAG_TAP, 115 * MS, tap, 0, tap.length);
        assertTrue(drawn(hp, spy, v, t, 116 * MS));
        assertFalse("INPUT says up", src.keyStates(null)[LMB]);
        assertTrue("lit at 116: " + capLevel(src, LMB), capLevel(src, LMB) > 0.99f);
        assertEquals("the right button did not flash", 0f, capLevel(src, RMB), 0f);
        assertTrue(drawn(hp, spy, v, t, 236 * MS));
        assertEquals("dark at 236", 0f, capLevel(src, LMB), 0f);

        assertTrue(drawn(hp, spy, v, t, 310 * MS));
        boolean[] held = src.keyStates(null);
        assertTrue("INPUT held W", held[0]);
        assertTrue("INPUT held LMB", held[LMB]);
        assertFalse(held[RMB]);
        assertEquals(1f, capLevel(src, LMB), 0f);
        assertEquals("playback time in ms", 310L, src.now());

        assertEquals("the recorded fade lasts as long as the live one", field(liveFade(0), "fadeMs"),
                field(src.keyFade(0), "fadeMs"));
        assertTrue("never the live fades", src.keyFade(LMB) != liveFade(LMB));
    }

    /** A seek back (a restart, or playback time moving back) forgets the flashes; a flash never outlives its time. */
    @Test
    public void aBackwardSeekForgetsTheFlashes() {
        ExtTrack t = new Rec().baseSettings(10 * MS).shown(20 * MS, RewindFormat.HUD_KEYSTROKES).input(20 * MS, 0)
                .track();
        Spy spy = new Spy();
        HudPlayback hp = new HudPlayback(spy);
        ViewState v = new ViewState();
        RecordedHudSource src = hp.source();
        byte[] tap = tapBody(RewindFormat.TAP_RMB);

        hp.timed(RewindFormat.TAG_TAP, 400 * MS, tap, 0, tap.length);
        assertTrue(drawn(hp, spy, v, t, 401 * MS));
        assertTrue(capLevel(src, RMB) > 0.99f);
        assertTrue(drawn(hp, spy, v, t, 300 * MS));
        assertEquals("time moved back: no flash from the future", 0f, capLevel(src, RMB), 0f);

        hp.timed(RewindFormat.TAG_TAP, 400 * MS, tap, 0, tap.length);
        hp.restart();
        assertTrue(drawn(hp, spy, v, t, 401 * MS));
        assertEquals("a restart forgets it", 0f, capLevel(src, RMB), 0f);
    }

    // ------------------------------------------------------------------ D5: nothing counted, looked up or sent

    /**
     * Drawing a recording, TAPs and every data read included, leaves the live trackers (CPS counters, Keystrokes fades,
     * generator timers, Session Stats, Map Info, Upgrades &amp; Traps) as they were, and hands the recorder nothing.
     */
    @Test
    public void theLiveTrackersAreTheSameAfterPlaybackAsBefore() throws Exception {
        String before = trackers();
        final int[] appended = {0};
        HudCapture.shared().setSink(new HudCapture.Sink() {
            @Override
            public boolean append(int tag, byte[] buf, int len) {
                appended[0]++;
                return true;
            }
        });
        HudCapture.shared().tickHead();
        HudCapture.shared().frame();

        ExtTrack t = new Rec().baseSettings(10 * MS)
                .number(20 * MS, RewindFormat.HUD_FPS, 144).cps(20 * MS, 9, 4).ping(20 * MS, "31")
                .number(20 * MS, RewindFormat.HUD_DIAMOND_TIMER, 11).rows(20 * MS, "Map: Example")
                .lines(20 * MS, new String[]{"Game"}, new int[]{HudModel.LINE_HEADER})
                .shown(20 * MS, RewindFormat.HUD_KEYSTROKES).input(20 * MS, RewindFormat.INPUT_LMB).track();
        Spy spy = new Spy() {
            @Override
            public void text(Minecraft mc, ClientSettings s, HudSource src) {
                super.text(mc, s, src);
                for (String id : HudModules.ids()) {
                    src.shows(id);
                }
                src.inGame();
                src.heightLimitVisible(s, false);
                src.sessionVisible(s, false);
                src.timerValue(true);
                src.timerValue(false);
                src.heightLimitRows(mc);
                src.teamUpgradesRows(TeamUpgradeRows.Layout.FULL);
                src.sessionLines();
                src.textRuns(mc, BedwarsHudRenderer.FPS_HUD);
                src.textRuns(mc, BedwarsHudRenderer.CPS_HUD);
                src.textRuns(mc, BedwarsHudRenderer.PING_HUD);
                boolean[] held = src.keyStates(mc);
                for (int i = 0; i < held.length; i++) {
                    src.keyFade(i).level(held[i], src.now());
                }
                src.potionEntries(mc);
                src.currentLeggings(mc);
                src.miniInventorySlots(mc);
            }
        };
        HudPlayback hp = new HudPlayback(spy);
        ViewState v = new ViewState();
        byte[] l = tapBody(RewindFormat.TAP_LMB), r = tapBody(RewindFormat.TAP_RMB);
        for (long ms = 30; ms < 400; ms += 16) {
            hp.timed(RewindFormat.TAG_TAP, ms * MS, l, 0, l.length);
            hp.timed(RewindFormat.TAG_TAP, ms * MS, r, 0, r.length);
            hp.draw(null, false, v, t, 0, ms * MS);
            hp.draw(null, true, v, t, 0, ms * MS);
        }
        hp.restart();
        hp.exit();
        assertTrue(spy.texts > 10);
        assertEquals("nothing handed to the recorder", 0, appended[0]);
        assertEquals("live trackers unchanged", before, trackers());
    }

    /** A read-only picture of the live trackers' state (reflection; nothing is called on them). */
    static String trackers() throws Exception {
        return trackers(Collections.<String>emptySet());
    }

    /**
     * {@link #trackers()} without the static fields named in {@code memos}: caches that are a pure function of their
     * own key (the HUD's default-layout memo, keyed by screen, font and placement settings), which any draw may fill.
     */
    static String trackers(java.util.Set<String> memos) throws Exception {
        StringBuilder b = new StringBuilder();
        for (String name : new String[]{"com.bedwarsqol.hud.BedwarsHudRenderer", "com.bedwarsqol.bedwars.GeneratorTracker",
                "com.bedwarsqol.feature.SessionStatsWatch", "com.bedwarsqol.feature.HeightLimitWatch",
                "com.bedwarsqol.feature.TeamUpgradesWatch"}) {
            Class<?> c = Class.forName(name);
            b.append(name).append('{');
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                if (f.getType().isPrimitive() && Modifier.isFinal(f.getModifiers())) continue; // constants
                if (memos.contains(f.getName())) continue;
                f.setAccessible(true);
                b.append(f.getName()).append('=');
                describe(b, f.get(null), 0, new IdentityHashMap<Object, Boolean>());
                b.append(';');
            }
            b.append("}\n");
        }
        return b.toString();
    }

    private static void describe(StringBuilder b, Object o, int depth, Map<Object, Boolean> seen) {
        if (o == null || o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof String
                || o instanceof Enum) {
            b.append(o);
            return;
        }
        if (seen.put(o, Boolean.TRUE) != null || depth > 4) {
            b.append('^');
            return;
        }
        Class<?> c = o.getClass();
        if (c.isArray()) {
            b.append('[');
            for (int i = 0; i < Array.getLength(o); i++) {
                describe(b, Array.get(o, i), depth + 1, seen);
                b.append(',');
            }
            b.append(']');
            return;
        }
        if (o instanceof Collection) {
            b.append("coll(");
            for (Object e : (Collection<?>) o) {
                describe(b, e, depth + 1, seen);
                b.append(',');
            }
            b.append(')');
            return;
        }
        if (o instanceof Map) {
            b.append("map(");
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                describe(b, e.getKey(), depth + 1, seen);
                b.append(':');
                describe(b, e.getValue(), depth + 1, seen);
                b.append(',');
            }
            b.append(')');
            return;
        }
        if (!c.getName().startsWith("com.bedwarsqol.")) {
            b.append(c.getName()); // a JDK or Minecraft object: its identity class only
            return;
        }
        b.append(c.getSimpleName()).append('{');
        for (Class<?> k = c; k != null && k.getName().startsWith("com.bedwarsqol."); k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    b.append(f.getName()).append('=');
                    describe(b, f.get(o), depth + 1, seen);
                    b.append(';');
                } catch (Exception e) {
                    b.append(f.getName()).append("=?;");
                }
            }
        }
        b.append('}');
    }

    // ------------------------------------------------------------------ helpers

    /** A source that delegates every read and shows nothing (subclasses note what {@code shows} saw). */
    private static class ShowsNothing implements HudSource {
        private final HudSource in;

        ShowsNothing(HudSource in) {
            this.in = in;
        }

        @Override public boolean shows(String id) { return false; }
        @Override public boolean inGame() { return in.inGame(); }
        @Override public boolean heightLimitVisible(ClientSettings cfg, boolean preview) { return false; }
        @Override public boolean sessionVisible(ClientSettings cfg, boolean preview) { return false; }
        @SuppressWarnings("rawtypes") @Override public List potionEntries(Minecraft mc) { return Collections.emptyList(); } // PotionEntry is package-private
        @Override public ItemStack currentLeggings(Minecraft mc) { return null; }
        @Override public ItemStack[] miniInventorySlots(Minecraft mc) { return new ItemStack[27]; }
        @Override public int timerValue(boolean diamond) { return in.timerValue(diamond); }
        @Override public List<String> heightLimitRows(Minecraft mc) { return Collections.emptyList(); }
        @Override public List<TeamUpgradeRows.Row> teamUpgradesRows(TeamUpgradeRows.Layout layout) { return Collections.emptyList(); }
        @SuppressWarnings("rawtypes") @Override public List sessionLines() { return Collections.emptyList(); } // Line is package-private
        @Override public List<TextModules.Seg> textRuns(Minecraft mc, String id) { return in.textRuns(mc, id); }
        @Override public boolean[] keyStates(Minecraft mc) { return in.keyStates(mc); }
        @Override public KeyFade keyFade(int cap) { return in.keyFade(cap); }
        @Override public long now() { return in.now(); }
    }

    /**
     * Just enough of a client for the HUD font's measuring paths (as {@code hud.FakeClient}): a window size, a GUI
     * scale and a font whose glyphs are all 6 px wide, built without running a constructor.
     */
    private static final class Client implements AutoCloseable {
        static final class Mc extends Minecraft {
            Mc() {
                super(null);
            }

            @Override
            public boolean isUnicode() {
                return false;
            }
        }

        static final class Font extends FontRenderer {
            Font() {
                super(null, null, null, false);
            }

            @Override
            public int getStringWidth(String text) {
                return text == null ? 0 : text.length() * 6;
            }

            @Override
            public int getCharWidth(char c) {
                return 6;
            }
        }

        private final Field instance;
        private final Object previous;
        final Minecraft mc;

        Client(int width, int height, int guiScale) throws Exception {
            mc = (Minecraft) allocate(Mc.class);
            mc.displayWidth = width;
            mc.displayHeight = height;
            mc.gameSettings = (GameSettings) allocate(GameSettings.class);
            mc.gameSettings.guiScale = guiScale;
            mc.fontRendererObj = (FontRenderer) allocate(Font.class);
            instance = Minecraft.class.getDeclaredField("theMinecraft");
            instance.setAccessible(true);
            previous = instance.get(null);
            instance.set(null, mc);
        }

        @Override
        public void close() throws Exception {
            instance.set(null, previous);
        }

        private static Object allocate(Class<?> type) throws Exception {
            Class<?> u = Class.forName("sun.misc.Unsafe");
            Field f = u.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return u.getMethod("allocateInstance", Class.class).invoke(f.get(null), type);
        }
    }
}
