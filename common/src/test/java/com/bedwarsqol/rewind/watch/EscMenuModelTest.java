package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * TASK A12, G40; Rewind Export TASK U1: Resume, Rejoin Hypixel (hero), Export and Gallery sharing a row, Quit to Title;
 * Resume restores play; Esc resumes.
 */
public class EscMenuModelTest {

    private static final int KEY_ESCAPE = 1;
    private static final int KEY_RETURN = 28;

    @Test
    public void keysInOrderWithLabels() {
        EscMenuModel.Key[] keys = EscMenuModel.Key.values();
        assertEquals(5, keys.length);
        assertEquals("Resume", EscMenuModel.label(keys[0]));
        assertEquals("Rejoin Hypixel", EscMenuModel.label(keys[1]));
        assertEquals("Export", EscMenuModel.label(keys[2]));
        assertEquals("Gallery", EscMenuModel.label(keys[3]));
        assertEquals("Quit to Title", EscMenuModel.label(keys[4]));
        assertSame(EscMenuModel.Key.EXPORT, keys[2]);
        assertTrue(EscMenuModel.hero(EscMenuModel.Key.REJOIN));
        for (EscMenuModel.Key k : keys) if (k != EscMenuModel.Key.REJOIN) assertFalse(EscMenuModel.hero(k));
    }

    @Test
    public void cardGeometryMatchesTheMockup() {
        EscMenuModel.Layout l = EscMenuModel.layout(960, 540);
        int h = 1 + 9 + 4 * 20 + 3 * 4 + 9 + 1;
        int x = Math.round((960 - 172) / 2f), y = Math.round((540 - h) / 2f);
        assertArrayEquals(new float[]{x, y, 172, h}, l.card(), 0f);
        assertArrayEquals(new float[]{x + 10, y + 10, 152, 20}, l.key(EscMenuModel.Key.RESUME), 0f);
        assertArrayEquals(new float[]{x + 10, y + 34, 152, 20}, l.key(EscMenuModel.Key.REJOIN), 0f);
        // Export and Gallery share the third row, half each with a 4 su gap (mockup drawEsc)
        assertArrayEquals(new float[]{x + 10, y + 58, 74, 20}, l.key(EscMenuModel.Key.EXPORT), 0f);
        assertArrayEquals(new float[]{x + 10 + 74 + 4, y + 58, 74, 20}, l.key(EscMenuModel.Key.GALLERY), 0f);
        assertArrayEquals(new float[]{x + 10, y + 82, 152, 20}, l.key(EscMenuModel.Key.QUIT), 0f);
    }

    @Test
    public void hitTest() {
        EscMenuModel.Layout l = EscMenuModel.layout(427, 240);
        for (EscMenuModel.Key k : EscMenuModel.Key.values()) {
            float[] r = l.key(k);
            assertSame(k, l.hit(r[0] + 1, r[1] + 1));
            assertSame(k, l.hit(r[0] + r[2] - 0.5f, r[1] + r[3] - 0.5f));
            assertNull(l.hit(r[0] - 0.5f, r[1] + 5));
        }
        float[] ex = l.key(EscMenuModel.Key.EXPORT);
        assertNull("the gap between Export and Gallery", l.hit(ex[0] + ex[2] + 2, ex[1] + 5));
        float[] c = l.card();
        assertNull(l.hit(c[0] + 2, c[1] + 2)); // the card itself is no key
        assertTrue(l.insideCard(c[0] + 2, c[1] + 2));
        assertFalse(l.insideCard(c[0] - 1, c[1]));
    }

    @Test
    public void resumeRestoresThePreviousPlayState() {
        EscMenuModel m = new EscMenuModel();
        m.open(true);
        assertTrue(m.resumePlays(false));
        assertFalse("at the end it stays paused", m.resumePlays(true));
        m.open(false);
        assertFalse(m.resumePlays(false));
    }

    @Test
    public void escapeResumes() {
        EscMenuModel m = new EscMenuModel();
        assertSame(EscMenuModel.Key.RESUME, m.keyPressed(KEY_ESCAPE));
        assertNull(m.keyPressed(KEY_RETURN));
        assertNull(m.keyPressed(57));
    }

    @Test
    public void aClickNeedsPressAndReleaseOnTheSameKey() {
        EscMenuModel m = new EscMenuModel();
        EscMenuModel.Layout l = EscMenuModel.layout(960, 540);
        float[] quit = l.key(EscMenuModel.Key.QUIT), gal = l.key(EscMenuModel.Key.GALLERY);
        m.mouseDown(l, quit[0] + 3, quit[1] + 3);
        assertSame(EscMenuModel.Key.QUIT, m.pressed());
        assertNull("released elsewhere", m.mouseUp(l, gal[0] + 3, gal[1] + 3));
        assertNull(m.pressed());
        m.mouseDown(l, quit[0] + 3, quit[1] + 3);
        assertSame(EscMenuModel.Key.QUIT, m.mouseUp(l, quit[0] + 4, quit[1] + 4));
        float[] ex = l.key(EscMenuModel.Key.EXPORT);
        m.mouseDown(l, ex[0] + 3, ex[1] + 3);
        assertSame(EscMenuModel.Key.EXPORT, m.mouseUp(l, ex[0] + 4, ex[1] + 4));
        m.mouseDown(l, 0, 0);
        assertNull(m.pressed());
        assertNull(m.mouseUp(l, 0, 0));
    }
}
