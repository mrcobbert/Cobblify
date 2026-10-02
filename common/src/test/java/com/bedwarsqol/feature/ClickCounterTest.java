package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ClickCounterTest {

    @Test
    public void countsOnlyTheLastSecond() {
        ClickCounter c = new ClickCounter();
        c.click(1000);
        c.click(1500);
        c.click(1900);
        assertEquals(3, c.cps(1900));
        assertEquals(3, c.cps(1999));
        assertEquals(2, c.cps(2000)); // the click at 1000 is exactly one second old
        assertEquals(1, c.cps(2500));
        assertEquals(0, c.cps(2900));
    }

    @Test
    public void startsAtZero() {
        assertEquals(0, new ClickCounter().cps(123456));
    }

    @Test
    public void aBurstPastCapacityKeepsTheNewestClicks() {
        ClickCounter c = new ClickCounter();
        for (int i = 0; i < 100; i++) c.click(5000 + i);
        assertEquals(64, c.cps(5099)); // capped by the ring, all recent
        assertEquals(0, c.cps(7000));
    }

    @Test
    public void ignoresClicksFromTheFuture() {
        ClickCounter c = new ClickCounter();
        c.click(2000);
        assertEquals(0, c.cps(1999));
    }
}
