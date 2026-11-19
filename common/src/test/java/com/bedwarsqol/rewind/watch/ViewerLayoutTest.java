package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * TASK A9, A10: the viewer bar's geometry (mockup {@code drawBar}), time and x, marker hit rects, seek on release.
 * Parity B11 (Q1): the bar sits at the top edge and its camera menu and the flight-speed bar open downward, clear of
 * the hotbar, hearts and XP bar.
 */
public class ViewerLayoutTest {

    static final long LEN = 406_600_000L;
    static final int D = RewindFormat.MARKER_DEATH, F = RewindFormat.MARKER_FINAL_KILL, K = RewindFormat.MARKER_KEY;

    static ViewerLayout layout(float w, long len, long[] us, int[] kinds) {
        return ViewerLayout.of(w, 540, GalleryModelTest.MC, len, us, kinds);
    }

    static ViewerLayout plain(float w) {
        return layout(w, LEN, new long[]{60_000_000L, 200_000_000L}, new int[]{D, F});
    }

    static float right(float[] r) {
        return r[0] + r[2];
    }

    @Test
    public void geometryAtNineSixtyMatchesTheMockupAtTheTopEdge() {
        ViewerLayout l = plain(960);
        float by = 2;
        assertArrayEquals(new float[]{2, by, 956, 26}, l.bar, 0f);
        assertArrayEquals(new float[]{7, by + 5, 16, 16}, l.play, 0f);
        // camera key: 7 + widest (icon 14 + "Free camera") + 6 + 6 + 7
        float camW = 7 + (14 + GalleryModelTest.MC.width("Free camera", ViewerLayout.FONT)) + 6 + 6 + 7;
        assertArrayEquals(new float[]{2 + 956 - 5 - camW, by + 5, camW, 16}, l.camera, 0f);
        float spW = GalleryModelTest.MC.width("0.25x", ViewerLayout.FONT) + 12;
        assertArrayEquals(new float[]{l.camera[0] - 4 - spW, by + 5, spW, 16}, l.speed, 0f);
        assertEquals(l.speed[0] - 9, l.timeRight, 0f);
        float wMax = GalleryModelTest.MC.width("0:00", ViewerLayout.FONT)
                + GalleryModelTest.MC.width(" / 6:47", ViewerLayout.FONT);
        assertEquals(l.timeRight - wMax, l.timeLeft, 0f);
        assertEquals(2 + 5 + 16 + 7, l.stripX, 0f);
        assertEquals(l.timeLeft - 9 - l.stripX, l.stripW, 0f);
        assertEquals(by + 8, l.stripY, 0f);
        assertEquals(10, l.stripH, 0f);
        assertEquals(" / 6:47", l.totalLabel());
    }

    @Test
    public void everyWidthFromThreeTwentyToNineSixtyKeepsPartsInOrder() {
        for (int w = 320; w <= 960; w++) {
            ViewerLayout l = plain(w);
            assertTrue(right(l.bar) <= w - 2 + 1e-3f);
            assertTrue("w " + w, right(l.play) < l.stripX - 1);
            assertTrue("w " + w, l.stripW >= 40);
            assertTrue("w " + w, l.stripX + l.stripW < l.timeLeft);
            assertTrue("w " + w, l.timeRight < l.speed[0]);
            assertTrue("w " + w, right(l.speed) < l.camera[0]);
            assertTrue("w " + w, right(l.camera) <= right(l.bar) - 5 + 1e-3f);
        }
    }

