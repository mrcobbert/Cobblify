package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The pause menu with the "Watch Last Game" row (G35, mockup {@code drawPause}): vanilla ids 0, 4, 5, 6, 7 move up 12,
 * Disconnect (1) moves down 12, the row goes at {@code h/4+92}, 200 x 20, centred.
 */
public class PauseMenuLayoutTest {

    /** Vanilla GuiIngameMenu.initGui: {id, x, y, w, h} at screen size w x h (i = -16). */
    private static int[][] vanilla(int w, int h) {
        int q = h / 4;
        return new int[][]{
                {4, w / 2 - 100, q + 24 - 16, 200, 20},
                {5, w / 2 - 100, q + 48 - 16, 98, 20},
                {6, w / 2 + 2, q + 48 - 16, 98, 20},
                {0, w / 2 - 100, q + 96 - 16, 98, 20},
                {7, w / 2 + 2, q + 96 - 16, 98, 20},
                {1, w / 2 - 100, q + 120 - 16, 200, 20},
        };
    }

    @Test
    public void shiftsMatchTheMockup() {
        for (int[] size : new int[][]{{960, 540}, {320, 240}, {427, 240}, {683, 384}, {641, 361}}) {
            int w = size[0], h = size[1];
            int y0 = h / 4 - 16 - 12; // mockup: vanilla i = -16, raised 12 for the extra row
            int[][] expected = {{4, y0 + 24}, {5, y0 + 48}, {6, y0 + 48}, {0, y0 + 96}, {7, y0 + 96}, {1, y0 + 144}};
            int[][] buttons = vanilla(w, h);
            for (int i = 0; i < buttons.length; i++) {
                int id = buttons[i][0];
                assertEquals("id " + id, expected[i][1], buttons[i][2] + PauseMenuLayout.shift(id));
            }
            assertArrayEquals(new int[]{w / 2 - 100, y0 + 120, 200, 20}, PauseMenuLayout.watchButton(w, h));
        }
    }

    @Test
    public void otherButtonsStayPut() {
        for (int id : new int[]{2, 3, 8, 14, 200, PauseMenuLayout.WATCH_BUTTON_ID, -1}) {
            assertEquals(0, PauseMenuLayout.shift(id));
        }
    }

    @Test
    public void theRowSitsBetweenOptionsAndDisconnectWithoutOverlap() {
        int w = 427, h = 240;
        int[] row = PauseMenuLayout.watchButton(w, h);
        int optionsBottom = h / 4 + 80 + PauseMenuLayout.shift(0) + 20;
        int disconnectTop = h / 4 + 104 + PauseMenuLayout.shift(1);
        assertTrue(row[1] >= optionsBottom);
        assertTrue(row[1] + row[3] <= disconnectTop);
        assertEquals(4, disconnectTop - (row[1] + row[3])); // vanilla's 4 px gap between rows
    }

    @Test
    public void labelAndIdAreFixed() {
        assertEquals("Watch Last Game", PauseMenuLayout.WATCH_LABEL);
        for (int[] b : vanilla(427, 240)) assertTrue(b[0] != PauseMenuLayout.WATCH_BUTTON_ID);
    }
}
