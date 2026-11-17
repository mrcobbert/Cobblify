package com.bedwarsqol.rewind;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The {@code .json} gallery cache: missing keys load as defaults, unknown keys are ignored, every field round-trips. */
public class RecordingMetaTest {

    private static final Set<String> KEYS = new HashSet<String>(Arrays.asList(
            "format", "id", "complete", "recovered", "failed", "endedBy", "startedAtMs", "gameStartUs", "endUs",
            "lengthUs", "mode", "modeLabel", "map", "serverType", "classification", "result", "server", "starred",
            "keyMarked", "markers", "segments", "rawBytes", "storedBytes", "cobblifyVersion"));

    private static RecordingMeta full() {
        RecordingMeta m = new RecordingMeta();
        m.id = "20261117-201500-ab12";
        m.complete = true;
        m.recovered = true;
        m.failed = true;
        m.endedBy = "quit";
        m.startedAtMs = 1_763_400_000_000L;
        m.gameStartUs = 42_000_000L;
        m.endUs = 942_000_000L;
        m.lengthUs = 900_000_000L;
        m.mode = "BEDWARS_FOUR_FOUR";
        m.modeLabel = "4v4v4v4";
        m.map = "Fake Map";
        m.serverType = "BEDWARS";
        m.classification = "game";
        m.result = RecordingMeta.RESULT_WIN;
        m.server = RecordingMeta.SERVER_HYPIXEL;
        m.starred = true;
        m.keyMarked = true;
        m.markers.add(new RecordingMeta.Marker(43_000_000L, "KEY"));
        m.markers.add(new RecordingMeta.Marker(500_000_000L, "BED_BROKEN"));
        m.segments = 3;
        m.rawBytes = 12_345_678L;
        m.storedBytes = 3_456_789L;
        m.cobblifyVersion = "0.19.0";
        return m;
    }

    @Test
    public void missingKeysLoadAsDefaults() {
        RecordingMeta m = RecordingMeta.fromJson("{}");
        assertNotNull(m);
        assertEquals(RewindFormat.FORMAT_VERSION, m.format);
        assertNull(m.id);
        assertFalse(m.complete);
        assertFalse(m.recovered);
        assertFalse(m.failed);
        assertNull(m.endedBy);
        assertEquals(0L, m.startedAtMs);
        assertEquals(0L, m.gameStartUs);
        assertEquals(0L, m.endUs);
        assertEquals(0L, m.lengthUs);
        assertNull(m.mode);
        assertNull(m.map);
        assertEquals(RecordingMeta.RESULT_UNKNOWN, m.result);
        assertFalse(m.starred);
        assertFalse(m.keyMarked);
        assertNotNull(m.markers);
        assertTrue(m.markers.isEmpty());
        assertEquals(0, m.segments);

        RecordingMeta partial = RecordingMeta.fromJson("{\"id\":\"x\",\"starred\":true,\"markers\":null,\"result\":null}");
        assertEquals("x", partial.id);
        assertTrue(partial.starred);
        assertNotNull(partial.markers);
        assertEquals(RecordingMeta.RESULT_UNKNOWN, partial.result);
    }

    @Test
    public void unknownKeysAreIgnored() {
        RecordingMeta m = RecordingMeta.fromJson(
                "{\"keyMarked\":true,\"futureField\":{\"a\":[1,2]},\"another\":\"v\",\"segments\":2}");
        assertNotNull(m);
        assertTrue(m.keyMarked);
        assertEquals(2, m.segments);
    }

    @Test
    public void everyFieldRoundTripsUnderThePlannedKeys() {
        RecordingMeta m = full();
        String json = m.toJson();
        Set<String> top = new TreeSet<String>();
        Matcher k = Pattern.compile("\"([A-Za-z]+)\":").matcher(json);
        while (k.find()) {
            top.add(k.group(1));
        }
        top.remove("tUs");
        top.remove("kind");
        assertEquals(new TreeSet<String>(KEYS), top);

        RecordingMeta back = RecordingMeta.fromUtf8(m.toUtf8());
        assertNotNull(back);
        assertEquals(m.toJson(), back.toJson());
        assertEquals(2, back.markers.size());
        assertEquals(43_000_000L, back.markers.get(0).tUs);
        assertEquals("KEY", back.markers.get(0).kind);
        assertEquals("BED_BROKEN", back.markers.get(1).kind);
        assertEquals("Fake Map", back.map);
        assertEquals(1_763_400_000_000L, back.startedAtMs);
        assertTrue(back.complete && back.recovered && back.failed && back.starred && back.keyMarked);
        assertEquals(new String(m.toUtf8(), StandardCharsets.UTF_8), json);
    }

    @Test
    public void malformedOrNonObjectJsonIsNull() {
        assertNull(RecordingMeta.fromJson(""));
        assertNull(RecordingMeta.fromJson("null"));
        assertNull(RecordingMeta.fromJson("[1,2]"));
        assertNull(RecordingMeta.fromJson("{\"segments\":\"many\"}"));
        assertNull(RecordingMeta.fromJson("{\"id\":"));
        assertNull(RecordingMeta.fromJson(null));
        assertNull(RecordingMeta.fromUtf8(new byte[]{'{'}));
    }

    @Test
    public void markerKindNamesMatchTheTrackCodes() {
        assertEquals("KEY", RewindFormat.markerName(RewindFormat.MARKER_KEY));
        assertEquals("DEATH", RewindFormat.markerName(RewindFormat.MARKER_DEATH));
        assertEquals("FINAL_KILL", RewindFormat.markerName(RewindFormat.MARKER_FINAL_KILL));
        assertEquals("BED_BROKEN", RewindFormat.markerName(RewindFormat.MARKER_BED_BROKEN));
        assertEquals("BED_LOST", RewindFormat.markerName(RewindFormat.MARKER_BED_LOST));
        assertNull(RewindFormat.markerName(0));
        assertNull(RewindFormat.markerName(6));
    }
}