    @Test
    public void timeAndXRoundTripWithinOnePixel() {
        for (int w : new int[]{320, 427, 641, 683, 960}) {
            ViewerLayout l = plain(w);
            float inner = l.stripW - 2;
            for (int x = (int) Math.ceil(l.stripX + 1); x <= l.stripX + 1 + inner; x++) {
                assertEquals("w " + w + " x " + x, x, l.xOf(l.timeAt(x)), 1f);
            }
            long perPixel = (long) Math.ceil(LEN / inner);
            for (long t = 0; t <= LEN; t += 1_234_567L) {
                assertEquals(t, l.timeAt(l.xOf(t)), perPixel);
            }
            assertEquals(l.stripX + 1, l.xOf(0), 0f);
            assertEquals(l.stripX + 1 + inner, l.xOf(LEN), 1e-3f);
            assertEquals(0, l.timeAt(l.stripX - 50));
            assertEquals(LEN, l.timeAt(l.stripX + l.stripW + 50));
        }
    }

    @Test
    public void zeroLengthDoesNotDivideByZero() {
        ViewerLayout l = layout(427, 0, new long[0], new int[0]);
        assertEquals(l.stripX + 1, l.xOf(0), 0f);
        assertEquals(0, l.timeAt(l.stripX + 30));
    }

    @Test
    public void markerIconsSitOnTheirTimeAndTheirHitRectsClickThem() {
        ViewerLayout l = plain(960);
        assertEquals(2, l.markerCount());
        for (int i = 0; i < 2; i++) {
            float[] icon = l.markerIcon(i), hit = l.markerHit(i);
            long t = i == 0 ? 60_000_000L : 200_000_000L;
            assertEquals(Math.round(l.xOf(t) - 7 / 2f), icon[0], 0f);
            assertEquals(l.stripY + 1, icon[1], 0f);
            assertArrayEquals(new float[]{icon[0] - 1, l.stripY - 1, 9, 12}, hit, 0f);
            ViewerLayout.Hit h = l.hit(icon[0] + 3, icon[1] + 3, false);
            assertSame(ViewerLayout.Part.MARKER, h.part);
            assertEquals(i, h.index);
        }
        // the key marker is 5 wide
        ViewerLayout k = layout(960, LEN, new long[]{100_000_000L}, new int[]{K});
        assertEquals(5, k.markerIcon(0)[2], 0f);
    }

    @Test
    public void markersOnTheSamePixelStayClickable() {
        long t = 100_000_000L;
        ViewerLayout l = layout(427, LEN, new long[]{t, t, t + 30_000, t}, new int[]{D, F, D, K});
        assertEquals(4, l.markerCount());
        // a deterministic fan: each icon starts STEP px right of the one drawn before it
        for (int i = 1; i < 4; i++) {
            assertEquals(l.markerIcon(i - 1)[0] + ViewerLayout.MARKER_STEP, l.markerIcon(i)[0], 0f);
        }
        assertEvery(l, 4);
    }

    @Test
    public void markersCrowdedAtTheEndFanInsideTheStrip() {
        ViewerLayout l = layout(427, LEN, new long[]{LEN, LEN, LEN}, new int[]{D, D, D});
        for (int i = 0; i < 3; i++) {
            float[] icon = l.markerIcon(i);
            assertTrue(icon[0] >= l.stripX + 1);
            assertTrue(icon[0] + icon[2] <= l.stripX + l.stripW - 1 + 1e-3f);
        }
        assertEvery(l, 3);
        ViewerLayout s = layout(427, LEN, new long[]{0, 0, 0}, new int[]{D, D, D});
        assertTrue(s.markerIcon(0)[0] >= s.stripX + 1);
        assertEvery(s, 3);
    }

    /** Scanning the strip finds every marker on top somewhere. */
    private static void assertEvery(ViewerLayout l, int n) {
        Set<Integer> seen = new HashSet<Integer>();
        for (float x = l.stripX - 2; x < l.stripX + l.stripW + 2; x += 0.5f) {
            ViewerLayout.Hit h = l.hit(x, l.stripY + 5, false);
            if (h.part == ViewerLayout.Part.MARKER) seen.add(h.index);
        }
        assertEquals(n, seen.size());
    }

