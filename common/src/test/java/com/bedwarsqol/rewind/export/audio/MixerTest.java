package com.bedwarsqol.rewind.export.audio;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link Mixer} (Rewind Export PLAN Solution 3, Phase 4; TASK S2, S3; review I2): where a sound lands, how loud it
 * is with distance, left and right, pitch and sample rate, clipping, and rendering in pieces.
 */
public class MixerTest {

    private static final long START = 2_000_000L;
    private static final short FULL = 32767;

    private final Map<String, Mixer.Pcm> sounds = new HashMap<>();
    private final Mixer.Source source = sounds::get;

    private static short[] constant(int frames, int channels, short v) {
        short[] s = new short[frames * channels];
        Arrays.fill(s, v);
        return s;
    }

    /** A sound at (x, y, z), the listener at the origin facing {@code yaw}. */
    private static SoundEvent ev(long us, String sound, double x, double y, double z, float volume, float pitch,
                                 float range, float yaw) {
        return new SoundEvent(us, sound, x, y, z, volume, pitch, range, 0, 0, 0, yaw);
    }

    private short[] render(Mixer m, long from, int frames) {
        short[] out = new short[frames * 2];
        m.render(from, frames, out);
        return out;
    }

    private static int countNonZero(short[] s) {
        int n = 0;
        for (short v : s) {
            if (v != 0) n++;
        }
        return n;
    }

    @Test
    public void anImpulseLandsOnItsRoundedSample() {
        sounds.put("imp", new Mixer.Pcm(48_000, 1, new short[]{FULL}));
        long[] times = {START, START + 1, START + 10, START + 11, START + 20_833, START + 1_234_567};
        for (long t : times) {
            Mixer m = new Mixer(START, source);
            assertTrue(m.add(ev(t, "imp", 0, 0, 0, 1f, 1f, 16f, 0f)));
            long expect = Math.round((t - START) * 48_000.0 / 1e6);
            assertEquals(expect, Mixer.sampleAt(START, t));
            short[] out = render(m, 0, 60_000);
            for (int i = 0; i < 60_000; i++) {
                boolean on = i == expect;
                assertEquals("t=" + t + " i=" + i, on, out[2 * i] != 0 || out[2 * i + 1] != 0);
            }
        }
    }

    @Test
    public void gainIsOneAtTheListenerHalfAtHalfTheRangeZeroAtAndBeyond() {
        assertEquals(1.0, Mixer.gain(ev(0, "x", 0, 0, 0, 1f, 1f, 16f, 0f)), 1e-9);
        assertEquals(0.5, Mixer.gain(ev(0, "x", 0, 0, 8, 1f, 1f, 16f, 0f)), 1e-9);
        assertEquals(0.5, Mixer.gain(ev(0, "x", 0, 8, 0, 1f, 1f, 16f, 0f)), 1e-9);
        assertEquals(0.0, Mixer.gain(ev(0, "x", 16, 0, 0, 1f, 1f, 16f, 0f)), 1e-9);
        assertEquals(0.0, Mixer.gain(ev(0, "x", 40, 0, 0, 1f, 1f, 16f, 0f)), 1e-9);
        assertEquals(0.25, Mixer.gain(ev(0, "x", 0, 0, 8, 0.5f, 1f, 16f, 0f)), 1e-9);

        // In the samples: a mono impulse at the listener keeps its full power (constant-power pan, centre).
        sounds.put("imp", new Mixer.Pcm(48_000, 1, new short[]{20_000}));
        double[] dist = {0, 8, 16, 30};
        double[] want = {20_000, 10_000, 0, 0};
        for (int k = 0; k < dist.length; k++) {
            Mixer m = new Mixer(START, source);
            m.add(ev(START, "imp", 0, 0, dist[k], 1f, 1f, 16f, 0f));
            short[] out = render(m, 0, 4);
            double power = Math.sqrt((double) out[0] * out[0] + (double) out[1] * out[1]);
            assertEquals("distance " + dist[k], want[k], power, 1.5);
        }
    }

