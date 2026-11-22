package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.gui.sheet.McCanvas;
import com.bedwarsqol.gui.sheet.SheetColors;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;

/**
 * Esc while watching (G40, G48, R2 subset; TASK A12; mockup {@code drawEsc}): a card over the darkened view with
 * Resume, Rejoin Hypixel (the accent key), Gallery and Quit to Title ({@link EscMenuModel}, drawn by
 * {@link RewindPainter#escMenu}). Opening it pauses playback; Resume (or Esc) restores the play state from before and
 * returns to the viewer; Rejoin ends the session and reconnects to the server that was left (no command is sent);
 * Gallery opens the gallery, whose Done comes back here; Quit to Title ends the session on the title screen.
 */
public final class RewindEscScreen extends RewindScreen {

    private final PlaybackSession session;
    private final GuiScreen viewer;
    private final EscMenuModel model;
    private final McCanvas canvas = new McCanvas();
    private EscMenuModel.Layout layout;
    private int scale = 1;
    private boolean leftDown;
    private int accent = -1;
    private SheetColors.Shades accentShades = SheetColors.Shades.of(0xC0DCFF);

    /**
     * @param viewer the viewer Resume returns to
     * @param model  the menu, already {@link #opened} (playback paused, the play state from before kept)
     */
    RewindEscScreen(PlaybackSession session, GuiScreen viewer, EscMenuModel model) {
        this.session = session;
        this.viewer = viewer;
        this.model = model;
    }

    /** Pauses playback and returns the menu's model holding the play state from before. */
    static EscMenuModel opened(PlaybackSession session) {
        ReplayTicks t = session.ticks();
        EscMenuModel m = new EscMenuModel();
        m.open(t != null && !t.paused());
        session.setPaused(true);
        return m;
    }

    /**
     * Carries out a key: Resume restores the play state ({@link EscMenuModel#resumePlays}) and runs
     * {@code showViewer}; Rejoin and Quit to Title end the session ({@link PlaybackSession.Exit#REJOIN},
     * {@link PlaybackSession.Exit#TITLE}); Gallery runs {@code showGallery} and keeps the session.
     */
    static void apply(EscMenuModel.Key key, PlaybackSession session, EscMenuModel model, Runnable showViewer,
                      Runnable showGallery) {
        if (key == null) {
            return;
        }
        switch (key) {
            case RESUME:
                ReplayTicks t = session.ticks();
                session.setPaused(!model.resumePlays(t == null || t.atEnd()));
                showViewer.run();
                break;
            case REJOIN:
                session.requestExit(PlaybackSession.Exit.REJOIN);
                break;
            case GALLERY:
                showGallery.run();
                break;
            case QUIT:
                session.requestExit(PlaybackSession.Exit.TITLE);
                break;
            default:
                break;
        }
    }

    private void fire(EscMenuModel.Key key) {
        final GuiScreen self = this;
        apply(key, session, model, () -> RewindPlayback.display(viewer),
                () -> RewindPlayback.display(new RewindGalleryScreen(GalleryModel.Origin.PLAYING, self)));
    }

    // ------------------------------------------------------------------ screen

    @Override
    protected void drawRewind(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        scale = new ScaledResolution(mc).getScaleFactor();
        float sw = mc.displayWidth / (float) scale, sh = mc.displayHeight / (float) scale;
        layout = EscMenuModel.layout(sw, sh);
        float[] m = mouse(Mouse.getX(), Mouse.getY());
        ClientSettings cfg = BedwarsQol.config;
        canvas.begin(scale, 0f, 0f, mc.displayHeight, scale, cfg == null || cfg.minecraftFont());
        RewindPainter.escMenu(canvas, layout, layout.hit(m[0], m[1]), model.pressed(), accent(cfg));
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

    /** A cursor position in GUI px (sheet units), as the settings sheet reads it. */
    private float[] mouse(int x, int y) {
        return new float[]{(x + 0.5f) / scale, (mc.displayHeight - y - 0.5f) / scale};
    }

    @Override
    public void handleMouseInput() {
        EscMenuModel.Layout l = layout;
        if (l == null || Mouse.getEventButton() != 0) {
            return;
        }
        float[] m = mouse(Mouse.getEventX(), Mouse.getEventY());
        if (Mouse.getEventButtonState()) {
            leftDown = true;
            model.mouseDown(l, m[0], m[1]);
            if (model.pressed() != null) {
                mc.getSoundHandler().playSound(
                        PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
            }
        } else if (leftDown) {
            leftDown = false;
            fire(model.mouseUp(l, m[0], m[1]));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        fire(model.keyPressed(keyCode));
    }
}
