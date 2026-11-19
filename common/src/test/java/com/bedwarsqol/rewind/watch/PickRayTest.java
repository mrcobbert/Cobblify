package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TASK D2 (PLAN "R3 Watch full"): a click, not a drag, in the free camera rides the player under the cursor. The
 * cursor becomes a world direction by vanilla's projection (vertical FOV, the window's aspect, the camera's yaw and
 * pitch as {@code EntityRenderer.orientCamera} rotates the world: pitch about x, then yaw + 180 about y), which is
 * cast against the players' boxes (grown by vanilla's 0.1 pick border) within 96 blocks; the nearest is ridden.
 */
public class PickRayTest {

    private static final double EPS = 1e-9;

    private static double[] dir(float yaw, float pitch, float fov, float w, float h, float x, float y) {
        double[] d = new double[3];
        PickRay.direction(yaw, pitch, fov, w, h, x, y, d);
        return d;
    }

    private static void assertDir(String what, double[] expected, double[] actual) {
        double n = Math.sqrt(expected[0] * expected[0] + expected[1] * expected[1] + expected[2] * expected[2]);
        for (int i = 0; i < 3; i++) assertEquals(what + " [" + i + "]", expected[i] / n, actual[i], 1e-6);
    }

    @Test
    public void theCentreOfTheScreenIsWhereTheCameraLooks() {
        assertDir("yaw 0 faces +z", new double[]{0, 0, 1}, dir(0, 0, 70, 427, 240, 213.5f, 120));
        assertDir("yaw 90 faces -x", new double[]{-1, 0, 0}, dir(90, 0, 70, 427, 240, 213.5f, 120));
        assertDir("yaw 180 faces -z", new double[]{0, 0, -1}, dir(180, 0, 70, 427, 240, 213.5f, 120));
        assertDir("pitch 90 looks down", new double[]{0, -1, 0}, dir(0, 90, 70, 427, 240, 213.5f, 120));
        assertDir("pitch -45 looks up", new double[]{0, 1, 1}, dir(0, -45, 70, 427, 240, 213.5f, 120));
    }

    @Test
    public void theEdgesFollowTheVerticalFovAndTheAspect() {
        // FOV 90 vertical, a 2:1 window: the top edge is 45 degrees up, the right edge atan(2) to the right
        assertDir("top", new double[]{0, 1, 1}, dir(0, 0, 90, 200, 100, 100, 0));
        assertDir("bottom", new double[]{0, -1, 1}, dir(0, 0, 90, 200, 100, 100, 100));
        assertDir("right (yaw 0: the player's right is -x)", new double[]{-2, 0, 1}, dir(0, 0, 90, 200, 100, 200, 50));
        assertDir("left", new double[]{2, 0, 1}, dir(0, 0, 90, 200, 100, 0, 50));
        assertDir("top right", new double[]{-2, 1, 1}, dir(0, 0, 90, 200, 100, 200, 0));
    }

    /** Against an independent model of vanilla's GL transform, for random cameras and cursors. */
    @Test
    public void matchesVanillasCameraRotationEverywhere() {
        Random r = new Random(14);
        for (int i = 0; i < 2000; i++) {
            float yaw = r.nextFloat() * 720 - 360, pitch = r.nextFloat() * 180 - 90, fov = 30 + r.nextFloat() * 100;
            float w = 320 + r.nextInt(800), h = 240 + r.nextInt(400), x = r.nextFloat() * w, y = r.nextFloat() * h;
            double t = Math.tan(Math.toRadians(fov) / 2);
            double[] cam = {(2 * x / w - 1) * t * w / h, (1 - 2 * y / h) * t, -1}; // GL eye space: -z ahead
            double[] v = rotY(-(yaw + 180), rotX(-pitch, cam)); // undo rotate(pitch, x) then rotate(yaw + 180, y)
            assertDir("case " + i, v, dir(yaw, pitch, fov, w, h, x, y));
        }
    }

