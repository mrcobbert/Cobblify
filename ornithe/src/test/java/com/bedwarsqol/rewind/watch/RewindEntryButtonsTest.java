package com.bedwarsqol.rewind.watch;

import net.minecraft.client.gui.GuiButton;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * The two vanilla entry points of Rewind (Phase 6c), on plain button lists built the way vanilla 1.8.9 builds them:
 * <ul>
 *   <li>{@code GuiIngameMenu.initGui} (javap of the mapped jar: {@code i = -16}; Disconnect id 1 at {@code h/4+120+i},
 *       Back 4 at {@code h/4+24+i}, Options 0 and LAN 7 at {@code h/4+96+i}, Achievements 5 and Statistics 6 at
 *       {@code h/4+48+i}), which the pause-menu mixin hands to {@link RewindGalleryScreen#layoutPauseMenu};</li>
 *   <li>{@code GuiMainMenu.addSingleplayerMultiplayerButtons} (Realms id 14), which the title mixin hands to
 *       {@link RewindGalleryScreen#relabelTitle}.</li>
 * </ul>
 */
public class RewindEntryButtonsTest {

    private static final int[][] SIZES = {{320, 240}, {427, 240}, {641, 361}, {854, 480}, {960, 540}};

    /** Vanilla's pause menu, in vanilla's order, ids, sizes and positions. */
    private static List<GuiButton> vanillaPauseMenu(int w, int h) {
        int i = -16;
        List<GuiButton> list = new ArrayList<GuiButton>();
        list.add(new GuiButton(1, w / 2 - 100, h / 4 + 120 + i, "Disconnect"));
        list.add(new GuiButton(4, w / 2 - 100, h / 4 + 24 + i, "Back to Game"));
        list.add(new GuiButton(0, w / 2 - 100, h / 4 + 96 + i, 98, 20, "Options..."));
        list.add(new GuiButton(7, w / 2 + 2, h / 4 + 96 + i, 98, 20, "Open to LAN"));
        list.add(new GuiButton(5, w / 2 - 100, h / 4 + 48 + i, 98, 20, "Achievements"));
        list.add(new GuiButton(6, w / 2 + 2, h / 4 + 48 + i, 98, 20, "Statistics"));
        return list;
    }

    private static GuiButton byId(List<GuiButton> list, int id) {
        for (GuiButton b : list) {
            if (b.id == id) return b;
        }
        return null;
    }

    @Test
    public void pauseMenuRowGoesAboveDisconnectAndVanillaKeepsItsOrder() {
        for (int[] s : SIZES) {
            int w = s[0], h = s[1];
            List<GuiButton> before = vanillaPauseMenu(w, h);
            List<GuiButton> list = vanillaPauseMenu(w, h);
            RewindGalleryScreen.layoutPauseMenu(list, w, h);

            String at = w + "x" + h;
            assertEquals(at + ": one row added", before.size() + 1, list.size());
            for (int k = 0; k < before.size(); k++) {
                GuiButton b = before.get(k), a = list.get(k);
                assertEquals(at + ": vanilla order kept", b.id, a.id);
                assertEquals(at + ": id " + a.id + " x", b.xPosition, a.xPosition);
                assertEquals(at + ": id " + a.id + " width", b.getButtonWidth(), a.getButtonWidth());
                assertEquals(at + ": id " + a.id + " y", b.yPosition + PauseMenuLayout.shift(a.id), a.yPosition);
                assertEquals(at + ": id " + a.id + " label", b.displayString, a.displayString);
            }

            GuiButton row = list.get(list.size() - 1);
            int[] r = PauseMenuLayout.watchButton(w, h);
            assertEquals(at, PauseMenuLayout.WATCH_BUTTON_ID, row.id);
            assertEquals(at, PauseMenuLayout.WATCH_LABEL, row.displayString);
            assertEquals(at, r[0], row.xPosition);
            assertEquals(at, r[1], row.yPosition);
            assertEquals(at, r[2], row.getButtonWidth());

            // The mockup's drawPause: everything above the row raised 12, Disconnect lowered 12, 4 px gaps.
            assertEquals(at, h / 4 - 4, byId(list, 4).yPosition);
            assertEquals(at, h / 4 + 20, byId(list, 5).yPosition);
            assertEquals(at, h / 4 + 20, byId(list, 6).yPosition);
            assertEquals(at, h / 4 + 68, byId(list, 0).yPosition);
            assertEquals(at, h / 4 + 68, byId(list, 7).yPosition);
            assertEquals(at, h / 4 + 92, row.yPosition);
            assertEquals(at, h / 4 + 116, byId(list, 1).yPosition);
            assertEquals(at, w / 2 - 100, row.xPosition);
            assertEquals(at, 200, row.getButtonWidth());
            assertEquals(at + ": gap above the row", 4, row.yPosition - (byId(list, 0).yPosition + 20));
            assertEquals(at + ": gap below the row", 4, byId(list, 1).yPosition - (row.yPosition + 20));
        }
    }

    @Test
    public void pauseMenuLeavesOtherModsButtonsAlone() {
        List<GuiButton> list = vanillaPauseMenu(427, 240);
        GuiButton other = new GuiButton(9001, 3, 7, 50, 20, "Other");
        list.add(other);
        RewindGalleryScreen.layoutPauseMenu(list, 427, 240);
        assertSame(other, byId(list, 9001));
        assertEquals(3, other.xPosition);
        assertEquals(7, other.yPosition);
    }

    /** Vanilla's title-screen buttons (not demo), in vanilla's order. */
    private static List<GuiButton> vanillaTitle(int w, int h) {
        int j = h / 4 + 48;
        List<GuiButton> list = new ArrayList<GuiButton>();
        list.add(new GuiButton(1, w / 2 - 100, j, "Singleplayer"));
        list.add(new GuiButton(2, w / 2 - 100, j + 24, "Multiplayer"));
        list.add(new GuiButton(14, w / 2 - 100, j + 48, "Minecraft Realms"));
        list.add(new GuiButton(0, w / 2 - 100, j + 72 + 12, 98, 20, "Options..."));
        list.add(new GuiButton(4, w / 2 + 2, j + 72 + 12, 98, 20, "Quit Game"));
        return list;
    }

    @Test
    public void titleRealmsSlotReadsRewindOnlyWithTheModuleOn() {
        List<GuiButton> on = vanillaTitle(427, 240);
        RewindGalleryScreen.relabelTitle(on, true);
        GuiButton rewind = byId(on, TitleEntryRules.REALMS_BUTTON_ID);
        assertNotNull(rewind);
        assertEquals("Rewind", rewind.displayString);
        assertEquals(427 / 2 - 100, rewind.xPosition);
        assertEquals(240 / 4 + 48 + 48, rewind.yPosition);
        assertEquals(200, rewind.getButtonWidth());
        List<GuiButton> vanilla = vanillaTitle(427, 240);
        for (int k = 0; k < vanilla.size(); k++) {
            assertEquals(vanilla.get(k).id, on.get(k).id);
            if (vanilla.get(k).id != TitleEntryRules.REALMS_BUTTON_ID) {
                assertEquals(vanilla.get(k).displayString, on.get(k).displayString);
            }
        }

        List<GuiButton> off = vanillaTitle(427, 240);
        RewindGalleryScreen.relabelTitle(off, false);
        assertEquals("Minecraft Realms", byId(off, TitleEntryRules.REALMS_BUTTON_ID).displayString);
    }

    @Test
    public void titleWithoutARealmsButtonIsUntouched() {
        List<GuiButton> demo = new ArrayList<GuiButton>();
        demo.add(new GuiButton(11, 10, 10, "Play Demo World"));
        demo.add(new GuiButton(12, 10, 34, "Reset Demo World"));
        RewindGalleryScreen.relabelTitle(demo, true);
        assertEquals("Play Demo World", demo.get(0).displayString);
        assertEquals("Reset Demo World", demo.get(1).displayString);
        assertNull(byId(demo, TitleEntryRules.REALMS_BUTTON_ID));
    }
}
