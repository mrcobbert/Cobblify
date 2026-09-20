package com.bedwarsqol.feature;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HypixelLocationCodecTest {

    private static byte[] str(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[b.length + 1];
        out[0] = (byte) b.length;
        System.arraycopy(b, 0, out, 1, b.length);
        return out;
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    @Test
    public void registerPayloadMatchesModApiWireFormat() {
        // version 1, one entry, "hyevent:location" (16 bytes) -> version 1
        byte[] expected = cat(new byte[]{1, 1}, str("hyevent:location"), new byte[]{1});
        assertArrayEquals(expected, HypixelLocationCodec.registerPayload());
        assertEquals(20, HypixelLocationCodec.registerPayload().length);
    }

    @Test
    public void decodesBedwarsGameLocation() {
        byte[] payload = cat(new byte[]{1, 1}, str("mini121A"),
                new byte[]{1}, str("BEDWARS"),
                new byte[]{0},                       // no lobby name on a game server
                new byte[]{1}, str("BEDWARS_EIGHT_ONE"),
                new byte[]{1}, str("Lighthouse"));
        HypixelLocationCodec.Location loc = HypixelLocationCodec.decodeLocation(payload);
        assertEquals("mini121A", loc.serverName);
        assertEquals("BEDWARS", loc.serverType);
        assertNull(loc.lobbyName);
        assertEquals("BEDWARS_EIGHT_ONE", loc.mode);
        assertEquals("Lighthouse", loc.map);
    }

    @Test
    public void decodesLobbyWithoutMapOrMode() {
        byte[] payload = cat(new byte[]{1, 1}, str("bedwarslobby3"),
                new byte[]{1}, str("BEDWARS"), new byte[]{1}, str("bedwarslobby3"), new byte[]{0}, new byte[]{0});
        HypixelLocationCodec.Location loc = HypixelLocationCodec.decodeLocation(payload);
        assertEquals("bedwarslobby3", loc.lobbyName);
        assertNull(loc.mode);
        assertNull(loc.map);
    }

    @Test
    public void rejectsErrorFramesUnknownVersionsAndGarbage() {
        assertNull(HypixelLocationCodec.decodeLocation(new byte[]{0, 2}));           // success=false, reason 2
        assertNull(HypixelLocationCodec.decodeLocation(cat(new byte[]{1, 2}, str("x")))); // version 2
        assertNull(HypixelLocationCodec.decodeLocation(new byte[]{1}));              // truncated
        assertNull(HypixelLocationCodec.decodeLocation(new byte[]{1, 1, 40, 1, 2})); // string longer than buffer
        assertNull(HypixelLocationCodec.decodeLocation(new byte[0]));
        assertNull(HypixelLocationCodec.decodeLocation(null));
    }

    @Test
    public void multiByteVarIntsRoundTrip() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HypixelLocationCodec.writeVarInt(out, 300);
        assertArrayEquals(new byte[]{(byte) 0xAC, 0x02}, out.toByteArray());
        assertEquals(300, new HypixelLocationCodec.Reader(out.toByteArray()).varInt());
    }
}
