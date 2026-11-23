package com.bedwarsqol.rewind.watch;

/**
 * The Esc menu while watching (G40; TASK A12, Rewind Export TASK U1; mockup {@code drawEsc}): a 172 su card over the
 * darkened view with Resume, Rejoin Hypixel (the hero key), Export and Gallery sharing a row, and Quit to Title.
 * Opening it pauses; Resume restores the play state from before, except at the end, where playback stays paused.
 * Esc resumes. A key fires when it is pressed and released over the same key.
 */
public final class EscMenuModel {

    public enum Key { RESUME, REJOIN, EXPORT, GALLERY, QUIT }

    public static final float CARD_W = 172f;
    public static final float KEY_H = 20f;
    private static final float PAD_X = 10f, PAD_Y = 1f + 9f, GAP = 4f;
    public static final float CARD_H = 1f + 9f + 4 * KEY_H + 3 * GAP + 9f + 1f;
    private static final int KEY_ESCAPE = 1;

    /** Where the card and its keys are on a screen, in GUI px (su). */
    public static final class Layout {
        private final float x, y;

        Layout(float x, float y) {
            this.x = x;
            this.y = y;
        }

        /** {x, y, w, h}. */
        public float[] card() {
            return new float[]{x, y, CARD_W, CARD_H};
        }

        /** {x, y, w, h} of a key. Export and Gallery split the third row, a {@code GAP} between them. */
        public float[] key(Key k) {
            float w = CARD_W - 2 * PAD_X, half = (w - GAP) / 2f;
            switch (k) {
                case RESUME:
                    return new float[]{x + PAD_X, y + PAD_Y, w, KEY_H};
                case REJOIN:
                    return new float[]{x + PAD_X, y + PAD_Y + (KEY_H + GAP), w, KEY_H};
                case EXPORT:
                    return new float[]{x + PAD_X, y + PAD_Y + 2 * (KEY_H + GAP), half, KEY_H};
                case GALLERY:
                    return new float[]{x + PAD_X + half + GAP, y + PAD_Y + 2 * (KEY_H + GAP), half, KEY_H};
                default:
                    return new float[]{x + PAD_X, y + PAD_Y + 3 * (KEY_H + GAP), w, KEY_H};
            }
        }

        /** The key under the point, or null. */
        public Key hit(float px, float py) {
            for (Key k : Key.values()) {
                float[] r = key(k);
                if (px >= r[0] && py >= r[1] && px < r[0] + r[2] && py < r[1] + r[3]) return k;
            }
            return null;
        }

        public boolean insideCard(float px, float py) {
            return px >= x && py >= y && px < x + CARD_W && py < y + CARD_H;
        }
    }

    private boolean resumePlays = true;
    private Key pressed;

    public static Layout layout(float screenW, float screenH) {
        return new Layout(Math.round((screenW - CARD_W) / 2f), Math.round((screenH - CARD_H) / 2f));
    }

    public static String label(Key k) {
        switch (k) {
            case RESUME:
                return "Resume";
            case REJOIN:
                return "Rejoin Hypixel";
            case EXPORT:
                return "Export";
            case GALLERY:
                return "Gallery";
            default:
                return "Quit to Title";
        }
    }

    /** Whether the key is drawn in the accent. */
    public static boolean hero(Key k) {
        return k == Key.REJOIN;
    }

    /** The menu opens (and playback pauses); {@code playing} is the play state just before. */
    public void open(boolean playing) {
        resumePlays = playing;
        pressed = null;
    }

    /** Whether Resume plays again: it did before the menu opened and the playhead is not at the end. */
    public boolean resumePlays(boolean atEnd) {
        return resumePlays && !atEnd;
    }

    /** The key a keyboard key triggers: Esc resumes; others nothing (null). */
    public Key keyPressed(int keyCode) {
        return keyCode == KEY_ESCAPE ? Key.RESUME : null;
    }

    public void mouseDown(Layout l, float x, float y) {
        pressed = l.hit(x, y);
    }

    /** The key that fires (pressed and released over it), or null. */
    public Key mouseUp(Layout l, float x, float y) {
        Key p = pressed;
        pressed = null;
        return p != null && p == l.hit(x, y) ? p : null;
    }

    /** The key held down, drawn sunk; null when none. */
    public Key pressed() {
        return pressed;
    }
}
