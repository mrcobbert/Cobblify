package com.bedwarsqol.rewind.export.audio;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SoundLog} (Rewind Export PLAN Solution 3, Phase 4; TASK S2): time order, delayed sounds, and nothing from
 * outside the range.
 */
public class SoundLogTest {

    static SoundEvent at(long us, String sound) {
        return new SoundEvent(us, sound, 0, 0, 0, 1f, 1f, 16f, 0, 0, 0, 0f);
    }

    @Test
    public void eventsComeOutInTimeOrder() {
        SoundLog log = new SoundLog(1_000_000L, 2_000_000L);
        log.add(at(1_500_000L, "c"));
        log.add(at(1_100_000L, "a"));
        log.add(at(1_300_000L, "b"));
        log.add(at(1_300_000L, "b2")); // same time: the order it was logged in
        List<SoundEvent> e = log.events();
        assertEquals(4, e.size());
        assertEquals("a", e.get(0).sound());
        assertEquals("b", e.get(1).sound());
        assertEquals("b2", e.get(2).sound());
        assertEquals("c", e.get(3).sound());
    }

    @Test
    public void aDelayedSoundsTimeIncludesItsDelay() {
        SoundLog log = new SoundLog(0L, 10_000_000L);
        assertTrue(log.add(at(1_000_000L, "far"), 3));
        log.add(at(1_120_000L, "near"));
        List<SoundEvent> e = log.events();
        assertEquals("near", e.get(0).sound());
        assertEquals("far", e.get(1).sound());
        assertEquals(1_150_000L, e.get(1).timeUs());
    }

    @Test
    public void eventsBeforeTheStartAndAtOrAfterTheEndAreDropped() {
        SoundLog log = new SoundLog(1_000_000L, 2_000_000L);
        assertFalse(log.add(at(999_999L, "before")));
        assertTrue(log.add(at(1_000_000L, "first")));
        assertTrue(log.add(at(1_999_999L, "last")));
        assertFalse(log.add(at(2_000_000L, "atEnd")));
        assertFalse(log.add(at(1_900_000L, "delayedPastEnd"), 2)); // 1,900,000 + 100,000 = the end
        assertTrue(log.add(at(950_000L, "delayedIntoRange"), 1)); // 1,000,000
        assertEquals(3, log.events().size());
        assertEquals(3, log.dropped());
    }

    @Test
    public void takeBeforeHandsOutEventsOnceInOrder() {
        SoundLog log = new SoundLog(0L, 10_000_000L);
        log.add(at(300L, "c"));
        log.add(at(100L, "a"));
        log.add(at(200L, "b"));
        List<SoundEvent> first = log.takeBefore(201L);
        assertEquals(2, first.size());
        assertEquals("a", first.get(0).sound());
        assertEquals("b", first.get(1).sound());
        log.add(at(250L, "late"));
        List<SoundEvent> rest = log.takeBefore(1_000L);
        assertEquals(2, rest.size());
        assertEquals("late", rest.get(0).sound());
        assertEquals("c", rest.get(1).sound());
        assertTrue(log.takeBefore(1_000L).isEmpty());
    }
}
