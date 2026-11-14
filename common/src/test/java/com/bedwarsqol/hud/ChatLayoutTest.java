package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The chat as a HUD item. The placement tests marked "contract" pin the chat slot rule that
 * {@code BedwarsHudRenderer.setHudAbsolutePosition} and {@code chatBox} implement with these functions; a port
 * of chat pinning must keep calling them and keep these tests green.
 */
public class ChatLayoutTest {

    private static final float EPS = 1e-3f;
    private static final float SW = 480, SH = 270;

    // ------------------------------------------------------------------ rows

    @Test
    public void closedLineCountNeverExceedsTen() {
        // the Unfocused Height slider at its maximum: floor(1.0 * 160 + 20) / 9 = 20 rows in vanilla
        assertEquals(10, ChatLayout.closedLineCount(20));
        assertEquals(10, ChatLayout.closedLineCount(10));
        assertEquals(5, ChatLayout.closedLineCount(5));
        for (int i = 0; i <= 100; i++) {
            float slider = i / 100f;
            int vanilla = (int) Math.floor(slider * 160f + 20f) / 9;
            assertTrue("slider " + slider, ChatLayout.closedLineCount(vanilla) <= 10);
            assertEquals(Math.min(10, vanilla), ChatLayout.closedLineCount(vanilla));
        }
    }

    @Test
    public void theBoxHugsTheLinesItShows() {
        assertEquals("an empty chat still has a one-row box", 1, ChatLayout.boxRows(0, 10));
        assertEquals(3, ChatLayout.boxRows(3, 10));
        assertEquals(10, ChatLayout.boxRows(25, 10));
        assertEquals(5, ChatLayout.boxRows(25, 5));
    }

    @Test
    public void shownRowsFollowVanillasLoop() {
        assertEquals(10, ChatLayout.shownRows(25, 10, 0));
        assertEquals(20, ChatLayout.shownRows(25, 20, 0));
        assertEquals(15, ChatLayout.shownRows(25, 20, 10));
        assertEquals(3, ChatLayout.shownRows(3, 20, 0));
        assertEquals(0, ChatLayout.shownRows(0, 10, 0));
        assertEquals(0, ChatLayout.shownRows(5, 10, 9));
    }

    // ------------------------------------------------------------------ geometry

    @Test
    public void lineAreaWidthIsVanillasFillWidth() {
        assertEquals(324f, ChatLayout.lineAreaWidth(320, 1f), EPS);
        // ceil(320 / 0.5) + 4 = 644 chat px, drawn at 0.5
        assertEquals(322f, ChatLayout.lineAreaWidth(320, 0.5f), EPS);
        assertEquals((float) (Math.ceil(200 / 0.75f) + 4) * 0.75f, ChatLayout.lineAreaWidth(200, 0.75f), EPS);
    }

    @Test
    public void vanillaSlotSitsOnVanillasBottomLeft() {
        assertArrayEquals(new float[]{2, 270 - 28 - 90, 324, 90}, ChatLayout.vanillaSlot(270, 320, 1f, 10), EPS);
        assertArrayEquals(new float[]{2, 241 - 28 - 45, 322, 45}, ChatLayout.vanillaSlot(241, 320, 0.5f, 10), EPS);
        float[] auto = ChatLayout.autoSlot(ChatLayout.vanillaSlot(270, 320, 1f, 10), 270, 1.5f);
        assertArrayEquals(new float[]{2, 242 - 135, 486, 135}, auto, EPS);
    }

    // ------------------------------------------------------------------ placement (contract)

    /** setHudAbsolutePosition then chatBox, through the same functions the renderer uses. */
    private static float[] roundTrip(float[] requested, int closed, int rows, float s) {
        float rowH = ChatLayout.ROW_HEIGHT * s;
        float slotH = closed * rowH;
        int[] stored = ChatLayout.storedSlot(requested, slotH, SW, SH);
        float[] auto = {2, SH - 28 - slotH, requested[2], slotH};
        float[] slot = ChatLayout.slot(stored[0], stored[1], stored[2], auto, SW, SH);
        return ChatLayout.visibleBox(slot, rows, rowH, SW, SH);
    }

    @Test
    public void slotFromBoxKeepsTheBoxBottom() {
        float[] box = {40, 100, 324, 27};
        float[] slot = ChatLayout.slotFromBox(box, 90);
        assertArrayEquals(new float[]{40, 37, 324, 90}, slot, EPS);
        assertEquals(box[1] + box[3], slot[1] + slot[3], EPS);
    }

