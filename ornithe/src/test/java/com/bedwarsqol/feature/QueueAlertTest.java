package com.bedwarsqol.feature;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pins the queue alert's per-queue dedupe/cap bound and its never-accuse UUID cross-check. */
public class QueueAlertTest {

    private static final UUID U = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    public void firstSightOfANameIsAccepted() {
        Set<String> seen = new HashSet<String>();
        assertTrue(QueueAlert.accept(seen, "Dream", 16));
        assertEquals(1, seen.size());
    }

    @Test
    public void repeatsOfTheSameNameAreDroppedCaseInsensitively() {
        Set<String> seen = new HashSet<String>();
        assertTrue(QueueAlert.accept(seen, "Dream", 16));
        assertFalse(QueueAlert.accept(seen, "Dream", 16));
        assertFalse(QueueAlert.accept(seen, "DREAM", 16));
        assertFalse(QueueAlert.accept(seen, "dream", 16));
        assertEquals(1, seen.size());
    }

    @Test
    public void distinctNamesAcceptUpToTheCapThenStop() {
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < 16; i++) {
            assertTrue("name " + i + " should fit under the cap", QueueAlert.accept(seen, "p" + i, 16));
        }
        assertEquals(16, seen.size());
        assertFalse(QueueAlert.accept(seen, "p16", 16));
        assertFalse(QueueAlert.accept(seen, "p17", 16));
        assertEquals(16, seen.size());
    }

    @Test
    public void aClearedSetStartsTheQueueBudgetOver() {
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < 16; i++) QueueAlert.accept(seen, "p" + i, 16);
        assertFalse(QueueAlert.accept(seen, "late", 16));
        seen.clear(); // what a session-id change does
        assertTrue(QueueAlert.accept(seen, "late", 16));
    }

    @Test
    public void blankOrMissingNamesAreNeverLookedUp() {
        Set<String> seen = new HashSet<String>();
        assertFalse(QueueAlert.accept(seen, null, 16));
        assertFalse(QueueAlert.accept(seen, "", 16));
        assertFalse(QueueAlert.accept(null, "Dream", 16));
        assertTrue(seen.isEmpty());
    }

    @Test
    public void matchingUuidAllowsTheAlertInEitherDashForm() {
        assertTrue(QueueAlert.uuidMatches(U, "069a79f4-44e9-4726-a5be-fca90e38aaf5"));
        assertTrue(QueueAlert.uuidMatches(U, "069a79f444e94726a5befca90e38aaf5"));
        assertTrue(QueueAlert.uuidMatches(U, "069A79F444E94726A5BEFCA90E38AAF5"));
        assertTrue(QueueAlert.uuidMatches(U, "  069a79f444e94726a5befca90e38aaf5  "));
    }

    @Test
    public void mismatchedOrAbsentUuidSuppressesTheAlert() {
        assertFalse(QueueAlert.uuidMatches(U, "853c80ef-3c37-49fd-aa49-938b674adae6"));
        assertFalse(QueueAlert.uuidMatches(U, null));
        assertFalse(QueueAlert.uuidMatches(U, ""));
        assertFalse(QueueAlert.uuidMatches(U, "   "));
        assertFalse(QueueAlert.uuidMatches(U, "-"));
        assertFalse(QueueAlert.uuidMatches(null, "069a79f444e94726a5befca90e38aaf5"));
        assertFalse(QueueAlert.uuidMatches(null, null));
    }
}