    /** Left and right of one mono tone at (x, y, z) heard from the origin facing yaw, in dB (right minus left). */
    private double rightOverLeftDb(double x, double y, double z, float yaw) {
        sounds.put("tone", new Mixer.Pcm(48_000, 1, constant(4_800, 1, (short) 10_000)));
        Mixer m = new Mixer(START, source);
        m.add(ev(START, "tone", x, y, z, 1f, 1f, 16f, yaw));
        short[] out = render(m, 0, 100);
        double l = Math.abs(out[100]);
        double r = Math.abs(out[101]);
        return 20 * Math.log10(Math.max(r, 1e-3) / Math.max(l, 1e-3));
    }

    @Test
    public void monoSoundsArePannedByTheirDirectionFromTheListener() {
        // Yaw 0 faces +z, so the right hand points to -x.
        assertTrue(rightOverLeftDb(-5, 0, 0, 0f) >= 6);
        assertTrue(rightOverLeftDb(5, 0, 0, 0f) <= -6);
        assertEquals(0, rightOverLeftDb(0, 0, 5, 0f), 0.01);
        assertEquals(0, rightOverLeftDb(0, 0, -5, 0f), 0.01);
        assertEquals(0, rightOverLeftDb(0, 5, 0, 0f), 0.01);
        assertEquals(0, rightOverLeftDb(0.3, 5, 0.2, 0f), 0.01); // within half a block horizontally: centre
        // Yaw 90 faces -x, so the right hand points to -z.
        assertTrue(rightOverLeftDb(0, 0, -5, 90f) >= 6);
        assertTrue(rightOverLeftDb(0, 0, 5, 90f) <= -6);
        assertEquals(0, rightOverLeftDb(-5, 0, 0, 90f), 0.01);
        assertEquals(0, rightOverLeftDb(5, 0, 0, 90f), 0.01);
        assertEquals(0, rightOverLeftDb(0, -5, 0, 90f), 0.01);
        assertEquals(1.0, Mixer.pan(ev(0, "x", -5, 0, 0, 1f, 1f, 16f, 0f)), 1e-9);
        assertEquals(-1.0, Mixer.pan(ev(0, "x", 0, 0, 5, 1f, 1f, 16f, 90f)), 1e-9);
    }

    @Test
    public void pitchTwoHalvesTheLength() {
        sounds.put("tone", new Mixer.Pcm(48_000, 1, constant(4_800, 1, (short) 1_000)));
        Mixer m = new Mixer(START, source);
        m.add(ev(START, "tone", 0, 0, 0, 1f, 2f, 16f, 0f));
        short[] out = render(m, 0, 6_000);
        assertEquals(2_400 * 2, countNonZero(out));
        Mixer one = new Mixer(START, source);
        one.add(ev(START, "tone", 0, 0, 0, 1f, 1f, 16f, 0f));
        assertEquals(4_800 * 2, countNonZero(render(one, 0, 6_000)));
    }

    @Test
    public void aSlowerSampleRateKeepsItsLength() {
        sounds.put("slow", new Mixer.Pcm(22_050, 1, constant(22_050, 1, (short) 1_000)));
        Mixer m = new Mixer(START, source);
        m.add(ev(START, "slow", 0, 0, 0, 1f, 1f, 16f, 0f));
        int n = countNonZero(render(m, 0, 50_000)) / 2;
        assertEquals(48_000, n, 1);
    }

    @Test
    public void aStereoSourceIsNotPanned() {
        short[] lr = new short[2 * 100];
        for (int i = 0; i < 100; i++) {
            lr[2 * i] = 8_000;
            lr[2 * i + 1] = -4_000;
        }
        sounds.put("st", new Mixer.Pcm(48_000, 2, lr));
        Mixer m = new Mixer(START, source);
        m.add(ev(START, "st", -5, 0, 0, 1f, 1f, 16f, 0f)); // hard right if it were mono
        short[] out = render(m, 0, 50);
        double g = Mixer.gain(ev(START, "st", -5, 0, 0, 1f, 1f, 16f, 0f));
        assertEquals(Math.round(8_000 * g), out[20]);
        assertEquals(Math.round(-4_000 * g), out[21]);
    }

