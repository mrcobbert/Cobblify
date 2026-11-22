package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.gui.sheet.McCanvas;
import com.bedwarsqol.gui.sheet.SheetColors;
import net.minecraft.client.gui.ScaledResolution;

/**
 * The loading card (G49; TASK A5; mockup {@code drawLoading}): an opaque dark screen with a card holding mode · map
 * and a key that fills by the catch-up's progress ({@link PlaybackSession#loadingProgress}), drawn by
 * {@link RewindPainter#loading}. No other text. It stands while a recording opens and while a backward seek rebuilds
 * the world. Esc gives up and ends on the title screen.
 */
public final class RewindLoadingScreen extends RewindScreen {

    private final PlaybackSession session;
    private final McCanvas canvas = new McCanvas();
    private int accent = -1;
    private SheetColors.Shades accentShades = SheetColors.Shades.of(0xC0DCFF);

    RewindLoadingScreen(PlaybackSession session) {
        this.session = session;
    }

    @Override
    protected void drawRewind(int mouseX, int mouseY, float partialTicks) {
        int scale = new ScaledResolution(mc).getScaleFactor();
        float sw = mc.displayWidth / (float) scale, sh = mc.displayHeight / (float) scale;
        ClientSettings cfg = BedwarsQol.config;
        canvas.begin(scale, 0f, 0f, mc.displayHeight, scale, cfg == null || cfg.minecraftFont());
        RewindPainter.loading(canvas, sw, sh, ModeLabels.title(session.meta()), session.loadingProgress(),
                accent(cfg));
        canvas.end();
    }

    private SheetColors.Shades accent(ClientSettings cfg) {
        int a = SheetColors.legible(GuiTheme.color(cfg == null ? null : cfg.guiAccent));
        if (a != accent) {
            accent = a;
            accentShades = SheetColors.Shades.of(a);
        }
        return accentShades;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == ViewerKeys.KEY_ESCAPE) {
            session.requestExit(PlaybackSession.Exit.TITLE);
        }
    }
}
