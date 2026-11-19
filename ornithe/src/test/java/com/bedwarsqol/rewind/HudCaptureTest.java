package com.bedwarsqol.rewind;

import com.bedwarsqol.feature.TeamUpgradeRows;
import com.bedwarsqol.hud.HudModel;
import com.bedwarsqol.hud.TextModules;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PLAN Phase 4 (TASK A11, A12): the HUD painters hand {@link HudCapture} what they drew; it writes a HUD record only
 * when a module's values or on-screen flag changed, at most once per tick, and allocates nothing either way. The
 * recorder never asks the HUD for data (source scan).
 */
public class HudCaptureTest {

    /** Keeps whole records (test only; the allocation tests use {@link Counting}). */
    static class Recording implements HudCapture.Sink {
        final List<byte[]> records = new ArrayList<byte[]>();
        int failures;
        Throwable cause;

        @Override
        public boolean append(int tag, byte[] buf, int len) {
            assertEquals(RewindFormat.TAG_HUD, tag);
            records.add(Arrays.copyOf(buf, len));
            return true;
        }

        @Override
        public void failed(String where, Throwable t) {
            assertEquals("hud", where);
            failures++;
            cause = t;
        }

        HudModel.Values values(int i) {
            byte[] r = records.get(i);
            RewindFormat.Cursor c = new RewindFormat.Cursor(r, 0, r.length);
            int len = c.varInt();
            assertEquals(r.length - c.pos(), len);
            return HudModel.decode(r, c.pos(), len);
        }

        HudModel.Values last() {
            return values(records.size() - 1);
        }
    }

    /** Counts records and bytes without keeping them: allocates nothing. */
    static final class Counting implements HudCapture.Sink {
        int records;
        long bytes;

        @Override
        public boolean append(int tag, byte[] buf, int len) {
            records++;
            bytes += len;
            return true;
        }
    }

    private final HudCapture cap = new HudCapture();
    private final Recording sink = new Recording();

    @Before
    public void start() {
        cap.setSink(sink);
    }

    @After
    public void stop() {
        cap.setSink(null);
    }

    /** One tick: head, then one HUD frame. */
    private void tick() {
        cap.tickHead();
        cap.frame();
    }

    private static List<String> rows(int distance) {
        // freshly built strings each call, as the painter builds them every frame
        List<String> out = new ArrayList<String>(3);
        out.add("Map: " + new String("Lighthouse"));
        out.add("Height Limit: " + Integer.toString(110));
        out.add("Distance: " + Integer.toString(distance));
        return out;
    }

    /** A painter-like session line type, read through a view. */
    static final class Line {
        final String text;
        final boolean header;

        Line(String text, boolean header) {
            this.text = text;
            this.header = header;
        }
    }

    static final HudModel.LineView<Line> VIEW = new HudModel.LineView<Line>() {
        @Override
        public String text(Line line) {
            return line.text;
        }

        @Override
        public int kind(Line line) {
            return line.header ? HudModel.LINE_HEADER : HudModel.LINE_ROW;
        }
    };

    private static List<Line> session(int kills) {
        List<Line> out = new ArrayList<Line>();
        out.add(new Line(new String("Game"), true));
        out.add(new Line("Kills: " + kills, false));
        out.add(new Line(new String("Session"), true));
        out.add(new Line("Finals: " + 12 + " / FKDR: " + "3.00", false));
        out.add(new Line(new String(""), false));
        return out;
    }

    private static List<TeamUpgradeRows.Row> team(String nextCost, float alpha) {
        List<TeamUpgradeRows.Row> out = new ArrayList<TeamUpgradeRows.Row>();
        out.add(new TeamUpgradeRows.Row(TeamUpgradeRows.Kind.HEADER, new String("Upgrades"), "", "",
                TeamUpgradeRows.Style.NORMAL, 1f));
        out.add(new TeamUpgradeRows.Row(TeamUpgradeRows.Kind.UPGRADE, new String("Sharpened Swords"), "",
                new String("Max"), TeamUpgradeRows.Style.NORMAL, 1f));
        out.add(new TeamUpgradeRows.Row(TeamUpgradeRows.Kind.TRAP, new String("1 Blindness"), "", "",
                TeamUpgradeRows.Style.FIRED, alpha));
        out.add(new TeamUpgradeRows.Row(TeamUpgradeRows.Kind.NEXT, new String("Next"), "", new String(nextCost),
                TeamUpgradeRows.Style.NORMAL, 1f));
        return out;
    }

    // ------------------------------------------------------------------ what is written

