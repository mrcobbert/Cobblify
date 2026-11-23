package com.bedwarsqol.rewind.export;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
