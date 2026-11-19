package com.bedwarsqol.hud;

import com.bedwarsqol.feature.TeamUpgradeRows;
import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** PLAN Phase 4 / TASK A11: each HUD module's values encode and decode equal ({@link HudModel}). */
public class HudModelCodecTest {

    private final ExtCodec codec = new ExtCodec();

    /** The body of the record in {@code codec.buffer()[0, n)} (its varint length dropped). */
    private HudModel.Values decode(int n) {
        byte[] b = codec.buffer();
        RewindFormat.Cursor c = new RewindFormat.Cursor(b, 0, n);
        int len = c.varInt();
        assertEquals("length prefix covers the body", n - c.pos(), len);
        return HudModel.decode(b, c.pos(), len);
    }

    @Test
    public void flagOnlyModulesCarryNoValues() {
        for (int m : new int[]{RewindFormat.HUD_POTION, RewindFormat.HUD_ARMOR, RewindFormat.HUD_INVENTORY,
                RewindFormat.HUD_KEYSTROKES}) {
            assertEquals(HudModel.SHAPE_NONE, HudModel.shape(m));
            int n = codec.beginHud(m, RewindFormat.HUD_FLAG_ON_SCREEN).end();
            assertEquals("len, module, flags", 3, n);
            HudModel.Values v = decode(n);
            assertEquals(m, v.module);
            assertTrue(v.onScreen);
        }
    }

    @Test
    public void fpsAndTimersRoundTripTheirNumber() {
        int[][] cases = {{RewindFormat.HUD_FPS, 150}, {RewindFormat.HUD_FPS, 0}, {RewindFormat.HUD_DIAMOND_TIMER, 23},
                {RewindFormat.HUD_EMERALD_TIMER, -1}, {RewindFormat.HUD_EMERALD_TIMER, 65}};
        for (int[] c : cases) {
            assertEquals(HudModel.SHAPE_INT, HudModel.shape(c[0]));
            int n = codec.beginHud(c[0], RewindFormat.HUD_FLAG_ON_SCREEN).putVarInt(c[1]).end();
            HudModel.Values v = decode(n);
            assertEquals(c[0], v.module);
            assertEquals(c[1], v.a);
        }
    }

    @Test
    public void cpsRoundTripsBothSides() {
        int n = codec.beginHud(RewindFormat.HUD_CPS, RewindFormat.HUD_FLAG_ON_SCREEN).putVarInt(9).putVarInt(3).end();
        HudModel.Values v = decode(n);
        assertEquals(HudModel.SHAPE_INT2, HudModel.shape(RewindFormat.HUD_CPS));
        assertEquals(9, v.a);
        assertEquals(3, v.b);
    }

    @Test
    public void pingRoundTripsItsText() {
        for (String s : new String[]{"27", "--", "1234"}) {
            int n = codec.beginHud(RewindFormat.HUD_PING, RewindFormat.HUD_FLAG_ON_SCREEN).putString(s).end();
            assertEquals(s, decode(n).text);
        }
    }

    @Test
    public void mapInfoRowsRoundTrip() {
        String[] rows = {"Map: Lighthouse", "Height Limit: 110", "Distance: 27"};
        codec.beginHud(RewindFormat.HUD_HEIGHT_LIMIT, RewindFormat.HUD_FLAG_ON_SCREEN);
        HudModel.putRows(codec, rows, rows.length);
        HudModel.Values v = decode(codec.end());
        assertEquals(Arrays.asList(rows), v.rows);
        // Unicode survives (a map name is the scoreboard's text)
        String[] odd = {"Map: Ærial — ☆", ""};
        codec.beginHud(RewindFormat.HUD_HEIGHT_LIMIT, RewindFormat.HUD_FLAG_ON_SCREEN);
        HudModel.putRows(codec, odd, odd.length);
        assertEquals(Arrays.asList(odd), decode(codec.end()).rows);
    }

