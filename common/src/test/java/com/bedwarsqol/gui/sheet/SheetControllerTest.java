package com.bedwarsqol.gui.sheet;

import static com.bedwarsqol.gui.sheet.SheetFixtures.find;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.bedwarsqol.gui.sheet.SheetLayout.Frame;
import com.bedwarsqol.gui.sheet.SheetLayout.Node;
import com.bedwarsqol.gui.sheet.SheetLayout.T;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The prototype's boot.js behaviour: clicks, Esc steps back, capture, confirm, search, sliders and typed values. */
public class SheetControllerTest {

    private static final int RSHIFT = 54;

    private final SheetFixtures.Values values = new SheetFixtures.Values();
    private final SheetState state = new SheetState();
    private final Host host = new Host();
    private final SheetController c = new SheetController(state, values, host);

    private static final class Host implements SheetController.Host {
        int clicks, closes, editHud;
        long now = 1000;
        final List<String> toasts = new ArrayList<String>();
        final List<Integer> keys = new ArrayList<Integer>();
        SheetFixtures.Values values;

        public void click() { clicks++; }
        public void close() { closes++; }
        public void editHud() { editHud++; }
        public void toast(String message) { toasts.add(message); }
        public void setKey(String id, int binding) { keys.add(binding); }
        public long now() { return now; }
    }

    private Frame frame() {
        Frame f = new SheetLayout(new SheetFixtures.Metrics(), values, state).layout(447f, "v0.17.0");
        c.frame(f);
        return f;
    }

    private void click(Node n) {
        c.pointerDown(n, n.x + n.w / 2f, 0);
        c.pointerUp(n);
    }

