package com.bedwarsqol.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EditHistoryTest {

    @Test
    public void emptyHistoryHasNothingToUndoOrRedo() {
        EditHistory<String> h = new EditHistory<String>(10);
        assertFalse(h.canUndo());
        assertNull(h.undo("now"));
        assertNull(h.redo("now"));
    }

    @Test
    public void undoThenRedoWalksBackAndForth() {
        EditHistory<String> h = new EditHistory<String>(10);
        h.record("a"); // a -> b
        h.record("b"); // b -> c
        assertEquals("b", h.undo("c"));
        assertEquals("a", h.undo("b"));
        assertNull(h.undo("a"));
        assertEquals("b", h.redo("a"));
        assertEquals("c", h.redo("b"));
        assertFalse(h.canRedo());
    }

    @Test
    public void aNewChangeClearsRedo() {
        EditHistory<String> h = new EditHistory<String>(10);
        h.record("a");
        h.undo("b");
        assertTrue(h.canRedo());
        h.record("a");
        assertFalse(h.canRedo());
    }

    @Test
    public void theOldestEntryDropsPastTheLimit() {
        EditHistory<String> h = new EditHistory<String>(2);
        h.record("a");
        h.record("b");
        h.record("c");
        assertEquals("c", h.undo("d"));
        assertEquals("b", h.undo("c"));
        assertNull(h.undo("b"));
    }
}
