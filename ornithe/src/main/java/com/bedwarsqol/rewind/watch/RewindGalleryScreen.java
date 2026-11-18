package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.gui.sheet.McCanvas;
import com.bedwarsqol.gui.sheet.SheetColors;
import com.bedwarsqol.rewind.Rewind;
import com.bedwarsqol.rewind.RewindGameRules;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.List;
import java.util.TimeZone;

/**
 * Rewind's gallery (G39, G46; TASK A2, A3; mockup {@code drawGallery}) and its two vanilla entries (G16, G17, G35,
 * G50; TASK A1, A4). Rows, keys and actions are {@link GalleryModel}'s, drawing is {@link RewindPainter#gallery}, in
 * GUI px (one sheet unit per GUI pixel); this screen feeds them LWJGL input and carries out the model's intents with
 * {@link Rewind#library()}.
 *
 * <p><b>Backdrop.</b> Opened from the title screen, the title's own panorama turns behind the card with vanilla's two
 * gradients and no logo (mockup {@code drawTitleBg(false)}); otherwise vanilla's default background (the dimmed world
 * of a playing session).
 *
 * <p><b>Entries.</b> The title screen's Realms button reads "Rewind" and opens the gallery while the Rewind module is
 * on ({@link #relabelTitle}, {@link #titleScreenAction}; {@code GuiMainMenuRewindMixin}). The pause menu gets "Watch
 * Last Game" above Disconnect when {@link WatchEntryRules} says so ({@link #pauseMenuInit}, {@link #layoutPauseMenu};
 * {@code GuiIngameMenuRewindMixin}); the row opens the newest kept game through {@link RewindPlayback#open}, which
 * leaves the server first. With the module off both screens stay vanilla.
 */
public final class RewindGalleryScreen extends GuiScreen {

    /** The title screen's panorama, drawn by {@code GuiMainMenuRewindMixin} with vanilla's private skybox code. */
    public interface TitleBackdrop {
        void bedwarsqol$drawPanorama(int mouseX, int mouseY, float partialTicks);
    }

    private final GalleryModel.Origin origin;
    /** Where Done leads: the title screen, or the Esc menu of a playing session. */
    private final GuiScreen back;
    private final McCanvas canvas = new McCanvas();
    private GalleryModel model;
    private GalleryModel.Layout layout;
    private int scale = 1;
    private boolean leftDown;
    private GuiTheme.Accent accent;
    private SheetColors.Shades accentShades = SheetColors.Shades.of(0xC0DCFF);

    /**
     * @param origin {@link GalleryModel.Origin#TITLE} from the title screen or after a recording could not be played,
     *               {@link GalleryModel.Origin#PLAYING} from a playing session's Esc menu
     * @param back   the screen Done opens: the title screen (a new one when null), or that Esc menu
     */
    public RewindGalleryScreen(GalleryModel.Origin origin, GuiScreen back) {
        this.origin = origin == null ? GalleryModel.Origin.TITLE : origin;
        this.back = back != null ? back : new GuiMainMenu();
    }

    /** The gallery over the title screen {@code title}, whose Done returns there. */
    public static RewindGalleryScreen fromTitle(GuiScreen title) {
        return new RewindGalleryScreen(GalleryModel.Origin.TITLE, title);
    }