    @Test
    public void eachModuleWritesItsValuesAndTheyDecodeEqual() {
        tick();
        cap.offerShown(RewindFormat.HUD_POTION);
        cap.offerTimer(RewindFormat.HUD_DIAMOND_TIMER, 23);
        cap.offerTimer(RewindFormat.HUD_EMERALD_TIMER, -1);
        cap.offerRuns(RewindFormat.HUD_FPS, TextModules.fps(150));
        cap.offerRuns(RewindFormat.HUD_CPS, TextModules.cps(9, 3));
        cap.offerRuns(RewindFormat.HUD_PING, TextModules.ping("--"));
        cap.offerRows(RewindFormat.HUD_HEIGHT_LIMIT, rows(27));
        cap.offerLines(RewindFormat.HUD_SESSION_STATS, session(3), VIEW);
        cap.offerTeamRows(RewindFormat.HUD_TEAM_UPGRADES, team("4", 0.5f));
        assertEquals(9, sink.records.size());
        for (int i = 0; i < 9; i++) assertTrue(sink.values(i).onScreen);
        assertEquals(RewindFormat.HUD_POTION, sink.values(0).module);
        assertEquals(23, sink.values(1).a);
        assertEquals(-1, sink.values(2).a);
        assertEquals(150, sink.values(3).a);
        assertEquals(9, sink.values(4).a);
        assertEquals(3, sink.values(4).b);
        assertEquals("--", sink.values(5).text);
        assertEquals(rows(27), sink.values(6).rows);
        HudModel.Values s = sink.values(7);
        assertEquals(5, s.lines.size());
        assertEquals(new HudModel.Line(HudModel.LINE_HEADER, "Game"), s.lines.get(0));
        assertEquals(new HudModel.Line(HudModel.LINE_ROW, "Kills: 3"), s.lines.get(1));
        HudModel.Values t = sink.values(8);
        List<TeamUpgradeRows.Row> want = team("4", 0.5f);
        assertEquals(want.size(), t.team.size());
        for (int i = 0; i < want.size(); i++) assertTrue(HudModel.sameRow(want.get(i), t.team.get(i)));
    }

    @Test
    public void unchangedValuesWriteNothing() {
        tick();
        cap.offerRows(RewindFormat.HUD_HEIGHT_LIMIT, rows(27));
        cap.offerRuns(RewindFormat.HUD_FPS, TextModules.fps(150));
        cap.offerShown(RewindFormat.HUD_ARMOR);
        assertEquals(3, sink.records.size());
        for (int i = 0; i < 50; i++) {
            tick();
            cap.offerRows(RewindFormat.HUD_HEIGHT_LIMIT, rows(27));
            cap.offerRuns(RewindFormat.HUD_FPS, TextModules.fps(150));
            cap.offerShown(RewindFormat.HUD_ARMOR);
        }
        assertEquals(3, sink.records.size());
    }

    @Test
    public void onlyTheFirstOfferAfterATickHeadCounts() {
        tick();
        cap.offerTimer(RewindFormat.HUD_DIAMOND_TIMER, 23);
        cap.frame();
        cap.offerTimer(RewindFormat.HUD_DIAMOND_TIMER, 22); // a second frame in the same tick
        assertEquals(1, sink.records.size());
        assertEquals(23, sink.last().a);
        tick();
        cap.offerTimer(RewindFormat.HUD_DIAMOND_TIMER, 22);
        assertEquals(2, sink.records.size());
        assertEquals(22, sink.last().a);
    }

    @Test
    public void aHiddenModuleWritesOneRecordWithTheFlagOff() {
        tick();
        cap.offerLines(RewindFormat.HUD_SESSION_STATS, session(3), VIEW);
        assertEquals(1, sink.records.size());
        tick(); // drawn this tick, Session Stats not offered
        assertEquals(1, sink.records.size());
        tick(); // at this head it was not offered during a drawn tick
        assertEquals(2, sink.records.size());
        HudModel.Values off = sink.last();
        assertEquals(RewindFormat.HUD_SESSION_STATS, off.module);
        assertFalse(off.onScreen);
        for (int i = 0; i < 10; i++) tick();
        assertEquals("written once", 2, sink.records.size());
        // back on screen with the same values: written again, values in full
        cap.offerLines(RewindFormat.HUD_SESSION_STATS, session(3), VIEW);
        assertEquals(3, sink.records.size());
        assertTrue(sink.last().onScreen);
        assertEquals(5, sink.last().lines.size());
    }

