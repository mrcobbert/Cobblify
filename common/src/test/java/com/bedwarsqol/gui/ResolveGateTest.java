package com.bedwarsqol.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the ticketed one-attempt-per-key contract the remote skin resolve rides on (F3): an attempt
 * is live until it succeeds, fails, or times out; a key that failed is not retried inside the
 * failure window; and a result carried by a superseded ticket never settles the gate nor re-opens
 * it, so a late reply from a timed-out attempt cannot cancel the attempt that replaced it.
 */
public class ResolveGateTest {

    private static final long INFLIGHT = 60_000L;
    private static final long FAILURE = 600_000L;

    private final ResolveGate gate = new ResolveGate(INFLIGHT, FAILURE);

    @Test
    public void onlyOneAttemptPerKeyIsLive() {
        long t1 = gate.tryStart("a", 0L);
        assertTrue(t1 >= 0L);
        assertEquals(-1L, gate.tryStart("a", 1L));
        assertEquals(-1L, gate.tryStart("a", 59_999L));
        assertTrue(gate.isLive("a", 59_999L));
    }

    @Test
    public void aFailedKeyWaitsOutTheFailureWindow() {
        long t1 = gate.tryStart("a", 0L);
        gate.fail("a", t1, 100L);
        assertFalse(gate.isLive("a", 100L));
        assertEquals(-1L, gate.tryStart("a", 101L));
        assertEquals(-1L, gate.tryStart("a", 100L + FAILURE - 1L));
        assertTrue(gate.tryStart("a", 100L + FAILURE) >= 0L);
    }

    @Test
    public void successReopensTheGate() {
        long t1 = gate.tryStart("a", 0L);
        gate.succeed("a", t1);
        assertFalse(gate.isLive("a", 1L));
        long t2 = gate.tryStart("a", 1L); // callers check their own cache before asking again
        assertTrue(t2 >= 0L);
        assertNotEquals(t1, t2);
    }

    @Test
    public void anAttemptTimesOutAndItsLateResultIsIgnored() {
        long t1 = gate.tryStart("a", 0L);
        assertFalse(gate.isLive("a", INFLIGHT));
        long t2 = gate.tryStart("a", INFLIGHT);
        assertTrue(t2 >= 0L);
        assertNotEquals(t1, t2);

        gate.succeed("a", t1);              // the timed-out attempt's late success
        gate.fail("a", t1, INFLIGHT + 1L);  // and its late failure
        assertTrue(gate.isLive("a", INFLIGHT + 1L));
        assertEquals(-1L, gate.tryStart("a", INFLIGHT + 1L));

        gate.succeed("a", t2);
        assertFalse(gate.isLive("a", INFLIGHT + 1L));
        assertTrue(gate.tryStart("a", INFLIGHT + 1L) >= 0L);
    }

    @Test
    public void keysAreIndependentAndCaseIsPreserved() {
        assertTrue(gate.tryStart("a", 0L) >= 0L);
        long b = gate.tryStart("b", 0L);
        assertTrue(b >= 0L);
        assertTrue(gate.tryStart("A", 0L) >= 0L); // the gate never folds case; callers lower-case

        gate.fail("b", b, 0L);
        assertEquals(-1L, gate.tryStart("b", 1L));
        assertTrue(gate.tryStart("c", 1L) >= 0L);
    }
}
