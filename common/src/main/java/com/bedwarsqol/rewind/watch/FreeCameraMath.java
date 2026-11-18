package com.bedwarsqol.rewind.watch;

/**
 * The free camera's math (G43, Q1, Q2). Real time: callers pass wall-clock seconds, so it moves the same paused, at
 * any speed and during a catch-up.
 *
 * <ul>
 *   <li>Speed levels 1-10, default 4: {@code 10 x 1.4^(level - 4)} blocks per second (3.6 to 75). One scroll notch
 *       is one level.</li>
 *   <li>Moves: WASD horizontally by yaw only (Minecraft's convention: yaw 0 faces +z, yaw 90 faces -x; strafe +1 is
 *       left), a diagonal no faster than straight; Space up, Shift down at the same speed, on top of the horizontal
 *       move.</li>
 *   <li>Drag look with vanilla's mouse curve: {@code f = sensitivity x 0.6 + 0.2},
 *       {@code yaw += dx x f^3 x 8 x 0.15}, {@code pitch -= dy x f^3 x 8 x 0.15} (dy positive = mouse moved up, as
 *       LWJGL reports it; inverted mouse flips it), pitch clamped to +-90.</li>
 *   <li>The speed bar shows for 1 s after a scroll, then fades out over 200 ms (mockup {@code drawFly}).</li>
 * </ul>
 *
 * Pure.
 */
public final class FreeCameraMath {

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 10;
    public static final int DEFAULT_LEVEL = 4;
    /** The speed bar's full-opacity time after a scroll. */
    public static final long SPEED_BAR_MS = 1000L;
    public static final long SPEED_BAR_FADE_MS = 200L;

    /** The free camera's pose: eye position and rotation in degrees. */
    public static final class Camera {
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
    }

    private FreeCameraMath() {
    }

    private static int clampLevel(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    public static double blocksPerSecond(int level) {
        return 10.0 * Math.pow(1.4, clampLevel(level) - DEFAULT_LEVEL);
    }

    /** The level after a mouse wheel delta (LWJGL's {@code Mouse.getEventDWheel()}): one level per notch, by sign. */
    public static int scrolled(int level, int wheel) {
        return clampLevel(level + Integer.signum(wheel));
    }

    /** +1, -1 or 0 from two opposite keys. */
    public static int axis(boolean plus, boolean minus) {
        return (plus ? 1 : 0) - (minus ? 1 : 0);
    }

    /**
     * Moves {@code c} for {@code seconds} of real time.
     *
     * @param forward  +1 forward (W), -1 back (S)
     * @param strafe   +1 left (A), -1 right (D)
     * @param vertical +1 up (Space), -1 down (Shift)
     */
    public static void move(Camera c, int forward, int strafe, int vertical, int level, double seconds) {
        if (seconds <= 0) {
            return;
        }
        double step = blocksPerSecond(level) * seconds;
        double f = Integer.signum(forward);
        double s = Integer.signum(strafe);
        double length = Math.sqrt(f * f + s * s);
        if (length > 0) {
            double rad = Math.toRadians(c.yaw);
            double sin = Math.sin(rad);
            double cos = Math.cos(rad);
            double k = step / length;
            c.x += (s * cos - f * sin) * k;
            c.z += (f * cos + s * sin) * k;
        }
        c.y += Integer.signum(vertical) * step;
    }

    /** Turns {@code c} by a drag of {@code dx, dy} mouse pixels (dy positive = up) with vanilla's sensitivity curve. */
    public static void look(Camera c, double dx, double dy, float sensitivity, boolean invert) {
        double f = sensitivity * 0.6 + 0.2;
        double scale = f * f * f * 8.0 * 0.15;
        c.yaw = (float) (c.yaw + dx * scale);
        double pitch = c.pitch - dy * scale * (invert ? -1 : 1);
        c.pitch = (float) Math.max(-90.0, Math.min(90.0, pitch));
    }

    /** The speed bar's opacity {@code msSinceScroll} after the last scroll: 1 for 1 s, then a 200 ms fade. */
    public static float speedBarAlpha(long msSinceScroll) {
        if (msSinceScroll < 0 || msSinceScroll >= SPEED_BAR_MS + SPEED_BAR_FADE_MS) {
            return 0f;
        }
        if (msSinceScroll < SPEED_BAR_MS) {
            return 1f;
        }
        return 1f - (float) (msSinceScroll - SPEED_BAR_MS) / SPEED_BAR_FADE_MS;
    }
}
