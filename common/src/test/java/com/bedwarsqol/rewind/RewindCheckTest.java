package com.bedwarsqol.rewind;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The G6 reader check: one line of counts only, exit 0 only when every pass criterion holds. */
public class RewindCheckTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String FAKE_NAME = "FakePlayerZed";
    private static final String FAKE_CHAT = "SecretChatLine";
    private static final String FAKE_SERVER = "mini123Q";

    /** What goes into a fixture recording; each flag removes one pass criterion. */
    static final class Spec {
        boolean end = true;
        boolean net = true;
        boolean tick = true;
        boolean key = true;
        boolean secondSegmentStartsWithChat = false;
        boolean tornTail = false;
        /** Format v2: one record of each extension tag (and one unknown extension tag) in each TRACK block. */
        boolean ext = false;
    }

    private File write(Spec s) throws IOException {
        long origin = 0;
        RecordingFormatTest.FileBuilder fb = new RecordingFormatTest.FileBuilder();
        for (int seg = 0; seg < 2; seg++) {
            fb.block(RewindFormat.BLOCK_HEADER, seg, 0, BlockCodec.headerPayload(RewindFormat.SOURCE_CLIENT_SNAPSHOT,
                    Arrays.asList(RecordingFormatTest.joinGame(0),
                            RecordingFormatTest.packet(0x38, FAKE_NAME))), true);
            fb.block(RewindFormat.BLOCK_HEADER, seg, 0, BlockCodec.headerPayload(RewindFormat.SOURCE_CAPTURE_CARRY,
                    Collections.singletonList(RecordingFormatTest.packet(0x47, FAKE_SERVER))), false);
            if (s.net) {
                RecordChunk.Producer p = new RecordChunk.Producer(4096, 1, 1, origin);
                p.startSegment(seg, null, 0, 0);
                byte[] first = seg == 0 ? RecordingFormatTest.joinGame(0)
                        : s.secondSegmentStartsWithChat ? RecordingFormatTest.packet(0x02, FAKE_CHAT)
                        : RecordingFormatTest.respawn(-1);
                p.appendNet(origin + 10, first, 0, first.length);
                byte[] chat = RecordingFormatTest.packet(0x02, FAKE_NAME + ": " + FAKE_CHAT);
                p.appendNet(origin + 20, chat, 0, chat.length);
                RecordChunk c = RecordingFormatTest.handOff(p);
                fb.block(RewindFormat.BLOCK_NET, seg, c.startTimeUs() - origin, RecordingFormatTest.committed(c), true);
            }
            RecordChunk.Producer t = new RecordChunk.Producer(4096, 1, 1, origin);
            t.startSegment(seg, null, 0, 0);
            SelfTrackEncoder enc = new SelfTrackEncoder();
            if (s.tick) {
                int n = enc.tick(1, 2, 3, 4, 5, 6, 0, 0, -1, 0);
                t.appendTrack(origin + 5, RewindFormat.TAG_TICK, enc.buffer(), 0, n);
                n = enc.tick(1.5, 2, 3, 4, 5, 6, 0, 0, -1, 0);
                t.appendTrack(origin + 50, RewindFormat.TAG_TICK, enc.buffer(), 0, n);
            }
            int n = enc.camera(1f, 2f);
            t.appendTrack(origin + 60, RewindFormat.TAG_CAMERA, enc.buffer(), 0, n);
            n = enc.info(RewindFormat.INFO_MAP, FAKE_SERVER);
            t.appendTrack(origin + 61, RewindFormat.TAG_INFO, enc.buffer(), 0, n);
            if (s.ext) {
                for (RecordingFormatV2Test.Rec r : RecordingFormatV2Test.extRecords(0)) {
                    t.appendTrack(origin + 62, r.tag, r.appended, 0, r.appended.length);
                }
                ExtCodec c = new ExtCodec();
                int m = c.chatInput(FAKE_CHAT + FAKE_NAME, 0);
                t.appendTrack(origin + 63, 0x7F, c.buffer(), 0, m);
            }
            if (seg == 1) {
                n = enc.marker(RewindFormat.MARKER_DEATH);
                t.appendTrack(origin + 70, RewindFormat.TAG_MARKER, enc.buffer(), 0, n);
                if (s.key) {
                    n = enc.marker(RewindFormat.MARKER_KEY);
                    t.appendTrack(origin + 71, RewindFormat.TAG_MARKER, enc.buffer(), 0, n);
                }
            }
            RecordChunk c = RecordingFormatTest.handOff(t);
            fb.block(RewindFormat.BLOCK_TRACK, seg, c.startTimeUs() - origin, RecordingFormatTest.committed(c), false);
        }
        if (s.end) {
            fb.block(RewindFormat.BLOCK_INDEX, 0, 0, BlockCodec.indexPayload(
                    Collections.<RecordingReader.IndexEntry>emptyList()), false);
            String json = "{\"map\":\"" + FAKE_SERVER + "\",\"complete\":true,\"note\":\"" + FAKE_NAME + "\"}";
            fb.block(RewindFormat.BLOCK_END, 0, 0, json.getBytes(StandardCharsets.UTF_8), true);
        }
        byte[] bytes = fb.bytes();
        if (s.tornTail) {
            bytes = Arrays.copyOf(bytes, bytes.length - 3);
        }
        File f = tmp.newFile();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        return f;
    }

    private static String[] run(File f) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true);
        int code = RewindCheck.run(new String[]{f.getPath()}, ps);
        return new String[]{String.valueOf(code), new String(buf.toByteArray(), StandardCharsets.UTF_8)};
    }

    @Test
    public void aCompleteRecordingPassesWithCountsOnly() throws IOException {
        File f = write(new Spec());
        String[] r = run(f);
        assertEquals("0", r[0]);
        String line = r[1].trim();
        assertFalse(line.contains("\n"));
        assertTrue(line, line.matches("([a-zA-Z0-9]+=(\\d+|true|false))( [a-zA-Z0-9]+=(\\d+|true|false))*"));
        assertFalse(line.contains(FAKE_NAME));
        assertFalse(line.contains(FAKE_CHAT));
        assertFalse(line.contains(FAKE_SERVER));
        assertFalse(line.contains(f.getName()));
        assertTrue(line, line.contains("complete=true"));
        assertTrue(line, line.contains("tornTail=false"));
        assertTrue(line, line.contains("blocks=10"));
        assertTrue(line, line.contains("segments=2"));
        assertTrue(line, line.contains("segmentsStartingAtBoundary=2"));
        assertTrue(line, line.contains("headerSource0=2"));
        assertTrue(line, line.contains("headerSource1=2"));
        assertTrue(line, line.contains("netRecords=4"));
        assertTrue(line, line.contains("trackTicks=4"));
        assertTrue(line, line.contains("cameraSamples=2"));
        assertTrue(line, line.contains("markerKey=1"));
        assertTrue(line, line.contains("markerDeath=1"));
        assertTrue(line, line.contains("markerFinalKill=0"));
        assertTrue(line, line.contains("markerBedBroken=0"));
        assertTrue(line, line.contains("markerBedLost=0"));
        assertTrue(line, line.contains("pass=true"));

        RewindCheck.Summary s = RewindCheck.summarize(f);
        assertEquals(0, s.exitCode());
        assertEquals(line, s.line());
    }

    private void assertFails(Spec s, int bit) throws IOException {
        String[] r = run(write(s));
        int code = Integer.parseInt(r[0]);
        assertTrue("exit " + code + " lacks bit " + bit, (code & bit) != 0);
        assertTrue(r[1], r[1].contains("pass=false"));
        assertFalse(r[1].contains(FAKE_NAME));
    }

    @Test
    public void eachFailConditionHasItsOwnExitBit() throws IOException {
        Spec incomplete = new Spec();
        incomplete.end = false;
        assertFails(incomplete, RewindCheck.FAIL_INCOMPLETE);
        assertEquals(RewindCheck.FAIL_INCOMPLETE, Integer.parseInt(run(write(incomplete))[0]));

        Spec torn = new Spec();
        torn.tornTail = true;
        assertFails(torn, RewindCheck.FAIL_TORN_TAIL);

        Spec noNet = new Spec();
        noNet.net = false;
        assertFails(noNet, RewindCheck.FAIL_NO_NET);

        Spec noTick = new Spec();
        noTick.tick = false;
        assertFails(noTick, RewindCheck.FAIL_NO_TICK);
        assertEquals(RewindCheck.FAIL_NO_TICK, Integer.parseInt(run(write(noTick))[0]));

        Spec noKey = new Spec();
        noKey.key = false;
        assertFails(noKey, RewindCheck.FAIL_NO_KEY);
        assertEquals(RewindCheck.FAIL_NO_KEY, Integer.parseInt(run(write(noKey))[0]));

        Spec chatFirst = new Spec();
        chatFirst.secondSegmentStartsWithChat = true;
        String[] r = run(write(chatFirst));
        assertEquals(String.valueOf(RewindCheck.FAIL_SEGMENT_BOUNDARY), r[0]);
        assertTrue(r[1], r[1].contains("segments=2"));
        assertTrue(r[1], r[1].contains("segmentsStartingAtBoundary=1"));
        assertFalse(r[1].contains(FAKE_CHAT));
    }

    @Test
    public void unreadableInputsExitWithTheUsageCode() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true);
        assertEquals(RewindCheck.EXIT_UNREADABLE, RewindCheck.run(new String[0], ps));
        assertEquals(RewindCheck.EXIT_UNREADABLE,
                RewindCheck.run(new String[]{new File(tmp.getRoot(), "missing.cbr").getPath()}, ps));
        File notOurs = tmp.newFile();
        FileOutputStream out = new FileOutputStream(notOurs);
        try {
            out.write(new byte[64]);
        } finally {
            out.close();
        }
        int code = RewindCheck.run(new String[]{notOurs.getPath()}, ps);
        assertTrue((code & RewindCheck.FAIL_TORN_TAIL) != 0 && (code & RewindCheck.FAIL_INCOMPLETE) != 0);
    }

    /** Format v2: the line says the version and counts each extension tag; a v1 file says 1 and counts none. */
    @Test
    public void aV2RecordingPassesAndCountsEveryExtensionTag() throws IOException {
        Spec spec = new Spec();
        spec.ext = true;
        File f = write(spec);
        String[] r = run(f);
        assertEquals(r[1], "0", r[0]);
        String line = r[1].trim();
        assertTrue(line, line.matches("([a-zA-Z0-9]+=(\\d+|true|false))( [a-zA-Z0-9]+=(\\d+|true|false))*"));
        assertFalse(line.contains(FAKE_NAME));
        assertFalse(line.contains(FAKE_CHAT));
        assertFalse(line.contains(FAKE_SERVER));
        assertTrue(line, line.contains(" formatVersion=2"));
        for (int tag : RewindFormat.EXT_TAGS) {
            assertTrue(line, line.contains(" ext" + RewindFormat.extName(tag) + "=2 "));
        }
        assertTrue(line, line.contains(" extUnknown=2 "));
        assertTrue(line, line.contains("trackTicks=4"));
        assertTrue(line, line.contains("cameraSamples=2"));
        RewindCheck.Summary s = RewindCheck.summarize(f);
        assertEquals(2, s.extCount(RewindFormat.TAG_TAP));
        assertEquals(2, s.extUnknown);

        // The same file with a v1 header: version 1, nothing counted.
        File v1 = write(new Spec());
        byte[] b = java.nio.file.Files.readAllBytes(v1.toPath());
        RewindFormat.putShort(b, 8, 1);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(b, 0, 24);
        RewindFormat.putInt(b, 24, (int) crc.getValue());
        java.nio.file.Files.write(v1.toPath(), b);
        String[] r1 = run(v1);
        assertEquals(r1[1], "0", r1[0]);
        assertTrue(r1[1], r1[1].contains(" formatVersion=1"));
        assertTrue(r1[1], r1[1].contains(" extTickHead=0"));
        assertTrue(r1[1], r1[1].contains(" extUnknown=0"));
    }
}
