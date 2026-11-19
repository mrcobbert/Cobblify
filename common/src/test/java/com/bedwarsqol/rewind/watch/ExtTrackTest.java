package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Assume;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Phase 10: the per-segment, per-tag view of a recording's extension records that the v2 playback parts read, by
 * time, by tick and as "state at time T" after a seek (time-scoped records are not replayed on a seek).
 */
public class ExtTrackTest {

    private static void add(ExtRecords.Builder b, int segment, RecordingFixture.Track t) {
        byte[] body = RecordingFixture.extBody(t);
        b.add(segment, t.timeUs, t.tag, body, 0, body.length);
    }

    @Test
    public void everyTagLandsInItsOwnListPerSegmentInTimeOrderWhateverTheAddOrder() {
        ExtRecords.Builder b = new ExtRecords.Builder();
        // Segment 1's records first, then segment 0's, each list added back to front.
        List<RecordingFixture.Track> late = RecordingFixture.everyExtTag(5_000_000L, 1_000, 7);
        List<RecordingFixture.Track> early = RecordingFixture.everyExtTag(1_000_000L, 1_000, 3);
        List<RecordingFixture.Track> early2 = RecordingFixture.everyExtTag(2_000_000L, 1_000, 4);
        for (int i = late.size() - 1; i >= 0; i--) {
            add(b, 1, late.get(i));
        }
        for (int i = early2.size() - 1; i >= 0; i--) {
            add(b, 0, early2.get(i));
        }
        for (int i = early.size() - 1; i >= 0; i--) {
            add(b, 0, early.get(i));
        }
        ExtRecords r = b.build();
        ExtTrack x = ExtTrack.of(r);
        assertSame(r, x.records());
        assertEquals(2, x.segmentCount());
        for (int tag : RewindFormat.EXT_TAGS) {
            String name = RewindFormat.extName(tag);
            assertTrue(name, x.has(tag));
            assertEquals(name, 3, x.count(tag));
            assertEquals(name, 2, x.count(tag, 0));
            assertEquals(name, 1, x.count(tag, 1));
            int i0 = early.indexOf(find(early, tag));
            assertEquals(name, early.get(i0).timeUs, x.timeUs(tag, 0, 0));
            assertEquals(name, early2.get(i0).timeUs, x.timeUs(tag, 0, 1));
            assertEquals(name, late.get(i0).timeUs, x.timeUs(tag, 1, 0));
            for (int s = 0; s < 2; s++) {
                for (int j = 0; j < x.count(tag, s); j++) {
                    int rec = x.record(tag, s, j);
                    assertEquals(name, tag, r.tag(rec));
                    assertEquals(name, s, r.segmentIndex(rec));
                    assertEquals(name, r.timeUs(rec), x.timeUs(tag, s, j));
                }
            }
            // The body reached through the track is the one added.
            int rec = x.record(tag, 1, 0);
            byte[] want = RecordingFixture.extBody(late.get(i0));
            assertEquals(name, want.length, r.length(rec));
            for (int k = 0; k < want.length; k++) {
                assertEquals(name, want[k], r.buffer()[r.offset(rec) + k]);
            }
        }
        assertFalse(x.has(0x3F));
        assertEquals(0, x.count(RewindFormat.TAG_TICK, 0));
        assertEquals(0, x.count(RewindFormat.TAG_VIEW, 5));
    }

    private static RecordingFixture.Track find(List<RecordingFixture.Track> l, int tag) {
        for (RecordingFixture.Track t : l) {
            if (t.tag == tag) {
                return t;
            }
        }
        throw new AssertionError("tag " + tag);
    }

    private static ExtRecords fovAt(long... times) {
        ExtRecords.Builder b = new ExtRecords.Builder();
        ExtCodec c = new ExtCodec();
        for (int i = 0; i < times.length; i++) {
            add(b, 0, RecordingFixture.ext(times[i], RewindFormat.TAG_FOV, c, c.fov(70f + i)));
        }
        return b.build();
    }

