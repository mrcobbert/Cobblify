package com.bedwarsqol.rewind.export;

import com.bedwarsqol.feature.DiagLog;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.watch.HudPlayback;
import com.bedwarsqol.rewind.watch.LoadedRecording;
import com.bedwarsqol.rewind.watch.ModeLabels;
import com.bedwarsqol.rewind.watch.NetCursor;
import com.bedwarsqol.rewind.watch.PlaybackSession;
import com.bedwarsqol.rewind.watch.RewindPlayback;
import com.bedwarsqol.rewind.watch.Timeline;
import com.bedwarsqol.rewind.watch.ViewState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Drives a Rewind export from the game loop (Rewind Export PLAN Solution 2 and Phase 5, as amended by Revisions 4 and
 * 4.1). {@link #start} exports a range of the recording being watched to an MP4 through the encoder helper;
 * {@link #capture} (cobdev's spike probes) hands the frames to a {@link FrameSink} instead. Both run one
 * {@link ExportJob}; the session paces every step to real time ({@link PlaybackSession#beginExport}).
 *
 * <p>Once per pass of the game loop the mixins call {@link #beforeRender}, {@link #afterMidFrameBind},
 * {@link #afterRender} and {@link #beforeDisplayUpdate}. While the export draws (its seek, pre-roll, frames and tail)
 * the export screen ({@link RewindExportScreen}) is the current screen, so only Esc does anything (it cancels).
 * <ul>
 *   <li>OWN route: every pass is drawn into our framebuffer at the export size, never into the window
 *       ({@link ExportFrames}); the read-back follows {@code updateCameraAndRender}. The window shows the frame as a
 *       preview with the filling key ({@link ExportOverlay}), drawn after the read-back.</li>
 *   <li>WINDOW route (PLAN D4, the fallback when the start-up check fails): nothing is overridden; the window's back
 *       buffer is read at {@code updateDisplay} HEAD, at the window's size rounded down to even numbers.</li>
 * </ul>
 * The first frame read back is the start-up check ({@link RenderRoute#decide}); a failure ends the export and selects
 * WINDOW for the rest of the game session. Motion Blur, vsync and the frame limit are put back through one
 * {@link ExportRestore}. Game thread, except {@link #statusJson}, {@link #frameLog} and {@link #cancel}.
 */
public final class RewindExport {

    /** How frames are taken (cobdev's spike; the product is {@link Mode#PRODUCT}). */
    public enum Mode {
        /** The product rule: real-time floor, frame 0 stable (2 s cap), queue-wait on the vanilla renderer. */
        PRODUCT,
        /** References: every frame drawn for {@link Options#fixedHoldMs} before it is taken (paced too). */
        FIXED,
        /** Raw render speed: no pacing, frame 0 at its first draw (K9 timing). */
        RAW
    }

    /** How a {@link #capture} runs. */
    public static final class Options {
        public RenderRoute.Route route = RenderRoute.Route.OWN;
        public int width = 1920;
        public int height = 1080;
        public Mode mode = Mode.PRODUCT;
        public long fixedHoldMs = 2000;
        /** The window is cleared to black instead of showing the preview (spike check K5). */
        public boolean clearWindow = true;
        /** Chat on (lines and the recorded input bar) or off. */
        public boolean chat = true;
    }

    /** An export to a file ({@link #start}). */
    public static final class Request {
        public long startUs;
        public long endUs;
        public ExportSettings settings = ExportSettings.defaults();
        /** Null: the game session's route (OWN until a start-up check failed). */
        public RenderRoute.Route route;
        /** A helper path that wins over the jar's (cobdev's missing-helper step); null: as configured. */
        public String helperOverride;
        /** An exact size instead of the settings' 16:9 height (cobdev's 640x360 step); 0: the settings'. */
        public int width;
        public int height;
    }

    /** The game session's route: WINDOW once a start-up check failed (TASK P9). */
    private static volatile RenderRoute.Route sessionRoute = RenderRoute.Route.OWN;
    private static volatile Run run;
    private static volatile String lastStatus = "{\"active\":false}";
    private static volatile List<String> lastFrames = Collections.emptyList();
    /** At most this many frame lines are kept for the probes. */
    static final int FRAME_LOG_LIMIT = 20_000;

    private RewindExport() {
    }

    // ================================================================== entry

    /**
     * Exports {@code [startUs, endUs)} of the recording being watched to the export folder (client thread).
     *
     * @return null when started, else why not (a code)
     */
    public static String start(Request q) {
        if (run != null) {
            return "busy";
        }
        PlaybackSession s = RewindPlayback.session();
        if (q == null || !RewindPlayback.active() || s.state() != PlaybackSession.State.WATCHING || s.exporting()
                || s.timeline() == null) {
            return "not-watching";
        }
        Minecraft mc = Minecraft.getMinecraft();
        ExportSettings set = q.settings == null ? ExportSettings.defaults() : q.settings;
        RenderRoute.Route route = q.route != null ? q.route : sessionRoute;
        boolean exact = q.width > 0 && q.height > 0 && (q.width & 1) == 0 && (q.height & 1) == 0;
        int w = route == RenderRoute.Route.WINDOW ? mc.displayWidth & ~1 : exact ? q.width
                : ExportSettings.width(set.height());
        int h = route == RenderRoute.Route.WINDOW ? mc.displayHeight & ~1 : exact ? q.height : set.height();
        if (q.endUs <= q.startUs || w < 128 || h < 128) {
            return "bad-range";
        }
        ExportPlan plan = new ExportPlan(q.startUs, q.endUs, set.fps());
        File dir = ExportDirs.resolve(System.getProperty(ExportDirs.OVERRIDE_PROPERTY), null,
                System.getProperty("user.home"), System.getProperty("os.name"));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return "folder";
        }
        RecordingMeta meta = s.meta();
        String base = ExportFileName.base(meta == null ? null : ModeLabels.mode(meta), meta == null ? null : meta.map,
                System.currentTimeMillis(), TimeZone.getDefault());
        File out = ExportFileName.pick(dir, base, File::exists);

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        HelperStore store = new HelperStore(new File(new File(System.getProperty("user.home"), ".cobblify"), "bin"),
                windows, HelperStore.jar(RewindExport.class));
        String override = q.helperOverride != null ? q.helperOverride : System.getProperty(HelperStore.OVERRIDE_PROPERTY);
        HelperStore.Found found = store.find(override);
        ExportJob.Request r = new ExportJob.Request();
        r.plan = plan;
        r.width = w;
        r.height = h;
        r.quality = set.quality();
        r.codec = set.codec();
        r.out = out;
        r.helper = found.file;
        r.helperProblem = found.problem;
        final int frameBytes = w * h * 4;
        ExportJob.EncoderStart encoders = args -> {
            if (!store.verify(new File(args.get(0)))) {
                throw new IllegalStateException("digest");
            }
            HelperProcess p = new HelperProcess(args, frameBytes, HelperProcess.processBuilder(), HelperProcess.STALL_MS);
            p.start();
            return p;
        };
        return begin(r, route, set.chat(), Mode.PRODUCT, null, encoders, REAL_FILES, false);
    }

    /**
     * Exports {@code plan} of the recording being watched into {@code sink} (cobdev's spike probes; client thread).
     *
     * @return null when started, else why not
     */
    public static String capture(ExportPlan plan, Options options, FrameSink sink) {
        if (options == null || sink == null || plan == null) {
            return "no plan, options or sink";
        }
        if (options.width <= 0 || options.height <= 0 || (options.width & 1) != 0 || (options.height & 1) != 0) {
            return "size must be even and positive";
        }
        ExportJob.Request r = new ExportJob.Request();
        r.plan = plan;
        r.width = options.route == RenderRoute.Route.WINDOW ? Minecraft.getMinecraft().displayWidth & ~1 : options.width;
        r.height = options.route == RenderRoute.Route.WINDOW ? Minecraft.getMinecraft().displayHeight & ~1
                : options.height;
        r.out = new File("spike.mp4");
        r.helper = new File("sink");
        r.fixedHoldMs = options.mode == Mode.FIXED ? options.fixedHoldMs : 0;
        r.settleFrameZero = options.mode != Mode.RAW;
        SinkEncoder enc = new SinkEncoder(sink, r.width, r.height);
        return begin(r, options.route, options.chat, options.mode, sink, args -> enc, NO_FILES, options.clearWindow);
    }

    private static String begin(ExportJob.Request r, RenderRoute.Route route, boolean chat, Mode mode, FrameSink sink,
                                ExportJob.EncoderStart encoders, ExportJob.Files files, boolean clearWindow) {
        if (run != null) {
            return "busy";
        }
        if (route == RenderRoute.Route.MAIN) {
            return "route-main"; // not built: the spike found OWN works (HANDOFF Phase 2)
        }
        Minecraft mc = Minecraft.getMinecraft();
        PlaybackSession s = RewindPlayback.session();
        if (!RewindPlayback.active() || s.state() != PlaybackSession.State.WATCHING || s.exporting()) {
            return "not-watching";
        }
        int windowScale = new ScaledResolution(mc).getScaleFactor();
        ExportTarget target = null;
        int scale = windowScale;
        if (route == RenderRoute.Route.OWN) {
            scale = ExportGuiScale.of(windowScale, mc.displayHeight, r.height);
            target = ExportTarget.create(r.width, r.height);
            ExportFrames.bindMain(mc);
            if (target == null) {
                return "no-framebuffers";
            }
        }
        ExportRestore restore = new ExportRestore();
        ExportFrames.saveExportSettings(mc, restore);
        final ExportTarget t = target;
        if (t != null) {
            restore.add("target", () -> {
                t.delete();
                ExportFrames.bindMain(mc);
            });
        }
        Run x = new Run(r, route, chat, mode, sink, target, scale, windowScale, restore, clearWindow);
        x.frames = new ExportFrames(mc, target, scale, x.screen);
        x.job = new ExportJob(r, new SessionAdapter(s, mode != Mode.RAW), new GameAdapter(x), encoders, files,
                ExportSoundTap.game().source());
        terrainTicks(s.recording(), s.timeline(), x.chunkTicks, x.blockTicks);
        run = x;
        x.job.prepare(nowMs());
        log(x, "rewind-export: start " + r.width + "x" + r.height + " fps=" + r.plan.fps() + " frames="
                + r.plan.frames() + " route=" + route + (target == null ? "" : " api=" + target.apiName()) + " guiScale="
                + windowScale + "->" + scale + " mode=" + mode + " quality=" + r.quality + " codec=" + r.codec
                + " bitrate=" + x.job.bitsPerSecond() + " chat=" + chat + " helper="
                + (r.helper == null ? r.helperProblem : "found"));
        if (x.job.finished()) {
            end(x);
            return null; // started and failed at once (helper missing): the status says why
        }
        publish(x);
        return null;
    }

    /** An export runs (from {@link #start} until it ended). */
    public static boolean active() {
        return run != null;
    }

    /** Asks the running export to stop at the next pass (any thread). */
    public static void cancel(String why) {
        Run x = run;
        if (x != null) {
            x.cancelAsked = why == null ? "cancel" : why;
        }
    }

    /** The running or last export's state and counts, as one JSON object (any thread). */
    public static String statusJson() {
        return lastStatus;
    }

    /** The running or last export's per-frame lines (counts and hashes only; any thread). */
    public static List<String> frameLog() {
        return lastFrames;
    }

    /** The game session's route (TASK P9). */
    public static RenderRoute.Route sessionRoute() {
        return sessionRoute;
    }

    /** The layers this pass's export frame asks for, or null when no export draws now (watching draws as recorded). */
    public static Set<ExportLayers.Layer> frameLayers() {
        Run x = run;
        return x == null || !x.drawing ? null : x.layers;
    }

    /** {@code GuiNewChat.drawChat} is skipped: an export frame with Chat off (TASK P5). */
    public static boolean hidesChatLines() {
        Set<ExportLayers.Layer> l = frameLayers();
        return l != null && !l.contains(ExportLayers.Layer.CHAT_LINES);
    }

    // ================================================================== hooks (MinecraftRewindExportMixin, EntityRendererRewindExportMixin)

    /** {@code runGameLoop}, right before {@code updateCameraAndRender}. */
    public static void beforeRender() {
        Run x = run;
        if (x == null) {
            return;
        }
        try {
            if (x.cancelAsked != null) {
                x.job.cancel(x.cancelAsked);
            }
            x.job.pass(nowMs());
            if (x.job.finished()) {
                end(x);
                return;
            }
            PlaybackSession s = RewindPlayback.session();
            if (!s.exporting()) {
                return; // the helper is not ready yet: an ordinary frame
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.currentScreen != x.screen) {
                RewindPlayback.display(x.screen); // after the seek's loading card and viewer: input goes to Esc only
            }
            x.layers = ExportLayers.of(s.view(), x.chat);
            x.drawing = true;
            x.passNanos = System.nanoTime();
            if (x.route == RenderRoute.Route.OWN) {
                x.frames.begin(x.clearWindow);
            }
        } catch (Throwable t) {
            x.job.cancel("begin-" + t.getClass().getSimpleName());
        }
    }

    /** {@code updateCameraAndRender}, right after vanilla's mid-frame {@code getFramebuffer().bindFramebuffer(true)}. */
    public static void afterMidFrameBind() {
        Run x = run;
        if (x != null && x.frames.inFrame()) {
            try {
                x.frames.rebind();
                x.rebinds++;
            } catch (Throwable ignored) {
                // the read-back's binding check reports it
            }
        }
    }

    /** {@code renderWorld}'s chunk-upload deadline: far out on export frames. */
    public static long renderDeadline(long finishTimeNano) {
        Run x = run;
        return x != null && x.drawing ? System.nanoTime() + 1_000_000_000L : finishTimeNano;
    }

    /** {@code runGameLoop}, right after {@code updateCameraAndRender} returned: the OWN route's read-back. */
    public static void afterRender() {
        Run x = run;
        if (x == null || !x.frames.inFrame()) {
            return;
        }
        try {
            ByteBuffer px = x.frames.readBack();
            x.boundAtReadBack = x.frames.boundAtReadBack == x.target.id();
            x.glError = x.frames.glError;
            drawn(x, px);
        } catch (Throwable t) {
            x.job.cancel("frame-" + t.getClass().getSimpleName());
        } finally {
            try {
                x.frames.end();
            } catch (Throwable ignored) {
                // the end binds the main framebuffer again
            }
        }
    }

    /** {@code Minecraft.updateDisplay} HEAD: WINDOW's read-back, then the preview and the key (never in the file). */
    public static void beforeDisplayUpdate() {
        Run x = run;
        if (x == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (x.route == RenderRoute.Route.WINDOW && x.drawing) {
                ByteBuffer px = ExportFrames.readWindow(mc, x.width(), x.height());
                x.boundAtReadBack = true;
                x.glError = ExportFrames.lastWindowError;
                drawn(x, px);
            }
        } catch (Throwable t) {
            x.job.cancel("window-" + t.getClass().getSimpleName());
        }
        x.drawing = false;
        try {
            if (x.clearWindow) {
                ExportFrames.clearWindow(mc);
            } else {
                ExportOverlay.draw(mc, x.route == RenderRoute.Route.OWN ? x.target : null, x.job.progress(),
                        x.job.state() == ExportJob.State.FAILED);
            }
        } catch (Throwable ignored) {
            // the window's look only
        }
    }

    // ================================================================== inside

    private static void drawn(Run x, ByteBuffer px) {
        PlaybackSession s = RewindPlayback.session();
        x.draws++;
        x.drawNanos += System.nanoTime() - x.passNanos;
        x.frameIndex = s.exportFrame();
        if (x.frameIndex >= 0 && x.startup == null) {
            RenderRoute.Check c = new RenderRoute.Check();
            c.complete = x.target == null || x.target.complete();
            c.boundAtReadBack = x.boundAtReadBack;
            c.glError = x.glError;
            c.uniform = RenderRoute.uniform(px, x.width(), x.height());
            RenderRoute.Decision d = RenderRoute.decide(x.route, c);
            x.startup = d.toString();
            if (d.route != x.route) {
                sessionRoute = RenderRoute.Route.WINDOW;
                log(x, "rewind-export: start-up check failed " + d.code + ": WINDOW for this game session");
                if (x.mode == Mode.PRODUCT) {
                    x.job.cancel("route-" + d.code);
                    return;
                }
            }
        }
        if (x.frameIndex >= 0) {
            if (!x.boundAtReadBack) {
                x.bindingMismatches++;
            }
            if (x.glError != 0) {
                x.glErrorFrames++;
            }
        }
        px.position(0);
        x.job.drawn(px, x.width(), x.height(), nowMs());
        publish(x);
    }

    private static void end(Run x) {
        if (run != x) {
            return;
        }
        run = null;
        x.drawing = false;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            x.frames.end();
        } catch (Throwable ignored) {
            // below
        }
        List<String> failed = x.restore.restore();
        try {
            if (RewindPlayback.active() && mc.currentScreen == x.screen) {
                RewindPlayback.displayViewer();
            }
        } catch (Throwable ignored) {
            // the session handles its screens
        }
        x.endNanos = System.nanoTime();
        log(x, "rewind-export: end " + x.job.state() + (x.job.failure() == null ? "" : " " + x.job.failure())
                + " " + x.job.summary() + " route=" + x.route + " startup=" + x.startup + " bindingMismatches="
                + x.bindingMismatches + " glErrorFrames=" + x.glErrorFrames
                + (failed.isEmpty() ? "" : " restoreFailed=" + failed));
        publish(x);
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private static void log(Run x, String line) {
        DiagLog.log(line);
        if (x.sink != null) {
            try {
                x.sink.log(line);
            } catch (Throwable ignored) {
                // the sink's problem
            }
        }
    }

    private static void publish(Run x) {
        ExportJob j = x.job;
        StringBuilder b = new StringBuilder(512);
        b.append("{\"active\":").append(run == x);
        b.append(",\"state\":\"").append(j.state()).append('"');
        b.append(",\"outcome\":").append(j.finished() ? "\"" + outcome(j) + "\"" : "null");
        b.append(",\"failure\":").append(j.failure() == null ? "null" : "\"" + j.failure() + "\"");
        b.append(",\"frames\":").append(j.plan().frames()).append(",\"taken\":").append(j.framesSent());
        b.append(",\"progress\":").append(fmt(j.progress()));
        b.append(",\"width\":").append(x.width()).append(",\"height\":").append(x.height());
        b.append(",\"fps\":").append(j.plan().fps());
        b.append(",\"route\":\"").append(x.route).append('"');
        b.append(",\"mode\":\"").append(x.mode).append('"');
        b.append(",\"windowScale\":").append(x.windowScale).append(",\"scale\":").append(x.scale);
        b.append(",\"rule\":\"").append(j.rule()).append('"');
        b.append(",\"settleCapped\":").append(j.settleCapped());
        b.append(",\"bitrate\":").append(j.bitsPerSecond());
        b.append(",\"bindingMismatches\":").append(x.bindingMismatches);
        b.append(",\"glErrorFrames\":").append(x.glErrorFrames).append(",\"rebinds\":").append(x.rebinds);
        b.append(",\"startup\":").append(x.startup == null ? "null" : "\"" + x.startup + "\"");
        b.append(",\"meanFrameMs\":").append(fmt(j.renderFps() <= 0 ? 0 : 1000.0 / j.renderFps()));
        b.append(",\"renderFps\":").append(fmt(j.renderFps()));
        b.append(",\"meanDrawMs\":").append(fmt(x.draws == 0 ? 0 : x.drawNanos / 1e6 / x.draws));
        b.append(",\"draws\":").append(x.draws);
        b.append(",\"chunkTicks\":").append(x.chunkTicks.cardinality());
        b.append(",\"blockTicks\":").append(x.blockTicks.cardinality());
        b.append(",\"stopSounds\":").append(x.stopSounds);
        b.append(",\"sounds\":{\"logged\":").append(ExportSoundTap.game().logged()).append(",\"discarded\":")
                .append(ExportSoundTap.game().discarded()).append(",\"missing\":")
                .append(ExportSoundTap.game().missing()).append('}');
        b.append(",\"tail\":").append(x.tailSeen);
        b.append(",\"file\":").append(x.sink != null || !j.finished() ? "null"
                : "\"" + json((j.state() == ExportJob.State.DONE ? j.outFile() : j.partialFile()).getAbsolutePath())
                + "\"");
        b.append(",\"elapsedMs\":").append(((x.endNanos == 0 ? System.nanoTime() : x.endNanos) - x.startNanos)
                / 1_000_000L);
        b.append('}');
        lastStatus = b.toString();
    }

    private static String outcome(ExportJob j) {
        switch (j.state()) {
            case DONE:
                return "done";
            case CANCELLED:
                return "cancelled-" + j.failure();
            default:
                return "failed-" + j.failure();
        }
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /**
     * The ticks (as ticks-run counts after them) whose records hold chunk data (0x21 S21, 0x26 S26) or block changes
     * (0x22 S22, 0x23 S23): a NET record stamped at t is fed by the first tick k with T[k] >= t (TickFeeder), so it has
     * been applied once k + 1 ticks have run. For the frame log and cobdev's K10/K11 fixture.
     */
    static void terrainTicks(LoadedRecording rec, Timeline tl, BitSet chunk, BitSet block) {
        if (rec == null || tl == null) {
            return;
        }
        NetCursor c = rec.newCursor();
        for (; !c.atEnd(); c.advance()) {
            int id = c.packetId();
            if (id != 0x21 && id != 0x26 && id != 0x22 && id != 0x23) {
                continue;
            }
            long k = tl.tickOf(c.timeUs());
            if (k < 0 || k >= Integer.MAX_VALUE - 1) {
                continue;
            }
            (id == 0x21 || id == 0x26 ? chunk : block).set((int) (k + 1));
        }
    }

    /** 64-bit FNV-1a, hex: a fingerprint of a frame's state, never its content. */
    static String hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h);
    }

    // ================================================================== the running export

    private static final ExportJob.Files REAL_FILES = new ExportJob.Files() {
        @Override
        public boolean exists(File f) {
            return f.exists();
        }

        @Override
        public boolean delete(File f) {
            return f.delete();
        }

        @Override
        public boolean rename(File from, File to) {
            return !to.exists() && from.renameTo(to);
        }
    };

    private static final ExportJob.Files NO_FILES = new ExportJob.Files() {
        @Override
        public boolean exists(File f) {
            return false;
        }

        @Override
        public boolean delete(File f) {
            return true;
        }

        @Override
        public boolean rename(File from, File to) {
            return true;
        }
    };

    /** The playback session as the job drives it. */
    private static final class SessionAdapter implements ExportJob.Session {
        private final PlaybackSession s;
        private final boolean paced;

        SessionAdapter(PlaybackSession s, boolean paced) {
            this.s = s;
            this.paced = paced;
        }

        @Override
        public boolean begin(ExportPlan plan) {
            return s.beginExport(plan, paced);
        }

        @Override
        public long frame() {
            return s.exportFrame();
        }

        @Override
        public boolean preroll() {
            return s.exportPreroll();
        }

        @Override
        public void advance() {
            s.advanceExportFrame();
        }

        @Override
        public void collectTail() {
            s.collectExportTail();
        }

        @Override
        public boolean tailCollected() {
            boolean t = s.exportTailCollected();
            Run x = run;
            if (t && x != null) {
                x.tailSeen = true;
            }
            return t;
        }

        @Override
        public boolean exporting() {
            return s.exporting();
        }

        @Override
        public void end() {
            s.endExport();
        }
    }

    /** The game around the job: sounds, the queue signal, the logs. */
    private static final class GameAdapter implements ExportJob.Game {
        private final Run x;

        GameAdapter(Run x) {
            this.x = x;
        }

        @Override
        public void stopSounds() {
            x.stopSounds++;
            Minecraft.getMinecraft().getSoundHandler().stopSounds();
            RewindExport.log(x, "rewind-export: stopSounds");
        }

        @Override
        public void soundTap(final ExportJob job) {
            if (job == null) {
                ExportSoundTap.game().stop();
                return;
            }
            ExportSoundTap.game().start(job.soundLog(), new ExportSoundTap.Context() {
                @Override
                public boolean discard() {
                    return job.discardSounds();
                }

                @Override
                public long eventTimeUs() {
                    return RewindPlayback.session().eventTimeUs();
                }

                @Override
                public double listenerX() {
                    Entity e = Minecraft.getMinecraft().getRenderViewEntity();
                    return e == null ? 0 : e.posX;
                }

                @Override
                public double listenerY() {
                    Entity e = Minecraft.getMinecraft().getRenderViewEntity();
                    return e == null ? 0 : e.posY + e.getEyeHeight();
                }

                @Override
                public double listenerZ() {
                    Entity e = Minecraft.getMinecraft().getRenderViewEntity();
                    return e == null ? 0 : e.posZ;
                }

                @Override
                public float listenerYaw() {
                    Entity e = Minecraft.getMinecraft().getRenderViewEntity();
                    return e == null ? 0 : e.rotationYaw;
                }
            });
        }

        @Override
        public Boolean queueReady() {
            Minecraft mc = Minecraft.getMinecraft();
            return VanillaChunkQueues.vanillaRenderer() ? VanillaChunkQueues.ready(mc) : null;
        }

        @Override
        public void log(String line) {
            RewindExport.log(x, line);
        }

        @Override
        public void frameTaken(long index, int draws, long waitedMs) {
            String line = frameLine(x, index, draws, waitedMs);
            if (x.sink != null) {
                try {
                    x.sink.log(line);
                } catch (Throwable ignored) {
                    // the sink's problem
                }
            }
            if (x.frameLines.size() < FRAME_LOG_LIMIT) {
                x.frameLines.add(line);
            }
        }
    }

    /** The spike's frame sink as an encoder: frames in order, no sound, done at once. */
    private static final class SinkEncoder implements ExportJob.Encoder {
        private final FrameSink sink;
        private final int w;
        private final int h;
        private long index;
        private boolean finished;

        SinkEncoder(FrameSink sink, int w, int h) {
            this.sink = sink;
            this.w = w;
            this.h = h;
        }

        @Override
        public Status status() {
            return finished ? Status.DONE : Status.READY;
        }

        @Override
        public String failure() {
            return null;
        }

        @Override
        public void frame(ByteBuffer bgra) {
            sink.frame(index++, bgra, w, h);
        }

        @Override
        public void sound(short[] interleaved, int frames) {
        }

        @Override
        public void finish() {
            finished = true;
        }

        @Override
        public void cancel() {
        }
    }

    private static String frameLine(Run x, long idx, int draws, long waited) {
        Minecraft mc = Minecraft.getMinecraft();
        PlaybackSession s = RewindPlayback.session();
        ViewState v = s.view();
        Entity e = mc.getRenderViewEntity();
        String pose = e == null ? "none" : hash(e.prevPosX + "," + e.prevPosY + "," + e.prevPosZ + "," + e.posX + ","
                + e.posY + "," + e.posZ + "," + e.prevRotationYaw + "," + e.rotationYaw + "," + e.prevRotationPitch
                + "," + e.rotationPitch);
        long run = s.ticks().ticksRun();
        int terrain = (x.chunkTicks.get((int) Math.min(Integer.MAX_VALUE - 1, run)) ? 1 : 0)
                | (x.blockTicks.get((int) Math.min(Integer.MAX_VALUE - 1, run)) ? 2 : 0);
        int[] q = VanillaChunkQueues.counts(mc);
        String hud = HudPlayback.debugState();
        StringBuilder b = new StringBuilder(220);
        b.append("{\"i\":").append(idx);
        b.append(",\"P\":").append(s.renderTimeUs());
        b.append(",\"ticks\":").append(run);
        b.append(",\"partial\":\"").append(String.format(Locale.ROOT, "%.6f", s.renderPartial())).append('"');
        b.append(",\"pose\":\"").append(pose).append('"');
        b.append(",\"hud\":\"").append(hash(hud)).append('"');
        if (idx == 0) {
            b.append(",\"keys\":\"").append(json(keysOnly(hud))).append('"');
        }
        b.append(",\"view\":\"").append(v.perspective()).append(v.hideGui() ? 'h' : '-')
                .append(v.showDebug() ? 'd' : '-').append(v.clean() ? 'c' : '-')
                .append(v.freeCamera() ? 'f' : '-').append('"');
        b.append(",\"screen\":").append(v.screenKind());
        b.append(",\"layers\":\"").append(layerCode(x.layers)).append('"');
        b.append(",\"terrain\":").append(terrain);
        b.append(",\"bound\":").append(x.boundAtReadBack);
        b.append(",\"glError\":").append(x.glError);
        b.append(",\"draws\":").append(draws);
        b.append(",\"waitMs\":").append(waited);
        b.append(",\"wallMs\":").append((System.nanoTime() - x.startNanos) / 1_000_000L);
        b.append(",\"queues\":").append(q == null ? "null" : "[" + q[0] + "," + q[1] + "," + q[2] + "," + q[3] + "]");
        b.append('}');
        return b.toString();
    }

    /** The Keystrokes part of the HUD digest (cap held and flash level per cap): counts only. */
    private static String keysOnly(String hud) {
        int i = hud == null ? -1 : hud.indexOf("keys");
        if (i < 0) {
            return "";
        }
        int end = hud.indexOf('|', i);
        return hud.substring(i, end < 0 ? Math.min(hud.length(), i + 120) : end);
    }

    private static String layerCode(Set<ExportLayers.Layer> l) {
        if (l == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (ExportLayers.Layer x : l) {
            b.append(x.name().charAt(0)).append(x.ordinal()).append(' ');
        }
        return b.toString().trim();
    }

    /** One running export. */
    private static final class Run {
        final ExportJob.Request req;
        final RenderRoute.Route route;
        final boolean chat;
        final Mode mode;
        final FrameSink sink;
        final ExportTarget target;
        final int scale;
        final int windowScale;
        final ExportRestore restore;
        final boolean clearWindow;
        final RewindExportScreen screen = new RewindExportScreen();
        final BitSet chunkTicks = new BitSet();
        final BitSet blockTicks = new BitSet();
        final List<String> frameLines = Collections.synchronizedList(new ArrayList<String>());
        final long startNanos = System.nanoTime();
        ExportFrames frames;
        ExportJob job;
        volatile String cancelAsked;
        volatile boolean drawing;
        Set<ExportLayers.Layer> layers = EnumSet.noneOf(ExportLayers.Layer.class);
        long passNanos;
        long frameIndex = -1;
        boolean boundAtReadBack;
        int glError;
        long bindingMismatches;
        long glErrorFrames;
        long rebinds;
        long draws;
        long drawNanos;
        long endNanos;
        int stopSounds;
        boolean tailSeen;
        String startup;

        Run(ExportJob.Request req, RenderRoute.Route route, boolean chat, Mode mode, FrameSink sink,
            ExportTarget target, int scale, int windowScale, ExportRestore restore, boolean clearWindow) {
            this.req = req;
            this.route = route;
            this.chat = chat;
            this.mode = mode;
            this.sink = sink;
            this.target = target;
            this.scale = scale;
            this.windowScale = windowScale;
            this.restore = restore;
            this.clearWindow = clearWindow;
            lastFrames = frameLines;
        }

        int width() {
            return req.width;
        }

        int height() {
            return req.height;
        }
    }
}
