package com.bedwarsqol.gui.sheet;

/** Text measurement for the layout, in sheet units at the font's size. */
public interface TextMetrics {

    /** Device pixels per sheet unit. */
    float unit();

    float width(String s, SheetFont font);

    /** The font's ascent, rounded in device px as WebKit rounds it, in su. */
    float ascent(SheetFont font);

    /** The font's descent, rounded in device px, in su. */
    float descent(SheetFont font);

    /** CSS {@code line-height: normal} for the font: its rounded ascent plus descent, in su. */
    float normalLineHeight(SheetFont font);

    /** Whether text is Minecraft's own font (the Font setting), which draws whole device pixels and its own shadow. */
    boolean minecraftFont();

    /** One pixel of Minecraft's font at this role, in su; 0 where the role is drawn in the modern font. */
    float pixel(SheetFont font);
}
