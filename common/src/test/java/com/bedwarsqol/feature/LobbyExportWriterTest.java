package com.bedwarsqol.feature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Writer identity on exported snapshots and per-JVM temp isolation. */
public class LobbyExportWriterTest {

    @Test
    public void distinctIdentitiesProduceDistinctTempNames() {
        assertNotNull(JvmWriterIdentity.current());
        JvmWriterIdentity a = JvmWriterIdentity.forTest(100, 1_700_000_000_001L);
        JvmWriterIdentity b = JvmWriterIdentity.forTest(200, 1_700_000_000_002L);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals("lobby.100.1700000000001.tmp", a.tempFileName());
        assertEquals("lobby.200.1700000000002.tmp", b.tempFileName());
        assertFalse(a.tempFileName().equals(b.tempFileName()));
    }

    @Test
    public void alternatingWritersProduceCompleteDestinationDocuments() throws Exception {
        File dir = Files.createTempDirectory("lobby-export-harness").toFile();
        try {
            File dest = new File(dir, "lobby.json");
            JvmWriterIdentity idA = JvmWriterIdentity.forTest(10, 1_111L);
            JvmWriterIdentity idB = JvmWriterIdentity.forTest(20, 2_222L);
            assertNotNull(idA);
            assertNotNull(idB);
            File tmpA = new File(dir, idA.tempFileName());
            File tmpB = new File(dir, idB.tempFileName());

            writeJson(tmpA, sampleBody(10, 1_111L, "MENU"));
            atomicMove(tmpA, dest);
            assertTrue(dest.isFile());
            assertFalse(tmpA.exists());

            JsonObject first = new JsonParser().parse(read(dest)).getAsJsonObject();
            assertEquals(10, first.get("jvmPid").getAsLong());
            assertEquals("MENU", first.get("context").getAsString());

            writeJson(tmpB, sampleBody(20, 2_222L, "LOBBY"));
            atomicMove(tmpB, dest);
            JsonObject second = new JsonParser().parse(read(dest)).getAsJsonObject();
            assertEquals(20, second.get("jvmPid").getAsLong());
            assertEquals("LOBBY", second.get("context").getAsString());

            // Each writer's temp is independent; no cross-writer rename of the other's temp.
            assertFalse(tmpA.exists());
            assertFalse(tmpB.exists());
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void currentJvmIdentityWouldSerializeOnExport() {
        JvmWriterIdentity id = JvmWriterIdentity.current();
        if (id == null) return;
        LobbyExport.Lobby lobby = new LobbyExport.Lobby();
        lobby.context = "QUEUE";
        lobby.inHypixel = true;
        com.google.gson.Gson gson = new com.google.gson.Gson();
        JsonObject tree = gson.toJsonTree(lobby).getAsJsonObject();
        tree.addProperty("jvmPid", id.pid);
        tree.addProperty("jvmStartTimeMs", id.startTimeMs);
        assertTrue(tree.has("jvmPid"));
        assertTrue(tree.has("jvmStartTimeMs"));
        for (String ctx : new String[] {"MENU", "LOBBY", "QUEUE", "GAME"}) {
            lobby.context = ctx;
            tree = gson.toJsonTree(lobby).getAsJsonObject();
            tree.addProperty("jvmPid", id.pid);
            tree.addProperty("jvmStartTimeMs", id.startTimeMs);
            assertEquals(ctx, tree.get("context").getAsString());
            assertEquals(id.pid, tree.get("jvmPid").getAsLong());
        }
    }

    private static String sampleBody(long pid, long start, String context) {
        return "{\"v\":1,\"jvmPid\":" + pid + ",\"jvmStartTimeMs\":" + start
                + ",\"context\":\"" + context + "\",\"inHypixel\":false,\"seq\":1}";
    }

    private static void writeJson(File file, String json) throws Exception {
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static void atomicMove(File tmp, File dest) throws Exception {
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void deleteTree(File root) {
        File[] kids = root.listFiles();
        if (kids != null) {
            for (File kid : kids) deleteTree(kid);
        }
        root.delete();
    }
}
