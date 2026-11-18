package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.feature.FeatureTasks;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The client-thread half of the lifetime rule (PLAN section 5 layer 4; review round 2 B3): a callback carries the
 * ticket captured where its work was submitted, and runs only if, when the client thread drains it, the gate is
 * unmuted and still in that ticket's epoch. A manual queue stands in for Minecraft's scheduled tasks.
 */
public class FeatureTasksTest {

    private final PlaybackGate gate = new PlaybackGate(0);
    /** Minecraft's scheduled-task queue, drained by {@link #drain()} as the client thread would. */
    private final ArrayDeque<Runnable> clientQueue = new ArrayDeque<Runnable>();
    private final AtomicInteger effects = new AtomicInteger();

    private final Runnable effect = new Runnable() {
        @Override
        public void run() {
            effects.incrementAndGet();
        }
    };

    private void schedule(PlaybackGate.Ticket submittedWith) {
        clientQueue.add(FeatureTasks.guarded(gate, submittedWith, effect));
    }

    private void drain() {
        Runnable r;
        while ((r = clientQueue.poll()) != null) {
            r.run();
        }
    }

    @Test
    public void workSubmittedAndFinishedOutsidePlaybackRuns() {
        PlaybackGate.Ticket t = gate.currentTicket();
        schedule(t);
        drain();
        assertEquals(1, effects.get());
    }

    /** The reviewer's case: submitted at epoch 0, finished and drained during playback (epoch 1). */
    @Test
    public void workSubmittedBeforePlaybackAndDrainedDuringItIsDropped() {
        PlaybackGate.Ticket atSubmission = gate.currentTicket();
        gate.enter();
        schedule(atSubmission);
        drain();
        assertEquals(0, effects.get());
    }

    /** Scheduled before playback but drained during it (a queue not yet drained when entry completed). */
    @Test
    public void aCallbackQueuedBeforeEntryAndDrainedAfterItIsDropped() {
        schedule(gate.currentTicket());
        gate.enter();
        drain();
        assertEquals(0, effects.get());
    }

    /** The reviewer's 0 -> 1 -> 2 case: the work finishes after exit, so the epoch is 2, not the ticket's 0. */
    @Test
    public void workSubmittedBeforePlaybackThatFinishesAfterExitIsDropped() {
        PlaybackGate.Ticket atSubmission = gate.currentTicket();
        gate.enter();
        gate.exit();
        schedule(atSubmission);
        drain();
        assertEquals(0, effects.get());
    }

    /** Capturing the epoch at scheduling time (Rev 2's mistake) would let this run: the ticket must win. */
    @Test
    public void aTicketTakenWhenTheCallbackIsScheduledIsNotTheSubmissionTicket() {
        PlaybackGate.Ticket atSubmission = gate.currentTicket();
        gate.enter();
        gate.exit();
        PlaybackGate.Ticket atScheduling = gate.currentTicket();
        assertTrue("this is what Rev 2 checked, and it passes", gate.isCurrent(atScheduling));
        schedule(atSubmission);
        drain();
        assertEquals("the submission ticket is stale", 0, effects.get());
    }

    @Test
    public void workSubmittedAfterExitRuns() {
        gate.enter();
        gate.exit();
        schedule(gate.currentTicket());
        drain();
        assertEquals(1, effects.get());
    }

    @Test
    public void workSubmittedDuringPlaybackNeverRuns() {
        gate.enter();
        PlaybackGate.Ticket during = gate.currentTicket();
        schedule(during);
        drain();
        gate.exit();
        schedule(during);
        drain();
        assertEquals(0, effects.get());
    }

    @Test
    public void aNullTicketNeverRuns() {
        schedule(null);
        drain();
        assertEquals(0, effects.get());
    }

    @Test
    public void workSubmittedWhileEntryDrainsIsDropped() {
        PlaybackGate.Ticket t = gate.currentTicket();
        gate.beginEntry();
        schedule(t);
        drain();
        assertEquals(0, effects.get());
    }

    /** Real threads: a lookup started at epoch 0 on a worker finishes during playback and after exit. */
    @Test(timeout = 10_000)
    public void aWorkerThatFinishesAtAnyLaterEpochHasNoEffect() throws Exception {
        for (final boolean finishAfterExit : new boolean[]{false, true}) {
            final PlaybackGate.Ticket atSubmission = gate.currentTicket();
            final CountDownLatch finish = new CountDownLatch(1);
            final CountDownLatch scheduled = new CountDownLatch(1);
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        finish.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    synchronized (clientQueue) {
                        schedule(atSubmission);
                    }
                    scheduled.countDown();
                }
            });
            worker.start();
            gate.enter();
            if (finishAfterExit) {
                gate.exit();
            }
            finish.countDown();
            assertTrue(scheduled.await(5, TimeUnit.SECONDS));
            synchronized (clientQueue) {
                drain();
            }
            if (!finishAfterExit) {
                gate.exit();
            }
            worker.join(5_000);
        }
        assertEquals(0, effects.get());
    }
}
