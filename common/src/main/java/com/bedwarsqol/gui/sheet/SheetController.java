package com.bedwarsqol.gui.sheet;

import com.bedwarsqol.feature.SessionHoldKey;
import com.bedwarsqol.gui.sheet.SheetLayout.Frame;
import com.bedwarsqol.gui.sheet.SheetLayout.Node;
import com.bedwarsqol.gui.sheet.SheetLayout.T;
import com.bedwarsqol.gui.sheet.SheetSchema.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * What the sheet does with the pointer and the keyboard, as the prototype's {@code boot.js} does it. Pure: the
 * screen feeds it hit nodes and LWJGL key codes, and it changes {@link SheetState} and {@link SheetValues} and calls
 * back to the {@link Host} for sound, closing and Edit HUD.
 */
public final class SheetController {

    /** Platform effects. */
    public interface Host {
        /** The vanilla button click. */
        void click();

        /** Slide the sheet out and close it. */
        void close();

        void editHud();

        void toast(String message);

        /** Store a key row's binding (0 clears it). */
        void setKey(String id, int binding);

        long now();
    }

    // LWJGL key codes
    public static final int KEY_ESCAPE = 1, KEY_BACK = 14, KEY_TAB = 15, KEY_RETURN = 28, KEY_A = 30, KEY_F = 33,
            KEY_SPACE = 57, KEY_HOME = 199, KEY_UP = 200, KEY_LEFT = 203, KEY_RIGHT = 205, KEY_END = 207,
            KEY_DOWN = 208, KEY_DELETE = 211, KEY_NUMPADENTER = 156;

    private static final float WHEEL_STEP = 39f;
    private static final long CONFIRM_MS = 3000;

    private final SheetState st;
    private final SheetValues values;
    private final Host host;

    /** The node held down, by key; the slider being dragged. */
    private String pressed;
    private Node dragging;
    private float dragX, dragW;
    /** Keyboard focus, by node key; whether it came from the keyboard (only then is the ring drawn). */
    private String focus;
    private boolean focusVisible;
    private boolean searchFocused;
    /** The search text is selected (Cmd/Ctrl+F): the next character replaces it. */
    private boolean searchSelected;
    /** The value field being typed in, its text, and whether that text is still all selected. */
    private String editing;
    private String editText = "";
    private boolean editSelected;
    private Frame frame;

    public SheetController(SheetState st, SheetValues values, Host host) {
        this.st = st;
        this.values = values;
        this.host = host;
    }

    public static String key(Node n) {
        return n == null ? null : n.type + ":" + n.id + ":" + n.index;
    }

    /** The current frame's node with this key, or null. */
    public static Node find(Frame f, String key) {
        if (key == null || f == null) return null;
        for (Node n : f.chrome) if (key.equals(key(n))) return n;
        for (Node n : f.body) if (key.equals(key(n))) return n;
        return null;
    }

    /** Called once a frame with the new layout; drops a confirm whose 3 s ran out. */
    public void frame(Frame f) {
        this.frame = f;
        if (st.confirm != null && host.now() > st.confirmUntil) st.confirm = null;
    }

    public String pressed() {
        return pressed;
    }

    public String focus() {
        return focusVisible ? focus : null;
    }

    public boolean searchFocused() {
        return searchFocused;
    }

    public String editing() {
        return editing;
    }

    public String editText() {
        return editText;
    }

    public boolean typing() {
        return searchFocused || editing != null;
    }

    // ------------------------------------------------------------------ pointer