    @Test
    public void ticksWithoutADrawnHudChangeNothing() {
        tick();
        cap.offerShown(RewindFormat.HUD_KEYSTROKES);
        // low frame rate, F3 or Tab: several tick heads with no HUD frame in between
        for (int i = 0; i < 5; i++) cap.tickHead();
        assertEquals(1, sink.records.size());
    }

    /** A new segment and an empty record list, so each case below counts only its own records. */
    private void fresh() {
        cap.setSink(sink);
        sink.records.clear();
    }

    @Test
    public void aRowListThatChangesOneValueWritesOneRecord() {
        tick();
        cap.offerRows(RewindFormat.HUD_HEIGHT_LIMIT, rows(27));
        tick();
        cap.offerRows(RewindFormat.HUD_HEIGHT_LIMIT, rows(26));
        assertEquals(2, sink.records.size());
        assertEquals(rows(26), sink.last().rows);

        fresh();
        tick();
        cap.offerTeamRows(RewindFormat.HUD_TEAM_UPGRADES, team("4", 1f));
        tick();
        cap.offerTeamRows(RewindFormat.HUD_TEAM_UPGRADES, team("Full", 1f));
        assertEquals(2, sink.records.size());
        assertEquals("Full", sink.last().team.get(3).right);

        fresh();
        tick();
        cap.offerLines(RewindFormat.HUD_SESSION_STATS, session(3), VIEW);
        tick();
        cap.offerLines(RewindFormat.HUD_SESSION_STATS, session(4), VIEW);
        assertEquals(2, sink.records.size());
        assertEquals("Kills: 4", sink.last().lines.get(1).text);

        fresh();
        tick();
        cap.offerRuns(RewindFormat.HUD_CPS, TextModules.cps(9, 3));
        tick();
        cap.offerRuns(RewindFormat.HUD_CPS, TextModules.cps(9, 4));
        assertEquals(2, sink.records.size());
        assertEquals(4, sink.last().b);
    }

    @Test
    public void runsAreReadBeforeTheirLabelAndBrackets() {
        tick();
        cap.offerRuns(RewindFormat.HUD_PING, TextModules.bracketed(
                TextModules.labelled(TextModules.ping("27"), TextModules.Label.BEFORE)));
        assertEquals("27", sink.last().text);
    }

    @Test
    public void nothingIsWrittenWithoutASink() {
        cap.setSink(null);
        tick();
        cap.offerShown(RewindFormat.HUD_POTION);
        cap.offerRows(RewindFormat.HUD_HEIGHT_LIMIT, rows(1));
        assertEquals(0, sink.records.size());
        assertFalse(cap.active());
    }

    @Test
    public void aNewSegmentWritesEveryModuleAgain() {
        tick();
        cap.offerTimer(RewindFormat.HUD_DIAMOND_TIMER, 5);
        cap.setSink(sink); // segment boundary
        tick();
        cap.offerTimer(RewindFormat.HUD_DIAMOND_TIMER, 5);
        assertEquals(2, sink.records.size());
    }

    @Test
    public void aFailingSinkIsReportedOnceAndCaptureStops() {
        final int[] failed = {0};
        cap.setSink(new HudCapture.Sink() {
            @Override
            public boolean append(int tag, byte[] buf, int len) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void failed(String where, Throwable cause) {
                failed[0]++;
            }
        });
        tick();
        cap.offerShown(RewindFormat.HUD_POTION); // returns normally
        cap.offerRows(RewindFormat.HUD_HEIGHT_LIMIT, rows(3));
        tick();
        cap.offerShown(RewindFormat.HUD_ARMOR);
        assertEquals(1, failed[0]);
        assertFalse(cap.active());

        Recording full = new Recording() {
            @Override
            public boolean append(int tag, byte[] buf, int len) {
                return false;
            }
        };
        cap.setSink(full);
        tick();
        cap.offerShown(RewindFormat.HUD_POTION);
        assertEquals("no room is a failure too", 1, full.failures);
        assertFalse(cap.active());
    }