    @Test
    public void shortBoxAtTheTopEdgeRoundTrips() {
        for (float s : new float[]{1f, 1.5f}) {
            float rowH = 9 * s;
            float[] requested = {4, 4, 300, 3 * rowH};
            int[] stored = ChatLayout.storedSlot(requested, 10 * rowH, SW, SH);
            assertEquals("top-left third", 0, stored[2]);
            assertTrue("the slot is not clamped", stored[1] < -50);
            float[] back = roundTrip(requested, 10, 3, s);
            // positions are stored as whole GUI px (as for every module): a box 13.5 px a row can come back
            // half a pixel off; at scale 1 it comes back exactly
            assertEquals("scale " + s, 4f, back[1], s == 1f ? EPS : 0.5f);
            assertEquals(4f, back[0], EPS);
            assertEquals(3 * rowH, back[3], EPS);
        }
        // the reviewer's case: 480x270, scale 1, slot top -59
        assertEquals(-59, ChatLayout.storedSlot(new float[]{4, 4, 324, 27}, 90, SW, SH)[1]);
    }

    @Test
    public void rowsGrowingAtTheTopEdgeClampTheBoxNotTheSlot() {
        float[] grown = roundTrip(new float[]{4, 4, 324, 27}, 10, 10, 1f);
        assertEquals(0f, grown[1], EPS);
        assertEquals(90f, grown[1] + grown[3], EPS);
        float[] grown15 = roundTrip(new float[]{4, 4, 300, 3 * 13.5f}, 10, 10, 1.5f);
        assertEquals(0f, grown15[1], EPS);
        assertEquals(135f, grown15[1] + grown15[3], EPS);
    }

    @Test
    public void bottomStaysWhileRowsGrow() {
        for (float s : new float[]{1f, 1.5f}) {
            float rowH = 9 * s, w = 300;
            float[] requested = {10, 240 - 3 * rowH, w, 3 * rowH};
            for (int rows = 3; rows <= 10; rows++) {
                float[] box = roundTrip(requested, 10, rows, s);
                assertEquals("bottom, " + rows + " rows at scale " + s, 240f, box[1] + box[3], 0.5f);
                assertEquals(rows * rowH, box[3], EPS);
                assertEquals(10f, box[0], 0.5f);
            }
            // off the left or right edge: the box's x clamps on screen
            float[] left = roundTrip(new float[]{-20, 240 - 3 * rowH, w, 3 * rowH}, 10, 3, s);
            assertEquals(0f, left[0], EPS);
            float[] right = roundTrip(new float[]{SW - w + 30, 240 - 3 * rowH, w, 3 * rowH}, 10, 3, s);
            assertEquals(SW - w, right[0], EPS);
        }
    }

    @Test
    public void everyAnchorThirdRoundTrips() {
        float s = 1f, rowH = 9f, w = 120f;
        float[] xs = {6, 180, SW - w - 6};
        float[] ys = {6, 120, SH - 6 - 3 * rowH};
        boolean[] anchors = new boolean[9];
        for (float x : xs) {
            for (float y : ys) {
                float[] requested = {x, y, w, 3 * rowH};
                anchors[ChatLayout.storedSlot(requested, 10 * rowH, SW, SH)[2]] = true;
                float[] back = roundTrip(requested, 10, 3, s);
                assertEquals("x at " + x + "," + y, x, back[0], 0.5f);
                assertEquals("y at " + x + "," + y, y, back[1], 0.5f);
            }
        }
        for (int a = 0; a < 9; a++) assertTrue("anchor " + a + " exercised", anchors[a]);
    }

    // ------------------------------------------------------------------ drawing transform

    @Test
    public void callSiteTranslateMapsVanillaChatOntoTheBox() {
        float sh = 270;
        float[][] boxes = {{2, sh - 28}, {40, 100}, {300, 260}};
        float[] scales = {0.5f, 1f, 1.5f};
        for (float[] b : boxes) {
            for (float s : scales) {
                for (float f : new float[]{1f, 0.5f}) {
                    float[] t = ChatLayout.callSiteTranslate(b[0], b[1], s, sh);
                    for (float px = 0; px < 300; px += 37) {
                        for (float py = -90; py <= 0; py += 9) {
                            // GuiIngame translate(0, sh - 48); ours translate(t), scale(s); drawChat translate(2, 20), scale(f)
                            float sx = 0 + t[0] + s * (2 + f * px);
                            float sy = sh - 48 + t[1] + s * (20 + f * py);
                            float vx = 2 + f * px, vy = sh - 28 + f * py;
                            assertEquals(b[0] + s * (vx - 2), sx, EPS);
                            assertEquals(b[1] + s * (vy - (sh - 28)), sy, EPS);
                        }
                    }
                }
            }
        }
        assertArrayEquals(new float[]{0, 0}, ChatLayout.callSiteTranslate(2, sh - 28, 1f, sh), EPS);
    }

