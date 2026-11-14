package com.bedwarsqol.hud;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The default layout (G30, G33) that Reset Positions applies. Fixture visual sizes (content + 1 GUI px of pad each
 * side): fps 46x18, cps 52x18, ping 40x18, armor 22x22, potion 52x44, Map Info 120x38, timers 42x22, session
 * 138x148, inventory 206x74, keystrokes 64x80.
 */
public class DefaultHudLayoutTest {

    private static final float EPS = 1e-3f;
    private static final float PAD = 1f;
    private static final String[][] SIZES = {{"fps", "46", "18"}, {"cps", "52", "18"}, {"ping", "40", "18"},
            {"armor", "22", "22"}, {"potion", "52", "44"}, {"heightlimit", "120", "38"}, {"diamondtimer", "42", "22"},
            {"emeraldtimer", "42", "22"}, {"sessionstats", "138", "148"}, {"inventory", "206", "74"},
            {"keystrokes", "64", "80"}, {"teamupgrades", "90", "40"}, {"scoreboard", "100", "120"}, {"chat", "320", "90"},
            {"tablist", "200", "60"}};

    private static List<DefaultHudLayout.Item> items(String... hidden) {
        Set<String> off = new HashSet<String>(Arrays.asList(hidden));
        List<DefaultHudLayout.Item> out = new ArrayList<DefaultHudLayout.Item>();
        for (String[] s : SIZES) {
            float w = Float.parseFloat(s[1]), h = Float.parseFloat(s[2]);
            out.add(new DefaultHudLayout.Item(s[0], w - 2 * PAD, h - 2 * PAD, PAD, !off.contains(s[0])));
        }
        return out;
    }

    private static float[] size(String id) {
        for (String[] s : SIZES) if (s[0].equals(id)) return new float[]{Float.parseFloat(s[1]), Float.parseFloat(s[2])};
        throw new AssertionError(id);
    }

    /** The visual top-left the stored spot gives at sw x sh. */
    private static float[] drawn(Map<String, DefaultHudLayout.Spot> spots, String id, float sw, float sh) {
        DefaultHudLayout.Spot s = spots.get(id);
        float[] v = size(id);
        float cw = v[0] - 2 * PAD, ch = v[1] - 2 * PAD;
        return new float[]{HudPlacement.absoluteX(s.x, s.anchor, cw, sw) - PAD, HudPlacement.absoluteY(s.y, s.anchor, ch, sh) - PAD};
    }

    private static void at(Map<String, DefaultHudLayout.Spot> spots, String id, float sw, float sh, float x, float y) {
        float[] d = drawn(spots, id, sw, sh);
        assertEquals(id + " x", x, d[0], EPS);
        assertEquals(id + " y", y, d[1], EPS);
    }

    private static void auto(Map<String, DefaultHudLayout.Spot> spots, String id) {
        assertEquals(id, HudPlacement.AUTO, spots.get(id).anchor);
    }

    /** Every G30 spot at sw x sh, from the fixture sizes. */
    private static void g30(float sw, float sh) {
        Map<String, DefaultHudLayout.Spot> s = DefaultHudLayout.place(sw, sh, items());
        at(s, "fps", sw, sh, 2, 2);
        at(s, "cps", sw, sh, 2, 22);
        at(s, "ping", sw, sh, 2, 42);
        at(s, "armor", sw, sh, 2, 62);
        at(s, "potion", sw, sh, 2, 86);
        at(s, "heightlimit", sw, sh, 2, (sh - 38) / 2f);
        at(s, "diamondtimer", sw, sh, sw - 2 - 42, 2);
        at(s, "emeraldtimer", sw, sh, sw - 2 - 42 - 2 - 42, 2);
        at(s, "sessionstats", sw, sh, sw - 2 - 138, 26);
        at(s, "inventory", sw, sh, (sw - 206) / 2f, 2);
        at(s, "keystrokes", sw, sh, sw - 2 - 64, sh - 2 - 80);
        assertTrue("G33: Armor stays left of the hotbar", drawn(s, "armor", sw, sh)[0] + 22 < (sw - 182) / 2f);
        for (String id : new String[]{"fps", "cps", "ping", "armor", "potion"}) assertEquals(id, 0, s.get(id).anchor);
        assertEquals(3, s.get("heightlimit").anchor);
        assertEquals(0f, s.get("heightlimit").y, EPS);
        assertEquals(2, s.get("diamondtimer").anchor);
        assertEquals(2, s.get("emeraldtimer").anchor);
        assertEquals(2, s.get("sessionstats").anchor);
        assertEquals(1, s.get("inventory").anchor);
        assertEquals(0f, s.get("inventory").x, EPS);
        assertEquals(8, s.get("keystrokes").anchor);
        for (String id : new String[]{"teamupgrades", "scoreboard", "chat", "tablist"}) auto(s, id);
    }

