package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.gui.render.GuiRender;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.gui.render.Theme;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.BedwarsHudRenderer.HudBox;
import com.bedwarsqol.hud.CornerResize;
import com.bedwarsqol.hud.HudModuleState;
import com.bedwarsqol.hud.HudModules;
import com.bedwarsqol.hud.HudSnapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Edit HUD: move, resize, show and hide the HUD modules in place, over the live game.
 *
 * <p>The world stays sharp and the vanilla HUD keeps drawing, so modules are placed against the real
 * hotbar, chat and scoreboard. Every module has a faint frame; hovering shows its name and corner
 * handles, and the selected one is framed in the menu accent. Hidden modules show as dashed ghosts
 * that can be placed and switched on. Every change can be undone.
 */
public class EditHudGui extends GuiScreen {

    private static final int DIM = 0x26000000;              // a light dim, so the editor chrome reads over the world
    private static final int FRAME = 0x40FFFFFF;            // every module's hairline frame
    private static final int FRAME_FILL = 0x0DFFFFFF;
    private static final int FRAME_HOVER = 0xB3FFFFFF;
    private static final int GHOST = 0x59FFFFFF;            // hidden modules: dashed, about 35%
    private static final int GHOST_FILL = 0x0AFFFFFF;
    private static final int GHOST_TEXT = 0x99FFFFFF;
    private static final int PANEL = 0xD0121212;           // the HUD panel fill, for labels, buttons and menus
    private static final int HELP_PANEL = 0xE6121212;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFAAAAAA;
    private static final float HANDLE = 3f;                 // corner handle side, GUI px
    private static final float HANDLE_REACH = 4f;           // how far from a corner a press still grabs it
    private static final float SNAP_DEVICE_PX = 8f;         // snap threshold in real pixels, the same at every GUI scale
    private static final long OPEN_FADE_MS = 160L;
    private static final long GUIDE_FADE_MS = 200L;
    private static final long NUDGE_GROUP_MS = 800L;        // arrow presses this close together undo as one step
    private static final long SAVE_DELAY_MS = 400L;
    private static final float LABEL_SCALE = 0.8f;
    private static final float BUTTON_SCALE = 1.0f;
    private static final float BUTTON_H = 18f;              // a Keystrokes cap's height
    private static final float MENU_SCALE = 0.75f;
    private static final float HELP_SCALE = 1.0f;
    private static final float HOTBAR_W = 182f;             // the vanilla hotbar, a snap target
    private static final float HOTBAR_H = 22f;

    private static final String RESET_ALL = "Reset All";
    private static final String HELP = "Help";
    private static final String SHOW = "Show";
    private static final String HIDE = "Hide";
    private static final String RESET_POSITION = "Reset Position";
    private static final String RESET_SIZE = "Reset Size";
    private static final String OPEN_SETTINGS = "Settings";

    private enum Mode { NONE, DRAG, RESIZE }

    private final EditHistory<Map<String, HudModuleState>> history = new EditHistory<Map<String, HudModuleState>>(100);
    private final Map<String, HoverFade> hoverFades = new HashMap<String, HoverFade>();
    private final HoverFade resetHover = new HoverFade(120L, 180L);
    private final HoverFade helpHover = new HoverFade(120L, 180L);
    private final HoverFade toolbarFade = new HoverFade(120L, 240L);

    private String selectedId;
    private String hoverId;
    private Mode mode = Mode.NONE;
    private Map<String, HudModuleState> before;   // the layout when the current drag or resize began
    private float grabX;
    private float grabY;
    private CornerResize resize;
    private boolean moved;
    private boolean wasSelectedBeforeClick;
    private float clickX;
    private float clickY;
    private long lastNudgeMs;
    private long openedMs;
    private long dirtySinceMs;

    // The last snap, kept after release so its guides can fade out.
    private HudSnapper.Snap snapX;
    private HudSnapper.Snap snapY;
    private long guidesReleasedMs;

    private String menuId;          // the module the right-click menu is for; null when closed
    private float menuX;
    private float menuY;
    private boolean helpOpen;
    private float badgeX;
    private float badgeY;

