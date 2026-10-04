package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.gui.sheet.KeyHold;
import com.bedwarsqol.gui.sheet.McCanvas;
import com.bedwarsqol.gui.sheet.SheetColors;
import com.bedwarsqol.gui.sheet.SheetController;
import com.bedwarsqol.gui.sheet.SheetFont;
import com.bedwarsqol.gui.sheet.SheetLayout;
import com.bedwarsqol.gui.sheet.SheetMotion;
import com.bedwarsqol.gui.sheet.SheetPainter;
import com.bedwarsqol.gui.sheet.SheetState;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The RShift settings sheet: a 244 su side sheet docked right over the untouched, still-running world. Layout,
 * painting and input live in {@code common/gui/sheet}; this screen feeds them device pixels, LWJGL events and
 * time, and draws through {@link McCanvas}. One sheet unit is one GUI pixel times the GUI Size.
 */
public class SettingsGui extends GuiScreen implements SheetController.Host {

    private static final float SCROLL_TAU = 0.10f;

    private final SheetState state = SheetState.SESSION;
    private final SettingsBindings values = new SettingsBindings();
    private final SheetController controller = new SheetController(state, values, this);
    private final McCanvas canvas = new McCanvas();
    private final SheetMotion motion = new SheetMotion();
    private final SheetPainter.Paint paint = new SheetPainter.Paint();
    private final SheetLayout layout = new SheetLayout(canvas, values, state);
    private final KeyHold keyHold = new KeyHold();

    private SheetLayout.Frame frame;
    private float unit = 2f, originX;
    private long openedAt = -1, closingAt = -1, lastFrame;
    private int heldButton = -1;
    private GuiTheme.Accent accent;
    private String toast;
    private long toastAt;

    public SettingsGui() {
    }

    /** Opens on the HUD page with the module for Edit HUD module {@code hudId} open. */
    public static SettingsGui forHudModule(String hudId) {
        SettingsGui gui = new SettingsGui();
        String id = SettingsBindings.moduleForHud(hudId);
        if (id != null) {
            gui.state.category = "hud";
            gui.state.query = "";
            gui.state.open.add(id);
            gui.state.scroll = gui.state.scrollShown = 0f;
        }
        return gui;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (openedAt < 0) {
            openedAt = System.currentTimeMillis();
            keyHold.open(Keyboard::isKeyDown, SettingsBindings.settingsKey(), Keyboard.KEY_ESCAPE);
        }
    }

    /** Runs right after {@code handleInput} has taken every queued event, so the polled keys match the last of them. */
    @Override
    public void updateScreen() {
        keyHold.tick(Keyboard::isKeyDown);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        state.capture = null;
        state.confirm = null;
        values.save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long now = System.currentTimeMillis();
        int sf = new ScaledResolution(mc).getScaleFactor();
        boolean minecraftFont = SettingsBindings.settings().minecraftFont();
        unit = SheetLayout.unit(sf, SettingsBindings.settings().guiSize, minecraftFont);
        float sheetW = SheetLayout.WIDTH * unit;
        float hidden = sheetW + 2f * unit;
        float slide;
        if (closingAt >= 0) slide = SheetMotion.SLIDE_OUT.at((now - closingAt) / (double) SheetMotion.CLOSE_MS) * hidden;
        else slide = (1f - SheetMotion.SLIDE_IN.at((now - openedAt) / (double) SheetMotion.OPEN_MS)) * hidden;
        originX = mc.displayWidth - sheetW + Math.round(slide);
        if (mc.theWorld == null) drawDefaultBackground(); // the sheet leaves the world untouched, but there may be none

        canvas.begin(unit, originX, 0f, mc.displayHeight, sf, minecraftFont);
        frame = layout.layout(mc.displayHeight / unit, "v" + BedwarsQol.VERSION);
        controller.frame(frame);
        advanceScroll(now);
        GuiTheme.Accent a = GuiTheme.fromToken(SettingsBindings.settings().guiAccent);
        if (a != accent) {
            accent = a;
            paint.accent = SheetColors.Shades.of(a.base() & 0xFFFFFF);
        }
        paint.values = values;
        paint.motion = motion;
        paint.now = now;
        paint.scroll = Math.round(state.scrollShown * unit) / unit;
        float[] m = mouseSheet();
        boolean holding = heldButton >= 0;
        paint.hover = closingAt >= 0 ? null : SheetLayout.hit(frame, m[0], m[1], paint.scroll);
        paint.pressed = SheetController.find(frame, controller.pressed());
        if (holding && paint.pressed != null && paint.pressed.type != SheetLayout.T.SLIDER
                && !SheetController.key(paint.pressed).equals(SheetController.key(paint.hover))) {
            paint.pressed = null; // :active ends while the pointer is off the control
        }
        paint.focus = SheetController.find(frame, controller.focus());
        paint.searchFocused = controller.searchFocused();
        paint.editing = controller.editing();
        paint.editText = controller.editText();
        paint.caretOn = (now / 300L) % 2L == 0L; // vanilla's text-field blink: 6 ticks on, 6 off
        SheetPainter.paint(canvas, frame, paint);
        canvas.end();
        drawToast(now, sf);
        lastFrame = now;
        if (closingAt >= 0 && now - closingAt >= SheetMotion.CLOSE_MS) mc.displayGuiScreen(null);
    }

