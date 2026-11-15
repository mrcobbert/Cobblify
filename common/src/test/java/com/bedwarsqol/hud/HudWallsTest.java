package com.bedwarsqol.hud;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Walls (G20): a dragged box stops at, slides along and goes around every other box; nothing else moves. Screen
 * 480x270, box 40x20, wall W = (200,60,50,150) unless noted (the kit's resolveWalls, numbers checked by hand).
 */
public class HudWallsTest {

    private static final float EPS = 1e-4f;
    private static final HudWalls.Rect W = new HudWalls.Rect(200, 60, 50, 150);
    private static final List<HudWalls.Rect> ONE = Collections.singletonList(W);

    private static float[] resolve(float x, float y, List<HudWalls.Rect> walls) {
        return HudWalls.resolve(x, y, 40, 20, 480, 270, walls, 7, 9);
    }

    private static void at(float x, float y, float[] got) {
        assertEquals("x", x, got[0], EPS);
        assertEquals("y", y, got[1], EPS);
    }

    @Test
    public void aFreeTargetIsKept() {
        at(100, 110, resolve(100, 110, ONE));
        at(100.25f, 3.5f, resolve(100.25f, 3.5f, ONE));
    }

    @Test
    public void headOnFromTheLeftStopsFlush() {
        at(160, 110, resolve(170, 110, ONE));
    }

    @Test
    public void slidesAlongTheWall() {
        at(160, 110, resolve(165, 110, ONE));
        at(160, 120, resolve(165, 120, ONE));
        at(160, 125, resolve(165, 125, ONE));
    }

    @Test
    public void goesAroundOnceTheFarSideIsNearer() {
        at(160, 110, resolve(200, 110, ONE));
        at(250, 110, resolve(215, 110, ONE));
    }

    @Test
    public void settlesInACorner() {
        List<HudWalls.Rect> two = Arrays.asList(new HudWalls.Rect(200, 100, 50, 50), new HudWalls.Rect(150, 150, 100, 50));
        at(160, 130, resolve(185, 140, two));
    }

    @Test
    public void theScreenEdgeIsAWallToo() {
        at(30, 110, resolve(10, 110, Collections.singletonList(new HudWalls.Rect(0, 100, 30, 50))));
    }

    @Test
    public void anOffScreenTargetIsClamped() {
        at(0, 250, resolve(-50, 300, Collections.<HudWalls.Rect>emptyList()));
        at(440, 0, resolve(900, -9, ONE));
    }

    @Test
    public void noFreeSpotKeepsTheCurrentSpot() {
        at(7, 9, resolve(100, 100, Collections.singletonList(new HudWalls.Rect(0, 0, 480, 270))));
    }

    @Test
    public void aTargetInsideAWallGoesToTheNearestFreeSpot() {
        at(160, 100, resolve(205, 100, ONE)); // left and right are both 45 away: the first tried (left) wins
        at(250, 100, resolve(206, 100, ONE));
        at(200, 210, resolve(200, 205, ONE)); // below the wall: 5 away
    }

    @Test
    public void flushAdjacencyIsNotOverlap() {
        assertTrue(HudWalls.free(160, 110, 40, 20, ONE));
        assertTrue(HudWalls.free(250, 110, 40, 20, ONE));
        assertTrue(HudWalls.free(200, 40, 40, 20, ONE));
        assertTrue(HudWalls.free(200, 210, 40, 20, ONE));
        assertFalse(HudWalls.free(160.01f, 110, 40, 20, ONE));
        assertTrue("within EPS of flush", HudWalls.free(160.0005f, 110, 40, 20, ONE));
        assertFalse(HudWalls.overlaps(160, 110, 40, 20, W));
        assertTrue(HudWalls.overlaps(161, 110, 40, 20, W));
    }

    // ----- corner resize: the largest scale that touches no wall (the kit's wallScale) -----

    private static final HudWalls.ScaleProbe GROW_RIGHT = new HudWalls.ScaleProbe() {
        @Override
        public HudWalls.Rect at(float scale) {
            return new HudWalls.Rect(100, 100, 40 * scale, 20 * scale);
        }
    };
    private static final List<HudWalls.Rect> WALL_AT_200 = Collections.singletonList(new HudWalls.Rect(200, 0, 50, 270));

    @Test
    public void growingStopsAtTheWall() {
        assertEquals(2.5f, HudWalls.largestFreeScale(1f, 4f, GROW_RIGHT, WALL_AT_200), 1e-3f);
        assertEquals(2f, HudWalls.largestFreeScale(1f, 2f, GROW_RIGHT, WALL_AT_200), 0f);
    }

    @Test
    public void shrinkingIsAlwaysAllowed() {
        assertEquals(1.5f, HudWalls.largestFreeScale(3f, 1.5f, GROW_RIGHT, WALL_AT_200), 0f);
        assertEquals(2.75f, HudWalls.largestFreeScale(3f, 2.75f, GROW_RIGHT, WALL_AT_200), 0f);
    }

    @Test
    public void growingWhileAlreadyOverlappingStaysPut() {
        assertEquals(3f, HudWalls.largestFreeScale(3f, 4f, GROW_RIGHT, WALL_AT_200), 0f);
    }

    // ----- the same for an item drawn only at whole steps k / sf (Minecraft's font) -----

    private static final List<HudWalls.Rect> WALL_AT_190 = Collections.singletonList(new HudWalls.Rect(190, 0, 50, 270));

    @Test
    public void aSteppedItemStopsOnTheLastStepThatFits() {
        // room up to 2.25; the smooth search parks just under it, the stepped one keeps the 2.0 step (GUI 2)
        assertEquals(2.25f, HudWalls.largestFreeScale(1f, 3f, GROW_RIGHT, WALL_AT_190), 1e-3f);
        assertEquals(2f, HudWalls.largestFreeStep(1f, 3f, 2, GROW_RIGHT, WALL_AT_190), 0f);
        // GUI 3: steps of a third, the last one under 2.25 is 2.0 as well; GUI 4: 2.25 itself fits
        assertEquals(2f, HudWalls.largestFreeStep(1f, 3f, 3, GROW_RIGHT, WALL_AT_190), 1e-6f);
        assertEquals(2.25f, HudWalls.largestFreeStep(1f, 3f, 4, GROW_RIGHT, WALL_AT_190), 0f);
    }

    @Test
    public void aSteppedItemThatCannotGrowKeepsItsStep() {
        assertEquals(2f, HudWalls.largestFreeStep(2f, 3f, 2, GROW_RIGHT, WALL_AT_190), 0f); // next step 2.5 hits
        assertEquals(3f, HudWalls.largestFreeStep(3f, 4f, 2, GROW_RIGHT, WALL_AT_190), 0f); // already in the wall
    }

    @Test
    public void aSteppedItemCanAlwaysShrinkOrGrowIntoFreeRoom() {
        assertEquals(1f, HudWalls.largestFreeStep(3f, 1f, 2, GROW_RIGHT, WALL_AT_190), 0f);
        assertEquals(1.5f, HudWalls.largestFreeStep(1f, 1.5f, 2, GROW_RIGHT, WALL_AT_190), 0f);
    }

    @Test
    public void theHotbarZoneIs182By50AtTheBottomCentre() {
        HudWalls.Rect z = HudWalls.hotbarZone(480, 270);
        assertEquals(149f, z.x, 0f);
        assertEquals(220f, z.y, 0f);
        assertEquals(182f, z.w, 0f);
        assertEquals(50f, z.h, 0f);
    }

    // ----- which boxes are walls (the rule the editor feeds; edit-hud 7b, G28, G29, D10) -----

    @Test
    public void everyOtherShownItemIsAWallButTheTabList() {
        assertTrue(HudWalls.isWall("potion", "armor", true, false));
        assertTrue("chat and HUD items are walls to each other (G29)", HudWalls.isWall("potion", "chat", true, false));
        assertTrue(HudWalls.isWall("chat", "keystrokes", true, false));
        assertTrue(HudWalls.isWall("potion", "scoreboard", true, false));
        assertFalse("a hidden item is no wall", HudWalls.isWall("potion", "armor", false, false));
        assertFalse("nor is the item itself", HudWalls.isWall("potion", "potion", true, false));
        assertFalse("the tab list is never an obstacle", HudWalls.isWall("potion", "tablist", true, false));
    }

    @Test
    public void whileTabIsHeldTheTabListsWallsAreScoreboardAndChat() {
        assertTrue(HudWalls.isWall("tablist", "scoreboard", true, true));
        assertTrue(HudWalls.isWall("tablist", "chat", true, true));
        assertFalse("the Cobblify items are hidden while Tab is held", HudWalls.isWall("tablist", "inventory", true, true));
        assertFalse(HudWalls.isWall("tablist", "potion", true, true));
        assertFalse(HudWalls.isWall("tablist", "scoreboard", false, true));
    }

    @Test
    public void chatsHotbarWallIsTheHotbarOnly() {
        HudWalls.Rect chat = HudWalls.hotbarWall("chat", 480, 270);
        assertEquals(149f, chat.x, 0f);
        assertEquals(248f, chat.y, 0f);
        assertEquals(182f, chat.w, 0f);
        assertEquals(22f, chat.h, 0f);
        HudWalls.Rect other = HudWalls.hotbarWall("potion", 480, 270);
        assertEquals(220f, other.y, 0f);
        assertEquals(50f, other.h, 0f);
        assertEquals(50f, HudWalls.hotbarWall("tablist", 480, 270).h, 0f);
    }
}