    /**
     * Review code-2 O7: when the recorder's rules stop recording the segment, the next offer finds the sink not
     * recording and switches capture off quietly: no failure is built or reported (no exception on the render thread).
     */
    @Test
    public void aSegmentThatStopsBeingRecordedTurnsCaptureOffWithoutAFailure() {
        final boolean[] recording = {true};
        Recording rules = new Recording() {
            @Override
            public boolean recording() {
                return recording[0];
            }

            @Override
            public boolean append(int tag, byte[] buf, int len) {
                return recording[0] && super.append(tag, buf, len);
            }
        };
        cap.setSink(rules);
        tick();
        cap.offerShown(RewindFormat.HUD_POTION);
        assertEquals(1, rules.records.size());
        recording[0] = false; // the rules drop the segment
        tick();
        cap.offerShown(RewindFormat.HUD_ARMOR);
        assertEquals("nothing built or reported", 0, rules.failures);
        assertNull(rules.cause);
        assertFalse("capture is off until the next segment", cap.active());
        recording[0] = true;
        cap.setSink(rules);
        tick();
        cap.offerShown(RewindFormat.HUD_ARMOR);
        assertEquals(2, rules.records.size());
    }

    @Test
    public void mousePressesGoToTheTapReceiver() {
        final List<Integer> taps = new ArrayList<Integer>();
        cap.mousePress(0); // no receiver: nothing
        cap.setTapReceiver(new HudCapture.TapReceiver() {
            @Override
            public void tap(int button) {
                taps.add(button);
            }
        });
        cap.mousePress(0);
        cap.mousePress(1);
        assertEquals(Arrays.asList(0, 1), taps);
        assertEquals("a tap is not a HUD record", 0, sink.records.size());
    }

    // ------------------------------------------------------------------ cost (A12, review B5)

    private static final int N = 100_000;

    /**
     * Freshly built, equal values every offer (new lists, new strings, as the painters produce them), and values that
     * change on every offer: either way capture allocates nothing.
     */
    @Test(timeout = 300_000)
    public void offersAllocateNothingWhetherValuesAreEqualOrChanged() {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long self = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(self);
        for (int i = 0; i < N; i++) keep = new byte[1 + (i & 7)];
        assertTrue("allocation meter works", mx.getThreadAllocatedBytes(self) - before >= N * 16L);

        // Distinct objects with equal contents (A, A2) and with one value changed (B); built before measuring.
        List<String> rowsA = rows(27), rowsA2 = rows(27), rowsB = rows(26);
        List<Line> sesA = session(3), sesA2 = session(3), sesB = session(4);
        List<TeamUpgradeRows.Row> teamA = team("4", 0.5f), teamA2 = team("4", 0.5f), teamB = team("Full", 0.25f);
        List<TextModules.Seg> fpsA = TextModules.fps(150), fpsA2 = TextModules.fps(150), fpsB = TextModules.fps(151);
        List<TextModules.Seg> cpsA = TextModules.cps(9, 3), cpsA2 = TextModules.cps(9, 3), cpsB = TextModules.cps(8, 3);
        List<TextModules.Seg> pingA = TextModules.ping(new String("27")), pingA2 = TextModules.ping(new String("27")),
                pingB = TextModules.ping("28");

        Counting count = new Counting();
        cap.setSink(count);
        long bestEqual = Long.MAX_VALUE, bestChanged = Long.MAX_VALUE;
        for (int round = 0; round < 4; round++) {
            // one tick with the A values, so the equal run below starts from what was written last
            cap.tickHead();
            cap.frame();
            cap.offerShown(RewindFormat.HUD_POTION);
            cap.offerTimer(RewindFormat.HUD_DIAMOND_TIMER, 23);
            cap.offerRuns(RewindFormat.HUD_FPS, fpsA);
            cap.offerRuns(RewindFormat.HUD_CPS, cpsA);
            cap.offerRuns(RewindFormat.HUD_PING, pingA);
            cap.offerRows(RewindFormat.HUD_HEIGHT_LIMIT, rowsA);
            cap.offerLines(RewindFormat.HUD_SESSION_STATS, sesA, VIEW);
            cap.offerTeamRows(RewindFormat.HUD_TEAM_UPGRADES, teamA);
            int start = count.records;
            long a = mx.getThreadAllocatedBytes(self);
            for (int i = 0; i < N; i++) {
                boolean odd = (i & 1) != 0;
                cap.tickHead();
                cap.frame();
                cap.offerShown(RewindFormat.HUD_POTION);
                cap.offerTimer(RewindFormat.HUD_DIAMOND_TIMER, 23);
                cap.offerRuns(RewindFormat.HUD_FPS, odd ? fpsA2 : fpsA);
                cap.offerRuns(RewindFormat.HUD_CPS, odd ? cpsA2 : cpsA);
                cap.offerRuns(RewindFormat.HUD_PING, odd ? pingA2 : pingA);
                cap.offerRows(RewindFormat.HUD_HEIGHT_LIMIT, odd ? rowsA2 : rowsA);
                cap.offerLines(RewindFormat.HUD_SESSION_STATS, odd ? sesA2 : sesA, VIEW);
                cap.offerTeamRows(RewindFormat.HUD_TEAM_UPGRADES, odd ? teamA2 : teamA);
            }
            long b = mx.getThreadAllocatedBytes(self);
            int equalRecords = count.records - start;
            for (int i = 0; i < N; i++) {
                boolean odd = (i & 1) != 0;
                cap.tickHead();
                cap.frame();
                cap.offerShown(RewindFormat.HUD_POTION);
                cap.offerTimer(RewindFormat.HUD_DIAMOND_TIMER, odd ? 22 : 23);
                cap.offerRuns(RewindFormat.HUD_FPS, odd ? fpsB : fpsA);
                cap.offerRuns(RewindFormat.HUD_CPS, odd ? cpsB : cpsA);
                cap.offerRuns(RewindFormat.HUD_PING, odd ? pingB : pingA);
                cap.offerRows(RewindFormat.HUD_HEIGHT_LIMIT, odd ? rowsB : rowsA);
                cap.offerLines(RewindFormat.HUD_SESSION_STATS, odd ? sesB : sesA, VIEW);
                cap.offerTeamRows(RewindFormat.HUD_TEAM_UPGRADES, odd ? teamB : teamA);
            }
            long c = mx.getThreadAllocatedBytes(self);
            assertEquals("equal values write nothing", 0, equalRecords);
            assertTrue("every changed offer writes", count.records - start - equalRecords >= 7L * (N - 1));
            bestEqual = Math.min(bestEqual, b - a);
            bestChanged = Math.min(bestChanged, c - b);
        }
        assertTrue("capture still on", cap.active());
        System.out.println("HudCapture A12 allocation over " + N + " ticks: equal " + bestEqual + " B, changed "
                + bestChanged + " B, " + count.bytes + " B of records");
        // the meter itself may report a few bytes; one allocation per offer would be >= 1.6 MB here
        assertTrue("equal offers allocate " + bestEqual + " B", bestEqual < 1024);
        assertTrue("changed offers allocate " + bestChanged + " B", bestChanged < 1024);
    }