    @Test
    public void indexAtIsTheLastRecordAtOrBeforeATimeAndFirstAfterTheNextOne() {
        ExtTrack x = ExtTrack.of(fovAt(100, 200, 200, 300));
        int f = RewindFormat.TAG_FOV;
        assertEquals(-1, x.indexAt(f, 0, 99));
        assertEquals(0, x.indexAt(f, 0, 100));
        assertEquals(0, x.indexAt(f, 0, 199));
        assertEquals("equal stamps: the later in file order", 2, x.indexAt(f, 0, 200));
        assertEquals(2, x.indexAt(f, 0, 299));
        assertEquals(3, x.indexAt(f, 0, 300));
        assertEquals(3, x.indexAt(f, 0, Long.MAX_VALUE));
        assertEquals(0, x.firstAfter(f, 0, 99));
        assertEquals(1, x.firstAfter(f, 0, 100));
        assertEquals(3, x.firstAfter(f, 0, 200));
        assertEquals(4, x.firstAfter(f, 0, 300));
        assertEquals(1, x.firstAtOrAfter(f, 0, 200));
        assertEquals(1, x.firstAtOrAfter(f, 0, 101));
        assertEquals(4, x.firstAtOrAfter(f, 0, 301));
        // Another segment or tag: nothing.
        assertEquals(-1, x.indexAt(f, 1, 1_000));
        assertEquals(0, x.firstAfter(f, 1, 1_000));
        assertEquals(-1, x.indexAt(RewindFormat.TAG_VIEW, 0, 1_000));
        assertEquals(-1, x.indexAt(f, -1, 1_000));
        // stateAt is the record index of indexAt.
        assertEquals(x.record(f, 0, 2), x.stateAt(f, 0, 250));
        assertEquals(-1, x.stateAt(f, 0, 50));
        ExtCodec.Fov fov = x.read(x.stateAt(f, 0, 250), new ExtCodec.Fov());
        assertEquals(72f, fov.degrees, 0f);
    }

    @Test
    public void aTicksRecordsAreThoseStampedFromItsHeadToTheNextHead() {
        // Heads at 1000, 1050, 1100 (v2 table); FOV records before tick 0, at a head, just before the next head, at
        // it, and after the last head. Tick 0 is the first tick fed, so it also holds what came before it (the
        // feeder's local phase does the same).
        TickTable table = TickTable.v2(1_000, new long[]{1_000, 1_050, 1_100}, new int[]{1, 1, 1},
                new int[]{0, 0, 0}, new int[]{0, 0, 0}, 3);
        ExtTrack x = ExtTrack.of(fovAt(900, 1_000, 1_049, 1_050, 1_120));
        int f = RewindFormat.TAG_FOV;
        assertEquals(0, table.tickOf(1_000));
        assertEquals(0, x.firstInTick(f, 0, table, 0));
        assertEquals(3, x.endOfTick(f, 0, table, 0));
        assertEquals(3, x.firstInTick(f, 0, table, 1));
        assertEquals(4, x.endOfTick(f, 0, table, 1));
        assertEquals("continuation tick after the last head", 4, x.firstInTick(f, 0, table, 2));
        assertEquals(5, x.endOfTick(f, 0, table, 2));
        assertEquals(5, x.firstInTick(f, 0, table, 3));
        assertEquals(5, x.endOfTick(f, 0, table, 3));
    }

    @Test
    public void stateAtAfterASeekStaysInsideTheSegment() {
        ExtRecords.Builder b = new ExtRecords.Builder();
        ExtCodec c = new ExtCodec();
        add(b, 0, RecordingFixture.ext(100, RewindFormat.TAG_VIEW, c, c.view(1, 0, RewindFormat.SCREEN_NONE)));
        add(b, 0, RecordingFixture.ext(200, RewindFormat.TAG_VIEW, c, c.view(2, RewindFormat.VIEW_F3,
                RewindFormat.SCREEN_CHAT)));
        add(b, 1, RecordingFixture.ext(500, RewindFormat.TAG_VIEW, c, c.view(0, 0, RewindFormat.SCREEN_NONE)));
        ExtTrack x = ExtTrack.of(b.build());
        int v = RewindFormat.TAG_VIEW;
        ExtCodec.View view = new ExtCodec.View();
        assertEquals(2, x.read(x.stateAt(v, 0, 450), view).perspective);
        assertEquals(RewindFormat.SCREEN_CHAT, view.screenKind);
        assertEquals("segment 1 before its first VIEW: nothing, not segment 0's", -1, x.stateAt(v, 1, 450));
        assertEquals(0, x.read(x.stateAt(v, 1, 500), view).perspective);
    }

