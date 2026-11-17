package com.bedwarsqol.rewind;

import com.bedwarsqol.feature.HypixelLocationCodec.Location;
import com.bedwarsqol.feature.SessionChatLine.Kind;
import com.bedwarsqol.rewind.MarkerRules.Marker;
import com.bedwarsqol.rewind.RewindGameRules.Command;
import com.bedwarsqol.rewind.RewindGameRules.CommandType;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Marker kinds, the keep-forever rule and when a marker is accepted (A7). */
public class MarkerRulesTest {

    private static final long S = RewindGameRules.SECOND_US;
    private static final Location BW_GAME =
            new Location("mini12A", "BEDWARS", null, "BEDWARS_EIGHT_ONE", "Lighthouse");
    private static final Location MAIN_LOBBY = new Location("lobby7", "MAIN", "lobby7", null, null);

    @Test
    public void onlyTheOwnKindsMapToMarkers() {
        assertEquals(Marker.DEATH, MarkerRules.forLine(Kind.DEATH));
        assertEquals(Marker.DEATH, MarkerRules.forLine(Kind.FINAL_DEATH));
        assertEquals(Marker.FINAL_KILL, MarkerRules.forLine(Kind.FINAL_KILL));
        assertEquals(Marker.BED_BROKEN, MarkerRules.forLine(Kind.BED_BREAK));
        assertEquals(Marker.BED_LOST, MarkerRules.forLine(Kind.BED_LOST));
        assertNull(MarkerRules.forLine(Kind.KILL));
        assertNull(MarkerRules.forLine(Kind.WIN));
        assertNull(MarkerRules.forLine(Kind.LOSS));
        assertNull(MarkerRules.forLine(Kind.ELIMINATED));
        assertNull(MarkerRules.forLine(Kind.GAME_END));
        assertNull(MarkerRules.forLine(null));
    }

    @Test
    public void everyKindIsMappedOrExplicitlyIgnored() {
        int mapped = 0;
        for (Kind k : Kind.values()) {
            if (MarkerRules.forLine(k) != null) mapped++;
        }
        assertEquals(5, mapped);   // DEATH, FINAL_DEATH, FINAL_KILL, BED_BREAK, BED_LOST
    }

    @Test
    public void markerCodesMatchTheTrackFormat() {
        assertEquals(1, Marker.KEY.code);
        assertEquals(2, Marker.DEATH.code);
        assertEquals(3, Marker.FINAL_KILL.code);
        assertEquals(4, Marker.BED_BROKEN.code);
        assertEquals(5, Marker.BED_LOST.code);
        for (Marker m : Marker.values()) assertEquals(m, Marker.fromCode(m.code));
        assertNull(Marker.fromCode(0));
        assertNull(Marker.fromCode(6));
    }

    @Test
    public void onlyTheKeyMarkerKeepsAGameForever() {
        assertTrue(MarkerRules.keepsForever(Marker.KEY));
        assertFalse(MarkerRules.keepsForever(Marker.DEATH));
        assertFalse(MarkerRules.keepsForever(Marker.FINAL_KILL));
        assertFalse(MarkerRules.keepsForever(Marker.BED_BROKEN));
        assertFalse(MarkerRules.keepsForever(Marker.BED_LOST));
    }

    @Test
    public void acceptedOnlyWithAnOpenGameOrAPendingGameServerRun() {
        assertTrue(MarkerRules.accepts(true, false));
        assertTrue(MarkerRules.accepts(false, true));
        assertFalse(MarkerRules.accepts(false, false));
    }

    @Test
    public void ownMarkersInAnOpenGameAreEmittedAndOnlyTheKeyFlashes() {
        RewindGameRules r = openBedwarsGame();
        List<Command> c = r.onGameLine(70 * S, Kind.FINAL_KILL);
        assertEquals(1, c.size());
        assertEquals(CommandType.MARKER, c.get(0).type);
        assertEquals(Marker.FINAL_KILL, c.get(0).marker);
        assertEquals(70 * S, c.get(0).timeUs);
        assertFalse(c.get(0).keyMarked);

        assertTrue(r.onGameLine(71 * S, Kind.KILL).isEmpty());          // not a marker kind

        c = r.onMarkerKey(72 * S);
        assertEquals(2, c.size());
        assertEquals(CommandType.MARKER, c.get(0).type);
        assertEquals(Marker.KEY, c.get(0).marker);
        assertTrue(c.get(0).keyMarked);                                 // keep forever
        assertEquals(CommandType.FLASH, c.get(1).type);

        c = r.onGameLine(73 * S, Kind.BED_LOST);
        assertEquals(Marker.BED_LOST, c.get(0).marker);
        assertTrue("the game stays key-marked", c.get(0).keyMarked);

        c = r.onGameLine(74 * S, Kind.WIN);
        r.tick(74 * S + 15 * S, false, true);
        Command close = only(r.tick(90 * S, false, true), CommandType.CLOSE);
        assertNull("closed already by the 15 s timer", close);
    }

