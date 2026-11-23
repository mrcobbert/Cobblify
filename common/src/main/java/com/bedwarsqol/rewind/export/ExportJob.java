package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.export.audio.Mixer;
import com.bedwarsqol.rewind.export.audio.SoundEvent;
import com.bedwarsqol.rewind.export.audio.SoundLog;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;

/**
 * One export, frames and sound to a file (Rewind Export PLAN Solution 4 and Phase 5, as amended by Revisions 4 and
 * 4.1; TASK P1, P10, S1, S2, S4, F2, U5, U6). The game calls {@link #pass} once per pass of its loop, before the frame,
 * and {@link #drawn} after the frame with its read-back.
 *
 * <ul>
 *   <li><b>PREPARING</b> ({@link #prepare}): a stale {@code .partial.mp4} of the same name is deleted, the helper starts
 *       with the bit rate of the chosen quality ({@link ExportQuality}) and writes to the partial file. Once it is
 *       ready: every sound still playing stops ({@link Game#stopSounds}, once), the sound tap turns on, and the
 *       session begins the export (its seek and pre-roll).</li>
 *   <li><b>SETTLING</b>: the seek and the 1 s pre-roll are drawn, never sent; their sounds are discarded
 *       ({@link #discardSounds}). Frame 0 is drawn again, time held, until three draws agree ({@link SettleRule}, 2 s
 *       cap, counted as {@code settleCapped}); on the vanilla renderer its chunk queues must read idle as well
 *       ({@code queue-wait}, an extra wait only).</li>
 *   <li><b>RENDERING</b>: frame i is sent at its first draw (no mid-range holds, Revision 4; the session paces each
 *       step to real time, Revision 4.1), then the sound of frame i-1's span, mixed after frame i's ticks have run. On
 *       the vanilla renderer a frame waits while the chunk queues are busy, capped at 2 s.</li>
 *   <li><b>FINISHING</b>: after the last frame the session collects the tail up to the selected end, the last span of
 *       sound is sent (to {@link ExportPlan#audioSamples()}), then finish; on the helper's {@code done} the partial
 *       file is renamed to the final name.</li>
 *   <li><b>DONE</b>, <b>CANCELLED</b>, <b>FAILED</b>(code): cancel and failure close the helper's input (it deletes
 *       its file) and delete the partial file as well; the session ends; the tap turns off.</li>
 * </ul>
 * Game thread.
 */
public final class ExportJob {

    public enum State { PREPARING, SETTLING, RENDERING, FINISHING, DONE, CANCELLED, FAILED }

    /** The playback session as the job drives it ({@code PlaybackSession} in the game). */
    public interface Session {
        /** {@code beginExport}: false when refused. */
        boolean begin(ExportPlan plan);

        /** {@code exportFrame()}: the frame this pass draws, or -1 (seek, pre-roll, a paced wait, the tail). */
        long frame();

        /** {@code exportPreroll()}. */
        boolean preroll();

        /** {@code advanceExportFrame()}. */
        void advance();

        /** {@code collectExportTail()}. */
        void collectTail();

        /** {@code exportTailCollected()}. */
        boolean tailCollected();

        /** {@code exporting()}. */
        boolean exporting();

        /** {@code endExport()}. */
        void end();
    }

    /** The game around the job. */
    public interface Game {
        /** {@code SoundHandler.stopSounds()}: nothing playing or queued is heard during the export. */
        void stopSounds();

        /** Turns the sound tap on for this job's log ({@link #soundLog}, {@link #discardSounds}), or off (null). */
        void soundTap(ExportJob job);

        /** The vanilla renderer's chunk queues read idle (true), busy (false), or there is no such signal (null). */
        Boolean queueReady();

        /** A counts-only log line. */
        void log(String line);

        /** Frame {@code index} was sent after {@code draws} draws, {@code waitedMs} after its first. */
        void frameTaken(long index, int draws, long waitedMs);
    }

