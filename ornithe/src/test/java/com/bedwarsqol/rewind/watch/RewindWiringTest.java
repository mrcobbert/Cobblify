package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.rewind.Rewind;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Phase 7 wiring: {@code BedwarsQol.onReady} connects the phases that were built apart. The viewer's loading card and
 * viewer become the session's screens, the camera controller ({@link CameraController}) answers the viewer's camera
 * seam ({@link ViewerCameraControls}), a failed session ends in the gallery, and playback reads recordings through the
 * one {@link Rewind#store()} the recorder writes through (so it knows which file is still being written here).
 */
public class RewindWiringTest {

    @After
    public void unwire() {
        CameraController.selectYourView();
        ViewerCameraControls.set(null);
        RewindPlayback.setScreens(null);
        RewindPlayback.setFailureGallery(null);
    }

    @Test
    public void readyRegistersTheScreensTheCamerasAndTheFailureGallery() {
        BedwarsQol.wireRewindPlayback();
        assertSame("the viewer phase's screens", RewindViewerScreen.screens(), RewindPlayback.screens());
        assertSame("the camera controller answers the viewer", CameraController.controls(), ViewerCameraControls.get());
        assertNotNull("a failed session ends in the gallery", RewindPlayback.failureGallery());
    }

    @Test
    public void playbackReadsThroughTheRecordersStore() {
        assertSame(Rewind.store(), RewindPlayback.store());
    }

    @Test
    public void theCameraSeamDrivesTheController() {
        ViewerCameraControls c = CameraController.controls();

        // The viewer selects your view when it first shows a session, before the world is complete: no world, no
        // camera, no connection here.
        c.select(ViewerCameraControls.YOU);
        assertEquals(ViewerCameraControls.YOU, c.camera());
        assertTrue(CameraController.yourView());

        c.select(ViewerCameraControls.FREE);
        assertEquals(ViewerCameraControls.FREE, c.camera());
        assertTrue("the controller is in the free camera", CameraController.freeCamera());

        int level = c.flyLevel();
        assertEquals(CameraController.speedLevel(), level);
        c.scroll(120);
        assertEquals(FreeCameraMath.scrolled(level, 120), c.flyLevel());
        assertEquals("scroll reaches the controller", c.flyLevel(), CameraController.speedLevel());
        c.move(1, 0, 1, 0.05); // recorded for the next drawn frame; no world needed

        c.select(ViewerCameraControls.YOU);
        assertEquals(ViewerCameraControls.YOU, c.camera());
        assertFalse(CameraController.freeCamera());
        int before = c.flyLevel();
        c.scroll(-120);
        assertEquals("in your view the wheel does nothing", before, c.flyLevel());
    }
}
