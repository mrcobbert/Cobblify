package com.bedwarsqol.gui.sheet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** {@code down} is what LWJGL's last poll saw, and each tick runs after the screen has taken every queued event. */
public class KeyHoldTest {

    private static final int RSHIFT = 54, ESCAPE = 1, A = 30;

    private final KeyHold hold = new KeyHold();
    private final Set<Integer> down = new HashSet<Integer>();

    private void open() {
        hold.open(down::contains, RSHIFT, ESCAPE);
    }

    private void tick() {
        hold.tick(down::contains);
    }

    @Test
    public void aKeyHeldDownRepeatsUntilATickSeesItUp() {
        open();
        down.add(RSHIFT);
        assertTrue(hold.press(RSHIFT));
        assertFalse("auto-repeat", hold.press(RSHIFT));
        tick();
        assertFalse("still down", hold.press(RSHIFT));
        down.remove(RSHIFT);
        tick();
        assertTrue("released, then pressed again", hold.press(RSHIFT));
    }

    @Test
    public void theOpeningPressIsHeldUntilTheKeyIsUp() {
        // Lunar: the sheet opens inside the key loop, and the same press reaches it next.
        down.add(RSHIFT);
        open();
        assertFalse(hold.press(RSHIFT));
        tick();
        assertFalse("auto-repeat of the opening press", hold.press(RSHIFT));
        down.remove(RSHIFT);
        tick();
        assertTrue(hold.press(RSHIFT));
    }

    @Test
    public void aTapAlreadyUpWhenTheScreenOpensIsReleasedByTheNextTick() {
        // Down and up both landed in one poll: the key reads up when the press opens the sheet.
        open();
        hold.press(RSHIFT);
        tick();
        assertTrue(hold.press(RSHIFT));
    }

    @Test
    public void anotherKeyIsAFreshPress() {
        open();
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
        open();
        assertFalse(hold.press(ESCAPE));
        tick();
        assertFalse(hold.press(ESCAPE));
        down.remove(ESCAPE);
        tick();
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
        hold.tick(spy);
        hold.press(RSHIFT);
        hold.tick(spy);
        assertEquals(new HashSet<Integer>(Arrays.asList(RSHIFT, ESCAPE)), polled);
        assertTrue("A was never seen, so its first press is fresh", hold.press(A));
    }

    @Test
    public void codesWithNoKeyAreAlwaysFresh() {
        open();
        assertTrue(hold.press(0));
        assertTrue(hold.press(0));
        assertTrue(hold.press(-1));
        assertTrue(hold.press(256));
        assertTrue(hold.press(256));
    }
}
