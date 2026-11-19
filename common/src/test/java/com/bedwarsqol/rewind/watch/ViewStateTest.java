package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The single owner of what the view shows (PLAN "B-seam", Q2, Q3, B4, D6): the view, the perspective, hidden HUD,
 * F3, Tab and the recorded screen, fed by the recording ({@link ViewState#recorded}) and by the viewer's keys. Phase 5
 * pins the rules; the parts that follow them come later.
 */
public class ViewStateTest {

    private static final int HIDE = RewindFormat.VIEW_HIDE_GUI;
    private static final int F3 = RewindFormat.VIEW_F3;
    private static final int TAB = RewindFormat.VIEW_TAB;

    @Test
    public void aNewRecordingStartsInYourViewInFirstPersonWithTheHudShown() {
        ViewState v = new ViewState();
        v.selectFree();
        v.toggleClean();
        v.reset();
        assertSame(ViewState.View.YOU, v.view());
        assertTrue(v.yourView());
        assertEquals(0, v.perspective());
        assertFalse(v.hideGui());
        assertFalse(v.showDebug());
        assertFalse(v.tabList());
        assertFalse(v.clean());
        assertFalse(v.overridden());
        assertEquals(RewindFormat.SCREEN_NONE, v.screenKind());
    }

    @Test
    public void f5CyclesFirstBehindFrontInYourViewOnly() {
        ViewState v = new ViewState();
        int[] seen = new int[4];
        for (int i = 0; i < 4; i++) {
            assertTrue(v.cyclePerspective());
            seen[i] = v.perspective();
        }
        assertEquals(1, seen[0]);
        assertEquals(2, seen[1]);
        assertEquals(0, seen[2]);
        assertEquals(1, seen[3]);
        v.selectFree();
        assertFalse("ignored in the free camera", v.cyclePerspective());
        assertEquals("the free camera is first person", 0, v.perspective());
        v.selectYou();
        assertEquals("key 1 clears the F5 choice", 0, v.perspective());
        assertFalse(v.overridden());
    }

    @Test
    public void yourViewFollowsTheRecordedViewUntilF5OrTheCleanKeyAndKeyOneGivesItBack() {
        ViewState v = new ViewState();
        v.recorded(2, HIDE | F3 | TAB, RewindFormat.SCREEN_INVENTORY);
        assertTrue(v.hasRecorded());
        assertEquals(2, v.perspective());
        assertTrue(v.hideGui());
        assertTrue(v.showDebug());
        assertTrue(v.tabList());
        assertEquals(RewindFormat.SCREEN_INVENTORY, v.screenKind());

        assertTrue(v.cyclePerspective()); // from the recorded front view
        assertTrue(v.overridden());
        assertEquals(0, v.perspective());
        assertFalse("the override drops the recorded F1", v.hideGui());
        assertFalse(v.showDebug());
        assertFalse(v.tabList());
        assertEquals("recorded screens are not part of the F5 override", RewindFormat.SCREEN_INVENTORY,
                v.screenKind());
        v.recorded(1, 0, RewindFormat.SCREEN_NONE);
        assertEquals("a later record does not end the override", 0, v.perspective());

        v.selectYou();
        assertFalse(v.overridden());
        assertEquals(1, v.perspective());
    }

    @Test
    public void theCleanViewHidesTheHudAndRecordedScreensInEveryViewUntilPressedAgain() {
        ViewState v = new ViewState();
        v.recorded(0, 0, RewindFormat.SCREEN_CHEST);
        v.toggleClean();
        assertTrue(v.clean());
        assertTrue(v.hideGui());
        assertEquals(RewindFormat.SCREEN_NONE, v.screenKind());
        v.selectFree();
        assertTrue("also in the free camera", v.hideGui());
        v.toggleClean();
        assertFalse(v.clean());
        assertFalse(v.hideGui());
        v.selectYou();
        v.toggleClean();
        v.selectYou();
        assertFalse("key 1 clears the clean view too", v.clean());
        assertEquals(RewindFormat.SCREEN_CHEST, v.screenKind());
    }

    @Test
    public void theFreeCameraAndARiddenViewShowNoRecordedViewState() {
        ViewState v = new ViewState();
        v.recorded(1, HIDE | F3 | TAB, RewindFormat.SCREEN_CHEST);
        assertTrue(v.selectFree());
        assertFalse("already there", v.selectFree());
        assertEquals(0, v.perspective());
        assertFalse(v.hideGui());
        assertFalse(v.showDebug());
        assertFalse(v.tabList());
        assertEquals(RewindFormat.SCREEN_NONE, v.screenKind());
        v.ride(42);
        assertSame(ViewState.View.RIDDEN, v.view());
        assertEquals(42, v.riddenEntityId());
        assertEquals(0, v.perspective());
        assertEquals(RewindFormat.SCREEN_NONE, v.screenKind());
        assertTrue(v.selectYou());
        assertEquals(-1, v.riddenEntityId());
    }

    /** B4 (Phase 7): your view hides the puppet only in first person; F5's behind and front views show it. */
    @Test
    public void thePuppetIsHiddenInYourViewOnlyInFirstPerson() {
        ViewState v = new ViewState();
        assertTrue("first person: the camera is you", v.puppetHidden(true, false));
        assertTrue(v.cyclePerspective());
        assertFalse("F5 behind: the recorded body", v.puppetHidden(true, false));
        assertTrue(v.cyclePerspective());
        assertFalse("F5 front", v.puppetHidden(true, false));
        assertTrue(v.cyclePerspective());
        assertTrue("first person again", v.puppetHidden(true, false));
        v.recorded(1, 0, RewindFormat.SCREEN_NONE);
        v.selectYou();
        assertFalse("a recorded behind view shows it too", v.puppetHidden(true, false));
        v.selectFree();
        assertFalse(v.puppetHidden(true, false));
        assertTrue("recorded invisible", v.puppetHidden(true, true));
        assertTrue("nothing sampled yet", v.puppetHidden(false, false));
        v.ride(7);
        assertFalse("another player's view sees you", v.puppetHidden(true, false));
    }

    /**
     * Coordinator amendment 1 + B1 (Phase 7): what the camera's {@code isSpectator()} answers, which is the only input
     * vanilla's {@code Entity.isInvisibleToPlayer} reads from the viewer (an invisible entity is drawn translucent for
     * a spectator viewer), and what hides the bare arm's body parts and the water and fire overlays. True only in your
     * view while the recorded game mode is spectator: never in your view otherwise, never in the free camera, never in
     * another player's view.
     */
    @Test
    public void theCameraIsASpectatorOnlyInYourViewOfARecordedSpectator() {
        int[] modes = {ViewGameType.NOT_SET, ViewGameType.SURVIVAL, ViewGameType.CREATIVE, ViewGameType.ADVENTURE,
                ViewGameType.SPECTATOR};
        ViewState v = new ViewState();
        for (int view = 0; view < 3; view++) {
            if (view == 0) {
                v.selectYou();
            } else if (view == 1) {
                v.selectFree();
            } else {
                v.ride(9);
            }
            for (int mode : modes) {
                boolean expected = view == 0 && mode == ViewGameType.SPECTATOR;
                assertEquals("view " + v.view() + " mode " + mode, expected, v.cameraSpectator(mode));
            }
        }
        v.selectYou();
        v.cyclePerspective();
        assertTrue("F5 keeps it", v.cameraSpectator(ViewGameType.SPECTATOR));
        v.toggleClean();
        assertTrue("the clean view keeps it", v.cameraSpectator(ViewGameType.SPECTATOR));
    }

    @Test
    public void everyChangeBumpsTheVersionSoConsumersApplyItOnce() {
        ViewState v = new ViewState();
        int a = v.version();
        v.cyclePerspective();
        int b = v.version();
        assertTrue(b != a);
        v.recorded(0, 0, 0);
        v.recorded(0, 0, 0);
        int c = v.version();
        v.recorded(0, 0, 0);
        assertEquals("an unchanged record changes nothing", c, v.version());
    }
}