    @Test
    public void sessionLinesRoundTripWithTheirKind() {
        String[] text = {"Game", "Kills: 3", "Session", "Finals: 12 / FKDR: 3.00", ""};
        int[] kinds = {HudModel.LINE_HEADER, HudModel.LINE_ROW, HudModel.LINE_HEADER, HudModel.LINE_ROW,
                HudModel.LINE_ROW};
        codec.beginHud(RewindFormat.HUD_SESSION_STATS, RewindFormat.HUD_FLAG_ON_SCREEN);
        HudModel.putLines(codec, text, kinds, text.length);
        HudModel.Values v = decode(codec.end());
        assertEquals(text.length, v.lines.size());
        for (int i = 0; i < text.length; i++) {
            assertEquals(new HudModel.Line(kinds[i], text[i]), v.lines.get(i));
        }
        assertTrue(v.lines.get(0).header());
        assertFalse(v.lines.get(1).header());
    }

    @Test
    public void teamRowsRoundTripEveryField() {
        List<TeamUpgradeRows.Row> rows = TeamUpgradeRows.example(TeamUpgradeRows.Layout.FULL);
        TeamUpgradeRows.Row fading = new TeamUpgradeRows.Row(TeamUpgradeRows.Kind.TRAP, "1 Blindness", "", "",
                TeamUpgradeRows.Style.FIRED, 0.4f);
        TeamUpgradeRows.Row[] all = rows.toArray(new TeamUpgradeRows.Row[rows.size() + 1]);
        all[rows.size()] = fading;
        codec.beginHud(RewindFormat.HUD_TEAM_UPGRADES, RewindFormat.HUD_FLAG_ON_SCREEN);
        HudModel.putTeamRows(codec, all, all.length);
        HudModel.Values v = decode(codec.end());
        assertEquals(all.length, v.team.size());
        for (int i = 0; i < all.length; i++) {
            TeamUpgradeRows.Row a = all[i], b = v.team.get(i);
            assertEquals(a.toString(), b.toString());
            assertEquals(HudModel.alpha8(a.alpha), HudModel.alpha8(b.alpha));
            assertTrue(HudModel.sameRow(a, b));
        }
        // the painter's own rounding of a fading row is what playback gets back
        assertEquals(Math.round(0.4f * 255f), Math.round(v.team.get(all.length - 1).alpha * 255f));
    }

    @Test
    public void offScreenRecordEndsAfterItsFlags() {
        int n = codec.beginHud(RewindFormat.HUD_SESSION_STATS, 0).end();
        assertEquals(3, n);
        HudModel.Values v = decode(n);
        assertFalse(v.onScreen);
        assertTrue(v.lines.isEmpty());
    }

    @Test
    public void sameRowComparesStoredFields() {
        TeamUpgradeRows.Row a = new TeamUpgradeRows.Row(TeamUpgradeRows.Kind.UPGRADE, "Sharpness", "", "Max",
                TeamUpgradeRows.Style.NORMAL, 1f);
        assertTrue(HudModel.sameRow(a, new TeamUpgradeRows.Row(TeamUpgradeRows.Kind.UPGRADE, new String("Sharpness"),
                "", "Max", TeamUpgradeRows.Style.NORMAL, 0.999f)));
        assertFalse(HudModel.sameRow(a, new TeamUpgradeRows.Row(TeamUpgradeRows.Kind.UPGRADE, "Sharpness", "", "4",
                TeamUpgradeRows.Style.NORMAL, 1f)));
        assertFalse(HudModel.sameRow(a, new TeamUpgradeRows.Row(TeamUpgradeRows.Kind.UPGRADE, "Sharpness", "", "Max",
                TeamUpgradeRows.Style.DIM, 1f)));
        assertFalse(HudModel.sameRow(a, new TeamUpgradeRows.Row(TeamUpgradeRows.Kind.UPGRADE, "Sharpness", "", "Max",
                TeamUpgradeRows.Style.NORMAL, 0.5f)));
        assertTrue(HudModel.same(null, null));
        assertFalse(HudModel.same("a", null));
    }

    @Test
    public void malformedValuesAreAFormatError() {
        byte[][] bad = {
                {(byte) RewindFormat.HUD_SESSION_STATS, 1, 1, 7, 0},           // line kind 7
                {(byte) RewindFormat.HUD_TEAM_UPGRADES, 1, 1, 0, 0, 0},       // row cut after its label
                {(byte) RewindFormat.HUD_HEIGHT_LIMIT, 1, 5, 1, 'a'},          // 5 rows promised, 1 there
                {(byte) RewindFormat.HUD_PING, 1},                             // no text
                {(byte) 0x7F, 1},                                              // not a module
        };
        for (byte[] b : bad) {
            try {
                HudModel.decode(b, 0, b.length);
                fail("decoded " + Arrays.toString(b));
            } catch (RewindFormat.FormatException expected) {
                // ok
            }
        }
    }

