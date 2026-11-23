package com.bedwarsqol.rewind.watch;

import net.minecraft.client.gui.GuiScreen;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Phase 7 integration defect: the gallery opened from the Esc menu of a playing session got no input while playback
 * was paused. A paused session runs 0 world ticks a frame, and vanilla hands a screen its input only from
 * {@code runTick}. While a recording plays the gallery must ignore vanilla's per-tick call and pump its input once per
 * drawn frame, the way {@link RewindScreen} does; with no recording playing it stays vanilla.
 */
public class RewindGalleryInputTest {

    private static final class Counter implements Runnable {
        int pumps;

        @Override
        public void run() {
            pumps++;
        }
    }

    private static RewindGalleryScreen gallery(boolean playing, Counter pump) {
        RewindGalleryScreen g = new RewindGalleryScreen(GalleryModel.Origin.PLAYING, new GuiScreen() {
        });
        g.playing = () -> playing;
        g.inputPump = pump;
        return g;
    }

    @Test
    public void whilePlayingInputIsPumpedOncePerFrameNotPerTick() {
        Counter pump = new Counter();
        RewindGalleryScreen g = gallery(true, pump);

        // A catch-up runs many ticks a frame: vanilla's per-tick calls must not deliver input.
        for (int i = 0; i < 5; i++) {
            g.handleInput();
        }
        assertEquals("vanilla's runTick call is ignored while a recording plays", 0, pump.pumps);

        // Paused: 0 ticks, but every drawn frame still pumps once.
        assertTrue("the screen stays open", g.frameInput());
        assertTrue(g.frameInput());
        assertEquals("one pump per drawn frame", 2, pump.pumps);
    }

    @Test
    public void withNoRecordingPlayingTheGalleryIsVanilla() {
        Counter pump = new Counter();
        RewindGalleryScreen g = gallery(false, pump);

        g.handleInput();
        g.handleInput();
        assertEquals("vanilla's runTick call delivers input", 2, pump.pumps);

        assertTrue(g.frameInput());
        assertEquals("drawing pumps nothing extra", 2, pump.pumps);
    }

    /**
     * Code review 1, I1: the screen enables keyboard repeat and vanilla forwards every repeated key-down to
     * {@code keyTyped}. Holding Delete (one physical press, then repeats) must delete nothing: the screen passes the
     * event's repeat flag to the model. Intents go to a recorder here, so no file is ever touched.
     */
    @Test
    public void holdingDeleteThroughTheScreenDeletesNothing() {
        for (int key : new int[]{GalleryModel.KEY_DELETE, GalleryModel.KEY_BACK}) {
            RewindGalleryScreen g = gallery(false, new Counter());
            final GalleryModel m = new GalleryModel(GalleryModel.Origin.PLAYING, GalleryModelTest.three(),
                    GalleryModelTest.NOW, GalleryModelTest.TZ);
            g.useModel(m);
            final boolean[] repeat = new boolean[1];
            g.repeatEvent = () -> repeat[0];
            final int[] deletes = new int[1];
            g.actions = intent -> {
                if (intent.kind == GalleryModel.Intent.Kind.DELETE) {
                    deletes[0]++;
                    m.removed(intent.id); // as the library's successful delete would report it
                }
            };
            for (int i = 0; i < 6; i++) { // one physical press, then five repeats
                repeat[0] = i > 0;
                g.keyTyped((char) 0, key);
            }
            assertEquals("repeat events deleted nothing", 0, deletes[0]);
            assertEquals(3, m.entries().size());
            repeat[0] = false;
            g.keyTyped((char) 0, key);
            assertEquals("a second physical press confirms", 1, deletes[0]);
        }
    }

    @Test
    public void productionWiringReadsTheKeyboardRepeatFlag() throws Exception {
        java.io.File src = new java.io.File(PlaybackIsolationScanTest.mainRoot(),
                "com/bedwarsqol/rewind/watch/RewindGalleryScreen.java");
        String text = PlaybackIsolationScanTest.strip(PlaybackIsolationScanTest.read(src));
        assertTrue(text.contains("repeatEvent = Keyboard::isRepeatEvent"));
    }

    /**
     * Rewind Export Phase 7 (TASK U2): the gallery's Export opens that game with a request the viewer reads when it
     * first shows it (export mode, paused at the first marker; {@code RewindViewerActionsTest}). Watch opens it with no
     * request. The opener is a recorder here, so no recording is ever opened.
     */
    @Test
    public void exportOpensTheGameWithTheExportModeRequest() {
        try {
            RewindGalleryScreen g = gallery(false, new Counter());
            GalleryModel m = new GalleryModel(GalleryModel.Origin.TITLE, GalleryModelTest.three(), GalleryModelTest.NOW,
                    GalleryModelTest.TZ);
            g.useModel(m);
            final java.util.List<String> opened = new java.util.ArrayList<String>();
            g.opener = opened::add;
            GalleryModel.Intent export = m.export();
            assertEquals(GalleryModel.Intent.Kind.EXPORT, export.kind);
            g.apply(export);
            assertEquals(java.util.Collections.singletonList(export.id), opened);
            assertEquals("the viewer will open it in export mode", export.id, RewindViewerScreen.exportRequest());

            RewindViewerScreen.requestExport(null);
            g.apply(m.watch());
            assertEquals(2, opened.size());
            assertEquals("Watch asks for no export mode", null, RewindViewerScreen.exportRequest());
        } finally {
            RewindViewerScreen.requestExport(null);
        }
    }

    @Test
    public void productionWiringOpensThroughThePlaybackSession() throws Exception {
        java.io.File src = new java.io.File(PlaybackIsolationScanTest.mainRoot(),
                "com/bedwarsqol/rewind/watch/RewindGalleryScreen.java");
        String text = PlaybackIsolationScanTest.strip(PlaybackIsolationScanTest.read(src));
        assertTrue(text.contains("opener = RewindPlayback::open"));
    }

    @Test
    public void productionWiringReadsThePlaybackSession() {
        RewindGalleryScreen g = new RewindGalleryScreen(GalleryModel.Origin.TITLE, new GuiScreen() {
        });
        assertEquals("no recording plays in a unit test", RewindPlayback.active(), g.playing.getAsBoolean());
    }
}
