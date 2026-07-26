package com.bedwarsqol.gui;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the pure Players-list animation model: first-open seeding (no intro), slide-in append, ghost
 * fade-out with gap compaction, quick-rejoin vs rejoin-after-drop, filter hide/show, removal
 * threshold, and click eligibility. Geometry is trivial (listTop=0, scroll=0, stride=10).
 */
public class PlayersListAnimTest {

    private static final float TOP = 0f, SCROLL = 0f, STRIDE = 10f;
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3), D = new UUID(0, 4);

    private static List<UUID> live(UUID... ids) { return Arrays.asList(ids); }

    private static List<String> names(UUID... ids) {
        List<String> n = new ArrayList<String>();
        for (UUID id : ids) n.add("p" + id.getLeastSignificantBits());
        return n;
    }

    private static void sync(PlayersListAnim a, UUID... ids) {
        a.sync(live(ids), names(ids), null, TOP, SCROLL, STRIDE);
    }

    @Test
    public void firstOpenSeedsToTargetsNoIntro() {
        PlayersListAnim a = new PlayersListAnim();
        sync(a, A, B, C);
        assertEquals(0f, a.get(A).renderY, 0f);
        assertEquals(10f, a.get(B).renderY, 0f);
        assertEquals(20f, a.get(C).renderY, 0f);
        assertEquals(1f, a.get(A).alpha, 0f);
        assertTrue(a.get(A).hittable());
        assertEquals(3, a.shownCount());
        assertEquals(3, a.size());
    }

    @Test
    public void appendSlidesInFromBelow() {
        PlayersListAnim a = new PlayersListAnim();
        sync(a, A, B, C);
        sync(a, A, B, C, D);
        PlayersListAnim.Row d = a.get(D);
        assertNotNull(d);
        assertFalse(d.leaving);
        assertTrue("fading in", d.alpha > 0f && d.alpha < 1f);
        assertTrue("sliding up toward slot 30", d.renderY > 30f && d.renderY < 40f);
        assertFalse("not yet clickable while faint", d.hittable());
        // Existing rows stay put.
        assertEquals(0f, a.get(A).renderY, 0f);
        assertEquals(20f, a.get(C).renderY, 0f);
        // Converge.
        for (int i = 0; i < 30; i++) sync(a, A, B, C, D);
        assertEquals(30f, a.get(D).renderY, 0.01f);
        assertEquals(1f, a.get(D).alpha, 0.01f);
        assertTrue(a.get(D).hittable());
    }

    @Test
    public void leaveGhostsThenClosesGap() {
        PlayersListAnim a = new PlayersListAnim();
        sync(a, A, B, C);
        sync(a, A, C); // B leaves
        assertTrue(a.get(B).leaving);
        assertEquals("ghost still tracked while fading", 3, a.size());
        assertTrue("ghost fading", a.get(B).alpha > 0f && a.get(B).alpha < 1f);
        assertEquals("B excluded from slot count", 2, a.shownCount());
        assertTrue("C sliding up to fill gap", a.get(C).renderY < 20f);
        assertFalse("ghost not clickable", a.get(B).hittable());
        for (int i = 0; i < 30; i++) sync(a, A, C);
        assertNull("faded ghost dropped", a.get(B));
        assertEquals(2, a.size());
        assertEquals(10f, a.get(C).renderY, 0.01f); // C now occupies slot 1
    }

    @Test
    public void quickRejoinKeepsInsertionPosition() {
        PlayersListAnim a = new PlayersListAnim();
        sync(a, A, B, C);
        sync(a, A, C);        // B starts leaving but is still in the map
        assertTrue(a.get(B).leaving);
        sync(a, A, B, C);     // rejoin before drop
        assertFalse(a.get(B).leaving);
        assertTrue(a.get(B).matches);
        // Order preserved A,B,C.
        List<UUID> order = new ArrayList<UUID>();
        for (PlayersListAnim.Row r : a.rows()) order.add(r.uuid);
        assertEquals(Arrays.asList(A, B, C), order);
    }

    @Test
    public void rejoinAfterDropAppendsAtEnd() {
        PlayersListAnim a = new PlayersListAnim();
        sync(a, A, B, C);
        for (int i = 0; i < 30; i++) sync(a, A, C); // let B fully leave + drop
        assertNull(a.get(B));
        sync(a, A, C, B);                            // B rejoins as new
        List<UUID> order = new ArrayList<UUID>();
        for (PlayersListAnim.Row r : a.rows()) order.add(r.uuid);
        assertEquals(Arrays.asList(A, C, B), order);
    }

    @Test
    public void filterHidesAndShows() {
        PlayersListAnim a = new PlayersListAnim();
        sync(a, A, B, C);
        a.sync(live(A, B, C), names(A, B, C), new boolean[]{true, false, true}, TOP, SCROLL, STRIDE);
        assertFalse(a.get(B).matches);
        assertFalse("filtered row not clickable", a.get(B).hittable());
        assertEquals(2, a.shownCount());
        assertTrue("C takes slot 1 while B hidden", a.get(C).renderY < 20f);
        // Unhide.
        for (int i = 0; i < 30; i++) sync(a, A, B, C);
        assertTrue(a.get(B).matches);
        assertEquals(3, a.shownCount());
        assertTrue(a.get(B).hittable());
    }

    @Test
    public void removalThresholdEventuallyDrops() {
        PlayersListAnim a = new PlayersListAnim();
        sync(a, A);
        sync(a); // A leaves
        assertEquals(1, a.size());        // still a fading ghost
        for (int i = 0; i < 40; i++) sync(a);
        assertEquals(0, a.size());
    }

    @Test
    public void leaveTravelsAtMostOneStride() {
        PlayersListAnim a = new PlayersListAnim();
        sync(a, A, B);
        float atLeave = a.get(B).renderY;              // slot 1 → y = 10
        sync(a, A);                                    // B leaves: exit target fixed at atLeave - STRIDE
        float floor = atLeave - STRIDE - 0.01f;
        for (int i = 0; i < 12 && a.get(B) != null; i++) {
            assertTrue("ghost never travels past one stride", a.get(B).renderY >= floor);
            sync(a, A);
        }
    }
}
