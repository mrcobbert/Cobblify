package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Keystrokes cap colours: Lunar's see-through idle caps (or none), today's solid accent when pressed, text by contrast. */
public class KeyCapStyleTest {

    private static final int PALE_BLUE = 0xFFC0DCFF;
    private static final int RED = 0xFFE5484D;

    @Test
    public void idleCapIsSeeThroughOrClear() {
        assertEquals(0x6F000000, KeyCapStyle.fill(0f, true, PALE_BLUE));
        assertEquals("no Background: no cap", 0, KeyCapStyle.fill(0f, false, PALE_BLUE) >>> 24);
        assertEquals(0xFFFFFFFF, KeyCapStyle.text(0f, PALE_BLUE));
    }

    @Test
    public void pressedCapIsSolidAccent() {
        assertEquals(0xFFC0DCFF, KeyCapStyle.fill(1f, true, PALE_BLUE));
        assertEquals(0xFFC0DCFF, KeyCapStyle.fill(1f, false, PALE_BLUE));
        assertEquals("the accent's own alpha does not matter", 0xFFE5484D, KeyCapStyle.fill(1f, true, RED & 0xFFFFFF));
    }

    @Test
    public void pressedTextFollowsContrast() {
        assertEquals(0xFF121212, KeyCapStyle.onAccent(PALE_BLUE));
        assertEquals(0xFF121212, KeyCapStyle.text(1f, PALE_BLUE));
        assertEquals(0xFFFFFFFF, KeyCapStyle.onAccent(0xFF2B4A80)); // a deep blue
        assertEquals(0xFFFFFFFF, KeyCapStyle.text(1f, 0xFF2B4A80));
    }

    @Test
    public void halfwayIsALerp() {
        // 0x6F000000 -> 0xFFC0DCFF at 0.5, each channel rounded as GuiRender.lerpColor does
        assertEquals(0xB7606E80, KeyCapStyle.fill(0.5f, true, PALE_BLUE));
        assertEquals(0x80606E80, KeyCapStyle.fill(0.5f, false, PALE_BLUE));
        assertEquals(0xFF898989, KeyCapStyle.text(0.5f, PALE_BLUE));
        assertEquals(KeyCapStyle.fill(0f, true, PALE_BLUE), KeyCapStyle.fill(-1f, true, PALE_BLUE));
        assertEquals(KeyCapStyle.fill(1f, true, PALE_BLUE), KeyCapStyle.fill(2f, true, PALE_BLUE));
    }

    @Test
    public void shadowOnlyWithoutBackgroundAndUnpressed() {
        assertTrue(KeyCapStyle.shadow(0f, false));
        assertTrue(KeyCapStyle.shadow(0.49f, false));
        assertFalse(KeyCapStyle.shadow(0.5f, false));
        assertFalse(KeyCapStyle.shadow(1f, false));
        assertFalse("Lunar caps: no shadow", KeyCapStyle.shadow(0f, true));
    }
}
