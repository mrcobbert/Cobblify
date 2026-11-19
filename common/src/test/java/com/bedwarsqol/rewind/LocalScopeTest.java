package com.bedwarsqol.rewind;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The local scope (PLAN section A "Local scope", A5, A6): a pure depth counter. A block change or sound counts as the
 * player's own only while one of the client's own-action methods is on the stack.
 */
public class LocalScopeTest {

    @Test
    public void localOnlyWhileAScopeIsOpen() {
        LocalScope s = new LocalScope();
        assertFalse(s.local());
        assertEquals(0, s.depth());
        s.enter();
        assertTrue(s.local());
        s.exit();
        assertFalse(s.local());
    }

    @Test
    public void nestingCounts() {
        LocalScope s = new LocalScope();
        s.enter();          // clickMouse
        s.enter();          // EntityPlayerSP.onUpdate inside it (a mod calling vanilla from odd places)
        assertEquals(2, s.depth());
        s.exit();
        assertTrue("still inside the outer scope", s.local());
        s.exit();
        assertFalse(s.local());
    }

    @Test
    public void anUnbalancedExitClampsAtZero() {
        LocalScope s = new LocalScope();
        s.exit();
        s.exit();
        assertEquals(0, s.depth());
        assertFalse(s.local());
        s.enter();
        assertTrue("one enter after extra exits opens a scope", s.local());
        s.exit();
        assertFalse(s.local());
        s.exitDestroy();
        assertFalse(s.destroying());
    }

    @Test
    public void theDestroyScopeGivesTheOwnBreakCause() {
        LocalScope s = new LocalScope();
        s.enter();
        assertEquals(RewindFormat.BLOCK_CAUSE_OTHER, s.blockCause());
        s.enterDestroy();
        assertTrue(s.destroying());
        assertEquals(RewindFormat.BLOCK_CAUSE_OWN_BREAK, s.blockCause());
        s.exitDestroy();
        assertEquals(RewindFormat.BLOCK_CAUSE_OTHER, s.blockCause());
        s.exit();
    }

    @Test
    public void resetClosesEveryScope() {
        LocalScope s = new LocalScope();
        s.enter();
        s.enter();
        s.enterDestroy();
        s.reset(); // a vanilla method threw past its RETURN hook: the next tick head starts clean
        assertFalse(s.local());
        assertFalse(s.destroying());
        assertEquals(0, s.depth());
    }
}
