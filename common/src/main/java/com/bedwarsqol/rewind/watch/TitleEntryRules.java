package com.bedwarsqol.rewind.watch;

/**
 * The title screen's Rewind entry (G17, G50; TASK A1): with the Rewind module on, the Minecraft Realms button
 * ({@code GuiMainMenu} id 14) reads "Rewind" and opens the gallery; with it off the button stays vanilla Realms.
 */
public final class TitleEntryRules {

    /** {@code GuiMainMenu.realmsButton}'s id; {@code actionPerformed} id 14 calls {@code switchToRealms}. */
    public static final int REALMS_BUTTON_ID = 14;
    public static final String LABEL = "Rewind";

    private TitleEntryRules() {
    }

    /** The Realms button's label, or null to keep vanilla's. */
    public static String label(boolean rewindOn) {
        return rewindOn ? LABEL : null;
    }

    /** Whether pressing button {@code id} opens the gallery instead of vanilla's action. */
    public static boolean opensGallery(boolean rewindOn, int id) {
        return rewindOn && id == REALMS_BUTTON_ID;
    }
}
