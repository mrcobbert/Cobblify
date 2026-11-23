package com.bedwarsqol.rewind.export.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * The export's offline sound mix: 48 kHz stereo, 16 bit (Rewind Export PLAN Solution 3; TASK S2, S3; review I2).
 *
 * <ul>
 *   <li>A sound starting at {@code t} begins on sample {@link #sampleAt round((t - start) x 48000 / 1e6)}.</li>
 *   <li>Gain = {@code volume x max(0, 1 - d / range)}, {@code d} the distance to the listener in blocks.</li>
 *   <li>Mono sounds get constant-power pan: {@code p} = the dot product of the listener's right vector
 *       {@code (-cos yaw, 0, -sin yaw)} with the unit direction to the sound in the horizontal plane (+1 right, -1
 *       left, 0 ahead, behind, above, below, or within half a block); left {@code cos((p + 1) pi / 4)}, right
 *       {@code sin((p + 1) pi / 4)}. So a mono sound at the listener keeps its full power, split equally. Stereo
 *       sounds are not panned.</li>
 *   <li>Pitch and the file's sample rate are applied together by linear resampling: pitch 2 plays twice as fast.</li>
 *   <li>The sum is clamped to 16 bits, never wrapped.</li>
 * </ul>
 *
 * Every output sample is a function of its index alone, so rendering in chunks gives the same samples as rendering
 * in one go, as long as chunks are rendered in increasing order (voices that ended before a chunk are dropped).
 * Sounds are added as they are logged; one added after part of its time was rendered plays its remaining part.
 *
 * <p>Not thread-safe.
 */
public final class Mixer {

    /** Output sample rate (frames per second). */
    public static final int RATE = 48_000;

    /** The decoded sound for a variant name, or null when it cannot be had (the sound is left out). */
    public interface Source {
        Pcm pcm(String sound);
    }

    /** Decoded 16-bit PCM, interleaved. Immutable by contract (the array is not copied). */
    public static final class Pcm {
        private final int rate;
        private final int channels;
        private final short[] samples;

        public Pcm(int rate, int channels, short[] samples) {
            if (rate <= 0 || channels < 1 || channels > 2 || samples.length % channels != 0) {
                throw new IllegalArgumentException("pcm " + rate + " Hz, " + channels + " ch, " + samples.length);
            }
            this.rate = rate;
            this.channels = channels;
            this.samples = samples;
        }

        public int rate() {
            return rate;
        }

        public int channels() {
            return channels;
        }

        public int frames() {
            return samples.length / channels;
        }

        public short sample(int frame, int channel) {
            return samples[frame * channels + channel];
        }

        /** Memory held by the samples. */
        public long bytes() {
            return 2L * samples.length;
        }
    }

    private static final class Voice {
        final Pcm pcm;
        final long start;
        final long end;
        final double step;
        final double left;
        final double right;

        Voice(Pcm pcm, long start, long length, double step, double left, double right) {
            this.pcm = pcm;
            this.start = start;
            this.end = start + length;
            this.step = step;
            this.left = left;
            this.right = right;
        }
    }

    private final long startUs;
    private final Source source;
    private final List<Voice> voices = new ArrayList<>();
    private double[] acc = new double[0];
    private int missing;

    /**
     * @param startUs the time of sample 0 (the range start)
     * @param source  decoded sounds by variant name
     */
    public Mixer(long startUs, Source source) {
        this.startUs = startUs;
        this.source = source;
    }

    /** The sample a sound starting at {@code timeUs} begins on: {@code round((t - start) x 48000 / 1e6)}. */
    public static long sampleAt(long startUs, long timeUs) {
        return Math.floorDiv((timeUs - startUs) * RATE + 500_000L, 1_000_000L);
    }

    /** {@code volume x max(0, 1 - d / range)}, {@code d} the straight distance from the listener. */
    public static double gain(SoundEvent e) {
        if (e.range() <= 0) {
            return 0;
        }
        double dx = e.x() - e.listenerX();
        double dy = e.y() - e.listenerY();
        double dz = e.z() - e.listenerZ();
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return e.volume() * Math.max(0, 1 - d / e.range());
    }

    /**
     * Where a mono sound sits between left (-1) and right (+1): the listener's right vector
     * {@code (-cos yaw, 0, -sin yaw)} dotted with the horizontal unit direction to the sound; 0 within half a block.
     */
    public static double pan(SoundEvent e) {
        double dx = e.x() - e.listenerX();
        double dz = e.z() - e.listenerZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        if (h < 0.5) {
            return 0;
        }
        double yaw = Math.toRadians(e.listenerYaw());
        double p = (-Math.cos(yaw) * dx - Math.sin(yaw) * dz) / h;
        return Math.max(-1, Math.min(1, p));
    }

    /**
     * Adds a logged sound. Returns false, and counts it, when its file cannot be had; it is then left out.
     */
    public boolean add(SoundEvent e) {
        Pcm pcm = source.pcm(e.sound());
        if (pcm == null || pcm.frames() == 0) {
            missing++;
            return false;
        }
        double g = gain(e);
        if (g <= 0) {
            return true; // out of hearing range: nothing to mix
        }
        double left;
        double right;
        if (pcm.channels() == 1) {
            double a = (pan(e) + 1) * Math.PI / 4;
            left = g * Math.cos(a);
            right = g * Math.sin(a);
        } else {
            left = g;
            right = g;
        }
        double step = pcm.rate() * (double) e.pitch() / RATE;
        if (!(step > 0)) {
            missing++;
            return false;
        }
        voices.add(new Voice(pcm, sampleAt(startUs, e.timeUs()), length(pcm.frames(), step), step, left, right));
        return true;
    }

    /** Output samples a file of {@code frames} lasts at {@code step} file frames per output sample. */
    private static long length(int frames, double step) {
        long n = (long) Math.ceil(frames / step);
        while (n > 0 && (long) ((n - 1) * step) >= frames) {
            n--;
        }
        while ((long) (n * step) < frames) {
            n++;
        }
        return n;
    }

    /**
     * Renders samples {@code [fromSample, fromSample + frames)} into {@code out} as interleaved left/right
     * ({@code out.length >= 2 x frames}). Call with increasing {@code fromSample}.
     */
    public void render(long fromSample, int frames, short[] out) {
        if (acc.length < 2 * frames) {
            acc = new double[2 * frames];
        }
        Arrays.fill(acc, 0, 2 * frames, 0);
        long to = fromSample + frames;
        for (Iterator<Voice> it = voices.iterator(); it.hasNext(); ) {
            Voice v = it.next();
            if (v.end <= fromSample) {
                it.remove();
                continue;
            }
            long a = Math.max(fromSample, v.start);
            long b = Math.min(to, v.end);
            Pcm p = v.pcm;
            int ch = p.channels;
            int len = p.frames();
            short[] s = p.samples;
            for (long k = a; k < b; k++) {
                double pos = (k - v.start) * v.step;
                int i = (int) pos;
                if (i >= len) {
                    break;
                }
                double f = pos - i;
                int o = (int) (k - fromSample) * 2;
                int at = i * ch;
                boolean last = i + 1 >= len;
                if (ch == 1) {
                    double x0 = s[at];
                    double x1 = last ? 0 : s[at + 1];
                    double x = x0 + (x1 - x0) * f;
                    acc[o] += x * v.left;
                    acc[o + 1] += x * v.right;
                } else {
                    double l0 = s[at];
                    double r0 = s[at + 1];
                    double l1 = last ? 0 : s[at + 2];
                    double r1 = last ? 0 : s[at + 3];
                    acc[o] += (l0 + (l1 - l0) * f) * v.left;
                    acc[o + 1] += (r0 + (r1 - r0) * f) * v.right;
                }
            }
        }
        for (int i = 0; i < 2 * frames; i++) {
            long x = Math.round(acc[i]);
            out[i] = (short) (x > Short.MAX_VALUE ? Short.MAX_VALUE : x < Short.MIN_VALUE ? Short.MIN_VALUE : x);
        }
    }

    /** Sounds left out because their file could not be had. */
    public int missing() {
        return missing;
    }

    /** Sounds still to be mixed or still sounding. */
    public int voices() {
        return voices.size();
    }
}
