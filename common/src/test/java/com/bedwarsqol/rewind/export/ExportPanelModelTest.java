package com.bedwarsqol.rewind.export;

import com.bedwarsqol.gui.sheet.SheetFont;
import com.bedwarsqol.gui.sheet.TextMetrics;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExportPanelModel} (G10, G12, G42; Rewind Export PLAN Solution 5, Phase 6, review I5; TASK U4, U5, U6, P9):
 * five rows, their values from the settings, what the encoder probe allows, the window route's one size, and the
 * Export key's states and intents.
 */
public class ExportPanelModelTest {

    private static final int KEY_ESCAPE = 1, KEY_RETURN = 28, KEY_SPACE = 57;
    private static final ExportPanelModel.Row RES = ExportPanelModel.Row.RESOLUTION, FPS = ExportPanelModel.Row.FRAME_RATE,
            QUALITY = ExportPanelModel.Row.QUALITY, CODEC = ExportPanelModel.Row.CODEC, CHAT = ExportPanelModel.Row.CHAT;

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

    /** Every preset size at every rate. */
    private static List<int[]> all() {
        List<int[]> rows = new ArrayList<int[]>();
        for (int h : ExportSettings.HEIGHTS) {
            for (int f : ExportSettings.RATES) rows.add(new int[]{ExportSettings.width(h), h, f});
        }
        return rows;
    }

    private static List<int[]> without(List<int[]> rows, int w, int h, int fps) {
        List<int[]> out = new ArrayList<int[]>();
        for (int[] r : rows) if (!(r[0] == w && r[1] == h && r[2] == fps)) out.add(r);
        return out;
    }

    private static ExportPanelModel full(ExportSettings s) {
        ExportPanelModel m = new ExportPanelModel(s);
        m.probe(new ExportPanelModel.Matrix(all(), all()));
        return m;
    }

    private static List<String> available(ExportPanelModel m, ExportPanelModel.Row r) {
        List<String> out = new ArrayList<String>();
        List<String> opts = m.options(r);
        for (int i = 0; i < opts.size(); i++) if (m.available(r, i)) out.add(opts.get(i));
        return out;
    }

    private static String chosen(ExportPanelModel m, ExportPanelModel.Row r) {
        int i = m.chosen(r);
        return i < 0 ? null : m.options(r).get(i);
    }

    @Test
    public void fiveRowsAndTheirValues() {
        ExportPanelModel m = full(ExportSettings.defaults());
        assertEquals(5, ExportPanelModel.Row.values().length);
        assertEquals("Resolution", ExportPanelModel.label(RES));
        assertEquals("Frame Rate", ExportPanelModel.label(FPS));
        assertEquals("Quality", ExportPanelModel.label(QUALITY));
        assertEquals("Codec", ExportPanelModel.label(CODEC));
        assertEquals("Chat", ExportPanelModel.label(CHAT));
        assertEquals(Arrays.asList("720p", "1080p", "1440p", "4K"), m.options(RES));
        assertEquals(Arrays.asList("30", "60", "120", "240"), m.options(FPS));
        assertEquals(Arrays.asList("Standard", "High", "Max"), m.options(QUALITY));
        assertEquals(Arrays.asList("H.264", "HEVC"), m.options(CODEC));
        assertEquals(Arrays.asList("On", "Off"), m.options(CHAT));
        for (ExportPanelModel.Row r : ExportPanelModel.Row.values()) {
            assertEquals(r.toString(), m.options(r), available(m, r));
        }
    }

    @Test
    public void theRowsShowTheSettingsDefaultsFirstAndTheLastUsedAfter() {
        ExportPanelModel d = full(ExportSettings.defaults());
        assertEquals("1080p", chosen(d, RES));
        assertEquals("60", chosen(d, FPS));
        assertEquals("High", chosen(d, QUALITY));
        assertEquals("H.264", chosen(d, CODEC));
        assertEquals("On", chosen(d, CHAT));
        ExportPanelModel last = full(ExportSettings.of(1440, 120, "max", "hevc", false));
        assertEquals("1440p", chosen(last, RES));
        assertEquals("120", chosen(last, FPS));
        assertEquals("Max", chosen(last, QUALITY));
        assertEquals("HEVC", chosen(last, CODEC));
        assertEquals("Off", chosen(last, CHAT));
        ExportPanelModel.Choice c = last.choice();
        assertEquals(2560, c.width());
        assertEquals(1440, c.height());
        assertEquals(120, c.fps());
        assertEquals("max", c.quality());
        assertEquals("hevc", c.codec());
        assertFalse(c.chat());
    }

