package com.bedwarsqol.rewind.watch;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * TASK P4 (Rewind Export PLAN Solution 2, "the free camera does not move or turn"): an export started in the free
 * camera has the pose it had when Export was pressed in every frame, also with movement keys held and the mouse moved;
 * outside an export the same input moves and turns it.
 */
public class ExportCameraTest {

    @After
    public void releaseKeys() {
        CameraController.holdMove(0, 0, 0);
    }

    private static String pose() {
        FreeCameraMath.Camera c = CameraController.freePose();
        return c.x + "," + c.y + "," + c.z + "," + c.yaw + "," + c.pitch;
    }

    @Test
    public void theFreeCameraPoseIsConstantOver600ExportFrames() {
        FreeCameraMath.Camera c = CameraController.freePose();
        c.x = 10.5;
        c.y = 70.62;
        c.z = -3.25;
        c.yaw = 45f;
        c.pitch = 12f;
        String pressed = pose();
        for (int i = 0; i < 600; i++) {
            CameraController.holdMove(1, -1, 1); // W, D and Space held every frame
            CameraController.freeStep(true, true, 1 / 60.0);
            CameraController.freeLook(true, 37, -11, 0.5f, false);
            assertEquals("frame " + i, pressed, pose());
        }
    }

    @Test
    public void outsideAnExportTheSameInputMovesAndTurnsIt() {
        String before = pose();
        CameraController.holdMove(1, 0, 0);
        CameraController.freeStep(true, false, 1 / 60.0);
        assertNotEquals("moved", before, pose());
        String moved = pose();
        CameraController.freeLook(false, 37, -11, 0.5f, false);
        assertNotEquals("turned", moved, pose());
    }

    @Test
    public void keysHeldDuringAnExportDoNotMoveItAfterwards() {
        String before = pose();
        CameraController.holdMove(1, 1, 1);
        CameraController.freeStep(true, true, 1 / 60.0);
        CameraController.freeStep(true, false, 1 / 60.0); // the export ended; the keys were not pressed again
        assertEquals(before, pose());
    }
}
