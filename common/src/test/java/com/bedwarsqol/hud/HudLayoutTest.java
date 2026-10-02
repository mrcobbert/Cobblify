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
}