    @Test
    public void backgroundOffSkipsOnlyTheLineFill() {
        assertTrue(ChatLayout.lineFill(true));
        assertFalse(ChatLayout.lineFill(false));
    }

    // ------------------------------------------------------------------ click mapping

    @Test
    public void mouseRemapIsTheIdentityAtTheDefaultSpot() {
        for (int sf = 1; sf <= 4; sf++) {
            int sh = 1080 / sf;
            for (int raw = 0; raw < 1920; raw += 7) {
                assertEquals(raw, ChatLayout.toVanillaRawX(raw, sf, 2f, 1f));
            }
            for (int raw = 0; raw < 1080; raw += 5) {
                assertEquals(raw, ChatLayout.toVanillaRawY(raw, sf, sh, sh - 28f, 1f));
            }
        }
    }

    /** Vanilla {@code GuiNewChat.getChatComponent}'s integer math: {row, xInChat} or null. */
    private static int[] vanillaHit(int rawX, int rawY, int sf, float chatScale, int lineCount, int drawn,
                                    int scroll, int chatWidth) {
        int x = rawX / sf - 3;
        int y = rawY / sf - 27;
        x = (int) Math.floor(x / chatScale);
        y = (int) Math.floor(y / chatScale);
        if (x < 0 || y < 0) return null;
        int lc = Math.min(lineCount, drawn);
        if (x <= (int) Math.floor(chatWidth / chatScale) && y < 9 * lc + lc) {
            int k = y / 9 + scroll;
            if (k >= 0 && k < drawn) return new int[]{k, x};
        }
        return null;
    }

    private static int[] hitAt(float gx, float gy, int sf, int sh, float boxLeft, float boxBottom, float s,
                               float chatScale, int rows) {
        int rawX = (int) Math.floor(gx * sf);
        int rawY = sh * sf - 1 - (int) Math.floor(gy * sf); // LWJGL counts device rows from the bottom
        int vx = ChatLayout.toVanillaRawX(rawX, sf, boxLeft, s);
        int vy = ChatLayout.toVanillaRawY(rawY, sf, sh, boxBottom, s);
        return vanillaHit(vx, vy, sf, chatScale, 20, rows, 0, 320);
    }

    @Test
    public void clicksAtAMovedScaledChatHitTheLineDrawnThere() {
        int rows = 6;
        for (int sf = 1; sf <= 4; sf++) {
            int sw = 1920 / sf, sh = 1080 / sf;
            for (float s : new float[]{0.5f, 0.75f, 1f, 1.5f, 2f}) {
                for (float f : new float[]{1f, 0.5f}) {
                    float rowH = 9 * f * s;
                    float w = ChatLayout.lineAreaWidth(320, f) * s;
                    float[][] origins = {
                            {2, sh - 28},                       // vanilla's spot
                            {4, 4 + rows * rowH},               // top-left
                            {sw - w - 4, sh / 2f + rows * rowH / 2}, // right-middle
                            {sw / 3f, sh - 60}};
                    for (float[] b : origins) {
                        String at = "sf " + sf + " s " + s + " f " + f + " box " + b[0] + "," + b[1];
                        for (int k = 0; k < rows; k++) {
                            float cy = b[1] - rowH * (k + 0.5f);
                            for (int c = 0; c < 20; c += 3) {
                                // the centre of character column c (6 chat px wide) on drawn row k
                                float cx = b[0] + s * f * (6 * c + 3);
                                int[] hit = hitAt(cx, cy, sf, sh, b[0], b[1], s, f, rows);
                                assertNotNull(at + " row " + k + " col " + c, hit);
                                assertEquals(at + " row " + k, k, hit[0]);
                                assertTrue(at + " col " + c + " -> x " + hit[1], hit[1] >= 6 * c && hit[1] < 6 * c + 6);
                            }
                        }
                        float midY = b[1] - rowH * 2.5f;
                        assertNull(at + " 1 px left", hitAt(b[0] - 1, midY, sf, sh, b[0], b[1], s, f, rows));
                        assertNull(at + " 1 px right", hitAt(b[0] + w + 1, midY, sf, sh, b[0], b[1], s, f, rows));
                        assertNull(at + " 1 px above", hitAt(b[0] + w / 2, b[1] - rows * rowH - 1, sf, sh, b[0], b[1], s, f, rows));
                    }
                }
            }
        }
    }
}
