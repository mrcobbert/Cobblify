package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * TASK D1, D4 (PLAN "R3 Watch full"): the players another player's view can ride, as the camera key's menu lists them
 * and keys 3 / Shift+3 step through them, sorted by team then name, each with the team's colour; and the ridden view's
 * rule when its player leaves the world: it becomes the free camera where the view was (only once playback is
 * settled, so a seek's rebuild does not throw the view out).
 */
public class PlayerListModelTest {

    private static PlayerListModel fourPlayers() {
        PlayerListModel m = new PlayerListModel();
        m.add(5, "zed", "Blue", "§9zed");
        m.add(3, "amy", "Red", "§c§lR §camy");
        m.add(9, "cat", null, null);
        m.add(7, "Bob", "Blue", "§9Bob");
        return m;
    }

    private static List<String> names(PlayerListModel m) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < m.size(); i++) out.add(m.get(i).name);
        return out;
    }

    @Test
    public void sortedByTeamThenNameWithTheTeamlessLast() {
        PlayerListModel m = fourPlayers();
        assertEquals(Arrays.asList("Bob", "zed", "amy", "cat"), names(m));
        assertEquals(7, m.get(0).entityId);
        assertEquals("Blue", m.get(0).team);
        assertEquals("the team's colour", 0xFF5555FF, m.get(0).colour);
        assertEquals(0xFFFF5555, m.get(2).colour);
        assertEquals("no team: white", PlayerListModel.WHITE, m.get(3).colour);
        assertEquals(2, m.indexOf(3));
        assertEquals(-1, m.indexOf(4));
        assertTrue(m.contains(9));
        assertFalse(m.contains(4));
    }

    @Test
    public void refillingReplacesTheList() {
        PlayerListModel m = fourPlayers();
        m.clear();
        assertEquals(0, m.size());
        m.add(11, "dee", "Green", "§adee");
        assertEquals(Arrays.asList("dee"), names(m));
        assertEquals(0xFF55FF55, m.get(0).colour);
    }

    @Test
    public void keyThreeRidesTheNextPlayerAndShiftThreeThePreviousWrappingAround() {
        PlayerListModel m = fourPlayers(); // Bob 7, zed 5, amy 3, cat 9
        assertEquals("from your view or the free camera: the first", 7, m.cycle(-1, 1));
        assertEquals("Shift+3 from outside: the last", 9, m.cycle(-1, -1));
        assertEquals(5, m.cycle(7, 1));
        assertEquals(3, m.cycle(5, 1));
        assertEquals("after the last: the first", 7, m.cycle(9, 1));
        assertEquals("before the first: the last", 9, m.cycle(7, -1));
        assertEquals(5, m.cycle(3, -1));
        assertEquals("a ridden player no longer listed: from the start", 7, m.cycle(42, 1));
        assertEquals(-1, new PlayerListModel().cycle(-1, 1));
        assertEquals("nobody to ride", -1, new PlayerListModel().cycle(7, -1));
    }

    @Test
    public void theColourIsTheNameTagsFirstColourCode() {
        assertEquals(0xFFFF5555, PlayerListModel.colour("§l§cR §cname"));
        assertEquals(0xFF55FF55, PlayerListModel.colour("§Aname"));
        assertEquals(0xFF555555, PlayerListModel.colour("§8[S] name"));
        assertEquals(0xFFFFFFFF, PlayerListModel.colour("§fname"));
        assertEquals(0xFF000000, PlayerListModel.colour("§0name"));
        assertEquals(PlayerListModel.WHITE, PlayerListModel.colour("name"));
        assertEquals(PlayerListModel.WHITE, PlayerListModel.colour("name§"));
        assertEquals(PlayerListModel.WHITE, PlayerListModel.colour(null));
    }

    /** D4. */
    @Test
    public void whenTheRiddenPlayerLeavesTheWorldTheViewBecomesTheFreeCameraWhereItWas() {
        PlayerListModel m = fourPlayers();
        double[] pose = new double[5];
        assertSame(PlayerListModel.Step.FOLLOW, m.follow(7, true, true, 1, 65.62, 3, 10f, 5f));
        assertSame(PlayerListModel.Step.FOLLOW, m.follow(7, true, true, 2, 66.62, 4, 20f, -5f));
        assertSame("gone while a seek rebuilds the world: wait for it", PlayerListModel.Step.WAIT,
                m.absent(7, false));
        assertSame(PlayerListModel.Step.LEAVE, m.absent(7, true));
        assertTrue(m.lastPose(7, pose));
        assertArrayEquals("where the view was: the last pose it showed", new double[]{2, 66.62, 4, 20, -5}, pose, 0);

        // a newly ridden player that never appears leaves with no pose of its own (the camera stays where it is)
        assertSame(PlayerListModel.Step.LEAVE, m.absent(5, true));
        assertFalse(m.lastPose(5, pose));
        assertFalse("another player's pose is not taken", m.lastPose(7, pose));

        assertSame("not riding", PlayerListModel.Step.NONE, m.absent(-1, true));
        assertSame(PlayerListModel.Step.NONE, m.follow(-1, true, true, 0, 0, 0, 0, 0));
    }
}