    /** The eased scroll, as today's menu eases it (exponential, tau 0.1 s), snapped to device px when drawn. */
    private void advanceScroll(long now) {
        float max = frame.maxScroll();
        if (state.scroll > max) state.scroll = max;
        if (state.scroll < 0f) state.scroll = 0f;
        float dt = lastFrame == 0L ? 1f : Math.min(0.1f, (now - lastFrame) / 1000f);
        state.scrollShown += (state.scroll - state.scrollShown) * (1f - (float) Math.exp(-dt / SCROLL_TAU));
        if (Math.abs(state.scroll - state.scrollShown) * unit < 0.5f) state.scrollShown = state.scroll;
    }

    /** The cursor in sheet units. */
    private float[] mouseSheet() {
        float x = Mouse.getX() + 0.5f, y = mc.displayHeight - Mouse.getY() - 0.5f;
        return new float[]{(x - originX) / unit, y / unit};
    }

    /** Top centre of the screen, in screen GUI px (not sheet units): fades in 120 ms, stays 2.2 s. */
    private void drawToast(long now, int sf) {
        if (toast == null) return;
        long t = now - toastAt;
        if (t > SheetMotion.TOAST_SHOW_MS + SheetMotion.TOAST_FADE_MS) {
            toast = null;
            return;
        }
        float in = Math.min(1f, t / (float) SheetMotion.TOAST_FADE_MS);
        float out = t <= SheetMotion.TOAST_SHOW_MS ? 1f
                : 1f - Math.min(1f, (t - SheetMotion.TOAST_SHOW_MS) / (float) SheetMotion.TOAST_FADE_MS);
        float fade = SheetMotion.EASE.at(Math.min(in, out));
        canvas.begin(sf, 0f, 0f, mc.displayHeight, sf, SettingsBindings.settings().minecraftFont());
        float tw = canvas.width(toast, SheetFont.TOAST);
        float w = tw + 16f + 2f, h = 1f + 5f + 9f + 6f + 1f;
        float x = (mc.displayWidth / (float) sf - w) / 2f, y = 8f - 4f * (1f - fade);
        canvas.pushAlpha(fade);
        canvas.fill(x, y, w, h, SheetColors.BLACK);
        canvas.fill(x + 1f, y + 1f, w - 2f, h - 2f, SheetColors.BAND);
        canvas.fill(x + 1f, y + 1f, w - 2f, 1f, SheetColors.GROOVE);
        canvas.text(toast, x + 1f + 8f, y + 1f + 5f, 9f, SheetFont.TOAST, SheetColors.TEXT_HI);
        canvas.popAlpha();
        canvas.end();
    }

    // ------------------------------------------------------------------ input

    @Override
    public void handleMouseInput() {
        if (frame == null || closingAt >= 0) return;
        float x = Mouse.getEventX() + 0.5f, y = mc.displayHeight - Mouse.getEventY() - 0.5f;
        float sx = (x - originX) / unit, sy = y / unit;
        int button = Mouse.getEventButton();
        if (button >= 0) {
            SheetLayout.Node n = SheetLayout.hit(frame, sx, sy, paint.scroll);
            if (Mouse.getEventButtonState()) {
                heldButton = button;
                controller.pointerDown(n, sx, button);
            } else if (button == heldButton) {
                heldButton = -1;
                if (button == 0) controller.pointerUp(n);
            }
        } else if (heldButton == 0) {
            controller.pointerDrag(sx);
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && sx >= 0f) controller.wheel(-wheel / 120f);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        boolean held = !keyHold.press(keyCode);
        if (closingAt >= 0) return;
        boolean alt = Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
        controller.key(typedChar, keyCode, isCtrlKeyDown(), isShiftKeyDown(), alt, SettingsBindings.settingsKey(), held);
    }

    // ------------------------------------------------------------------ host

    @Override
    public void click() {
        mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    @Override
    public void close() {
        if (closingAt < 0) closingAt = System.currentTimeMillis();
    }

    @Override
    public void editHud() {
        values.save();
        mc.displayGuiScreen(new EditHudGui());
    }

    @Override
    public void toast(String message) {
        toast = message;
        toastAt = System.currentTimeMillis();
    }

    @Override
    public void setKey(String id, int binding) {
        values.setKey(id, binding);
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }
}
