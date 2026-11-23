package com.bedwarsqol.rewind.export;

import com.bedwarsqol.feature.DiagLog;
import com.bedwarsqol.rewind.watch.HudPlayback;
import com.bedwarsqol.rewind.watch.LoadedRecording;
import com.bedwarsqol.rewind.watch.NetCursor;
import com.bedwarsqol.rewind.watch.PlaybackSession;
import com.bedwarsqol.rewind.watch.RewindPlayback;
import com.bedwarsqol.rewind.watch.Timeline;
import com.bedwarsqol.rewind.watch.ViewState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Locale;

/**
 * Off-screen export frames at a fixed step (Rewind Export PLAN Solution 2; Phase 2 spike). {@link #capture} starts an
 * export of a playing recording through the session's fixed-step clock ({@link PlaybackSession#beginExport}); then,
 * once per pass of the game loop, the mixins call {@link #beforeRender}, {@link #afterMidFrameBind},
 * {@link #afterRender} and {@link #beforeDisplayUpdate}. Every frame of the export (seek, pre-roll, frames, tail) is
 * drawn into the target, never into the window; a frame the session marks as an export frame
 * ({@link PlaybackSession#exportFrame}) is read back, held by the settle rule if it must be, handed to the
 * {@link FrameSink} and advanced.
 *
 * <p>Only the OWN route is built in the spike (MAIN and WINDOW are refused). The first captured frame is the start-up
 * check ({@link RenderRoute#decide}); its decision is reported, not acted on, so the spike's pictures stay inspectable.
 * Game thread, except {@link #statusJson} and {@link #cancel}, which any thread may call.
 */
public final class RewindExport {

    /** How held frames settle. */
    public enum Settle {
        /** Every export frame is taken at its first draw (raw latency, no hold). */
        NONE,
        /** The best-effort rule: hold per packet class plus three stable draws ({@link SettleRule#bestEffort}). */
        RULE,
        /** The vanilla renderer's queues say ready, then three stable draws ({@link VanillaChunkQueues}). */
        READY,
        /** Every frame is drawn for {@link Options#fixedHoldMs} before it is taken (the K6/K10 reference). */
        FIXED
    }

    /** What to export and how. */
    public static final class Options {
        public RenderRoute.Route route = RenderRoute.Route.OWN;
        public int width = 1920;
        public int height = 1080;
        public Settle settle = Settle.RULE;
        public long fixedHoldMs = 2000;
        /** Holds after a tick with chunk data (0x21/0x26) or a block change (0x22/0x23); 0: stability only. */
        public long holdChunkMs = SettleRule.HOLD_CHUNK_MS;
        public long holdBlockMs = SettleRule.HOLD_BLOCK_MS;
        /** The window (and the main framebuffer) is cleared to black on every export frame (spike check K5). */
        public boolean clearWindow = true;
    }

    /** Packet classes held by the settle rule (bits). */
    static final int CHUNK = 1;
    static final int BLOCK = 2;

    private static volatile Job job;
    private static volatile String lastStatus = "{\"active\":false}";
    private static volatile String cancelAsked;

    private RewindExport() {
    }

    // ================================================================== entry

    /**
     * Starts exporting {@code plan} (client thread, while a recording plays and no export runs).
     *
     * @return null when started, else why not
     */
    public static String capture(ExportPlan plan, Options options, FrameSink sink) {
        if (job != null) {
            return "an export is running";
        }
        if (options == null || sink == null || plan == null) {
            return "no plan, options or sink";
        }
        if (options.route != RenderRoute.Route.OWN) {
            return "the spike builds only the OWN route";
        }
        if (options.width <= 0 || options.height <= 0 || (options.width & 1) != 0 || (options.height & 1) != 0) {
            return "size must be even and positive";
        }
        if (options.settle == Settle.READY && !VanillaChunkQueues.vanillaRenderer()) {
            return "no readiness signal with this renderer";
        }
        Minecraft mc = Minecraft.getMinecraft();
        PlaybackSession s = RewindPlayback.session();
        if (!RewindPlayback.active() || s.state() != PlaybackSession.State.WATCHING || s.exporting()) {
            return "no recording is being watched";
        }
        LoadedRecording rec = s.recording();
        Timeline tl = s.timeline();
        if (rec == null || tl == null) {
            return "no recording";
        }
        int windowScale = new ScaledResolution(mc).getScaleFactor();
        int scale = ExportGuiScale.of(windowScale, mc.displayHeight, options.height);
        int prevFb = ExportTarget.bound();
        ExportTarget target = ExportTarget.create(options.width, options.height);
        if (target == null) {
            return "no framebuffer objects";
        }
        ExportFrames.bindMain(mc);
        if (!s.beginExport(plan)) {
            target.delete();
            ExportFrames.bindMain(mc);
            return "the session refused the plan";
        }
        Job j = new Job(plan, options, sink, target, new ExportFrames(mc, target, scale, new RewindExportScreen()),
                new ExportFrames.Restore(mc));
        terrainTicks(rec, tl, j.chunkTicks, j.blockTicks);
        j.windowScale = windowScale;
        j.scale = scale;
        j.prevFramebuffer = prevFb;
        j.lastRun = -1;
        cancelAsked = null;
        job = j;
        log(j, "rewind-export: start " + options.width + "x" + options.height + " fps=" + plan.fps() + " frames="
                + plan.frames() + " route=" + options.route + " api=" + target.apiName() + " status=0x"
                + Integer.toHexString(target.status) + " guiScale=" + windowScale + "->" + scale + " settle="
                + options.settle + " holdChunkMs=" + options.holdChunkMs + " holdBlockMs=" + options.holdBlockMs
                + " chunkTicks=" + j.chunkTicks.cardinality() + " blockTicks=" + j.blockTicks.cardinality());
        publish(j);
        return null;
    }

