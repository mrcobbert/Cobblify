package com.bedwarsqol.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** A Reset Positions done from the settings menu is undoable in the next Edit HUD, if nothing changed in between (D7). */
public class PendingUndoTest {

    @Test
    public void aMatchingStateGivesTheBeforeStateOnce() {
        PendingUndo<String> p = new PendingUndo<String>();
        p.offer("before", "after");
        assertEquals("before", p.take("after"));
        assertNull("one use only", p.take("after"));
    }

    @Test
    public void aChangedStateGivesNothingAndClears() {
        PendingUndo<String> p = new PendingUndo<String>();
        p.offer("before", "after");
        assertNull(p.take("changed"));
        assertNull(p.take("after"));
    }

    @Test
    public void nothingOfferedGivesNothing() {
        assertNull(new PendingUndo<String>().take("anything"));
        assertNull(new PendingUndo<String>().take(null));
    }
}
