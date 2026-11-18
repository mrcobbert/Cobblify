package com.bedwarsqol.rewind.watch;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Rewind's decoder for stored packets (PLAN Phase 3): varint id, vanilla's play-state class for that clientbound id,
 * {@code readPacketData}, and every byte consumed. Packets written by vanilla's own {@code writePacketData} come back
 * with equal fields; a trailing byte or an unknown id is rejected. Fake values only.
 */
public class VanillaPacketDecoderTest {

    @Test
    public void joinGameRoundTrips() throws Exception {
        byte[] bytes = encode(new S01PacketJoinGame(4242, WorldSettings.GameType.ADVENTURE, true, -1,
                EnumDifficulty.HARD, 16, WorldType.FLAT, true));
        S01PacketJoinGame p = (S01PacketJoinGame) VanillaPacketDecoder.decode(bytes, 0, bytes.length);
        assertEquals(4242, p.getEntityId());
        assertEquals(WorldSettings.GameType.ADVENTURE, p.getGameType());
        assertTrue(p.isHardcoreMode());
        assertEquals(-1, p.getDimension());
        assertEquals(EnumDifficulty.HARD, p.getDifficulty());
        assertEquals(16, p.getMaxPlayers());
        assertEquals(WorldType.FLAT, p.getWorldType());
        assertTrue(p.isReducedDebugInfo());
        assertArrayEquals(bytes, encode(p));
    }

    @Test
    public void respawnRoundTrips() throws Exception {
        byte[] bytes = encode(new S07PacketRespawn(1, EnumDifficulty.PEACEFUL, WorldType.DEFAULT,
                WorldSettings.GameType.SPECTATOR));
        S07PacketRespawn p = (S07PacketRespawn) VanillaPacketDecoder.decode(bytes, 0, bytes.length);
        assertEquals(1, p.getDimensionID());
        assertEquals(EnumDifficulty.PEACEFUL, p.getDifficulty());
        assertEquals(WorldType.DEFAULT, p.getWorldType());
        assertEquals(WorldSettings.GameType.SPECTATOR, p.getGameType());
        assertArrayEquals(bytes, encode(p));
    }

    @Test
    public void playerListAddRoundTrips() throws Exception {
        S38PacketPlayerListItem in = new S38PacketPlayerListItem(S38PacketPlayerListItem.Action.ADD_PLAYER,
                Collections.<EntityPlayerMP>emptyList());
        UUID one = new UUID(0x1111222233334444L, 0x5555666677778888L);
        UUID two = new UUID(-7L, 42L);
        in.getEntries().add(in.new AddPlayerData(new GameProfile(one, "FakePlayerOne"), 87,
                WorldSettings.GameType.SURVIVAL, new ChatComponentText("Fake One")));
        in.getEntries().add(in.new AddPlayerData(new GameProfile(two, "FakePlayerTwo"), 0,
                WorldSettings.GameType.SPECTATOR, null));
        byte[] bytes = encode(in);

        S38PacketPlayerListItem p = (S38PacketPlayerListItem) VanillaPacketDecoder.decode(bytes, 0, bytes.length);
        assertEquals(S38PacketPlayerListItem.Action.ADD_PLAYER, p.getAction());
        assertEquals(2, p.getEntries().size());
        S38PacketPlayerListItem.AddPlayerData a = p.getEntries().get(0);
        assertEquals(one, a.getProfile().getId());
        assertEquals("FakePlayerOne", a.getProfile().getName());
        assertEquals(87, a.getPing());
        assertEquals(WorldSettings.GameType.SURVIVAL, a.getGameMode());
        assertEquals("Fake One", a.getDisplayName().getUnformattedText());
        S38PacketPlayerListItem.AddPlayerData b = p.getEntries().get(1);
        assertEquals(two, b.getProfile().getId());
        assertEquals(WorldSettings.GameType.SPECTATOR, b.getGameMode());
        assertNull(b.getDisplayName());
        assertArrayEquals(bytes, encode(p));
    }

