package com.bedwarsqol.gui.sheet;

/**
 * The live settings behind the sheet, by schema id. Each tree implements it over its {@code ClientSettings}, and
 * lists only the modules and rows it has. Toggles and choices save at once; numbers save on {@link #save()}, so a
 * slider drag writes the file once, when it ends.
 */
public interface SheetValues {

    /** Whether this platform has the module or row. */
    boolean has(String id);

    /** A module's on/off, or a toggle row's value. */
    boolean on(String id);

    void setOn(String id, boolean on);

    /** The chosen option name of an option row or swatch row. */
    String choice(String id);

    void setChoice(String id, String option);

    double number(String id);

    void setNumber(String id, double value);

    /** What a key row shows: the bound key's name, or "None". */
    String keyLabel(String id);

    /** Run an action row (Reset Session). */
    void run(String id);

    /** Write the settings file. */
    void save();
}