    @Test
    public void hitPartsOfTheBar() {
        ViewerLayout l = plain(640);
        assertSame(ViewerLayout.Part.PLAY, l.hit(l.play[0] + 2, l.play[1] + 2, false).part);
        assertSame(ViewerLayout.Part.SPEED, l.hit(l.speed[0] + 2, l.speed[1] + 2, false).part);
        assertSame(ViewerLayout.Part.CAMERA, l.hit(l.camera[0] + 2, l.camera[1] + 2, false).part);
        // between markers on the timeline, and on its recess edge
        float x = (l.markerIcon(0)[0] + 10 + l.markerIcon(1)[0]) / 2f;
        assertSame(ViewerLayout.Part.STRIP, l.hit(x, l.stripY + 2, false).part);
        assertSame(ViewerLayout.Part.STRIP, l.hit(l.stripX - 0.5f, l.bar[1] + 4.5f, false).part);
        // the time text sits on the bar but is no control
        assertSame(ViewerLayout.Part.BAR, l.hit(l.timeRight - 2, l.bar[1] + 12, false).part);
        assertSame(ViewerLayout.Part.NONE, l.hit(300, 100, false).part);
        assertFalse(l.over(300, 100));
        assertTrue(l.over(l.bar[0] + 1, l.bar[1] + 1));
    }

    @Test
    public void cameraDropdownOpensDownwardBelowTheKey() {
        ViewerLayout l = plain(640);
        float[] dd = l.dropdown();
        assertEquals(l.camera[0], dd[0], 0f);
        assertEquals(l.camera[2], dd[2], 0f);
        assertEquals(1 + 3 + 2 * 14 + 3 + 1, dd[3], 0f);
        assertEquals(l.bar[1] + l.bar[3] + 3, dd[1], 0f);
        for (int i = 0; i < 2; i++) {
            float[] r = l.dropdownRow(i);
            assertArrayEquals(new float[]{dd[0] + 1, dd[1] + 4 + 14 * i, dd[2] - 2, 14}, r, 0f);
            ViewerLayout.Hit h = l.hit(r[0] + 5, r[1] + 5, true);
            assertSame(ViewerLayout.Part.CAMERA_OPTION, h.part);
            assertEquals(i, h.index);
            assertSame("closed: no dropdown", ViewerLayout.Part.NONE, l.hit(r[0] + 5, r[1] + 5, false).part);
        }
        assertSame(ViewerLayout.Part.DROPDOWN, l.hit(dd[0] + 1, dd[1] + 1, true).part);
        assertEquals(Arrays.asList("You", "Free camera"), Arrays.asList(ViewerLayout.CAMERA_LABELS));
    }

