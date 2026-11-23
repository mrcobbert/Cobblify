package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.rewind.Rewind;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    private List<PlaybackPart> partsBefore;
    private RecordedScreenHolder holderBefore;

    /** The wiring registers playback parts for the whole JVM; each test puts the registry back as it found it. */
    @Before
    public void rememberParts() throws Exception {
        partsBefore = new ArrayList<PlaybackPart>(registry());
        holderBefore = RewindCamera.screenHolder();
    }

    @After
    public void unwire() throws Exception {
        CameraController.selectYourView();
        ViewerCameraControls.set(null);
        RewindPlayback.setScreens(null);
        RewindPlayback.setFailureGallery(null);
        registry().clear();
        registry().addAll(partsBefore);
        RewindCamera.setScreenHolder(holderBefore);
    }

    @Test
    public void readyRegistersTheScreensTheCamerasAndTheFailureGallery() {
        BedwarsQol.wireRewindPlayback();
        assertSame("the viewer phase's screens", RewindViewerScreen.screens(), RewindPlayback.screens());
        assertSame("the camera controller answers the viewer", CameraController.controls(), ViewerCameraControls.get());
        assertNotNull("a failed session ends in the gallery", RewindPlayback.failureGallery());
    }

    /**
     * Rewind parity Phase 16: ready registers every playback part, once each, in an order whose first-answer questions
     * and same-tick writes come out right. {@code PuppetSounds} answers the puppet's sounds ahead of the derived steps
     * (StepSoundPart registers it first); {@code RecordedScreens} is the camera's window holder before
     * {@code ContainerPart} writes into its containers; {@code HandV2Part} puts the v2 hand on the camera before
     * {@code RiddenView} puts another player's hand over it in their view; {@code RiddenView} answers the camera's
     * tab entry (the ridden player's skin and arm model) before {@code Identity} answers it with the recorded one.
     */
    @Test
    public void readyRegistersEveryPlaybackPartOnceInOrder() throws Exception {
        List<PlaybackPart> registry = registry();
        List<PlaybackPart> saved = new ArrayList<PlaybackPart>(registry);
        RecordedScreenHolder holder = RewindCamera.screenHolder();
        registry.clear(); // other test classes in this JVM may have registered single parts already
        try {
            BedwarsQol.wireRewindPlayback();
            BedwarsQol.wireRewindPlayback(); // a second call registers nothing twice
            List<String> got = new ArrayList<String>();
            for (PlaybackPart p : RewindPlayback.parts()) {
                got.add(p.getClass().getSimpleName());
            }
            assertEquals(Arrays.asList("CameraStatePart", "PuppetSounds", "StepSoundPart", "RecordedScreens",
                    "DigPart", "BlockPart", "SoundPart", "ContainerPart", "ViewPart", "FovPart", "HandV2Part",
                    "RiddenView", "Identity", "HudPlayback"), got);
            assertTrue("the recorded screens hold the camera's windows",
                    RewindCamera.screenHolder() instanceof RecordedScreens);
            assertSame(StepSoundPart.instance(), RewindPlayback.parts().get(2));
            assertSame(PuppetSounds.instance(), RewindPlayback.parts().get(1));
        } finally {
            registry.clear();
            registry.addAll(saved);
            RewindCamera.setScreenHolder(holder);
        }
    }

    @SuppressWarnings("unchecked")
    static List<PlaybackPart> registry() throws Exception {
        Field f = RewindPlayback.class.getDeclaredField("PARTS");
        f.setAccessible(true);
        return (List<PlaybackPart>) f.get(null);
    }

    /**
     * Rewind Export Phase 7: the viewer's export seam is the game's export ({@code RewindExport}) unless a test replaces
     * it. With nothing being watched it starts nothing, and nothing runs.
     */
    @Test
    public void theViewersExportSeamDrivesTheGamesExport() {
        RewindViewerScreen.Controls.setExporter(null);
        RewindViewerScreen.Controls.Exporter e = RewindViewerScreen.Controls.exporter();
        assertTrue("the game's export", e instanceof RewindViewerScreen.GameExporter);
        assertFalse(e.active());
        com.bedwarsqol.rewind.export.ExportPanelModel p = new com.bedwarsqol.rewind.export.ExportPanelModel(null);
        p.probe(new com.bedwarsqol.rewind.export.ExportPanelModel.Matrix(
                java.util.Collections.singletonList(new int[]{1920, 1080, 60}), null));
        assertEquals("not-watching", e.start(1_000_000L, 2_000_000L, p.choice(), p));
        assertFalse(e.active());
        assertEquals("nothing ended yet in this JVM", null, e.outcome());
    }

    /**
     * Code review 1 I4 (TASK U6): the export's render hooks report an exception as a render error (the export ends
     * FAILED, {@code ExportJobTest.aRenderErrorEndsFailedNotCancelled}), never as a cancel. The hooks need a running
     * export in a real game, so the wiring is read from the source.
     */
    @Test
    public void theExportsRenderHooksFailTheExportOnAnException() throws Exception {
        java.io.File src = null;
        for (String d : new String[]{"src/main/java", "ornithe/src/main/java"}) {
            java.io.File f = new java.io.File(d, "com/bedwarsqol/rewind/export/RewindExport.java");
            if (f.isFile()) {
                src = f;
            }
        }
        assertNotNull("RewindExport.java from " + new java.io.File(".").getAbsolutePath(), src);
        String code = new String(java.nio.file.Files.readAllBytes(src.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        for (String where : new String[]{"begin", "frame", "window"}) {
            assertTrue(where + " fails the export", code.contains("x.job.renderError(\"" + where + "\", t);"));
            assertFalse(where + " is not a cancel", code.contains("job.cancel(\"" + where + "-"));
        }
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
