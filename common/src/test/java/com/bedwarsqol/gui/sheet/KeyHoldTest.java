package com.bedwarsqol.gui.sheet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class KeyHoldTest {

    private static final int RSHIFT = 54, ESCAPE = 1, A = 30;

    private final KeyHold hold = new KeyHold();
    private final Set<Integer> down = new HashSet<Integer>();

    /** A frame, as the screen runs it: the first one also opens. */
    private void frame(boolean first) {
        if (first) hold.open(down::contains, RSHIFT, ESCAPE);
        hold.frame(down::contains);
    }

    @Test
    public void aKeyHeldDownRepeatsUntilAFrameSeesItUp() {
        frame(true);
        down.add(RSHIFT);
        assertTrue(hold.press(RSHIFT));
        assertFalse("auto-repeat", hold.press(RSHIFT));
        frame(false);
        assertFalse("still down", hold.press(RSHIFT));
        down.remove(RSHIFT);
        frame(false);
        assertTrue("released, then pressed again", hold.press(RSHIFT));
    }

    @Test
    public void theOpeningPressIsReleasedByTheFirstFrameOnceTheKeyIsUp() {
        // Lunar: the press that opened the sheet reaches it before the first frame.
        down.add(RSHIFT);
        hold.press(RSHIFT);
        down.remove(RSHIFT);
        frame(true);
        assertTrue(hold.press(RSHIFT));
    }

    @Test
    public void theOpeningPressStaysHeldWhileTheKeyIsDown() {
        down.add(RSHIFT);
        hold.press(RSHIFT);
        frame(true);
        assertFalse("auto-repeat of the opening press", hold.press(RSHIFT));
        down.remove(RSHIFT);
        frame(false);
        assertTrue(hold.press(RSHIFT));
    }

    @Test
    public void aTapShorterThanAFrameIsStillFresh() {
        frame(true);
        assertTrue(hold.press(RSHIFT)); // down and up both landed before this frame
        frame(false);
        assertTrue(hold.press(RSHIFT));
    }

    @Test
    public void anotherKeyIsAFreshPress() {
        frame(true);
        down.add(A);
        assertTrue(hold.press(A));
        down.add(RSHIFT);
        assertTrue(hold.press(RSHIFT));
        assertFalse(hold.press(A));
    }

    @Test
    public void aKeyAlreadyDownWhenTheScreenOpensIsHeld() {
        // Esc held in Edit HUD: Edit HUD took the press, and the sheet only ever sees its repeats.
        down.add(ESCAPE);
        frame(true);
        assertFalse(hold.press(ESCAPE));
        frame(false);
        assertFalse(hold.press(ESCAPE));
        down.remove(ESCAPE);
        frame(false);
        assertTrue(hold.press(ESCAPE));
    }

    @Test
    public void onlyTheNamedOrPressedKeysAreEverPolled() {
        final Set<Integer> polled = new HashSet<Integer>();
        KeyHold.Keys spy = code -> {
            polled.add(code);
            return down.contains(code);
        };
        down.add(A); // down, but neither named nor pressed
        hold.open(spy, RSHIFT, ESCAPE, -98, 0, 300);
        hold.frame(spy);
        hold.press(RSHIFT);
        hold.frame(spy);
        assertEquals(new HashSet<Integer>(Arrays.asList(RSHIFT, ESCAPE)), polled);
        assertTrue("A was never seen, so its first press is fresh", hold.press(A));
    }

    @Test
    public void codesWithNoKeyAreAlwaysFresh() {
        frame(true);
        assertTrue(hold.press(0));
        assertTrue(hold.press(0));
        assertTrue(hold.press(-1));
        assertTrue(hold.press(256));
        assertTrue(hold.press(256));
    }
}
