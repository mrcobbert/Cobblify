package cobdev.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step RWX's probes (Rewind Export PLAN Phase 2, the off-screen spike; {@link Policy#REWIND_EXPORT_PROBES}). Cobblify is
 * reached by reflection on its own names ({@code com.bedwarsqol.rewind.export.RewindExport} and friends), so the same
 * bridge works in dev and prod mode; the frame sink is a {@link Proxy} of Cobblify's {@code FrameSink}. Pictures are
 * written by the game into {@link Policy#EXPORT_ROOT}{@code /RUN} only (folders 0777 so the agent can read and delete
 * them) and compared in the game, so nothing but counts, means and boxes leaves it. Only a recording of cobdev's own
 * Rewind folder can be exported, and never on a server.
 */
final class RewindExportProbes {

    static final String EXPORT = "com.bedwarsqol.rewind.export.RewindExport";
    static final String OPTIONS = EXPORT + "$Options";
    static final String MODE = EXPORT + "$Mode";
    static final String REQUEST = EXPORT + "$Request";
    static final String SETTINGS = "com.bedwarsqol.rewind.export.ExportSettings";
    static final String PLAN = "com.bedwarsqol.rewind.export.ExportPlan";
    static final String SINK = "com.bedwarsqol.rewind.export.FrameSink";
    static final String ROUTE = "com.bedwarsqol.rewind.export.RenderRoute$Route";
    static final String SCREEN = "com.bedwarsqol.rewind.export.RewindExportScreen";

    /** PNG writer queue: at most this many frames wait (the game thread blocks beyond it). */
    private static final int WRITE_QUEUE = 12;

    private static volatile Sink sink;
    private static final Object GRID_LOCK = new Object();
    private static long gridUntil;
    private static List<long[]> gridLog = new ArrayList<>();
    private static List<float[]> gridFull = new ArrayList<>();
    private static Object liveSound;
    private static float[] gridLast;
    private static long gridStart;
    private static Thread gridDriver;
    private static ByteBuffer gridPixels;
    private static volatile boolean tickHook;
    private static final List<long[]> TICKS = new ArrayList<>();
    private static long lastTicksRun = -1;
    private static GuiScreen savedScreen;

    private RewindExportProbes() {
    }

    static Object run(String name, List<String> args) throws Exception {
        switch (name) {
            case "rewind-export-status":
                return status();
            case "rewind-export-cancel":
                RewindWatchProbes.call(RewindWatchProbes.cls(EXPORT), "cancel", "probe");
                return Bridge.ok();
            case "rewind-export-compare":
                return compare(args);
            case "rewind-export-stats":
                return stats(args);
            case "rewind-export-env":
                return env();
            case "rewind-export-state":
                return state(args);
            case "rewind-export-region":
                return region(args);
            case "rewind-export-k11":
                return k11(args);
            default:
                break;
        }
        return Bridge.onMain(() -> {
            Bridge.requireLocal();
            switch (name) {
                case "rewind-export-spike":
                    return spike(args);
                case "rewind-export-screen":
                    return screen(args);
                case "rewind-export-gl":
                    return gl();
                case "rewind-export-shot":
                    return shot(args);
                case "rewind-export-grid":
                    return grid(args);
                case "rewind-export-terrain":
                    return terrain();
                case "rewind-export-clean":
                    return clean();
                case "rewind-export-start":
                    return start(args);
                case "rewind-export-live-sound":
                    return liveSound(args);
                case "rewind-export-chat-type":
                    return chatType(args);
                default:
                    throw new Refused("unknown probe " + name);
            }
        }, 30_000);
    }

    // ------------------------------------------------------------------ spike

    private static Map<String, Object> spike(List<String> args) throws Exception {
        Object[] a = Policy.exportSpike(args);
        Object session = playing();
        ownFolder();
        Object timeline = RewindWatchProbes.call(session, "timeline");
        long startTick = ((Number) RewindWatchProbes.call(timeline, "startTick")).longValue();
        long base = ((Number) RewindWatchProbes.call(timeline, "timeOfTick", startTick)).longValue();
        long startUs = base + (Long) a[11];
        int fps = (Integer) a[3];
        int frames = (Integer) a[4];
        long endUs = startUs + (frames * 1_000_000L + fps - 1) / fps; // exactly `frames` frames
        Object plan = RewindWatchProbes.cls(PLAN).getConstructor(long.class, long.class, int.class)
                .newInstance(startUs, endUs, fps);
        Object o = RewindWatchProbes.cls(OPTIONS).getConstructor().newInstance();
        setField(o, "route", enumValue(RewindWatchProbes.cls(ROUTE), ((String) a[0]).toUpperCase()));
        setField(o, "width", a[1]);
        setField(o, "height", a[2]);
        String settle = (String) a[5]; // Revision 4: no per-class holds; the hold arguments are ignored
        setField(o, "mode", enumValue(RewindWatchProbes.cls(MODE), "none".equals(settle) ? "RAW"
                : "fixed".equals(settle) ? "FIXED" : "PRODUCT"));
        setField(o, "chat", a[10]);
        String run = (String) a[9];
        File dir = "-".equals(run) ? null : new File(Policy.exportFolder(run));
        if (dir != null) prepare(dir);
        // K11's export runs keep 64x36 luma grids only: writing PNGs would slow the export below its real speed and
        // give terrain more time than an export has (PLAN Revision 4.1, check K11)
        Sink s = new Sink(dir, run.startsWith("k11-x-"));
        Object proxy = Proxy.newProxyInstance(RewindExportProbes.class.getClassLoader(),
                new Class<?>[]{RewindWatchProbes.cls(SINK)}, (p, m, x) -> {
                    if ("frame".equals(m.getName())) s.frame(((Number) x[0]).longValue(), (ByteBuffer) x[1],
                            (Integer) x[2], (Integer) x[3]);
                    else if ("log".equals(m.getName())) s.log((String) x[0]);
                    return null;
                });
        Object why = RewindWatchProbes.call(RewindWatchProbes.cls(EXPORT), "capture", plan, o, proxy);
        if (why != null) throw new Refused("capture refused: " + why);
        sink = s;
        Map<String, Object> out = Bridge.ok();
        out.put("frames", RewindWatchProbes.call(plan, "frames"));
        out.put("startUs", startUs - base);
        return out;
    }