    @Test
    public void escStepsBackOneThingAtATime() {
        values.setOn("session", true);
        state.open.add("session");
        frame();
        state.confirm = "sessionReset";
        state.confirmUntil = host.now + 3000;
        c.key('s', 31, false, false, false, RSHIFT); // typing anywhere focuses search
        assertTrue(c.searchFocused());
        assertEquals("s", state.query);
        c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT);
        assertNull("the confirm goes first", state.confirm);
        c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT);
        assertEquals("then the search text", "", state.query);
        assertTrue(c.searchFocused());
        c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT);
        assertFalse("then search focus", c.searchFocused());
        assertEquals(0, host.closes);
        c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT);
        assertEquals("then the sheet closes", 1, host.closes);
    }

    @Test
    public void settingsKeyClosesUnlessTyping() {
        frame();
        c.key('a', 30, false, false, false, RSHIFT);
        c.key((char) 0, RSHIFT, false, true, false, RSHIFT);
        assertEquals(0, host.closes);
        c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT);
        c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT);
        c.key((char) 0, RSHIFT, false, true, false, RSHIFT);
        assertEquals(1, host.closes);
    }

    @Test
    public void keysBeforeTheFirstFrameDoNothing() {
        // On Lunar the press that opens the sheet reaches the new screen before anything is drawn.
        assertFalse(c.key((char) 0, RSHIFT, false, true, false, RSHIFT));
        assertFalse(c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT));
        assertFalse(c.key('a', 30, false, false, false, RSHIFT));
        assertEquals(0, host.closes);
        assertEquals(0, host.clicks);
        assertFalse(c.searchFocused());
        frame();
        c.key((char) 0, RSHIFT, false, true, false, RSHIFT);
        assertEquals(1, host.closes);
    }

    @Test
    public void aHeldKeyNeverClosesTheSheet() {
        frame();
        assertTrue(c.key((char) 0, RSHIFT, false, true, false, RSHIFT, true));
        assertTrue(c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT, true));
        assertEquals(0, host.closes);
        assertEquals(0, host.clicks);
        c.key('s', 31, false, false, false, RSHIFT);
        c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT, true);
        assertEquals("a held Esc does not step back either", "s", state.query);
        c.key((char) 0, SheetController.KEY_BACK, false, false, false, RSHIFT, true);
        assertEquals("typing still repeats", "", state.query);
        c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT);
        assertFalse(c.searchFocused());
        c.key((char) 0, RSHIFT, false, true, false, RSHIFT, false);
        assertEquals("a fresh press closes", 1, host.closes);
    }

    @Test
    public void aHeldEscKeepsAValueEdit() {
        values.setOn("handPos", true);
        state.category = "combat";
        state.open.add("handPos");
        Node field = find(frame(), T.VALUE, "handScale");
        c.pointerDown(field, field.x, 0);
        c.pointerUp(field);
        assertEquals("handScale", c.editing());
        c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT, true);
        assertEquals("handScale", c.editing());
        c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT);
        assertNull(c.editing());
        assertEquals(0, host.closes);
    }

    @Test
    public void aHeldKeyIsNotTheNewBinding() {
        values.setOn("session", true);
        values.setOn("sessionHold", true);
        state.open.add("session");
        click(find(frame(), T.KEY, "sessionKey"));
        assertEquals("sessionKey", state.capture);
        c.key((char) 0, 42, false, true, false, RSHIFT, true); // Shift, held since before the click
        c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT, true);
        assertEquals("still waiting", "sessionKey", state.capture);
        assertTrue(host.keys.isEmpty());
        c.key('r', 19, false, false, false, RSHIFT);
        assertNull(state.capture);
        assertEquals(19, (int) host.keys.get(0));
    }

    @Test
    public void keyCaptureBindsClearsOrCancels() {
        values.setOn("session", true);
        values.setOn("sessionHold", true);
        state.open.add("session");
        Node key = find(frame(), T.KEY, "sessionKey");
        click(key);
        assertEquals("sessionKey", state.capture);
        c.key((char) 0, SheetController.KEY_ESCAPE, false, false, false, RSHIFT);
        assertNull(state.capture);
        assertTrue(host.keys.isEmpty());
        click(key);
        c.key((char) 0, SheetController.KEY_BACK, false, false, false, RSHIFT);
        click(key);
        c.key('r', 19, false, false, false, RSHIFT);
        click(key);
        c.pointerDown(null, 0f, 0); // a left click cancels
        click(key);
        c.pointerDown(null, 0f, 4); // a side button binds
        assertEquals(0, (int) host.keys.get(0));
        assertEquals(19, (int) host.keys.get(1));
        assertEquals(-96, (int) host.keys.get(2));
        assertEquals(3, host.keys.size());
    }

    @Test
    public void resetAsksOnceThenRuns() {
        values.setOn("session", true);
        state.open.add("session");
        Node reset = find(frame(), T.BUTTON, "sessionReset");
        click(reset);
        assertEquals("sessionReset", state.confirm);
        assertEquals(0, values.runs);
        host.now += 2000;
        frame();
        click(find(frame(), T.BUTTON, "sessionReset"));
        assertEquals(1, values.runs);
        assertEquals("Session stats reset", host.toasts.get(0));
        click(find(frame(), T.BUTTON, "sessionReset"));
        host.now += 3001;
        frame();
        assertNull("the confirm times out after 3 s", state.confirm);
        assertEquals(1, values.runs);
    }

    @Test
    public void clicksActOnReleaseOverTheSameControl() {
        Frame f = frame();
        Node tg = find(f, T.MODULE_TOGGLE, "potion");
        c.pointerDown(tg, tg.x, 0);
        c.pointerUp(find(f, T.MODULE_TOGGLE, "armor"));
        assertFalse(values.on("potion"));
        click(tg);
        assertTrue(values.on("potion"));
        assertEquals(1, host.clicks);
        click(find(frame(), T.OPEN, "potion"));
        assertTrue(state.open.contains("potion"));
    }

    @Test
    public void categoryClearsSearchAndScroll() {
        state.query = "chat";
        state.scroll = 40f;
        Node combat = null;
        for (Node n : frame().chrome) if (n.type == T.CATEGORY && "combat".equals(n.id)) combat = n;
        click(combat);
        assertEquals("combat", state.category);
        assertEquals("", state.query);
        assertEquals(0f, state.scroll, 0f);
    }

    @Test
    public void slidersDragAndTypedValuesCommit() {
        values.setOn("handPos", true);
        state.category = "combat";
        state.open.add("handPos");
        Frame f = frame();
        Node slider = find(f, T.SLIDER, "handX");
        c.pointerDown(slider, slider.x + slider.w * 0.35f, 0);
        assertEquals(-0.3, values.number("handX"), 1e-9);
        c.pointerDrag(slider.x + slider.w * 2f);
        assertEquals(1.0, values.number("handX"), 1e-9);
        c.pointerUp(slider);
        assertEquals(1, values.saves);
        Node field = find(frame(), T.VALUE, "handScale");
        c.pointerDown(field, field.x, 0);
        c.pointerUp(field);
        assertEquals("handScale", c.editing());
        for (char ch : "1,25".toCharArray()) c.key(ch, 2, false, false, false, RSHIFT);
        c.key((char) 0, SheetController.KEY_RETURN, false, false, false, RSHIFT);
        assertNull(c.editing());
        assertEquals(1.25, values.number("handScale"), 1e-9);
    }

    @Test
    public void tabMovesFocusAndSpacePresses() {
        frame();
        c.key((char) 0, SheetController.KEY_TAB, false, false, false, RSHIFT);
        assertTrue("search comes first", c.searchFocused());
        c.key((char) 0, SheetController.KEY_TAB, false, false, false, RSHIFT);
        assertEquals("CLOSE:null:-1", c.focus());
        c.key((char) 0, SheetController.KEY_TAB, false, false, false, RSHIFT);
        c.key((char) 0, SheetController.KEY_TAB, false, false, false, RSHIFT);
        assertEquals("CATEGORY:combat:1", c.focus());
        c.key(' ', SheetController.KEY_SPACE, false, false, false, RSHIFT);
        assertEquals("combat", state.category);
    }
}
