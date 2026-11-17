package com.bedwarsqol.rewind;

import com.bedwarsqol.rewind.CarryOverEncoder.Objective;
import com.bedwarsqol.rewind.CarryOverEncoder.Property;
import com.bedwarsqol.rewind.CarryOverEncoder.Score;
import com.bedwarsqol.rewind.CarryOverEncoder.Snapshot;
import com.bedwarsqol.rewind.CarryOverEncoder.TabEntry;
import com.bedwarsqol.rewind.CarryOverEncoder.Team;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Source-0 carry-over packets, byte for byte, in protocol-47 field order (vanilla 1.8.9
 * {@code writePacketData}): varint packet id, then the body. Big-endian numbers, LEB128 varints,
 * strings as varint UTF-8 length + bytes. Phase 6 cross-checks the same bytes with vanilla's
 * {@code readPacketData}.
 */
public class CarryOverEncoderTest {

    // ---- independent oracle: a DataOutputStream (big-endian) plus protocol varints/strings ----

    private static final class Out {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream data = new DataOutputStream(bytes);

        Out varInt(int v) throws IOException {
            while ((v & ~0x7F) != 0) {
                data.writeByte((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            data.writeByte(v);
            return this;
        }

        Out str(String s) throws IOException {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            varInt(b.length);
            data.write(b);
            return this;
        }

        Out b(int v) throws IOException { data.writeByte(v); return this; }
        Out i(int v) throws IOException { data.writeInt(v); return this; }
        Out l(long v) throws IOException { data.writeLong(v); return this; }
        Out f(float v) throws IOException { data.writeFloat(v); return this; }
        Out bool(boolean v) throws IOException { data.writeBoolean(v); return this; }

        byte[] get() throws IOException { data.flush(); return bytes.toByteArray(); }
    }

    @Test
    public void packetIdsAreProtocol47() {
        assertEquals(0x01, CarryOverEncoder.S01_JOIN_GAME);
        assertEquals(0x09, CarryOverEncoder.S09_HELD_ITEM);
        assertEquals(0x38, CarryOverEncoder.S38_PLAYER_LIST);
        assertEquals(0x39, CarryOverEncoder.S39_ABILITIES);
        assertEquals(0x3B, CarryOverEncoder.S3B_OBJECTIVE);
        assertEquals(0x3C, CarryOverEncoder.S3C_SCORE);
        assertEquals(0x3D, CarryOverEncoder.S3D_DISPLAY);
        assertEquals(0x3E, CarryOverEncoder.S3E_TEAMS);
    }

    @Test
    public void joinGame() throws IOException {
        // int entityId, u8 game type (bit 3 hardcore), i8 dimension, u8 difficulty, u8 max players,
        // string level type, bool reduced debug.
        byte[] expected = new Out().varInt(0x01).i(4242).b(0).b(-1).b(2).b(120).str("default").bool(true).get();
        assertArrayEquals(expected, CarryOverEncoder.joinGame(4242, 0, -1, 2, 120, "default", true));
        assertArrayEquals(new byte[]{0x01, 0x00, 0x00, 0x00, 0x07, 0x02, 0x01, 0x00, 0x14,
                        0x04, 'f', 'l', 'a', 't', 0x00},
                CarryOverEncoder.joinGame(7, 2, 1, 0, 20, "flat", false));
        // Max players above 255 is truncated to its low byte, as vanilla's writeByte does.
        assertEquals((byte) 0x2C, CarryOverEncoder.joinGame(1, 0, 0, 0, 300, "default", false)[8]);
    }

    @Test
    public void playerListAddPlayer() throws IOException {
        TabEntry plain = new TabEntry(0x0123456789ABCDEFL, 0xFEDCBA9876543210L, "FakeAlpha",
                Collections.<Property>emptyList(), 0, 35, null);
        TabEntry skinned = new TabEntry(1L, -2L, "FakeBravo",
                Arrays.asList(new Property("textures", "dGV4dHVyZQ==", "c2lnbmF0dXJl"),
                        new Property("extra", "v", null)),
                -1, 0, "{\"text\":\"[VIP] FakeBravo\"}");
        byte[] expected = new Out().varInt(0x38).varInt(0).varInt(2)
                .l(0x0123456789ABCDEFL).l(0xFEDCBA9876543210L).str("FakeAlpha").varInt(0)
                .varInt(0).varInt(35).bool(false)
                .l(1L).l(-2L).str("FakeBravo").varInt(2)
                .str("textures").str("dGV4dHVyZQ==").bool(true).str("c2lnbmF0dXJl")
                .str("extra").str("v").bool(false)
                .varInt(-1).varInt(0).bool(true).str("{\"text\":\"[VIP] FakeBravo\"}")
                .get();
        assertArrayEquals(expected, CarryOverEncoder.playerListAdd(Arrays.asList(plain, skinned)));
        // game type NOT_SET (-1) is a 5-byte varint, as vanilla writes it
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F},
                new Out().varInt(-1).get());
        // An empty tab list is still one well-formed S38.
        assertArrayEquals(new byte[]{0x38, 0x00, 0x00}, CarryOverEncoder.playerListAdd(Collections.<TabEntry>emptyList()));
    }