    private static Map<String, Object> status() throws Exception {
        Map<String, Object> out = Bridge.ok();
        String json = (String) RewindWatchProbes.call(RewindWatchProbes.cls(EXPORT), "statusJson");
        out.put("export", new com.google.gson.JsonParser().parse(json));
        Sink s = sink;
        if (s != null) {
            out.put("written", s.written.get());
            out.put("queued", s.queue.size());
            out.put("lines", s.lines.size());
            out.put("writeErrors", s.errors.get());
            boolean finished = json.contains("\"active\":false");
            if (finished && s.queue.isEmpty() && s.idle) {
                out.put("saved", s.save(json));
            }
        }
        return out;
    }

    /** Writes PNGs on its own thread; keeps the log lines for {@code frames.json}. */
    private static final class Sink {
        final File dir;
        final BlockingQueue<Object[]> queue = new ArrayBlockingQueue<>(WRITE_QUEUE);
        final List<String> lines = new ArrayList<>();
        final AtomicInteger written = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        volatile boolean idle = true;
        boolean saved;
        final boolean gridsOnly;
        final List<byte[]> grids = new ArrayList<>();

        Sink(File dir, boolean gridsOnly) {
            this.dir = dir;
            this.gridsOnly = gridsOnly;
            if (dir == null || gridsOnly) return;
            Thread t = new Thread(this::drain, "cobdev-export-writer");
            t.setDaemon(true);
            t.start();
        }

        void frame(long index, ByteBuffer px, int w, int h) {
            if (dir == null) return; // timing run: discarded
            if (gridsOnly) {
                float[] g = cellGridBgra(px, w, h);
                byte[] b = new byte[g.length];
                for (int i = 0; i < g.length; i++) b[i] = (byte) Math.round(Math.max(0, Math.min(255, g[i])));
                synchronized (grids) {
                    grids.add(b);
                }
                written.incrementAndGet();
                return;
            }
            int[] copy = new int[w * h];
            px.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(copy);
            try {
                queue.put(new Object[]{index, w, h, copy});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized void log(String line) {
            lines.add(line);
        }

        private void drain() {
            while (true) {
                try {
                    Object[] f = queue.poll(60, TimeUnit.SECONDS);
                    if (f == null) {
                        if (sink != this) return;
                        continue;
                    }
                    idle = false;
                    write(new File(dir, String.format("frame-%05d.png", (Long) f[0])), (Integer) f[1], (Integer) f[2],
                            (int[]) f[3]);
                    written.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    idle = queue.isEmpty();
                }
            }
        }

        synchronized boolean save(String status) throws Exception {
            if (dir == null) return true; // a timing run: nothing to save
            if (saved) return true;
            StringBuilder b = new StringBuilder("{\"status\":").append(status).append(",\"lines\":[");
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                b.append(i == 0 ? "" : ",").append(l.startsWith("{") ? l : new com.google.gson.Gson().toJson(l));
            }
            b.append("]}");
            File f = new File(dir, "frames.json");
            if (gridsOnly) {
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                synchronized (grids) {
                    for (byte[] g : grids) all.write(g, 0, g.length);
                }
                Files.write(new File(dir, "grids.bin").toPath(), all.toByteArray());
            }
            Files.write(f.toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
            saved = true;
            return true;
        }
    }

    // ------------------------------------------------------------------ screens, state, shots

    /** {@code rewind-export-screen export|viewer}: the export screen as the real screen, or the viewer back. */
    private static Map<String, Object> screen(List<String> args) throws Exception {
        if (args.size() != 1 || !("export".equals(args.get(0)) || "viewer".equals(args.get(0)))) {
            throw new Refused("probe rewind-export-screen export|viewer");
        }
        Object session = playing();
        Minecraft mc = Minecraft.getMinecraft();
        Class<?> pb = RewindWatchProbes.cls(RewindWatchProbes.PLAYBACK);
        GuiScreen s;
        if ("export".equals(args.get(0))) {
            savedScreen = mc.currentScreen;
            s = (GuiScreen) RewindWatchProbes.cls(SCREEN).getConstructor().newInstance();
        } else {
            Object screens = RewindWatchProbes.call(pb, "screens");
            s = (GuiScreen) RewindWatchProbes.call(screens, "viewer", session);
        }
        RewindWatchProbes.call(pb, "display", s);
        Map<String, Object> out = Bridge.ok();
        out.put("screen", mc.currentScreen == null ? null : mc.currentScreen.getClass().getSimpleName());
        return out;
    }

    /** {@code rewind-export-gl}: what an export must put back (K8). */
    private static Map<String, Object> gl() throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        Map<String, Object> out = Bridge.ok();
        out.put("displayWidth", mc.displayWidth);
        out.put("displayHeight", mc.displayHeight);
        out.put("window", org.lwjgl.opengl.Display.getWidth() + "x" + org.lwjgl.opengl.Display.getHeight());
        out.put("guiScale", mc.gameSettings.guiScale);
        out.put("scaleFactor", new ScaledResolution(mc).getScaleFactor());
        out.put("framebuffer", GL11.glGetInteger(0x8CA6));
        Framebuffer fb = mc.getFramebuffer();
        out.put("mainFramebufferSize", fb.framebufferWidth + "x" + fb.framebufferHeight);
        IntBuffer vp = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, vp);
        out.put("viewport", vp.get(0) + "," + vp.get(1) + "," + vp.get(2) + "," + vp.get(3));
        out.put("vsync", mc.gameSettings.enableVsync);
        out.put("limitFramerate", mc.gameSettings.limitFramerate);
        out.put("motionBlurSuppressed", RewindWatchProbes.call(RewindWatchProbes.cls("com.bedwarsqol.feature.MotionBlur"),
                "suppressed"));
        out.put("exportActive", RewindWatchProbes.call(RewindWatchProbes.cls(EXPORT), "active"));
        out.put("screen", mc.currentScreen == null ? null : mc.currentScreen.getClass().getSimpleName());
        out.put("fps", Minecraft.getDebugFPS());
        return out;
    }

