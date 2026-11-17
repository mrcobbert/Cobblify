package com.bedwarsqol.rewind;

import com.bedwarsqol.feature.HypixelLocationCodec.Location;
import com.bedwarsqol.rewind.RewindGameRules.Command;
import com.bedwarsqol.rewind.RewindGameRules.CommandType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bedwarsqol.rewind.RewindWriterTest.chat;
import static com.bedwarsqol.rewind.RewindWriterTest.packet;
import static com.bedwarsqol.rewind.RewindWriterTest.s01;
import static com.bedwarsqol.rewind.RewindWriterTest.s07;
import static com.bedwarsqol.rewind.RewindWriterTest.snapshot;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A15: the {@code .stats.json} file holds numbers and booleans only, under a fixed key schema. Chat
 * text, player names, map names and the server address that pass through the recorder never reach it.
 */
public class PacketStatsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String CHAT = "SecretChatLine";
    private static final String PLAYER = "AlicePlayer";
    private static final String SERVER = "mc.alice-secret.example:25565";
    private static final String SERVER_NAME = "fakeMini77Q";
    private static final String MAP = "SecretMapName";

    /** S38 ADD_PLAYER with one entry named {@link #PLAYER} (fake UUID). */
    private static byte[] playerList() {
        byte[] name = PLAYER.getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[1 + 1 + 1 + 16 + 1 + name.length + 1 + 1 + 1 + 1];
        int p = 0;
        b[p++] = 0x38;
        b[p++] = 0; // ADD_PLAYER
        b[p++] = 1; // one entry
        p += 16;    // uuid zeros
        b[p++] = (byte) name.length;
        System.arraycopy(name, 0, b, p, name.length);
        p += name.length;
        b[p++] = 0; // no properties
        b[p++] = 0; // game type
        b[p++] = 5; // ping
        b[p] = 0;   // no display name
        return b;
    }

    /** Runs the rules through a Bed Wars game on {@link #SERVER_NAME} and returns its CLOSE stats. */
    private static RewindGameRules.RunStats runStats() {
        RewindGameRules rules = new RewindGameRules();
        rules.onConnect(0, false, true);
        rules.onBoundary(1_000, 0);
        rules.onLocation(2_000, 0, new Location(SERVER_NAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", MAP));
        rules.tick(3_000, false, true);
        rules.onLocation(4_000, 0, new Location(SERVER, "MAIN", "lobby-" + PLAYER, null, MAP));
        List<Command> close = rules.onDisconnect(10_000_000L, false);
        assertEquals(CommandType.CLOSE, close.get(0).type);
        assertNotNull(close.get(0).stats);
        return close.get(0).stats;
    }

    private static void assertNoPrivateText(String json) {
        for (String secret : new String[]{CHAT, PLAYER, "Alice", SERVER, "alice-secret", SERVER_NAME, MAP,
                "BEDWARS", "\"text\""}) {
            assertFalse("stats must not hold '" + secret + "': " + json, json.contains(secret));
        }
    }

    /** Every key is in the fixed schema, every value a number or a boolean (arrays hold flat objects). */
    private static JsonObject assertSchema(String json) {
        JsonElement root = new JsonParser().parse(json);
        assertTrue(root.isJsonObject());
        JsonObject o = root.getAsJsonObject();
        Set<String> seen = new HashSet<String>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String key = e.getKey();
            seen.add(key);
            assertTrue("key in schema: " + key, PacketStats.KEYS.contains(key));
            List<String> itemKeys = PacketStats.ARRAY_ITEM_KEYS.get(key);
            if (itemKeys != null) {
                assertTrue(key + " is an array", e.getValue().isJsonArray());
                JsonArray a = e.getValue().getAsJsonArray();
                for (JsonElement item : a) {
                    assertTrue(item.isJsonObject());
                    Set<String> itemSeen = new HashSet<String>();
                    for (Map.Entry<String, JsonElement> f : item.getAsJsonObject().entrySet()) {
                        itemSeen.add(f.getKey());
                        assertNumberOrBoolean(key + "." + f.getKey(), f.getValue());
                    }
                    assertEquals("item keys of " + key, new HashSet<String>(itemKeys), itemSeen);
                }
            } else {
                assertNumberOrBoolean(key, e.getValue());
            }
        }
        assertEquals("every schema key present", new HashSet<String>(PacketStats.KEYS), seen);
        return o;
    }

    private static void assertNumberOrBoolean(String where, JsonElement v) {
        assertTrue(where + " is a primitive: " + v, v.isJsonPrimitive());
        JsonPrimitive p = v.getAsJsonPrimitive();
        assertTrue(where + " is a number or boolean: " + v, p.isNumber() || p.isBoolean());
    }

    private static JsonObject countOf(JsonObject root, String array, int id) {
        for (JsonElement e : root.getAsJsonArray(array)) {
            if (e.getAsJsonObject().get("id").getAsInt() == id) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    @Test
    public void statsHoldOnlyNumbersAndBooleansUnderTheFixedSchema() {
        PacketStats s = new PacketStats();
        long t = 1_000_000L;
        byte[] join = s01(9, 0);
        byte[] chat = chat(CHAT);
        byte[] players = playerList();
        s.addNet(t, join, 0, join.length);
        s.addNet(t + 10, chat, 0, chat.length);
        s.addNet(t + 20, players, 0, players.length);
        byte[] nether = s07(-1);
        s.addNet(t + 2_000_000L, nether, 0, nether.length);
        s.addNet(t + 2_000_010L, nether, 0, nether.length); // same dimension: not rebuilt
        s.addHeader(0, RewindFormat.SOURCE_CLIENT_SNAPSHOT, 3);
        s.addHeader(0, RewindFormat.SOURCE_CAPTURE_CARRY, 1);
        s.addSkipped(0x3F, 4, 400);
        s.setRestrictChannelSeen(true);
        s.setRunStats(runStats());
        s.setGame(t, 9_000_000L);
        s.setSegments(2);
        s.setBytes(1234, 567);

        String json = s.toJson();
        assertNoPrivateText(json);
        JsonObject o = assertSchema(json);

        assertEquals(1, countOf(o, "packets", 0x02).get("count").getAsLong());
        assertEquals(chat.length, countOf(o, "packets", 0x02).get("bytes").getAsLong());
        assertEquals(2, countOf(o, "packets", 0x07).get("count").getAsLong());
        assertEquals(4, countOf(o, "skipped", 0x3F).get("count").getAsLong());
        JsonArray b = o.getAsJsonArray("boundaries");
        assertEquals(3, b.size());
        assertEquals(1, b.get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals(0, b.get(0).getAsJsonObject().get("dimension").getAsInt());
        assertTrue(b.get(0).getAsJsonObject().get("rebuilt").getAsBoolean());
        assertEquals(-1, b.get(1).getAsJsonObject().get("dimension").getAsInt());
        assertTrue(b.get(1).getAsJsonObject().get("rebuilt").getAsBoolean());
        assertFalse(b.get(2).getAsJsonObject().get("rebuilt").getAsBoolean());
        assertEquals(3, o.getAsJsonArray("headerPackets").get(0).getAsJsonObject().get("source0").getAsInt());
        assertEquals(2, o.getAsJsonArray("locations").size());
        assertEquals(1, o.get("startSource").getAsInt());
        assertTrue(o.get("restrictChannelSeen").getAsBoolean());
        assertEquals(3, o.get("peakPacketsPerSecond").getAsLong());
        assertEquals(1234, o.get("rawBytes").getAsLong());
    }

    @Test
    public void writerStatsFileHoldsNoChatNamesMapOrAddress() throws Exception {
        File dir = new File(tmp.getRoot(), "rewind");
        RewindWriterTest.Fixture f = new RewindWriterTest.Fixture(dir, null, 1_000_000L);
        f.connect(false, 1024, 32);
        f.advance(1_000);
        f.netBoundary(0, RewindWriterTest.s47(PLAYER + " " + SERVER), s01(9, 0));
        f.clientBoundary(0, snapshot(0));
        f.submit(f.rules.onLocation(f.clock.nowUs, 0,
                new Location(SERVER_NAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", MAP)));
        f.advance(1_000);
        f.submit(f.rules.tick(f.clock.nowUs, false, true));
        f.net(chat(CHAT));
        f.net(playerList());
        f.net(chat(SERVER));
        f.feed.skipped(0x00, 12);
        f.feed.skipped(0x3F, 300);
        f.feed.restrictChannelSeen();
        f.feed.capture.add(150);
        f.feed.track.add(900);
        f.feed.camera.add(80);
        SelfTrackEncoder enc = new SelfTrackEncoder();
        int n = enc.info(RewindFormat.INFO_MAP, MAP);
        f.trackRecord(RewindFormat.TAG_INFO, enc.buffer(), n);
        n = enc.info(RewindFormat.INFO_MODE_LABEL, "Solo " + PLAYER);
        f.trackRecord(RewindFormat.TAG_INFO, enc.buffer(), n);
        for (int i = 0; i < 20; i++) {
            f.advance(50_000);
            f.net(packet(0x12, 20, i));
            f.tick(i);
        }
        f.step();
        f.submit(f.rules.onDisconnect(f.clock.nowUs, false));
        f.step();

        File cbr = RewindWriterTest.onlyCbr(dir);
        File stats = new File(dir, cbr.getName().replace(".cbr", ".stats.json"));
        assertTrue(stats.isFile());
        String json = new String(Files.readAllBytes(stats.toPath()), StandardCharsets.UTF_8);
        assertNoPrivateText(json);
        JsonObject o = assertSchema(json);
        assertEquals(2, countOf(o, "packets", 0x02).get("count").getAsLong());
        assertEquals(20, countOf(o, "packets", 0x12).get("count").getAsLong());
        assertEquals(1, countOf(o, "skipped", 0x00).get("count").getAsLong());
        assertEquals(12, countOf(o, "skipped", 0x00).get("bytes").getAsLong());
        assertTrue(o.get("restrictChannelSeen").getAsBoolean());
        assertEquals(150, o.get("captureNsPerPacketMax").getAsLong());
        assertEquals(1, o.getAsJsonArray("boundaries").size());
        assertEquals(1, o.get("segments").getAsInt());
        assertEquals(1, o.get("startSource").getAsInt());
        assertTrue(o.get("storedBytes").getAsLong() > 0);
        // The .json gallery cache does carry the map (it is not private); the stats file does not.
        assertEquals(MAP, RewindWriterTest.jsonOf(cbr).map);
    }
}
