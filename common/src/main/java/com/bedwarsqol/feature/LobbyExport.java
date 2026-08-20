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
    /** Immutable for this JVM; null when identity cannot be validated. */
    private static final JvmWriterIdentity WRITER = JvmWriterIdentity.current();
    /** Per-JVM temp path; set once when identity is valid. */
    private static final File TEMP_FILE = tempFileFor(WRITER);

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
        if (WRITER == null || TEMP_FILE == null) return;
        try {
            JsonObject tree = GSON.toJsonTree(lobby).getAsJsonObject();
            tree.addProperty("jvmPid", WRITER.pid);
            tree.addProperty("jvmStartTimeMs", WRITER.startTimeMs);
            String body = tree.toString(); // seq-free, so it changes only when the roster does
            if (body.equals(lastBody)) return;
            seq++;
            tree.addProperty("seq", seq);
            writeAtomically(GSON.toJson(tree));
            lastBody = body;
        } catch (Throwable ignored) {
            deleteOwnedTempBestEffort();
            // never propagate into the game; the launcher degrades to its idle state on a missing file
        }
    }

    private static File tempFileFor(JvmWriterIdentity identity) {
        if (identity == null) return null;
        File dir = new File(System.getProperty("user.home"), ".cobblify");
        return new File(dir, identity.tempFileName());
    }

    private static void writeAtomically(String json) throws Exception {
        File dir = new File(System.getProperty("user.home"), ".cobblify");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File file = new File(dir, "lobby.json");
        File tmp = TEMP_FILE;
        if (tmp == null) return;
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

    /** Best-effort delete of this JVM's temp file only; used after a failed write. */
    static void deleteOwnedTempBestEffort() {
        File tmp = TEMP_FILE;
        if (tmp != null && tmp.isFile()) {
            try {
                tmp.delete();
            } catch (Throwable ignored) {
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

    /**
     * Launcher header for a queue/game snapshot. Maps Hypixel's sidebar {@code Mode:} value (and
     * {@link com.bedwarsqol.stats.BedwarsMode#label()} fallbacks) onto the demo titles:
     * {@code Solos}, {@code Doubles}, {@code 3v3v3v3}, {@code 4v4v4v4}, {@code 4v4}.
     * Recognized labels may carry a decorative, non-alphanumeric suffix from the scoreboard; that
     * suffix is discarded so a seasonal glyph cannot leak into the launcher title. {@code Overall}
     * and blank input are not a mode — returns null so the UI stays generic.
     */
    public static String dashboardModeLabel(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        if (isLabelWithDecoration(v, "overall")) return null;
        if (isLabelWithDecoration(v, "solo") || isLabelWithDecoration(v, "solos")) return "Solos";
        if (isLabelWithDecoration(v, "doubles")) return "Doubles";
        if (isLabelWithDecoration(v, "3v3v3v3")) return "3v3v3v3";
        if (isLabelWithDecoration(v, "4v4v4v4")) return "4v4v4v4";
        if (isLabelWithDecoration(v, "4v4")) return "4v4";
        return v;
    }

    /** Exact label, case-insensitive, optionally followed only by whitespace/symbol decoration. */
    private static boolean isLabelWithDecoration(String value, String label) {
        if (value.length() < label.length()
                || !value.regionMatches(true, 0, label, 0, label.length())) return false;
        for (int i = label.length(); i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) return false;
            i += Character.charCount(codePoint);
        }
        return true;
    }

    /** Root of {@code lobby.json}; {@code seq} and writer identity are injected by the writer. */
    public static final class Lobby {
        public final int v = 1;
        public long jvmPid;
        public long jvmStartTimeMs;
        public String context = "MENU";     // MENU | LOBBY | QUEUE | GAME
        public boolean inHypixel;
        public String self;
        public String mode;                  // Solos|Doubles|3v3v3v3|4v4v4v4|4v4|…; null in lobby/menu
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
