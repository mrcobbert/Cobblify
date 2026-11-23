package com.bedwarsqol.gui.sheet;

import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.watch.EscMenuModel;
import com.bedwarsqol.rewind.watch.GalleryModel;
import com.bedwarsqol.rewind.watch.RewindPainter;
import com.bedwarsqol.rewind.watch.ViewerLayout;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Rewind's screens drawn on {@link SheetFixtures} (TASK A2, A9, G49, G51): they draw no text but their labels, the
 * played part and the cursor marker in the accent, marker icons as pixel art, the armed Delete key in red.
 */
public class RewindPainterTest {

    private static final SheetColors.Shades ACC = SheetColors.Shades.of(0xC0DCFF);
    private static final long LEN = 406_600_000L;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long NOW = 1_794_693_600_000L; // 2026-11-14 22:00 UTC

    /** A {@link SheetFixtures.Recorder} that also records alpha layers and disabled groups. */
    private static final class Spy implements Canvas {
        final SheetFixtures.Recorder rec = new SheetFixtures.Recorder();
        final List<Float> alphas = new ArrayList<Float>();
        final List<Integer> disabled = new ArrayList<Integer>();

        @Override public float unit() { return rec.unit(); }
        @Override public void fill(float x, float y, float w, float h, int argb) { rec.fill(x, y, w, h, argb); }
        @Override public void stroke(float[] points, float width, int argb) { rec.stroke(points, width, argb); }
        @Override public void ring(float cx, float cy, float r, float width, int argb) { rec.ring(cx, cy, r, width, argb); }
        @Override public void field(float x, float y, float w, float h, int kind, float hue) { rec.field(x, y, w, h, kind, hue); }
        @Override public void text(String s, float x, float lineTop, float lineHeight, SheetFont font, int argb) {
            rec.text(s, x, lineTop, lineHeight, font, argb);
        }
        @Override public float baseline(SheetFont font, float lineTop, float lineHeight) { return lineTop; }
        @Override public float[] contentArea(SheetFont font, float lineTop, float lineHeight) {
            return rec.contentArea(font, lineTop, lineHeight);
        }
        @Override public void pushClip(float x, float y, float w, float h) { }
        @Override public void popClip() { }
        @Override public void pushAlpha(float a) { alphas.add(a); }
        @Override public void popAlpha() { }
        @Override public void beginDisabled(int backdrop) { disabled.add(backdrop); }
        @Override public void endDisabled() { }
        @Override public float width(String s, SheetFont font) { return rec.width(s, font); }
        @Override public float ascent(SheetFont font) { return rec.ascent(font); }
        @Override public float descent(SheetFont font) { return rec.descent(font); }
        @Override public float normalLineHeight(SheetFont font) { return rec.normalLineHeight(font); }
        @Override public boolean minecraftFont() { return rec.minecraftFont(); }
        @Override public float pixel(SheetFont font) { return rec.pixel(font); }
    }

    private static List<String> texts(SheetFixtures.Recorder r) {
        List<String> out = new ArrayList<String>();
        for (Object[] t : r.texts) out.add((String) t[0]);
        return out;
    }

    private static boolean hasFill(SheetFixtures.Recorder r, float x, float y, float w, float h, int colour) {
        for (int i = 0; i < r.fills.size(); i++) {
            float[] f = r.fills.get(i);
            if (r.colours.get(i) == colour && Math.abs(f[0] - x) < 1e-3f && Math.abs(f[1] - y) < 1e-3f
                    && Math.abs(f[2] - w) < 1e-3f && Math.abs(f[3] - h) < 1e-3f) return true;
        }
        return false;
    }

    private static int count(SheetFixtures.Recorder r, int colour) {
        int n = 0;
        for (int c : r.colours) if (c == colour) n++;
        return n;
    }

    private static ViewerLayout viewer(TextMetrics m) {
        return ViewerLayout.of(640, 360, m, LEN, new long[]{60_000_000L, 200_000_000L, 300_000_000L},
                new int[]{RewindFormat.MARKER_DEATH, RewindFormat.MARKER_BED_BROKEN, RewindFormat.MARKER_FINAL_KILL});
    }

    private static RewindPainter.BarState state(long t) {
        RewindPainter.BarState s = new RewindPainter.BarState();
        s.timeUs = t;
        s.accent = ACC;
        return s;
    }

    // ------------------------------------------------------------------ loading card

