package com.bedwarsqol.rewind;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.buffer.Unpooled;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S39PacketPlayerAbilities;
import net.minecraft.network.play.server.S3BPacketScoreboardObjective;
import net.minecraft.network.play.server.S3CPacketUpdateScore;
import net.minecraft.network.play.server.S3DPacketDisplayScoreboard;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Source 0 of the carry-over HEADER (PLAN "Carry-over header"), built by {@link CarryOverSnapshot} from vanilla
 * client objects and read back by vanilla's own {@code readPacketData}: S01, one S38 ADD_PLAYER, S3B, S3E, S3C,
 * S3D, S39, S09, in that order, each consumed to its last byte and re-encoded by vanilla to the same bytes. Where
 * vanilla can build the same packet from the same objects (every type but S38), its bytes are ours exactly.
 * Source 0 never holds an S47: the tab header is frozen by the capture handler instead (round 2 B1).
 * Fake names and values only.
 */
public class CarryOverDecodeTest {

    private static final UUID ONE = new UUID(0x1111222233334444L, 0x5555666677778888L);
    private static final UUID TWO = new UUID(-7L, 42L);

    /** The vanilla objects a client holds at a boundary, all fake. */
    static final class Fixture {
        final Scoreboard board = new Scoreboard();
        final ScoreObjective sidebar;
        final ScoreObjective list;
        final ScorePlayerTeam red;
        final ScorePlayerTeam blue;
        final Score score;
        final Score hearts;
        final PlayerCapabilities caps = new PlayerCapabilities();
        final List<NetworkPlayerInfo> tab = new ArrayList<NetworkPlayerInfo>();

        Fixture() {
            sidebar = board.addScoreObjective("fakeSidebar", IScoreObjectiveCriteria.DUMMY);
            sidebar.setDisplayName("§e§lFAKE WARS");
            list = board.addScoreObjective("fakeHealth", IScoreObjectiveCriteria.DUMMY);
            list.setDisplayName("hp");
            list.setRenderType(IScoreObjectiveCriteria.EnumRenderType.HEARTS);
            board.setObjectiveInDisplaySlot(1, sidebar);
            board.setObjectiveInDisplaySlot(0, list);
            board.setObjectiveInDisplaySlot(5, sidebar); // a team-colour sidebar slot

            red = board.createTeam("fakeTeamRed");
            red.setTeamName("Red Team");
            red.setNamePrefix("§c");
            red.setNameSuffix(" §7[R]");
            red.setAllowFriendlyFire(false);
            red.setSeeFriendlyInvisiblesEnabled(true);
            red.setNameTagVisibility(Team.EnumVisible.HIDE_FOR_OTHER_TEAMS);
            red.setChatFormat(EnumChatFormatting.RED);
            board.addPlayerToTeam("FakePlayerOne", "fakeTeamRed");
            board.addPlayerToTeam("FakePlayerTwo", "fakeTeamRed");
            blue = board.createTeam("fakeTeamBlue"); // vanilla defaults: no members, colour RESET
            score = board.getValueFromObjective("FakePlayerOne", sidebar);
            score.setScorePoints(7);
            hearts = board.getValueFromObjective("FakePlayerTwo", list);
            hearts.setScorePoints(-3);

            caps.disableDamage = true;
            caps.isFlying = false;
            caps.allowFlying = true;
            caps.isCreativeMode = false;
            caps.setFlySpeed(0.07f);
            caps.setPlayerWalkSpeed(0.125f);

            S38PacketPlayerListItem outer = new S38PacketPlayerListItem();
            GameProfile p1 = new GameProfile(ONE, "FakePlayerOne");
            p1.getProperties().put("textures", new Property("textures", "ZmFrZVNraW4=", "ZmFrZVNpZw=="));
            p1.getProperties().put("fakeUnsigned", new Property("fakeUnsigned", "dmFsdWU="));
            tab.add(new NetworkPlayerInfo(outer.new AddPlayerData(p1, 87, WorldSettings.GameType.SURVIVAL,
                    new ChatComponentText("Fake é One"))));
            GameProfile p2 = new GameProfile(TWO, "FakePlayerTwo");
            tab.add(new NetworkPlayerInfo(outer.new AddPlayerData(p2, 0, WorldSettings.GameType.SPECTATOR, null)));
        }

