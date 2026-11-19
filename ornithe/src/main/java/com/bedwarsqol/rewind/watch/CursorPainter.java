package com.bedwarsqol.rewind.watch;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;

/**
 * The recorded mouse cursor (PLAN "ViewPart": "plus a drawn cursor arrow"; Phase 12; TASK C5). While recording, the
 * cursor over a screen was the system's own pointer, which is not part of the picture vanilla draws; playback draws
 * an arrow at the recorded position over the recorded screen, so a click on a shop item or an inventory slot is seen
 * where it happened. A plain black-outlined white arrow, its tip at the recorded point, sized in screen pixels (one
 * cell per pixel at GUI scale 1-3, two at 4 and up) and snapped to the pixel grid. Drawn over the screen and its
 * tooltip, under the viewer bar ({@link RecordedScreens#drawUnder}). Drawing only: no input, nothing else changes.
 */
final class CursorPainter {

    /** The arrow, row by row: {@code B} outline, {@code W} fill, {@code .} clear; the tip is row 0, column 0. */
    static final String[] ARROW = {
            "B...........",
            "BB..........",
            "BWB.........",
            "BWWB........",
            "BWWWB.......",
            "BWWWWB......",
            "BWWWWWB.....",
            "BWWWWWWB....",
            "BWWWWWWWB...",
            "BWWWWWWWWB..",
            "BWWWWWWWWWB.",
            "BWWWWWWBBBBB",
            "BWWWBWWB....",
            "BWWBBWWB....",
            "BWB..BWWB...",
            "BB...BWWB...",
            "B.....BWWB..",
            "......BWWB..",
            ".......BB...",
    };

    private static final int OUTLINE = 0xFF000000;
    private static final int FILL = 0xFFFFFFFF;
    /** Above vanilla's items (z 200-300) and tooltips (300) in a container screen. */
    private static final float Z = 400f;

    private CursorPainter() {
    }

    /** The arrow with its tip at GUI point ({@code x}, {@code y}); {@code scaleFactor} is the GUI scale in use. */
    static void draw(float x, float y, int scaleFactor) {
        int scale = Math.max(1, scaleFactor);
        int cellPx = Math.max(1, scale / 2);
        float cell = cellPx / (float) scale;
        GlStateManager.pushMatrix();
        GlStateManager.translate(Math.round(x * scale) / (float) scale, Math.round(y * scale) / (float) scale, Z);
        GlStateManager.scale(cell, cell, 1f);
        for (int r = 0; r < ARROW.length; r++) {
            String row = ARROW[r];
            int c = 0;
            while (c < row.length()) {
                char ch = row.charAt(c);
                int end = c + 1;
                while (end < row.length() && row.charAt(end) == ch) {
                    end++;
                }
                if (ch != '.') {
                    Gui.drawRect(c, r, end, r + 1, ch == 'B' ? OUTLINE : FILL);
                }
                c = end;
            }
        }
        GlStateManager.popMatrix();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
