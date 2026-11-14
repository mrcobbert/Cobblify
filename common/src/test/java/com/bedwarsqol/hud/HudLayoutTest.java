package com.bedwarsqol.hud;

import com.bedwarsqol.hud.HudLayout.Box;
import com.bedwarsqol.hud.HudLayout.Item;
import com.bedwarsqol.hud.HudLayout.Spot;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.bedwarsqol.hud.HudLayout.Horizontal.CENTRE;
import static com.bedwarsqol.hud.HudLayout.Horizontal.LEFT;
import static com.bedwarsqol.hud.HudLayout.Horizontal.RIGHT;
import static com.bedwarsqol.hud.HudLayout.Vertical.BOTTOM;
import static com.bedwarsqol.hud.HudLayout.Vertical.MIDDLE;
import static com.bedwarsqol.hud.HudLayout.Vertical.TOP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HudLayoutTest {

    private static final float EPS = 1e-4f;
    private static final List<Box> NOTHING = Collections.emptyList();
    private final HudLayout layout = new HudLayout(480, 270, 2, 2);

    private static void assertAt(float x, float y, float[] at) {
        assertEquals(x, at[0], EPS);
        assertEquals(y, at[1], EPS);
    }

    @Test
    public void aLoneModuleTakesItsCorner() {
        Map<String, float[]> out = layout.place(Collections.singletonList(
                new Item("a", 40, 20, new Spot(RIGHT, TOP, HudLayout.Slide.LEFT))), NOTHING);
        assertAt(480 - 2 - 40, 2, out.get("a"));
    }

    @Test
    public void centreAndMiddleSpotsAreCentred() {
        Map<String, float[]> out = layout.place(Arrays.asList(
                new Item("top", 100, 20, new Spot(CENTRE, TOP, HudLayout.Slide.NONE)),
                new Item("mid", 40, 30, new Spot(LEFT, MIDDLE, HudLayout.Slide.NONE))), NOTHING);
        assertAt(190, 2, out.get("top"));
        assertAt(2, 120, out.get("mid"));
    }

    @Test
    public void slidesLeftPastAModulePlacedBefore() {
        Map<String, float[]> out = layout.place(Arrays.asList(
                new Item("diamond", 40, 20, new Spot(RIGHT, TOP, HudLayout.Slide.LEFT)),
                new Item("emerald", 45, 20, new Spot(RIGHT, TOP, HudLayout.Slide.LEFT))), NOTHING);
        assertAt(438, 2, out.get("diamond"));
        assertAt(438 - 2 - 45, 2, out.get("emerald"));
    }

    @Test
    public void slidesDownPastEverythingInTheWay() {
        Map<String, float[]> out = layout.place(Arrays.asList(
                new Item("diamond", 40, 20, new Spot(RIGHT, TOP, HudLayout.Slide.LEFT)),
                new Item("emerald", 40, 30, new Spot(RIGHT, TOP, HudLayout.Slide.LEFT)),
                new Item("session", 80, 100, new Spot(RIGHT, TOP, HudLayout.Slide.DOWN))), NOTHING);
        // Under the taller of the two timers, which both sit within the session's columns.
        assertAt(480 - 2 - 80, 2 + 30 + 2, out.get("session"));
    }

    @Test
    public void triesTheNextSpotWhenSlidingRunsOffScreen() {
        Map<String, float[]> out = layout.place(Arrays.asList(
                new Item("session", 80, 240, new Spot(RIGHT, TOP, HudLayout.Slide.DOWN)),
                new Item("keys", 60, 70,
                        new Spot(RIGHT, BOTTOM, HudLayout.Slide.UP),
                        new Spot(RIGHT, BOTTOM, HudLayout.Slide.LEFT))), NOTHING);
        // Up would leave the screen above the session panel, so it goes beside it instead.
        assertAt(398 - 2 - 60, 270 - 2 - 70, out.get("keys"));
    }

    @Test
    public void keepsClearOfObstacles() {
        List<Box> hotbar = Collections.singletonList(new Box(149, 220, 182, 50));
        Map<String, float[]> out = layout.place(Collections.singletonList(
                new Item("keys", 60, 70, new Spot(CENTRE, BOTTOM, HudLayout.Slide.UP))), hotbar);
        assertAt(210, 220 - 2 - 70, out.get("keys"));
    }

    @Test
    public void keepsTheGapBetweenBoxes() {
        Map<String, float[]> out = layout.place(Arrays.asList(
                new Item("a", 50, 20, new Spot(LEFT, TOP, HudLayout.Slide.DOWN)),
                new Item("b", 50, 20, new Spot(LEFT, TOP, HudLayout.Slide.DOWN))), NOTHING);
        assertAt(2, 2 + 20 + 2, out.get("b"));
    }

    @Test
    public void withNoRoomAnywhereItTakesItsFirstSpot() {
        List<Box> wall = Collections.singletonList(new Box(0, 0, 480, 270));
        Map<String, float[]> out = layout.place(Collections.singletonList(
                new Item("a", 40, 20,
                        new Spot(RIGHT, TOP, HudLayout.Slide.LEFT),
                        new Spot(LEFT, BOTTOM, HudLayout.Slide.UP))), wall);
        assertAt(438, 2, out.get("a"));
    }

    @Test
    public void aSpotThatDoesNotSlideFailsWhenBlocked() {
        Map<String, float[]> out = layout.place(Arrays.asList(
                new Item("a", 40, 20, new Spot(LEFT, TOP, HudLayout.Slide.NONE)),
                new Item("b", 40, 20,
                        new Spot(LEFT, TOP, HudLayout.Slide.NONE),
                        new Spot(RIGHT, TOP, HudLayout.Slide.NONE))), NOTHING);
        assertAt(438, 2, out.get("b"));
    }

    @Test
    public void aBoxTooBigForTheScreenKeepsItsLeadingEdgeOnScreen() {
        Map<String, float[]> out = layout.place(Collections.singletonList(
                new Item("huge", 600, 20, new Spot(RIGHT, TOP, HudLayout.Slide.LEFT))), NOTHING);
        assertAt(2, 2, out.get("huge"));
    }

    @Test
    public void withNoFreeSpotItCoversAsLittleAsItCan() {
        // A wide box with no free spot: centred it would cover the tall right column, slid left it only
        // clips the narrow left one.
        List<Box> columns = Arrays.asList(new Box(2, 2, 30, 266), new Box(380, 2, 98, 266));
        Map<String, float[]> out = layout.place(Collections.singletonList(
                new Item("wide", 360, 50,
                        new Spot(CENTRE, TOP, HudLayout.Slide.LEFT))), columns);
        assertAt(380 - 2 - 360, 2, out.get("wide"));
    }

    // ----- fitScale (Team Upgrades' Full panel: review code-1 I2, and HUD Size 2 beside Map Info) -----

    /** The Full panel's measured size: {130 s + 12, 129 s + 12} GUI px. */
    private static final HudLayout.Sizer FULL_PANEL = s -> new float[]{130f * s + 12f, 129f * s + 12f};
    private static final Spot[] TEAM_SPOTS = {new Spot(LEFT, MIDDLE, HudLayout.Slide.DOWN),
            new Spot(LEFT, MIDDLE, HudLayout.Slide.UP), new Spot(LEFT, BOTTOM, HudLayout.Slide.UP)};
    private static final List<Item> NONE_BEFORE = Collections.emptyList();

    /** The hotbar with the health, armour and food bars: 182 x 50, centred on the bottom edge. */
    private static List<Box> hotbar(float sw, float sh) {
        return Collections.singletonList(new Box((sw - 182f) / 2f, sh - 50f, 182f, 50f));
    }

    private static float fit(float sw, float sh, List<Item> before, List<Box> obstacles, float scale) {
        return new HudLayout(sw, sh, 2, 2).fitScale(before, obstacles, "team", scale, 0.5f, 0.5f, FULL_PANEL, TEAM_SPOTS);
    }

    @Test
    public void fitScaleStepsDownUntilThePanelClearsTheHotbar() {
        // 854x480 at GUI scale 2 (427x240), HUD Size Large: 207 x 205.5 fits neither beside nor above
        assertEquals(1.0f, fit(427f, 240f, NONE_BEFORE, hotbar(427f, 240f), 1.5f), 1e-6f);
    }

    @Test
    public void fitScaleStepsDownUntilThePanelClearsTheModulesPlacedBeforeIt() {
        // 1280x720 at GUI scale 2 (640x360), HUD Size 2: Map Info in the middle of the left edge, Armor,
        // Potion and Keystrokes above it. At 1.5 the panel fits neither under nor over Map Info; at 1.0 it does.
        List<Box> obstacles = Arrays.asList(new Box(229f, 310f, 182f, 50f), new Box(2f, 2f, 94f, 148f));
        List<Item> mapInfo = Collections.singletonList(new Item("map", 112f, 56f, new Spot(LEFT, MIDDLE, HudLayout.Slide.DOWN)));
        assertEquals(1.0f, fit(640f, 360f, mapInfo, obstacles, 1.5f), 1e-6f);
        // without Map Info the configured scale already fits
        assertEquals(1.5f, fit(640f, 360f, NONE_BEFORE, obstacles, 1.5f), 1e-6f);

        // and the fitted panel is placed clear of everything
        HudLayout layout = new HudLayout(640f, 360f, 2, 2);
        float[] size = FULL_PANEL.size(1.0f);
        Map<String, float[]> out = layout.place(Arrays.asList(mapInfo.get(0), new Item("team", size[0], size[1], TEAM_SPOTS)), obstacles);
        float[] team = out.get("team"), map = out.get("map");
        assertFalse(overlaps(team, map));
        for (Box b : obstacles) assertFalse(overlaps(team, new float[]{b.x, b.y, b.width, b.height}));
    }

    private static boolean overlaps(float[] a, float[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    @Test
    public void fitScaleKeepsAScaleThatAlreadyFits() {
        assertEquals(1.5f, fit(640f, 360f, NONE_BEFORE, hotbar(640f, 360f), 1.5f), 1e-6f);
        assertEquals(1.0f, fit(427f, 240f, NONE_BEFORE, hotbar(427f, 240f), 1.0f), 1e-6f);
    }

    @Test
    public void fitScaleTriesTheMinimumWhenAStepWouldPassIt() {
        // GUI scale 1 at HUD Size 2: 1.5 draws as 2.0, one font pixel down (1.0) would be 0.5, below the
        // one-pixel minimum, so the minimum itself is the last try
        HudLayout.Sizer snapped = s -> FULL_PANEL.size(s >= 1.5f ? 2f : s);
        List<Box> obstacles = Arrays.asList(new Box(336f, 430f, 182f, 50f));
        List<Item> mapInfo = Collections.singletonList(new Item("map", 168f, 72f, new Spot(LEFT, MIDDLE, HudLayout.Slide.DOWN)));
        assertEquals(1.0f, new HudLayout(854f, 480f, 2, 2).fitScale(mapInfo, obstacles, "team", 1.5f, 1f, 1f, snapped, TEAM_SPOTS), 1e-6f);
    }

    /** Review code-2 I1: 854x480 at GUI scale 2, HUD Size 1, every module on (recorded smoke boxes). */
    private static final List<Box> EVERY_MODULE = Arrays.asList(
            new Box(2, 32, 47, 44), new Box(2, 2, 22, 22), new Box(98.5f, 2, 206, 74), new Box(382, 2, 43, 22),
            new Box(337, 2, 43, 22), new Box(57, 158, 64, 80), new Box(306, 26, 119, 148), new Box(2, 101, 86, 38),
            new Box(122.5f, 190, 182, 50));
    private static final HudLayout.Sizer PADDED = s -> {
        float pad = Math.max(2f, Math.round(4f * s));
        return new float[]{130f * s + 2f * pad, 129f * s + 2f * pad};
    };
    private static final Spot[] BESIDE = {new Spot(LEFT, MIDDLE, HudLayout.Slide.RIGHT),
            new Spot(LEFT, TOP, HudLayout.Slide.RIGHT)};

    @Test
    public void aFullLeftEdgeTriesBesideTheLeftColumnAtTheSmallestSize() {
        HudLayout layout = new HudLayout(427f, 240f, 2, 2);
        HudLayout.Fit fit = layout.fit(NONE_BEFORE, EVERY_MODULE, "team", 1.0f, 0.5f, 0.5f, PADDED, TEAM_SPOTS, BESIDE);
        assertEquals(0.5f, fit.scale, 1e-6f);
        assertTrue(fit.free);
        float[] size = PADDED.size(fit.scale);
        float[] team = layout.place(Collections.singletonList(new Item("team", size[0], size[1], fit.spots)), EVERY_MODULE).get("team");
        for (Box b : EVERY_MODULE) assertFalse(overlaps(team, new float[]{b.x, b.y, b.width, b.height}));
    }

    @Test
    public void aFreeLeftEdgeNeverMovesBesideTheColumn() {
        // the profile layout: only Map Info and the timers; the left edge has room at full size
        List<Box> profile = Arrays.asList(new Box(595, 2, 43, 22), new Box(550, 2, 43, 22), new Box(2, 101, 86, 38),
                new Box(229, 310, 182, 50));
        HudLayout.Fit fit = new HudLayout(640f, 360f, 2, 2).fit(NONE_BEFORE, profile, "team", 1.0f, 0.5f, 0.5f, PADDED,
                TEAM_SPOTS, BESIDE);
        assertEquals(1.0f, fit.scale, 1e-6f);
        assertTrue(fit.free);
        assertEquals(TEAM_SPOTS.length, fit.spots.length);
    }

    @Test
    public void fitScaleStopsAtTheMinimumWhenNothingFits() {
        assertEquals(0.5f, fit(100f, 80f, NONE_BEFORE, hotbar(100f, 80f), 1.5f), 1e-6f);
    }
}