        CarryOverEncoder.Snapshot snapshot() {
            CarryOverEncoder.Snapshot s = new CarryOverEncoder.Snapshot();
            CarryOverSnapshot.joinGame(s, 4242, WorldSettings.GameType.ADVENTURE, true, -1, EnumDifficulty.HARD, 16,
                    WorldType.FLAT, true);
            CarryOverSnapshot.tabList(s, tab);
            CarryOverSnapshot.scoreboard(s, board);
            CarryOverSnapshot.abilities(s, caps, 5);
            return s;
        }
    }

    // ------------------------------------------------------------------ vanilla codec helpers

    static int idOf(byte[] packet) {
        return RewindFormat.packetId(packet, 0, packet.length);
    }

    /** Vanilla's decoder for {@code bytes}: the packet class for its id, {@code readPacketData}, every byte consumed. */
    static Packet<?> decode(byte[] bytes) throws Exception {
        int id = idOf(bytes);
        Packet<?> p = EnumConnectionState.PLAY.getPacket(EnumPacketDirection.CLIENTBOUND, id);
        PacketBuffer buf = new PacketBuffer(Unpooled.wrappedBuffer(bytes));
        assertEquals(id, buf.readVarIntFromBuffer());
        p.readPacketData(buf);
        assertEquals("packet 0x" + Integer.toHexString(id) + " read to its last byte", 0, buf.readableBytes());
        return p;
    }