    @Test
    public void pickingChangesTheChoiceAndItIsWhatIsRemembered() {
        ExportPanelModel m = full(ExportSettings.defaults());
        m.pick(RES, 3);
        m.pick(FPS, 0);
        m.pick(QUALITY, 0);
        m.pick(CODEC, 1);
        m.pick(CHAT, 1);
        ExportPanelModel.Choice c = m.choice();
        assertEquals(3840, c.width());
        assertEquals(2160, c.height());
        assertEquals(30, c.fps());
        ExportSettings s = c.settings();
        assertEquals(2160, s.height());
        assertEquals(30, s.fps());
        assertEquals("standard", s.quality());
        assertEquals("hevc", s.codec());
        assertFalse(s.chat());
        m.pick(CHAT, 0);
        assertTrue(m.choice().chat());
    }

    @Test
    public void withoutAnHevcEncoderCodecHasOneValue() {
        ExportPanelModel m = new ExportPanelModel(ExportSettings.of(1080, 60, "high", "hevc", true));
        m.probe(new ExportPanelModel.Matrix(all(), new ArrayList<int[]>()));
        assertEquals(Arrays.asList("H.264"), m.options(CODEC));
        assertEquals("a stored HEVC falls back", "H.264", chosen(m, CODEC));
        assertEquals("h264", m.choice().codec());
    }

    @Test
    public void aPairMissingFromTheMatrixCannotBePickedAndAStoredOneFallsBackToTheNearest() {
        List<int[]> h264 = without(without(all(), 3840, 2160, 120), 3840, 2160, 240);
        ExportPanelModel m = new ExportPanelModel(ExportSettings.of(2160, 240, "high", "h264", true));
        m.probe(new ExportPanelModel.Matrix(h264, new ArrayList<int[]>()));
        assertEquals("4K", chosen(m, RES));
        assertEquals(Arrays.asList("30", "60"), available(m, FPS));
        assertEquals("240 at 4K falls back to the nearest rate", "60", chosen(m, FPS));
        m.pick(FPS, 2);
        assertEquals("120 cannot be picked at 4K", 60, m.choice().fps());
        m.pick(RES, 1);
        assertEquals(Arrays.asList("30", "60", "120", "240"), available(m, FPS));
        m.pick(FPS, 3);
        assertEquals(240, m.choice().fps());
        m.pick(RES, 3);
        assertEquals("back at 4K the nearest rate to 240", 60, m.choice().fps());

        // a size with no rows at all: not pickable, and a stored one falls back to the nearest size
        List<int[]> no1440 = new ArrayList<int[]>();
        for (int[] r : all()) if (r[1] != 1440) no1440.add(r);
        ExportPanelModel n = new ExportPanelModel(ExportSettings.of(1440, 60, "high", "h264", true));
        n.probe(new ExportPanelModel.Matrix(no1440, no1440));
        assertFalse(n.available(RES, 2));
        assertEquals("1080p", chosen(n, RES));
        n.pick(RES, 2);
        assertEquals(1080, n.choice().height());
        assertEquals("what is remembered is what is exported", 1080, n.choice().settings().height());
    }

    @Test
    public void theWindowRouteOffersTheWindowsOwnSizeWithItsRates() {
        List<int[]> rows = all();
        rows.add(new int[]{1366, 768, 30});
        rows.add(new int[]{1366, 768, 60});
        ExportPanelModel m = new ExportPanelModel(ExportSettings.of(2160, 120, "high", "h264", true));
        m.probe(new ExportPanelModel.Matrix(rows, new ArrayList<int[]>()));
        m.route(RenderRoute.Route.WINDOW, 1367, 769); // rounded down to even
        assertEquals(Arrays.asList("1366x768"), m.options(RES));
        assertEquals("1366x768", chosen(m, RES));
        assertEquals(Arrays.asList("30", "60"), available(m, FPS));
        ExportPanelModel.Choice c = m.choice();
        assertEquals(1366, c.width());
        assertEquals(768, c.height());
        assertEquals(60, c.fps());
        assertEquals("the stored resolution is kept for the off-screen route", 2160, c.settings().height());
        assertSame(ExportPanelModel.KeyState.EXPORT, m.keyState());
        m.route(RenderRoute.Route.OWN, 1366, 768);
        assertEquals(Arrays.asList("720p", "1080p", "1440p", "4K"), m.options(RES));
        assertEquals("4K", chosen(m, RES));
    }

