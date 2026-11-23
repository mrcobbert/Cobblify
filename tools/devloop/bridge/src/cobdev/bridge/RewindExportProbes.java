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
    static final String SETTLE = EXPORT + "$Settle";
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
        long startUs = base + (Long) a[6] * 1000L;
        int fps = (Integer) a[3];
        int frames = (Integer) a[4];
        long endUs = startUs + (frames * 1_000_000L + fps - 1) / fps; // exactly `frames` frames
        Object plan = RewindWatchProbes.cls(PLAN).getConstructor(long.class, long.class, int.class)
                .newInstance(startUs, endUs, fps);
        Object o = RewindWatchProbes.cls(OPTIONS).getConstructor().newInstance();
        setField(o, "route", enumValue(RewindWatchProbes.cls(ROUTE), ((String) a[0]).toUpperCase()));
        setField(o, "width", a[1]);
        setField(o, "height", a[2]);
        setField(o, "settle", enumValue(RewindWatchProbes.cls(SETTLE), ((String) a[5]).toUpperCase()));
        setField(o, "holdChunkMs", a[7]);
        setField(o, "holdBlockMs", a[8]);
        String run = (String) a[9];
        File dir = "-".equals(run) ? null : new File(Policy.exportFolder(run));
        if (dir != null) prepare(dir);
        Sink s = new Sink(dir);
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

        Sink(File dir) {
            this.dir = dir;
            if (dir == null) return;
            Thread t = new Thread(this::drain, "cobdev-export-writer");
            t.setDaemon(true);
            t.start();
        }

        void frame(long index, ByteBuffer px, int w, int h) {
            if (dir == null) return; // timing run: discarded
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
        if (args.size() != 1 || !("watch".equals(args.get(0)) || "log".equals(args.get(0)))) {
            throw new Refused("probe rewind-export-grid watch|log");
        }
        registerTickHook();
        Map<String, Object> out = Bridge.ok();
        synchronized (GRID_LOCK) {
            if ("watch".equals(args.get(0))) {
                gridLog = new ArrayList<>();
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