    /** Vanilla's encoding of {@code p}, id first, as a connection would carry it decompressed. */
    static byte[] encode(Packet<?> p) throws IOException {
        Integer id = EnumConnectionState.PLAY.getPacketId(EnumPacketDirection.CLIENTBOUND, p);
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeVarIntToBuffer(id);
        p.writePacketData(buf);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    static List<byte[]> ofId(List<byte[]> packets, int id) {
        List<byte[]> out = new ArrayList<byte[]>();
        for (byte[] p : packets) {
            if (idOf(p) == id) {
                out.add(p);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void everySourceZeroPacketReadsBackThroughVanillaAndReencodesToTheSameBytes() throws Exception {
        List<byte[]> packets = CarryOverSnapshot.packets(new Fixture().snapshot());
        assertTrue(packets.size() >= 8);
        for (byte[] p : packets) {
            assertArrayEquals("vanilla re-encodes 0x" + Integer.toHexString(idOf(p)) + " to our bytes", p,
                    encode(decode(p)));
        }
    }

    @Test
    public void orderIsS01ThenTabListThenScoreboardThenAbilitiesAndHeldSlot() {
        List<byte[]> packets = CarryOverSnapshot.packets(new Fixture().snapshot());
        List<Integer> ids = new ArrayList<Integer>();
        for (byte[] p : packets) {
            ids.add(idOf(p));
        }
        // 2 objectives, 2 teams, 2 scores, 3 occupied display slots
        assertEquals(Arrays.asList(0x01, 0x38, 0x3B, 0x3B, 0x3E, 0x3E, 0x3C, 0x3C, 0x3D, 0x3D, 0x3D, 0x39, 0x09), ids);
    }

    @Test
    public void sourceZeroNeverHoldsAnS47() {
        Fixture f = new Fixture();
        List<CarryOverEncoder.Snapshot> shots = Arrays.asList(f.snapshot(), new CarryOverEncoder.Snapshot());
        for (CarryOverEncoder.Snapshot s : shots) {
            List<byte[]> packets = CarryOverSnapshot.packets(s);
            assertEquals("starts with the synthetic S01", 0x01, idOf(packets.get(0)));
            for (byte[] p : packets) {
                assertNotEquals("no S47 in source 0", RewindFormat.PACKET_PLAYER_LIST_HEADER_FOOTER, idOf(p));
            }
        }
    }

    @Test
    public void s01CarriesThePreBoundaryWorldAsVanillaWritesIt() throws Exception {
        byte[] ours = CarryOverSnapshot.packets(new Fixture().snapshot()).get(0);
        S01PacketJoinGame p = (S01PacketJoinGame) decode(ours);
        assertEquals(4242, p.getEntityId());
        assertEquals(WorldSettings.GameType.ADVENTURE, p.getGameType());
        assertTrue(p.isHardcoreMode());
        assertEquals(-1, p.getDimension());
        assertEquals(EnumDifficulty.HARD, p.getDifficulty());
        assertEquals(16, p.getMaxPlayers());
        assertEquals(WorldType.FLAT, p.getWorldType());
        assertTrue(p.isReducedDebugInfo());
        assertArrayEquals(encode(new S01PacketJoinGame(4242, WorldSettings.GameType.ADVENTURE, true, -1,
                EnumDifficulty.HARD, 16, WorldType.FLAT, true)), ours);
    }

    @Test
    public void s38AddsEveryTabEntryWithProfilePropertiesGameTypePingAndDisplayName() throws Exception {
        Fixture f = new Fixture();
        byte[] ours = ofId(CarryOverSnapshot.packets(f.snapshot()), 0x38).get(0);
        S38PacketPlayerListItem p = (S38PacketPlayerListItem) decode(ours);
        assertEquals(S38PacketPlayerListItem.Action.ADD_PLAYER, p.getAction());
        assertEquals(2, p.getEntries().size());
        Map<UUID, S38PacketPlayerListItem.AddPlayerData> byId = new HashMap<UUID, S38PacketPlayerListItem.AddPlayerData>();
        for (S38PacketPlayerListItem.AddPlayerData d : p.getEntries()) {
            byId.put(d.getProfile().getId(), d);
        }
        for (NetworkPlayerInfo info : f.tab) {
            S38PacketPlayerListItem.AddPlayerData d = byId.get(info.getGameProfile().getId());
            assertEquals(info.getGameProfile().getName(), d.getProfile().getName());
            assertEquals(info.getGameType(), d.getGameMode());
            assertEquals(info.getResponseTime(), d.getPing());
            IChatComponent want = info.getDisplayName();
            if (want == null) {
                assertNull(d.getDisplayName());
            } else {
                assertEquals(want.getUnformattedText(), d.getDisplayName().getUnformattedText());
            }
            Collection<Property> wantProps = info.getGameProfile().getProperties().values();
            Collection<Property> gotProps = d.getProfile().getProperties().values();
            assertEquals(wantProps.size(), gotProps.size());
            for (Property w : wantProps) {
                boolean found = false;
                for (Property g : gotProps) {
                    found |= w.getName().equals(g.getName()) && w.getValue().equals(g.getValue())
                            && (w.hasSignature() ? w.getSignature().equals(g.getSignature()) : !g.hasSignature());
                }
                assertTrue("property " + w.getName(), found);
            }
        }
    }

    @Test
    public void scoreboardPacketsAreTheBytesVanillaBuildsFromTheSameBoard() throws Exception {
        Fixture f = new Fixture();
        List<byte[]> packets = CarryOverSnapshot.packets(f.snapshot());

        // Vanilla's S3B constructor takes the render type from the objective's criteria (the server's view); a
        // client objective holds the type the server sent, so the carried one is the objective's own.
        List<byte[]> got3b = ofId(packets, 0x3B);
        assertEquals(2, got3b.size());
        byte[] sidebar = null;
        byte[] hearts = null;
        for (byte[] b : got3b) {
            if (Arrays.equals(b, encode(new S3BPacketScoreboardObjective(f.sidebar, 0)))) {
                sidebar = b;
            } else {
                hearts = b;
            }
        }
        assertTrue("the sidebar objective is vanilla's bytes", sidebar != null);
        f.list.setRenderType(IScoreObjectiveCriteria.EnumRenderType.INTEGER);
        byte[] asInteger = encode(new S3BPacketScoreboardObjective(f.list, 0));
        byte[] tail = "hearts".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertArrayEquals("the hearts objective differs from vanilla's only in its render type",
                Arrays.copyOf(asInteger, asInteger.length - 8), Arrays.copyOf(hearts, hearts.length - 7));
        assertArrayEquals(tail, Arrays.copyOfRange(hearts, hearts.length - 6, hearts.length));
        assertArrayEquals("vanilla reads it as HEARTS and writes it back", hearts, encode(decode(hearts)));

        List<byte[]> want3e = new ArrayList<byte[]>();
        for (ScorePlayerTeam t : f.board.getTeams()) {
            want3e.add(encode(new S3EPacketTeams(t, 0)));
        }
        assertSameSet(want3e, ofId(packets, 0x3E));
        S3EPacketTeams red = null;
        for (byte[] b : ofId(packets, 0x3E)) {
            S3EPacketTeams t = (S3EPacketTeams) decode(b);
            if (t.getName().equals("fakeTeamRed")) {
                red = t;
            }
        }
        assertEquals("Red Team", red.getDisplayName());
        assertEquals("§c", red.getPrefix());
        assertEquals(" §7[R]", red.getSuffix());
        assertEquals(2, red.getFriendlyFlags());
        assertEquals("hideForOtherTeams", red.getNameTagVisibility());
        assertEquals(EnumChatFormatting.RED.getColorIndex(), red.getColor());
        assertEquals(2, red.getPlayers().size());

        List<byte[]> want3c = Arrays.asList(encode(new S3CPacketUpdateScore(f.score)),
                encode(new S3CPacketUpdateScore(f.hearts)));
        assertSameSet(want3c, ofId(packets, 0x3C));

        List<byte[]> want3d = new ArrayList<byte[]>();
        for (int slot = 0; slot < CarryOverEncoder.DISPLAY_SLOTS; slot++) {
            ScoreObjective o = f.board.getObjectiveInDisplaySlot(slot);
            if (o != null) {
                want3d.add(encode(new S3DPacketDisplayScoreboard(slot, o)));
            }
        }
        assertEquals(3, want3d.size());
        List<byte[]> got3d = ofId(packets, 0x3D);
        for (int i = 0; i < want3d.size(); i++) {
            assertArrayEquals("display slot packet " + i + " in slot order", want3d.get(i), got3d.get(i));
        }
    }

    @Test
    public void abilitiesAndHeldSlotAreTheBytesVanillaWrites() throws Exception {
        Fixture f = new Fixture();
        List<byte[]> packets = CarryOverSnapshot.packets(f.snapshot());
        byte[] s39 = ofId(packets, 0x39).get(0);
        assertArrayEquals(encode(new S39PacketPlayerAbilities(f.caps)), s39);
        S39PacketPlayerAbilities a = (S39PacketPlayerAbilities) decode(s39);
        assertTrue(a.isInvulnerable());
        assertFalse(a.isFlying());
        assertTrue(a.isAllowFlying());
        assertFalse(a.isCreativeMode());
        assertEquals(0.07f, a.getFlySpeed(), 0f);
        assertEquals(0.125f, a.getWalkSpeed(), 0f);
        byte[] s09 = ofId(packets, 0x09).get(0);
        assertArrayEquals(encode(new S09PacketHeldItemChange(5)), s09);
        assertEquals(5, ((S09PacketHeldItemChange) decode(s09)).getHeldItemHotbarIndex());
    }

    @Test
    public void anEmptyBoundaryStillDecodes() throws Exception {
        // The first boundary of a connection: no world, player or tab list yet.
        CarryOverEncoder.Snapshot s = new CarryOverEncoder.Snapshot();
        CarryOverSnapshot.joinGame(s, 0, null, false, 0, null, 0, null, false);
        CarryOverSnapshot.tabList(s, null);
        CarryOverSnapshot.scoreboard(s, null);
        CarryOverSnapshot.abilities(s, null, 0);
        List<byte[]> packets = CarryOverSnapshot.packets(s);
        for (byte[] p : packets) {
            assertArrayEquals(p, encode(decode(p)));
        }
        assertEquals(Arrays.asList(0x01, 0x38, 0x39, 0x09),
                Arrays.asList(idOf(packets.get(0)), idOf(packets.get(1)), idOf(packets.get(2)), idOf(packets.get(3))));
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static String hexAll(List<byte[]> l) {
        StringBuilder sb = new StringBuilder("[");
        for (byte[] b : l) sb.append(hex(b)).append(' ');
        return sb.append(']').toString();
    }

    private static void assertSameSet(List<byte[]> want, List<byte[]> got) {
        assertEquals(want.size(), got.size());
        List<byte[]> left = new ArrayList<byte[]>(got);
        for (byte[] w : want) {
            boolean removed = false;
            for (int i = 0; i < left.size() && !removed; i++) {
                if (Arrays.equals(w, left.get(i))) {
                    left.remove(i);
                    removed = true;
                }
            }
            assertTrue("vanilla's packet is among ours: " + hex(w) + " in " + hexAll(got), removed);
        }
    }
}