    @Override
    public void initGui() {
        buttonList.clear();
        Keyboard.enableRepeatEvents(true);
        if (openedMs == 0L) openedMs = Minecraft.getSystemTime();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        settings().save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void updateScreen() {
        if (dirtySinceMs != 0L && Minecraft.getSystemTime() - dirtySinceMs >= SAVE_DELAY_MS) {
            settings().save();
            dirtySinceMs = 0L;
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long now = Minecraft.getSystemTime();
        ClientSettings cfg = settings();
        int accent = GuiTheme.fromToken(cfg.guiAccent).base();
        float open = Math.min(1f, (now - openedMs) / (float) OPEN_FADE_MS);

        GuiRender.rect(0, 0, width, height, fade(DIM, open));

        List<HudBox> boxes = BedwarsHudRenderer.getEditorBoxes(mc, cfg);
        hoverId = mode == Mode.NONE && menuId == null && !helpOpen ? topmostAt(boxes, mouseX, mouseY) : null;
        Map<String, Float> hover = new HashMap<String, Float>();
        for (HudBox box : boxes) hover.put(box.id, hoverFade(box.id).update(box.id.equals(hoverId), now));

        // Hidden modules first, under everything else.
        for (HudBox box : boxes) {
            if (!box.shown) drawGhost(box, box.id.equals(selectedId) ? accent : GHOST, open);
        }

        BedwarsHudRenderer.renderEditPreview(mc, cfg);
        drawGuides(now, accent, open);

        for (HudBox box : boxes) {
            boolean selected = box.id.equals(selectedId);
            float h = hover.get(box.id);
            if (box.shown) drawFrame(box, h, selected, accent, open);
            if (selected || h > 0f) drawHandles(box, selected ? 1f : h, selected ? accent : TEXT, open);
        }
        // Name labels last, so no frame covers them.
        for (HudBox box : boxes) {
            boolean active = box.id.equals(selectedId) && mode != Mode.NONE;
            float level = Math.max(hover.get(box.id), active ? 1f : 0f);
            if (level > 0f) drawNameLabel(box, level * open);
        }

        if (mode == Mode.RESIZE && selectedId != null) drawResizeBadge(cfg, open);
        if (helpOpen) {
            drawHelp();
        } else {
            drawToolbar(mouseX, mouseY, now, accent, open);
            if (menuId != null) drawMenu(mouseX, mouseY, accent);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawFrame(HudBox box, float hover, boolean selected, int accent, float open) {
        GuiRender.rect(box.visualX(), box.visualY(), box.visualRight(), box.visualBottom(), fade(FRAME_FILL, open));
        int color = selected || isSnapTarget(box.id) ? accent : GuiRender.lerpColor(FRAME, FRAME_HOVER, hover);
        GuiRender.border(box.visualX(), box.visualY(), box.visualRight(), box.visualBottom(), fade(color, open), 1f);
    }

    private void drawGhost(HudBox box, int outline, float open) {
        float x1 = box.visualX(), y1 = box.visualY(), x2 = box.visualRight(), y2 = box.visualBottom();
        GuiRender.rect(x1, y1, x2, y2, fade(GHOST_FILL, open));
        dashedBorder(x1, y1, x2, y2, fade(outline, open));
        String name = HudModules.title(box.id);
        float fit = (x2 - x1 - 4f) / Math.max(1f, GuiRender.textWidth(name, 1f, BedwarsQolFont.Weight.BOLD));
        float scale = Math.min(LABEL_SCALE, fit);
        if (scale < 0.35f) return;
        GuiRender.textCentered(name, (x1 + x2) / 2f, (y1 + y2) / 2f - BedwarsQolFont.height(scale) / 2f, scale,
                fade(GHOST_TEXT, open), BedwarsQolFont.Weight.BOLD);
    }

    /** Small squares tucked inside the corners, showing where a resize can start. */
    private static void drawHandles(HudBox box, float level, int color, float open) {
        int c = fade(color, level * open);
        float x1 = box.visualX(), y1 = box.visualY(), x2 = box.visualRight(), y2 = box.visualBottom();
        GuiRender.rect(x1, y1, x1 + HANDLE, y1 + HANDLE, c);
        GuiRender.rect(x2 - HANDLE, y1, x2, y1 + HANDLE, c);
        GuiRender.rect(x1, y2 - HANDLE, x1 + HANDLE, y2, c);
        GuiRender.rect(x2 - HANDLE, y2 - HANDLE, x2, y2, c);
    }

    private void drawNameLabel(HudBox box, float level) {
        String name = HudModules.title(box.id) + (box.shown ? "" : " (hidden)");
        float w = GuiRender.textWidth(name, LABEL_SCALE, BedwarsQolFont.Weight.BOLD) + 6f;
        float h = BedwarsQolFont.height(LABEL_SCALE) + 4f;
        float x = clamp(box.visualX(), 1f, width - w - 1f);
        float y = box.visualY() - h - 2f;
        if (y < 1f) y = box.visualBottom() + 2f;
        GuiRender.roundedRect(x, y, x + w, y + h, Theme.CARD_R, fade(PANEL, level));
        GuiRender.text(name, x + 3f, y + 2f, LABEL_SCALE, fade(TEXT, level), BedwarsQolFont.Weight.BOLD);
    }

    /** Accent guides for the current snap: between the two matched boxes, or across the screen. */
    private void drawGuides(long now, int accent, float open) {
        float level = 1f;
        if (mode == Mode.NONE) {
            if (guidesReleasedMs == 0L) return;
            level = 1f - (now - guidesReleasedMs) / (float) GUIDE_FADE_MS;
            if (level <= 0f) {
                snapX = snapY = null;
                guidesReleasedMs = 0L;
                return;
            }
        }
        HudBox moving = selectedBox();
        if (moving == null) return;
        int color = fade(accent, level * open);
        if (snapX != null && snapX.snapped) {
            float top = 0f, bottom = height;
            if (snapX.target != null) {
                top = Math.min(moving.visualY(), snapX.target.y);
                bottom = Math.max(moving.visualBottom(), snapX.target.y + snapX.target.height);
            }
            GuiRender.rect(snapX.line - 0.5f, top, snapX.line + 0.5f, bottom, color);
        }
        if (snapY != null && snapY.snapped) {
            float left = 0f, right = width;
            if (snapY.target != null) {
                left = Math.min(moving.visualX(), snapY.target.x);
                right = Math.max(moving.visualRight(), snapY.target.x + snapY.target.width);
            }
            GuiRender.rect(left, snapY.line - 0.5f, right, snapY.line + 0.5f, color);
        }
    }

    private boolean isSnapTarget(String id) {
        if (mode == Mode.NONE && guidesReleasedMs == 0L) return false;
        return matches(snapX, id) || matches(snapY, id);
    }

    private static boolean matches(HudSnapper.Snap snap, String id) {
        return snap != null && snap.snapped && snap.target != null && id.equals(snap.target.id);
    }

    private void drawResizeBadge(ClientSettings cfg, float open) {
        HudModuleState s = HudModules.get(cfg, selectedId);
        if (s == null) return;
        String text = String.format("%.2fx", s.scale);
        float w = GuiRender.textWidth(text, MENU_SCALE, BedwarsQolFont.Weight.BOLD) + 8f;
        float h = BedwarsQolFont.height(MENU_SCALE) + 5f;
        float x = clamp(badgeX + 8f, 1f, width - w - 1f);
        float y = clamp(badgeY + 8f, 1f, height - h - 1f);
        GuiRender.roundedRect(x, y, x + w, y + h, Theme.CARD_R, fade(PANEL, open));
        GuiRender.text(text, x + 4f, y + 2.5f, MENU_SCALE, fade(TEXT, open), BedwarsQolFont.Weight.BOLD);
    }

    /** "Reset All" and "Help" just under the crosshair, a spot no HUD module or vanilla bar uses. */
    private void drawToolbar(int mouseX, int mouseY, long now, int accent, float open) {
        float[][] r = toolbarRects();
        boolean busy = mode != Mode.NONE || menuId != null;
        float alpha = (1f - 0.75f * toolbarFade.update(busy || toolbarCovered(), now)) * open;
        boolean usable = !busy;
        drawButton(r[0], RESET_ALL, resetHover.update(usable && inside(r[0], mouseX, mouseY), now), accent, alpha);
        drawButton(r[1], HELP, helpHover.update(usable && inside(r[1], mouseX, mouseY), now), accent, alpha);
    }

    private static void drawButton(float[] r, String label, float hover, int accent, float alpha) {
        GuiRender.roundedRect(r[0], r[1], r[2], r[3], Theme.CARD_R, fade(GuiRender.lerpColor(PANEL, accent, hover), alpha));
        GuiRender.textCentered(label, (r[0] + r[2]) / 2f, r[1] + (r[3] - r[1] - BedwarsQolFont.height(BUTTON_SCALE)) / 2f,
                BUTTON_SCALE, fade(TEXT, alpha), BedwarsQolFont.Weight.BOLD);
    }

    private void drawMenu(int mouseX, int mouseY, int accent) {
        List<String> items = menuItems();
        float[] r = menuRect(items);
        float rowH = menuRowHeight();
        GuiRender.roundedRect(r[0], r[1], r[2], r[3], Theme.CARD_R, PANEL);
        GuiRender.text(HudModules.title(menuId), r[0] + 5f, r[1] + (rowH - BedwarsQolFont.height(MENU_SCALE)) / 2f,
                MENU_SCALE, TEXT_DIM, BedwarsQolFont.Weight.BOLD);
        for (int i = 0; i < items.size(); i++) {
            float y = r[1] + rowH * (i + 1);
            if (GuiRender.inside(mouseX, mouseY, r[0], y, r[2], y + rowH)) GuiRender.rect(r[0], y, r[2], y + rowH, accent);
            GuiRender.text(items.get(i), r[0] + 5f, y + (rowH - BedwarsQolFont.height(MENU_SCALE)) / 2f, MENU_SCALE,
                    TEXT, BedwarsQolFont.Weight.BOLD);
        }
    }

    private void drawHelp() {
        String mod = Minecraft.isRunningOnMac ? "Cmd" : "Ctrl";
        String alt = Minecraft.isRunningOnMac ? "Option" : "Alt";
        String[][] rows = {
                {"Drag", "Move a module"},
                {"Drag a corner", "Resize"},
                {"Right-click", "Reset, settings, show or hide"},
                {mod + "+Z / " + mod + "+Y", "Undo / redo"},
                {"Arrows", "Nudge (Shift: 10 px)"},
                {"Hold " + alt, "Don't snap"},
                {"Tab", "Next module"},
                {"Click again", "Pick the module underneath"},
                {"Delete", "Hide the selected module"},
                {"Esc", "Back"},
        };
        float keyW = 0f, descW = 0f;
        for (String[] row : rows) {
            keyW = Math.max(keyW, GuiRender.textWidth(row[0], HELP_SCALE, BedwarsQolFont.Weight.BOLD));
            descW = Math.max(descW, GuiRender.textWidth(row[1], HELP_SCALE, BedwarsQolFont.Weight.REGULAR));
        }
        float pad = 12f;
        float rowH = BedwarsQolFont.height(HELP_SCALE) + 5f;
        float w = keyW + descW + 3f * pad;
        float h = rows.length * rowH + 2f * pad - 5f;
        float x = (width - w) / 2f, y = (height - h) / 2f;
        GuiRender.roundedRect(x, y, x + w, y + h, Theme.CARD_R, HELP_PANEL);
        for (int i = 0; i < rows.length; i++) {
            float ry = y + pad + i * rowH;
            GuiRender.text(rows[i][0], x + pad, ry, HELP_SCALE, TEXT, BedwarsQolFont.Weight.BOLD);
            GuiRender.text(rows[i][1], x + 2f * pad + keyW, ry, HELP_SCALE, TEXT_DIM, BedwarsQolFont.Weight.REGULAR);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (helpOpen) {
            helpOpen = false;
            return;
        }
        if (menuId != null) {
            if (mouseButton == 0) clickMenu(mouseX, mouseY);
            menuId = null;
            return;
        }
        ClientSettings cfg = settings();
        List<HudBox> boxes = BedwarsHudRenderer.getEditorBoxes(mc, cfg);

        if (mouseButton == 1) {
            String id = topmostAt(boxes, mouseX, mouseY);
            if (id != null) {
                selectedId = id;
                menuId = id;
                menuX = mouseX;
                menuY = mouseY;
            }
            return;
        }
        if (mouseButton != 0) return;

        float[][] toolbar = toolbarRects();
        if (!toolbarCovered()) {
            if (inside(toolbar[0], mouseX, mouseY)) {
                playClick();
                resetAll();
                return;
            }
            if (inside(toolbar[1], mouseX, mouseY)) {
                playClick();
                helpOpen = true;
                return;
            }
        }

        // A corner of the selected or hovered module starts a resize.
        HudBox grabbed = cornerTarget(boxes, mouseX, mouseY);
        if (grabbed != null) {
            int corner = CornerResize.cornerAt(mouseX, mouseY, grabbed.visualX(), grabbed.visualY(),
                    grabbed.visualWidth(), grabbed.visualHeight(), HANDLE_REACH);
            selectedId = grabbed.id;
            resize = new CornerResize(corner, grabbed.x, grabbed.y, grabbed.width, grabbed.height,
                    HudModules.get(cfg, grabbed.id).scale);
            badgeX = mouseX;
            badgeY = mouseY;
            beginChange(Mode.RESIZE);
            return;
        }

        clickX = mouseX;
        clickY = mouseY;
        List<String> stack = stackAt(boxes, mouseX, mouseY);
        if (stack.isEmpty()) {
            selectedId = null;
            return;
        }
        wasSelectedBeforeClick = stack.contains(selectedId);
        if (!wasSelectedBeforeClick) selectedId = stack.get(0);
        HudBox box = boxById(boxes, selectedId);
        grabX = mouseX - box.x;
        grabY = mouseY - box.y;
        beginChange(Mode.DRAG);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (clickedMouseButton != 0 || selectedId == null) return;
        if (mode == Mode.DRAG) {
            if (!moved && Math.abs(mouseX - clickX) < 1f && Math.abs(mouseY - clickY) < 1f) return;
            moved = true;
            drag(mouseX - grabX, mouseY - grabY);
        } else if (mode == Mode.RESIZE) {
            moved = true;
            badgeX = mouseX;
            badgeY = mouseY;
            resizeTo(mouseX, mouseY);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (mode != Mode.NONE) endChange();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            escape();
            return;
        }
        if (helpOpen || menuId != null) {
            helpOpen = false;
            menuId = null;
            return;
        }
        if (typedChar == '?') {
            helpOpen = true;
            return;
        }
        if (isCtrlKeyDown() && (keyCode == Keyboard.KEY_Z || keyCode == Keyboard.KEY_Y)) {
            if (keyCode == Keyboard.KEY_Y || isShiftKeyDown()) redo();
            else undo();
            return;
        }
        if (mode != Mode.NONE) return;
        if (keyCode == Keyboard.KEY_TAB) {
            cycleSelection(isShiftKeyDown() ? -1 : 1);
            return;
        }
        if (selectedId == null) return;
        int step = isShiftKeyDown() ? 10 : 1;
        if (keyCode == Keyboard.KEY_UP) nudge(0, -step);
        else if (keyCode == Keyboard.KEY_DOWN) nudge(0, step);
        else if (keyCode == Keyboard.KEY_LEFT) nudge(-step, 0);
        else if (keyCode == Keyboard.KEY_RIGHT) nudge(step, 0);
        else if (keyCode == Keyboard.KEY_DELETE || keyCode == Keyboard.KEY_BACK) setShown(selectedId, false);
    }

    /** Esc backs out one step: menu or help, then a drag in progress, then the selection, then the editor. */
    private void escape() {
        if (helpOpen || menuId != null) {
            helpOpen = false;
            menuId = null;
        } else if (mode != Mode.NONE) {
            HudModules.restore(settings(), before);
            mode = Mode.NONE;
            resize = null;
            snapX = snapY = null;
        } else if (selectedId != null) {
            selectedId = null;
        } else {
            settings().save();
            mc.displayGuiScreen(new SettingsGui());
        }
    }

    // ------------------------------------------------------------------ editing

    private void beginChange(Mode next) {
        mode = next;
        moved = false;
        before = HudModules.snapshot(settings());
        snapX = snapY = null;
        guidesReleasedMs = 0L;
    }

    private void endChange() {
        // A click without a drag on the module that was already selected picks the next one under it.
        if (mode == Mode.DRAG && !moved && wasSelectedBeforeClick) cycleUnderneath();
        if (!HudModules.snapshot(settings()).equals(before)) {
            history.record(before);
            markDirty();
        }
        mode = Mode.NONE;
        resize = null;
        guidesReleasedMs = Minecraft.getSystemTime();
    }

    private void drag(float rawX, float rawY) {
        ClientSettings cfg = settings();
        List<HudBox> boxes = BedwarsHudRenderer.getEditorBoxes(mc, cfg);
        HudBox box = boxById(boxes, selectedId);
        if (box == null) return;
        float vx = rawX - box.pad, vy = rawY - box.pad;
        if (snapping()) {
            List<HudSnapper.Target> targets = snapTargets(boxes, box.id);
            snapX = HudSnapper.snap(vx, box.visualWidth(), true, width, targets, snapThreshold());
            snapY = HudSnapper.snap(vy, box.visualHeight(), false, height, targets, snapThreshold());
            vx = snapX.position;
            vy = snapY.position;
        } else {
            snapX = snapY = null;
        }
        place(cfg, box, vx + box.pad, vy + box.pad);
    }

    private void resizeTo(int mouseX, int mouseY) {
        ClientSettings cfg = settings();
        HudModuleState s = HudModules.get(cfg, selectedId);
        HudBox box = selectedBox();
        if (s == null || box == null || resize == null) return;
        float scale = resize.scaleFor(mouseX, mouseY, width, height, box.pad, snapping());
        HudModules.set(cfg, selectedId, s.withScale(scale));
        // Measure at the new scale, then keep the opposite corner where it was.
        HudBox scaled = selectedBox();
        if (scaled == null) return;
        BedwarsHudRenderer.setHudAbsolutePosition(cfg, selectedId, resize.left(scale), resize.top(scale),
                scaled.width, scaled.height, width, height);
    }

    private void nudge(int dx, int dy) {
        ClientSettings cfg = settings();
        HudBox box = selectedBox();
        if (box == null) return;
        long now = Minecraft.getSystemTime();
        if (now - lastNudgeMs > NUDGE_GROUP_MS) history.record(HudModules.snapshot(cfg));
        lastNudgeMs = now;
        place(cfg, box, box.x + dx, box.y + dy);
        markDirty();
    }

    /** Moves {@code box}'s content to (x, y), kept on screen with its panel padding. */
    private void place(ClientSettings cfg, HudBox box, float x, float y) {
        BedwarsHudRenderer.setHudAbsolutePosition(cfg, box.id,
                clamp(x, box.pad, width - box.width - box.pad),
                clamp(y, box.pad, height - box.height - box.pad),
                box.width, box.height, width, height);
    }

    private void undo() {
        ClientSettings cfg = settings();
        Map<String, HudModuleState> previous = history.undo(HudModules.snapshot(cfg));
        if (previous == null) return;
        HudModules.restore(cfg, previous);
        lastNudgeMs = 0L;
        markDirty();
    }

    private void redo() {
        ClientSettings cfg = settings();
        Map<String, HudModuleState> next = history.redo(HudModules.snapshot(cfg));
        if (next == null) return;
        HudModules.restore(cfg, next);
        lastNudgeMs = 0L;
        markDirty();
    }

    /** Runs {@code change} on the layout as one undoable step. */
    private void edit(Runnable change) {
        ClientSettings cfg = settings();
        Map<String, HudModuleState> previous = HudModules.snapshot(cfg);
        change.run();
        if (!HudModules.snapshot(cfg).equals(previous)) {
            history.record(previous);
            lastNudgeMs = 0L;
            markDirty();
        }
    }

    private void resetAll() {
        edit(new Runnable() {
            @Override
            public void run() {
                ClientSettings cfg = settings();
                for (String id : HudModules.ids()) HudModules.set(cfg, id, HudModules.defaults(cfg, id));
            }
        });
    }

    private void resetPosition(final String id) {
        edit(new Runnable() {
            @Override
            public void run() {
                ClientSettings cfg = settings();
                HudModuleState d = HudModules.defaults(cfg, id);
                HudModules.set(cfg, id, HudModules.get(cfg, id).withPosition(d.x, d.y, d.anchor));
            }
        });
    }

    /** Back to the HUD Size default, keeping the module's top-left corner where it is. */
    private void resetSize(final String id) {
        edit(new Runnable() {
            @Override
            public void run() {
                ClientSettings cfg = settings();
                HudBox old = boxById(BedwarsHudRenderer.getEditorBoxes(mc, cfg), id);
                HudModules.set(cfg, id, HudModules.get(cfg, id).withScale(cfg.defaultTextSizeScale()));
                HudBox scaled = boxById(BedwarsHudRenderer.getEditorBoxes(mc, cfg), id);
                if (old != null && scaled != null) place(cfg, scaled, old.x, old.y);
            }
        });
    }

    private void setShown(final String id, final boolean shown) {
        edit(new Runnable() {
            @Override
            public void run() {
                ClientSettings cfg = settings();
                HudModules.set(cfg, id, HudModules.get(cfg, id).withEnabled(shown));
            }
        });
    }

    private void cycleSelection(int dir) {
        List<String> ids = HudModules.ids();
        int i = selectedId == null ? (dir > 0 ? -1 : 0) : ids.indexOf(selectedId);
        selectedId = ids.get(Math.floorMod(i + dir, ids.size()));
    }

    private void cycleUnderneath() {
        List<String> stack = stackAt(BedwarsHudRenderer.getEditorBoxes(mc, settings()), clickX, clickY);
        int i = stack.indexOf(selectedId);
        if (stack.size() > 1 && i >= 0) selectedId = stack.get((i + 1) % stack.size());
    }

    // ------------------------------------------------------------------ right-click menu

    private List<String> menuItems() {
        HudModuleState s = HudModules.get(settings(), menuId);
        boolean shown = s != null && s.enabled;
        List<String> items = new ArrayList<String>();
        if (!shown) items.add(SHOW);
        items.add(RESET_POSITION);
        items.add(RESET_SIZE);
        items.add(OPEN_SETTINGS);
        if (shown) items.add(HIDE);
        return items;
    }

    private static float menuRowHeight() {
        return BedwarsQolFont.height(MENU_SCALE) + 6f;
    }

    private float[] menuRect(List<String> items) {
        float w = GuiRender.textWidth(HudModules.title(menuId), MENU_SCALE, BedwarsQolFont.Weight.BOLD);
        for (String item : items) w = Math.max(w, GuiRender.textWidth(item, MENU_SCALE, BedwarsQolFont.Weight.BOLD));
        w += 12f;
        float h = menuRowHeight() * (items.size() + 1);
        float x = clamp(menuX, 1f, width - w - 1f);
        float y = clamp(menuY, 1f, height - h - 1f);
        return new float[]{x, y, x + w, y + h};
    }

    private void clickMenu(int mouseX, int mouseY) {
        List<String> items = menuItems();
        float[] r = menuRect(items);
        float rowH = menuRowHeight();
        for (int i = 0; i < items.size(); i++) {
            float y = r[1] + rowH * (i + 1);
            if (!GuiRender.inside(mouseX, mouseY, r[0], y, r[2], y + rowH)) continue;
            String item = items.get(i);
            String id = menuId;
            playClick();
            if (SHOW.equals(item)) setShown(id, true);
            else if (HIDE.equals(item)) setShown(id, false);
            else if (RESET_POSITION.equals(item)) resetPosition(id);
            else if (RESET_SIZE.equals(item)) resetSize(id);
            else if (OPEN_SETTINGS.equals(item)) {
                settings().save();
                mc.displayGuiScreen(SettingsGui.forHudModule(id));
            }
            return;
        }
    }

    // ------------------------------------------------------------------ geometry

    private List<HudSnapper.Target> snapTargets(List<HudBox> boxes, String movingId) {
        List<HudSnapper.Target> targets = new ArrayList<HudSnapper.Target>();
        for (HudBox b : boxes) {
            if (b.id.equals(movingId) || !b.shown) continue;
            targets.add(new HudSnapper.Target(b.id, b.visualX(), b.visualY(), b.visualWidth(), b.visualHeight()));
        }
        targets.add(new HudSnapper.Target(null, (width - HOTBAR_W) / 2f, height - HOTBAR_H, HOTBAR_W, HOTBAR_H));
        return targets;
    }

    private float snapThreshold() {
        return SNAP_DEVICE_PX / new ScaledResolution(mc).getScaleFactor();
    }

    private static boolean snapping() {
        return !Keyboard.isKeyDown(Keyboard.KEY_LMENU) && !Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }

    /** The selected or hovered module with a corner under the mouse, the selected one first. */
    private HudBox cornerTarget(List<HudBox> boxes, int mouseX, int mouseY) {
        String[] ids = {selectedId, hoverId};
        for (String id : ids) {
            HudBox box = boxById(boxes, id);
            if (box != null && CornerResize.cornerAt(mouseX, mouseY, box.visualX(), box.visualY(),
                    box.visualWidth(), box.visualHeight(), HANDLE_REACH) >= 0) return box;
        }
        return null;
    }

    /** Ids of the modules under a point, topmost first; shown modules sit over hidden ones. */
    private static List<String> stackAt(List<HudBox> boxes, float x, float y) {
        List<String> shown = new ArrayList<String>();
        List<String> hidden = new ArrayList<String>();
        for (HudBox box : boxes) {
            if (box.containsVisual(x, y)) (box.shown ? shown : hidden).add(box.id);
        }
        Collections.reverse(shown);
        Collections.reverse(hidden);
        shown.addAll(hidden);
        return shown;
    }

    private static String topmostAt(List<HudBox> boxes, float x, float y) {
        List<String> stack = stackAt(boxes, x, y);
        return stack.isEmpty() ? null : stack.get(0);
    }

    private float[][] toolbarRects() {
        float resetW = GuiRender.textWidth(RESET_ALL, BUTTON_SCALE, BedwarsQolFont.Weight.BOLD) + 16f;
        float helpW = GuiRender.textWidth(HELP, BUTTON_SCALE, BedwarsQolFont.Weight.BOLD) + 16f;
        float total = resetW + 4f + helpW;
        float x = (width - total) / 2f;
        float y = height / 2f + 20f;
        return new float[][]{{x, y, x + resetW, y + BUTTON_H}, {x + resetW + 4f, y, x + total, y + BUTTON_H}};
    }

    /** Whether a module sits over the toolbar, which then fades and stops taking clicks. */
    private boolean toolbarCovered() {
        float[][] r = toolbarRects();
        for (HudBox box : BedwarsHudRenderer.getEditorBoxes(mc, settings())) {
            if (box.visualX() < r[1][2] && box.visualRight() > r[0][0]
                    && box.visualY() < r[0][3] && box.visualBottom() > r[0][1]) return true;
        }
        return false;
    }

    private static boolean inside(float[] r, int x, int y) {
        return GuiRender.inside(x, y, r[0], r[1], r[2], r[3]);
    }

    private HudBox selectedBox() {
        return boxById(BedwarsHudRenderer.getEditorBoxes(mc, settings()), selectedId);
    }

    private static HudBox boxById(List<HudBox> boxes, String id) {
        if (id == null) return null;
        for (HudBox box : boxes) {
            if (id.equals(box.id)) return box;
        }
        return null;
    }

    private HoverFade hoverFade(String id) {
        HoverFade f = hoverFades.get(id);
        if (f == null) {
            f = new HoverFade(100L, 160L);
            hoverFades.put(id, f);
        }
        return f;
    }

    private static void dashedBorder(float x1, float y1, float x2, float y2, int color) {
        float dash = 3f, gap = 2f;
        for (float x = x1; x < x2; x += dash + gap) {
            float e = Math.min(x + dash, x2);
            GuiRender.rect(x, y1, e, y1 + 1f, color);
            GuiRender.rect(x, y2 - 1f, e, y2, color);
        }
        for (float y = y1 + 1f; y < y2 - 1f; y += dash + gap) {
            float e = Math.min(y + dash, y2 - 1f);
            GuiRender.rect(x1, y, x1 + 1f, e, color);
            GuiRender.rect(x2 - 1f, y, x2, e, color);
        }
    }

    /** {@code color} with its alpha multiplied by {@code level}. */
    private static int fade(int color, float level) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, level)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private void markDirty() {
        if (dirtySinceMs == 0L) dirtySinceMs = Minecraft.getSystemTime();
    }

    private void playClick() {
        mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    private static ClientSettings settings() {
        if (BedwarsQol.config == null) {
            // Sanitized, so the one-time migrations stamp it now and cannot undo a later toggle on save.
            BedwarsQol.config = new ClientSettings();
            BedwarsQol.config.sanitize();
        }
        BedwarsQol.config.sanitize();
        return BedwarsQol.config;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }
}
