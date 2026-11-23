package com.bedwarsqol.rewind.export;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link HelperProtocol} (Rewind Export PLAN Solution 4; HANDOFF Phase 3 protocol): packet headers {@code u8 kind,
 * u32 little-endian length}; status lines {@code {"ready":true,"protocol":1,...}}, {@code {"frames":n}},
 * {@code {"done":true,"frames":n}}, {@code {"error":"code"[,"status":n]}}; unknown keys are ignored, garbage is not a
 * status.
 */
public class HelperProtocolTest {

    @Test
    public void packetHeaders() {
        assertArrayEquals(new byte[]{1, 0x00, (byte) 0x90, (byte) 0x7E, 0x00}, HelperProtocol.header(1, 1920 * 1080 * 4));
        assertArrayEquals(new byte[]{2, (byte) 0x80, 0x0C, 0, 0}, HelperProtocol.header(2, 800 * 4));
        assertArrayEquals(new byte[]{3, 0, 0, 0, 0}, HelperProtocol.header(3, 0));
        assertArrayEquals(new byte[]{1, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x7F},
                HelperProtocol.header(1, Integer.MAX_VALUE));
    }

    @Test
    public void statusLines() {
        HelperProtocol.Status r = HelperProtocol.parse("{\"ready\":true,\"protocol\":1,\"encoder\":\"hardware\"}");
        assertEquals(HelperProtocol.Status.Kind.READY, r.kind);
        assertEquals(1, r.protocol);
        assertEquals("hardware", r.encoder);
        HelperProtocol.Status p = HelperProtocol.parse("{\"frames\":42}");
        assertEquals(HelperProtocol.Status.Kind.PROGRESS, p.kind);
        assertEquals(42, p.frames);
        HelperProtocol.Status d = HelperProtocol.parse(" {\"done\": true, \"frames\": 900} ");
        assertEquals(HelperProtocol.Status.Kind.DONE, d.kind);
        assertEquals(900, d.frames);
        HelperProtocol.Status e = HelperProtocol.parse("{\"error\":\"settings_refused\",\"status\":-12902}");
        assertEquals(HelperProtocol.Status.Kind.ERROR, e.kind);
        assertEquals("settings_refused", e.error);
        assertEquals(-12902, e.status);
        HelperProtocol.Status w = HelperProtocol.parse("{\"ready\":true,\"protocol\":2}");
        assertEquals(2, w.protocol);
    }

    @Test
    public void unknownKeysAreIgnoredAndGarbageIsNoStatus() {
        HelperProtocol.Status u = HelperProtocol.parse("{\"frames\":3,\"newKey\":[1,2,{\"x\":\"}\"}],\"s\":\"a\\\"b\"}");
        assertEquals(HelperProtocol.Status.Kind.PROGRESS, u.kind);
        assertEquals(3, u.frames);
        assertEquals(HelperProtocol.Status.Kind.OTHER, HelperProtocol.parse("{\"hello\":1}").kind);
        for (String g : new String[]{null, "", "garbage", "{", "{\"frames\":}", "[1,2]", "{\"frames\":3",
                "dyld: warning", "{\"done\":tru}"}) {
            assertNull(g, HelperProtocol.parse(g));
        }
    }

    @Test
    public void theEncodeArgumentListKeepsThePathWhole() {
        File out = new File("/Users/p/Movies/Cobblify/Solo \"x\" - 'y' $(z).partial.mp4");
        List<String> a = HelperProtocol.encodeArgs("/h/cobblify-encode", out, 1920, 1080, 60, "hevc", 9_500_000L);
        assertEquals(Arrays.asList("/h/cobblify-encode", "encode", "--out", out.getAbsolutePath(), "--width", "1920",
                "--height", "1080", "--fps", "60", "--codec", "hevc", "--bitrate", "9500000"), a);
    }

    /**
     * Rewind Export Phase 7: the probe's one line (HANDOFF Phase 3: {@code {"protocol":1,"videosDir":"..."|null,
     * "h264":[[w,h,fps]...],"hevc":[...]}}) gives the panel its matrix and the export folder its videos folder; the
     * window's size goes as {@code --size} only when the helper accepts it (even, 128 to 8192).
     */
    @Test
    public void theProbeLineAndArguments() {
        HelperProtocol.Probe p = HelperProtocol.parseProbe(
                "{\"protocol\":1,\"videosDir\":\"C:\\\\Users\\\\a\\\\Videos\",\"h264\":[[1280,720,30],[1280,720,60]],"
                        + "\"hevc\":[]}");
        assertEquals(1, p.protocol);
        assertEquals("C:\\Users\\a\\Videos", p.videosDir);
        assertEquals(2, p.h264.size());
        assertArrayEquals(new int[]{1280, 720, 60}, p.h264.get(1));
        assertEquals(0, p.hevc.size());
        ExportPanelModel.Matrix m = p.matrix();
        assertTrue(m.has("h264", 1280, 720, 30));
        assertFalse(m.any("hevc"));

        HelperProtocol.Probe q = HelperProtocol.parseProbe(
                " {\"protocol\": 1, \"videosDir\": null, \"extra\": {\"a\": [1]}, \"h264\": [], "
                        + "\"hevc\": [[1920, 1080, 240]]} ");
        assertNull(q.videosDir);
        assertArrayEquals(new int[]{1920, 1080, 240}, q.hevc.get(0));

        assertNull("garbage", HelperProtocol.parseProbe("probe failed"));
        assertNull("a row that is not three numbers", HelperProtocol.parseProbe(
                "{\"protocol\":1,\"videosDir\":null,\"h264\":[[1280,720]],\"hevc\":[]}"));
        assertNull("no rows at all", HelperProtocol.parseProbe("{\"protocol\":1,\"videosDir\":null}"));
        assertNull(HelperProtocol.parseProbe(null));

        assertEquals(Arrays.asList("/h/cobblify-encode", "probe", "--size", "1366x768"),
                HelperProtocol.probeArgs("/h/cobblify-encode", 1367, 769));
        assertEquals(Arrays.asList("/h/cobblify-encode", "probe"), HelperProtocol.probeArgs("/h/cobblify-encode", 100, 768));
        assertEquals(Arrays.asList("/h/cobblify-encode", "probe"), HelperProtocol.probeArgs("/h/cobblify-encode", 1366, 9000));
    }
}