    /** A mouse button went down on {@code n} (null: nothing interactive), at sheet x {@code x}. */
    public void pointerDown(Node n, float x, int button) {
        if (st.capture != null) {
            // Left click cancels the capture; any other button becomes the binding.
            if (button != 0) host.setKey(st.capture, SessionHoldKey.bindingForMouse(button));
            st.capture = null;
            host.click();
            return;
        }
        if (button != 0) return;
        commitEdit();
        focusVisible = false;
        if (n == null) {
            searchFocused = false;
            return;
        }
        pressed = key(n);
        if (n.type == T.SEARCH) {
            searchFocused = true;
            searchSelected = false;
            return;
        }
        searchFocused = false;
        if (n.type == T.SLIDER) {
            host.click();
            dragging = n;
            dragX = n.x;
            dragW = n.w;
            focus = key(n);
            drag(x);
            return;
        }
        if (n.type == T.VALUE) {
            editing = n.id;
            editText = n.text;
            editSelected = true;
            focus = key(n);
        }
    }

    public void pointerDrag(float x) {
        if (dragging != null) drag(x);
    }

    private void drag(float x) {
        Row r = SheetSchema.row(dragging.id);
        values.setNumber(r.id, SheetFormat.atFraction(r, (x - dragX) / dragW));
    }

    /** The button came up over {@code n}; a press and release on the same control is a click. */
    public void pointerUp(Node n) {
        if (dragging != null) {
            dragging = null;
            pressed = null;
            values.save();
            return;
        }
        String was = pressed;
        pressed = null;
        if (n == null || was == null || !was.equals(key(n)) || n.type == T.SEARCH || n.type == T.VALUE) return;
        activate(n);
    }

    public void wheel(float notches) {
        if (frame == null) return;
        st.scroll = clamp(st.scroll + notches * WHEEL_STEP, 0f, frame.maxScroll());
    }

    /** Press a control (a click, or Space/Enter on the focused one). */
    void activate(Node n) {
        if (n.disabled) return;
        host.click();
        switch (n.type) {
            case CATEGORY:
                st.category = n.id;
                setQuery("");
                st.capture = null;
                st.scroll = st.scrollShown = 0f;
                break;
            case OPEN:
                if (!st.open.remove(n.id)) st.open.add(n.id);
                break;
            case MODULE_TOGGLE:
            case TOGGLE:
                values.setOn(n.id, !values.on(n.id));
                break;
            case OPTION:
            case SWATCH:
                values.setChoice(n.id, SheetSchema.row(n.id).options()[n.index]);
                break;
            case KEY:
                st.capture = n.id.equals(st.capture) ? null : n.id;
                break;
            case BUTTON:
                if (n.id.equals(st.confirm)) {
                    st.confirm = null;
                    values.run(n.id);
                    host.toast("Session stats reset");
                } else if (SheetSchema.row(n.id).confirm()) {
                    st.confirm = n.id;
                    st.confirmUntil = host.now() + CONFIRM_MS;
                } else {
                    values.run(n.id);
                }
                break;
            case MORE:
                st.showAll.add(n.id);
                break;
            case CLOSE:
                closeSheet();
                break;
            case HERO:
                host.editHud();
                break;
            default:
                break;
        }
    }

    private void closeSheet() {
        st.capture = null;
        st.confirm = null;
        searchFocused = false;
        commitEdit();
        host.close();
    }

    // ------------------------------------------------------------------ keys

    /** A fresh key press; see {@link #key(char, int, boolean, boolean, boolean, int, boolean)}. */
    public boolean key(char ch, int code, boolean ctrl, boolean shift, boolean alt, int settingsKey) {
        return key(ch, code, ctrl, shift, alt, settingsKey, false);
    }

