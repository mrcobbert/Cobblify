package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.export.ExportLayers.Layer;
import com.bedwarsqol.rewind.watch.ViewState;
import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExportLayers} (TASK P3, P5): per view (your view, another player's, the free camera; clean view on and off;
 * Chat on and off) the layers an export frame asks for. No viewer chrome in any; Chat off removes the chat lines and
 * the recorded chat input bar, Chat on keeps both wherever watching shows them.
 */
public class ExportLayersTest {

    private static final EnumSet<Layer> CHROME = EnumSet.of(Layer.VIEWER_BAR, Layer.RANGE_HANDLES, Layer.EXPORT_PANEL,
            Layer.EXPORT_KEY, Layer.CAMERA_MENU, Layer.HEAD_ICONS, Layer.FLY_SPEED, Layer.LOADING_CARD, Layer.ESC_MENU,
            Layer.MOTION_BLUR);

    private static ViewState you(boolean clean, int screen) {
        ViewState v = new ViewState();
        v.recorded(0, 0, screen);
        if (clean) {
            v.toggleClean();
        }
        return v;
    }

    @Test
    public void noViewerChromeInAnyView() {
        for (ViewState v : new ViewState[]{you(false, RewindFormat.SCREEN_NONE), you(true, RewindFormat.SCREEN_CHAT),
                ridden(false), ridden(true), free(false), free(true)}) {
            for (boolean chat : new boolean[]{true, false}) {
                EnumSet<Layer> l = ExportLayers.of(v, chat);
                for (Layer c : CHROME) {
                    assertFalse(v.view() + " clean " + v.clean() + " chat " + chat + " asks for " + c, l.contains(c));
                    assertTrue(c + " is chrome", ExportLayers.chrome(c));
                }
                assertTrue("the world is always drawn", l.contains(Layer.WORLD));
            }
        }
        assertFalse(ExportLayers.chrome(Layer.WORLD));
        assertFalse(ExportLayers.chrome(Layer.CHAT_INPUT));
    }

    @Test
    public void yourView() {
        EnumSet<Layer> l = ExportLayers.of(you(false, RewindFormat.SCREEN_NONE), true);
        assertEquals(EnumSet.of(Layer.WORLD, Layer.HAND, Layer.VANILLA_HUD, Layer.COBBLIFY_HUD, Layer.CHAT_LINES), l);
        EnumSet<Layer> chest = ExportLayers.of(you(false, RewindFormat.SCREEN_CHEST), true);
        assertEquals(EnumSet.of(Layer.WORLD, Layer.HAND, Layer.VANILLA_HUD, Layer.COBBLIFY_HUD, Layer.CHAT_LINES,
                Layer.RECORDED_SCREENS, Layer.CURSOR), chest);
        EnumSet<Layer> typing = ExportLayers.of(you(false, RewindFormat.SCREEN_CHAT), true);
        assertTrue(typing.contains(Layer.CHAT_INPUT));
        assertTrue(typing.contains(Layer.CHAT_LINES));
        ViewState f5 = you(false, RewindFormat.SCREEN_NONE);
        f5.cyclePerspective();
        assertFalse("no hand behind the player", ExportLayers.of(f5, true).contains(Layer.HAND));
        assertEquals("the clean view hides the HUD, screens and hand (hideGUI)", EnumSet.of(Layer.WORLD),
                ExportLayers.of(you(true, RewindFormat.SCREEN_CHAT), true));
        ViewState f1 = new ViewState();
        f1.recorded(0, RewindFormat.VIEW_HIDE_GUI, RewindFormat.SCREEN_NONE);
        assertEquals("the recorded F1", EnumSet.of(Layer.WORLD), ExportLayers.of(f1, true));
    }

    @Test
    public void chatOffRemovesTheLinesAndTheInputBarChatOnKeepsBoth() {
        EnumSet<Layer> on = ExportLayers.of(you(false, RewindFormat.SCREEN_CHAT), true);
        EnumSet<Layer> off = ExportLayers.of(you(false, RewindFormat.SCREEN_CHAT), false);
        assertTrue(on.contains(Layer.CHAT_LINES) && on.contains(Layer.CHAT_INPUT));
        assertFalse(off.contains(Layer.CHAT_LINES));
        assertFalse(off.contains(Layer.CHAT_INPUT));
        EnumSet<Layer> rest = EnumSet.copyOf(on);
        rest.removeAll(EnumSet.of(Layer.CHAT_LINES, Layer.CHAT_INPUT));
        assertEquals("nothing else changes", rest, off);
        assertFalse(ExportLayers.of(ridden(false), false).contains(Layer.CHAT_LINES));
    }

    @Test
    public void anotherPlayersView() {
        assertEquals(EnumSet.of(Layer.WORLD, Layer.HAND, Layer.VANILLA_HUD, Layer.CHAT_LINES, Layer.RIDDEN_NAME),
                ExportLayers.of(ridden(false), true));
        assertEquals(EnumSet.of(Layer.WORLD), ExportLayers.of(ridden(true), true));
    }

    @Test
    public void theFreeCamera() {
        assertEquals("no hand in the free camera", EnumSet.of(Layer.WORLD, Layer.VANILLA_HUD, Layer.CHAT_LINES),
                ExportLayers.of(free(false), true));
        assertEquals(EnumSet.of(Layer.WORLD), ExportLayers.of(free(true), true));
    }

    private static ViewState ridden(boolean clean) {
        ViewState v = new ViewState();
        v.ride(42);
        if (clean) {
            v.toggleClean();
        }
        return v;
    }

    private static ViewState free(boolean clean) {
        ViewState v = new ViewState();
        v.selectFree();
        if (clean) {
            v.toggleClean();
        }
        return v;
    }
}