    @Test
    public void keyedStateReadsEachInventorySlotHudModuleAndSettingOnItsOwn() {
        ExtRecords.Builder b = new ExtRecords.Builder();
        ExtCodec c = new ExtCodec();
        byte[] sword = RecordingFixture.itemSlot(267, 1, 0);
        byte[] wool = RecordingFixture.itemSlot(35, 64, 14);
        byte[] none = {(byte) 0xFF, (byte) 0xFF};
        int inv = RewindFormat.TAG_INV;
        add(b, 0, RecordingFixture.ext(100, inv, c, c.inv(3, sword, 0, sword.length)));
        add(b, 0, RecordingFixture.ext(100, inv, c, c.inv(RewindFormat.INV_CURSOR, wool, 0, wool.length)));
        add(b, 0, RecordingFixture.ext(300, inv, c, c.inv(3, none, 0, none.length)));
        c.beginSettings();
        c.settingFloat(RewindFormat.SETTING_FOV, 90f);
        c.settingString(RewindFormat.SETTING_GUI_ACCENT, "pale-blue");
        add(b, 0, RecordingFixture.ext(100, RewindFormat.TAG_SETTINGS, c, c.endSettings()));
        c.beginSettings();
        c.settingFloat(RewindFormat.SETTING_FOV, 100f);
        add(b, 0, RecordingFixture.ext(400, RewindFormat.TAG_SETTINGS, c, c.endSettings()));
        int hud = RewindFormat.TAG_HUD;
        add(b, 0, RecordingFixture.ext(150, hud, c,
                c.beginHud(RewindFormat.HUD_FPS, RewindFormat.HUD_FLAG_ON_SCREEN).putVarInt(144).end()));
        add(b, 0, RecordingFixture.ext(160, hud, c,
                c.beginHud(RewindFormat.HUD_PING, RewindFormat.HUD_FLAG_ON_SCREEN).putString("31").end()));
        add(b, 0, RecordingFixture.ext(250, hud, c, c.beginHud(RewindFormat.HUD_FPS, 0).end()));
        ExtTrack x = ExtTrack.of(b.build());

        ExtCodec.Inv slot = new ExtCodec.Inv();
        assertEquals(-1, x.keyedAt(inv, 3, 0, 99));
        assertEquals(3, x.read(x.keyedAt(inv, 3, 0, 299), slot).slot);
        assertEquals(sword.length, slot.slotLen);
        assertEquals("slot 3 emptied at 300", 2, x.read(x.keyedAt(inv, 3, 0, 300), slot).slotLen);
        assertEquals("the cursor is its own key", wool.length,
                x.read(x.keyedAt(inv, RewindFormat.INV_CURSOR, 0, 1_000), slot).slotLen);
        assertEquals(-1, x.keyedAt(inv, 4, 0, 1_000));

        ExtCodec.Settings s = new ExtCodec.Settings();
        int st = RewindFormat.TAG_SETTINGS;
        x.read(x.keyedAt(st, RewindFormat.SETTING_FOV, 0, 500), s);
        assertEquals(100f, s.floats[s.indexOf(RewindFormat.SETTING_FOV)], 0f);
        x.read(x.keyedAt(st, RewindFormat.SETTING_GUI_ACCENT, 0, 500), s);
        assertEquals("a key absent from the later record keeps the earlier value", "pale-blue",
                s.strings[s.indexOf(RewindFormat.SETTING_GUI_ACCENT)]);
        x.read(x.keyedAt(st, RewindFormat.SETTING_FOV, 0, 399), s);
        assertEquals(90f, s.floats[s.indexOf(RewindFormat.SETTING_FOV)], 0f);
        assertEquals(-1, x.keyedAt(st, RewindFormat.SETTING_GUI_SCALE, 0, 500));

        ExtCodec.Hud h = new ExtCodec.Hud();
        assertTrue(x.read(x.keyedAt(hud, RewindFormat.HUD_FPS, 0, 200), h).onScreen());
        assertFalse("FPS went off screen at 250", x.read(x.keyedAt(hud, RewindFormat.HUD_FPS, 0, 250), h).onScreen());
        assertEquals(RewindFormat.HUD_PING, x.read(x.keyedAt(hud, RewindFormat.HUD_PING, 0, 250), h).module);
        assertEquals(-1, x.keyedAt(hud, RewindFormat.HUD_CPS, 0, 250));

        // Tags without keys, and a key in another segment, give nothing.
        assertEquals(-1, x.keyedAt(RewindFormat.TAG_FOV, 0, 0, 1_000));
        assertEquals(-1, x.keyedAt(inv, 3, 1, 1_000));
    }