    @Test
    public void manyLoudSoundsNeverWrapAround() {
        sounds.put("hi", new Mixer.Pcm(48_000, 1, constant(1_000, 1, FULL)));
        sounds.put("lo", new Mixer.Pcm(48_000, 1, constant(1_000, 1, (short) -32768)));
        Mixer m = new Mixer(START, source);
        for (int i = 0; i < 20; i++) {
            m.add(ev(START, "hi", 0, 0, 0, 1f, 1f, 16f, 0f));
            m.add(ev(START + 50_000, "lo", 0, 0, 0, 1f, 1f, 16f, 0f));
        }
        short[] out = render(m, 0, 4_000);
        for (int i = 0; i < 1_000 * 2; i++) {
            assertEquals(FULL, out[i]);
        }
        for (int i = 2_400 * 2; i < 3_400 * 2; i++) {
            assertEquals(-32768, out[i]);
        }
    }

    @Test
    public void renderingInChunksEqualsRenderingInOneGo() {
        short[] tone = new short[30_000];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) (12_000 * Math.sin(i * 0.05));
        }
        sounds.put("a", new Mixer.Pcm(44_100, 1, tone));
        sounds.put("b", new Mixer.Pcm(22_050, 1, tone));
        SoundEvent[] evs = {
                ev(START + 3_000, "a", 3, 0, 4, 0.8f, 1.3f, 16f, 30f),
                ev(START + 15_500, "b", -6, 1, 2, 1f, 0.7f, 16f, 30f),
                ev(START + 16_700, "a", 0, 0, 0, 1f, 0.5f, 16f, 30f), // starts before a chunk, ends after it
                ev(START + 99_000, "b", 9, 0, -9, 1f, 2f, 20f, 30f),
        };
        Mixer whole = new Mixer(START, source);
        Mixer parts = new Mixer(START, source);
        for (SoundEvent e : evs) {
            whole.add(e);
            parts.add(e);
        }
        int total = 800 * 120;
        short[] a = render(whole, 0, total);
        short[] b = new short[total * 2];
        short[] chunk = new short[800 * 2];
        for (int c = 0; c < 120; c++) {
            parts.render((long) c * 800, 800, chunk);
            System.arraycopy(chunk, 0, b, c * 800 * 2, 800 * 2);
        }
        assertArrayEquals(a, b);
        // The voice that starts at 16,700 us (sample 802) runs on across the chunk boundary at 1,600 without a gap.
        int boundary = 1_600 * 2;
        assertTrue(b[boundary - 2] != 0 && b[boundary] != 0);
        assertTrue(Math.abs(b[boundary] - b[boundary - 2]) < 2_000);
    }

    @Test
    public void aSoundLoggedWithAnEarlierStartStillPlaysItsRemainingPart() {
        sounds.put("tone", new Mixer.Pcm(48_000, 1, constant(2_000, 1, (short) 1_000)));
        Mixer m = new Mixer(START, source);
        render(m, 0, 800);
        m.add(ev(START, "tone", 0, 0, 0, 1f, 1f, 16f, 0f)); // starts at sample 0, logged after 0..800 was rendered
        short[] out = render(m, 800, 2_000);
        assertEquals(1_200 * 2, countNonZero(out));
    }

    @Test
    public void anUnknownSoundIsLeftOutAndCounted() {
        Mixer m = new Mixer(START, source);
        assertFalse(m.add(ev(START, "nope", 0, 0, 0, 1f, 1f, 16f, 0f)));
        assertEquals(1, m.missing());
        assertEquals(0, countNonZero(render(m, 0, 100)));
    }
}
