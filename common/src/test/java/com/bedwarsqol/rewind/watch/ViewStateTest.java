package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
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

    // ------------------------------------------------------------------ Phase 12: the recorded view by playback time (C4)

    private static final long MS = 1_000L;

    /** A v2 recording's extension records (segment, time, then the encoded record). */
    private static final class Rec {
        private final ExtCodec codec = new ExtCodec();
        private final ExtRecords.Builder b = new ExtRecords.Builder();

        Rec view(int segment, long us, int perspective, int flags, int kind) {
            b.addEncoded(segment, us, RewindFormat.TAG_VIEW, codec.buffer(), codec.view(perspective, flags, kind));
            return this;
        }

        Rec mouse(long us, int dx, int dy) {
            b.addEncoded(0, us, RewindFormat.TAG_MOUSE, codec.buffer(), codec.mouse(dx, dy));
            return this;
        }

        Rec chat(long us, String text, int cursor) {
            b.addEncoded(0, us, RewindFormat.TAG_CHAT_INPUT, codec.buffer(), codec.chatInput(text, cursor));
            return this;
        }

        ExtTrack track() {
            return ExtTrack.of(b.build());
        }
    }

    private static void at(ViewTrack r, ViewState v, ExtTrack t, long us) {
        r.at(t, 0, us);
        r.apply(v);
    }

    /**
     * C4, Q2: your view follows the recorded perspective, F1, F3, Tab and screen at playback time, and a seek (either
     * way) lands on the state recorded at its time, not on what was shown before it.
     */
    @Test
    public void yourViewFollowsTheRecordedViewAtPlaybackTimeAndASeekLandsOnTheStateAtItsTime() {
        ExtTrack t = new Rec()
                .view(0, 100 * MS, 0, 0, RewindFormat.SCREEN_NONE)
                .view(0, 200 * MS, 1, TAB, RewindFormat.SCREEN_NONE)
                .view(0, 300 * MS, 2, HIDE | F3, RewindFormat.SCREEN_INVENTORY)
                .view(0, 400 * MS, 0, 0, RewindFormat.SCREEN_NONE)
                .track();
        ViewTrack r = new ViewTrack();
        ViewState v = new ViewState();
        at(r, v, t, 50 * MS);
        assertFalse("nothing recorded before the first VIEW", r.hasView());
        assertFalse(v.hasRecorded());

        at(r, v, t, 250 * MS);
        assertTrue(r.hasView());
        assertEquals("F5 behind", 1, v.perspective());
        assertTrue("the recorded Tab list", v.tabList());
        assertFalse(v.hideGui());

        at(r, v, t, 350 * MS);
        assertEquals("F5 front", 2, v.perspective());
        assertTrue("F1", v.hideGui());
        assertTrue("F3", v.showDebug());
        assertFalse(v.tabList());
        assertEquals(RewindFormat.SCREEN_INVENTORY, v.screenKind());

        at(r, v, t, 260 * MS); // a backward seek
        assertEquals(1, v.perspective());
        assertTrue(v.tabList());
        assertFalse(v.showDebug());
        assertEquals(RewindFormat.SCREEN_NONE, v.screenKind());

        at(r, v, t, 450 * MS); // a forward seek over the inventory
        assertEquals(0, v.perspective());
        assertFalse(v.hideGui());
        assertEquals(RewindFormat.SCREEN_NONE, v.screenKind());
    }

    /** Q2: F5 or the clean view take over from the recording until key 1, which shows the state recorded now. */
    @Test
    public void f5OverridesTheRecordingUntilKeyOneWhichShowsTheStateRecordedNow() {
        ExtTrack t = new Rec()
                .view(0, 100 * MS, 1, TAB, RewindFormat.SCREEN_NONE)
                .view(0, 300 * MS, 2, F3, RewindFormat.SCREEN_NONE)
                .track();
        ViewTrack r = new ViewTrack();
        ViewState v = new ViewState();
        at(r, v, t, 150 * MS);
        assertTrue(v.cyclePerspective()); // from the recorded behind view to front
        assertEquals(2, v.perspective());
        assertFalse("the override drops the recorded Tab", v.tabList());
        at(r, v, t, 350 * MS);
        assertTrue(v.overridden());
        assertEquals("a later VIEW does not end the override", 2, v.perspective());
        assertFalse(v.showDebug());
        v.cyclePerspective();
        assertEquals(0, v.perspective());
        v.selectYou();
        assertEquals("key 1: the recorded state at playback time", 2, v.perspective());
        assertTrue(v.showDebug());

        v.toggleClean();
        at(r, v, t, 360 * MS);
        assertTrue("the clean view hides the HUD", v.hideGui());
        assertFalse(v.showDebug());
        v.selectYou();
        assertTrue(v.showDebug());
        assertFalse(v.hideGui());
    }

    /** C10: a v1 recording (no VIEW records) never changes the view; the free camera never follows the recording. */
    @Test
    public void aV1RecordingRecordsNoViewAndOnlyYourViewFollowsIt() {
        ViewTrack r = new ViewTrack();
        ViewState v = new ViewState();
        at(r, v, ExtTrack.EMPTY, 5_000 * MS);
        assertFalse(r.hasView());
        assertFalse(v.hasRecorded());
        assertEquals(0, v.perspective());

        ExtTrack t = new Rec().view(0, 100 * MS, 1, HIDE | F3 | TAB, RewindFormat.SCREEN_CHEST).track();
        v.selectFree();
        at(r, v, t, 200 * MS);
        assertTrue(v.hasRecorded());
        assertEquals(0, v.perspective());
        assertFalse(v.hideGui());
        assertFalse(v.showDebug());
        assertFalse("Phase 15 hides the recorded HUD on tabList()/showDebug(): your view only", v.tabList());
        v.selectYou();
        assertTrue(v.tabList());
        assertTrue(v.showDebug());
    }

    /** A segment reads only its own VIEW records; an unknown segment (-1) takes the latest segment that began. */
    @Test
    public void aSegmentReadsOnlyItsOwnRecords() {
        ExtTrack t = new Rec()
                .view(0, 100 * MS, 1, 0, RewindFormat.SCREEN_NONE)
                .view(1, 500 * MS, 2, 0, RewindFormat.SCREEN_NONE)
                .track();
        ViewTrack r = new ViewTrack();
        r.at(t, 1, 400 * MS);
        assertFalse("segment 1 has no VIEW before 500 ms", r.hasView());
        r.at(t, 0, 400 * MS);
        assertEquals(1, r.perspective());
        r.at(t, -1, 400 * MS);
        assertEquals("unknown segment: the latest that began", 1, r.perspective());
        r.at(t, -1, 600 * MS);
        assertEquals(2, r.perspective());
        assertEquals(1, ViewTrack.segmentFor(t, RewindFormat.TAG_VIEW, -1, 600 * MS));
        assertEquals(0, ViewTrack.segmentFor(t, RewindFormat.TAG_VIEW, 0, 600 * MS));
    }

    /**
     * MOUSE is stored only while a screen is open (first frame at once): the cursor is the latest MOUSE of the screen
     * open now, never one from an earlier screen, and there is none while no screen is open.
     */
    @Test
    public void theRecordedMouseIsTheOpenScreensOwn() {
        ExtTrack t = new Rec()
                .view(0, 100 * MS, 0, 0, RewindFormat.SCREEN_CHEST)
                .mouse(101 * MS, 40, -8)
                .mouse(150 * MS, 44, -12)
                .view(0, 200 * MS, 0, 0, RewindFormat.SCREEN_NONE)
                .view(0, 300 * MS, 0, TAB, RewindFormat.SCREEN_INVENTORY)
                .view(0, 320 * MS, 0, 0, RewindFormat.SCREEN_INVENTORY) // a flag change inside the same screen
                .mouse(330 * MS, -4, 6)
                .track();
        ViewTrack r = new ViewTrack();
        r.at(t, 0, 120 * MS);
        assertTrue(r.mouseShown());
        assertEquals(40, r.mouseDx());
        assertEquals(-8, r.mouseDy());
        r.at(t, 0, 160 * MS);
        assertEquals(44, r.mouseDx());
        r.at(t, 0, 250 * MS);
        assertFalse("no screen: no cursor", r.mouseShown());
        r.at(t, 0, 325 * MS);
        assertFalse("the inventory has no MOUSE yet: not the chest's", r.mouseShown());
        assertEquals("the screen opened at 300 ms (the flag change is the same screen)", 300 * MS, r.screenSinceUs());
        r.at(t, 0, 340 * MS);
        assertTrue(r.mouseShown());
        assertEquals(-4, r.mouseDx());
        assertEquals(6, r.mouseDy());
    }

    /**
     * CHAT_INPUT is measured from the empty bar of each chat: the text shown is this chat's latest typing, an empty
     * bar before the first keystroke, and never the text of an earlier chat.
     */
    @Test
    public void theRecordedChatTextIsThisChatsOwnTyping() {
        ExtTrack t = new Rec()
                .view(0, 100 * MS, 0, 0, RewindFormat.SCREEN_CHAT)
                .chat(120 * MS, "abc", 3)
                .chat(140 * MS, "ab", 1)
                .view(0, 200 * MS, 0, 0, RewindFormat.SCREEN_NONE)
                .view(0, 300 * MS, 0, 0, RewindFormat.SCREEN_CHAT)
                .chat(300 * MS, "/", 1) // a chat opened with "/" writes its text in the open's frame
                .view(0, 400 * MS, 0, 0, RewindFormat.SCREEN_NONE)
                .view(0, 500 * MS, 0, 0, RewindFormat.SCREEN_CHAT)
                .track();
        ViewTrack r = new ViewTrack();
        r.at(t, 0, 110 * MS);
        assertTrue(r.chatShown());
        assertEquals("", r.chatText());
        assertEquals(0, r.chatCursor());
        r.at(t, 0, 130 * MS);
        assertEquals("abc", r.chatText());
        assertEquals(3, r.chatCursor());
        r.at(t, 0, 150 * MS);
        assertEquals("ab", r.chatText());
        assertEquals(1, r.chatCursor());
        r.at(t, 0, 250 * MS);
        assertFalse(r.chatShown());
        r.at(t, 0, 300 * MS);
        assertEquals("/", r.chatText());
        r.at(t, 0, 510 * MS);
        assertTrue(r.chatShown());
        assertEquals("not the earlier chat's text", "", r.chatText());
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