    @Test
    public void loadingCardDrawsOnlyTheTitle() {
        SheetFixtures.Recorder r = new SheetFixtures.Recorder(new SheetFixtures.MinecraftMetrics(2f));
        RewindPainter.loading(r, 640, 360, "Fours · Lighthouse", 0.5f, ACC);
        assertEquals(Collections.singletonList("Fours · Lighthouse"), texts(r));
        // an opaque dark background over the whole screen first
        assertTrue(hasFill(r, 0, 0, 640, 360, SheetColors.WELL_TOP));
        assertEquals(SheetColors.WELL_TOP, (int) r.colours.get(0));
        // the key fills with the accent
        assertTrue(count(r, ACC.base) > 0);
    }

    @Test
    public void loadingKeyFillsByProgress() {
        SheetFixtures.Recorder empty = new SheetFixtures.Recorder();
        RewindPainter.loading(empty, 640, 360, "Solo", 0f, ACC);
        assertEquals(0, count(empty, ACC.base));
        float[] card = RewindPainter.loadingCard(empty, 640, 360, "Solo");
        assertEquals(68, card[3], 0f);
        assertEquals(168, card[2], 0f);
    }

    // ------------------------------------------------------------------ viewer bar

    @Test
    public void barDrawsNoTextButItsLabels() {
        SheetFixtures.Recorder r = new SheetFixtures.Recorder(new SheetFixtures.MinecraftMetrics(2f));
        ViewerLayout l = viewer(r);
        RewindPainter.bar(r, l, state(65_000_000L));
        assertEquals(Arrays.asList("You", "1x", "1:05", " / 6:47"), texts(r));

        SheetFixtures.Recorder open = new SheetFixtures.Recorder(new SheetFixtures.MinecraftMetrics(2f));
        RewindPainter.BarState s = state(65_000_000L);
        s.menuOpen = true;
        s.camera = ViewerLayout.FREE;
        s.speed = 0.25f;
        RewindPainter.bar(open, viewer(open), s);
        assertEquals(Arrays.asList("Free camera", "0.25x", "1:05", " / 6:47", "Free camera", "You"), texts(open));
    }

    /** Phase 14 (D1, D3): the players in the camera menu, the key showing the ridden one, their faces and name. */
    @Test
    public void theMenuListsPlayersInTheirTeamColourAndTheKeyShowsTheRiddenOne() {
        int red = 0xFFFF5555, blue = 0xFF5555FF;
        SheetFixtures.Recorder r = new SheetFixtures.Recorder(new SheetFixtures.MinecraftMetrics(2f));
        ViewerLayout l = ViewerLayout.of(640, 360, r, LEN, new long[0], new int[0], new String[]{"amy", "Bob"},
                ViewerLayout.FIRST_PLAYER + 1);
        RewindPainter.BarState s = state(65_000_000L);
        s.camera = ViewerLayout.FIRST_PLAYER + 1;
        s.playerColours = new int[]{red, blue};
        s.menuOpen = true;
        RewindPainter.bar(r, l, s);
        assertEquals(Arrays.asList("Bob", "1x", "1:05", " / 6:47", "Free camera", "You", "amy", "Bob"), texts(r));
        assertEquals("the key's name in the team colour", blue, (int) r.textColours.get(0));
        assertEquals(red, (int) r.textColours.get(6));
        assertEquals(blue, (int) r.textColours.get(7));
        float[] amy = l.dropdownRow(2);
        assertTrue("a team-coloured head frame", hasFill(r, amy[0] + 6, amy[1] + 2, 10, 10, red));
        assertTrue("the ridden row is marked", hasFill(r, l.dropdownRow(3)[0], l.dropdownRow(3)[1] + 2, 2, 10,
                ACC.base));

        List<float[]> faces = RewindPainter.faces(l, s);
        assertEquals("the key, You, amy and Bob (the free camera has no face)", 4, faces.size());
        assertEquals(ViewerLayout.FIRST_PLAYER + 1, faces.get(0)[4], 0f);
        assertEquals(ViewerLayout.YOU, faces.get(1)[4], 0f);
        assertEquals(ViewerLayout.FIRST_PLAYER, faces.get(2)[4], 0f);
        assertEquals(amy[0] + 7, faces.get(2)[0], 0f);
        assertEquals(amy[1] + 3, faces.get(2)[1], 0f);

        SheetFixtures.Recorder name = new SheetFixtures.Recorder(new SheetFixtures.MinecraftMetrics(2f));
        RewindPainter.riddenName(name, 640, "Bob", blue);
        assertEquals(Collections.singletonList("Bob"), texts(name));
        assertEquals(blue, (int) name.textColours.get(0));
        assertEquals(ViewerLayout.riddenNameTop(), (Float) name.texts.get(0)[2], 0f);
        assertEquals("centred", 320f, (Float) name.texts.get(0)[1] + name.width("Bob", ViewerLayout.FONT) / 2f, 0.5f);
    }