    static volatile Object keep;

    // ------------------------------------------------------------------ the recorder never asks the HUD (A12)

    /** The HUD's data reads (HudSource); no recorder class may name one. */
    static final String[] HUD_DATA_METHODS = {"potionEntries", "heightLimitRows", "sessionLines", "teamUpgradesRows",
            "textRuns", "timerValue"};

    @Test
    public void noRecorderClassNamesAHudDataMethod() throws IOException {
        File root = new File(mainRoot(), "com/bedwarsqol/rewind");
        List<String> hits = new ArrayList<String>();
        for (File f : javaFiles(root)) hits.addAll(hudDataCalls(f.getPath(), strip(read(f))));
        assertTrue("recorder code names a HUD data method: " + hits, hits.isEmpty());
    }

    @Test
    public void theScanFindsAPlantedCall() {
        String planted = "class A { void a(HudSource s) { s.heightLimitRows(mc); /* textRuns */ } }\n// sessionLines\n";
        assertEquals(Arrays.asList("planted:1 heightLimitRows"), hudDataCalls("planted", strip(planted)));
    }

    static List<String> hudDataCalls(String name, String code) {
        List<String> out = new ArrayList<String>();
        for (String m : HUD_DATA_METHODS) {
            Matcher x = Pattern.compile("\\b" + m + "\\b").matcher(code);
            while (x.find()) {
                int line = 1;
                for (int i = 0; i < x.start(); i++) if (code.charAt(i) == '\n') line++;
                out.add(name + ":" + line + " " + m);
            }
        }
        return out;
    }

    /** {@code ornithe/src/main/java}, from Gradle's working directory (the project) or the repo root. */
    static File mainRoot() {
        for (String p : new String[]{"src/main/java", "ornithe/src/main/java"}) {
            File f = new File(p);
            if (new File(f, "com/bedwarsqol/rewind/HudCapture.java").isFile()) return f;
        }
        throw new AssertionError("ornithe/src/main/java not found from " + new File(".").getAbsolutePath());
    }

    static List<File> javaFiles(File dir) {
        List<File> out = new ArrayList<File>();
        collect(dir, out);
        if (out.isEmpty()) throw new AssertionError("no sources under " + dir);
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collect(k, out);
            else if (k.getName().endsWith(".java")) out.add(k);
        }
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Blanks comments (newlines kept) so documentation may name what code may not. */
    static String strip(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i), next = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