    private static boolean intersects(float[] a, float[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    /**
     * B11: at GUI scales 1-4 of common windows, nothing the viewer draws (the bar, the open camera menu, the flight
     * speed bar) touches vanilla's hotbar (182 x 22 at the bottom centre), XP bar (5 px above it) or the hearts,
     * armour, hunger and air rows (up to 59 px above the bottom), as {@code GuiIngame} lays them out.
     */
    @Test
    public void theBarItsMenuAndTheSpeedBarNeverCoverTheHotbarHeartsOrXpBar() {
        int[][] displays = {{1920, 1080}, {1280, 720}, {854, 480}, {2560, 1440}, {1366, 768}};
        for (int[] d : displays) {
            for (int scale = 1; scale <= 4; scale++) {
                float w = (float) Math.ceil(d[0] / (double) scale), h = (float) Math.ceil(d[1] / (double) scale);
                if (w < 320 || h < 240) {
                    continue; // vanilla never picks a scale that small
                }
                ViewerLayout l = ViewerLayout.of(w, h, GalleryModelTest.MC, LEN, new long[]{60_000_000L},
                        new int[]{D});
                float cx = w / 2f;
                float[][] hud = {
                        {cx - 91, h - 22, 182, 22},      // hotbar
                        {cx - 91, h - 29, 182, 5},       // XP bar
                        {cx - 91, h - 39, 81, 9},        // hearts
                        {cx - 91, h - 49, 81, 9},        // a second heart row or armour
                        {cx - 91, h - 59, 81, 9},        // absorption rows
                        {cx + 10, h - 39, 81, 9},        // hunger
                        {cx + 10, h - 49, 81, 9},        // air
                };
                float[] fly = {cx - 46, ViewerLayout.flySpeedTop() - 1, 92, 10};
                String at = (int) w + "x" + (int) h + " scale " + scale;
                for (float[] r : hud) {
                    assertFalse(at + " bar", intersects(l.bar, r));
                    assertFalse(at + " camera menu", intersects(l.dropdown(), r));
                    assertFalse(at + " speed bar", intersects(fly, r));
                }
                assertTrue(at + ": the bar is at the top", l.bar[1] + l.bar[3] < h / 4f);
                assertTrue(at + ": the speed bar is below the bar", fly[1] > l.bar[1] + l.bar[3]);
            }
        }
    }

    // ------------------------------------------------------------------ Phase 14: players in the camera menu (D1, D3)

    static ViewerLayout withPlayers(float w, float h, String[] players, int camera) {
        return ViewerLayout.of(w, h, GalleryModelTest.MC, LEN, new long[]{60_000_000L, 200_000_000L},
                new int[]{D, F}, players, camera);
    }

    @Test
    public void theCameraMenuListsYouFreeCameraAndEveryPlayer() {
        ViewerLayout l = withPlayers(640, 540, new String[]{"Alice", "Bob"}, 0);
        assertEquals(4, l.cameraOptionCount());
        assertEquals(Arrays.asList("You", "Free camera", "Alice", "Bob"), Arrays.asList(l.cameraLabel(0),
                l.cameraLabel(1), l.cameraLabel(2), l.cameraLabel(3)));
        assertEquals(2, ViewerLayout.FIRST_PLAYER);
        float[] dd = l.dropdown();
        assertEquals("short names: one column as wide as the key", l.camera[0], dd[0], 0f);
        assertEquals(l.camera[2], dd[2], 0f);
        assertEquals(1 + 3 + 4 * 14 + 3 + 1, dd[3], 0f);
        for (int i = 0; i < 4; i++) {
            float[] r = l.dropdownRow(i);
            assertArrayEquals(new float[]{dd[0] + 1, dd[1] + 4 + 14 * i, dd[2] - 2, 14}, r, 0f);
            ViewerLayout.Hit h = l.hit(r[0] + 5, r[1] + 5, true);
            assertSame(ViewerLayout.Part.CAMERA_OPTION, h.part);
            assertEquals(i, h.index);
        }
        // picking a player's row asks for that option (the viewer rides player option - FIRST_PLAYER)
        ViewerLayout.Pointer p = new ViewerLayout.Pointer();
        float[] bob = l.dropdownRow(3);
        p.down(l, bob[0] + 2, bob[1] + 2, true, false);
        ViewerLayout.Action a = p.up(l, bob[0] + 3, bob[1] + 3);
        assertSame(ViewerLayout.Action.Kind.CAMERA, a.kind);
        assertEquals(3, a.index);
        // no players: You and Free camera only
        assertEquals(2, withPlayers(640, 540, new String[0], 0).cameraOptionCount());
        assertEquals(2, withPlayers(640, 540, null, 0).cameraOptionCount());
    }

    @Test
    public void aLongNameWidensTheMenuNotTheKey() {
        String longName = "Abcdefghijklmnop";
        ViewerLayout plain = plain(640);
        ViewerLayout l = withPlayers(640, 540, new String[]{longName}, 0);
        assertArrayEquals("the bar does not move", plain.camera, l.camera, 0f);
        assertEquals(plain.stripW, l.stripW, 0f);
        float[] dd = l.dropdown();
        float need = 7 + ViewerLayout.CAMERA_ICON + GalleryModelTest.MC.width(longName, ViewerLayout.FONT) + 7;
        assertTrue("the menu fits the name: " + dd[2] + " >= " + need, dd[2] >= need);
        assertEquals("right-aligned under the key", right(l.camera), right(dd), 1e-3f);
    }

    @Test
    public void theKeyShowsTheRiddenPlayerWithTheNameTrimmedToFit() {
        ViewerLayout you = withPlayers(640, 540, new String[]{"Bob", "Abcdefghijklmnop"}, 0);
        assertEquals(0, you.camera());
        assertEquals("You", you.cameraKeyLabel());
        assertEquals("Free camera", withPlayers(640, 540, new String[]{"Bob"}, 1).cameraKeyLabel());
        ViewerLayout bob = withPlayers(640, 540, new String[]{"Bob", "Abcdefghijklmnop"}, 2);
        assertEquals(2, bob.camera());
        assertEquals("Bob", bob.cameraKeyLabel());
        assertArrayEquals(you.camera, bob.camera, 0f);
        ViewerLayout longOne = withPlayers(640, 540, new String[]{"Bob", "Abcdefghijklmnop"}, 3);
        assertArrayEquals("riding a long name keeps the key's width", you.camera, longOne.camera, 0f);
        String shown = longOne.cameraKeyLabel();
        assertTrue(shown, "Abcdefghijklmnop".startsWith(shown) && shown.length() >= 3 && shown.length() < 16);
        assertTrue(GalleryModelTest.MC.width(shown, ViewerLayout.FONT)
                <= GalleryModelTest.MC.width("Free camera", ViewerLayout.FONT));
        assertTrue(GalleryModelTest.MC.width(shown + "Abcdefghijklmnop".charAt(shown.length()), ViewerLayout.FONT)
                > GalleryModelTest.MC.width("Free camera", ViewerLayout.FONT));
        assertEquals("an option that does not exist shows your view", "You",
                withPlayers(640, 540, new String[]{"Bob"}, 7).cameraKeyLabel());
    }

    /** B11 with a full lobby: the menu opens more columns instead of reaching the hotbar, hearts or XP bar. */
    @Test
    public void aFullLobbyOpensColumnsThatStayClearOfTheHud() {
        String[] players = new String[15];
        for (int i = 0; i < players.length; i++) players[i] = "Player_" + (char) ('a' + i) + "name";
        int[][] displays = {{1920, 1080}, {1280, 720}, {854, 480}, {2560, 1440}, {1366, 768}, {640, 480}};
        boolean sawColumns = false;
        for (int[] d : displays) {
            for (int scale = 1; scale <= 4; scale++) {
                float w = (float) Math.ceil(d[0] / (double) scale), h = (float) Math.ceil(d[1] / (double) scale);
                if (w < 320 || h < 240) continue;
                ViewerLayout l = withPlayers(w, h, players, 0);
                assertEquals(17, l.cameraOptionCount());
                float cx = w / 2f;
                float[][] hud = {{cx - 91, h - 22, 182, 22}, {cx - 91, h - 29, 182, 5}, {cx - 91, h - 59, 81, 37},
                        {cx + 10, h - 49, 81, 27}};
                String at = (int) w + "x" + (int) h + " scale " + scale;
                float[] dd = l.dropdown();
                assertTrue(at, dd[0] >= 0 && right(dd) <= w);
                assertTrue(at, dd[1] + dd[3] <= h - ViewerLayout.HUD_CLEAR);
                for (float[] r : hud) assertFalse(at + " menu over the HUD", intersects(dd, r));
                if (dd[2] > l.camera[2] + 1) sawColumns = true;
                for (int i = 0; i < 17; i++) {
                    float[] r = l.dropdownRow(i);
                    assertTrue(at + " row " + i + " inside the card", r[0] >= dd[0] && right(r) <= right(dd)
                            && r[1] >= dd[1] && r[1] + r[3] <= dd[1] + dd[3]);
                    ViewerLayout.Hit hit = l.hit(r[0] + r[2] / 2, r[1] + r[3] / 2, true);
                    assertSame(at, ViewerLayout.Part.CAMERA_OPTION, hit.part);
                    assertEquals(at, i, hit.index);
                    for (int j = 0; j < i; j++) assertFalse(at + " rows overlap", intersects(r, l.dropdownRow(j)));
                }
            }
        }
        assertTrue("short windows get a second column", sawColumns);
    }

    @Test
    public void theRiddenPlayersNameSitsUnderTheBar() {
        ViewerLayout l = plain(427);
        assertTrue(ViewerLayout.riddenNameTop() > l.bar[1] + l.bar[3]);
        assertTrue("clear of the hotbar region at the smallest window",
                ViewerLayout.riddenNameTop() + 9 < 240 - ViewerLayout.HUD_CLEAR);
    }

    @Test
    public void timeLabels() {
        ViewerLayout l = plain(960);
        assertEquals("1:05", l.timeLabel(65_900_000L));
        assertEquals("6:47", l.timeLabel(LEN));
        float tw = GalleryModelTest.MC.width(" / 6:47", ViewerLayout.FONT);
        assertEquals(l.timeRight - tw - GalleryModelTest.MC.width("1:05", ViewerLayout.FONT),
                l.timeX(65_900_000L), 0f);
        assertEquals(l.timeRight - tw, l.totalX(), 0f);
    }

    @Test
    public void speedLabels() {
        assertEquals("0.25x", ViewerLayout.speedLabel(0.25f));
        assertEquals("0.5x", ViewerLayout.speedLabel(0.5f));
        assertEquals("1x", ViewerLayout.speedLabel(1f));
        assertEquals("2x", ViewerLayout.speedLabel(2f));
        assertEquals("4x", ViewerLayout.speedLabel(4f));
    }

    @Test
    public void knobIsCentredOnThePlayhead() {
        ViewerLayout l = plain(640);
        float x = l.xOf(100_000_000L);
        assertArrayEquals(new float[]{x - 4, l.bar[1] + 5, 8, 16}, l.knob(100_000_000L), 0f);
    }

    @Test
    public void fromATimelineMarkersCountFromTheGameStart() {
        long start = 30_000_000L, end = start + LEN;
        RecordingMeta meta = new RecordingMeta();
        meta.gameStartUs = start;
        meta.endUs = end;
        Timeline tl = Timeline.build(1_000_000L, end, Timeline.NONE, 0,
                Arrays.asList(new Timeline.Marker(start + 60_000_000L, D)), meta);
        ViewerLayout l = ViewerLayout.of(960, 540, GalleryModelTest.MC, tl);
        assertEquals(1, l.markerCount());
        assertEquals(D, l.markerKind(0));
        assertEquals(Math.round(l.xOf(60_000_000L) - 3.5f), l.markerIcon(0)[0], 0f);
        assertEquals(" / 6:47", l.totalLabel());
    }

    // ------------------------------------------------------------------ pointer: clicks, marker jumps, seek on release

    @Test
    public void dragOnTheTimelinePreviewsAndSeeksOnRelease() {
        ViewerLayout l = plain(640);
        ViewerLayout.Pointer p = new ViewerLayout.Pointer();
        float x1 = l.xOf(10_000_000L) + 0.2f, x2 = l.xOf(300_000_000L);
        ViewerLayout.Action a = p.down(l, x1, l.stripY + 2, false, false);
        assertSame(ViewerLayout.Action.Kind.NONE, a.kind);
        assertTrue(p.dragging());
        assertEquals(l.timeAt(x1), p.previewUs());
        p.move(l, x2);
        assertEquals(l.timeAt(x2), p.previewUs());
        a = p.up(l, x2 + 400, l.bar[1] - 50);
        assertSame(ViewerLayout.Action.Kind.SEEK, a.kind);
        assertEquals(LEN, a.timeUs);
        assertFalse(p.dragging());
        assertSame(ViewerLayout.Action.Kind.NONE, p.up(l, x2, l.stripY).kind);
    }

    @Test
    public void aClickOnTheTimelineSeeksThere() {
        ViewerLayout l = plain(640);
        ViewerLayout.Pointer p = new ViewerLayout.Pointer();
        float x = (l.markerIcon(0)[0] + 10 + l.markerIcon(1)[0]) / 2f;
        p.down(l, x, l.stripY + 2, false, false);
        ViewerLayout.Action a = p.up(l, x, l.stripY + 2);
        assertSame(ViewerLayout.Action.Kind.SEEK, a.kind);
        assertEquals(l.timeAt(x), a.timeUs);
    }

    @Test
    public void markerClicksJump() {
        ViewerLayout l = plain(640);
        ViewerLayout.Pointer p = new ViewerLayout.Pointer();
        float[] icon = l.markerIcon(1);
        p.down(l, icon[0] + 2, icon[1] + 2, false, false);
        assertFalse(p.dragging());
        ViewerLayout.Action a = p.up(l, icon[0] + 3, icon[1] + 3);
        assertSame(ViewerLayout.Action.Kind.MARKER, a.kind);
        assertEquals(1, a.index);
        // released elsewhere: nothing
        p.down(l, icon[0] + 2, icon[1] + 2, false, false);
        assertSame(ViewerLayout.Action.Kind.NONE, p.up(l, l.play[0] + 2, l.play[1] + 2).kind);
    }

    @Test
    public void keysOnTheBar() {
        ViewerLayout l = plain(640);
        ViewerLayout.Pointer p = new ViewerLayout.Pointer();
        p.down(l, l.play[0] + 2, l.play[1] + 2, false, false);
        assertSame(ViewerLayout.Part.PLAY, p.pressed().part);
        assertSame(ViewerLayout.Action.Kind.PLAY_PAUSE, p.up(l, l.play[0] + 3, l.play[1] + 3).kind);
        assertSame(null, p.pressed());
        p.down(l, l.speed[0] + 2, l.speed[1] + 2, false, false);
        assertSame(ViewerLayout.Action.Kind.SPEED_UP, p.up(l, l.speed[0] + 2, l.speed[1] + 2).kind);
        // right-click steps the speed down at once
        assertSame(ViewerLayout.Action.Kind.SPEED_DOWN, p.down(l, l.speed[0] + 2, l.speed[1] + 2, false, true).kind);
        p.down(l, l.camera[0] + 2, l.camera[1] + 2, false, false);
        assertSame(ViewerLayout.Action.Kind.CAMERA_MENU, p.up(l, l.camera[0] + 2, l.camera[1] + 2).kind);
        float[] free = l.dropdownRow(1);
        p.down(l, free[0] + 2, free[1] + 2, true, false);
        ViewerLayout.Action a = p.up(l, free[0] + 2, free[1] + 2);
        assertSame(ViewerLayout.Action.Kind.CAMERA, a.kind);
        assertEquals(1, a.index);
    }

    @Test
    public void aPressOutsideAnOpenDropdownClosesIt() {
        ViewerLayout l = plain(640);
        ViewerLayout.Pointer p = new ViewerLayout.Pointer();
        // on the bar's empty part: closes and does nothing else
        ViewerLayout.Action a = p.down(l, l.timeRight - 2, l.bar[1] + 12, true, false);
        assertSame(ViewerLayout.Action.Kind.CLOSE_MENU, a.kind);
        assertSame(ViewerLayout.Action.Kind.NONE, p.up(l, l.timeRight - 2, l.bar[1] + 12).kind);
        // in the world: closes
        assertSame(ViewerLayout.Action.Kind.CLOSE_MENU, p.down(l, 300, 100, true, false).kind);
        // on the play key: closes the menu and the key still works on release
        assertSame(ViewerLayout.Action.Kind.CLOSE_MENU, p.down(l, l.play[0] + 2, l.play[1] + 2, true, false).kind);
        assertSame(ViewerLayout.Action.Kind.PLAY_PAUSE, p.up(l, l.play[0] + 2, l.play[1] + 2).kind);
    }
}