    /**
     * A key press. {@code ch} is the typed character (0 for none), {@code code} the LWJGL key code,
     * {@code settingsKey} the bound open/close key, {@code held} whether the key has stayed down since an earlier
     * press ({@link KeyHold}). Returns whether the sheet used it.
     *
     * <p>Keys before the first frame do nothing: on Lunar the press that opens the sheet reaches it too, because
     * Weave's KeyboardEvent fires inside Minecraft's key loop, before the loop hands that press to the new screen.
     * A held key never closes the sheet or steps back, and is never taken as a new binding; only a fresh press of
     * the settings key or Esc closes or steps back.
     */
    public boolean key(char ch, int code, boolean ctrl, boolean shift, boolean alt, int settingsKey, boolean held) {
        if (frame == null) return false;
        if (st.capture != null) {
            if (held) return true;
            int binding = SessionHoldKey.bindingForKey(code);
            if (binding == SessionHoldKey.KEEP_WAITING) return true;
            if (binding != SessionHoldKey.CANCEL) host.setKey(st.capture, binding);
            st.capture = null;
            host.click();
            return true;
        }
        if (held && code == KEY_ESCAPE) return true;
        if (editing != null && editKey(ch, code, shift)) return true;
        if (code == settingsKey && settingsKey > 0 && !typing()) {
            if (held) return true;
            closeSheet();
            host.click();
            return true;
        }
        if (code == KEY_ESCAPE) {
            if (st.confirm != null) st.confirm = null;
            else if (searchFocused) {
                if (!st.query.isEmpty()) setQuery("");
                else searchFocused = false;
            } else if (!st.query.isEmpty()) setQuery("");
            else closeSheet();
            return true;
        }
        if (ctrl && code == KEY_F) {
            searchFocused = true;
            searchSelected = true;
            focusVisible = false;
            return true;
        }
        if (searchFocused) return searchKey(ch, code, ctrl, shift);
        if (code == KEY_TAB) {
            moveFocus(shift ? -1 : 1);
            return true;
        }
        Node f = focus == null ? null : find(frame, focus);
        if (f != null && f.type == T.SLIDER && !f.disabled) {
            Row r = SheetSchema.row(f.id);
            double v = values.number(r.id), big = shift ? 10 : 1;
            boolean used = true;
            if (code == KEY_LEFT || code == KEY_DOWN) v -= r.step() * big;
            else if (code == KEY_RIGHT || code == KEY_UP) v += r.step() * big;
            else if (code == KEY_HOME) v = r.min();
            else if (code == KEY_END) v = r.max();
            else used = false;
            if (used) {
                values.setNumber(r.id, SheetFormat.snap(r, v));
                values.save();
                return true;
            }
        }
        if (f != null && focusVisible && (code == KEY_SPACE || code == KEY_RETURN || code == KEY_NUMPADENTER)) {
            if (f.type == T.SEARCH) searchFocused = true;
            else if (f.type == T.VALUE) {
                editing = f.id;
                editText = f.text;
                editSelected = true;
            } else activate(f);
            return true;
        }
        if (ch >= ' ' && ch != ' ' && ch != 127 && !ctrl && !alt) {
            searchFocused = true;
            focusVisible = false;
            setQuery(st.query + ch);
            return true;
        }
        return false;
    }

    private boolean searchKey(char ch, int code, boolean ctrl, boolean shift) {
        if (code == KEY_BACK) {
            if (searchSelected || ctrl) setQuery("");
            else if (!st.query.isEmpty()) setQuery(st.query.substring(0, st.query.length() - 1));
            searchSelected = false;
            return true;
        }
        if (ctrl && code == KEY_A) {
            searchSelected = true;
            return true;
        }
        if (code == KEY_RETURN || code == KEY_NUMPADENTER) {
            List<Node> order = focusOrder();
            for (Node n : order) {
                if (frame.body.contains(n)) {
                    searchFocused = false;
                    setFocus(n);
                    break;
                }
            }
            return true;
        }
        if (code == KEY_TAB) {
            searchFocused = false;
            focus = "SEARCH:null:-1";
            focusVisible = true;
            moveFocus(shift ? -1 : 1);
            return true;
        }
        if (ch >= ' ' && ch != 127 && !ctrl) {
            setQuery(searchSelected ? String.valueOf(ch) : st.query + ch);
            searchSelected = false;
            return true;
        }
        return true;
    }