    @Test
    public void spotsAt960x540() {
        Map<String, DefaultHudLayout.Spot> s = DefaultHudLayout.place(960, 540, items());
        at(s, "fps", 960, 540, 2, 2);
        at(s, "cps", 960, 540, 2, 22);
        at(s, "ping", 960, 540, 2, 42);
        at(s, "armor", 960, 540, 2, 62);
        at(s, "potion", 960, 540, 2, 86);
        at(s, "heightlimit", 960, 540, 2, 251);
        at(s, "diamondtimer", 960, 540, 916, 2);
        at(s, "emeraldtimer", 960, 540, 872, 2);
        at(s, "sessionstats", 960, 540, 820, 26);
        at(s, "inventory", 960, 540, 377, 2);
        at(s, "keystrokes", 960, 540, 894, 458);
        g30(960, 540);
    }

    @Test
    public void theSameRulesAtSmallerScreensEvenWhereSpotsOverlap() {
        g30(640, 360);   // 1920x1080 at GUI 3, 1280x720 at GUI 2
        g30(427, 240);   // 1280x720 at GUI 3: Map Info over Potion, Session Stats over Keystrokes (D8: exact spots)
        g30(1280, 720);
        Map<String, DefaultHudLayout.Spot> s = DefaultHudLayout.place(427, 240, items());
        float[] potion = drawn(s, "potion", 427, 240), map = drawn(s, "heightlimit", 427, 240);
        assertTrue("D8: kept overlapping", map[1] < potion[1] + 44);
        float[] session = drawn(s, "sessionstats", 427, 240), keys = drawn(s, "keystrokes", 427, 240);
        assertTrue("D8: kept overlapping", keys[1] < session[1] + 148);
    }

    @Test
    public void storedSpotsFollowAnotherScreenSize() {
        Map<String, DefaultHudLayout.Spot> s = DefaultHudLayout.place(960, 540, items());
        at(s, "inventory", 1280, 720, (1280 - 206) / 2f, 2);
        at(s, "heightlimit", 1280, 720, 2, (720 - 38) / 2f);
        at(s, "keystrokes", 1280, 720, 1280 - 2 - 64, 720 - 2 - 80);
        at(s, "diamondtimer", 1280, 720, 1280 - 2 - 42, 2);
        at(s, "emeraldtimer", 1280, 720, 1280 - 2 - 42 - 2 - 42, 2);
        at(s, "sessionstats", 1280, 720, 1280 - 2 - 138, 26);
        at(s, "fps", 1280, 720, 2, 2);
    }

    @Test
    public void hiddenItemsKeepNoSlot() {
        Map<String, DefaultHudLayout.Spot> s = DefaultHudLayout.place(960, 540, items("fps", "cps", "ping"));
        at(s, "armor", 960, 540, 2, 2);
        at(s, "potion", 960, 540, 2, 26);
        auto(s, "fps");
        auto(s, "cps");
        auto(s, "ping");
    }

    @Test
    public void sessionStatsTopsTheScreenWithoutTimers() {
        Map<String, DefaultHudLayout.Spot> s = DefaultHudLayout.place(960, 540, items("diamondtimer", "emeraldtimer"));
        at(s, "sessionstats", 960, 540, 820, 2);
        auto(s, "diamondtimer");
        auto(s, "emeraldtimer");
    }

    @Test
    public void applyingASpotKeepsSizeSwitchAndBackground() {
        Map<String, DefaultHudLayout.Spot> s = DefaultHudLayout.place(960, 540, items());
        HudModuleState st = new HudModuleState(40, -9, 5, 1.75f, true, false, 0.25f, 0f);
        HudModuleState placed = s.get("keystrokes").apply(st);
        assertEquals(8, placed.anchor);
        assertEquals(1.75f, placed.scale, 0f);
        assertTrue(placed.enabled);
        assertEquals(false, placed.background);
        HudModuleState auto = s.get("chat").apply(st.withEnabled(false));
        assertEquals(HudPlacement.AUTO, auto.anchor);
        assertEquals(0f, auto.fracX, 0f);
        assertEquals(1.75f, auto.scale, 0f);
        assertEquals(false, auto.enabled);
    }
}
