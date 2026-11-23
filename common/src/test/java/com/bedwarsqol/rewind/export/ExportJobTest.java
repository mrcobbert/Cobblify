package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.export.audio.Mixer;
import com.bedwarsqol.rewind.export.audio.SoundEvent;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExportJob} with a fake session, a fake helper and fake files (Rewind Export PLAN Phase 5 and Revisions 4 and
 * 4.1; TASK P10, S2, S4, F2, U5, U6): the state order; frame 0 is drawn until it is stable without time moving;
 * {@code stopSounds} once, before the seek; sound for span i-1 after frame i; the last span after the tail; sample
 * positions 47,520 and 48,240 and the 48,480-sample total; pre-roll frames drawn but never sent, sounds then discarded;
 * the bit rate per quality; progress; cancel and failure in each state leave no file; success renames the partial file;
 * a stale partial file is removed on prepare.
 */
public class ExportJobTest {

    static final int W = 64;
    static final int H = 36;

    /** Everything the fakes saw, in order. */
    final List<String> events = new ArrayList<String>();
    final Set<File> files = new HashSet<File>();
    final File out = new File("/exports/Solo - Map - 2026-11-22 10.00.00.mp4");
    final File partial = new File("/exports/Solo - Map - 2026-11-22 10.00.00.partial.mp4");

    FakeSession session;
    FakeGame game;
    FakeEncoder encoder;
    List<String> startArgs;
    boolean encoderStartThrows;
    ExportJob job;
    long now = 10_000;
    /** The pixels the next draw reads back (frame 0's settle compares them). */
    int shade = 100;
    /** Interleaved samples the helper got, in order. */
    final List<Short> samples = new ArrayList<Short>();

    // ------------------------------------------------------------------ fakes

    /** A session with a 3-frame pre-roll, a fixed step and a tail; sounds "play" when their time is stepped over. */
    final class FakeSession implements ExportJob.Session {
        ExportPlan plan;
        boolean begun;
        boolean refuse;
        boolean ended;
        int prerollLeft = 3;
        boolean preroll;
        long idx;
        long frame = -1;
        boolean taken;
        boolean last;
        boolean tailAsked;
        boolean tailDone;
        long timeUs = Long.MIN_VALUE;
        /** time -> sound, played (through the job's discard check) when a step reaches it. */
        final TreeMap<Long, SoundEvent> sounds = new TreeMap<Long, SoundEvent>();
        final List<Boolean> discardSeenInPreroll = new ArrayList<Boolean>();

        @Override
        public boolean begin(ExportPlan p) {
            events.add("begin");
            if (refuse) {
                return false;
            }
            plan = p;
            begun = true;
            return true;
        }

        /** Minecraft's frame hook: the next step. */
        void frameStart() {
            if (!begun || ended) {
                return;
            }
            long from = timeUs;
            if (prerollLeft > 0) {
                preroll = true;
                timeUs = plan.startUs() - prerollLeft * 16_667L;
                prerollLeft--;
                frame = -1;
            } else if (tailAsked && !tailDone) {
                preroll = false;
                tailDone = true;
                frame = -1;
                timeUs = plan.endUs() - 1;
            } else if (last) {
                frame = -1;
            } else {
                preroll = false;
                if (taken) {
                    taken = false;
                    if (idx + 1 < plan.frames()) {
                        idx++;
                    } else {
                        last = true;
                        frame = -1;
                        return;
                    }
                }
                frame = idx;
                timeUs = plan.timeOfFrame(idx);
            }
            for (SoundEvent e : sounds.subMap(from, false, timeUs, true).values()) {
                if (preroll) {
                    discardSeenInPreroll.add(job.discardSounds());
                }
                if (!job.discardSounds()) { // ExportSoundTap's own check
                    job.soundLog().add(e);
                }
            }
        }

        @Override
        public long frame() {
            return frame;
        }

        @Override
        public boolean preroll() {
            return begun && preroll;
        }

        @Override
        public void advance() {
            events.add("advance " + frame);
            taken = true;
        }

        @Override
        public void collectTail() {
            events.add("collectTail");
            tailAsked = true;
        }

        @Override
        public boolean tailCollected() {
            return tailDone;
        }