    @Test
    public void playedPartIsInTheAccent() {
        SheetFixtures.Recorder r = new SheetFixtures.Recorder();
        ViewerLayout l = viewer(r);
        long t = 100_000_000L;
        RewindPainter.bar(r, l, state(t));
        float x = l.xOf(t);
        assertTrue(hasFill(r, l.stripX + 1, l.stripY + 1, x - l.stripX - 1, l.stripH - 2, ACC.dp));
        assertTrue(hasFill(r, l.stripX + 1, l.stripY + 1, x - l.stripX - 1, 1, ACC.base));
    }

    @Test
    public void theCursorMarkerIsHighlighted() {
        SheetFixtures.Recorder plain = new SheetFixtures.Recorder();
        ViewerLayout l = viewer(plain);
        RewindPainter.bar(plain, l, state(0));
        float[] icon = l.markerIcon(1);
        assertFalse(hasFill(plain, icon[0] - 1, l.stripY, icon[2] + 2, l.stripH, ACC.base));

        SheetFixtures.Recorder cursor = new SheetFixtures.Recorder();
        RewindPainter.BarState s = state(0);
        s.cursorMarker = 1;
        RewindPainter.bar(cursor, l, s);
        assertTrue(hasFill(cursor, icon[0] - 1, l.stripY, icon[2] + 2, l.stripH, ACC.base));
        float[] other = l.markerIcon(0);
        assertFalse(hasFill(cursor, other[0] - 1, l.stripY, other[2] + 2, l.stripH, ACC.base));
    }

    @Test
    public void hoveredMarkerGetsTheHoverFace() {
        SheetFixtures.Recorder r = new SheetFixtures.Recorder();
        ViewerLayout l = viewer(r);
        RewindPainter.BarState s = state(0);
        float[] icon = l.markerIcon(2);
        s.hover = l.hit(icon[0] + 2, icon[1] + 2, false);
        RewindPainter.bar(r, l, s);
        assertTrue(hasFill(r, icon[0] - 1, l.stripY, icon[2] + 2, l.stripH, SheetColors.FACE_HOVER));
    }

    @Test
    public void barFadesAsOneLayer() {
        Spy r = new Spy();
        RewindPainter.BarState s = state(0);
        s.alpha = 0.5f;
        RewindPainter.bar(r, viewer(r), s);
        assertEquals(Collections.singletonList(0.5f), r.alphas);
        SheetFixtures.Recorder gone = new SheetFixtures.Recorder();
        s.alpha = 0f;
        RewindPainter.bar(gone, viewer(gone), s);
        assertTrue(gone.fills.isEmpty());
        assertTrue(gone.texts.isEmpty());
    }

    @Test
    public void markerIconsArePixelArtWithAShadow() {
        SheetFixtures.Recorder r = new SheetFixtures.Recorder();
        RewindPainter.markerIcon(r, RewindFormat.MARKER_DEATH, 10, 20);
        // 33 lit pixels in the death cross, each with a shadow one down-right
        assertEquals(33, count(r, 0xFFFF5555));
        assertEquals(33, count(r, SheetColors.textShadow(0xFFFF5555)));
        for (int i = 0; i < r.fills.size(); i++) {
            float[] f = r.fills.get(i);
            assertEquals(1f, f[2], 0f);
            assertEquals(1f, f[3], 0f);
            assertTrue(f[0] >= 10 && f[0] <= 17 && f[1] >= 20 && f[1] <= 27);
        }
        // both bed kinds use one colour: R1 markers carry no team
        SheetFixtures.Recorder bed = new SheetFixtures.Recorder();
        RewindPainter.markerIcon(bed, RewindFormat.MARKER_BED_BROKEN, 0, 0);
        RewindPainter.markerIcon(bed, RewindFormat.MARKER_BED_LOST, 10, 0);
        assertTrue(count(bed, RewindPainter.BED) > 0);
        SheetFixtures.Recorder unknown = new SheetFixtures.Recorder();
        RewindPainter.markerIcon(unknown, 99, 0, 0);
        assertTrue(unknown.fills.isEmpty());
    }

    @Test
    public void flySpeedBarShowsTheLevel() {
        SheetFixtures.Recorder r = new SheetFixtures.Recorder();
        RewindPainter.flySpeed(r, 640, 360, 4, 1f, ACC);
        assertEquals(4, count(r, ACC.base));
        assertTrue(r.texts.isEmpty());
        SheetFixtures.Recorder full = new SheetFixtures.Recorder();
        RewindPainter.flySpeed(full, 640, 360, 10, 1f, ACC);
        assertEquals(10, count(full, ACC.base));
    }