    private void setQuery(String q) {
        boolean changed = !q.equals(st.query);
        st.query = q;
        if (changed) {
            st.showAll.clear();
            st.capture = null;
            st.scroll = st.scrollShown = 0f;
        }
    }

    // ------------------------------------------------------------------ value field

    private boolean editKey(char ch, int code, boolean shift) {
        Row r = SheetSchema.row(editing);
        if (code == KEY_RETURN || code == KEY_NUMPADENTER) {
            commitEdit();
            return true;
        }
        if (code == KEY_ESCAPE) {
            editing = null;
            return true;
        }
        if (code == KEY_UP || code == KEY_DOWN) {
            double v = values.number(r.id) + (code == KEY_UP ? 1 : -1) * r.step() * (shift ? 10 : 1);
            values.setNumber(r.id, SheetFormat.snap(r, v));
            values.save();
            editText = SheetFormat.number(r, values.number(r.id));
            editSelected = true;
            return true;
        }
        if (code == KEY_BACK) {
            editText = editSelected || editText.isEmpty() ? "" : editText.substring(0, editText.length() - 1);
            editSelected = false;
            return true;
        }
        if (code == KEY_TAB) {
            commitEdit();
            return false;
        }
        if ((ch >= '0' && ch <= '9') || ch == '.' || ch == ',' || ch == '-' || ch == '+') {
            editText = editSelected ? String.valueOf(ch) : editText + ch;
            editSelected = false;
            return true;
        }
        return ch != 0;
    }

    /** Apply the typed value (as the prototype's {@code commitVal}) and leave the field. */
    private void commitEdit() {
        if (editing == null) return;
        Row r = SheetSchema.row(editing);
        editing = null;
        String s = editText.replace(',', '.').replaceAll("[^0-9.+-]", "");
        try {
            double n = Double.parseDouble(s);
            values.setNumber(r.id, SheetFormat.atFraction(r, (n - r.min()) / (r.max() - r.min())));
            values.save();
        } catch (NumberFormatException ignored) {
            // nothing usable typed: the value stays
        }
    }

    // ------------------------------------------------------------------ focus

    private static boolean focusable(Node n) {
        if (n.disabled) return false;
        switch (n.type) {
            case SEARCH: case CLOSE: case CATEGORY: case HERO: case MODULE_TOGGLE: case TOGGLE: case OPTION:
            case SWATCH: case SLIDER: case VALUE: case KEY: case BUTTON: case MORE:
                return true;
            case OPEN:
                return n.open;
            default:
                return false;
        }
    }

    /** Document order: header, categories, body, footer. */
    private List<Node> focusOrder() {
        List<Node> out = new ArrayList<Node>();
        if (frame == null) return out;
        Node hero = null;
        for (Node n : frame.chrome) {
            if (n.type == T.HERO) hero = n;
            else if (focusable(n)) out.add(n);
        }
        for (Node n : frame.body) if (focusable(n)) out.add(n);
        if (hero != null) out.add(hero);
        return out;
    }

    private void moveFocus(int dir) {
        List<Node> order = focusOrder();
        if (order.isEmpty()) return;
        int at = -1;
        for (int i = 0; i < order.size(); i++) if (key(order.get(i)).equals(focus)) at = i;
        int next = at < 0 ? (dir > 0 ? 0 : order.size() - 1) : (at + dir + order.size()) % order.size();
        Node n = order.get(next);
        if (n.type == T.SEARCH) {
            searchFocused = true;
            focus = key(n);
            focusVisible = false;
            return;
        }
        setFocus(n);
    }

    private void setFocus(Node n) {
        focus = key(n);
        focusVisible = true;
        if (frame != null && frame.body.contains(n)) {
            // scroll it into view
            if (n.y < st.scroll) st.scroll = Math.max(0f, n.y - 4f);
            else if (n.y + n.h > st.scroll + frame.bodyHeight) st.scroll = Math.min(frame.maxScroll(), n.y + n.h - frame.bodyHeight + 4f);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(hi, v);
    }
}
