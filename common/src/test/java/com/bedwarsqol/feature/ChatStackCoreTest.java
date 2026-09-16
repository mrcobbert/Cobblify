package com.bedwarsqol.feature;

import com.bedwarsqol.feature.ChatStackCore.Capture;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChatStackCoreTest {

    // ---- plain / blank / decorative -------------------------------------------------------------

    @Test
    public void plainStripsFormattingCodes() {
        assertEquals("Hello", ChatStackCore.plain("§aHello§r"));
        assertEquals("no codes", ChatStackCore.plain("no codes"));
        assertEquals("", ChatStackCore.plain("§a§m"));
        assertEquals("x", ChatStackCore.plain("x§")); // lone trailing § dropped
        assertNull(ChatStackCore.plain(null));
    }

    @Test
    public void blankAndDecorativeAreDisjoint() {
        String[] blanks = {null, "", "   ", "\t", ChatStackCore.plain("§r"), ChatStackCore.plain("§a§m")};
        for (String b : blanks) {
            assertTrue("blank: " + b, ChatStackCore.blank(b));
            assertFalse("not decorative: " + b, ChatStackCore.decorative(b));
        }
        String[] bars = {"-----------", ChatStackCore.plain("§a§m-----------------------"),
                "▬▬▬▬▬▬", "= = = =", "* * *", ">>>"};
        for (String bar : bars) {
            assertFalse("not blank: " + bar, ChatStackCore.blank(bar));
            assertTrue("decorative: " + bar, ChatStackCore.decorative(bar));
        }
        String[] text = {"- x -", "You cannot say the same message twice!", "gg", "1", "é"};
        for (String t : text) {
            assertFalse(ChatStackCore.blank(t));
            assertFalse("not decorative: " + t, ChatStackCore.decorative(t));
        }
    }

    // ---- capture decision (both Ignore Blank Lines values) --------------------------------------

    @Test
    public void blankIsTransparentOnlyWhenIgnoringBlanks() {
        assertEquals(Capture.TRANSPARENT, ChatStackCore.captureAction("   ", true));
        assertEquals(Capture.CAPTURE, ChatStackCore.captureAction("   ", false));
        assertEquals(Capture.TRANSPARENT, ChatStackCore.captureAction("", true));
        assertEquals(Capture.CAPTURE, ChatStackCore.captureAction("", false));
    }

    @Test
    public void decorativeAlwaysResets() {
        assertEquals(Capture.RESET, ChatStackCore.captureAction("-----", true));
        assertEquals(Capture.RESET, ChatStackCore.captureAction("-----", false));
    }

    @Test
    public void ordinaryTextIsCaptured() {
        assertEquals(Capture.CAPTURE, ChatStackCore.captureAction("Dewier: gg", true));
        assertEquals(Capture.CAPTURE, ChatStackCore.captureAction("Dewier: gg", false));
    }

    @Test
    public void deletableOrUnreadableLinesAlwaysReset() {
        assertEquals(Capture.RESET, ChatStackCore.captureAction(true, false, "A", true));
        assertEquals(Capture.RESET, ChatStackCore.captureAction(false, true, "A", true));
        assertEquals(Capture.RESET, ChatStackCore.captureAction(true, false, "   ", true)); // even a blank
        assertEquals(Capture.CAPTURE, ChatStackCore.captureAction(false, false, "A", true));
        assertEquals(Capture.TRANSPARENT, ChatStackCore.captureAction(false, false, "   ", true));
    }

    /**
     * A minimal model of the mixin's chain state, driven by the same two rules the mixin calls:
     * {@code captureAction} at print, {@code shouldStack} at the next receipt. Pins consecutive-only
     * stacking, the deletable-id chain break, and blank transparency across a chain.
     */
    private static final class Chain {
        String base; int count;
        boolean receive(String plain, boolean deletable, boolean ignoreBlanks) {
            if (base != null && !deletable && ChatStackCore.shouldStack(base, plain, false, 0, 0, 0)) {
                count++;
                return true; // absorbed
            }
            switch (ChatStackCore.captureAction(deletable, false, plain, ignoreBlanks)) {
                case TRANSPARENT: break;
                case RESET: base = null; count = 0; break;
                default: base = plain; count = 1;
            }
            return false;
        }
    }

    @Test
    public void chainIsConsecutiveOnly() {
        Chain c = new Chain();
        assertFalse(c.receive("A", false, true));
        assertTrue(c.receive("A", false, true));
        assertEquals(2, c.count);
        assertFalse(c.receive("B", false, true));   // B moves the chain on
        assertFalse(c.receive("A", false, true));   // A again is a fresh line, not (x3)
        assertEquals(1, c.count);
        assertEquals("A", c.base);
    }

    @Test
    public void deletableIdBreaksTheChainAndIsNeverAbsorbed() {
        Chain c = new Chain();
        assertFalse(c.receive("A", false, true));
        assertFalse(c.receive("A", true, true));    // same text, deletable: printed, chain broken
        assertNull(c.base);
        assertFalse(c.receive("A", false, true));   // starts over
        assertEquals(1, c.count);
    }

    @Test
    public void blanksAreTransparentAcrossAChainOnlyWhenIgnored() {
        Chain c = new Chain();
        assertFalse(c.receive("A", false, true));
        assertFalse(c.receive("", false, true));    // transparent
        assertTrue(c.receive("A", false, true));    // still stacks
        Chain d = new Chain();
        assertFalse(d.receive("A", false, false));
        assertFalse(d.receive("", false, false));   // captured: chain moves to the blank
        assertFalse(d.receive("A", false, false));  // fresh
        assertTrue(d.receive("A", false, false));
        assertTrue(d.receive("", false, false) == false); // different from base A
        assertTrue(d.receive("", false, false));    // two blanks stack when not ignored
    }

    // ---- shouldStack ----------------------------------------------------------------------------

    @Test
    public void identicalTextStacksAndDifferentDoesNot() {
        assertTrue(ChatStackCore.shouldStack("a", "a", false, 0, 0, 0));
        assertFalse(ChatStackCore.shouldStack("a", "b", false, 0, 0, 0));
        assertFalse(ChatStackCore.shouldStack(null, "a", false, 0, 0, 0));
        assertFalse(ChatStackCore.shouldStack("a", null, false, 0, 0, 0));
    }

    @Test
    public void timeWindowIsInclusiveAndSliding() {
        long w = 5000;
        assertTrue(ChatStackCore.shouldStack("a", "a", true, w, 1000, 6000));   // exactly at the edge
        assertFalse(ChatStackCore.shouldStack("a", "a", true, w, 1000, 6001));  // one ms too old
        // Sliding: the last stack (not the first print) anchors the window.
        assertTrue(ChatStackCore.shouldStack("a", "a", true, w, 9000, 13000));
    }

    @Test
    public void timeBasedOffNeverExpires() {
        assertTrue(ChatStackCore.shouldStack("a", "a", false, 5000, 0, Long.MAX_VALUE / 2));
    }

    @Test
    public void windowMsIsNeverNegative() {
        assertEquals(5000L, ChatStackCore.windowMs(5.0f));
        assertEquals(1500L, ChatStackCore.windowMs(1.5f));
        assertEquals(0L, ChatStackCore.windowMs(-3f));
        assertEquals(0L, ChatStackCore.windowMs(Float.NaN));
    }

    // ---- rowStart / targetPresent ---------------------------------------------------------------

    @Test
    public void rowStartSumsNewerMessages() {
        int[] rows = {1, 3, 2, 1};
        assertEquals(0, ChatStackCore.rowStart(rows, 0));
        assertEquals(1, ChatStackCore.rowStart(rows, 1));
        assertEquals(4, ChatStackCore.rowStart(rows, 2));
        assertEquals(6, ChatStackCore.rowStart(rows, 3));
        assertEquals(7, ChatStackCore.rowStart(rows, 99)); // idx past the end: total rows
    }

    @Test
    public void targetPresentRequiresWholeRangeInsideList() {
        assertTrue(ChatStackCore.targetPresent(0, 1, 1));
        assertTrue(ChatStackCore.targetPresent(3, 2, 5));
        assertFalse(ChatStackCore.targetPresent(3, 2, 4));  // last row trimmed
        assertFalse(ChatStackCore.targetPresent(5, 1, 5));  // starts past the end
        assertFalse(ChatStackCore.targetPresent(0, 0, 5));  // nothing to replace
        assertFalse(ChatStackCore.targetPresent(-1, 1, 5));
    }

    /**
     * Vanilla caps chatLines at 100 messages and drawnChatLines at 100 rows independently. A captured
     * message followed by transparent blanks that wrap to more rows than the cap outlives its rows.
     */
    @Test
    public void targetOutlivesItsRowsUnderIndependentCaps() {
        final int msgCap = 100, rowCap = 100;
        // Newest-first: index 0 is the newest. The target is one message of 2 rows, then N blanks
        // of 1 row each are printed after it (so they sit at lower indices).
        int blanks = 99; // 99 + 1 = 100 messages: chatLines is exactly full, target still present
        int[] rows = new int[blanks + 1];
        Arrays.fill(rows, 1);
        rows[blanks] = 2; // the target, at the oldest index
        int messages = blanks + 1;
        assertTrue(messages <= msgCap);
        int drawnSize = Math.min(rowCap, blanks + 2); // rows trimmed to the cap
        int idx = blanks;
        int start = ChatStackCore.rowStart(rows, idx);
        assertEquals(99, start);
        assertFalse("rows trimmed while the message survives",
                ChatStackCore.targetPresent(start, 2, drawnSize)); // 99 + 2 > 100
        // With fewer blanks the target's rows are all still drawn.
        int fewer = 50;
        int[] rows2 = new int[fewer + 1];
        Arrays.fill(rows2, 1);
        rows2[fewer] = 2;
        assertTrue(ChatStackCore.targetPresent(ChatStackCore.rowStart(rows2, fewer), 2, Math.min(rowCap, fewer + 2)));
    }

    // ---- replaceRows ----------------------------------------------------------------------------

    private static List<String> list(String... s) {
        return new ArrayList<String>(Arrays.asList(s));
    }

    @Test
    public void replaceRowsExactGrownShrunk() {
        List<String> d = list("n1", "t2", "t1", "o1");
        assertEquals(2, ChatStackCore.replaceRows(d, 1, 2, list("T2", "T1")));
        assertEquals(list("n1", "T2", "T1", "o1"), d);

        d = list("n1", "t1", "o1");
        assertEquals(1, ChatStackCore.replaceRows(d, 1, 1, list("T2", "T1")));
        assertEquals(list("n1", "T2", "T1", "o1"), d);

        d = list("n1", "t2", "t1", "o1");
        assertEquals(2, ChatStackCore.replaceRows(d, 1, 2, list("T1")));
        assertEquals(list("n1", "T1", "o1"), d);
    }

    @Test
    public void replaceRowsAtHeadAndTail() {
        List<String> d = list("t1", "o1");
        ChatStackCore.replaceRows(d, 0, 1, list("T1"));
        assertEquals(list("T1", "o1"), d);

        d = list("n1", "t1");
        ChatStackCore.replaceRows(d, 1, 1, list("T1"));
        assertEquals(list("n1", "T1"), d);
    }

    @Test
    public void replaceRowsClampsOutOfRange() {
        List<String> d = list("a", "b");
        assertEquals(1, ChatStackCore.replaceRows(d, 1, 5, list("X"))); // oldRows past the end
        assertEquals(list("a", "X"), d);

        d = list("a", "b");
        assertEquals(0, ChatStackCore.replaceRows(d, 7, 1, list("X")));  // start past the end
        assertEquals(list("a", "b", "X"), d);

        d = new ArrayList<String>();
        assertEquals(0, ChatStackCore.replaceRows(d, 0, 1, list("X")));  // empty list
        assertEquals(list("X"), d);

        d = list("a");
        assertEquals(0, ChatStackCore.replaceRows(d, -3, 0, list("X"))); // negative start
        assertEquals(list("X", "a"), d);
    }

    // ---- scrollDelta ----------------------------------------------------------------------------

    @Test
    public void scrollDeltaMirrorsVanillaForATargetBelowTheViewport() {
        // Newest-first indices: the viewport shows [scrollPos, scrollPos + lineCount). A target at
        // index 0 with scrollPos 3 sits below everything the reader scrolled past.
        assertEquals(0, ChatStackCore.scrollDelta(false, 3, 0, 1, 2)); // chat closed
        assertEquals(0, ChatStackCore.scrollDelta(true, 0, 0, 1, 2));  // at the bottom
        assertEquals(1, ChatStackCore.scrollDelta(true, 3, 0, 1, 2));  // grew by a row
        assertEquals(-1, ChatStackCore.scrollDelta(true, 3, 0, 2, 1)); // shrank by a row
        assertEquals(0, ChatStackCore.scrollDelta(true, 3, 0, 2, 2));  // same size
        // Vanilla's own case: a brand-new row at index 0 (oldRows 0) scrolls by one.
        assertEquals(1, ChatStackCore.scrollDelta(true, 1, 0, 0, 1));
    }

    @Test
    public void scrollDeltaIgnoresATargetAtOrAboveTheViewport() {
        // start 1, one row, scrollPos 1: the target's row IS the viewport's bottom row (index 1).
        assertEquals(0, ChatStackCore.scrollDelta(true, 1, 1, 1, 2));
        // Entirely above the viewport (older than what is shown).
        assertEquals(0, ChatStackCore.scrollDelta(true, 2, 5, 1, 2));
        // Straddling the viewport edge: partially visible, no compensation.
        assertEquals(0, ChatStackCore.scrollDelta(true, 2, 1, 3, 4));
        // Two-row target ending exactly at the viewport edge is still fully below it.
        assertEquals(2, ChatStackCore.scrollDelta(true, 3, 1, 2, 4));
    }
}