    /** {@code rewind-export-shot RUN NAME}: the main framebuffer (the last whole frame) as RUN/NAME.png. */
    private static Map<String, Object> shot(List<String> args) throws Exception {
        if (args.size() != 2) throw new Refused("probe rewind-export-shot RUN NAME");
        File dir = new File(Policy.exportFolder(args.get(0)));
        Refused.check(Policy.exportName(args.get(1)));
        prepare(dir);
        Object[] f = Bridge.readFrame();
        int w = (Integer) f[0], h = (Integer) f[1];
        int[] px = (int[]) f[2];
        write(new File(dir, args.get(1) + ".png"), w, h, px);
        long max = 0, sum = 0;
        for (int p : px) {
            long l = luma(p);
            max = Math.max(max, l);
            sum += l;
        }
        Map<String, Object> out = Bridge.ok();
        out.put("width", w);
        out.put("height", h);
        out.put("maxLuma", max);
        out.put("meanLuma", sum / (double) px.length);
        return out;
    }

    private static Map<String, Object> clean() throws Exception {
        playing();
        Object view = RewindWatchProbes.call(RewindWatchProbes.cls(RewindWatchProbes.PLAYBACK), "view");
        RewindWatchProbes.call(view, "toggleClean");
        RewindWatchProbes.call(RewindWatchProbes.cls(RewindWatchProbes.PLAYBACK), "viewStateChanged");
        Map<String, Object> out = Bridge.ok();
        out.put("clean", RewindWatchProbes.call(view, "clean"));
        return out;
    }

    // ------------------------------------------------------------------ pictures (any thread)

    /**
     * {@code rewind-export-compare RUN_A NAME_A RUN_B NAME_B}: mean absolute RGB difference (0-255) of two pictures of
     * the same size, its max, the box of pixels differing by more than 24, and the box of those in the bottom 15%.
     * NAME {@code *} on both sides: every {@code frame-*.png} the two runs share, one result each.
     */
    private static Map<String, Object> compare(List<String> args) throws Exception {
        if (args.size() != 4) throw new Refused("probe rewind-export-compare RUN_A NAME_A RUN_B NAME_B");
        File a = new File(Policy.exportFolder(args.get(0)));
        File b = new File(Policy.exportFolder(args.get(2)));
        Map<String, Object> out = Bridge.ok();
        if ("*".equals(args.get(1)) && "*".equals(args.get(3))) {
            List<Object> each = new ArrayList<>();
            String[] names = a.list();
            if (names == null) throw new Refused("no run " + args.get(0));
            Arrays.sort(names);
            double worst = 0;
            for (String n : names) {
                if (!n.matches("frame-\\d{5}\\.png") || !new File(b, n).isFile()) continue;
                Map<String, Object> r = diff(new File(a, n), new File(b, n));
                r.put("name", n.substring(0, n.length() - 4));
                worst = Math.max(worst, (Double) r.get("mean"));
                each.add(r);
            }
            out.put("pairs", each.size());
            out.put("worstMean", worst);
            out.put("each", each);
            return out;
        }
        Refused.check(Policy.exportName(args.get(1)));
        Refused.check(Policy.exportName(args.get(3)));
        out.putAll(diff(new File(a, args.get(1) + ".png"), new File(b, args.get(3) + ".png")));
        return out;
    }

