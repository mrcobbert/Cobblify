package com.bedwarsqol.rewind.watch;

/**
 * The pause menu with "Watch Last Game" (G35; mockup {@code drawPause}). Vanilla {@code GuiIngameMenu.initGui} puts
 * Back (4) at {@code h/4+8}, Achievements (5) and Statistics (6) at {@code h/4+32}, Options (0) and LAN (7) at
 * {@code h/4+80}, Disconnect (1) at {@code h/4+104}. With the row: ids 0, 4, 5, 6, 7 move up 12, Disconnect moves
 * down 12, and the row (200 x 20, centred) goes at {@code h/4+92}, 4 px above Disconnect. Vanilla keeps its order.
 */
public final class PauseMenuLayout {

    /** The row's button id: unused by vanilla's pause menu. */
    public static final int WATCH_BUTTON_ID = 0x5257;
    public static final String WATCH_LABEL = "Watch Last Game";
    public static final int SHIFT = 12;

    private PauseMenuLayout() {
    }

    /** How far a vanilla pause-menu button moves down (negative = up) when the row is shown. */
    public static int shift(int id) {
        switch (id) {
            case 0:
            case 4:
            case 5:
            case 6:
            case 7:
                return -SHIFT;
            case 1:
                return SHIFT;
            default:
                return 0;
        }
    }

    /** The row's {x, y, w, h} on a screen {@code width} x {@code height} GUI px. */
    public static int[] watchButton(int width, int height) {
        return new int[]{width / 2 - 100, height / 4 + 92, 200, 20};
    }
}
