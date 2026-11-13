package com.bedwarsqol.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * A minimal synchronous event bus for one event type, for platforms without Forge's. It keeps the
 * Forge semantics the features depend on:
 * <ul>
 *   <li>listeners run by {@link EventPriority}, highest first, and in registration order within a
 *       priority;</li>
 *   <li>once a {@link CancelableEvent} is canceled, only listeners registered with
 *       {@code receiveCanceled} still see it (a later one may un-cancel it);</li>
 *   <li>every listener gets the same event object, so an in-place edit is what later listeners and
 *       the poster see;</li>
 *   <li>a listener's exception propagates to the poster and stops the dispatch.</li>
 * </ul>
 * Registration copies the listener array, so a listener registered during a post runs from the next
 * post on.
 */
public final class EventDispatcher<E> {

    private static final class Entry<E> {
        final EventPriority priority;
        final boolean receiveCanceled;
        final Consumer<? super E> listener;
        final int seq;

        Entry(EventPriority priority, boolean receiveCanceled, Consumer<? super E> listener, int seq) {
            this.priority = priority;
            this.receiveCanceled = receiveCanceled;
            this.listener = listener;
            this.seq = seq;
        }
    }

    private final Object lock = new Object();
    private volatile List<Entry<E>> listeners = Collections.emptyList();

    /** Registers at {@link EventPriority#NORMAL}, not receiving canceled events. */
    public void register(Consumer<? super E> listener) {
        register(EventPriority.NORMAL, false, listener);
    }

    public void register(EventPriority priority, boolean receiveCanceled, Consumer<? super E> listener) {
        if (priority == null || listener == null) throw new NullPointerException();
        synchronized (lock) {
            List<Entry<E>> next = new ArrayList<>(listeners);
            next.add(new Entry<E>(priority, receiveCanceled, listener, next.size()));
            Collections.sort(next, new Comparator<Entry<E>>() {
                @Override
                public int compare(Entry<E> a, Entry<E> b) {
                    int byPriority = a.priority.compareTo(b.priority);
                    return byPriority != 0 ? byPriority : Integer.compare(a.seq, b.seq);
                }
            });
            listeners = Collections.unmodifiableList(next);
        }
    }

    /**
     * Delivers {@code event} to every listener in order.
     *
     * @return whether the event ends up canceled (always false for events that are not a
     *         {@link CancelableEvent})
     */
    public boolean post(E event) {
        CancelableEvent cancelable = event instanceof CancelableEvent ? (CancelableEvent) event : null;
        for (Entry<E> entry : listeners) {
            if (cancelable != null && cancelable.isCanceled() && !entry.receiveCanceled) continue;
            entry.listener.accept(event);
        }
        return cancelable != null && cancelable.isCanceled();
    }
}
