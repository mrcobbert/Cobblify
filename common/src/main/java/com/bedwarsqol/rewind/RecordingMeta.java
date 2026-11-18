package com.bedwarsqol.rewind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-game metadata: the {@code <id>.json} gallery cache and the END block's JSON. Field names are the
 * on-disk keys (format v1). A missing key keeps the default below; unknown keys are ignored.
 *
 * <p>Uses Gson (shipped with Minecraft 1.8.9, as {@code LobbyExport} does). {@link RecordingReader} and
 * {@link RewindCheck} never load this class, so the G6 check still runs on the JDK alone.
 */
public final class RecordingMeta {

    public static final String RESULT_WIN = "WIN";
    public static final String RESULT_LOSS = "LOSS";
    public static final String RESULT_UNKNOWN = "UNKNOWN";
    public static final String SERVER_HYPIXEL = "HYPIXEL";
    public static final String SERVER_SINGLEPLAYER = "SINGLEPLAYER";
    /** {@link #mode} of a single-player recording. */
    public static final String MODE_SINGLEPLAYER = "SINGLEPLAYER";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** One marker: time on the recording's clock and its kind ({@link RewindFormat#markerName}). */
    public static final class Marker {
        public long tUs;
        public String kind;

        public Marker() {
        }

        public Marker(long tUs, String kind) {
            this.tUs = tUs;
            this.kind = kind;
        }
    }

    public int format = RewindFormat.FORMAT_VERSION;
    /** {@code yyyyMMdd-HHmmss-xxxx}; never a name, server or chat. */
    public String id;
    public boolean complete;
    public boolean recovered;
    public boolean failed;
    public String endedBy;
    public long startedAtMs;
    public long gameStartUs;
    public long endUs;
    /** endUs - gameStartUs. */
    public long lengthUs;
    /** Hypixel mode id, or {@link #MODE_SINGLEPLAYER}. */
    public String mode;
    public String modeLabel;
    public String map;
    public String serverType;
    public String classification;
    public String result = RESULT_UNKNOWN;
    /** {@link #SERVER_HYPIXEL} or {@link #SERVER_SINGLEPLAYER}; never the address. */
    public String server;
    public boolean starred;
    public boolean keyMarked;
    public List<Marker> markers = new ArrayList<Marker>();
    public int segments;
    public long rawBytes;
    public long storedBytes;
    public String cobblifyVersion;

    public RecordingMeta() {
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public byte[] toUtf8() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    /** Parses metadata JSON; null if it is empty, malformed or not an object. */
    public static RecordingMeta fromJson(String json) {
        if (json == null) {
            return null;
        }
        String t = json.trim();
        if (!t.startsWith("{")) {
            return null;
        }
        RecordingMeta m;
        try {
            m = GSON.fromJson(t, RecordingMeta.class);
        } catch (RuntimeException e) {
            return null;
        }
        if (m == null) {
            return null;
        }
        if (m.markers == null) {
            m.markers = new ArrayList<Marker>();
        }
        if (m.result == null) {
            m.result = RESULT_UNKNOWN;
        }
        return m;
    }

    public static RecordingMeta fromUtf8(byte[] json) {
        return json == null ? null : fromJson(new String(json, StandardCharsets.UTF_8));
    }
}