    /** An export runs. */
    public static boolean active() {
        return job != null;
    }

    /** Asks the running export to stop at the next frame (any thread). */
    public static void cancel(String why) {
        cancelAsked = why == null ? "cancel" : why;
    }

    /** The running or last export's counts, as one JSON object (any thread). */
    public static String statusJson() {
        return lastStatus;
    }

    // ================================================================== hooks (MinecraftRewindExportMixin, EntityRendererRewindExportMixin)

    /** {@code runGameLoop}, right before {@code updateCameraAndRender}. */
    public static void beforeRender() {
        Job j = job;
        if (j == null) {
            return;
        }
        try {
            PlaybackSession s = RewindPlayback.session();
            if (cancelAsked != null) {
                finish(j, "cancelled-" + cancelAsked);
                return;
            }
            if (!s.exporting()) {
                finish(j, "session-ended");
                return;
            }
            j.passNanos = System.nanoTime();
            j.frames.begin(j.options.clearWindow);
        } catch (Throwable t) {
            finish(j, "failed-begin-" + t.getClass().getSimpleName());
        }
    }

    /** {@code updateCameraAndRender}, right after vanilla's mid-frame {@code getFramebuffer().bindFramebuffer(true)}. */
    public static void afterMidFrameBind() {
        Job j = job;
        if (j != null && j.frames.inFrame()) {
            try {
                j.frames.rebind();
                j.rebinds++;
            } catch (Throwable ignored) {
                // the read-back's binding check reports it
            }
        }
    }

    /** {@code renderWorld}'s chunk-upload deadline: far out on export frames. */
    public static long renderDeadline(long finishTimeNano) {
        Job j = job;
        return j != null && j.frames.inFrame() ? System.nanoTime() + 1_000_000_000L : finishTimeNano;
    }

    /** {@code runGameLoop}, right after {@code updateCameraAndRender} returned. */
    public static void afterRender() {
        Job j = job;
        if (j == null || !j.frames.inFrame()) {
            return;
        }
        PlaybackSession s = RewindPlayback.session();
        try {
            long idx = s.exportFrame();
            if (idx >= 0) {
                j.onDraw(s, idx, j.frames.readBack(), System.nanoTime());
            }
        } catch (Throwable t) {
            j.failure = "failed-frame-" + t.getClass().getSimpleName();
        } finally {
            try {
                j.frames.end();
            } catch (Throwable ignored) {
                // finish binds the main framebuffer again
            }
        }
        if (j.failure != null) {
            finish(j, j.failure);
        } else if (s.exportTailCollected()) {
            finish(j, "done");
        } else {
            publish(j);
        }
    }

    /** {@code Minecraft.updateDisplay} HEAD: the window shows nothing of the export (spike: black). */
    public static void beforeDisplayUpdate() {
        Job j = job;
        if (j != null && j.options.clearWindow) {
            try {
                ExportFrames.clearWindow(Minecraft.getMinecraft());
            } catch (Throwable ignored) {
                // a frame of the window only
            }
        }
    }

    // ================================================================== job