    /** The helper as the job sees it ({@link HelperProcess}). */
    public interface Encoder {
        enum Status { STARTING, READY, DONE, FAILED }

        Status status();

        String failure();

        void frame(ByteBuffer bgra);

        void sound(short[] interleaved, int frames);

        void finish();

        void cancel();
    }

    /** Starts the helper with an argument list. */
    public interface EncoderStart {
        Encoder start(List<String> args) throws Exception;
    }

    /** The file system as the job uses it. */
    public interface Files {
        boolean exists(File f);

        boolean delete(File f);

        boolean rename(File from, File to);
    }

    /** What to export. */
    public static final class Request {
        public ExportPlan plan;
        public int width;
        public int height;
        public String quality = "high";
        public String codec = "h264";
        /** The final file ({@link ExportFileName#pick}). */
        public File out;
        /** The helper to run ({@link HelperStore#find}); null with {@link #helperProblem} when there is none. */
        public File helper;
        public String helperProblem;
        /** Reference captures only (cobdev): every frame is drawn this long before it is sent; 0 in the product. */
        public long fixedHoldMs;
        /** Frame 0 waits for a stable picture (the product); off for raw timing runs. */
        public boolean settleFrameZero = true;
    }

    /** The helper must say ready within this. */
    public static final long START_TIMEOUT_MS = 15_000;
    /** And done within this after finish. */
    public static final long FINISH_TIMEOUT_MS = 60_000;

    private final Request req;
    private final ExportPlan plan;
    private final Session session;
    private final Game game;
    private final EncoderStart encoders;
    private final Files files;
    private final File partial;
    private final long bps;
    private final SoundLog log;
    private final Mixer mixer;

    private Encoder encoder;
    private State state = State.PREPARING;
    private String failure;
    private String cancelAsked;
    private boolean begun;
    private long preparedMs;
    private long finishMs;
    private boolean finishSent;
    private boolean lastTaken;

    private long offered = -1;
    private long offeredMs;
    private int drawsOfFrame;
    private SettleRule rule;

    private long sent;
    private long soundSent;
    private short[] soundBuf = new short[0];

    private long settleCapped;
    private long frameZeroDraws;
    private long frameZeroMs;
    private boolean queueSignal;
    private long queueWaits;
    private long queueCapped;
    private long draws;
    private long firstSentMs = -1;
    private long lastSentMs;

    public ExportJob(Request request, Session session, Game game, EncoderStart encoders, Files files,
                     Mixer.Source source) {
        this.req = request;
        this.plan = request.plan;
        this.session = session;
        this.game = game;
        this.encoders = encoders;
        this.files = files;
        this.partial = ExportFileName.partial(request.out);
        this.bps = ExportQuality.bitsPerSecond(request.width, request.height, plan.fps(), request.quality,
                request.codec);
        this.log = new SoundLog(plan.startUs(), plan.endUs());
        this.mixer = new Mixer(plan.startUs(), source);
    }

    /** Removes a stale partial file and starts the helper (PREPARING). */
    public void prepare(long nowMs) {
        preparedMs = nowMs;
        if (files.exists(partial)) {
            files.delete(partial);
        }
        if (req.helper == null) {
            fail("helper-" + (req.helperProblem == null ? "missing" : req.helperProblem));
            return;
        }
        try {
            encoder = encoders.start(HelperProtocol.encodeArgs(req.helper.getAbsolutePath(), partial, req.width,
                    req.height, plan.fps(), req.codec, bps));
        } catch (Exception e) {
            encoder = null;
            fail("helper-start");
            return;
        }
        if (encoder == null) {
            fail("helper-start");
        }
    }