    @Test
    public void anEmptyTrackAnswersNothing() {
        assertSame(ExtTrack.EMPTY, ExtTrack.of(ExtRecords.EMPTY));
        assertSame(ExtTrack.EMPTY, ExtTrack.of(null));
        ExtTrack x = ExtTrack.EMPTY;
        assertSame(ExtRecords.EMPTY, x.records());
        assertEquals(0, x.segmentCount());
        for (int tag = 0; tag < 256; tag++) {
            assertFalse(x.has(tag));
            assertEquals(0, x.count(tag));
            assertEquals(0, x.count(tag, 0));
            assertEquals(-1, x.indexAt(tag, 0, Long.MAX_VALUE));
            assertEquals(0, x.firstAfter(tag, 0, Long.MIN_VALUE));
            assertEquals(-1, x.stateAt(tag, 0, Long.MAX_VALUE));
            assertEquals(-1, x.keyedAt(tag, 3, 0, Long.MAX_VALUE));
        }
    }

    @Test
    public void theCheckerSortsKnownWellFormedUnknownAndMalformedBodies() {
        ExtTrack.Checker ck = new ExtTrack.Checker();
        for (RecordingFixture.Track t : RecordingFixture.everyExtTag(0, 1, 5)) {
            byte[] body = RecordingFixture.extBody(t);
            assertEquals(RewindFormat.extName(t.tag), ExtTrack.Check.OK, ck.check(t.tag, body, 0, body.length));
            if (body.length > 1 && t.tag != RewindFormat.TAG_OUT && t.tag != RewindFormat.TAG_HUD) {
                assertEquals(RewindFormat.extName(t.tag) + " cut", ExtTrack.Check.MALFORMED,
                        ck.check(t.tag, body, 0, 1));
            }
        }
        assertEquals(ExtTrack.Check.UNKNOWN, ck.check(0x3F, new byte[]{1, 2}, 0, 2));
        assertEquals(ExtTrack.Check.UNKNOWN, ck.check(0xFF, new byte[0], 0, 0));
        assertEquals("INV slot 99", ExtTrack.Check.MALFORMED, ck.check(RewindFormat.TAG_INV,
                new byte[]{99, (byte) 0xFF, (byte) 0xFF}, 0, 3));
        assertEquals("SETTINGS type 9", ExtTrack.Check.MALFORMED, ck.check(RewindFormat.TAG_SETTINGS,
                new byte[]{1, 1, 9, 0}, 0, 4));
        assertEquals("HUD module 200", ExtTrack.Check.MALFORMED, ck.check(RewindFormat.TAG_HUD,
                new byte[]{(byte) 200, 1, 5}, 0, 3));
        assertEquals("HUD Ping value cut", ExtTrack.Check.MALFORMED, ck.check(RewindFormat.TAG_HUD,
                new byte[]{RewindFormat.HUD_PING, RewindFormat.HUD_FLAG_ON_SCREEN, 9, 'x'}, 0, 4));
        assertEquals("OUT with no id", ExtTrack.Check.MALFORMED, ck.check(RewindFormat.TAG_OUT, new byte[0], 0, 0));
    }