        @Override
        public boolean exporting() {
            return begun && !ended;
        }

        @Override
        public void end() {
            events.add("end");
            ended = true;
        }
    }

    final class FakeGame implements ExportJob.Game {
        Boolean queue;
        int stopSounds;
        ExportJob tap;

        @Override
        public void stopSounds() {
            stopSounds++;
            events.add("stopSounds");
        }

        @Override
        public void soundTap(ExportJob j) {
            tap = j;
            events.add(j == null ? "tap off" : "tap on");
        }

        @Override
        public Boolean queueReady() {
            return queue;
        }

        @Override
        public void log(String line) {
        }

        @Override
        public void frameTaken(long index, int draws, long waitedMs) {
        }
    }

    final class FakeEncoder implements ExportJob.Encoder {
        Status status = Status.STARTING;
        String failure;
        boolean cancelled;
        boolean finished;

        @Override
        public Status status() {
            return status;
        }

        @Override
        public String failure() {
            return failure;
        }

        @Override
        public void frame(ByteBuffer bgra) {
            assertEquals("a whole frame", W * H * 4, bgra.remaining());
            events.add("frame");
        }

        @Override
        public void sound(short[] interleaved, int frames) {
            events.add("sound " + frames);
            for (int i = 0; i < 2 * frames; i++) {
                samples.add(interleaved[i]);
            }
        }

        @Override
        public void finish() {
            events.add("finish");
            finished = true;
        }

        @Override
        public void cancel() {
            events.add("cancel");
            cancelled = true;
            files.remove(partial); // the helper deletes its file too
        }
    }

    final ExportJob.Files fakeFiles = new ExportJob.Files() {
        @Override
        public boolean exists(File f) {
            return files.contains(f);
        }

        @Override
        public boolean delete(File f) {
            events.add("delete " + f.getName());
            return files.remove(f);
        }

        @Override
        public boolean rename(File from, File to) {
            events.add("rename");
            if (!files.remove(from)) {
                return false;
            }
            files.add(to);
            return true;
        }
    };

    /** One impulse: sample 0 is 10,000, mono, 48 kHz. */
    static final Mixer.Source IMPULSE = sound -> new Mixer.Pcm(48_000, 1, new short[]{10_000, 0, 0, 0});

    static SoundEvent at(long us) {
        return new SoundEvent(us, "impulse", 0, 0, 0, 1f, 1f, 16f, 0, 0, 0, 0f);
    }

    @Before
    public void fresh() {
        events.clear();
        files.clear();
        samples.clear();
        session = new FakeSession();
        game = new FakeGame();
        encoder = new FakeEncoder();
        encoderStartThrows = false;
        startArgs = null;
    }

    ExportJob.Request request(ExportPlan plan, String quality) {
        ExportJob.Request r = new ExportJob.Request();
        r.plan = plan;
        r.width = W;
        r.height = H;
        r.quality = quality;
        r.codec = "h264";
        r.out = out;
        r.helper = new File("/bin/cobblify-encode-0123");
        return r;
    }

    ExportJob job(ExportJob.Request r) {
        job = new ExportJob(r, session, game, args -> {
            if (encoderStartThrows) {
                throw new java.io.IOException("no");
            }
            startArgs = args;
            events.add("start");
            files.add(partial);
            return encoder;
        }, fakeFiles, IMPULSE);
        job.prepare(now);
        return job;
    }

    ByteBuffer pixels() {
        ByteBuffer b = ByteBuffer.allocateDirect(W * H * 4);
        for (int i = 0; i < W * H; i++) {
            b.put((byte) shade).put((byte) shade).put((byte) shade).put((byte) 255);
        }
        b.flip();
        return b;
    }

    /** One game-loop pass: the frame hook, the job's pass, the draw and its read-back. */
    void pass() {
        now += 16;
        session.frameStart();
        job.pass(now);
        job.drawn(pixels(), W, H, now);
    }

    void runUntil(ExportJob.State s, int max) {
        for (int i = 0; i < max && job.state() != s && !job.finished(); i++) {
            pass();
        }
        assertEquals(s, job.state());
    }

    List<ExportJob.State> states;

