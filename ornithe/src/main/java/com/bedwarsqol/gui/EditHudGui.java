package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.gui.render.GuiRender;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.BedwarsHudRenderer.HudBox;
import com.bedwarsqol.hud.CornerResize;
import com.bedwarsqol.hud.HudDrag;
import com.bedwarsqol.hud.HudGrid;
import com.bedwarsqol.hud.HudModuleState;
import com.bedwarsqol.hud.HudModules;
import com.bedwarsqol.hud.HudSnapper;
import com.bedwarsqol.hud.HudWalls;
import com.bedwarsqol.hud.ScaleSteps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

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
    private static final int PANEL = 0xD0121212;           // the HUD panel fill, for the menu and the resize badge
    private static final int LABEL_FILL = 0xFF121212;      // name labels are solid: they often sit over another module
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFAAAAAA;
    private static final float HANDLE = 3f;                 // corner handle side, GUI px
    private static final float HANDLE_REACH = 4f;           // how far from a corner a press still grabs it
    private static final long OPEN_FADE_MS = 160L;
    private static final long GUIDE_FADE_MS = 200L;
    private static final long NUDGE_GROUP_MS = 800L;        // arrow presses this close together undo as one step
    private static final long SAVE_DELAY_MS = 400L;
    private static final float LABEL_SCALE = 0.8f;
    private static final float MENU_SCALE = 0.75f;
    private static final float HOTBAR_W = HudWalls.HOTBAR_W; // the vanilla hotbar, a snap target
    private static final float HOTBAR_H = HudWalls.HOTBAR_H;

    private enum Mode { NONE, DRAG, RESIZE }

    /** Reset Positions done from the settings menu, undoable here if the layout has not changed since (D7). */
    private static final PendingUndo<Map<String, HudModuleState>> PENDING = new PendingUndo<Map<String, HudModuleState>>();

    private final EditHistory<Map<String, HudModuleState>> history = new EditHistory<Map<String, HudModuleState>>(100);
    /** Holding Tab shows the tab list alone (G28); fed only with the player-list key's keyboard state. */
    private final EditTabHold tabHold = new EditTabHold();
    private final Map<String, HoverFade> hoverFades = new HashMap<String, HoverFade>();

    private String selectedId;
    private String hoverId;
    private Mode mode = Mode.NONE;
    private Map<String, HudModuleState> before;   // the layout when the current drag or resize began
    private float grabX;            // mouse offset from the dragged module's top-left, or from the grabbed corner
    private float grabY;
    private CornerResize resize;
    private boolean moved;
    private boolean pinnedThisChange; // the other Auto modules were pinned for the current drag or resize
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
    private float badgeX;
    private float badgeY;

    @Override
    public void initGui() {
        buttonList.clear();
        Keyboard.enableRepeatEvents(true);
        if (openedMs == 0L) {
            openedMs = Minecraft.getSystemTime();
            Map<String, HudModuleState> beforeReset = PENDING.take(HudModules.snapshot(settings()));
            if (beforeReset != null) history.record(beforeReset);
        }
    }

    /** Offers a change made outside the editor (Reset Positions) as the first undo step of the next Edit HUD. */
    static void offerUndo(Map<String, HudModuleState> before, Map<String, HudModuleState> after) {
        PENDING.offer(before, after);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (tabHold.held()) KeyBinding.setKeyBindState(mc.gameSettings.keyBindPlayerList.getKeyCode(), false);
        settings().save();
    }

    /**
     * Feeds the player-list key's keyboard state to the Tab hold, once a frame and on each input event. Its binding is
     * this editor's output (pressed while held, so vanilla draws the tab list where the item puts it) and is never read
     * here, so the physical release always gets through (plan review R1-B1).
     */
    private void pollTab() {
        KeyBinding list = mc.gameSettings.keyBindPlayerList;
        EditTabHold.Step step = tabHold.step(GameSettings.isKeyDown(list));
        if (step == EditTabHold.Step.NONE) return;
        if (mode != Mode.NONE) endChange(); // a change in progress is kept (undoable)
        menuId = null;
        boolean held = step == EditTabHold.Step.PRESS;
        // the selection stays only on an item that is still shown: the scoreboard and chat show either way
        if (held ? selectedId != null && !isOverlay(selectedId) : BedwarsHudRenderer.TAB_LIST_HUD.equals(selectedId)) {
            selectedId = null;
        }
        KeyBinding.setKeyBindState(list.getKeyCode(), held);
    }

    private static boolean isOverlay(String id) {
        return BedwarsHudRenderer.SCOREBOARD_HUD.equals(id) || BedwarsHudRenderer.CHAT_HUD.equals(id)
                || BedwarsHudRenderer.TAB_LIST_HUD.equals(id);
    }

    /**
     * What the editor shows and takes input for: every editor box (the modules, the scoreboard and chat), or while Tab
     * is held only the tab list, the scoreboard and chat, the tab list on top.
     */
    private List<HudBox> visibleBoxes() {
        ClientSettings cfg = settings();
        List<HudBox> all = BedwarsHudRenderer.getEditorBoxes(mc, cfg);
        if (!tabHold.held()) return all;
        List<HudBox> out = new ArrayList<HudBox>();
        for (HudBox b : all) {
            if (BedwarsHudRenderer.SCOREBOARD_HUD.equals(b.id) || BedwarsHudRenderer.CHAT_HUD.equals(b.id)) out.add(b);
        }
        HudBox tab = BedwarsHudRenderer.tabListBox(mc, cfg);
        if (tab != null) out.add(tab);
        return out;
    }

    /** Item {@code id}'s box measured alone: the tab list's own box, else its editor box. */
    private HudBox measuredBox(ClientSettings cfg, String id) {
        return BedwarsHudRenderer.TAB_LIST_HUD.equals(id) ? BedwarsHudRenderer.tabListBox(mc, cfg)
                : BedwarsHudRenderer.getEditorBox(mc, cfg, id);
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

        pollTab();
        boolean tabHeld = tabHold.held();
        List<HudBox> boxes = visibleBoxes();
        // A press only becomes a drag once the mouse moves; until then nothing changes, so a click doesn't flash.
        hoverId = (mode == Mode.NONE || !moved) && menuId == null ? topmostAt(boxes, mouseX, mouseY) : null;
        Map<String, Float> hover = new HashMap<String, Float>();
        for (HudBox box : boxes) hover.put(box.id, hoverFade(box.id).update(box.id.equals(hoverId), now));

        // Hidden modules first, under everything else.
        for (HudBox box : boxes) {
            if (!box.shown) drawGhost(box, box.id.equals(selectedId) ? accent : GHOST, open);
        }

        // While Tab is held vanilla draws the tab list (its binding is pressed) and the Cobblify items stay hidden.
        if (!tabHeld) BedwarsHudRenderer.renderEditPreview(mc, cfg);
        // Item icons in the preview write depth; clear it so frames, labels and menus always draw on top.
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
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
        if (menuId != null) drawMenu(mouseX, mouseY, accent);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawFrame(HudBox box, float hover, boolean selected, int accent, float open) {
        int color = selected || isSnapTarget(box.id) ? accent : GuiRender.lerpColor(FRAME, FRAME_HOVER, hover);
        float x1 = box.visualX(), y1 = box.visualY(), x2 = box.visualRight(), y2 = box.visualBottom();
        GuiRender.rect(x1, y1, x2, y2, fade(FRAME_FILL, open));
        GuiRender.border(x1, y1, x2, y2, fade(color, open), 1f);
    }

    private void drawGhost(HudBox box, int outline, float open) {
        float x1 = box.visualX(), y1 = box.visualY(), x2 = box.visualRight(), y2 = box.visualBottom();
        GuiRender.rect(x1, y1, x2, y2, fade(GHOST_FILL, open));
        dashedBorder(x1, y1, x2, y2, fade(outline, open));
        String name = HudModules.ghostTitle(box.id);
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
        GuiRender.rect(x, y, x + w, y + h, fade(LABEL_FILL, level));
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
        String text = String.format("%.2fx", BedwarsHudRenderer.drawnScale(cfg, selectedId)); // the size drawn
        float w = GuiRender.textWidth(text, MENU_SCALE, BedwarsQolFont.Weight.BOLD) + 8f;
        float h = BedwarsQolFont.height(MENU_SCALE) + 5f;
        float x = clamp(badgeX + 8f, 1f, width - w - 1f);
        float y = clamp(badgeY + 8f, 1f, height - h - 1f);
        GuiRender.rect(x, y, x + w, y + h, fade(PANEL, open));
        GuiRender.text(text, x + 4f, y + 2.5f, MENU_SCALE, fade(TEXT, open), BedwarsQolFont.Weight.BOLD);
    }

    private void drawMenu(int mouseX, int mouseY, int accent) {
        List<String> items = menuItems();
        float[] r = menuRect(items);
        float rowH = menuRowHeight();
        GuiRender.rect(r[0], r[1], r[2], r[3], PANEL);
        GuiRender.text(HudModules.title(menuId), r[0] + 5f, r[1] + (rowH - BedwarsQolFont.height(MENU_SCALE)) / 2f,
                MENU_SCALE, TEXT_DIM, BedwarsQolFont.Weight.BOLD);
        for (int i = 0; i < items.size(); i++) {
            float y = r[1] + rowH * (i + 1);
            if (GuiRender.inside(mouseX, mouseY, r[0], y, r[2], y + rowH)) GuiRender.rect(r[0], y, r[2], y + rowH, accent);
            GuiRender.text(items.get(i), r[0] + 5f, y + (rowH - BedwarsQolFont.height(MENU_SCALE)) / 2f, MENU_SCALE,
                    TEXT, BedwarsQolFont.Weight.BOLD);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        pollTab();
        if (menuId != null) {
            if (mouseButton == 0) clickMenu(mouseX, mouseY);
            menuId = null;
            return;
        }
        ClientSettings cfg = settings();
        List<HudBox> boxes = visibleBoxes();

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

        // Grab offsets and the press point to a fraction of a GUI pixel, so every step can be one screen pixel.
        float[] p = eventPointer();
        // A corner of the selected or hovered module starts a resize.
        HudBox grabbed = cornerTarget(boxes, mouseX, mouseY);
        if (grabbed != null) {
            int corner = CornerResize.cornerAt(mouseX, mouseY, grabbed.visualX(), grabbed.visualY(),
                    grabbed.visualWidth(), grabbed.visualHeight(), HANDLE_REACH);
            selectedId = grabbed.id;
            // The snapshot comes first, so Esc and undo put back everything the resize changes, Team Upgrades' size
            // included (it is pinned at the first move, in resizeTo); a click with no move changes nothing.
            beginChange(Mode.RESIZE);
            // From the size it is drawn at: a stepped item's step, Team Upgrades' fitted size in its default spot.
            resize = new CornerResize(corner, grabbed.x, grabbed.y, grabbed.width, grabbed.height,
                    BedwarsHudRenderer.drawnScale(cfg, grabbed.id));
            // The handle sits on the drawn frame, outside the content; keep that offset so the size
            // does not jump on the first move.
            grabX = p[0] - CornerResize.cornerX(corner, grabbed.x, grabbed.width);
            grabY = p[1] - CornerResize.cornerY(corner, grabbed.y, grabbed.height);
            badgeX = mouseX;
            badgeY = mouseY;
            return;
        }

        clickX = p[0];
        clickY = p[1];
        List<String> stack = stackAt(boxes, mouseX, mouseY);
        if (stack.isEmpty()) {
            selectedId = null;
            return;
        }
        wasSelectedBeforeClick = stack.contains(selectedId);
        if (!wasSelectedBeforeClick) selectedId = stack.get(0);
        HudBox box = boxById(boxes, selectedId);
        grabX = p[0] - box.x;
        grabY = p[1] - box.y;
        beginChange(Mode.DRAG);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        pollTab();
        if (clickedMouseButton != 0 || selectedId == null) return;
        float[] p = eventPointer();
        if (mode == Mode.DRAG) {
            if (!moved && Math.abs(p[0] - clickX) < 1f && Math.abs(p[1] - clickY) < 1f) return;
            moved = true;
            drag(p[0] - grabX, p[1] - grabY);
        } else if (mode == Mode.RESIZE) {
            moved = true;
            badgeX = mouseX;
            badgeY = mouseY;
            resizeTo(p[0] - grabX, p[1] - grabY);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (mode != Mode.NONE) endChange();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        pollTab();
        if (keyCode == Keyboard.KEY_TAB) return; // holding Tab shows the tab list (D9: it no longer cycles the selection)
        if (keyCode == Keyboard.KEY_ESCAPE) {
            escape();
            return;
        }
        if (menuId != null) {
            menuId = null;
            return;
        }
        if (isCtrlKeyDown() && (keyCode == Keyboard.KEY_Z || keyCode == Keyboard.KEY_Y)) {
            if (keyCode == Keyboard.KEY_Y || isShiftKeyDown()) redo();
            else undo();
            return;
        }
        if (mode != Mode.NONE) return;
        if (selectedId == null) return;
        int step = isShiftKeyDown() ? 10 : 1; // screen pixels

        if (keyCode == Keyboard.KEY_UP) nudge(0, -step);
        else if (keyCode == Keyboard.KEY_DOWN) nudge(0, step);
        else if (keyCode == Keyboard.KEY_LEFT) nudge(-step, 0);
        else if (keyCode == Keyboard.KEY_RIGHT) nudge(step, 0);
        else if ((keyCode == Keyboard.KEY_DELETE || keyCode == Keyboard.KEY_BACK) && EditHudMenu.hideable(selectedId)) {
            setShown(selectedId, false);
        }
    }

    /** Esc backs out one step: the menu, then a drag in progress, then the selection, then the editor. */
    private void escape() {
        if (menuId != null) {
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
        pinnedThisChange = false;
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

    /** Moves the dragged module's content towards (rawX, rawY): where it was at the press plus the pointer's travel. */
    private void drag(float rawX, float rawY) {
        ClientSettings cfg = settings();
        pinOnce();
        List<HudBox> boxes = visibleBoxes();
        HudBox box = boxById(boxes, selectedId);
        if (box == null) return;
        boolean snap = snapping(); // Alt moves freely: no snapping, no walls
        List<HudSnapper.Target> targets = snap ? snapTargets(boxes, box.id) : Collections.<HudSnapper.Target>emptyList();
        HudDrag.Result r = HudDrag.move(rawX - box.pad, rawY - box.pad, box.visualWidth(), box.visualHeight(),
                width, height, scaleFactor(), snap, targets, snap, wallRects(boxes, box.id), box.visualX(), box.visualY());
        snapX = r.snapX;
        snapY = r.snapY;
        place(cfg, box, r.x + box.pad, r.y + box.pad);
    }

    private void resizeTo(float cornerX, float cornerY) {
        ClientSettings cfg = settings();
        pinOnce();
        BedwarsHudRenderer.pinDrawnScale(cfg, selectedId); // Team Upgrades leaves its default spot at the size drawn
        HudModuleState s = HudModules.get(cfg, selectedId);
        List<HudBox> boxes = visibleBoxes();
        HudBox box = boxById(boxes, selectedId);
        if (s == null || box == null || resize == null) return;
        float scale = resize.scaleFor(cornerX, cornerY, width, height, box.pad, snapping());
        // The tab list stops growing once it fills the screen width (cycle B's fit rule), before any wall.
        if (BedwarsHudRenderer.TAB_LIST_HUD.equals(selectedId)) {
            scale = Math.min(scale, BedwarsHudRenderer.tabListFitScale(mc, cfg));
        }
        // A HUD module in Minecraft's font draws at any size (SharpText) and is crispest at whole steps (ScaleSteps):
        // a step catches the pointer within ScaleSteps.REACH (Alt: freely).
        boolean stepped = BedwarsHudRenderer.stepsScale(selectedId);
        int sf = scaleFactor();
        if (stepped && snapping()) {
            // never past the screen room scaleFor already kept it within
            float room = resize.roomScale(width, height, box.pad);
            scale = Math.min(ScaleSteps.snap(scale, sf, ScaleSteps.REACH / sf), Math.max(scale, room));
        }
        // Growing stops at the first wall (Alt: freely); shrinking is always allowed. Stopped just past a step, a
        // module rests on that step.
        if (snapping()) {
            HudWalls.ScaleProbe probe = resizeProbe(cfg, selectedId, s);
            List<HudWalls.Rect> walls = wallRects(boxes, selectedId);
            float from = resizeStartScale(cfg, selectedId);
            float free = HudWalls.largestFreeScale(from, scale, probe, walls);
            if (stepped && free < scale) free = Math.max(from, ScaleSteps.restOnStep(free, sf, CornerResize.SNAP_TO_ONE));
            scale = free;
        }
        HudModules.set(cfg, selectedId, s.withScale(scale));
        // Measure at the new scale, then keep the opposite corner where it was.
        HudBox scaled = selectedBox();
        if (scaled == null) return;
        // Placed from its measured size: a box laid out at its text scale steps in whole device pixels (A-I1).
        BedwarsHudRenderer.setHudAbsolutePosition(cfg, selectedId, resize.leftFor(scaled.width), resize.topFor(scaled.height),
                scaled.width, scaled.height, width, height);
    }

    /** Moves the selected module by whole screen pixels from wherever it is, sub-pixel offset included. */
    private void nudge(int dx, int dy) {
        ClientSettings cfg = settings();
        HudBox box = selectedBox();
        if (box == null) return;
        long now = Minecraft.getSystemTime();
        if (now - lastNudgeMs > NUDGE_GROUP_MS) history.record(HudModules.snapshot(cfg));
        lastNudgeMs = now;
        pinOthers(box.id);
        List<HudBox> boxes = visibleBoxes();
        int sf = scaleFactor();
        boolean walls = snapping(); // arrows stop at walls like a drag; Alt + arrow moves freely
        HudDrag.Result r = HudDrag.move(HudGrid.nudged(box.x, dx, sf) - box.pad, HudGrid.nudged(box.y, dy, sf) - box.pad,
                box.visualWidth(), box.visualHeight(), width, height, sf, false,
                Collections.<HudSnapper.Target>emptyList(), walls, wallRects(boxes, box.id), box.visualX(), box.visualY());
        place(cfg, box, r.x + box.pad, r.y + box.pad);
        markDirty();
    }

    /** Pins the other Auto modules once per drag or resize, at its first move (so a click alone changes nothing). */
    private void pinOnce() {
        if (pinnedThisChange || selectedId == null) return;
        pinnedThisChange = true;
        pinOthers(selectedId);
    }

    /**
     * Every other shown Auto module keeps the spot it has now, so a change to {@code id} moves nothing else. The tab
     * list is no Auto-layout obstacle, so a change to it moves nothing and pins nothing.
     */
    private void pinOthers(String id) {
        if (BedwarsHudRenderer.TAB_LIST_HUD.equals(id)) return;
        BedwarsHudRenderer.pinShownAuto(mc, settings(), Collections.singleton(id));
    }

    /**
     * The scale a corner resize starts from: the item's, or for the tab list the scale it really draws at (its scale,
     * shrunk to fit the screen width), so the box the resize measures matches.
     */
    private float resizeStartScale(ClientSettings cfg, String id) {
        float s = HudModules.get(cfg, id).scale;
        return BedwarsHudRenderer.TAB_LIST_HUD.equals(id) ? Math.min(s, BedwarsHudRenderer.tabListFitScale(mc, cfg)) : s;
    }

    /**
     * The rectangle a corner resize of {@code id} really covers at a candidate scale: the box measured alone at that
     * scale (then the scale is put back), placed with the opposite corner pinned (review I2).
     */
    private HudWalls.ScaleProbe resizeProbe(final ClientSettings cfg, final String id, final HudModuleState base) {
        return new HudWalls.ScaleProbe() {
            @Override
            public HudWalls.Rect at(float scale) {
                HudModules.set(cfg, id, base.withScale(scale));
                HudBox b = measuredBox(cfg, id);
                HudModules.set(cfg, id, base);
                return b == null || resize == null ? null
                        : resize.visualRectAt(scale, b.width, b.height, b.pad, width, height);
            }
        };
    }

    /** Moves {@code box}'s content to (x, y), kept on screen with its panel padding. */
    private void place(ClientSettings cfg, HudBox box, float x, float y) {
        BedwarsHudRenderer.pinDrawnScale(cfg, box.id); // a move never changes the size
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

    /**
     * Runs {@code change} on the layout as one undoable step. A change to one module ({@code id}, null for none) first
     * pins the other Auto modules, so it moves nothing else.
     */
    private void edit(String id, Runnable change) {
        ClientSettings cfg = settings();
        Map<String, HudModuleState> previous = HudModules.snapshot(cfg);
        if (id != null) pinOthers(id);
        change.run();
        if (!HudModules.snapshot(cfg).equals(previous)) {
            history.record(previous);
            lastNudgeMs = 0L;
            markDirty();
        }
    }

    private void resetPosition(final String id) {
        edit(id, new Runnable() {
            @Override
            public void run() {
                ClientSettings cfg = settings();
                HudModuleState d = HudModules.defaults(cfg, id);
                HudModules.set(cfg, id, HudModules.get(cfg, id).withPosition(d.x, d.y, d.anchor));
            }
        });
    }

    /**
     * Back to the HUD Size default, keeping the module's top-left corner where it is; one that grew into a neighbour
     * moves to the nearest free spot.
     */
    private void resetSize(final String id) {
        edit(id, new Runnable() {
            @Override
            public void run() {
                ClientSettings cfg = settings();
                HudBox old = boxById(visibleBoxes(), id);
                // the HUD Size default for a module, 1.0 for the scoreboard, chat and tab list (cycle B)
                HudModules.set(cfg, id, HudModules.get(cfg, id).withScale(HudModules.defaults(cfg, id).scale));
                HudBox scaled = boxById(visibleBoxes(), id);
                if (old != null && scaled != null) place(cfg, scaled, old.x, old.y);
                moveClearOfWalls(cfg, id);
            }
        });
    }

    /** Moves module {@code id} to the nearest spot clear of every wall, if it overlaps one. */
    private void moveClearOfWalls(ClientSettings cfg, String id) {
        List<HudBox> boxes = visibleBoxes();
        HudBox now = boxById(boxes, id);
        if (now == null) return;
        List<HudWalls.Rect> walls = wallRects(boxes, id);
        if (HudWalls.free(now.visualX(), now.visualY(), now.visualWidth(), now.visualHeight(), walls)) return;
        float[] r = HudWalls.resolve(now.visualX(), now.visualY(), now.visualWidth(), now.visualHeight(), width, height,
                walls, now.visualX(), now.visualY());
        place(cfg, now, r[0] + now.pad, r[1] + now.pad);
    }

    /**
     * Shows or hides module {@code id} as one undoable step, moving nothing else. A shown Cobblify module takes its
     * first free Auto spot, whatever spot it had before it was hidden (undo brings that spot back).
     */
    private void setShown(final String id, final boolean shown) {
        edit(id, new Runnable() {
            @Override
            public void run() {
                ClientSettings cfg = settings();
                if (shown && HudModules.ids().contains(id)) {
                    BedwarsHudRenderer.show(mc, cfg, Collections.singletonList(id));
                } else {
                    HudModules.set(cfg, id, HudModules.get(cfg, id).withEnabled(shown));
                }
            }
        });
    }

    private void cycleUnderneath() {
        List<String> stack = stackAt(visibleBoxes(), clickX, clickY);
        int i = stack.indexOf(selectedId);
        if (stack.size() > 1 && i >= 0) selectedId = stack.get((i + 1) % stack.size());
    }

    // ------------------------------------------------------------------ right-click menu

    private List<String> menuItems() {
        ClientSettings cfg = settings();
        HudModuleState s = HudModules.get(cfg, menuId);
        boolean shown = s != null && s.enabled;
        Boolean background = HudModules.backgroundIds().contains(menuId) ? HudModules.background(cfg, menuId) : null;
        return EditHudMenu.items(shown, EditHudMenu.hideable(menuId), background);
    }

    /**
     * Switches item {@code id}'s Background, the same setting as its settings row, as one undoable step. An item whose
     * box grew into a neighbour (a panel pads more than bare content) moves to the nearest free spot.
     */
    private void toggleBackground(final String id) {
        edit(id, new Runnable() {
            @Override
            public void run() {
                ClientSettings cfg = settings();
                Map<String, Boolean> before = new HashMap<String, Boolean>();
                for (String i : HudModules.ids()) before.put(i, HudModules.background(cfg, i));
                HudModules.setBackground(cfg, id, !HudModules.background(cfg, id));
                for (String i : HudModules.ids()) {
                    if (before.get(i).booleanValue() != HudModules.background(cfg, i)) moveClearOfWalls(cfg, i);
                }
            }
        });
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
            if (EditHudMenu.SHOW.equals(item)) setShown(id, true);
            else if (EditHudMenu.HIDE.equals(item)) setShown(id, false);
            else if (EditHudMenu.RESET_POSITION.equals(item)) resetPosition(id);
            else if (EditHudMenu.RESET_SIZE.equals(item)) resetSize(id);
            else if (EditHudMenu.isBackground(item)) toggleBackground(id);
            else if (EditHudMenu.OPEN_SETTINGS.equals(item)) {
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

    /**
     * The walls for moving {@code movingId} ({@link HudWalls#isWall}): every other shown item's box as drawn, the
     * scoreboard and chat included, never the tab list (while Tab is held: the scoreboard and chat only), and the
     * hotbar zone (for chat the hotbar alone, D10).
     */
    private List<HudWalls.Rect> wallRects(List<HudBox> boxes, String movingId) {
        List<HudWalls.Rect> walls = new ArrayList<HudWalls.Rect>();
        boolean tabHeld = tabHold.held();
        for (HudBox b : boxes) {
            if (!HudWalls.isWall(movingId, b.id, b.shown, tabHeld)) continue;
            walls.add(new HudWalls.Rect(b.visualX(), b.visualY(), b.visualWidth(), b.visualHeight()));
        }
        walls.add(HudWalls.hotbarWall(movingId, width, height));
        return walls;
    }

    private int scaleFactor() {
        return new ScaledResolution(mc).getScaleFactor();
    }

    /**
     * The pointer of the mouse event being handled, in GUI px to a fraction (vanilla passes whole GUI px). Valid only
     * inside {@code handleMouseInput}, which calls mouseClicked, mouseClickMove and mouseReleased.
     */
    private float[] eventPointer() {
        return HudGrid.pointer(Mouse.getEventX(), Mouse.getEventY(), mc.displayHeight, scaleFactor());
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

    private HudBox selectedBox() {
        return boxById(visibleBoxes(), selectedId);
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
