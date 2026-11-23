package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.export.audio.Mixer;
import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Decodes a sound file for the export mix with the Ogg Vorbis decoder the game ships (JOrbis, {@code com.jcraft.jogg}
 * and {@code com.jcraft.jorbis} inside the {@code com.paulscode:codecjorbis} library on every 1.8.9 classpath, Ornithe
 * included), once per variant, into a cache of at most {@code capacityBytes} (least recently used out first) (Rewind
 * Export PLAN Solution 3; TASK S5).
 *
 * <p>A file that cannot be opened or decoded, or decodes to nothing, gives null, is counted, and is remembered as
 * failed so it is not tried again. Nothing here throws.
 *
 * <p>JOrbis is driven directly rather than through the library's {@code paulscode.sound.codecs.CodecJOrbis} wrapper,
 * which the plan named: the wrapper only opens URLs (an in-memory file would need a {@code URLStreamHandler}, which
 * the network guard in {@code QueuedWorkScanTest} rejects), and its {@code readAll} spins forever on a file that ends
 * before its last page (it takes only a 0-byte read as the end, never Java's -1; seen in {@code OggPcmTest}). Here the
 * whole file is read first (sound effects are small; at most {@link #MAX_FILE_BYTES}), its pages are checked to be
 * complete up to an end-of-stream page, and the decoded samples are cut to that page's granule position, the stream's
 * true length (the decoder returns whole blocks).
 */
public final class OggPcm {

    /** The cache size the plan gives (32 MB). */
    public static final long DEFAULT_CAPACITY = 32L << 20;
    /** Larger files are not decoded (music is streamed and never reaches the mix). */
    public static final int MAX_FILE_BYTES = 8 << 20;

    /** Opens the file's bytes; may throw or return null when it cannot be had. */
    public interface Opener {
        InputStream open() throws IOException;
    }

    private final long capacity;
    private final LinkedHashMap<String, Mixer.Pcm> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> failed = new HashSet<>();
    private long cachedBytes;
    private int decodes;
    private int hits;
    private int failures;

    public OggPcm(long capacityBytes) {
        this.capacity = capacityBytes;
    }

    /** The decoded sound for {@code key}, from the cache or decoded through {@code opener}; null on failure. */
    public synchronized Mixer.Pcm decode(String key, Opener opener) {
        Mixer.Pcm hit = cache.get(key);
        if (hit != null) {
            hits++;
            return hit;
        }
        if (failed.contains(key)) {
            return null;
        }
        Mixer.Pcm pcm;
        try {
            pcm = read(opener);
        } catch (IOException | RuntimeException | LinkageError e) {
            pcm = null;
        }
        if (pcm == null) {
            failures++;
            failed.add(key);
            return null;
        }
        decodes++;
        cache.put(key, pcm);
        cachedBytes += pcm.bytes();
        for (Iterator<Map.Entry<String, Mixer.Pcm>> it = cache.entrySet().iterator();
             cachedBytes > capacity && cache.size() > 1 && it.hasNext(); ) {
            Map.Entry<String, Mixer.Pcm> e = it.next();
            if (e.getKey().equals(key)) {
                continue;
            }
            cachedBytes -= e.getValue().bytes();
            it.remove();
        }
        return pcm;
    }

    private static Mixer.Pcm read(Opener opener) throws IOException {
        byte[] file = readFile(opener);
        if (file == null) {
            return null;
        }
        long granule = completeStreamLength(file);
        if (granule <= 0) {
            return null;
        }
        return vorbis(file, granule);
    }

    /** Decodes a complete Ogg Vorbis file held in memory; at most {@code maxFrames} frames. Null when it is not one. */
    private static Mixer.Pcm vorbis(byte[] file, long maxFrames) {
        SyncState sync = new SyncState();
        StreamState stream = new StreamState();
        Page page = new Page();
        Packet packet = new Packet();
        Info info = new Info();
        Comment comment = new Comment();
        DspState dsp = new DspState();
        Block block = new Block(dsp);
        sync.init();
        int at = sync.buffer(file.length);
        System.arraycopy(file, 0, sync.data, at, file.length);
        sync.wrote(file.length);
        if (sync.pageout(page) != 1) {
            return null;
        }
        stream.init(page.serialno());
        info.init();
        comment.init();
        if (stream.pagein(page) < 0 || stream.packetout(packet) != 1
                || info.synthesis_headerin(comment, packet) < 0) {
            return null;
        }
        for (int headers = 1; headers < 3; ) {
            int r = sync.pageout(page);
            if (r == 0) {
                return null; // the file ended before the comment and setup headers
            }
            if (r < 0) {
                continue;
            }
            stream.pagein(page);
            while (headers < 3) {
                r = stream.packetout(packet);
                if (r == 0) {
                    break;
                }
                if (r < 0 || info.synthesis_headerin(comment, packet) < 0) {
                    return null;
                }
                headers++;
            }
        }
        int channels = info.channels;
        if (channels < 1 || channels > 2 || info.rate <= 0 || maxFrames > Integer.MAX_VALUE / channels) {
            return null;
        }
        dsp.synthesis_init(info);
        block.init(dsp);
        short[] out = new short[(int) Math.min(maxFrames, 1 << 16) * channels];
        int frames = 0;
        float[][][] pcm = new float[1][][];
        int[] index = new int[channels];
        boolean eos = false;
        while (!eos) {
            int r = sync.pageout(page);
            if (r == 0) {
                break; // no complete page left (all bytes were fed at once, so this is the end)
            }
            if (r < 0) {
                continue; // a damaged stretch was skipped
            }
            stream.pagein(page);
            while (true) {
                r = stream.packetout(packet);
                if (r == 0) {
                    break;
                }
                if (r < 0) {
                    continue;
                }
                if (block.synthesis(packet) == 0) {
                    dsp.synthesis_blockin(block);
                }
                int n;
                while ((n = dsp.synthesis_pcmout(pcm, index)) > 0) {
                    int take = (int) Math.min(n, maxFrames - frames);
                    if ((frames + take) * channels > out.length) {
                        out = Arrays.copyOf(out, Math.max(out.length * 2, (frames + take) * channels));
                    }
                    for (int c = 0; c < channels; c++) {
                        float[] src = pcm[0][c];
                        int o = index[c];
                        for (int k = 0; k < take; k++) {
                            int v = (int) (src[o + k] * 32767f);
                            out[(frames + k) * channels + c] = (short) (v > 32767 ? 32767 : v < -32768 ? -32768 : v);
                        }
                    }
                    frames += take;
                    dsp.synthesis_read(n);
                }
            }
            if (page.eos() != 0) {
                eos = true;
            }
        }
        if (frames == 0) {
            return null;
        }
        return new Mixer.Pcm(info.rate, channels, Arrays.copyOf(out, frames * channels));
    }

    private static byte[] readFile(Opener opener) throws IOException {
        InputStream in = opener.open();
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) {
                out.write(b, 0, n);
                if (out.size() > MAX_FILE_BYTES) {
                    return null;
                }
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * The stream's length in samples per channel (the granule position of its end-of-stream page) when {@code file}
     * is a run of complete Ogg pages ending with one; otherwise -1.
     */
    static long completeStreamLength(byte[] file) {
        int i = 0;
        while (i + 27 <= file.length) {
            if (file[i] != 'O' || file[i + 1] != 'g' || file[i + 2] != 'g' || file[i + 3] != 'S') {
                return -1;
            }
            int segments = file[i + 26] & 0xff;
            int body = 0;
            if (i + 27 + segments > file.length) {
                return -1;
            }
            for (int k = 0; k < segments; k++) {
                body += file[i + 27 + k] & 0xff;
            }
            int end = i + 27 + segments + body;
            if (end > file.length) {
                return -1;
            }
            if ((file[i + 5] & 0x04) != 0) {
                long g = 0;
                for (int k = 7; k >= 0; k--) {
                    g = g << 8 | (file[i + 6 + k] & 0xff);
                }
                return g;
            }
            i = end;
        }
        return -1;
    }

    /** Files decoded (cache misses that succeeded). */
    public synchronized int decodes() {
        return decodes;
    }

    /** Requests served from the cache. */
    public synchronized int hits() {
        return hits;
    }

    /** Files that could not be opened or decoded. */
    public synchronized int failures() {
        return failures;
    }

    /** Memory held by the cache. */
    public synchronized long cachedBytes() {
        return cachedBytes;
    }
}
