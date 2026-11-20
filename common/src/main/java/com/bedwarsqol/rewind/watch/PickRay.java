package com.bedwarsqol.rewind.watch;

/**
 * Click to ride (PLAN "R3 Watch full"; TASK D2): in the free camera a click rides the player under the crosshair
 * (the screen's centre, while the camera holds the cursor) or under the free cursor (not on the bar).
 *
 * <ul>
 *   <li>{@link #direction}: the cursor as a world direction, by vanilla's projection: {@code gluPerspective} with the
 *       vertical FOV and the window's aspect, and {@code EntityRenderer.orientCamera}'s rotation (the world turned by
 *       the pitch about x, then by yaw + 180 about y). So yaw 0 looks along +z, yaw 90 along -x, pitch 90 straight
 *       down, and the screen's right is the camera's right.</li>
 *   <li>{@link #nearest}: the ray against the players' boxes, each grown by vanilla's pick border ({@value #BORDER},
 *       {@code Entity.getCollisionBorderSize}), within {@value #REACH} blocks; the nearest is ridden. Blocks do not
 *       stop the ray.</li>
 * </ul>
 *
 * Pure.
 */
public final class PickRay {

    public static final double REACH = 96;
    public static final double BORDER = 0.1;

    private PickRay() {
    }

    /**
     * The world direction (unit vector into {@code out}) under the cursor at ({@code cursorX}, {@code cursorY}) of a
     * {@code width} x {@code height} screen (any unit, y down), for a camera at {@code yaw}/{@code pitch} degrees with
     * a vertical field of view of {@code fovDegrees}.
     */
    public static void direction(float yaw, float pitch, float fovDegrees, float width, float height, float cursorX,
                                 float cursorY, double[] out) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        double sy = Math.sin(y), cy = Math.cos(y), sp = Math.sin(p), cp = Math.cos(p);
        // forward, right and up of the camera in world space
        double fx = -sy * cp, fy = -sp, fz = cy * cp;
        double rx = -cy, rz = -sy;
        double ux = -sy * sp, uy = cp, uz = cy * sp;
        double t = Math.tan(Math.toRadians(fovDegrees) / 2);
        double aspect = height > 0 ? width / (double) height : 1;
        double nx = width > 0 ? 2.0 * cursorX / width - 1 : 0, ny = height > 0 ? 1 - 2.0 * cursorY / height : 0;
        double h = nx * t * aspect, v = ny * t;
        double dx = fx + rx * h + ux * v, dy = fy + uy * v, dz = fz + rz * h + uz * v;
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        out[0] = dx / n;
        out[1] = dy / n;
        out[2] = dz / n;
    }

    /**
     * Where the ray from (ox, oy, oz) along (dx, dy, dz) enters the box {@code box[off .. off + 6)} = {minX, minY,
     * minZ, maxX, maxY, maxZ}: the distance in units of the direction's length (0 from inside), or -1 for a miss.
     */
    public static double hit(double ox, double oy, double oz, double dx, double dy, double dz, double[] box,
                             int off) {
        double near = Double.NEGATIVE_INFINITY, far = Double.POSITIVE_INFINITY;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz};
        for (int a = 0; a < 3; a++) {
            double min = box[off + a], max = box[off + 3 + a];
            if (d[a] == 0) {
                if (o[a] < min || o[a] > max) return -1;
                continue;
            }
            double t1 = (min - o[a]) / d[a], t2 = (max - o[a]) / d[a];
            if (t1 > t2) {
                double s = t1;
                t1 = t2;
                t2 = s;
            }
            near = Math.max(near, t1);
            far = Math.min(far, t2);
            if (near > far) return -1;
        }
        if (far < 0) return -1;
        return Math.max(0, near);
    }

    /**
     * The nearest of the first {@code count} boxes ({@code boxes[6 i .. 6 i + 6)}) the ray from (ox, oy, oz) along
     * the unit vector {@code dir} enters within {@code reach} blocks.
     *
     * @return its index, or -1
     */
    public static int nearest(double ox, double oy, double oz, double[] dir, double[] boxes, int count,
                              double reach) {
        int best = -1;
        double bestT = reach;
        for (int i = 0; i < count; i++) {
            double t = hit(ox, oy, oz, dir[0], dir[1], dir[2], boxes, i * 6);
            if (t >= 0 && t <= bestT) {
                if (best < 0 || t < bestT) {
                    best = i;
                    bestT = t;
                }
            }
        }
        return best;
    }
}
