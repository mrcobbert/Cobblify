package com.bedwarsqol.event;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The dispatcher stands in for Forge's event bus on platforms without one, so it must keep the bus
 * semantics the features rely on: HIGHEST listeners first, registration order inside a priority,
 * canceled events reaching only {@code receiveCanceled} listeners, and one shared event object so an
 * in-place edit by an early listener is what later listeners see.
 */
public class EventDispatcherTest {

    /** A chat-like event: cancelable, with a mutable payload. */
    private static final class Msg extends CancelableEvent {
        final StringBuilder text;

        Msg(String text) {
            this.text = new StringBuilder(text);
        }
    }

    @Test
    public void runsHighestFirstThenLowerPrioritiesInOrder() {
        EventDispatcher<Msg> bus = new EventDispatcher<>();
        List<String> order = new ArrayList<>();
        bus.register(EventPriority.LOWEST, false, e -> order.add("lowest"));
        bus.register(e -> order.add("normal"));
        bus.register(EventPriority.HIGHEST, true, e -> order.add("highest"));
        bus.register(EventPriority.LOW, false, e -> order.add("low"));
        bus.register(EventPriority.HIGH, false, e -> order.add("high"));

        bus.post(new Msg("x"));

        assertEquals(Arrays.asList("highest", "high", "normal", "low", "lowest"), order);
    }

    @Test
    public void keepsRegistrationOrderWithinAPriority() {
        EventDispatcher<Msg> bus = new EventDispatcher<>();
        List<String> order = new ArrayList<>();
        bus.register(e -> order.add("a"));
        bus.register(EventPriority.HIGHEST, true, e -> order.add("first-highest"));
        bus.register(e -> order.add("b"));
        bus.register(EventPriority.HIGHEST, true, e -> order.add("second-highest"));
        bus.register(e -> order.add("c"));

        bus.post(new Msg("x"));

        assertEquals(Arrays.asList("first-highest", "second-highest", "a", "b", "c"), order);
    }

    @Test
    public void canceledEventReachesOnlyReceiveCanceledListeners() {
        EventDispatcher<Msg> bus = new EventDispatcher<>();
        List<String> seen = new ArrayList<>();
        bus.register(EventPriority.HIGHEST, false, e -> {
            seen.add("canceler");
            e.setCanceled(true);
        });
        bus.register(EventPriority.HIGH, true, e -> seen.add("receivesCanceled"));
        bus.register(e -> seen.add("skipped"));
        bus.register(EventPriority.LOWEST, true, e -> seen.add("lowestReceivesCanceled"));

        boolean canceled = bus.post(new Msg("x"));

        assertTrue(canceled);
        assertEquals(Arrays.asList("canceler", "receivesCanceled", "lowestReceivesCanceled"), seen);
    }

    @Test
    public void alreadyCanceledEventSkipsOrdinaryListeners() {
        EventDispatcher<Msg> bus = new EventDispatcher<>();
        List<String> seen = new ArrayList<>();
        bus.register(e -> seen.add("ordinary"));
        bus.register(EventPriority.HIGHEST, true, e -> seen.add("watcher"));
        Msg msg = new Msg("x");
        msg.setCanceled(true);

        assertTrue(bus.post(msg));
        assertEquals(Arrays.asList("watcher"), seen);
    }

    @Test
    public void receiveCanceledListenerCanUncancel() {
        EventDispatcher<Msg> bus = new EventDispatcher<>();
        List<String> seen = new ArrayList<>();
        bus.register(EventPriority.HIGHEST, false, e -> e.setCanceled(true));
        bus.register(EventPriority.HIGH, true, e -> e.setCanceled(false));
        bus.register(e -> seen.add("runsAgain"));

        assertFalse(bus.post(new Msg("x")));
        assertEquals(Arrays.asList("runsAgain"), seen);
    }

    @Test
    public void inPlaceEditIsVisibleToLaterListenersAndThePoster() {
        EventDispatcher<Msg> bus = new EventDispatcher<>();
        List<String> seen = new ArrayList<>();
        bus.register(EventPriority.HIGHEST, true, e -> seen.add("before:" + e.text));
        bus.register(e -> e.text.append(" [tag]"));
        bus.register(EventPriority.LOW, false, e -> seen.add("after:" + e.text));
        Msg msg = new Msg("hello");

        bus.post(msg);

        assertEquals(Arrays.asList("before:hello", "after:hello [tag]"), seen);
        assertEquals("hello [tag]", msg.text.toString());
    }

    @Test
    public void nonCancelableEventsReachEveryListener() {
        EventDispatcher<int[]> bus = new EventDispatcher<>();
        bus.register(e -> e[0]++);
        bus.register(EventPriority.LOWEST, false, e -> e[0] += 10);
        int[] counter = {0};

        assertFalse(bus.post(counter));
        assertEquals(11, counter[0]);
    }

    @Test
    public void listenerExceptionPropagatesAndStopsDispatchLikeForge() {
        EventDispatcher<Msg> bus = new EventDispatcher<>();
        List<String> seen = new ArrayList<>();
        bus.register(EventPriority.HIGHEST, false, e -> {
            throw new IllegalStateException("boom");
        });
        bus.register(e -> seen.add("never"));
        try {
            bus.post(new Msg("x"));
            fail("expected the listener's exception");
        } catch (IllegalStateException expected) {
            assertEquals("boom", expected.getMessage());
        }
        assertTrue(seen.isEmpty());
    }

    @Test
    public void listenerRegisteredDuringDispatchRunsFromTheNextPost() {
        EventDispatcher<Msg> bus = new EventDispatcher<>();
        List<String> seen = new ArrayList<>();
        bus.register(e -> {
            seen.add("outer");
            if (seen.size() == 1) bus.register(x -> seen.add("late"));
        });

        bus.post(new Msg("x"));
        assertEquals(Arrays.asList("outer"), seen);
        bus.post(new Msg("y"));
        assertEquals(Arrays.asList("outer", "outer", "late"), seen);
    }
}
