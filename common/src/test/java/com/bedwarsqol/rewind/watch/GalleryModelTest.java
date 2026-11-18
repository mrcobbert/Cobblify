package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.gui.sheet.SheetFont;
import com.bedwarsqol.gui.sheet.TextMetrics;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** TASK A2, A3 and coordinator amendment 2: gallery rows, order, selection, star, armed delete, Done, keys. */
public class GalleryModelTest {

    static final TimeZone TZ = TimeZone.getTimeZone("UTC");
    /** 2026-11-14 22:00 UTC. */
    static final long NOW = 1_794_693_600_000L;
    static final int KEY_UP = 200, KEY_DOWN = 208, KEY_RETURN = 28, KEY_NUMPADENTER = 156, KEY_DELETE = 211,
            KEY_BACK = 14, KEY_ESCAPE = 1;

    /** Minecraft's font at one device px per su: 6 px a character, 4 a space. */
    static final TextMetrics MC = new TextMetrics() {
        @Override public float unit() { return 1f; }
        @Override public float width(String s, SheetFont font) {
            float w = 0;
            for (int i = 0; i < s.length(); i++) w += s.charAt(i) == ' ' ? 4 : 6;
            return w;
        }
        @Override public float ascent(SheetFont font) { return 7.5f; }
        @Override public float descent(SheetFont font) { return 1.5f; }
        @Override public float normalLineHeight(SheetFont font) { return 9f; }
        @Override public boolean minecraftFont() { return true; }
        @Override public float pixel(SheetFont font) { return 1f; }
    };

    static RecordingMeta meta(String id, long startedAtMs) {
        RecordingMeta m = new RecordingMeta();
        m.id = id;
        m.complete = true;
        m.startedAtMs = startedAtMs;
        m.server = RecordingMeta.SERVER_HYPIXEL;
        m.serverType = "BEDWARS";
        m.mode = "BEDWARS_EIGHT_ONE";
        m.gameStartUs = 10_000_000L;
        m.endUs = 10_000_000L + 400_000_000L;
        m.lengthUs = 400_000_000L;
        return m;
    }

    static List<RecordingMeta> three() {
        RecordingMeta a = meta("20261114-200000-aaaa", NOW - 2 * 3_600_000L);
        RecordingMeta b = meta("20261114-210000-bbbb", NOW - 3_600_000L);
        RecordingMeta c = meta("20261113-230000-cccc", NOW - 23 * 3_600_000L);
        return new ArrayList<RecordingMeta>(Arrays.asList(a, b, c));
    }

    static GalleryModel model(GalleryModel.Origin o, List<RecordingMeta> metas) {
        return new GalleryModel(o, metas, NOW, TZ);
    }

    static List<String> ids(GalleryModel m) {
        List<String> out = new ArrayList<String>();
        for (GalleryModel.Entry e : m.entries()) out.add(e.id);
        return out;
    }

    // ------------------------------------------------------------------ rows