    // ------------------------------------------------------------------ screen

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (model == null) {
            model = new GalleryModel(origin, Rewind.library().list(), System.currentTimeMillis(), TimeZone.getDefault());
            Rewind.refreshLibrary();
        }
        if (back instanceof TitleBackdrop && (back.width != width || back.height != height)) {
            back.setWorldAndResolution(mc, width, height); // the panorama needs the title laid out at this size
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        if (back instanceof TitleBackdrop) {
            back.updateScreen(); // turns the panorama
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        scale = new ScaledResolution(mc).getScaleFactor();
        backdrop(mouseX, mouseY, partialTicks);
        ClientSettings cfg = BedwarsQol.config;
        canvas.begin(scale, 0f, 0f, mc.displayHeight, scale, cfg == null || cfg.minecraftFont());
        layout = model.layout(mc.displayWidth / (float) scale, mc.displayHeight / (float) scale, canvas);
        float[] m = mouse(Mouse.getX(), Mouse.getY());
        RewindPainter.gallery(canvas, model, layout, layout.hit(m[0], m[1]), accent(cfg));
        canvas.end();
    }

    private void backdrop(int mouseX, int mouseY, float partialTicks) {
        if (back instanceof TitleBackdrop && mc.theWorld == null) {
            GlStateManager.disableAlpha();
            ((TitleBackdrop) back).bedwarsqol$drawPanorama(mouseX, mouseY, partialTicks);
            GlStateManager.enableAlpha();
            drawGradientRect(0, 0, width, height, 0x80FFFFFF, 0x00FFFFFF);
            drawGradientRect(0, 0, width, height, 0x00000000, 0x80000000);
        } else {
            drawDefaultBackground();
        }
    }

    private SheetColors.Shades accent(ClientSettings cfg) {
        GuiTheme.Accent a = GuiTheme.fromToken(cfg == null ? null : cfg.guiAccent);
        if (a != accent) {
            accent = a;
            accentShades = SheetColors.Shades.of(a.base() & 0xFFFFFF);
        }
        return accentShades;
    }

    /** A cursor position in GUI px (sheet units), as the settings sheet reads it. */
    private float[] mouse(int x, int y) {
        return new float[]{(x + 0.5f) / scale, (mc.displayHeight - y - 0.5f) / scale};
    }

    // ------------------------------------------------------------------ input

    @Override
    public void handleMouseInput() {
        if (layout == null) return;
        float[] m = mouse(Mouse.getEventX(), Mouse.getEventY());
        if (Mouse.getEventButton() == 0) {
            if (Mouse.getEventButtonState()) {
                leftDown = true;
                model.mouseDown(layout, m[0], m[1]);
                GalleryModel.Hit p = model.pressed();
                if (p != null && p.part != GalleryModel.Hit.Part.ROW) click();
            } else if (leftDown) {
                leftDown = false;
                apply(model.mouseUp(layout, m[0], m[1], System.currentTimeMillis()));
            }
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) model.scroll(wheel > 0 ? -1 : 1);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        apply(model.key(keyCode, System.currentTimeMillis()));
    }

    private void apply(GalleryModel.Intent intent) {
        switch (intent.kind) {
            case WATCH:
                RewindPlayback.open(intent.id);
                break;
            case DELETE:
                if (Rewind.library().delete(intent.id)) model.removed(intent.id);
                else model.deleteFailed();
                break;
            case STAR:
                if (Rewind.library().setStarred(intent.id, intent.starred)) model.starred(intent.id, intent.starred);
                break;
            case DONE:
                mc.displayGuiScreen(back);
                break;
            default:
                break;
        }
    }

    private void click() {
        mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    // ------------------------------------------------------------------ the title screen's entry

    private static boolean rewindOn() {
        ClientSettings cfg = BedwarsQol.config;
        return cfg != null && cfg.rewind;
    }

    /** {@code GuiMainMenu.initGui} RETURN: the Realms button reads "Rewind" while the module is on. */
    public static void titleScreenInit(List<GuiButton> buttons) {
        relabelTitle(buttons, rewindOn());
    }

    /** Relabels the Realms button (id 14) "Rewind" when {@code rewindOn}; otherwise nothing changes. */
    public static void relabelTitle(List<GuiButton> buttons, boolean rewindOn) {
        String label = TitleEntryRules.label(rewindOn);
        if (label == null || buttons == null) return;
        for (GuiButton b : buttons) {
            if (b != null && b.id == TitleEntryRules.REALMS_BUTTON_ID) b.displayString = label;
        }
    }

    /**
     * {@code GuiMainMenu.actionPerformed} HEAD: the Realms slot opens the gallery while the module is on.
     *
     * @return true when handled here (vanilla's Realms action is skipped)
     */
    public static boolean titleScreenAction(GuiScreen title, GuiButton button) {
        if (button == null || !TitleEntryRules.opensGallery(rewindOn(), button.id)) return false;
        Minecraft.getMinecraft().displayGuiScreen(fromTitle(title));
        return true;
    }

    // ------------------------------------------------------------------ the pause menu's entry

    /** {@code GuiIngameMenu.initGui} RETURN: adds "Watch Last Game" when {@link WatchEntryRules} says so. */
    public static void pauseMenuInit(List<GuiButton> buttons, int width, int height) {
        if (!rewindOn() || !HypixelContext.isOnHypixel()) return;
        RewindGameRules.EntryState s = Rewind.entryState();
        Rewind.refreshLibrary(); // quick-checks finished games off the client thread for the next look
        // The recorder's state first: the library's metadata is only read when the row could show.
        if (!WatchEntryRules.show(true, true, WatchEntryRules.Newest.PASSED, s)) return;
        if (!WatchEntryRules.show(true, true, Rewind.newestAvailability(), s)) return;
        layoutPauseMenu(buttons, width, height);
    }

    /**
     * Makes room for the row and adds it ({@link PauseMenuLayout}): vanilla ids 0, 4, 5, 6, 7 move up 12, Disconnect
     * moves down 12, the row goes in between. Vanilla's buttons keep their order; other buttons are not moved.
     */
    public static void layoutPauseMenu(List<GuiButton> buttons, int width, int height) {
        for (GuiButton b : buttons) {
            if (b != null) b.yPosition += PauseMenuLayout.shift(b.id);
        }
        int[] r = PauseMenuLayout.watchButton(width, height);
        buttons.add(new GuiButton(PauseMenuLayout.WATCH_BUTTON_ID, r[0], r[1], r[2], r[3],
                PauseMenuLayout.WATCH_LABEL));
    }

    /**
     * {@code GuiIngameMenu.actionPerformed} HEAD: "Watch Last Game" opens the newest kept game.
     *
     * @return true when handled here
     */
    public static boolean pauseMenuAction(GuiButton button) {
        if (button == null || button.id != PauseMenuLayout.WATCH_BUTTON_ID) return false;
        RecordingLibrary.NewestGame newest = Rewind.newestGame();
        if (newest != null) RewindPlayback.open(newest.id);
        return true;
    }
}
