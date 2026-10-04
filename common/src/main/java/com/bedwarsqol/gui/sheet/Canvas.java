package com.bedwarsqol.gui.sheet;

/**
 * What the sheet painter draws on. Coordinates are sheet units (su); the implementation rounds every edge to a
 * device pixel on its own, from the exact position, the way WebKit snaps boxes. Colours are ARGB.
 */
public interface Canvas extends TextMetrics {

    /** Fill the axis-aligned rect [x, x+w) x [y, y+h). */
    void fill(float x, float y, float w, float h, int argb);

    /** An anti-aliased stroke through the points {x0, y0, x1, y1, ...} with butt caps and mitred joins. */
    void stroke(float[] points, float width, int argb);

    /** An anti-aliased stroked circle. */
    void ring(float cx, float cy, float r, float width, int argb);

    /** Draw text whose CSS line box starts at {@code lineTop} and is {@code lineHeight} tall. */
    void text(String s, float x, float lineTop, float lineHeight, SheetFont font, int argb);

    /** The baseline of a line box, in su. */
    float baseline(SheetFont font, float lineTop, float lineHeight);

    /** The inline content area (ascent to descent) of a line box, {top, bottom} in su: a {@code mark}'s extent. */
    float[] contentArea(SheetFont font, float lineTop, float lineHeight);

    /** Clip to a rect, intersected with the current clip. */
    void pushClip(float x, float y, float w, float h);

    void popClip();

    /** Multiply every following colour's alpha by {@code a}. */
    void pushAlpha(float a);

    void popAlpha();

    /**
     * Start a disabled control: everything until {@link #endDisabled()} draws as one layer at opacity 0.40 and
     * saturation 0.15 over {@code backdrop}, the flat colour under the control.
     */
    void beginDisabled(int backdrop);

    void endDisabled();
}