    @Test
    public void outsideAGameMarkersAreIgnoredAndNothingFlashes() {
        RewindGameRules r = new RewindGameRules();
        assertTrue(r.onMarkerKey(0).isEmpty());                          // not even connected
        r.onConnect(0, false, true);
        assertTrue(r.onMarkerKey(1).isEmpty());                          // no world yet
        r.onBoundary(S, 1);
        r.onLocation(S + 1, 1, MAIN_LOBBY);
        r.tick(2 * S, false, false);
        assertTrue(r.onMarkerKey(3 * S).isEmpty());                      // lobby verdict pending
        assertTrue(r.onGameLine(3 * S, Kind.DEATH).isEmpty());
        r.tick(12 * S, false, false);                                    // lobby dropped
        assertTrue(r.onMarkerKey(13 * S).isEmpty());
    }

    @Test
    public void aMarkerBeforeAnyLocationIsIgnored() {
        RewindGameRules r = new RewindGameRules();
        r.onConnect(0, false, true);
        r.onBoundary(S, 1);
        assertTrue(r.onMarkerKey(2 * S).isEmpty());                      // unclassified run
    }

    @Test
    public void aPendingKeyMarkerIsAppliedOnOpen() {
        RewindGameRules r = new RewindGameRules();
        r.onConnect(0, false, true);
        r.onBoundary(S, 1);
        r.onLocation(S + 100, 1, BW_GAME);
        r.tick(2 * S, true, false);                                      // pregame queue, pending
        List<Command> c = r.onMarkerKey(5 * S);
        assertEquals(CommandType.MARKER, c.get(0).type);
        assertEquals(Marker.KEY, c.get(0).marker);
        assertEquals(CommandType.FLASH, c.get(1).type);
        r.onGameLine(6 * S, Kind.DEATH);                                 // a pregame death, own

        Command open = only(r.tick(30 * S, false, true), CommandType.OPEN);
        assertTrue(open.keyMarked);
        assertEquals(2, open.markers.size());
        assertEquals(Marker.KEY, open.markers.get(0).marker);
        assertEquals(5 * S, open.markers.get(0).timeUs);
        assertEquals(Marker.DEATH, open.markers.get(1).marker);
        assertEquals(6 * S, open.markers.get(1).timeUs);
    }

    @Test
    public void aPendingKeyMarkerOnARunThatIsDroppedKeepsNothing() {
        RewindGameRules r = new RewindGameRules();
        r.onConnect(0, false, true);
        r.onBoundary(S, 1);
        r.onLocation(S + 100, 1, BW_GAME);
        r.tick(2 * S, true, false);
        r.onMarkerKey(5 * S);
        r.onBoundary(20 * S, 2);                                         // leave the queue
        r.onLocation(20 * S + 100, 2, MAIN_LOBBY);
        r.onBoundary(40 * S, 3);
        r.onLocation(40 * S + 100, 3, new Location("mini99B", "BEDWARS", null, "BEDWARS_EIGHT_TWO", "Aquarium"));
        r.tick(41 * S, true, false);
        Command open = only(r.tick(60 * S, false, true), CommandType.OPEN);
        assertFalse(open.keyMarked);
        assertTrue(open.markers.isEmpty());
    }

    @Test
    public void aFailedGameIgnoresMarkers() {
        RewindGameRules r = openBedwarsGame();
        r.onFailure(70 * S);
        assertTrue(r.onMarkerKey(71 * S).isEmpty());
        assertTrue(r.onGameLine(72 * S, Kind.FINAL_KILL).isEmpty());
    }

    private static RewindGameRules openBedwarsGame() {
        RewindGameRules r = new RewindGameRules();
        r.onConnect(0, false, true);
        r.onBoundary(S, 1);
        r.onLocation(S + 100, 1, BW_GAME);
        r.tick(2 * S, true, false);
        assertTrue(only(r.tick(60 * S, false, true), CommandType.OPEN) != null);
        return r;
    }

    static Command only(List<Command> commands, CommandType type) {
        List<Command> found = new ArrayList<Command>();
        for (Command c : commands) if (c.type == type) found.add(c);
        if (found.size() > 1) throw new AssertionError("more than one " + type + ": " + commands);
        return found.isEmpty() ? null : found.get(0);
    }
}
