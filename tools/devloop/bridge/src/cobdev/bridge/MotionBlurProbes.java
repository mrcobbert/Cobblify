package cobdev.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Probes for Motion Blur (cycle 2026-11-17-motion-blur, PLAN Phase 3, cobdev step MB). Cobblify is reached by
 * reflection on its public {@code com.bedwarsqol.feature.MotionBlur.record()} (the last decided frame: frameId, yaw,
 * pitch, smearPx, taps, active, worldPassRan, resetReason, cutSerial, cutSerialNow, glEntries, failed, suppressed);
 * Cobblify's names are not remapped, so the same calls work in dev and prod. Every probe needs the singleplayer world
 * ({@link Probes#run}).
 *
 * <p>A <b>frame driver</b> thread keeps exactly one task queued on the game thread while a scripted turn or a frame
 * log is running, and queues the next as soon as it has run, so the task runs once in every pass of the game loop:
 * before that pass's tick and frame, after the previous frame. There it sets the turn's yaw (current and previous
 * alike, so the interpolated camera yaw equals it at any partial tick) and logs the record of the frame just drawn.
 *
 * <ul>
 *   <li>{@code mb-record}: the record plus {@code Minecraft.getDebugFPS()}; {@code mb-record watch} starts a frame
 *       log (one record per drawn frame), {@code mb-record log} stops it and returns it.</li>
 *   <li>{@code mb-turn FROM TO DEG/S}: a turn of the player's yaw from FROM to TO, the yaw a function of the time
 *       since the start ({@link Policy#turnYaw}); it rests at TO.</li>
 *   <li>{@code mb-pose YAW PITCH}: stops any turn and sets the current and previous yaw and pitch to these floats.</li>
 *   <li>{@code mb-shot [stopTurn]}: one game-thread task that (optionally) stops the turn, reads the record of the
 *       last drawn frame and that same frame from the main framebuffer ({@link Bridge#readFrame}). Returns both, the
 *       PNG as base64.</li>
 * </ul>
 */
final class MotionBlurProbes {

    static final String MOTION_BLUR = "com.bedwarsqol.feature.MotionBlur";
    /** The most frames one log keeps, and the longest it runs. */
    private static final int LOG_MAX_FRAMES = 4096;
    private static final long LOG_MAX_NANOS = 10_000_000_000L;

    private static final Object LOCK = new Object();
    /** The running turn, or null. Guarded by {@link #LOCK}. */
    private static Turn turn;
    /** The frame log while {@code mb-record watch} runs, or null; {@link #logUntil} ends its collecting. */
    private static List<Map<String, Object>> log;
    private static long logUntil;
    private static long logLastFrame = Long.MIN_VALUE;
    private static Thread driver;
    private static Method record;

    private MotionBlurProbes() {
    }

    /** A scripted turn: from, to, deg/s, and its start on {@code System.nanoTime()}. */
    private static final class Turn {
        final double from, to, rate;
        final long start;

        Turn(double from, double to, double rate, long start) {
            this.from = from;
            this.to = to;
            this.rate = rate;
            this.start = start;
        }

        double elapsedMs(long now) {
            return (now - start) / 1e6;
        }
    }

    static Object run(String name, List<String> args) throws Exception {
        switch (name) {
            case "mb-record":
                return record(args);
            case "mb-turn":
                return turn(args);
            case "mb-pose":
                return pose(args);
            case "mb-shot":
                return shot(args);
            default:
                throw new Refused("unknown probe " + name);
        }
    }

    /** {@code mb-record [watch|log]}. */
    private static Map<String, Object> record(List<String> args) throws Exception {
        String mode = Policy.mbRecordMode(args);
        recordMethod(); // refuse before touching the game when this build has no Motion Blur
        return Bridge.onMain(() -> {
            Map<String, Object> out = Bridge.ok();
            if ("log".equals(mode)) {
                List<Map<String, Object>> frames;
                synchronized (LOCK) {
                    frames = log == null ? new ArrayList<>() : log;
                    log = null;
                }
                out.put("frames", frames);
                return out;
            }
            out.putAll(current());
            out.put("fps", Minecraft.getDebugFPS());
            if ("watch".equals(mode)) {
                synchronized (LOCK) {
                    log = new ArrayList<>();
                    logUntil = System.nanoTime() + LOG_MAX_NANOS;
                    logLastFrame = Long.MIN_VALUE;
                }
                startDriver();
                out.put("watching", true);
            }
            return out;
        });
    }

    /** {@code mb-turn FROM TO DEG/S}: starts at FROM on this game-thread task; the driver moves it every pass after. */
    private static Map<String, Object> turn(List<String> args) throws Exception {
        double[] t = Policy.mbTurn(args);
        return Bridge.onMain(() -> {
            EntityPlayerSP p = player();
            setYaw(p, (float) t[0]);
            synchronized (LOCK) {
                turn = new Turn(t[0], t[1], t[2], System.nanoTime());
            }
            startDriver();
            Map<String, Object> out = Bridge.ok();
            out.put("from", t[0]);
            out.put("to", t[1]);
            out.put("rate", t[2]);
            out.put("durationMs", Math.abs(t[1] - t[0]) / t[2] * 1000);
            return out;
        });
    }

    /** {@code mb-pose YAW PITCH}: stops any turn; the current and previous yaw and pitch become exactly these floats. */
    private static Map<String, Object> pose(List<String> args) throws Exception {
        float[] pose = Policy.mbPose(args);
        return Bridge.onMain(() -> {
            EntityPlayerSP p = player();
            synchronized (LOCK) {
                turn = null;
            }
            setYaw(p, pose[0]);
            p.rotationPitch = pose[1];
            p.prevRotationPitch = pose[1];
            Map<String, Object> out = Bridge.ok();
            out.put("yaw", (double) p.rotationYaw);
            out.put("pitch", (double) p.rotationPitch);
            return out;
        });
    }

    /**
     * {@code mb-shot [stopTurn]}: in one game-thread task, the record of the last drawn frame and that frame's pixels.
     * {@code turning} says whether a turn was still under way (not yet at TO) when the task ran.
     */
    private static Map<String, Object> shot(List<String> args) throws Exception {
        boolean stop = Policy.mbShotStop(args);
        recordMethod();
        Object[] got = Bridge.onMain(() -> {
            player();
            long now = System.nanoTime();
            Map<String, Object> out = Bridge.ok();
            synchronized (LOCK) {
                out.put("turning", turn != null);
                out.put("turnElapsedMs", turn == null ? null : turn.elapsedMs(now));
                if (stop) turn = null;
            }
            out.put("record", current());
            out.put("fps", Minecraft.getDebugFPS());
            return new Object[]{out, Bridge.readFrame()};
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) got[0];
        Object[] frame = (Object[]) got[1];
        out.put("width", frame[0]);
        out.put("height", frame[1]);
        out.put("png", Base64.getEncoder().encodeToString(Bridge.png(frame)));
        return out;
    }

    // ------------------------------------------------------------------------------ frame driver

    private static void startDriver() {
        synchronized (LOCK) {
            if (driver != null && driver.isAlive()) return;
            driver = new Thread(MotionBlurProbes::drive, "cobdev-motion-blur");
            driver.setDaemon(true);
            driver.start();
        }
    }

    /** Keeps one {@link #onPass} task queued while there is work; ends when the turn and the log are both done. */
    private static void drive() {
        while (true) {
            synchronized (LOCK) {
                if (!busy(System.nanoTime())) {
                    driver = null;
                    return;
                }
            }
            Minecraft m = Minecraft.getMinecraft();
            Future<Object> f = m.addScheduledTask(() -> {
                onPass();
                return null;
            });
            try {
                while (true) {
                    try {
                        f.get(100, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException e) {
                        synchronized (LOCK) {
                            // frozen or throttled: never queue a second task behind this one
                            if (!busy(System.nanoTime())) {
                                f.cancel(false);
                                break;
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (ExecutionException e) {
                synchronized (LOCK) {
                    turn = null;
                    logUntil = 0;
                }
            }
        }
    }

    private static boolean busy(long now) {
        return turn != null || (log != null && now < logUntil && log.size() < LOG_MAX_FRAMES);
    }

    /** Game thread, once per pass of the game loop: advance the turn, log the frame just drawn. */
    private static void onPass() throws Exception {
        Minecraft m = Minecraft.getMinecraft();
        if (!Policy.keepHolding(m.theWorld != null, m.isSingleplayer()) || m.thePlayer == null) {
            synchronized (LOCK) {
                turn = null; // a multiplayer server or the title screen ends the turn and the log
                logUntil = 0;
            }
            return;
        }
        long now = System.nanoTime();
        synchronized (LOCK) {
            if (turn != null) {
                double yaw = Policy.turnYaw(turn.from, turn.to, turn.rate, (now - turn.start) / 1e9);
                setYaw(m.thePlayer, (float) yaw);
                if (yaw == turn.to) turn = null; // rests at TO
            }
            if (log != null && now < logUntil && log.size() < LOG_MAX_FRAMES) {
                Map<String, Object> rec = current();
                Object id = rec.get("frameId");
                long frame = id instanceof Number ? ((Number) id).longValue() : Long.MIN_VALUE;
                if (frame != logLastFrame) {
                    log.add(rec);
                    logLastFrame = frame;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    private static EntityPlayerSP player() {
        Minecraft m = Bridge.mc();
        EntityPlayerSP p = m.thePlayer;
        if (p == null) throw new Refused("no player yet");
        if (m.getRenderViewEntity() != p) throw new Refused("the camera is not the player (spectating another entity)");
        return p;
    }

    /** Current and previous yaw alike, so the interpolated camera yaw is exactly {@code yaw} at any partial tick. */
    private static void setYaw(EntityPlayerSP p, float yaw) {
        p.rotationYaw = yaw;
        p.prevRotationYaw = yaw;
    }

    /** {@code MotionBlur.record()} as a plain map (numbers, booleans, strings, null). Game thread. */
    private static Map<String, Object> current() throws Exception {
        Object r;
        try {
            r = recordMethod().invoke(null);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            if (c instanceof Error) throw (Error) c;
            throw e;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) r).entrySet()) out.put(String.valueOf(e.getKey()), Mods.plain(e.getValue()));
        return out;
    }

    private static Method recordMethod() {
        Method m = record;
        if (m != null) return m;
        try {
            m = Class.forName(MOTION_BLUR, true, MotionBlurProbes.class.getClassLoader()).getMethod("record");
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
            throw new Refused("this build has no " + MOTION_BLUR + ".record() (Motion Blur not in it yet?)");
        }
        record = m;
        return m;
    }
}
