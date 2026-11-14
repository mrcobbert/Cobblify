package com.bedwarsqol.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Edit HUD's right-click menu, from one rule: {@code [Show] Reset Position, Reset Size, [Background: On|Off],
 * Settings, [Hide]}. Show lists for a hidden item, Hide for a shown one, and neither for an item that cannot be hidden
 * (chat and the tab list). The Background row is the item's own Background switch, the same one its settings row uses.
 */
public final class EditHudMenu {

    public static final String SHOW = "Show";
    public static final String HIDE = "Hide";
    public static final String RESET_POSITION = "Reset Position";
    public static final String RESET_SIZE = "Reset Size";
    public static final String OPEN_SETTINGS = "Settings";
    private static final String BACKGROUND = "Background: ";

    private EditHudMenu() {
    }

    /** The rows for an item that is {@code shown} or not, can be hidden or not, with Background {@code background}. */
    public static List<String> items(boolean shown, boolean hideable, Boolean background) {
        List<String> items = new ArrayList<String>();
        if (!shown && hideable) items.add(SHOW);
        items.add(RESET_POSITION);
        items.add(RESET_SIZE);
        if (background != null) items.add(background(background));
        items.add(OPEN_SETTINGS);
        if (shown && hideable) items.add(HIDE);
        return items;
    }

    /** The Background row's text for the item's current value. */
    public static String background(boolean on) {
        return BACKGROUND + (on ? "On" : "Off");
    }

    public static boolean isBackground(String item) {
        return item != null && item.startsWith(BACKGROUND);
    }

    /** Whether HUD item {@code id} can be hidden from the editor (Hide, Delete): every item but chat and the tab list. */
    public static boolean hideable(String id) {
        return !"chat".equals(id) && !"tablist".equals(id);
    }
}