    /** Drives the whole export, the helper ready at once and done one pass after finish. */
    void runToEnd() {
        states = new ArrayList<ExportJob.State>();
        states.add(job.state());
        encoder.status = ExportJob.Encoder.Status.READY;
        for (int i = 0; i < 10_000 && !job.finished(); i++) {
            pass();
            if (encoder.finished) {
                encoder.status = ExportJob.Encoder.Status.DONE;
            }
            if (states.get(states.size() - 1) != job.state()) {
                states.add(job.state());
            }
        }
        assertTrue("finished: " + job.state(), job.finished());
    }

    int indexOf(String e) {
        return events.indexOf(e);
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void theStatesComeInOrderAndSuccessRenamesThePartialFile() {
        job(request(new ExportPlan(0, 1_000_000L, 60), "high"));
        assertEquals(ExportJob.State.PREPARING, job.state());
        runToEnd();
        assertEquals(Arrays.asList(ExportJob.State.PREPARING, ExportJob.State.SETTLING, ExportJob.State.RENDERING,
                ExportJob.State.FINISHING, ExportJob.State.DONE), states);
        assertNull(job.failure());
        assertTrue(files.contains(out));
        assertFalse(files.contains(partial));
        assertEquals(60, job.framesSent());
        assertEquals(1.0, job.progress(), 0);
        assertTrue("the session ends", events.contains("end"));
        assertTrue("the tap is off at the end", events.contains("tap off"));
        assertEquals("the helper writes the partial file", partial.getAbsolutePath(),
                startArgs.get(startArgs.indexOf("--out") + 1));
    }

    @Test
    public void frameZeroIsDrawnUntilStableAndTimeDoesNotMove() {
        job(request(new ExportPlan(0, 1_000_000L, 60), "high"));
        encoder.status = ExportJob.Encoder.Status.READY;
        for (int i = 0; i < 4; i++) {
            pass(); // the session begins, then 3 pre-roll passes
        }
        for (int i = 0; i < 6; i++) {
            shade = 100 + 10 * i; // the picture still changes
            pass();
            assertEquals("frame 0 again, time held", 0, session.frame());
            assertEquals(0, session.timeUs);
        }
        assertFalse("not sent while it changes", events.contains("frame"));
        assertEquals(ExportJob.State.SETTLING, job.state());
        shade = 200;
        pass();
        pass();
        assertFalse("two equal draws are not three", events.contains("frame"));
        pass();
        assertTrue("the third equal draw is taken", events.contains("frame"));
        assertEquals(ExportJob.State.RENDERING, job.state());
        assertEquals("advance 0", events.get(events.size() - 1));
    }

    @Test
    public void midRangeFramesAreTakenAtTheirFirstDrawUnlessTheQueuesAreBusy() {
        job(request(new ExportPlan(0, 200_000L, 60), "high"));
        encoder.status = ExportJob.Encoder.Status.READY;
        runUntil(ExportJob.State.RENDERING, 50);
        long sent = job.framesSent();
        pass();
        assertEquals("no hold mid-range (Revision 4)", sent + 1, job.framesSent());
        game.queue = Boolean.FALSE; // vanilla renderer, queues busy: an extra wait only
        pass();
        pass();
        assertEquals(sent + 1, job.framesSent());
        game.queue = Boolean.TRUE;
        pass();
        assertEquals(sent + 2, job.framesSent());
        game.queue = Boolean.FALSE;
        long t0 = now;
        while (now - t0 < SettleRule.CAP_MS + 32 && job.framesSent() == sent + 2) {
            pass();
        }
        assertEquals("the queue wait is capped at 2 s", sent + 3, job.framesSent());
        assertTrue(now - t0 >= SettleRule.CAP_MS);
    }

    @Test
    public void stopSoundsOnceBeforeTheSeekAndNeverAgain() {
        job(request(new ExportPlan(0, 500_000L, 60), "high"));
        pass();
        pass();
        assertEquals("not before the helper is ready", 0, game.stopSounds);
        runToEnd();
        assertEquals(1, game.stopSounds);
        assertTrue(indexOf("stopSounds") < indexOf("begin"));
        assertTrue("the tap is on before the seek", indexOf("tap on") < indexOf("begin"));
    }

    @Test
    public void soundForSpanIMinusOneIsSentAfterFrameI() {
        job(request(new ExportPlan(0, 100_000L, 60), "high")); // 6 frames
        runToEnd();
        List<String> io = new ArrayList<String>();
        for (String e : events) {
            if (e.equals("frame") || e.startsWith("sound") || e.equals("finish") || e.equals("collectTail")) {
                io.add(e.startsWith("sound") ? "sound" : e);
            }
        }
        assertEquals(Arrays.asList("frame", "frame", "sound", "frame", "sound", "frame", "sound", "frame", "sound",
                "frame", "sound", "collectTail", "sound", "finish"), io);
    }

    @Test
    public void theTailsSoundLandsOnSample47520() {
        job(request(new ExportPlan(0, 1_000_000L, 60), "high"));
        session.sounds.put(990_000L, at(990_000L)); // after the last frame (983,333): only the tail reaches it
        runToEnd();
        assertTrue(indexOf("collectTail") < events.lastIndexOf("sound 800"));
        assertEquals("48,000 stereo samples", 48_000 * 2, samples.size());
        int first = -1;
        for (int i = 0; i < samples.size(); i++) {
            if (samples.get(i) != 0) {
                first = i / 2;
                break;
            }
        }
        assertEquals(47_520, first);
    }

    @Test
    public void anEndNotOnAFrameKeepsTheSoundToTheSelectedEnd() {
        job(request(new ExportPlan(0, 1_010_000L, 60), "high"));
        session.sounds.put(1_005_000L, at(1_005_000L));
        runToEnd();
        assertEquals("round((end - start) x 48000 / 1e6) = 48,480", 48_480 * 2, samples.size());
        int first = -1;
        for (int i = 0; i < samples.size(); i++) {
            if (samples.get(i) != 0) {
                first = i / 2;
                break;
            }
        }
        assertEquals(48_240, first);
    }

    @Test
    public void preRollFramesAreDrawnButNeverSentAndTheirSoundsAreDiscarded() {
        job(request(new ExportPlan(1_000_000L, 1_100_000L, 60), "high"));
        session.sounds.put(960_000L, at(1_000_000L)); // plays during the pre-roll
        encoder.status = ExportJob.Encoder.Status.READY;
        pass(); // begins
        for (int i = 0; i < 3; i++) {
            pass();
            assertTrue(session.preroll());
            assertTrue("sounds are discarded during the pre-roll", job.discardSounds());
            assertFalse("no pre-roll frame reaches the helper", events.contains("frame"));
        }
        assertEquals(Arrays.asList(true), session.discardSeenInPreroll);
        assertTrue(job.soundLog().events().isEmpty());
        runToEnd();
        assertFalse(job.discardSounds());
        for (short s : samples) {
            assertEquals("nothing from the pre-roll in the mix", 0, s);
        }
    }

    @Test
    public void theHelperGetsTheBitRateOfTheChosenQuality() {
        Set<String> rates = new HashSet<String>();
        for (String q : new String[]{"standard", "high", "max"}) {
            fresh();
            ExportJob.Request req = request(new ExportPlan(0, 1_000_000L, 60), q);
            req.width = 1920;
            req.height = 1080;
            job(req);
            String r = startArgs.get(startArgs.indexOf("--bitrate") + 1);
            assertEquals(String.valueOf(ExportQuality.bitsPerSecond(1920, 1080, 60, q, "h264")), r);
            assertEquals(String.valueOf(job.bitsPerSecond()), r);
            rates.add(r);
        }
        assertEquals("three different values", 3, rates.size());
    }

    @Test
    public void progressIsFramesSentOverFrames() {
        job(request(new ExportPlan(0, 1_000_000L, 60), "high"));
        encoder.status = ExportJob.Encoder.Status.READY;
        runUntil(ExportJob.State.RENDERING, 50);
        for (int i = 0; i < 14; i++) {
            pass();
        }
        assertEquals(15, job.framesSent());
        assertEquals(15 / 60.0, job.progress(), 1e-9);
    }

    /** Cancel in PREPARING, SETTLING, RENDERING and FINISHING. */
    @Test
    public void cancelInEachStateLeavesNoFile() {
        for (ExportJob.State at : new ExportJob.State[]{ExportJob.State.PREPARING, ExportJob.State.SETTLING,
                ExportJob.State.RENDERING, ExportJob.State.FINISHING}) {
            fresh();
            job(request(new ExportPlan(0, 200_000L, 60), "high"));
            if (at != ExportJob.State.PREPARING) {
                encoder.status = ExportJob.Encoder.Status.READY;
                runUntil(at, 100);
            }
            job.cancel("esc");
            pass();
            assertEquals(at + "", ExportJob.State.CANCELLED, job.state());
            assertTrue(at + ": the helper's input closed", encoder.cancelled);
            assertFalse(at + ": no partial file", files.contains(partial));
            assertFalse(at + ": no final file", files.contains(out));
            assertEquals(at + ": the session ends once begun", at != ExportJob.State.PREPARING, events.contains("end"));
            int sent = events.size();
            pass();
            job.cancel("again");
            assertEquals("nothing after the end", sent, events.size());
        }
    }

    @Test
    public void eachFailureEndsFailedWithItsCodeAndNoFile() {
        // the helper is missing
        ExportJob.Request r = request(new ExportPlan(0, 200_000L, 60), "high");
        r.helper = null;
        r.helperProblem = "missing";
        job(r);
        assertEquals(ExportJob.State.FAILED, job.state());
        assertEquals("helper-missing", job.failure());
        assertNull("never started", startArgs);

        // the helper does not start
        fresh();
        encoderStartThrows = true;
        job(request(new ExportPlan(0, 200_000L, 60), "high"));
        assertEquals(ExportJob.State.FAILED, job.state());
        assertEquals("helper-start", job.failure());

        // the helper fails while preparing, settling, rendering, finishing
        for (ExportJob.State at : new ExportJob.State[]{ExportJob.State.PREPARING, ExportJob.State.SETTLING,
                ExportJob.State.RENDERING, ExportJob.State.FINISHING}) {
            fresh();
            job(request(new ExportPlan(0, 200_000L, 60), "high"));
            if (at != ExportJob.State.PREPARING) {
                encoder.status = ExportJob.Encoder.Status.READY;
                runUntil(at, 100);
            }
            encoder.status = ExportJob.Encoder.Status.FAILED;
            encoder.failure = "encoder_refused";
            pass();
            assertEquals(at + "", ExportJob.State.FAILED, job.state());
            assertEquals("encoder_refused", job.failure());
            assertFalse(at + ": no file", files.contains(partial) || files.contains(out));
            assertTrue(encoder.cancelled);
        }

        // the session refuses the plan
        fresh();
        session.refuse = true;
        job(request(new ExportPlan(0, 200_000L, 60), "high"));
        encoder.status = ExportJob.Encoder.Status.READY;
        pass();
        assertEquals(ExportJob.State.FAILED, job.state());
        assertEquals("session", job.failure());
        assertFalse(files.contains(partial));

        // the file cannot be renamed
        fresh();
        job(request(new ExportPlan(0, 100_000L, 60), "high"));
        encoder.status = ExportJob.Encoder.Status.READY;
        for (int i = 0; i < 1_000 && !encoder.finished; i++) {
            pass();
        }
        files.remove(partial); // gone under us
        encoder.status = ExportJob.Encoder.Status.DONE;
        pass();
        assertEquals(ExportJob.State.FAILED, job.state());
        assertEquals("rename", job.failure());
        assertFalse(files.contains(out));

        // the helper never says ready
        fresh();
        job(request(new ExportPlan(0, 100_000L, 60), "high"));
        now += ExportJob.START_TIMEOUT_MS + 1;
        pass();
        assertEquals(ExportJob.State.FAILED, job.state());
        assertEquals("helper-timeout", job.failure());
    }

    @Test
    public void aStalePartialFileIsRemovedOnPrepare() {
        files.add(partial);
        job(request(new ExportPlan(0, 200_000L, 60), "high"));
        assertTrue("deleted before the helper starts: " + events,
                indexOf("delete " + partial.getName()) >= 0 && indexOf("delete " + partial.getName()) < indexOf("start"));
        assertNotEquals(ExportJob.State.FAILED, job.state());
    }
}