    // ------------------------------------------------------------------ Esc menu

    @Test
    public void escMenuDrawsItsFiveKeysWithRejoinInTheAccent() {
        SheetFixtures.Recorder r = new SheetFixtures.Recorder(new SheetFixtures.MinecraftMetrics(2f));
        EscMenuModel.Layout l = EscMenuModel.layout(640, 360);
        RewindPainter.escMenu(r, l, null, null, ACC);
        assertEquals(Arrays.asList("Resume", "Rejoin Hypixel", "Export", "Gallery", "Quit to Title"), texts(r));
        float[] rejoin = l.key(EscMenuModel.Key.REJOIN);
        assertTrue(hasFill(r, rejoin[0] + 2, rejoin[1] + 2, rejoin[2] - 4, rejoin[3] - 6, ACC.base));
        float[] resume = l.key(EscMenuModel.Key.RESUME);
        assertTrue(hasFill(r, resume[0] + 2, resume[1] + 2, resume[2] - 4, resume[3] - 6, SheetColors.FACE));
    }

    // ------------------------------------------------------------------ gallery

    private static GalleryModel gallery() {
        List<RecordingMeta> l = new ArrayList<RecordingMeta>();
        for (int i = 0; i < 2; i++) {
            RecordingMeta m = new RecordingMeta();
            m.id = "20261114-21000" + i + "-aaaa";
            m.complete = true;
            m.startedAtMs = NOW - 3_600_000L * (i + 1);
            m.serverType = "BEDWARS";
            m.mode = i == 0 ? "BEDWARS_FOUR_FOUR" : "BEDWARS_EIGHT_ONE";
            m.map = i == 0 ? "Lighthouse" : null;
            m.result = i == 0 ? "WIN" : "UNKNOWN";
            m.gameStartUs = 1_000_000L;
            m.endUs = 1_000_000L + LEN;
            m.lengthUs = LEN;
            m.starred = i == 1;
            m.markers.add(new RecordingMeta.Marker(1_000_000L + LEN / 2, "DEATH"));
            l.add(m);
        }
        return new GalleryModel(GalleryModel.Origin.TITLE, l, NOW, UTC);
    }

    @Test
    public void galleryDrawsNoTextButItsLabels() {
        SheetFixtures.Recorder r = new SheetFixtures.Recorder(new SheetFixtures.MinecraftMetrics(2f));
        GalleryModel m = gallery();
        RewindPainter.gallery(r, m, m.layout(640, 360, r), null, ACC);
        assertEquals(Arrays.asList("Fours · Lighthouse", "WIN", "Today 21:00", "6:47", "Solo", "Today 20:00",
                "6:47", "Watch", "Export", "Delete", "Done"), texts(r));
        int win = r.textColours.get(1);
        assertEquals(0xFF55FF55, win);
    }

    @Test
    public void galleryMarksStarsSelectionAndArmedDelete() {
        SheetFixtures.Recorder r = new SheetFixtures.Recorder();
        GalleryModel m = gallery();
        GalleryModel.Layout l = m.layout(640, 360, r);
        RewindPainter.gallery(r, m, l, null, ACC);
        assertTrue("one starred row in gold", count(r, RewindPainter.STAR_ON) > 0);
        float[] row0 = l.row(0);
        assertTrue("selected row ring", hasFill(r, row0[0], row0[1], row0[2], 1, SheetColors.TEXT_HI));
        assertEquals(0, count(r, RewindPainter.RED.base));
        // marker icons on the mini timeline
        assertTrue(count(r, 0xFFFF5555) >= 2 * 33);

        m.delete(); // armed: the key turns red
        SheetFixtures.Recorder armed = new SheetFixtures.Recorder();
        RewindPainter.gallery(armed, m, m.layout(640, 360, armed), null, ACC);
        assertTrue(count(armed, RewindPainter.RED.base) > 0);
    }

    @Test
    public void anEmptyGalleryDisablesWatchExportAndDelete() {
        Spy r = new Spy();
        GalleryModel m = new GalleryModel(GalleryModel.Origin.TITLE, Collections.<RecordingMeta>emptyList(), NOW, UTC);
        RewindPainter.gallery(r, m, m.layout(640, 360, r), null, ACC);
        assertEquals(3, r.disabled.size());
        assertEquals(Arrays.asList("Watch", "Export", "Delete", "Done"), texts(r.rec));
    }
}
