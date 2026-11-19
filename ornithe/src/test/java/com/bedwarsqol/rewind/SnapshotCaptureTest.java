package com.bedwarsqol.rewind;

import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.rewind.ViewCaptureTest.Rec;
import com.bedwarsqol.rewind.ViewCaptureTest.Recorder;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.InventoryPlayer;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A10 and A11's settings part (PLAN Phase 3): each segment starts with one SETTINGS record holding every numbered key
 * (vanilla FOV, GUI scale, scaled and display size, view bobbing, particles, gamma; Cobblify's view-changing switches;
 * every HUD item's placing and styling fields) and one IDENTITY record (own UUID, name, skin property); a setting that
 * changes mid-game is written again, alone.
 */
public class SnapshotCaptureTest {

    @BeforeClass
    public static void bootstrap() {
        InventoryCaptureTest.bootstrap();
    }

    private static ExtCodec.Settings decode(ExtCodec codec, int n) {
        RewindFormat.Cursor c = new RewindFormat.Cursor(codec.buffer(), 0, n);
        int len = c.varInt();
        return new ExtCodec.Settings().read(codec.buffer(), c.pos(), len);
    }

    private static ExtCodec.Settings decode(Rec r) {
        return new ExtCodec.Settings().read(r.body, 0, r.body.length);
    }

    static GameSettings gameSettings() {
        GameSettings gs = new GameSettings();
        gs.fovSetting = 90f;
        gs.guiScale = 2;
        gs.viewBobbing = false;
        gs.particleSetting = 1;
        gs.gammaSetting = 0.5f;
        return gs;
    }

    static ClientSettings clientSettings() {
        ClientSettings cfg = new ClientSettings();
        cfg.guiFont = "modern";
        cfg.guiAccent = "red";
        cfg.defaultTextSize = 2;
        cfg.oldAnimations = true;
        cfg.oaBlockHit = false;
        cfg.handPositionEnabled = true;
        cfg.handPosX = 0.3f;
        cfg.handScale = 0.75f;
        cfg.noHurtCam = true;
        cfg.zoomEnabled = true;
        cfg.mbStrength = 7;
        cfg.ptMultiplier = 3;
        cfg.potionStatusEnabled = true;
        cfg.potionInGameOnly = true;
        cfg.potionHudX = 12;
        cfg.potionHudY = -40;
        cfg.potionHudAnchor = 5;
        cfg.potionHudScale = 1.5f;
        cfg.genTimersEnabled = true;
        cfg.emeraldTimerHudX = 300;
        cfg.fpsEnabled = true;
        cfg.fpsBrackets = true;
        cfg.fpsLabel = 2;
        cfg.sessionStatsHoldKey = true;
        cfg.sessionStatsKeyCode = 42;
        cfg.teamUpgradesLayout = ClientSettings.TEAM_UPGRADES_FULL;
        cfg.scoreboardHudEnabled = false;
        cfg.tabListHudScale = 0.8f;
        cfg.setHudBackground("chat", false);
        cfg.setHudBackground("keystrokes", false);
        cfg.hudSubPixel.put("ping", new float[]{0.25f, -0.5f});
        return cfg;
    }

    private static int i(ExtCodec.Settings s, int key) {
        int k = s.indexOf(key);
        assertTrue("key " + key + " present", k >= 0);
        assertEquals("key " + key + " type", RewindFormat.SETTING_TYPE_VARINT, s.types[k]);
        return s.ints[k];
    }

    private static float f(ExtCodec.Settings s, int key) {
        int k = s.indexOf(key);
        assertTrue("key " + key + " present", k >= 0);
        assertEquals("key " + key + " type", RewindFormat.SETTING_TYPE_F32, s.types[k]);
        return s.floats[k];
    }

    private static String str(ExtCodec.Settings s, int key) {
        int k = s.indexOf(key);
        assertTrue("key " + key + " present", k >= 0);
        assertEquals(RewindFormat.SETTING_TYPE_STRING, s.types[k]);
        return s.strings[k];
    }

    private static int hud(int item, int field) {
        return RewindFormat.settingHudKey(item, field);
    }

    // ------------------------------------------------------------------ SETTINGS content

    @Test
    public void theFullSnapshotHoldsEveryNumberedKeyWithItsValue() {
        SettingsSnapshot snap = new SettingsSnapshot();
        snap.read(gameSettings(), 480, 270, 1920, 1080, clientSettings());
        ExtCodec codec = new ExtCodec();
        int n = snap.write(codec, true);
        assertTrue(n > 0);
        ExtCodec.Settings s = decode(codec, n);

        int[] keys = SettingsSnapshot.keys();
        assertEquals(keys.length, s.count);
        Set<Integer> seen = new HashSet<Integer>();
        for (int k = 0; k < s.count; k++) {
            assertTrue("each key once: " + s.keys[k], seen.add(s.keys[k]));
            assertEquals("key " + s.keys[k] + " typed as numbered", RewindFormat.settingType(s.keys[k]), s.types[k]);
        }
        for (int key = RewindFormat.SETTING_FOV; key <= RewindFormat.SETTING_GAMMA; key++) {
            assertTrue("vanilla key " + key, seen.contains(key));
        }
        for (int key = RewindFormat.SETTING_GUI_FONT; key <= RewindFormat.SETTING_PT_MULTIPLIER; key++) {
            assertTrue("Cobblify key " + key, seen.contains(key));
        }
        for (int item = RewindFormat.HUD_POTION; item <= RewindFormat.HUD_ITEM_TAB_LIST; item++) {
            for (int field : new int[]{RewindFormat.HUD_FIELD_X, RewindFormat.HUD_FIELD_Y, RewindFormat.HUD_FIELD_ANCHOR,
                    RewindFormat.HUD_FIELD_SCALE, RewindFormat.HUD_FIELD_BACKGROUND, RewindFormat.HUD_FIELD_SUB_X,
                    RewindFormat.HUD_FIELD_SUB_Y}) {
                assertTrue("item " + item + " field " + field, seen.contains(hud(item, field)));
            }
        }

        // vanilla and screen
        assertEquals(90f, f(s, RewindFormat.SETTING_FOV), 0f);
        assertEquals(2, i(s, RewindFormat.SETTING_GUI_SCALE));
        assertEquals(0, i(s, RewindFormat.SETTING_VIEW_BOBBING));
        assertEquals(480, i(s, RewindFormat.SETTING_SCALED_WIDTH));
        assertEquals(270, i(s, RewindFormat.SETTING_SCALED_HEIGHT));
        assertEquals(1920, i(s, RewindFormat.SETTING_DISPLAY_WIDTH));
        assertEquals(1080, i(s, RewindFormat.SETTING_DISPLAY_HEIGHT));
        assertEquals(1, i(s, RewindFormat.SETTING_PARTICLES_VANILLA));
        assertEquals(0.5f, f(s, RewindFormat.SETTING_GAMMA), 0f);
        // Cobblify
        assertEquals("modern", str(s, RewindFormat.SETTING_GUI_FONT));
        assertEquals("red", str(s, RewindFormat.SETTING_GUI_ACCENT));
        assertEquals(2, i(s, RewindFormat.SETTING_HUD_TEXT_SIZE));
        assertEquals(1, i(s, RewindFormat.SETTING_OLD_ANIMATIONS));
        assertEquals(0, i(s, RewindFormat.SETTING_OA_BLOCK_HIT));
        assertEquals(1, i(s, RewindFormat.SETTING_HAND_POSITION));
        assertEquals(0.3f, f(s, RewindFormat.SETTING_HAND_POS_X), 0f);
        assertEquals(0.75f, f(s, RewindFormat.SETTING_HAND_SCALE), 0f);
        assertEquals(1, i(s, RewindFormat.SETTING_NO_HURT_CAM));
        assertEquals(1, i(s, RewindFormat.SETTING_ZOOM));
        assertEquals(7, i(s, RewindFormat.SETTING_MOTION_BLUR_STRENGTH));
        assertEquals(3, i(s, RewindFormat.SETTING_PT_MULTIPLIER));
        // HUD items
        assertEquals(1, i(s, hud(RewindFormat.HUD_POTION, RewindFormat.HUD_FIELD_ENABLED)));
        assertEquals(1, i(s, hud(RewindFormat.HUD_POTION, RewindFormat.HUD_FIELD_IN_GAME_ONLY)));
        assertEquals(12, i(s, hud(RewindFormat.HUD_POTION, RewindFormat.HUD_FIELD_X)));
        assertEquals(-40, i(s, hud(RewindFormat.HUD_POTION, RewindFormat.HUD_FIELD_Y)));
        assertEquals(5, i(s, hud(RewindFormat.HUD_POTION, RewindFormat.HUD_FIELD_ANCHOR)));
        assertEquals(1.5f, f(s, hud(RewindFormat.HUD_POTION, RewindFormat.HUD_FIELD_SCALE)), 0f);
        assertEquals(1, i(s, hud(RewindFormat.HUD_POTION, RewindFormat.HUD_FIELD_BACKGROUND)));
        assertEquals(1, i(s, hud(RewindFormat.HUD_EMERALD_TIMER, RewindFormat.HUD_FIELD_ENABLED)));
        assertEquals(300, i(s, hud(RewindFormat.HUD_EMERALD_TIMER, RewindFormat.HUD_FIELD_X)));
        assertEquals(0, i(s, hud(RewindFormat.HUD_KEYSTROKES, RewindFormat.HUD_FIELD_BACKGROUND)));
        assertEquals(1, i(s, hud(RewindFormat.HUD_FPS, RewindFormat.HUD_FIELD_OPTION_A)));
        assertEquals(2, i(s, hud(RewindFormat.HUD_FPS, RewindFormat.HUD_FIELD_OPTION_B)));
        assertEquals(1, i(s, hud(RewindFormat.HUD_SESSION_STATS, RewindFormat.HUD_FIELD_OPTION_A)));
        assertEquals(42, i(s, hud(RewindFormat.HUD_SESSION_STATS, RewindFormat.HUD_FIELD_OPTION_B)));
        assertEquals(1, i(s, hud(RewindFormat.HUD_TEAM_UPGRADES, RewindFormat.HUD_FIELD_OPTION_A)));
        assertEquals(0.25f, f(s, hud(RewindFormat.HUD_PING, RewindFormat.HUD_FIELD_SUB_X)), 0f);
        assertEquals(-0.5f, f(s, hud(RewindFormat.HUD_PING, RewindFormat.HUD_FIELD_SUB_Y)), 0f);
        assertEquals(0f, f(s, hud(RewindFormat.HUD_FPS, RewindFormat.HUD_FIELD_SUB_X)), 0f);
        assertEquals(0, i(s, hud(RewindFormat.HUD_ITEM_SCOREBOARD, RewindFormat.HUD_FIELD_ENABLED)));
        assertEquals(0, i(s, hud(RewindFormat.HUD_ITEM_CHAT, RewindFormat.HUD_FIELD_BACKGROUND)));
        assertEquals(0.8f, f(s, hud(RewindFormat.HUD_ITEM_TAB_LIST, RewindFormat.HUD_FIELD_SCALE)), 0f);
        // a field an item does not have is not written
        assertEquals(-1, s.indexOf(hud(RewindFormat.HUD_ITEM_CHAT, RewindFormat.HUD_FIELD_ENABLED)));
        assertEquals(-1, s.indexOf(hud(RewindFormat.HUD_FPS, RewindFormat.HUD_FIELD_IN_GAME_ONLY)));
        assertEquals(-1, s.indexOf(hud(RewindFormat.HUD_POTION, RewindFormat.HUD_FIELD_OPTION_A)));
        assertEquals(-1, s.indexOf(hud(RewindFormat.HUD_TEAM_UPGRADES, RewindFormat.HUD_FIELD_OPTION_B)));
    }

    @Test
    public void aChangedSettingIsWrittenAloneAndAnUnchangedOneNever() {
        GameSettings gs = gameSettings();
        ClientSettings cfg = clientSettings();
        SettingsSnapshot snap = new SettingsSnapshot();
        ExtCodec codec = new ExtCodec();
        snap.read(gs, 480, 270, 1920, 1080, cfg);
        assertTrue(snap.write(codec, true) > 0);
        snap.read(gs, 480, 270, 1920, 1080, cfg);
        assertEquals("nothing changed", 0, snap.write(codec, false));

        cfg.guiAccent = new String("red"); // an equal string, another object
        snap.read(gs, 480, 270, 1920, 1080, cfg);
        assertEquals(0, snap.write(codec, false));

        gs.fovSetting = 100f;
        snap.read(gs, 480, 270, 1920, 1080, cfg);
        int n = snap.write(codec, false);
        ExtCodec.Settings s = decode(codec, n);
        assertEquals(1, s.count);
        assertEquals(100f, f(s, RewindFormat.SETTING_FOV), 0f);

        cfg.keystrokesHudX = 77;
        cfg.setHudBackground("keystrokes", true);
        snap.read(gs, 427, 240, 854, 480, cfg); // the window was resized too
        s = decode(codec, snap.write(codec, false));
        assertEquals(6, s.count);
        assertEquals(77, i(s, hud(RewindFormat.HUD_KEYSTROKES, RewindFormat.HUD_FIELD_X)));
        assertEquals(1, i(s, hud(RewindFormat.HUD_KEYSTROKES, RewindFormat.HUD_FIELD_BACKGROUND)));
        assertEquals(427, i(s, RewindFormat.SETTING_SCALED_WIDTH));
        assertEquals(480, i(s, RewindFormat.SETTING_DISPLAY_HEIGHT));
    }

    // ------------------------------------------------------------------ through the per-tick capture

    static GameProfile profile() {
        GameProfile p = new GameProfile(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"), "Steve_Test");
        p.getProperties().put("textures", new Property("textures", "eyJ0ZXh0dXJlcyI6e319", "c2lnbmF0dXJl"));
        return p;
    }

    @Test
    public void eachSegmentStartsWithOneSettingsAndOneIdentityRecord() {
        GameSettings gs = gameSettings();
        ClientSettings cfg = clientSettings();
        SettingsSnapshot snap = new SettingsSnapshot();
        InventoryPlayer inv = InventoryCaptureTest.fullInventory();
        InventoryCaptureTest.Own own = new InventoryCaptureTest.Own();
        GameProfile profile = profile();
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        for (int t = 0; t < 30; t++) {
            snap.read(gs, 480, 270, 1920, 1080, cfg);
            assertTrue(c.tick(s, 0, inv, own, own, snap, profile));
        }
        List<Rec> settings = s.of(RewindFormat.TAG_SETTINGS);
        assertEquals(1, settings.size());
        assertEquals(SettingsSnapshot.keys().length, decode(settings.get(0)).count);
        List<Rec> ids = s.of(RewindFormat.TAG_IDENTITY);
        assertEquals(1, ids.size());
        assertEquals("identity first, settings next", RewindFormat.TAG_IDENTITY, s.recs.get(0).tag);
        assertEquals(RewindFormat.TAG_SETTINGS, s.recs.get(1).tag);
        ExtCodec.Identity id = new ExtCodec.Identity().read(ids.get(0).body, 0, ids.get(0).body.length);
        assertEquals(profile.getId(), new UUID(id.uuidMost, id.uuidLeast));
        assertEquals("Steve_Test", id.name);
        assertEquals("eyJ0ZXh0dXJlcyI6e319", id.skinValue);
        assertEquals("c2lnbmF0dXJl", id.skinSignature);

        cfg.dynamicFov = !cfg.dynamicFov; // mid-game change
        snap.read(gs, 480, 270, 1920, 1080, cfg);
        c.tick(s, 0, inv, own, own, snap, profile);
        settings = s.of(RewindFormat.TAG_SETTINGS);
        assertEquals(2, settings.size());
        ExtCodec.Settings changed = decode(settings.get(1));
        assertEquals(1, changed.count);
        assertEquals(RewindFormat.SETTING_DYNAMIC_FOV, changed.keys[0]);
        assertEquals("identity is per segment", 1, s.of(RewindFormat.TAG_IDENTITY).size());

        c.reset();
        snap.read(gs, 480, 270, 1920, 1080, cfg);
        c.tick(s, 0, inv, own, own, snap, profile);
        settings = s.of(RewindFormat.TAG_SETTINGS);
        assertEquals(3, settings.size());
        assertEquals("a new segment states every key", SettingsSnapshot.keys().length, decode(settings.get(2)).count);
        assertEquals(2, s.of(RewindFormat.TAG_IDENTITY).size());
    }

    @Test
    public void aProfileWithoutASkinWritesEmptySkinStrings() {
        GameProfile p = new GameProfile(UUID.fromString("00000000-0000-0000-0000-00000000002a"), "NoSkin");
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        InventoryCaptureTest.Own own = new InventoryCaptureTest.Own();
        c.tick(s, 0, new InventoryPlayer(null), own, own, null, p);
        List<Rec> ids = s.of(RewindFormat.TAG_IDENTITY);
        assertEquals(1, ids.size());
        ExtCodec.Identity id = new ExtCodec.Identity().read(ids.get(0).body, 0, ids.get(0).body.length);
        assertEquals("NoSkin", id.name);
        assertEquals("", id.skinValue);
        assertEquals("", id.skinSignature);
        assertFalse(s.of(RewindFormat.TAG_SETTINGS).size() > 0);
    }
}