    private static void finish(Job j, String outcome) {
        if (job != j) {
            return;
        }
        job = null;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            j.frames.end();
        } catch (Throwable ignored) {
            // below
        }
        try {
            RewindPlayback.session().endExport();
        } catch (Throwable ignored) {
            // the session handles its own state
        }
        try {
            j.target.delete();
            ExportFrames.bindMain(mc);
        } catch (Throwable ignored) {
            // GL objects only
        }
        j.restore.restore();
        j.outcome = outcome;
        j.endNanos = System.nanoTime();
        log(j, "rewind-export: " + outcome + " frames=" + j.taken + "/" + j.plan.frames() + " held=" + j.heldFrames
                + " heldDraws=" + j.heldDraws + " settleCapped=" + j.capped + " rule=" + ruleName(j.options.settle)
                + " meanFrameMs=" + fmt(j.meanFrameMs()) + " startup=" + j.startup);
        publish(j);
    }

    private static String ruleName(Settle s) {
        return s == Settle.READY ? "ready-signal" : s == Settle.RULE ? "best-effort" : s.name().toLowerCase(Locale.ROOT);
    }

    private static void log(Job j, String line) {
        DiagLog.log(line);
        try {
            j.sink.log(line);
        } catch (Throwable ignored) {
            // the sink's problem
        }
    }

    private static void publish(Job j) {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"active\":").append(job == j);
        b.append(",\"outcome\":").append(j.outcome == null ? "null" : "\"" + j.outcome + "\"");
        b.append(",\"frames\":").append(j.plan.frames()).append(",\"taken\":").append(j.taken);
        b.append(",\"width\":").append(j.options.width).append(",\"height\":").append(j.options.height);
        b.append(",\"windowScale\":").append(j.windowScale).append(",\"scale\":").append(j.scale);
        b.append(",\"settle\":\"").append(ruleName(j.options.settle)).append('"');
        b.append(",\"heldFrames\":").append(j.heldFrames).append(",\"heldDraws\":").append(j.heldDraws);
        b.append(",\"settleCapped\":").append(j.capped).append(",\"maxWaitMs\":").append(j.maxWaitMs);
        b.append(",\"bindingMismatches\":").append(j.bindingMismatches);
        b.append(",\"glErrorFrames\":").append(j.glErrorFrames).append(",\"rebinds\":").append(j.rebinds);
        b.append(",\"startup\":").append(j.startup == null ? "null" : "\"" + j.startup + "\"");
        b.append(",\"meanFrameMs\":").append(fmt(j.meanFrameMs()));
        b.append(",\"meanDrawMs\":").append(fmt(j.draws == 0 ? 0 : j.drawNanos / 1e6 / j.draws));
        b.append(",\"draws\":").append(j.draws);
        b.append(",\"chunkTicks\":").append(j.chunkTicks.cardinality());
        b.append(",\"blockTicks\":").append(j.blockTicks.cardinality());
        b.append(",\"api\":\"").append(j.target.apiName()).append('"');
        b.append('}');
        lastStatus = b.toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /**
     * The ticks (as ticks-run counts after them) whose records hold chunk data (0x21 S21, 0x26 S26) or block changes
     * (0x22 S22, 0x23 S23): a NET record stamped at t is fed by the first tick k with T[k] >= t (TickFeeder), so it has
     * been applied once k + 1 ticks have run.
     */
    static void terrainTicks(LoadedRecording rec, Timeline tl, BitSet chunk, BitSet block) {
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

    /** One running export. */
    private static final class Job {
        final ExportPlan plan;
        final Options options;
        final FrameSink sink;
        final ExportTarget target;
        final ExportFrames frames;
        final ExportFrames.Restore restore;
        final BitSet chunkTicks = new BitSet();
        final BitSet blockTicks = new BitSet();
        int windowScale;
        int scale;
        int prevFramebuffer;

        long passNanos;
        long offered = -1;
        long frameNanos;
        long lastRun;
        int drawsThisFrame;
        SettleRule rule;
        int ruleClasses;

        long taken;
        long heldFrames;
        long heldDraws;
        long capped;
        long maxWaitMs;
        long bindingMismatches;
        long glErrorFrames;
        long rebinds;
        long draws;
        long drawNanos;
        long firstTakenNanos;
        long lastTakenNanos;
        long endNanos;
        String startup;
        String failure;
        String outcome;

        Job(ExportPlan plan, Options options, FrameSink sink, ExportTarget target, ExportFrames frames,
            ExportFrames.Restore restore) {
            this.plan = plan;
            this.options = options;
            this.sink = sink;
            this.target = target;
            this.frames = frames;
            this.restore = restore;
        }

        double meanFrameMs() {
            return taken < 2 ? 0 : (lastTakenNanos - firstTakenNanos) / 1e6 / (taken - 1);
        }

        void onDraw(PlaybackSession s, long idx, ByteBuffer px, long now) {
            Minecraft mc = Minecraft.getMinecraft();
            draws++;
            drawNanos += now - passNanos;
            if (idx != offered) {
                offered = idx;
                frameNanos = passNanos;
                drawsThisFrame = 0;
                long run = s.ticks().ticksRun();
                int classes = 0;
                for (long t = Math.max(lastRun + 1, 0); t <= run && lastRun >= 0; t++) {
                    if (chunkTicks.get((int) t)) classes |= CHUNK;
                    if (blockTicks.get((int) t)) classes |= BLOCK;
                }
                lastRun = run;
                ruleClasses = classes;
                rule = makeRule(idx, classes, passNanos / 1_000_000L, mc);
            }
            drawsThisFrame++;
            boolean take;
            String settled;
            long nowMs = now / 1_000_000L;
            if (options.settle == Settle.FIXED) {
                take = now - frameNanos >= options.fixedHoldMs * 1_000_000L;
                settled = "fixed";
            } else if (rule == null) {
                take = true;
                settled = "none";
            } else {
                Boolean ready = options.settle == Settle.READY ? VanillaChunkQueues.ready(mc) : Boolean.TRUE;
                SettleRule.State st = rule.offer(SettleRule.grid(px, target.width, target.height), nowMs,
                        Boolean.TRUE.equals(ready));
                take = st != SettleRule.State.SETTLING;
                settled = st == SettleRule.State.CAPPED ? "capped" : "settled";
                if (st == SettleRule.State.CAPPED) capped++;
            }
            if (!take) {
                return;
            }
            boolean bound = frames.boundAtReadBack == target.id();
            if (!bound) bindingMismatches++;
            if (frames.glError != 0) glErrorFrames++;
            if (startup == null) {
                RenderRoute.Check c = new RenderRoute.Check();
                c.complete = target.complete();
                c.boundAtReadBack = bound;
                c.glError = frames.glError;
                c.uniform = RenderRoute.uniform(px, target.width, target.height);
                startup = RenderRoute.decide(options.route, c).toString();
            }
            if (drawsThisFrame > 1) {
                heldFrames++;
                heldDraws += drawsThisFrame - 1;
            }
            long waited = (now - frameNanos) / 1_000_000L;
            maxWaitMs = Math.max(maxWaitMs, drawsThisFrame > 1 ? waited : 0);
            sink.frame(idx, px, target.width, target.height);
            sink.log(frameLine(s, mc, idx, bound, settled, waited));
            if (taken == 0) firstTakenNanos = now;
            lastTakenNanos = now;
            taken++;
            s.advanceExportFrame();
            if (idx + 1 >= plan.frames()) {
                s.collectExportTail();
            }
        }

        private SettleRule makeRule(long idx, int classes, long startMs, Minecraft mc) {
            switch (options.settle) {
                case RULE:
                    if (idx == 0) {
                        return SettleRule.bestEffort(Math.max(options.holdChunkMs, options.holdBlockMs), startMs);
                    }
                    if (classes == 0) {
                        return null;
                    }
                    long hold = Math.max((classes & CHUNK) != 0 ? options.holdChunkMs : 0,
                            (classes & BLOCK) != 0 ? options.holdBlockMs : 0);
                    return SettleRule.bestEffort(hold, startMs);
                case READY:
                    if (idx == 0 || classes != 0 || !Boolean.TRUE.equals(VanillaChunkQueues.ready(mc))) {
                        return SettleRule.readySignal(startMs);
                    }
                    return null;
                default:
                    return null;
            }
        }

        private String frameLine(PlaybackSession s, Minecraft mc, long idx, boolean bound, String settled, long waited) {
            ViewState v = s.view();
            Entity e = mc.getRenderViewEntity();
            String pose = e == null ? "none" : hash(e.prevPosX + "," + e.prevPosY + "," + e.prevPosZ + "," + e.posX + ","
                    + e.posY + "," + e.posZ + "," + e.prevRotationYaw + "," + e.rotationYaw + "," + e.prevRotationPitch
                    + "," + e.rotationPitch);
            int[] q = VanillaChunkQueues.counts(mc);
            StringBuilder b = new StringBuilder(200);
            b.append("{\"i\":").append(idx);
            b.append(",\"P\":").append(s.renderTimeUs());
            b.append(",\"ticks\":").append(s.ticks().ticksRun());
            b.append(",\"partial\":\"").append(String.format(Locale.ROOT, "%.6f", s.renderPartial())).append('"');
            b.append(",\"pose\":\"").append(pose).append('"');
            b.append(",\"hud\":\"").append(hash(HudPlayback.debugState())).append('"');
            b.append(",\"view\":\"").append(v.perspective()).append(v.hideGui() ? 'h' : '-')
                    .append(v.showDebug() ? 'd' : '-').append(v.clean() ? 'c' : '-')
                    .append(v.freeCamera() ? 'f' : '-').append('"');
            b.append(",\"screen\":").append(v.screenKind());
            b.append(",\"terrain\":").append(ruleClasses);
            b.append(",\"bound\":").append(bound);
            b.append(",\"glError\":").append(frames.glError);
            b.append(",\"draws\":").append(drawsThisFrame);
            b.append(",\"settle\":\"").append(settled).append('"');
            b.append(",\"waitMs\":").append(waited);
            b.append(",\"queues\":").append(q == null ? "null"
                    : "[" + q[0] + "," + q[1] + "," + q[2] + "," + q[3] + "]");
            b.append('}');
            return b.toString();
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
}