    /** Once per pass, before the frame. */
    public void pass(long nowMs) {
        if (finished()) {
            return;
        }
        if (cancelAsked != null) {
            stop(State.CANCELLED, cancelAsked);
            return;
        }
        Encoder.Status es = encoder == null ? Encoder.Status.FAILED : encoder.status();
        if (es == Encoder.Status.FAILED) {
            fail(encoder == null ? "helper-start" : encoder.failure() == null ? "helper" : encoder.failure());
            return;
        }
        switch (state) {
            case PREPARING:
                if (es == Encoder.Status.READY) {
                    game.stopSounds();
                    game.soundTap(this);
                    if (!session.begin(plan)) {
                        fail("session");
                        return;
                    }
                    begun = true;
                    state = State.SETTLING;
                    game.log("rewind-export: helper ready, seek and pre-roll");
                } else if (nowMs - preparedMs > START_TIMEOUT_MS) {
                    fail("helper-timeout");
                }
                break;
            case SETTLING:
            case RENDERING:
                if (!session.exporting()) {
                    fail("session-ended");
                }
                break;
            case FINISHING:
                if (finishSent && es == Encoder.Status.DONE) {
                    complete();
                } else if (finishSent && nowMs - finishMs > FINISH_TIMEOUT_MS) {
                    fail("finish-timeout");
                } else if (!finishSent && !session.exporting()) {
                    fail("session-ended");
                }
                break;
            default:
                break;
        }
    }

    /** After the frame, with its read-back ({@code width x height x 4} BGRA bytes from the buffer's position). */
    public void drawn(ByteBuffer bgra, int width, int height, long nowMs) {
        if (state != State.SETTLING && state != State.RENDERING && state != State.FINISHING) {
            return;
        }
        draws++;
        if (lastTaken) {
            if (!finishSent && session.tailCollected()) {
                soundUpTo(plan.audioSamples(), plan.endUs());
                encoder.finish();
                finishSent = true;
                finishMs = nowMs;
            }
            return;
        }
        long idx = session.frame();
        if (idx < 0) {
            return; // seek, pre-roll or a paced wait: drawn, not sent
        }
        if (idx != offered) {
            offered = idx;
            offeredMs = nowMs;
            drawsOfFrame = 0;
            rule = idx == 0 && req.settleFrameZero && req.fixedHoldMs <= 0 ? SettleRule.frameZero(nowMs) : null;
        }
        drawsOfFrame++;
        Boolean queue = game.queueReady();
        if (queue != null) {
            queueSignal = true;
        }
        if (req.fixedHoldMs > 0) {
            if (nowMs - offeredMs < req.fixedHoldMs) {
                return;
            }
        } else if (rule != null) {
            SettleRule.State st = rule.offer(SettleRule.grid(bgra, width, height), nowMs, queue == null || queue);
            if (st == SettleRule.State.SETTLING) {
                return;
            }
            if (st == SettleRule.State.CAPPED) {
                settleCapped++;
            }
            frameZeroDraws = drawsOfFrame;
            frameZeroMs = nowMs - offeredMs;
        } else if (queue != null && !queue) {
            if (nowMs - offeredMs < SettleRule.CAP_MS) {
                queueWaits++;
                return;
            }
            queueCapped++;
        }
        encoder.frame(bgra);
        if (idx >= 1) {
            soundUpTo(sampleOf(idx), plan.timeOfFrame(idx));
        }
        sent++;
        if (firstSentMs < 0) {
            firstSentMs = nowMs;
        }
        lastSentMs = nowMs;
        game.frameTaken(idx, drawsOfFrame, nowMs - offeredMs);
        session.advance();
        if (idx == 0) {
            state = State.RENDERING;
        }
        if (idx + 1 >= plan.frames()) {
            session.collectTail();
            lastTaken = true;
            state = State.FINISHING;
        }
    }

    /** Asks the export to stop at the next pass (Esc, the probe). */
    public void cancel(String why) {
        if (!finished() && cancelAsked == null) {
            cancelAsked = why == null ? "cancel" : why;
        }
    }

