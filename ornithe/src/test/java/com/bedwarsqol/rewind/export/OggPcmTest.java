package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.export.audio.Mixer;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link OggPcm} (Rewind Export PLAN Solution 3, Phase 4; TASK S5): the game's own Ogg decoder turns a sound file into
 * PCM once, keeps it in a bounded cache, and reports a broken file as a failure instead of throwing.
 *
 * <p>The fixture is a synthetic 0.2 s 440 Hz sine (stereo, 44.1 kHz, Vorbis) made with ffmpeg; it is not a game asset.
 */
public class OggPcmTest {

    private static final String FIXTURE = "/rewind/export/sine-440hz-0.2s.ogg";

    private static byte[] fixture() throws IOException {
        InputStream in = OggPcmTest.class.getResourceAsStream(FIXTURE);
        assertNotNull(FIXTURE, in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        for (int n; (n = in.read(b)) > 0; ) {
            out.write(b, 0, n);
        }
        in.close();
        return out.toByteArray();
    }

    /** Counts how often the decoder opened the file. */
    private static final class Counting implements OggPcm.Opener {
        final byte[] bytes;
        int opened;

        Counting(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public InputStream open() {
            opened++;
            return new ByteArrayInputStream(bytes);
        }
    }

    @Test
    public void theFixtureDecodesToItsRateChannelsAndLength() throws IOException {
        OggPcm ogg = new OggPcm(OggPcm.DEFAULT_CAPACITY);
        Mixer.Pcm pcm = ogg.decode("test:sine.ogg", new Counting(fixture()));
        assertNotNull(pcm);
        assertEquals(44_100, pcm.rate());
        assertEquals(2, pcm.channels());
        assertEquals(0.2 * 44_100, pcm.frames(), 0.02 * 0.2 * 44_100);
        // The sine, not silence: ffmpeg measures the fixture's peak at 0.0898 of full scale (2,941) in both channels.
        int peakL = 0, peakR = 0;
        for (int i = 0; i < pcm.frames(); i++) {
            peakL = Math.max(peakL, Math.abs(pcm.sample(i, 0)));
            peakR = Math.max(peakR, Math.abs(pcm.sample(i, 1)));
        }
        assertEquals(2_941, peakL, 150);
        assertEquals(2_941, peakR, 150);
        // Smooth like a 440 Hz sine (byte order right): neighbours differ by at most ~2 pi 440 / 44100 of the peak.
        int maxStep = 0;
        for (int i = 1; i < pcm.frames(); i++) {
            maxStep = Math.max(maxStep, Math.abs(pcm.sample(i, 0) - pcm.sample(i - 1, 0)));
        }
        assertTrue("max step " + maxStep, maxStep < 300);
    }

    @Test
    public void aSecondRequestIsServedFromTheCache() throws IOException {
        OggPcm ogg = new OggPcm(OggPcm.DEFAULT_CAPACITY);
        Counting file = new Counting(fixture());
        Mixer.Pcm first = ogg.decode("test:sine.ogg", file);
        Mixer.Pcm second = ogg.decode("test:sine.ogg", file);
        assertNotNull(first);
        assertSame(first, second);
        assertEquals(1, file.opened);
        assertEquals(1, ogg.decodes());
        assertEquals(1, ogg.hits());
    }

    @Test
    public void theCacheDropsTheLeastRecentlyUsedPastItsCapacity() throws IOException {
        byte[] bytes = fixture();
        OggPcm probe = new OggPcm(OggPcm.DEFAULT_CAPACITY);
        long one = probe.decode("k", new Counting(bytes)).bytes();
        OggPcm ogg = new OggPcm(one * 2); // room for two
        Counting a = new Counting(bytes), b = new Counting(bytes), c = new Counting(bytes);
        ogg.decode("a", a);
        ogg.decode("b", b);
        ogg.decode("a", a); // a is now the most recent
        ogg.decode("c", c); // evicts b
        assertTrue(ogg.cachedBytes() <= one * 2);
        ogg.decode("a", a);
        ogg.decode("b", b);
        assertEquals(1, a.opened);
        assertEquals(2, b.opened);
    }

    /** The game's codec spins forever on a file that ends mid-stream if it is fed Java's end-of-stream (-1). */
    @Test(timeout = 20_000)
    public void aTruncatedFileIsAFailureNotAnException() throws IOException {
        byte[] bytes = fixture();
        int[] cuts = {0, 40, 3_000, 3_391, 3_391 + 400, bytes.length - 1};
        for (int cut : cuts) {
            OggPcm ogg = new OggPcm(OggPcm.DEFAULT_CAPACITY);
            assertNull("cut at " + cut, ogg.decode("cut" + cut, new Counting(Arrays.copyOf(bytes, cut))));
            assertEquals(1, ogg.failures());
        }
        // Complete pages, but the last one (all the audio) fails its checksum: the codec skips it and reaches the end.
        byte[] corrupt = bytes.clone();
        corrupt[corrupt.length - 10] ^= 0x55;
        OggPcm ogg = new OggPcm(OggPcm.DEFAULT_CAPACITY);
        assertNull(ogg.decode("corrupt", new Counting(corrupt)));
    }

    @Test
    public void aFileThatCannotBeOpenedIsAFailure() {
        OggPcm ogg = new OggPcm(OggPcm.DEFAULT_CAPACITY);
        assertNull(ogg.decode("missing", () -> {
            throw new IOException("not found");
        }));
        assertNull(ogg.decode("null", () -> null));
        assertEquals(2, ogg.failures());
    }
}
