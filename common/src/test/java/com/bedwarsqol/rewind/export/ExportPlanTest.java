package com.bedwarsqol.rewind.export;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExportPlan} (Rewind Export PLAN Solution 1, Phase 1; TASK P1, S1): the frame count, the exact time of every
 * frame and the sound length.
 */
public class ExportPlanTest {

    private static final int[] RATES = {30, 60, 120, 240};
    private static final long US = 1_000_000L;

    @Test
    public void fifteenSecondsAtSixtyIsNineHundredFrames() {
        ExportPlan p = new ExportPlan(7_000_000L, 22_000_000L, 60);
        assertEquals(900, p.frames());
        assertEquals(7_000_000L, p.timeOfFrame(0));
        assertEquals(22_000_000L, p.pictureEndUs());
        assertEquals(15 * 48_000L, p.audioSamples());
    }

    @Test
    public void aRangeShorterThanOneFrameIsOneFrame() {
        assertEquals(1, new ExportPlan(1_000L, 1_000L + 16_000L, 60).frames());
        assertEquals(1, new ExportPlan(1_000L, 1_000L, 240).frames());
        assertEquals(1, new ExportPlan(0L, 33_332L, 30).frames());
        assertEquals(1, new ExportPlan(0L, 33_334L, 30).frames());
        assertEquals(2, new ExportPlan(0L, 66_667L, 30).frames());
    }

    @Test
    public void frameCountIsTheFloorOfLengthTimesRate() {
        Random r = new Random(11);
        for (int fps : RATES) {
            for (int i = 0; i < 1_000; i++) {
                long start = 5_000_000L + r.nextInt(1_000_000_000);
                long len = 1 + r.nextInt(600_000_000);
                ExportPlan p = new ExportPlan(start, start + len, fps);
                assertEquals(Math.max(1, len * fps / US), p.frames());
            }
        }
    }

    /** No drift and no overflow: frame i is at start + floor(i x 1e6 / fps), exactly one second per fps frames. */
    @Test
    public void timeOfFrameIsExactAtEveryRateUpToAMillionFrames() {
        long start = 1L << 40; // a long recording clock, far from zero
        for (int fps : RATES) {
            ExportPlan p = new ExportPlan(start, start + 1_000_001L * US / fps + US, fps);
            assertTrue(p.frames() > 1_000_000L);
            for (long i = 0; i <= 1_000_000L; i++) {
                long want = start + i * US / fps;
                if (p.timeOfFrame(i) != want) {
                    assertEquals(fps + " fps frame " + i, want, p.timeOfFrame(i));
                }
            }
            for (long i = 0; i + fps <= 1_000_000L; i += 997) {
                assertEquals(fps + " fps: fps frames are exactly one second", US,
                        p.timeOfFrame(i + fps) - p.timeOfFrame(i));
            }
            assertEquals(start + 1_000_000L * US / fps, p.timeOfFrame(1_000_000L));
        }
        ExportPlan far = new ExportPlan(0L, Long.MAX_VALUE / 2, 240);
        assertEquals("no overflow for a huge index", (Long.MAX_VALUE / 2 / US) * US,
                far.timeOfFrame(far.frames() / 240 * 240));
    }

    /** Review r2-I1: the sound track runs to the selected end, so it is longer than the picture by under a frame. */
    @Test
    public void soundRunsToTheSelectedEndAndIsLongerThanThePictureByLessThanOneFrame() {
        ExportPlan p = new ExportPlan(0L, 1_010_000L, 60);
        assertEquals(60, p.frames());
        assertEquals("the selected end is kept", 1_010_000L, p.endUs());
        assertEquals(1_000_000L, p.pictureEndUs());
        assertEquals(48_480L, p.audioSamples());

        Random r = new Random(5);
        for (int fps : RATES) {
            check(new ExportPlan(0L, US, fps));
            check(new ExportPlan(0L, 1_004_166L, fps)); // just short of the next 240 fps frame
            check(new ExportPlan(3L, 3L + 1_010_000L, fps));
            for (int i = 0; i < 1_000; i++) {
                long start = r.nextInt(2_000_000_000);
                long len = US + r.nextInt(600_000_000); // an export range is at least 1 s (TASK U3)
                check(new ExportPlan(start, start + len, fps));
            }
        }
    }

    private static void check(ExportPlan p) {
        // in samples: the picture is frames x 48000 / fps samples long (a whole number at every rate)
        long picture = p.frames() * ExportPlan.SAMPLE_RATE / p.fps();
        long perFrame = ExportPlan.SAMPLE_RATE / p.fps();
        long extra = p.audioSamples() - picture;
        assertTrue(p.fps() + " fps [" + p.startUs() + ", " + p.endUs() + "): sound shorter than picture by " + extra,
                extra >= 0);
        assertTrue(p.fps() + " fps [" + p.startUs() + ", " + p.endUs() + "): sound longer by " + extra
                + " samples, a frame is " + perFrame, extra < perFrame);
        assertTrue("S1: within 50 ms", extra * US / ExportPlan.SAMPLE_RATE < 50_000L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void anEndBeforeTheStartIsRefused() {
        new ExportPlan(10L, 9L, 60);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aRateOfZeroIsRefused() {
        new ExportPlan(0L, 10L, 0);
    }
}
