package com.bedwarsqol.feature;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the Cobblify lobby dashboard file, {@code ~/.cobblify/lobby.json}, that the launcher polls to
 * render the roster. Pure IO/JSON: it takes a plain-Java {@link Lobby} snapshot (built on the client
 * thread by {@code LobbySnapshot}), serializes it to the shared contract shape with Gson, and writes it
 * atomically (temp file + rename) on a background thread so the reader never sees a half-written file.
 *
 * <p>Platform-neutral by construction — no Minecraft/Forge/Weave types — so it lives in {@code common/}
 * and is compiled once for both trees. Fail-soft everywhere: a submit is a cheap store on the client
 * thread, all disk work is swallowed on error, and the {@code seq} counter is bumped only when the
 * serialized body actually changed, so the launcher can cheaply detect real updates.
 */
public final class LobbyExport {

    /** Debounce floor between disk writes; the client submits a fresh snapshot every tick. */
    private static final long WRITE_INTERVAL_MS = 500L;

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private static volatile Lobby latest;
    private static volatile boolean pending;

    // Background-thread only.
    private static String lastBody = "";
    private static int seq;

    static {
        Thread t = new Thread(LobbyExport::writeLoop, "BedwarsQol-LobbyExport");
        t.setDaemon(true);
        t.start();
    }

    private LobbyExport() {}

    /** Store the newest snapshot for the writer thread. Cheap; safe to call every client tick. */
    public static void submit(Lobby lobby) {
        latest = lobby;
        pending = true;
    }

    /** Force the next debounce cycle to re-evaluate (e.g. a stat just resolved to a real value). */
    public static void markDirty() {
        pending = true;
    }

    private static void writeLoop() {
        while (true) {
            try {
                Thread.sleep(WRITE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!pending) continue;
            pending = false;
            Lobby snapshot = latest;
            if (snapshot != null) writeIfChanged(snapshot);
        }
    }

    private static void writeIfChanged(Lobby lobby) {
        try {
            JsonObject tree = GSON.toJsonTree(lobby).getAsJsonObject();
            String body = tree.toString(); // seq-free, so it changes only when the roster does
            if (body.equals(lastBody)) return;
            seq++;
            tree.addProperty("seq", seq);
            writeAtomically(GSON.toJson(tree));
            lastBody = body;
        } catch (Throwable ignored) {
            // never propagate into the game; the launcher degrades to its idle state on a missing file
        }
    }

    private static void writeAtomically(String json) throws Exception {
        File dir = new File(System.getProperty("user.home"), ".cobblify");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File file = new File(dir, "lobby.json");
        File tmp = new File(dir, "lobby.json.tmp");
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
        try {
            w.write(json);
        } finally {
            w.close();
        }
        try {
            Files.move(tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicUnsupported) {
            if (!tmp.renameTo(file)) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Strip Minecraft {@code §x} color codes from a rank label, leaving plain text (e.g. {@code [MVP+]}). */
    public static String stripColors(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                i++; // skip the code char too
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ---- contract DTOs (field names ARE the JSON keys; do not rename) ----

    /** Root of {@code lobby.json}; {@code seq} is injected by the writer, not this object. */
    public static final class Lobby {
        public final int v = 1;
        public String context = "MENU";     // MENU | LOBBY | QUEUE | GAME
        public boolean inHypixel;
        public String self;
        public Integer partyCount;           // null = unknown / feature off
        public List<Player> yourParty = new ArrayList<Player>();
        public List<Player> players = new ArrayList<Player>();
        public List<Team> teams = new ArrayList<Team>();
    }

    public static final class Team {
        public String name;                  // Red|Blue|Green|Yellow|Aqua|White|Pink|Gray|Unknown
        public List<Player> players = new ArrayList<Player>();

        public Team(String name) {
            this.name = name;
        }
    }

    public static final class Player {
        public String name;
        public String state = "LOADING";     // OK|NICKED|NEVER_PLAYED|ERROR|LOADING
        public boolean nicked;
        public String realName;              // de-nicked real IGN, else null
        public String rank = "";             // plain text, no color codes
        public double fkdr;
        public double wlr;
        public int finalKills;
        public double kd;
        public int seraphThreat = -1;        // -1 = unknown
        public List<String> seraphTags = new ArrayList<String>();
        public List<String> urchinTags = new ArrayList<String>();

        public Player(String name) {
            this.name = name;
        }
    }
}