    private static Map<String, Object> diff(File fa, File fb) throws Exception {
        BufferedImage a = ImageIO.read(fa), b = ImageIO.read(fb);
        if (a == null || b == null) throw new Refused("missing picture " + (a == null ? fa.getName() : fb.getName()));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("size", a.getWidth() + "x" + a.getHeight());
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            out.put("sameSize", false);
            out.put("mean", 255.0);
            return out;
        }
        int w = a.getWidth(), h = a.getHeight();
        int[] pa = a.getRGB(0, 0, w, h, null, 0, w), pb = b.getRGB(0, 0, w, h, null, 0, w);
        long sum = 0;
        int max = 0;
        int[] box = {w, h, -1, -1}, bottom = {w, h, -1, -1};
        long differing = 0, differingTop = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = pa[y * w + x], q = pb[y * w + x];
                int d = Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255))
                        + Math.abs((p & 255) - (q & 255));
                sum += d;
                int m = Math.max(Math.abs((p >> 16 & 255) - (q >> 16 & 255)), Math.max(Math.abs((p >> 8 & 255)
                        - (q >> 8 & 255)), Math.abs((p & 255) - (q & 255))));
                max = Math.max(max, m);
                if (m > 24) {
                    differing++;
                    grow(box, x, y);
                    if (y >= h * 85 / 100) grow(bottom, x, y);
                    if (y < h * 30 / 100) differingTop++;
                }
            }
        }
        out.put("sameSize", true);
        out.put("mean", sum / (3.0 * w * h));
        out.put("max", max);
        out.put("differing", differing);
        out.put("differingTop30", differingTop);
        out.put("box", box[2] < 0 ? null : Arrays.asList(box[0], box[1], box[2], box[3]));
        // K4: in the bottom 4% of rows, the run of columns with a pixel differing by more than 64 that holds the
        // centre column (the hotbar touches the bottom edge, centred; 64 is above the vignette's difference, which
        // the clean view also drops: at 48 it joined the hotbar to the hand once), and that run's top and bottom row.
        int band = h - Math.max(1, h * 4 / 100);
        boolean[] col = new boolean[w];
        for (int y = band; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = pa[y * w + x], q = pb[y * w + x];
                if (Math.max(Math.abs((p >> 16 & 255) - (q >> 16 & 255)), Math.max(Math.abs((p >> 8 & 255)
                        - (q >> 8 & 255)), Math.abs((p & 255) - (q & 255)))) > 64) col[x] = true;
            }
        }
        if (col[w / 2]) {
            int l = w / 2, r = w / 2;
            while (l > 0 && col[l - 1]) l--;
            while (r < w - 1 && col[r + 1]) r++;
            int top = h, bot = -1;
            for (int y = 0; y < h; y++) {
                for (int x = l; x <= r; x++) {
                    int p = pa[y * w + x], q = pb[y * w + x];
                    if (Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p >> 16 & 255) - (q >> 16 & 255))
                            + Math.abs((p & 255) - (q & 255)) > 24 && y >= h * 70 / 100) {
                        top = Math.min(top, y);
                        bot = Math.max(bot, y);
                    }
                }
            }
            out.put("centreRun", Arrays.asList(l, r, top, bot));
        } else {
            out.put("centreRun", null);
        }
        out.put("bottomBox", bottom[2] < 0 ? null : Arrays.asList(bottom[0], bottom[1], bottom[2], bottom[3]));
        return out;
    }

    private static void grow(int[] box, int x, int y) {
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.max(box[2], x);
        box[3] = Math.max(box[3], y);
    }

    /** {@code rewind-export-stats RUN}: per frame, its size, luma variance and mean difference to the frame before. */
    private static Map<String, Object> stats(List<String> args) throws Exception {
        if (args.size() != 1) throw new Refused("probe rewind-export-stats RUN");
        File dir = new File(Policy.exportFolder(args.get(0)));
        String[] names = dir.list();
        if (names == null) throw new Refused("no run " + args.get(0));
        Arrays.sort(names);
        List<Object> each = new ArrayList<>();
        int[] prev = null;
        for (String n : names) {
            if (!n.matches("frame-\\d{5}\\.png")) continue;
            BufferedImage img = ImageIO.read(new File(dir, n));
            int w = img.getWidth(), h = img.getHeight();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            double mean = 0, sq = 0;
            for (int p : px) {
                double l = luma(p);
                mean += l;
                sq += l * l;
            }
            mean /= px.length;
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("name", n.substring(0, n.length() - 4));
            r.put("size", w + "x" + h);
            r.put("variance", sq / px.length - mean * mean);
            if (prev != null && prev.length == px.length) {
                long d = 0;
                for (int i = 0; i < px.length; i++) d += Math.abs(luma(px[i]) - luma(prev[i]));
                r.put("diffPrev", d / (double) px.length);
            }
            prev = px;
            each.add(r);
        }
        Map<String, Object> out = Bridge.ok();
        out.put("frames", each.size());
        out.put("each", each);
        return out;
    }

    private static long luma(int p) {
        return (2126L * (p >> 16 & 255) + 7152L * (p >> 8 & 255) + 722L * (p & 255)) / 10000L;
    }

    private static void write(File f, int w, int h, int[] bgraBottomUp) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < h; row++) img.setRGB(0, h - 1 - row, w, 1, bgraBottomUp, row * w, w);
        File tmp = new File(f.getPath() + ".tmp");
        ImageIO.write(img, "png", tmp);
        Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void prepare(File dir) throws Exception {
        File root = new File(Policy.EXPORT_ROOT);
        if (!root.isDirectory() && !root.mkdirs()) throw new Refused("cannot create " + Policy.EXPORT_ROOT);
        openPerms(root);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Refused("cannot create the run folder");
        openPerms(dir);
    }

    private static void openPerms(File d) {
        try {
            Files.setPosixFilePermissions(d.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (Exception ignored) {
            // not ours: cobdev stage made it world-writable already
        }
    }

    // ------------------------------------------------------------------ K10: latency per packet class

    /**
     * {@code rewind-export-grid watch}: from now, once per pass of the game loop (a frame driver keeps one task
     * queued), the main framebuffer's 64x36 luma grid against the previous one: [ms since watch, mean difference x
     * 1000]; and every tick end where the ticks run changed: [ms, ticksRun]. At most 30 s. {@code log}: stops and
     * returns both.
     */
    private static Map<String, Object> grid(List<String> args) throws Exception {
        if (args.size() == 2 && "cells".equals(args.get(0))) {
            return cells(Long.parseLong(args.get(1)));
        }
        if (args.size() != 1 || !("watch".equals(args.get(0)) || "log".equals(args.get(0)))) {
            throw new Refused("probe rewind-export-grid watch|log|cells TICK");
        }
        registerTickHook();
        Map<String, Object> out = Bridge.ok();
        synchronized (GRID_LOCK) {
            if ("watch".equals(args.get(0))) {
                gridLog = new ArrayList<>();
                gridFull = new ArrayList<>();
                gridLast = null;
                gridStart = System.nanoTime();
                gridUntil = gridStart + 30_000_000_000L;
                synchronized (TICKS) {
                    TICKS.clear();
                }
                startGridDriver();
                out.put("watching", true);
                return out;
            }
            gridUntil = 0;
            List<Object> frames = new ArrayList<>();
            for (long[] e : gridLog) frames.add(Arrays.asList(e[0], e[1]));
            List<Object> ticks = new ArrayList<>();
            synchronized (TICKS) {
                for (long[] e : TICKS) ticks.add(Arrays.asList((e[0] - gridStart) / 1_000_000L, e[1]));
            }
            out.put("frames", frames);
            out.put("ticks", ticks);
            return out;
        }
    }

    private static void startGridDriver() {
        if (gridDriver != null && gridDriver.isAlive()) return;
        gridDriver = new Thread(() -> {
            while (true) {
                synchronized (GRID_LOCK) {
                    if (System.nanoTime() > gridUntil) {
                        gridDriver = null;
                        return;
                    }
                }
                try {
                    Future<Object> f = Minecraft.getMinecraft().addScheduledTask(() -> {
                        gridPass();
                        return null;
                    });
                    f.get(2, TimeUnit.SECONDS);
                } catch (Exception e) {
                    synchronized (GRID_LOCK) {
                        gridUntil = 0;
                    }
                }
            }
        }, "cobdev-export-grid");
        gridDriver.setDaemon(true);
        gridDriver.start();
    }

    /** Game thread, between frames: the main framebuffer holds the whole previous frame. */
    private static void gridPass() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!OpenGlHelper.isFramebufferEnabled()) return;
        Framebuffer fb = mc.getFramebuffer();
        int w = fb.framebufferWidth, h = fb.framebufferHeight;
        if (gridPixels == null || gridPixels.capacity() < w * h * 4) gridPixels = BufferUtils.createByteBuffer(w * h * 4);
        gridPixels.clear();
        fb.bindFramebuffer(false);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, gridPixels);
        fb.unbindFramebuffer();
        float[] g = lumaGrid(gridPixels, w, h);
        long now = System.nanoTime();
        synchronized (GRID_LOCK) {
            double d = gridLast == null ? 0 : meanDiff(gridLast, g);
            gridLast = g;
            gridLog.add(new long[]{(now - gridStart) / 1_000_000L, Math.round(d * 1000)});
            if (gridFull.size() < 4000) gridFull.add(g);
        }
    }

    private static float[] lumaGrid(ByteBuffer px, int w, int h) {
        float[] sum = new float[64 * 36];
        int[] n = new int[64 * 36];
        for (int y = 0; y < h; y += 2) {
            int gy = y * 36 / h;
            for (int x = 0; x < w; x += 2) {
                int p = 4 * (y * w + x);
                int cell = gy * 64 + x * 64 / w;
                sum[cell] += 0.2126f * (px.get(p + 2) & 255) + 0.7152f * (px.get(p + 1) & 255)
                        + 0.0722f * (px.get(p) & 255);
                n[cell]++;
            }
        }
        for (int i = 0; i < sum.length; i++) sum[i] = n[i] == 0 ? 0 : sum[i] / n[i];
        return sum;
    }

    private static double meanDiff(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / a.length;
    }

    private static synchronized void registerTickHook() {
        if (tickHook) return;
        net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents.TICK_END.register(m -> {
            try {
                Object session = RewindWatchProbes.call(RewindWatchProbes.cls(RewindWatchProbes.PLAYBACK), "session");
                Object ticks = RewindWatchProbes.call(session, "ticks");
                if (ticks == null) return;
                long run = ((Number) RewindWatchProbes.call(ticks, "ticksRun")).longValue();
                if (run != lastTicksRun) {
                    lastTicksRun = run;
                    synchronized (TICKS) {
                        if (TICKS.size() < 10_000) TICKS.add(new long[]{System.nanoTime(), run});
                    }
                }
            } catch (Exception ignored) {
                // no session
            }
        });
        tickHook = true;
    }

    /**
     * {@code rewind-export-terrain}: the playing recording's ticks (as ticks-run counts after them, RewindExport's
     * rule) holding chunk data (0x21, 0x26) and block changes (0x22, 0x23), and the timeline's first tick.
     */
    private static Map<String, Object> terrain() throws Exception {
        Object session = playing();
        Object rec = RewindWatchProbes.call(session, "recording");
        Object tl = RewindWatchProbes.call(session, "timeline");
        BitSet chunk = new BitSet(), block = new BitSet();
        RewindWatchProbes.call(RewindWatchProbes.cls(EXPORT), "terrainTicks", rec, tl, chunk, block);
        Map<String, Object> out = Bridge.ok();
        out.put("startTick", RewindWatchProbes.call(tl, "startTick"));
        out.put("endTick", RewindWatchProbes.call(tl, "endTick"));
        out.put("chunk", bits(chunk));
        out.put("block", bits(block));
        // K11: each listed tick's recorded time from the first tick (bit b = b ticks run after it: tick b - 1)
        long base = ((Number) RewindWatchProbes.call(tl, "timeOfTick", RewindWatchProbes.call(tl, "startTick")))
                .longValue();
        Map<String, Object> times = new java.util.TreeMap<>();
        for (BitSet b : new BitSet[]{chunk, block}) {
            for (int i = b.nextSetBit(0); i >= 0; i = b.nextSetBit(i + 1)) {
                long t = ((Number) RewindWatchProbes.call(tl, "timeOfTick", (long) (i - 1))).longValue();
                times.put(String.valueOf(i), t - base);
            }
        }
        out.put("timesUs", times);
        // RWE: the recorded clicks (TAP records), from the first tick
        Object ext = RewindWatchProbes.call(rec, "ext");
        int tapTag = RewindWatchProbes.cls("com.bedwarsqol.rewind.RewindFormat").getField("TAG_TAP").getInt(null);
        int n = ((Number) RewindWatchProbes.call(ext, "size")).intValue();
        List<Long> taps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (((Number) RewindWatchProbes.call(ext, "tag", i)).intValue() == tapTag) {
                taps.add(((Number) RewindWatchProbes.call(ext, "timeUs", i)).longValue() - base);
            }
        }
        out.put("tapsUs", taps);
        return out;
    }

    private static List<Integer> bits(BitSet b) {
        List<Integer> out = new ArrayList<>();
        for (int i = b.nextSetBit(0); i >= 0; i = b.nextSetBit(i + 1)) out.add(i);
        return out;
    }

    /**
     * {@code rewind-export-env}: loaded mods (id and version, from the loader) and, for K10's readiness search, the
     * names and contents of the files in the game's {@code config/} folder whose name mentions a renderer
     * (argentum, celeritas, sodium, embeddium), at most 64 KB each: public files the renderer itself writes.
     */
    private static Map<String, Object> env() throws Exception {
        Map<String, Object> out = Bridge.ok();
        List<String> mods = new ArrayList<>();
        for (net.fabricmc.loader.api.ModContainer m : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
            mods.add(m.getMetadata().getId() + " " + m.getMetadata().getVersion().getFriendlyString());
        }
        java.util.Collections.sort(mods);
        out.put("mods", mods);
        File cfg = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toFile();
        Map<String, Object> files = new java.util.TreeMap<>();
        List<String> all = new ArrayList<>();
        File[] list = cfg.listFiles();
        if (list != null) {
            for (File f : list) {
                all.add(f.getName() + (f.isDirectory() ? "/" : ""));
                String n = f.getName().toLowerCase();
                if (f.isFile() && f.length() <= 65_536 && (n.contains("argentum") || n.contains("celeritas")
                        || n.contains("sodium") || n.contains("embeddium"))) {
                    files.put(f.getName(), new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                }
            }
        }
        java.util.Collections.sort(all);
        out.put("configFiles", all);
        out.put("rendererConfigs", files);
        return out;
    }

    // ------------------------------------------------------------------ Phase 5: the pipeline (step RWE) and K11

    /** {@code rewind-export-start JSON}: a real export (helper, MP4) into the dev client's own export folder. */
    private static Map<String, Object> start(List<String> args) throws Exception {
        Object[] a = Policy.exportStart(String.join(" ", args), System.getProperty("cobblify.exportDir"));
        Object session = playing();
        ownFolder();
        Object timeline = RewindWatchProbes.call(session, "timeline");
        long base = ((Number) RewindWatchProbes.call(timeline, "timeOfTick",
                RewindWatchProbes.call(timeline, "startTick"))).longValue();
        Class<?> rq = RewindWatchProbes.cls(REQUEST);
        Object q = rq.getConstructor().newInstance();
        setField(q, "startUs", base + (Long) a[0]);
        setField(q, "endUs", base + (Long) a[1]);
        int w = (Integer) a[2], h = (Integer) a[3];
        Object settings = RewindWatchProbes.cls(SETTINGS).getMethod("of", int.class, int.class, String.class,
                String.class, boolean.class).invoke(null, w == 0 ? h : 1080, a[4], a[5], a[6], a[7]);
        setField(q, "settings", settings);
        if (w != 0) {
            setField(q, "width", w);
            setField(q, "height", h);
        }
        setField(q, "route", enumValue(RewindWatchProbes.cls(ROUTE), ((String) a[8]).toUpperCase()));
        if ("missing".equals(a[9])) {
            setField(q, "helperOverride", Policy.EXPORTS_DIR + "/no-such-helper");
        }
        Object why = RewindWatchProbes.call(RewindWatchProbes.cls(EXPORT), "start", q);
        if (why != null) throw new Refused("start refused: " + why);
        sink = null;
        Map<String, Object> out = Bridge.ok();
        out.put("started", true);
        return out;
    }

    /** {@code rewind-export-state [frames]}: the export's state JSON, and its frame lines with {@code frames}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> state(List<String> args) throws Exception {
        Map<String, Object> out = Bridge.ok();
        String json = (String) RewindWatchProbes.call(RewindWatchProbes.cls(EXPORT), "statusJson");
        out.put("export", new com.google.gson.JsonParser().parse(json));
        if (args.size() == 1 && "frames".equals(args.get(0))) {
            List<String> lines = (List<String>) RewindWatchProbes.call(RewindWatchProbes.cls(EXPORT), "frameLog");
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            synchronized (lines) {
                for (String l : lines) arr.add(new com.google.gson.JsonParser().parse(l));
            }
            out.put("frames", arr);
        }
        return out;
    }

    /**
     * {@code rewind-export-live-sound play|check}: a long sound in the live sound system (not the recording's), so
     * an export started while it rings must stop it (TASK S4, review I3); {@code check}: is it still playing.
     */
    private static Map<String, Object> liveSound(List<String> args) throws Exception {
        if (args.size() != 1 || !("play".equals(args.get(0)) || "check".equals(args.get(0)))) {
            throw new Refused("probe rewind-export-live-sound play|check");
        }
        Minecraft mc = Minecraft.getMinecraft();
        if ("play".equals(args.get(0))) {
            net.minecraft.client.audio.ISound snd = net.minecraft.client.audio.PositionedSoundRecord.create(
                    new net.minecraft.util.ResourceLocation("portal.travel"), 1.0f);
            liveSound = snd;
            mc.getSoundHandler().playSound(snd);
        }
        Map<String, Object> out = Bridge.ok();
        out.put("playing", liveSound != null
                && mc.getSoundHandler().isSoundPlaying((net.minecraft.client.audio.ISound) liveSound));
        return out;
    }

    /** {@code rewind-export-chat-type TEXT}: the open vanilla chat's input field holds TEXT (the fixture's typing). */
    private static Map<String, Object> chatType(List<String> args) throws Exception {
        String text = Policy.exportChatText(args);
        Bridge.requireSingleplayerWorld();
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof net.minecraft.client.gui.GuiChat)) throw new Refused("vanilla chat is not open");
        if (text == null) {
            mc.displayGuiScreen(null); // closed, nothing sent
            Map<String, Object> out = Bridge.ok();
            out.put("closed", true);
            return out;
        }
        net.minecraft.client.gui.GuiTextField field = null;
        for (java.lang.reflect.Field f : net.minecraft.client.gui.GuiChat.class.getDeclaredFields()) {
            if (f.getType() == net.minecraft.client.gui.GuiTextField.class) {
                f.setAccessible(true);
                field = (net.minecraft.client.gui.GuiTextField) f.get(mc.currentScreen);
            }
        }
        if (field == null) throw new Refused("no input field");
        field.setText(text);
        Map<String, Object> out = Bridge.ok();
        out.put("text", field.getText());
        return out;
    }

    /**
     * {@code rewind-export-region RUN_A NAME_A RUN_B NAME_B X0 Y0 X1 Y1}: in that box (top-left origin), the pixels
     * whose largest channel difference is above 48, and the mean absolute difference.
     */
    private static Map<String, Object> region(List<String> args) throws Exception {
        int[] r = Policy.exportRegion(args);
        BufferedImage a = ImageIO.read(new File(Policy.exportFolder(args.get(0)), args.get(1) + ".png"));
        BufferedImage b = ImageIO.read(new File(Policy.exportFolder(args.get(2)), args.get(3) + ".png"));
        if (a == null || b == null) throw new Refused("missing picture");
        int x1 = Math.min(r[2], Math.min(a.getWidth(), b.getWidth()) - 1);
        int y1 = Math.min(r[3], Math.min(a.getHeight(), b.getHeight()) - 1);
        long differing = 0, sum = 0, n = 0;
        for (int y = r[1]; y <= y1; y++) {
            for (int x = r[0]; x <= x1; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int dr = Math.abs((p >> 16 & 255) - (q >> 16 & 255)), dg = Math.abs((p >> 8 & 255) - (q >> 8 & 255)),
                        db = Math.abs((p & 255) - (q & 255));
                if (Math.max(dr, Math.max(dg, db)) > 48) differing++;
                sum += dr + dg + db;
                n++;
            }
        }
        Map<String, Object> out = Bridge.ok();
        out.put("differing", differing);
        out.put("mean", n == 0 ? 0 : sum / (3.0 * n));
        out.put("pixels", n);
        return out;
    }

    /** A cell counts as changed, or as matching, at this mean-luma distance (0-255) on the 64x36 grid. */
    private static final float CELL_TOL = 8f;

    /**
     * K11, export side ({@code rewind-export-k11 RUN_X J0 RUN_REF K}, PLAN Revision 4.1 r3-I1): RUN_REF holds the
     * settled references of RUN_X's frames j0-1 .. j0+K (2 s each). The change is where reference 0 (before it) and
     * reference 1 (frame j0, settled) differ: the 64x36 grid cells whose mean luma moved more than {@value #CELL_TOL}.
     * It shows in the first frame j >= j0 of RUN_X whose changed cells all match reference j-j0+1 within it: late =
     * j - j0 frames (null: not within K). The negative fixture leaves the old picture (reference 0) in every frame and
     * must never show it.
     */
    private static Map<String, Object> k11(List<String> args) throws Exception {
        Object[] a = Policy.exportK11(args);
        File x = new File(Policy.exportFolder((String) a[0]));
        File ref = new File(Policy.exportFolder((String) a[2]));
        int j0 = (Integer) a[1], k = (Integer) a[3];
        byte[] xGrids = null;
        File gb = new File(x, "grids.bin");
        if (gb.isFile()) xGrids = Files.readAllBytes(gb.toPath());
        float[] before = cellGrid(new File(ref, "frame-00000.png"));
        float[] after = cellGrid(new File(ref, "frame-00001.png"));
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < before.length; i++) if (Math.abs(before[i] - after[i]) > CELL_TOL) changed.add(i);
        Map<String, Object> out = Bridge.ok();
        out.put("cells", changed.size());
        Integer late = null, negLate = null;
        float worst = 0;
        for (int m = 1; m <= k + 1 && !changed.isEmpty(); m++) {
            File rf = new File(ref, String.format("frame-%05d.png", m));
            int j = j0 - 1 + m;
            float[] e;
            if (xGrids != null) {
                if ((j + 1) * 2304 > xGrids.length) break;
                e = new float[2304];
                for (int i = 0; i < 2304; i++) e[i] = xGrids[j * 2304 + i] & 255;
            } else {
                File xf = new File(x, String.format("frame-%05d.png", j));
                if (!xf.isFile()) break;
                e = cellGrid(xf);
            }
            if (!rf.isFile()) break;
            float[] r = cellGrid(rf);
            float d = 0, dn = 0;
            for (int c : changed) {
                d = Math.max(d, Math.abs(e[c] - r[c]));
                dn = Math.max(dn, Math.abs(before[c] - r[c]));
            }
            if (m == 1) worst = d;
            if (late == null && d <= CELL_TOL) late = m - 1;
            if (negLate == null && dn <= CELL_TOL) negLate = m - 1;
        }
        out.put("late", late);
        out.put("negativeLate", negLate);
        out.put("firstFrameWorstCell", worst);
        return out;
    }

    /**
     * A picture's 64x36 mean-luma grid over every second column of every second row counted from the bottom: the
     * same pixels {@link #cellGridBgra} reads from a read-back (bottom row first), so both sides compare like with like.
     */
    private static float[] cellGrid(File f) throws Exception {
        BufferedImage img = ImageIO.read(f);
        if (img == null) throw new Refused("missing picture " + f.getName());
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        float[] sum = new float[64 * 36];
        int[] n = new int[64 * 36];
        for (int y = 0; y < h; y++) {
            if (((h - 1 - y) & 1) != 0) continue;
            int gy = y * 36 / h;
            for (int xx = 0; xx < w; xx += 2) {
                int p = px[y * w + xx];
                int cell = gy * 64 + xx * 64 / w;
                sum[cell] += 0.2126f * (p >> 16 & 255) + 0.7152f * (p >> 8 & 255) + 0.0722f * (p & 255);
                n[cell]++;
            }
        }
        for (int i = 0; i < sum.length; i++) sum[i] = n[i] == 0 ? 0 : sum[i] / n[i];
        return sum;
    }

    /** {@link #cellGrid} from a BGRA read-back, bottom row first. */
    static float[] cellGridBgra(ByteBuffer px, int w, int h) {
        float[] sum = new float[64 * 36];
        int[] n = new int[64 * 36];
        int base = px.position();
        for (int r = 0; r < h; r += 2) {
            int gy = (h - 1 - r) * 36 / h;
            int row = base + 4 * r * w;
            for (int xx = 0; xx < w; xx += 2) {
                int p = row + 4 * xx;
                int cell = gy * 64 + xx * 64 / w;
                sum[cell] += 0.2126f * (px.get(p + 2) & 255) + 0.7152f * (px.get(p + 1) & 255)
                        + 0.0722f * (px.get(p) & 255);
                n[cell]++;
            }
        }
        for (int i = 0; i < sum.length; i++) sum[i] = n[i] == 0 ? 0 : sum[i] / n[i];
        return sum;
    }

    /**
     * K11, watch side ({@code rewind-export-grid cells TICK}): from the grids logged since {@code watch}, the cells that
     * changed between the last grid before TICK ended and the last grid (the settled picture), and the first grid at
     * or after TICK's end in which all of them match the settled picture within {@value #CELL_TOL}: lateMs.
     */
    private static Map<String, Object> cells(long tick) {
        Map<String, Object> out = Bridge.ok();
        synchronized (GRID_LOCK) {
            Long t0 = null;
            synchronized (TICKS) {
                for (long[] e : TICKS) if (e[1] == tick) t0 = (e[0] - gridStart) / 1_000_000L;
            }
            out.put("tickMs", t0);
            if (t0 == null || gridFull.isEmpty()) return out;
            int first = -1;
            for (int i = 0; i < gridLog.size() && i < gridFull.size(); i++) {
                if (gridLog.get(i)[0] >= t0) {
                    first = i;
                    break;
                }
            }
            if (first <= 0) return out;
            float[] before = gridFull.get(first - 1), fin = gridFull.get(Math.min(gridFull.size(), gridLog.size()) - 1);
            List<Integer> changed = new ArrayList<>();
            for (int c = 0; c < before.length; c++) if (Math.abs(before[c] - fin[c]) > CELL_TOL) changed.add(c);
            out.put("cells", changed.size());
            Long shown = null;
            for (int i = first; i < gridFull.size() && i < gridLog.size() && !changed.isEmpty(); i++) {
                float[] g = gridFull.get(i);
                boolean all = true;
                for (int c : changed) {
                    if (Math.abs(g[c] - fin[c]) > CELL_TOL) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    shown = gridLog.get(i)[0];
                    break;
                }
            }
            out.put("lateMs", shown == null ? null : shown - t0);
            out.put("grids", gridFull.size());
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static Object playing() throws Exception {
        Object session = RewindWatchProbes.call(RewindWatchProbes.cls(RewindWatchProbes.PLAYBACK), "session");
        String state = String.valueOf(RewindWatchProbes.call(session, "state"));
        if (!"WATCHING".equals(state)) throw new Refused("no recording is being watched (state " + state + ")");
        return session;
    }

    private static void ownFolder() throws Exception {
        File dir = (File) RewindWatchProbes.cls(Probes.REWIND_DIR).getMethod("get").invoke(null);
        Refused.check(Policy.rewindOwnFolder("rewind-export-spike", System.getProperty(Policy.REWIND_DIR_PROPERTY),
                dir.getAbsolutePath(), System.getProperty("user.home")));
    }

    private static void setField(Object o, String name, Object v) throws Exception {
        o.getClass().getField(name).set(o, v);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }
}
