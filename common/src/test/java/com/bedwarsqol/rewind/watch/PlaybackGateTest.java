package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The gate's muted flag and epoch (PLAN section 5, layer 3; Phase 5a): entry mutes and advances the epoch, exit
 * unmutes and advances it again, so a ticket taken before either is stale; changes made on one thread are seen by
 * another without any other synchronization.
 */
public class PlaybackGateTest {

    @Test
    public void startsUnmutedAtEpochZeroWithACurrentTicket() {
        PlaybackGate gate = new PlaybackGate();
        assertFalse(gate.isMuted());
        assertEquals(0L, gate.epoch());
        PlaybackGate.Ticket t = gate.currentTicket();
        assertNotNull(t);
        assertEquals(0L, t.epoch());
        assertTrue(gate.isCurrent(t));
    }

    @Test
    public void entryMutesAndAdvancesTheEpochAndExitUnmutesAndAdvancesItAgain() {
        PlaybackGate gate = new PlaybackGate();
        gate.enter();
        assertTrue(gate.isMuted());
        assertEquals(1L, gate.epoch());
        gate.exit();
        assertFalse(gate.isMuted());
        assertEquals(2L, gate.epoch());
    }

    @Test
    public void aTicketFromBeforeAnEntryIsStaleDuringPlaybackAndAfterIt() {
        PlaybackGate gate = new PlaybackGate();
        PlaybackGate.Ticket before = gate.currentTicket();
        gate.enter();
        assertFalse("muted", gate.isCurrent(before));
        gate.exit();
        assertFalse("epoch moved on twice", gate.isCurrent(before));
    }

    @Test
    public void aTicketTakenDuringPlaybackIsNeverCurrentAndIsStaleAfterExit() {
        PlaybackGate gate = new PlaybackGate();
        gate.enter();
        PlaybackGate.Ticket during = gate.currentTicket();
        assertFalse("nothing is current while muted", gate.isCurrent(during));
        gate.exit();
        assertFalse(gate.isCurrent(during));
    }

    @Test
    public void aTicketTakenAfterExitIsCurrentUntilTheNextEntry() {
        PlaybackGate gate = new PlaybackGate();
        gate.enter();
        gate.exit();
        PlaybackGate.Ticket after = gate.currentTicket();
        assertTrue(gate.isCurrent(after));
        gate.enter();
        assertFalse(gate.isCurrent(after));
    }

    @Test
    public void aNullTicketIsNeverCurrent() {
        assertFalse(new PlaybackGate().isCurrent(null));
    }

    @Test
    public void repeatedEntryOrExitStillAdvancesTheEpoch() {
        PlaybackGate gate = new PlaybackGate();
        PlaybackGate.Ticket t0 = gate.currentTicket();
        gate.exit(); // exit without entry: harmless, but a ticket from before it is still stale
        assertFalse(gate.isMuted());
        assertFalse(gate.isCurrent(t0));
        gate.enter();
        gate.enter();
        assertTrue(gate.isMuted());
        assertEquals(3L, gate.epoch());
    }

    @Test
    public void theGlobalGateIsOneInstance() {
        assertTrue(PlaybackGate.global() == PlaybackGate.global());
        assertEquals(PlaybackGate.global().isMuted(), PlaybackGate.muted());
    }

    @Test(timeout = 10_000)
    public void entryMadeOnOneThreadIsSeenByAnotherThatIsAlreadyRunning() throws Exception {
        final PlaybackGate gate = new PlaybackGate();
        final PlaybackGate.Ticket before = gate.currentTicket();
        final CountDownLatch running = new CountDownLatch(1);
        final AtomicLong seenEpoch = new AtomicLong(-1);
        final AtomicReference<Boolean> staleSeen = new AtomicReference<Boolean>();
        Thread watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                running.countDown();
                // Spin on the plain reads only: the visibility comes from the gate itself.
                while (!gate.isMuted()) {
                    Thread.yield();
                }
                seenEpoch.set(gate.epoch());
                staleSeen.set(!gate.isCurrent(before));
            }
        }, "gate-watcher");
        watcher.setDaemon(true);
        watcher.start();
        assertTrue(running.await(5, TimeUnit.SECONDS));
        gate.enter();
        watcher.join(5_000);
        assertFalse("the watcher saw the entry", watcher.isAlive());
        assertEquals(1L, seenEpoch.get());
        assertEquals(Boolean.TRUE, staleSeen.get());
    }

    @Test(timeout = 10_000)
    public void exitMadeOnOneThreadIsSeenByAnotherThatIsAlreadyRunning() throws Exception {
        final PlaybackGate gate = new PlaybackGate();
        gate.enter();
        final PlaybackGate.Ticket during = gate.currentTicket();
        final CountDownLatch running = new CountDownLatch(1);
        final AtomicReference<PlaybackGate.Ticket> fresh = new AtomicReference<PlaybackGate.Ticket>();
        final AtomicReference<Boolean> duringStale = new AtomicReference<Boolean>();
        Thread watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                running.countDown();
                while (gate.isMuted()) {
                    Thread.yield();
                }
                duringStale.set(!gate.isCurrent(during));
                fresh.set(gate.currentTicket());
            }
        }, "gate-watcher");
        watcher.setDaemon(true);
        watcher.start();
        assertTrue(running.await(5, TimeUnit.SECONDS));
        gate.exit();
        watcher.join(5_000);
        assertFalse(watcher.isAlive());
        assertEquals(Boolean.TRUE, duringStale.get());
        assertNotNull(fresh.get());
        assertEquals(2L, fresh.get().epoch());
        assertTrue("a ticket taken on the other thread after exit is current here", gate.isCurrent(fresh.get()));
    }
}