    @Test
    public void scoreboardObjectiveCreate() throws IOException {
        // string name, i8 mode (0 create), string display name, string render type
        byte[] expected = new Out().varInt(0x3B).str("sidebarObj").b(0).str("\u00a7e\u00a7lBED WARS").str("integer").get();
        assertArrayEquals(expected, CarryOverEncoder.objective(new Objective("sidebarObj", "\u00a7e\u00a7lBED WARS", "integer")));
    }

    @Test
    public void teamCreateWithMembers() throws IOException {
        // string name, i8 mode 0, display, prefix, suffix, i8 friendly flags, string name tag
        // visibility, i8 colour, varint member count, member strings.
        byte[] expected = new Out().varInt(0x3E).str("team_red").b(0).str("Red").str("\u00a7c").str("")
                .b(3).str("always").b(12).varInt(2).str("FakeAlpha").str("FakeBravo").get();
        assertArrayEquals(expected, CarryOverEncoder.team(new Team("team_red", "Red", "\u00a7c", "", 3, "always", 12,
                Arrays.asList("FakeAlpha", "FakeBravo"))));
        // colour -1 (no colour) is written as a signed byte
        byte[] none = CarryOverEncoder.team(new Team("t", "t", "", "", 0, "never", -1, Collections.<String>emptyList()));
        assertArrayEquals(new Out().varInt(0x3E).str("t").b(0).str("t").str("").str("").b(0).str("never").b(-1)
                .varInt(0).get(), none);
    }

    @Test
    public void scoreCreateOrUpdate() throws IOException {
        // string owner, action 0 (varint enum ordinal = one byte), string objective, varint value
        byte[] expected = new Out().varInt(0x3C).str("\u00a7aFakeLine").b(0).str("sidebarObj").varInt(300).get();
        assertArrayEquals(expected, CarryOverEncoder.score(new Score("\u00a7aFakeLine", "sidebarObj", 300)));
        assertArrayEquals(new Out().varInt(0x3C).str("x").b(0).str("o").varInt(-5).get(),
                CarryOverEncoder.score(new Score("x", "o", -5)));
    }

    @Test
    public void displaySlot() throws IOException {
        assertArrayEquals(new Out().varInt(0x3D).b(1).str("sidebarObj").get(), CarryOverEncoder.displaySlot(1, "sidebarObj"));
        assertArrayEquals(new Out().varInt(0x3D).b(18).str("x").get(), CarryOverEncoder.displaySlot(18, "x"));
    }

    @Test
    public void abilities() throws IOException {
        // i8 flags (1 invulnerable, 2 flying, 4 allow flying, 8 creative), f32 fly speed, f32 walk speed
        assertArrayEquals(new Out().varInt(0x39).b(0x0F).f(0.05f).f(0.1f).get(),
                CarryOverEncoder.abilities(true, true, true, true, 0.05f, 0.1f));
        assertArrayEquals(new Out().varInt(0x39).b(0x04).f(0.05f).f(0.1f).get(),
                CarryOverEncoder.abilities(false, false, true, false, 0.05f, 0.1f));
        assertArrayEquals(new Out().varInt(0x39).b(0x00).f(0.025f).f(0.13f).get(),
                CarryOverEncoder.abilities(false, false, false, false, 0.025f, 0.13f));
    }

    @Test
    public void heldSlot() {
        assertArrayEquals(new byte[]{0x09, 0x05}, CarryOverEncoder.heldSlot(5));
        assertArrayEquals(new byte[]{0x09, 0x00}, CarryOverEncoder.heldSlot(0));
    }

