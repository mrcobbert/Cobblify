package com.bedwarsqol.hud;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The chat's slot to a fraction of a GUI pixel (edit-hud code review round 2, I1): Edit HUD resolves walls and moves by
 * one screen pixel in fractional GUI px, so the chat's stored slot keeps the fraction, as every other item's does. The
 * slot rule (bottom kept, anchor from the visible box) is cycle B's and unchanged.
 */
public class ChatSubPixelTest {

    private static final float SW = 960, SH = 540;

    @Test
    public void theExactSlotReproducesTheBoxAndRoundsToTheIntSlot() {
        float slotH = 90f, rowH = 9f;
        float[][] boxes = {{26.666656f, 60f, 324f, 9f}, {100.333336f, 500.25f, 324f, 27f}, {600.5f, 240.75f, 324f, 9f},
                {2f, 512f, 324f, 9f}, {318.4f, 10.6f, 324f, 18f}};
        for (float[] box : boxes) {
            float[] exact = ChatLayout.exactStoredSlot(box, slotH, SW, SH);
            int[] whole = ChatLayout.storedSlot(box, slotH, SW, SH);
            HudModuleState st = new HudModuleState(0, 0, -1, 1f, true).withExactPosition(exact[0], exact[1], (int) exact[2]);
            assertEquals("the int fields are B's storedSlot", whole[0], st.x);
            assertEquals(whole[1], st.y);
            assertEquals(whole[2], st.anchor);
            float[] auto = {2f, SH - 28f - slotH, box[2], slotH};
            float[] slot = ChatLayout.slot(st.exactX(), st.exactY(), st.anchor, auto, SW, SH);
            float[] drawn = ChatLayout.visibleBox(slot, Math.round(box[3] / rowH), rowH, SW, SH);
            assertEquals("x of " + box[0], box[0], drawn[0], 1e-3f);
            assertEquals("bottom of " + box[1], box[1] + box[3], drawn[1] + drawn[3], 1e-3f);
        }
    }

    @Test
    public void aWallSafeSpotStaysClearOfTheWall() {
        // the reviewer's case: one row of chat (324 x 9), slot 90, dragged towards (40, 60) next to a wall
        List<HudWalls.Rect> wall = Collections.singletonList(new HudWalls.Rect(350.66666f, 30f, 40f, 100f));
        float[] at = HudWalls.resolve(40f, 60f, 324f, 9f, SW, SH, wall, 2f, 60f);
        assertEquals(26.666656f, at[0], 1e-3f);
        float[] exact = ChatLayout.exactStoredSlot(new float[]{at[0], at[1], 324f, 9f}, 90f, SW, SH);
        HudModuleState st = new HudModuleState(0, 0, -1, 1f, true).withExactPosition(exact[0], exact[1], (int) exact[2]);
        float[] auto = {2f, SH - 28f - 90f, 324f, 90f};
        float[] drawn = ChatLayout.visibleBox(ChatLayout.slot(st.exactX(), st.exactY(), st.anchor, auto, SW, SH), 1, 9f, SW, SH);
        assertTrue("drawn " + drawn[0] + ".." + (drawn[0] + drawn[2]), HudWalls.free(drawn[0], drawn[1], drawn[2], drawn[3], wall));
    }

    @Test
    public void aOneScreenPixelNudgeMovesTheChat() {
        // GUI scale 3, chat at x = 100: one Alt+Right is 1/3 GUI px, and it sticks
        float[] box = {HudGrid.nudged(100f, 1, 3), 300f, 324f, 9f};
        float[] exact = ChatLayout.exactStoredSlot(box, 90f, SW, SH);
        HudModuleState st = new HudModuleState(0, 0, -1, 1f, true).withExactPosition(exact[0], exact[1], (int) exact[2]);
        float[] auto = {2f, SH - 28f - 90f, 324f, 90f};
        float[] drawn = ChatLayout.visibleBox(ChatLayout.slot(st.exactX(), st.exactY(), st.anchor, auto, SW, SH), 1, 9f, SW, SH);
        assertEquals(100f + 1f / 3f, drawn[0], 1e-3f);
    }
}
