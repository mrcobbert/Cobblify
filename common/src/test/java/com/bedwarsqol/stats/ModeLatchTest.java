package com.bedwarsqol.stats;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;

/**
 * The latch that turns per-tick sidebar observations into the mode every stats surface shows.
 *
 * <p>The load-bearing case is {@link #anUnsupportedLabelBlocksTheTeamColourFallback()}: mapping a
 * dream mode to {@code UNKNOWN} is not enough on its own, because the active game has no
 * {@code Mode:} line at all and the team-colour fallback cannot tell "never saw a label" from "saw
 * one we do not map" - so Rush Doubles was re-derived as DOUBLES from its eight two-player teams
 * and the dream game showed Doubles stats anyway.
 */
public class ModeLatchTest {

    private final AtomicInteger fallbackCalls = new AtomicInteger();

    private Supplier<BedwarsMode> fallback(final BedwarsMode mode) {
        return () -> {
            fallbackCalls.incrementAndGet();
            return mode;
        };
    }

    @Test
    public void anUnsupportedLabelBlocksTheTeamColourFallback() {
        for (String dream : new String[] {"Rush Doubles", "Ultimate 4v4v4v4", "Castle", "4v4"}) {
            ModeLatch latch = new ModeLatch();
            fallbackCalls.set(0);
            latch.observe(dream, false, fallback(BedwarsMode.DOUBLES));
            assertEquals(dream, BedwarsMode.UNKNOWN, latch.current());
            // The game starts: no Mode: line any more, eight teams of two on the scoreboard.
            latch.observe(null, true, fallback(BedwarsMode.DOUBLES));
            assertEquals("re-derived a mode for " + dream, BedwarsMode.UNKNOWN, latch.current());
            assertEquals("the fallback must not run for " + dream, 0, fallbackCalls.get());
        }
    }

    @Test
    public void joiningMidGameWithNoLabelStillUsesTheFallback() {
        ModeLatch latch = new ModeLatch();
        latch.observe(null, true, fallback(BedwarsMode.DOUBLES));
        assertEquals(BedwarsMode.DOUBLES, latch.current());
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    public void aSupportedLabelLatchesAndSurvivesTheGameStart() {
        ModeLatch latch = new ModeLatch();
        latch.observe("Doubles ✦", false, fallback(BedwarsMode.SOLO));
        assertEquals(BedwarsMode.DOUBLES, latch.current());
        latch.observe(null, true, fallback(BedwarsMode.SOLO));
        assertEquals("a latched mode is never second-guessed", BedwarsMode.DOUBLES, latch.current());
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    public void theFallbackOnlyRunsInAnActiveGame() {
        ModeLatch latch = new ModeLatch();
        latch.observe(null, false, fallback(BedwarsMode.FOURS));
        assertEquals(BedwarsMode.UNKNOWN, latch.current());
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    public void resetForgetsTheUnsupportedLabel() {
        ModeLatch latch = new ModeLatch();
        latch.observe("Rush Doubles", false, fallback(BedwarsMode.DOUBLES));
        latch.reset(); // each Hypixel world is a fresh game
        assertEquals(BedwarsMode.UNKNOWN, latch.current());
        latch.observe(null, true, fallback(BedwarsMode.DOUBLES));
        assertEquals(BedwarsMode.DOUBLES, latch.current());
    }

    @Test
    public void aLaterSupportedLabelClearsTheUnsupportedMemory() {
        ModeLatch latch = new ModeLatch();
        latch.observe("Rush Doubles", false, fallback(BedwarsMode.SOLO));
        latch.observe("Solo", false, fallback(BedwarsMode.SOLO));
        assertEquals(BedwarsMode.SOLO, latch.current());
        latch.observe(null, true, fallback(BedwarsMode.DOUBLES));
        assertEquals(BedwarsMode.SOLO, latch.current());
    }

    @Test
    public void aBlankLabelCountsAsNoLabel() {
        ModeLatch latch = new ModeLatch();
        latch.observe("   ", true, fallback(BedwarsMode.FOURS));
        assertEquals(BedwarsMode.FOURS, latch.current());
    }
}