    /**
     * S21 cannot be built from a Chunk without a World, so its body is laid out as protocol 47 carries it (x, z,
     * ground-up, section mask, data); vanilla's {@code writePacketData} of the decoded packet gives the same bytes.
     */
    @Test
    public void chunkDataRoundTrips() throws Exception {
        byte[] data = new byte[4096 * 2 + 2048 + 2048 + 256]; // one section with sky light, plus biomes
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeVarIntToBuffer(0x21);
        buf.writeInt(3);
        buf.writeInt(-7);
        buf.writeBoolean(true);
        buf.writeShort(0x0001);
        buf.writeByteArray(data);
        byte[] bytes = bytes(buf);

        S21PacketChunkData p = (S21PacketChunkData) VanillaPacketDecoder.decode(bytes, 0, bytes.length);
        assertEquals(3, p.getChunkX());
        assertEquals(-7, p.getChunkZ());
        assertEquals(0x0001, p.getExtractedSize());
        assertArrayEquals(data, p.getExtractedDataBytes());
        assertArrayEquals("ground-up flag and every field written back the same", bytes, encode(p));
    }

    @Test
    public void titleRoundTrips() throws Exception {
        byte[] title = encode(new S45PacketTitle(S45PacketTitle.Type.TITLE, new ChatComponentText("FAKE TITLE")));
        S45PacketTitle t = (S45PacketTitle) VanillaPacketDecoder.decode(title, 0, title.length);
        assertEquals(S45PacketTitle.Type.TITLE, t.getType());
        assertEquals("FAKE TITLE", t.getMessage().getUnformattedText());
        assertArrayEquals(title, encode(t));

        byte[] times = encode(new S45PacketTitle(10, 70, 20));
        S45PacketTitle s = (S45PacketTitle) VanillaPacketDecoder.decode(times, 0, times.length);
        assertEquals(S45PacketTitle.Type.TIMES, s.getType());
        assertEquals(10, s.getFadeInTime());
        assertEquals(70, s.getDisplayTime());
        assertEquals(20, s.getFadeOutTime());
        assertArrayEquals(times, encode(s));
    }

    @Test
    public void decodesFromTheMiddleOfABuffer() throws Exception {
        byte[] packet = encode(new S45PacketTitle(S45PacketTitle.Type.SUBTITLE, new ChatComponentText("fake sub")));
        byte[] around = new byte[packet.length + 9];
        Arrays.fill(around, (byte) 0x7F);
        System.arraycopy(packet, 0, around, 5, packet.length);
        S45PacketTitle p = (S45PacketTitle) VanillaPacketDecoder.decode(around, 5, packet.length);
        assertEquals(S45PacketTitle.Type.SUBTITLE, p.getType());
        assertEquals("fake sub", p.getMessage().getUnformattedText());
    }

    @Test
    public void aTrailingByteIsRejected() throws Exception {
        byte[] ok = encode(new S07PacketRespawn(0, EnumDifficulty.EASY, WorldType.DEFAULT,
                WorldSettings.GameType.SURVIVAL));
        byte[] trailing = Arrays.copyOf(ok, ok.length + 1);
        assertRejected(trailing, 0, trailing.length);
    }

    @Test
    public void anUnknownIdIsRejected() {
        assertRejected(new byte[] {0x4A, 0x00}, 0, 2); // one past the last protocol-47 clientbound id (0x49)
        assertRejected(new byte[] {0x7F}, 0, 1);
    }

    @Test
    public void aCutOrEmptyPacketIsRejected() throws Exception {
        byte[] ok = encode(new S01PacketJoinGame(1, WorldSettings.GameType.SURVIVAL, false, 0, EnumDifficulty.EASY,
                8, WorldType.DEFAULT, false));
        assertRejected(ok, 0, ok.length - 3);
        assertRejected(new byte[0], 0, 0);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertRejected(byte[] buf, int off, int len) {
        try {
            Packet<?> p = VanillaPacketDecoder.decode(buf, off, len);
            fail("decoded " + p.getClass().getSimpleName() + " from bad bytes");
        } catch (IOException expected) {
            assertFalse(expected.getMessage() == null || expected.getMessage().isEmpty());
        }
    }

    /** Vanilla's encoding of {@code p}, id first, as a connection carries it decompressed. */
    static byte[] encode(Packet<?> p) throws IOException {
        Integer id = EnumConnectionState.PLAY.getPacketId(EnumPacketDirection.CLIENTBOUND, p);
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeVarIntToBuffer(id);
        p.writePacketData(buf);
        return bytes(buf);
    }

    private static byte[] bytes(PacketBuffer buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }
}