    /** Code review 1, O5: the stored ids are fixed numbers (the ordinals the first v2 build wrote), not ordinals. */
    @Test
    public void teamRowIdsArePinned() {
        int[] kinds = {HudModel.TEAM_KIND_HEADER, HudModel.TEAM_KIND_UPGRADE, HudModel.TEAM_KIND_TRAP,
                HudModel.TEAM_KIND_NEXT, HudModel.TEAM_KIND_NONE};
        assertEquals(Arrays.toString(kinds), "[0, 1, 2, 3, 4]");
        assertEquals(0, HudModel.teamKindId(TeamUpgradeRows.Kind.HEADER));
        assertEquals(1, HudModel.teamKindId(TeamUpgradeRows.Kind.UPGRADE));
        assertEquals(2, HudModel.teamKindId(TeamUpgradeRows.Kind.TRAP));
        assertEquals(3, HudModel.teamKindId(TeamUpgradeRows.Kind.NEXT));
        assertEquals(4, HudModel.teamKindId(TeamUpgradeRows.Kind.NONE));
        assertEquals(0, HudModel.teamStyleId(TeamUpgradeRows.Style.NORMAL));
        assertEquals(1, HudModel.teamStyleId(TeamUpgradeRows.Style.DIM));
        assertEquals(2, HudModel.teamStyleId(TeamUpgradeRows.Style.FIRED));
        for (TeamUpgradeRows.Kind k : TeamUpgradeRows.Kind.values()) {
            assertEquals(k, HudModel.teamKind(HudModel.teamKindId(k)));
        }
        for (TeamUpgradeRows.Style st : TeamUpgradeRows.Style.values()) {
            assertEquals(st, HudModel.teamStyle(HudModel.teamStyleId(st)));
        }
        // the bytes on disk: a TRAP row in FIRED style stores 2 and 2
        codec.beginHud(RewindFormat.HUD_TEAM_UPGRADES, RewindFormat.HUD_FLAG_ON_SCREEN);
        HudModel.putTeamRows(codec, new TeamUpgradeRows.Row[]{new TeamUpgradeRows.Row(TeamUpgradeRows.Kind.TRAP,
                "a", "", "", TeamUpgradeRows.Style.FIRED, 1f)}, 1);
        int n = codec.end();
        byte[] b = codec.buffer();
        // len, module, flags, count, kind, "a" (len 1, 'a'), "" , "", style, alpha
        assertEquals(2, b[4]);
        assertEquals(2, b[9]);
        assertEquals(11, n);
    }

    /** O5: a row id a later build added is not a format error: an unknown kind skips that row, an unknown style is NORMAL. */
    @Test
    public void unknownTeamRowIdsAreTolerated() {
        byte[] body = {
                (byte) RewindFormat.HUD_TEAM_UPGRADES, (byte) RewindFormat.HUD_FLAG_ON_SCREEN, 3,
                1, 1, 'S', 0, 1, '4', 0, (byte) 255,        // UPGRADE "S" "" "4" NORMAL
                9, 1, 'X', 0, 0, 0, (byte) 255,             // kind 9: unknown, skipped
                2, 1, 'B', 0, 0, 7, (byte) 128,             // TRAP "B" style 7: unknown, NORMAL
        };
        HudModel.Values v = HudModel.decode(body, 0, body.length);
        assertEquals(2, v.team.size());
        assertEquals("UPGRADE|S||4|NORMAL", v.team.get(0).toString());
        assertEquals("TRAP|B|||NORMAL", v.team.get(1).toString());
        assertEquals(128, HudModel.alpha8(v.team.get(1).alpha));
    }

    @Test
    public void everyHudModuleHasAShape() {
        for (int m = RewindFormat.HUD_POTION; m <= RewindFormat.HUD_PING; m++) {
            assertTrue("module " + m, HudModel.shape(m) >= 0);
        }
        assertEquals(-1, HudModel.shape(RewindFormat.HUD_ITEM_SCOREBOARD));
        assertEquals(-1, HudModel.shape(0));
    }
}