    @Test
    public void aSizeWithNoRowsDisablesTheExportKey() {
        ExportPanelModel m = new ExportPanelModel(ExportSettings.defaults());
        assertSame("no probe yet", ExportPanelModel.KeyState.DISABLED, m.keyState());
        m.probe(new ExportPanelModel.Matrix(all(), all()));
        m.route(RenderRoute.Route.WINDOW, 1000, 700);
        assertSame(ExportPanelModel.KeyState.DISABLED, m.keyState());
        assertNull(m.choice());
        ExportPanelModel.Layout l = m.layout(960, 540 - 30, MC);
        float[] k = l.key();
        m.mouseDown(l, k[0] + 3, k[1] + 3);
        assertSame(ExportPanelModel.Intent.Kind.NONE, m.mouseUp(l, k[0] + 3, k[1] + 3).kind);
        assertSame(ExportPanelModel.Intent.Kind.NONE, m.key(KEY_RETURN).kind);
        ExportPanelModel none = new ExportPanelModel(ExportSettings.defaults());
        none.probe(new ExportPanelModel.Matrix(new ArrayList<int[]>(), new ArrayList<int[]>()));
        assertSame(ExportPanelModel.KeyState.DISABLED, none.keyState());
    }

    @Test
    public void theKeyGoesExportFillingOpenFolderAndFailed() {
        ExportPanelModel m = full(ExportSettings.defaults());
        ExportPanelModel.Layout l = m.layout(960, 540 - 30, MC);
        float[] k = l.key();
        assertSame(ExportPanelModel.KeyState.EXPORT, m.keyState());
        m.mouseDown(l, k[0] + 3, k[1] + 3);
        ExportPanelModel.Intent go = m.mouseUp(l, k[0] + 4, k[1] + 4);
        assertSame(ExportPanelModel.Intent.Kind.EXPORT, go.kind);
        assertNotNull(go.choice);
        assertEquals(1920, go.choice.width());

        m.filling(0.25f);
        assertSame(ExportPanelModel.KeyState.FILLING, m.keyState());
        assertEquals(0.25f, m.fill(), 0f);
        m.filling(2f);
        assertEquals(1f, m.fill(), 0f);

        m.done();
        assertSame(ExportPanelModel.KeyState.OPEN_FOLDER, m.keyState());
        m.mouseDown(l, k[0] + 3, k[1] + 3);
        assertSame(ExportPanelModel.Intent.Kind.OPEN_FOLDER, m.mouseUp(l, k[0] + 3, k[1] + 3).kind);
        assertSame("Esc returns to the viewer", ExportPanelModel.Intent.Kind.LEAVE, m.key(KEY_ESCAPE).kind);

        m.failed();
        assertSame(ExportPanelModel.KeyState.FAILED, m.keyState());
        m.mouseDown(l, k[0] + 3, k[1] + 3);
        assertSame("another export can be started", ExportPanelModel.Intent.Kind.EXPORT,
                m.mouseUp(l, k[0] + 3, k[1] + 3).kind);
        m.pick(QUALITY, 2);
        assertSame("a new setting reads Export again", ExportPanelModel.KeyState.EXPORT, m.keyState());
        m.done();
        m.pick(FPS, 0);
        assertSame(ExportPanelModel.KeyState.EXPORT, m.keyState());
        assertSame(ExportPanelModel.Intent.Kind.LEAVE, m.key(KEY_ESCAPE).kind);
    }

    @Test
    public void whileFillingOnlyEscapeDoesAnything() {
        ExportPanelModel m = full(ExportSettings.defaults());
        ExportPanelModel.Layout l = m.layout(960, 540 - 30, MC);
        m.filling(0.5f);
        float[] k = l.key();
        m.mouseDown(l, k[0] + 3, k[1] + 3);
        assertSame(ExportPanelModel.Intent.Kind.NONE, m.mouseUp(l, k[0] + 3, k[1] + 3).kind);
        float[] o = l.option(RES, 0);
        m.mouseDown(l, o[0] + 2, o[1] + 2);
        assertSame(ExportPanelModel.Intent.Kind.NONE, m.mouseUp(l, o[0] + 2, o[1] + 2).kind);
        assertEquals("1080p", chosen(m, RES));
        m.pick(RES, 0);
        assertEquals("1080p", chosen(m, RES));
        assertSame(ExportPanelModel.Intent.Kind.NONE, m.key(KEY_RETURN).kind);
        assertSame(ExportPanelModel.Intent.Kind.NONE, m.key(KEY_SPACE).kind);
        assertSame(ExportPanelModel.Intent.Kind.CANCEL, m.key(KEY_ESCAPE).kind);
        assertSame(ExportPanelModel.KeyState.FILLING, m.keyState());
        m.idle();
        assertSame(ExportPanelModel.KeyState.EXPORT, m.keyState());
    }