    @Test
    public void newestFirst() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        assertEquals(Arrays.asList("20261114-210000-bbbb", "20261114-200000-aaaa", "20261113-230000-cccc"), ids(m));
        assertEquals(0, m.selected());
    }

    @Test
    public void sameStartTimeOrdersByIdNewestFirst() {
        List<RecordingMeta> l = Arrays.asList(meta("20261114-200000-0001", 5), meta("20261114-200000-0002", 5));
        assertEquals(Arrays.asList("20261114-200000-0002", "20261114-200000-0001"), ids(model(GalleryModel.Origin.TITLE, l)));
    }

    @Test
    public void replayVisitsUnclosedAndUnnamedRecordingsAreNotListed() {
        List<RecordingMeta> l = three();
        RecordingMeta replay = meta("20261114-215000-dddd", NOW - 600_000L);
        replay.serverType = "REPLAY";
        RecordingMeta open = meta("20261114-215500-eeee", NOW - 300_000L);
        open.complete = false;
        RecordingMeta noId = meta(null, NOW);
        l.add(replay);
        l.add(open);
        l.add(noId);
        l.add(null);
        GalleryModel m = model(GalleryModel.Origin.TITLE, l);
        assertEquals(3, m.entries().size());
        assertFalse(ids(m).contains("20261114-215000-dddd"));
        assertFalse(ids(m).contains("20261114-215500-eeee"));
    }

    @Test
    public void rowLabels() {
        List<RecordingMeta> l = three();
        l.get(1).result = "WIN";
        l.get(1).map = "Lighthouse";
        l.get(1).lengthUs = 406_600_000L;
        l.get(1).mode = "BEDWARS_FOUR_FOUR";
        l.get(2).result = "LOSS";
        GalleryModel m = model(GalleryModel.Origin.TITLE, l);
        GalleryModel.Entry newest = m.entries().get(0);
        assertEquals("Fours · Lighthouse", newest.title);
        assertEquals("WIN", newest.result);
        assertEquals("Today 21:00", newest.when);
        assertEquals("6:47", newest.length);
        GalleryModel.Entry mid = m.entries().get(1);
        assertEquals("Solo", mid.title);
        assertNull(mid.result);
        assertEquals("6:40", mid.length);
        GalleryModel.Entry old = m.entries().get(2);
        assertEquals("LOSS", old.result);
        assertEquals("Yesterday 23:00", old.when);
    }

    @Test
    public void markIconsArePlacedByTimeInsideTheGame() {
        List<RecordingMeta> l = Collections.singletonList(meta("20261114-210000-bbbb", NOW));
        RecordingMeta m0 = l.get(0);
        long s = m0.gameStartUs;
        m0.markers.add(new RecordingMeta.Marker(s + 100_000_000L, "DEATH"));
        m0.markers.add(new RecordingMeta.Marker(s, "BED_BROKEN"));
        m0.markers.add(new RecordingMeta.Marker(s + 400_000_000L, "FINAL_KILL"));
        m0.markers.add(new RecordingMeta.Marker(s - 1, "DEATH"));                 // before the game start
        m0.markers.add(new RecordingMeta.Marker(s + 400_000_001L, "DEATH"));      // after the end
        m0.markers.add(new RecordingMeta.Marker(s + 200_000_000L, "SOMETHING"));  // unknown kind
        m0.markers.add(null);
        List<GalleryModel.Mark> marks = model(GalleryModel.Origin.TITLE, l).entries().get(0).marks;
        assertEquals(3, marks.size());
        assertEquals(0f, marks.get(0).at, 0f);
        assertEquals(RewindFormat.MARKER_BED_BROKEN, marks.get(0).kind);
        assertEquals(0.25f, marks.get(1).at, 1e-6f);
        assertEquals(RewindFormat.MARKER_DEATH, marks.get(1).kind);
        assertEquals(1f, marks.get(2).at, 0f);
        assertEquals(RewindFormat.MARKER_FINAL_KILL, marks.get(2).kind);
    }

    // ------------------------------------------------------------------ selection and keys

    @Test
    public void upAndDownMoveTheSelectionWithinTheList() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        m.key(KEY_UP, NOW);
        assertEquals(0, m.selected());
        m.key(KEY_DOWN, NOW);
        m.key(KEY_DOWN, NOW);
        m.key(KEY_DOWN, NOW);
        assertEquals(2, m.selected());
        m.key(KEY_UP, NOW);
        assertEquals(1, m.selected());
    }

    @Test
    public void enterWatchesTheSelectedGame() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        m.key(KEY_DOWN, NOW);
        GalleryModel.Intent i = m.key(KEY_RETURN, NOW);
        assertSame(GalleryModel.Intent.Kind.WATCH, i.kind);
        assertEquals("20261114-200000-aaaa", i.id);
        assertSame(GalleryModel.Intent.Kind.WATCH, m.key(KEY_NUMPADENTER, NOW).kind);
        assertSame(GalleryModel.Intent.Kind.WATCH, m.watch().kind);
    }

    @Test
    public void doubleClickWatchesSingleClickSelects() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        assertSame(GalleryModel.Intent.Kind.NONE, m.clickRow(1, 1000).kind);
        assertEquals(1, m.selected());
        GalleryModel.Intent i = m.clickRow(1, 1300);
        assertSame(GalleryModel.Intent.Kind.WATCH, i.kind);
        assertEquals("20261114-200000-aaaa", i.id);
        // a third click right after is a new first click, not another double-click
        assertSame(GalleryModel.Intent.Kind.NONE, m.clickRow(1, 1450).kind);
        // too slow
        assertSame(GalleryModel.Intent.Kind.NONE, m.clickRow(2, 5000).kind);
        assertSame(GalleryModel.Intent.Kind.NONE, m.clickRow(2, 5400).kind);
        // another row in between
        m.clickRow(0, 9000);
        assertSame(GalleryModel.Intent.Kind.NONE, m.clickRow(1, 9100).kind);
        assertEquals(1, m.selected());
    }

    @Test
    public void deleteNeedsASecondPress() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        m.key(KEY_DOWN, NOW);
        assertSame(GalleryModel.Intent.Kind.NONE, m.delete().kind);
        assertTrue(m.armed());
        GalleryModel.Intent i = m.delete();
        assertSame(GalleryModel.Intent.Kind.DELETE, i.kind);
        assertEquals("20261114-200000-aaaa", i.id);
        m.removed("20261114-200000-aaaa");
        assertFalse(m.armed());
        assertEquals(Arrays.asList("20261114-210000-bbbb", "20261113-230000-cccc"), ids(m));
        assertEquals(1, m.selected());
    }

    @Test
    public void deleteKeysArmAndConfirm() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        assertSame(GalleryModel.Intent.Kind.NONE, m.key(KEY_DELETE, NOW).kind);
        assertTrue(m.armed());
        assertSame(GalleryModel.Intent.Kind.DELETE, m.key(KEY_BACK, NOW).kind);
    }

    /**
     * Code review 1, I1: a held Delete key sends key-repeat events; none of them may confirm. The reviewer's
     * sequence: three rows, one physical press then five repeats, every DELETE intent applied.
     */
    @Test
    public void aHeldDeleteKeyNeverConfirmsOrDeletes() {
        for (int code : new int[]{KEY_DELETE, KEY_BACK}) {
            GalleryModel m = model(GalleryModel.Origin.TITLE, three());
            int deleted = 0;
            for (int i = 0; i < 6; i++) {
                GalleryModel.Intent in = m.key(code, i > 0, NOW);
                if (in.kind == GalleryModel.Intent.Kind.DELETE) {
                    m.removed(in.id);
                    deleted++;
                }
            }
            assertEquals("repeat events deleted nothing", 0, deleted);
            assertEquals(3, m.entries().size());
            assertTrue("the physical press armed it; repeats leave it armed", m.armed());
            // A second physical press (after the release) confirms.
            GalleryModel.Intent confirm = m.key(code, false, NOW);
            assertSame(GalleryModel.Intent.Kind.DELETE, confirm.kind);
            m.removed(confirm.id);
            // Holding that second press does not arm or delete the next row.
            for (int i = 0; i < 5; i++) {
                assertSame(GalleryModel.Intent.Kind.NONE, m.key(code, true, NOW).kind);
            }
            assertFalse(m.armed());
            assertEquals(2, m.entries().size());
        }
    }

    @Test
    public void heldArrowKeysStillMoveTheSelection() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        m.key(KEY_DOWN, false, NOW);
        m.key(KEY_DOWN, true, NOW);
        assertEquals(2, m.selected());
        m.key(KEY_UP, true, NOW);
        assertEquals(1, m.selected());
    }

    @Test
    public void removingTheLastRowMovesTheSelectionUp() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        m.key(KEY_DOWN, NOW);
        m.key(KEY_DOWN, NOW);
        m.delete();
        m.removed(m.delete().id);
        assertEquals(1, m.selected());
        m.removed("20261114-200000-aaaa");
        m.removed("20261114-210000-bbbb");
        assertTrue(m.entries().isEmpty());
        assertEquals(0, m.selected());
        assertSame(GalleryModel.Intent.Kind.NONE, m.delete().kind);
        assertFalse("nothing to arm", m.armed());
        assertSame(GalleryModel.Intent.Kind.NONE, m.watch().kind);
        assertSame(GalleryModel.Intent.Kind.NONE, m.key(KEY_RETURN, NOW).kind);
    }

    @Test
    public void aRefusedDeleteDisarms() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        m.delete();
        m.delete();
        m.deleteFailed();
        assertFalse(m.armed());
        assertEquals(3, m.entries().size());
    }

    @Test
    public void anyOtherActionResetsTheArmedDelete() {
        String[] actions = {"up", "down", "enter", "click", "star", "watch", "done", "esc"};
        for (String a : actions) {
            GalleryModel m = model(GalleryModel.Origin.TITLE, three());
            m.key(KEY_DOWN, NOW);
            m.delete();
            assertTrue(a, m.armed());
            if (a.equals("up")) m.key(KEY_UP, NOW);
            else if (a.equals("down")) m.key(KEY_DOWN, NOW);
            else if (a.equals("enter")) m.key(KEY_RETURN, NOW);
            else if (a.equals("click")) m.clickRow(1, NOW);
            else if (a.equals("star")) m.clickStar(2);
            else if (a.equals("watch")) m.watch();
            else if (a.equals("done")) m.done();
            else m.key(KEY_ESCAPE, NOW);
            assertFalse(a, m.armed());
            // the next Delete arms again instead of deleting
            assertSame(a, GalleryModel.Intent.Kind.NONE, m.delete().kind);
        }
    }

    @Test
    public void starAsksTheLibraryAndShowsOnlyWhatItConfirmed() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        GalleryModel.Intent i = m.clickStar(2);
        assertSame(GalleryModel.Intent.Kind.STAR, i.kind);
        assertEquals("20261113-230000-cccc", i.id);
        assertTrue(i.starred);
        assertFalse("not until the library confirms", m.entries().get(2).starred);
        assertEquals("a star click does not select", 0, m.selected());
        m.starred("20261113-230000-cccc", true);
        assertTrue(m.entries().get(2).starred);
        assertFalse(m.clickStar(2).starred);
    }

    @Test
    public void doneTarget() {
        GalleryModel title = model(GalleryModel.Origin.TITLE, three());
        assertSame(GalleryModel.Done.TITLE_SCREEN, title.doneTarget());
        GalleryModel.Intent d = title.done();
        assertSame(GalleryModel.Intent.Kind.DONE, d.kind);
        assertSame(GalleryModel.Done.TITLE_SCREEN, d.done);
        GalleryModel playing = model(GalleryModel.Origin.PLAYING, three());
        assertSame(GalleryModel.Done.ESC_MENU, playing.doneTarget());
        assertSame(GalleryModel.Done.ESC_MENU, playing.key(KEY_ESCAPE, NOW).done);
    }

    // ------------------------------------------------------------------ layout, mouse

    @Test
    public void layoutAtFullWidthMatchesTheMockup() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        GalleryModel.Layout l = m.layout(960, 540, MC);
        int h = 1 + (3 * 30 + 2) + (1 + 8 + 20 + 8) + 1;
        float x = Math.round((960 - 440) / 2f), y = Math.round((540 - h) / 2f);
        assertEquals(x, l.x, 0f);
        assertEquals(y, l.y, 0f);
        assertEquals(440, l.w, 0f);
        assertEquals(h, l.h, 0f);
        assertEquals(3, l.rows);
        assertEquals(x + 1 + 26, l.titleX, 0f);
        assertEquals(x + 1 + 168, l.resultX, 0f);
        assertEquals(x + 1 + 212, l.dateX, 0f);
        float[] row1 = l.row(1);
        assertEquals(y + 1 + 30 + 1, row1[1], 0f);
        assertEquals(438, row1[2], 0f);
        float fy = y + 1 + 92;
        assertEquals(fy + 1 + 8, l.watch[1], 0f);
        assertEquals(x + 11, l.watch[0], 0f);
        assertEquals(72, l.watch[2], 0f);
        assertEquals(x + 11 + 72 + 4, l.delete[0], 0f);
        assertEquals(x + 440 - 11 - 64, l.done[0], 0f);
    }

    @Test
    public void narrowScreensKeepColumnsApart() {
        for (int w = 320; w <= 960; w += 7) {
            GalleryModel.Layout l = model(GalleryModel.Origin.TITLE, three()).layout(w, 240, MC);
            assertTrue(l.x >= 0 && l.x + l.w <= w);
            float dateW = MC.width("Yesterday 00:00", ViewerLayout.FONT);
            float lenLeft = l.x + 1 + l.w - 2 - 12 - MC.width("00:00", ViewerLayout.FONT);
            assertTrue("w " + w, l.dateX + dateW <= lenLeft);
            assertTrue("w " + w, l.resultX + MC.width("LOSS", ViewerLayout.FONT) < l.dateX);
            assertTrue("w " + w, l.titleW > 60);
            assertTrue(l.delete[0] + l.delete[2] < l.done[0]);
        }
    }

    @Test
    public void manyRowsScrollToKeepTheSelectionVisible() {
        List<RecordingMeta> l = new ArrayList<RecordingMeta>();
        for (int i = 0; i < 20; i++) l.add(meta(String.format("20261114-2000%02d-aaaa", i), NOW - i * 60_000L));
        GalleryModel m = model(GalleryModel.Origin.TITLE, l);
        GalleryModel.Layout lay = m.layout(427, 240, MC);
        assertTrue(lay.rows < 20);
        assertTrue(lay.y >= 0 && lay.y + lay.h <= 240);
        for (int i = 0; i < 19; i++) m.key(KEY_DOWN, NOW);
        lay = m.layout(427, 240, MC);
        assertEquals(19, m.selected());
        assertEquals(20 - lay.rows, m.firstRow());
        m.scroll(-100);
        assertEquals(0, m.firstRow());
        m.scroll(3);
        assertEquals(3, m.firstRow());
        // a click on the first visible row selects entry 3
        float[] r = m.layout(427, 240, MC).row(0);
        m.mouseDown(m.layout(427, 240, MC), r[0] + 100, r[1] + 10);
        m.mouseUp(m.layout(427, 240, MC), r[0] + 100, r[1] + 10, NOW);
        assertEquals(3, m.selected());
    }

    @Test
    public void mouseHitsRowsStarsAndKeys() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        GalleryModel.Layout l = m.layout(960, 540, MC);
        float[] row2 = l.row(2);
        GalleryModel.Hit h = l.hit(row2[0] + 200, row2[1] + 12);
        assertSame(GalleryModel.Hit.Part.ROW, h.part);
        assertEquals(2, h.row);
        float[] star = l.star(2);
        h = l.hit(star[0] + 1, star[1] + 1);
        assertSame(GalleryModel.Hit.Part.STAR, h.part);
        assertEquals(2, h.row);
        assertSame(GalleryModel.Hit.Part.WATCH, l.hit(l.watch[0] + 2, l.watch[1] + 2).part);
        assertSame(GalleryModel.Hit.Part.DELETE, l.hit(l.delete[0] + 2, l.delete[1] + 2).part);
        assertSame(GalleryModel.Hit.Part.DONE, l.hit(l.done[0] + 2, l.done[1] + 2).part);
        assertSame(GalleryModel.Hit.Part.CARD, l.hit(l.x + 3, l.y + l.h - 3).part);
        assertSame(GalleryModel.Hit.Part.NONE, l.hit(l.x - 3, l.y).part);
    }

    @Test
    public void mouseClicksNeedPressAndReleaseOnTheSamePart() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, three());
        GalleryModel.Layout l = m.layout(960, 540, MC);
        m.mouseDown(l, l.delete[0] + 2, l.delete[1] + 2);
        assertSame(GalleryModel.Hit.Part.DELETE, m.pressed().part);
        assertSame(GalleryModel.Intent.Kind.NONE, m.mouseUp(l, l.done[0] + 2, l.done[1] + 2, NOW).kind);
        assertFalse(m.armed());
        assertNull(m.pressed());
        m.mouseDown(l, l.delete[0] + 2, l.delete[1] + 2);
        m.mouseUp(l, l.delete[0] + 2, l.delete[1] + 2, NOW);
        assertTrue(m.armed());
        m.mouseDown(l, l.done[0] + 2, l.done[1] + 2);
        GalleryModel.Intent d = m.mouseUp(l, l.done[0] + 3, l.done[1] + 3, NOW);
        assertSame(GalleryModel.Intent.Kind.DONE, d.kind);
        assertFalse(m.armed());
    }

    @Test
    public void anEmptyGalleryKeepsOneRowOfSpace() {
        GalleryModel m = model(GalleryModel.Origin.TITLE, Collections.<RecordingMeta>emptyList());
        GalleryModel.Layout l = m.layout(960, 540, MC);
        assertEquals(0, l.rows);
        assertEquals(1 + 30 + (1 + 8 + 20 + 8) + 1, l.h, 0f);
        assertSame(GalleryModel.Hit.Part.CARD, l.hit(l.x + 100, l.y + 10).part);
    }
}