    @Test
    public void snapshotEncodesInOrderAndNeverAnS47() {
        Snapshot s = new Snapshot();
        s.entityId = 99;
        s.gameType = 0;
        s.dimension = 0;
        s.difficulty = 1;
        s.maxPlayers = 16;
        s.levelType = "default";
        s.reducedDebug = false;
        s.tab.add(new TabEntry(3L, 4L, "FakeCharlie", Collections.<Property>emptyList(), 0, 10, null));
        s.objectives.add(new Objective("o1", "One", "integer"));
        s.objectives.add(new Objective("o2", "Two", "hearts"));
        s.teams.add(new Team("t1", "T1", "", "", 0, "always", -1, Collections.singletonList("FakeCharlie")));
        s.scores.add(new Score("FakeCharlie", "o2", 20));
        s.displaySlots[0] = "o2";
        s.displaySlots[1] = "o1";
        s.allowFlying = true;
        s.flySpeed = 0.05f;
        s.walkSpeed = 0.1f;
        s.heldSlot = 3;

        List<byte[]> packets = CarryOverEncoder.encode(s);
        List<Integer> ids = new ArrayList<Integer>();
        for (byte[] p : packets) ids.add(p[0] & 0xFF);
        assertEquals(Arrays.asList(0x01, 0x38, 0x3B, 0x3B, 0x3E, 0x3C, 0x3D, 0x3D, 0x39, 0x09), ids);
        for (int id : ids) assertNotEquals(0x47, id);

        assertArrayEquals(CarryOverEncoder.joinGame(99, 0, 0, 1, 16, "default", false), packets.get(0));
        assertArrayEquals(CarryOverEncoder.playerListAdd(s.tab), packets.get(1));
        assertArrayEquals(CarryOverEncoder.objective(s.objectives.get(0)), packets.get(2));
        assertArrayEquals(CarryOverEncoder.objective(s.objectives.get(1)), packets.get(3));
        assertArrayEquals(CarryOverEncoder.team(s.teams.get(0)), packets.get(4));
        assertArrayEquals(CarryOverEncoder.score(s.scores.get(0)), packets.get(5));
        assertArrayEquals(CarryOverEncoder.displaySlot(0, "o2"), packets.get(6));
        assertArrayEquals(CarryOverEncoder.displaySlot(1, "o1"), packets.get(7));
        assertArrayEquals(CarryOverEncoder.abilities(false, false, true, false, 0.05f, 0.1f), packets.get(8));
        assertArrayEquals(CarryOverEncoder.heldSlot(3), packets.get(9));
    }

    @Test
    public void anEmptySnapshotStillStartsWithS01AndHoldsTheFixedPackets() {
        Snapshot s = new Snapshot();
        s.levelType = "default";
        List<byte[]> packets = CarryOverEncoder.encode(s);
        List<Integer> ids = new ArrayList<Integer>();
        for (byte[] p : packets) ids.add(p[0] & 0xFF);
        assertEquals(Arrays.asList(0x01, 0x38, 0x39, 0x09), ids);        // empty display slots emit nothing
        assertEquals(CarryOverEncoder.DISPLAY_SLOTS, s.displaySlots.length);
        assertEquals(19, CarryOverEncoder.DISPLAY_SLOTS);
    }

    @Test
    public void nullStringsEncodeAsEmpty() throws IOException {
        // A team with no prefix object, a null level type: vanilla would never hold null, but the
        // encoder must not throw at a world switch.
        assertArrayEquals(new Out().varInt(0x3E).str("t").b(0).str("").str("").str("").b(0).str("").b(0)
                        .varInt(0).get(),
                CarryOverEncoder.team(new Team("t", null, null, null, 0, null, 0, null)));
        assertArrayEquals(new Out().varInt(0x01).i(1).b(0).b(0).b(0).b(0).str("").bool(false).get(),
                CarryOverEncoder.joinGame(1, 0, 0, 0, 0, null, false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aStringOverVanillasLimitIsRejected() {
        char[] big = new char[32768];
        Arrays.fill(big, 'a');
        CarryOverEncoder.objective(new Objective(new String(big), "x", "integer"));
    }

    @Test
    public void nonAsciiStringsUseUtf8ByteLength() throws IOException {
        assertArrayEquals(new Out().varInt(0x3D).b(1).str("\u00a7\u00e9\u00fc").get(), CarryOverEncoder.displaySlot(1, "\u00a7\u00e9\u00fc"));
        assertEquals(1 + 1 + 1 + 6, CarryOverEncoder.displaySlot(1, "\u00a7\u00e9\u00fc").length);
    }
}
