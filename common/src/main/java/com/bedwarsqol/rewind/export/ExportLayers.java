package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.watch.ViewState;

import java.util.EnumSet;

/**
 * What an export frame draws (TASK P3, P5; Rewind Export PLAN Solution 2), per view: exactly what watching shows for the
 * view active when Export was pressed, without any of the viewer's chrome. Watching's own rules decide the rest
 * ({@link ViewState}): the clean view and the recorded F1 hide every HUD (the recorded one too, it draws inside the
 * skipped overlay), the hand and recorded screens; recorded
 * screens, the recorded Cobblify HUD and the chat input bar are your view's only; the ridden player's name shows in
 * another player's view; the free camera has no hand. Chat off drops the chat lines and the recorded chat input bar
 * with its typed text (review I4). Pure.
 */
public final class ExportLayers {

    /** The layers of a watched frame. */
    public enum Layer {
        WORLD, HAND, VANILLA_HUD, COBBLIFY_HUD, RECORDED_SCREENS, CURSOR, RIDDEN_NAME, CHAT_LINES, CHAT_INPUT,
        // the viewer's chrome: never in an export
        VIEWER_BAR, RANGE_HANDLES, EXPORT_PANEL, EXPORT_KEY, CAMERA_MENU, HEAD_ICONS, FLY_SPEED, LOADING_CARD,
        ESC_MENU, MOTION_BLUR
    }

    private static final EnumSet<Layer> CHROME = EnumSet.range(Layer.VIEWER_BAR, Layer.MOTION_BLUR);

    private ExportLayers() {
    }

    /** The layers an export frame asks for in {@code view}, with Chat on or off. */
    public static EnumSet<Layer> of(ViewState view, boolean chat) {
        EnumSet<Layer> l = EnumSet.of(Layer.WORLD);
        boolean hud = !view.hidesEveryHud();
        if (!view.freeCamera() && view.perspective() == 0 && !view.hideGui()) {
            l.add(Layer.HAND);
        }
        if (hud) {
            l.add(Layer.VANILLA_HUD);
            if (chat) {
                l.add(Layer.CHAT_LINES);
            }
        }
        if (hud && view.recordedHud()) {
            l.add(Layer.COBBLIFY_HUD);
        }
        int screen = view.screenKind();
        if (screen == RewindFormat.SCREEN_CHAT) {
            if (chat) {
                l.add(Layer.CHAT_INPUT);
            }
        } else if (screen != RewindFormat.SCREEN_NONE) {
            l.add(Layer.RECORDED_SCREENS);
            l.add(Layer.CURSOR);
        }
        if (view.riddenEntityId() >= 0 && !view.clean()) {
            l.add(Layer.RIDDEN_NAME);
        }
        return l;
    }

    /** True for the viewer's own chrome, which no export frame holds. */
    public static boolean chrome(Layer layer) {
        return CHROME.contains(layer);
    }
}
