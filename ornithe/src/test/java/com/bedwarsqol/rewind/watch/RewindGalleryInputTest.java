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

    @Test
    public void productionWiringReadsThePlaybackSession() {
        RewindGalleryScreen g = new RewindGalleryScreen(GalleryModel.Origin.TITLE, new GuiScreen() {
        });
        assertEquals("no recording plays in a unit test", RewindPlayback.active(), g.playing.getAsBoolean());
    }
}