    @Test
    public void escapeOutsideAnExportLeavesExportMode() {
        ExportPanelModel m = full(ExportSettings.defaults());
        assertSame(ExportPanelModel.Intent.Kind.LEAVE, m.key(KEY_ESCAPE).kind);
        assertSame(ExportPanelModel.Intent.Kind.NONE, m.key(KEY_SPACE).kind);
    }

    @Test
    public void clicksOnOptionsAndTheChatSwitch() {
        ExportPanelModel m = full(ExportSettings.defaults());
        ExportPanelModel.Layout l = m.layout(960, 540 - 30, MC);
        float[] o = l.option(RES, 2);
        m.mouseDown(l, o[0] + 2, o[1] + 2);
        assertSame(ExportPanelModel.Intent.Kind.NONE, m.mouseUp(l, o[0] + 3, o[1] + 3).kind);
        assertEquals("1440p", chosen(m, RES));
        float[] t = l.toggle();
        m.mouseDown(l, t[0] + 2, t[1] + 2);
        m.mouseUp(l, t[0] + 2, t[1] + 2);
        assertEquals("Off", chosen(m, CHAT));
        // released over another option: nothing
        float[] q = l.option(QUALITY, 0);
        m.mouseDown(l, q[0] + 2, q[1] + 2);
        m.mouseUp(l, l.option(QUALITY, 2)[0] + 2, q[1] + 2);
        assertEquals("High", chosen(m, QUALITY));
    }

    @Test
    public void thePanelSitsAboveTheBarAtTheRightAndFitsTheSmallestScreen() {
        float[][] screens = {{960, 540}, {427, 240}, {640, 360}, {1920, 1080}};
        for (float[] sc : screens) {
            ExportPanelModel m = full(ExportSettings.defaults());
            float barTop = sc[1] - 2 - 26;
            ExportPanelModel.Layout l = m.layout(sc[0], barTop, MC);
            assertTrue(l.x >= 0 && l.x + l.w <= sc[0] - 2 + 1e-3f);
            assertTrue("above the bar on " + sc[0] + "x" + sc[1], l.y + l.h <= barTop - 3 + 1e-3f);
            assertTrue(l.y >= 0);
            assertEquals(sc[0] - 2, l.x + l.w, 1e-3f);
            // rows inside the panel, options inside the options column, in order
            for (ExportPanelModel.Row r : ExportPanelModel.Row.values()) {
                if (r == CHAT) continue;
                float prevRight = -1;
                for (int i = 0; i < m.options(r).size(); i++) {
                    float[] o = l.option(r, i);
                    assertTrue(o[0] >= l.x && o[0] + o[2] <= l.x + l.w);
                    assertTrue(o[0] >= prevRight - 1);
                    prevRight = o[0] + o[2];
                    assertSame(ExportPanelModel.Hit.Part.OPTION, l.hit(o[0] + o[2] / 2f, o[1] + 5).part);
                    assertSame(r, l.hit(o[0] + o[2] / 2f, o[1] + 5).row);
                    assertEquals(i, l.hit(o[0] + o[2] / 2f, o[1] + 5).index);
                }
            }
            float[] t = l.toggle();
            assertSame(ExportPanelModel.Hit.Part.TOGGLE, l.hit(t[0] + 2, t[1] + 2).part);
            float[] k = l.key();
            assertSame(ExportPanelModel.Hit.Part.KEY, l.hit(k[0] + 2, k[1] + 2).part);
            assertTrue(k[1] + k[3] <= l.y + l.h);
            assertSame(ExportPanelModel.Hit.Part.PANEL, l.hit(l.x + 2, l.y + 2).part);
            assertSame(ExportPanelModel.Hit.Part.NONE, l.hit(l.x - 2, l.y + 2).part);
        }
    }
}