    private static double[] rotX(double deg, double[] v) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new double[]{v[0], c * v[1] - s * v[2], s * v[1] + c * v[2]};
    }

    private static double[] rotY(double deg, double[] v) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new double[]{c * v[0] + s * v[2], v[1], -s * v[0] + c * v[2]};
    }

    /** A player's box (0.6 x 1.8) standing at (x, y, z), grown by the pick border, as {minX, minY, minZ, max...}. */
    private static double[] player(double x, double y, double z) {
        double b = PickRay.BORDER;
        return new double[]{x - 0.3 - b, y - b, z - 0.3 - b, x + 0.3 + b, y + 1.8 + b, z + 0.3 + b};
    }

    private static double[] concat(double[]... boxes) {
        double[] out = new double[boxes.length * 6];
        for (int i = 0; i < boxes.length; i++) System.arraycopy(boxes[i], 0, out, i * 6, 6);
        return out;
    }

    @Test
    public void aBoxIsHitAtItsNearFace() {
        double[] box = player(0, 64, 10);
        assertEquals(10 - 0.4, PickRay.hit(0, 65, 0, 0, 0, 1, box, 0), EPS);
        assertEquals("beside it", -1, PickRay.hit(1, 65, 0, 0, 0, 1, box, 0), EPS);
        assertEquals("behind the camera", -1, PickRay.hit(0, 65, 20, 0, 0, 1, box, 0), EPS);
        assertEquals("over its head", -1, PickRay.hit(0, 66.1, 0, 0, 0, 1, box, 0), EPS);
        assertEquals("from inside: at once", 0, PickRay.hit(0, 65, 10, 0, 0, 1, box, 0), EPS);
        assertEquals("parallel to a face, outside", -1, PickRay.hit(0.5, 65, 0, 0, 0, 1, box, 0), EPS);
    }

    @Test
    public void theNearestPlayerWithinReachIsPicked() {
        double[] d = {0, 0, 1};
        double[] boxes = concat(player(0, 64, 30), player(0, 64, 12), player(3, 64, 5));
        assertEquals("the nearer of two in line", 1, PickRay.nearest(0, 65, 0, d, boxes, 3, PickRay.REACH));
        assertEquals("only the first n boxes count", 0, PickRay.nearest(0, 65, 0, d, boxes, 1, PickRay.REACH));
        assertEquals(-1, PickRay.nearest(0, 65, 0, d, concat(player(0, 64, 100)), 1, PickRay.REACH));
        assertEquals(0, PickRay.nearest(0, 65, 0, d, concat(player(0, 64, 95)), 1, PickRay.REACH));
        assertEquals(-1, PickRay.nearest(0, 65, 0, d, new double[0], 0, PickRay.REACH));
    }

    @Test
    public void aClickedPlayerOnScreenIsTheOneUnderTheCursor() {
        // camera at eye (0, 65.62, 0), yaw 0, FOV 70, 427 x 240: a player 10 blocks ahead and 3 to the camera's left
        // (+x) shows left of the centre
        float w = 427, h = 240;
        double[] boxes = concat(player(3, 64, 10), player(-3, 64, 10));
        double t = Math.tan(Math.toRadians(70) / 2);
        float leftX = (float) ((1 - 3 / 10.0 / (t * w / h)) * w / 2); // x of the left player's centre line
        double[] d = dir(0, 0, 70, w, h, leftX, h / 2f);
        assertEquals(0, PickRay.nearest(0, 65.62, 0, d, boxes, 2, PickRay.REACH));
        d = dir(0, 0, 70, w, h, w - leftX, h / 2f);
        assertEquals(1, PickRay.nearest(0, 65.62, 0, d, boxes, 2, PickRay.REACH));
        d = dir(0, 0, 70, w, h, w / 2f, h / 2f);
        assertEquals("between them: nobody", -1, PickRay.nearest(0, 65.62, 0, d, boxes, 2, PickRay.REACH));
    }

    @Test
    public void aClickIsAPressAndReleaseWithinThreePixels() {
        PickRay.Click c = new PickRay.Click();
        assertFalse("no press", c.release(10, 10));
        c.press(10, 10);
        assertTrue(c.down());
        assertTrue(c.release(12, 12));
        assertFalse(c.down());
        c.press(10, 10);
        assertFalse("4 px: a drag", c.release(14, 10));
        c.press(10, 10);
        c.move(20, 10);
        c.move(10, 10);
        assertFalse("dragged away and back: still a drag", c.release(10, 10));
        c.press(10, 10);
        c.move(13, 10);
        assertTrue("exactly 3 px", c.release(13, 10));
        c.press(10, 10);
        c.cancel();
        assertFalse(c.release(10, 10));
    }
}
