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
                {(byte) RewindFormat.HUD_TEAM_UPGRADES, 1, 1, 99, 0, 0, 0, 0, 0}, // row kind 99
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

    @Test
    public void everyHudModuleHasAShape() {
        for (int m = RewindFormat.HUD_POTION; m <= RewindFormat.HUD_PING; m++) {
            assertTrue("module " + m, HudModel.shape(m) >= 0);
        }
        assertEquals(-1, HudModel.shape(RewindFormat.HUD_ITEM_SCOREBOARD));
        assertEquals(-1, HudModel.shape(0));
    }
}
