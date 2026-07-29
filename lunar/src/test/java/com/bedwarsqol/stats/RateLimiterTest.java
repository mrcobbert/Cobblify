package com.bedwarsqol.stats;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the bounded-wait overload used by the batch fallback: it must never wait past its deadline
 * and must never claim a slot it didn't win, while the unbounded path keeps its pacing behaviour.
 */
public class RateLimiterTest {

    @Test
    public void boundedWaitGivesUpAtItsDeadline() throws Exception {
        RateLimiter limiter = new RateLimiter();
        limiter.awaitSlot(); // closes the spacing gate for the next 1300ms

        long start = System.currentTimeMillis();
        boolean claimed = limiter.awaitSlot(200L);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(claimed);
        assertTrue("bounded wait ran " + elapsed + "ms past a 200ms bound", elapsed < 1000L);
    }

    @Test
    public void uncontendedBoundedWaitClaimsASlot() throws Exception {
        assertTrue(new RateLimiter().awaitSlot(200L));
    }

    @Test
    public void unboundedWaitStillSpacesRequests() throws Exception {
        RateLimiter limiter = new RateLimiter();
        limiter.awaitSlot();

        long start = System.currentTimeMillis();
        assertTrue(limiter.awaitSlot());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue("second claim came after only " + elapsed + "ms", elapsed >= 1200L);
    }

    /**
     * A failed bounded wait must leave the pacing state untouched, otherwise the next request would
     * be spaced from a slot that was never claimed. The gate here opens 1300ms after the first claim;
     * had the expired wait moved it, it would only reopen 1300ms after that (~2500ms), so a claim at
     * 1600ms proves the failed return mutated nothing.
     */
    @Test
    public void expiredBoundedWaitClaimsNothingAndMovesNothing() throws Exception {
        RateLimiter limiter = new RateLimiter();
        limiter.awaitSlot();
        long firstClaim = System.currentTimeMillis();

        boolean claimed = limiter.awaitSlot(1200L); // expires just before the gate opens
        long waited = System.currentTimeMillis() - firstClaim;

        assertFalse("claimed a slot past its deadline", claimed);
        assertTrue("bounded wait gave up after only " + waited + "ms of a 1200ms budget",
                waited >= 1100L);

        long untilProbe = 1600L - (System.currentTimeMillis() - firstClaim);
        if (untilProbe > 0L) Thread.sleep(untilProbe);
        assertTrue("the failed wait moved the spacing gate", limiter.awaitSlot(100L));
    }

    /**
     * When the deadline and the spacing gate coincide, either answer is legal — but a slot may never
     * be handed out before the spacing has actually elapsed.
     */
    @Test
    public void deadlineCoincidingWithTheGateNeverClaimsEarly() throws Exception {
        RateLimiter limiter = new RateLimiter();
        limiter.awaitSlot();

        long start = System.currentTimeMillis();
        boolean claimed = limiter.awaitSlot(1300L); // deadline lands on the gate opening
        long elapsed = System.currentTimeMillis() - start;

        assertTrue("bounded wait returned " + claimed + " after only " + elapsed + "ms",
                elapsed >= 1200L);
    }
}
