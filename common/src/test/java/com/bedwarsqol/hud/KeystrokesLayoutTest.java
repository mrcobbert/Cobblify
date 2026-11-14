package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Lunar's Keystrokes geometry, as the approved sandbox draws it: 18 px caps, 1 px gaps, mouse row over a 10 px space bar. */
public class KeystrokesLayoutTest {

    @Test
    public void capsAreLunarGeometry() {
        float[][] want = {{19, 0, 18, 18}, {0, 19, 18, 18}, {19, 19, 18, 18}, {38, 19, 18, 18},
                {0, 57, 56, 10}, {0, 38, 27.5f, 18}, {28.5f, 38, 27.5f, 18}};
        assertEquals(7, KeystrokesLayout.CAPS.length);
        for (int i = 0; i < want.length; i++) assertArrayEquals("cap " + i, want[i], KeystrokesLayout.CAPS[i], 0f);
        assertArrayEquals(new String[]{"W", "A", "S", "D", "", "LMB", "RMB"}, KeystrokesLayout.LABELS);
        assertEquals(18, KeystrokesLayout.CAP);
        assertEquals(1, KeystrokesLayout.GAP);
        assertEquals(10, KeystrokesLayout.SPACE_H);
    }

    @Test
    public void sizeIs56By67() {
        assertEquals(56, KeystrokesLayout.WIDTH);
        assertEquals(67, KeystrokesLayout.HEIGHT);
    }

    @Test
    public void labelOrigins() {
        float[] w = KeystrokesLayout.CAPS[0];
        assertEquals(19f + 6.5f, KeystrokesLayout.labelX(w, 6f, 1f), 0f);
        assertEquals(0f + 5f, KeystrokesLayout.labelY(w), 0f);
        float[] lmb = KeystrokesLayout.CAPS[5];
        assertEquals(5.25f, KeystrokesLayout.labelX(lmb, 18f, 1f), 0f);
        assertEquals(38f + 5f, KeystrokesLayout.labelY(lmb), 0f);
        float[] rmb = KeystrokesLayout.CAPS[6];
        assertEquals(28.5f + 5.25f, KeystrokesLayout.labelX(rmb, 18f, 1f), 0f);
    }

    @Test
    public void spaceLine() {
        assertArrayEquals(new float[]{18, 62, 20, 1}, KeystrokesLayout.SPACE_LINE, 0f);
        assertEquals(0xFF3F3F3F, KeystrokesLayout.SPACE_LINE_SHADOW);
    }
}