    @Test
    public void readingAllocatesNothing() {
        java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue(base instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) base;
        long self = Thread.currentThread().getId();
        ExtRecords.Builder b = new ExtRecords.Builder();
        for (int s = 0; s < 3; s++) {
            for (int i = 0; i < 50; i++) {
                for (RecordingFixture.Track t : RecordingFixture.everyExtTag(s * 10_000_000L + i * 50_000L, 1_000, i)) {
                    add(b, s, t);
                }
            }
        }
        ExtTrack x = ExtTrack.of(b.build());
        assertEquals(150, x.count(RewindFormat.TAG_FOV));
        TickTable table = TickTable.grid(0);
        ExtCodec.View view = new ExtCodec.View();
        long sum = 0;
        for (int round = 0; round < 2; round++) {
            long before = mx.getThreadAllocatedBytes(self);
            for (int i = 0; i < 20_000; i++) {
                long t = (i * 7_919L) % 30_000_000L;
                int s = (int) (t / 10_000_000L);
                sum += x.indexAt(RewindFormat.TAG_FOV, s, t);
                sum += x.firstAfter(RewindFormat.TAG_SOUND, s, t);
                sum += x.stateAt(RewindFormat.TAG_VIEW, s, t);
                sum += x.keyedAt(RewindFormat.TAG_INV, i % 36, s, t);
                sum += x.keyedAt(RewindFormat.TAG_SETTINGS, RewindFormat.SETTING_FOV, s, t);
                sum += x.keyedAt(RewindFormat.TAG_HUD, RewindFormat.HUD_FPS, s, t);
                sum += x.firstInTick(RewindFormat.TAG_DIG, s, table, t / 50_000L);
                sum += x.endOfTick(RewindFormat.TAG_DIG, s, table, t / 50_000L);
                int rec = x.stateAt(RewindFormat.TAG_VIEW, s, t);
                if (rec >= 0) {
                    sum += x.read(rec, view).perspective;
                }
            }
            long spent = mx.getThreadAllocatedBytes(self) - before;
            if (round == 1) {
                assertTrue("reading allocated " + spent + " B over 20k rounds", spent < 1024);
            }
        }
        assertTrue(sum != 0);
    }

    /**
     * Review code-2 O11 (corrupt files): per-segment index arrays are sized by the highest segment a record names. A
     * record in segment 19,999 of a crafted file cost about 99 MB; segments at or past {@link ExtTrack#MAX_SEGMENTS}
     * are not indexed (their records are skipped, as a malformed one is), so building the track stays small.
     */
    @Test
    public void aRecordInAFarSegmentIsNotIndexedAndCostsNoPerSegmentArrays() {
        java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue(base instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) base;
        long self = Thread.currentThread().getId();
        ExtCodec c = new ExtCodec();
        ExtRecords.Builder b = new ExtRecords.Builder();
        b.addEncoded(0, 1_000L, RewindFormat.TAG_FOV, c.buffer(), c.fov(70f));
        b.addEncoded(ExtTrack.MAX_SEGMENTS - 1, 2_000L, RewindFormat.TAG_FOV, c.buffer(), c.fov(71f));
        b.addEncoded(19_999, 3_000L, RewindFormat.TAG_FOV, c.buffer(), c.fov(72f));
        b.addEncoded(19_999, 3_000L, RewindFormat.TAG_INV, c.buffer(), c.inv(3, new byte[]{(byte) 0xFF, (byte) 0xFF},
                0, 2));
        ExtRecords records = b.build();
        long before = mx.getThreadAllocatedBytes(self);
        ExtTrack t = ExtTrack.of(records);
        long spent = mx.getThreadAllocatedBytes(self) - before;
        assertTrue("built in " + spent + " bytes", spent < 16L << 20);
        assertEquals(1, t.count(RewindFormat.TAG_FOV, 0));
        assertEquals("the last indexed segment", 1, t.count(RewindFormat.TAG_FOV, ExtTrack.MAX_SEGMENTS - 1));
        assertEquals(0, t.count(RewindFormat.TAG_FOV, 19_999));
        assertEquals(-1, t.keyedAt(RewindFormat.TAG_INV, 3, 19_999, 4_000L));
        assertEquals(ExtTrack.MAX_SEGMENTS, t.segmentCount());
    }
}
