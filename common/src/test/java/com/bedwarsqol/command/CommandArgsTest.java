package com.bedwarsqol.command;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Pins the empty-token drop (F11). Vanilla and Forge split a typed command line on a single space, so
 * {@code /cobblify mode  solo} reaches the handler as {@code ["mode", "", "solo"]} and the mode read
 * {@code args[1]} as the empty token ("Unknown mode ''"). Weave splits on {@code \s+} and never does,
 * so this runs before dispatch on both platforms and is a no-op on Lunar.
 */
public class CommandArgsTest {

    @Test
    public void doubledSpaceInsideTheLineIsDropped() {
        assertArrayEquals(new String[]{"mode", "solo"}, CommandArgs.dropEmpty("mode  solo".split(" ")));
    }

    @Test
    public void leadingSpacesAreDropped() {
        assertArrayEquals(new String[]{"Steve"}, CommandArgs.dropEmpty("  Steve".split(" ")));
    }

    @Test
    public void emptyStaysEmpty() {
        assertEquals(0, CommandArgs.dropEmpty(new String[0]).length);
    }

    @Test
    public void nullIsEmpty() {
        assertEquals(0, CommandArgs.dropEmpty(null).length);
    }

    @Test
    public void aCleanLineIsUnchanged() {
        assertArrayEquals(new String[]{"mode", "solo"},
                CommandArgs.dropEmpty(new String[]{"mode", "solo"}));
    }
}