    // ------------------------------------------------------------------ state

    public State state() {
        return state;
    }

    public boolean finished() {
        return state == State.DONE || state == State.CANCELLED || state == State.FAILED;
    }

    /** The failure code (FAILED) or the cancel reason (CANCELLED); null otherwise. */
    public String failure() {
        return failure;
    }

    /** Frames sent / frames. */
    public double progress() {
        return sent / (double) plan.frames();
    }

    public long framesSent() {
        return sent;
    }

    /** Sounds played now are not logged: before frame 0 (the seek and the pre-roll). */
    public boolean discardSounds() {
        return !begun || session.preroll();
    }

    /** Where the tap logs the export's sounds. */
    public SoundLog soundLog() {
        return log;
    }

    public File partialFile() {
        return partial;
    }

    public File outFile() {
        return req.out;
    }

    public ExportPlan plan() {
        return plan;
    }

    public long bitsPerSecond() {
        return bps;
    }

    public long settleCapped() {
        return settleCapped;
    }

    /** {@code queue-wait} when the vanilla renderer's queue signal was used, else {@code real-time-floor} only. */
    public String rule() {
        return queueSignal ? "real-time-floor+queue-wait" : "real-time-floor";
    }

    /** Frames sent per second of wall time, from the first frame sent to the last (0 before two). */
    public double renderFps() {
        double secs = firstSentMs < 0 || sent < 2 ? 0 : (lastSentMs - firstSentMs) / 1000.0;
        return secs <= 0 ? 0 : (sent - 1) / secs;
    }

    /** A counts-only summary for the log. */
    public String summary() {
        return String.format(Locale.ROOT, "frames=%d/%d rule=%s settleCapped=%d frame0Draws=%d frame0Ms=%d"
                        + " queueWaits=%d queueCapped=%d draws=%d renderFps=%.2f soundsMissing=%d soundsDropped=%d"
                        + " bitrate=%d",
                sent, plan.frames(), rule(), settleCapped, frameZeroDraws, frameZeroMs, queueWaits, queueCapped, draws,
                renderFps(), mixer.missing(), log.dropped(), bps);
    }

    // ------------------------------------------------------------------ inside

    /** The sample frame i starts on. */
    private long sampleOf(long i) {
        return Math.min(plan.audioSamples(), Mixer.sampleAt(plan.startUs(), plan.timeOfFrame(i)));
    }

    /** Mixes every sound starting before {@code toUs} and sends the samples up to {@code toSample}. */
    private void soundUpTo(long toSample, long toUs) {
        for (SoundEvent e : log.takeBefore(toUs)) {
            mixer.add(e);
        }
        int n = (int) Math.max(0, toSample - soundSent);
        if (n == 0) {
            return;
        }
        if (soundBuf.length < 2 * n) {
            soundBuf = new short[2 * n];
        }
        mixer.render(soundSent, n, soundBuf);
        encoder.sound(soundBuf, n);
        soundSent = toSample;
    }

    private void complete() {
        if (!files.rename(partial, req.out)) {
            fail("rename");
            return;
        }
        state = State.DONE;
        cleanup(false);
        game.log("rewind-export: done " + summary());
    }

    private void fail(String code) {
        if (finished()) {
            return;
        }
        failure = code;
        state = State.FAILED;
        cleanup(true);
        game.log("rewind-export: failed " + code + " " + summary());
    }

    private void stop(State end, String why) {
        failure = why;
        state = end;
        cleanup(true);
        game.log("rewind-export: cancelled " + summary());
    }

    private void cleanup(boolean discard) {
        if (discard && encoder != null) {
            try {
                encoder.cancel();
            } catch (RuntimeException ignored) {
                // closing the input is all that matters, and it is done
            }
        }
        if (discard && files.exists(partial)) {
            files.delete(partial);
        }
        if (begun) {
            session.end();
        }
        game.soundTap(null);
    }
}
