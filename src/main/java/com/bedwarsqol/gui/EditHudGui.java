package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.BedwarsHudRenderer.HudBox;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.gui.render.GuiBlur;
import com.bedwarsqol.gui.render.GuiRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class EditHudGui extends GuiScreen {

    private static final int HANDLE_SIZE = 4;
    private static final int SNAP_DISTANCE = 10;
    private static final int SELECTED = 0xFFFFFFFF;      // selected box: solid white
    private static final int SELECTED_FILL = 0x1AFFFFFF; // selected fill: ~10% white
    private static final int SNAP_LINE = 0xFFBFBFBF;     // snap guide: light gray
    private static final float RESET_SCALE = 0.65f;      // "Reset HUD Sizes" button label scale (half size)
    private static final int SCRIM = 0x73000000;         // same dim-over-blur scrim as SettingsGui

    private String selectedId = BedwarsHudRenderer.POTION_HUD;
    private boolean dragging;
    private boolean scaling;
    private float dragOffsetX;
    private float dragOffsetY;
    private float scaleOriginX;
    private float scaleBaseWidth;
    private boolean hasSnapX;
    private boolean hasSnapY;
    private float snapX;
    private float snapY;

    @Override
    public void initGui() {
        buttonList.clear();
        GuiBlur.begin(); // start the world-blur fade-in behind the editor
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Re-establish the 2D GUI orthographic projection ourselves (BedwarsHudRenderer cancels the
        // in-game overlay while the blur is active, which otherwise skips vanilla's setupOverlayRendering).
        mc.entityRenderer.setupOverlayRendering();
        GuiBlur.update(0, 0, mc.displayWidth, mc.displayHeight);
        if (GuiBlur.isActive()) GuiRender.rect(0, 0, width, height, SCRIM);
        else drawDefaultBackground();

        ClientSettings cfg = settings();
        Minecraft mc = Minecraft.getMinecraft();
        BedwarsHudRenderer.renderEditPreview(mc, cfg);

        if (hasSnapX) drawRect(Math.round(snapX), 0, Math.round(snapX) + 1, height, SNAP_LINE);
        if (hasSnapY) drawRect(0, Math.round(snapY), width, Math.round(snapY) + 1, SNAP_LINE);

        // Only the selected element is framed (fill + solid outline + scale handle). Unselected
        // elements show no box — the HUD preview itself is enough to see and click them.
        List<HudBox> boxes = BedwarsHudRenderer.getHudBoxes(mc, cfg, true);
        for (HudBox box : boxes) {
            if (!box.id.equals(selectedId)) continue;
            drawRect(Math.round(box.x) - 3, Math.round(box.y) - 3, Math.round(box.right()) + 3, Math.round(box.bottom()) + 3, SELECTED_FILL);
            drawOutline(box, SELECTED, 1);
            drawScaleHandle(box);
        }

        float[] rb = resetButtonRect();
        boolean rHover = GuiRender.inside(mouseX, mouseY, rb[0], rb[1], rb[2], rb[3]);
        GuiRender.roundedRect(rb[0], rb[1], rb[2], rb[3], 2, rHover ? 0xFF2E2E2E : 0xFF242424);
        GuiRender.textCentered("Reset HUD Sizes", (rb[0] + rb[2]) / 2f,
                rb[1] + (rb[3] - rb[1] - BedwarsQolFont.height(RESET_SCALE)) / 2f, RESET_SCALE, 0xFFEDEDED, BedwarsQolFont.Weight.MEDIUM);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;

        ClientSettings cfg = settings();
        float[] rb = resetButtonRect();
        if (GuiRender.inside(mouseX, mouseY, rb[0], rb[1], rb[2], rb[3])) {
            cfg.applyDefaultTextSize();
            cfg.save();
            playClick();
            return;
        }
        List<HudBox> boxes = BedwarsHudRenderer.getHudBoxes(mc, cfg, true);
        HudBox selected = boxById(boxes, selectedId);
        if (selected != null && inScaleHandle(selected, mouseX, mouseY)) {
            scaling = true;
            dragging = false;
            scaleOriginX = selected.x;
            float scale = getHudScale(cfg, selected.id);
            scaleBaseWidth = selected.width / scale;
            return;
        }

        HudBox hit = hitBox(boxes, mouseX, mouseY);
        if (hit != null) {
            selectedId = hit.id;
            dragging = true;
            scaling = false;
            dragOffsetX = mouseX - hit.x;
            dragOffsetY = mouseY - hit.y;
        } else {
            selectedId = null;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (clickedMouseButton != 0) return;
        if (dragging) {
            moveSelected(mouseX - dragOffsetX, mouseY - dragOffsetY);
        } else if (scaling) {
            scaleSelected(mouseX, mouseY);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        dragging = false;
        scaling = false;
        clearSnap();
        settings().save();
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            settings().save();
            mc.displayGuiScreen(new SettingsGui());
            return;
        }

        if (keyCode == Keyboard.KEY_UP) nudgeSelected(0, -1);
        else if (keyCode == Keyboard.KEY_DOWN) nudgeSelected(0, 1);
        else if (keyCode == Keyboard.KEY_LEFT) nudgeSelected(-1, 0);
        else if (keyCode == Keyboard.KEY_RIGHT) nudgeSelected(1, 0);
        else super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        GuiBlur.end();
        settings().save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void moveSelected(float rawX, float rawY) {
        if (selectedId == null) return;
        ClientSettings cfg = settings();
        HudBox box = boxById(BedwarsHudRenderer.getHudBoxes(mc, cfg, true), selectedId);
        if (box == null) return;

        clearSnap();
        SnapResult x = snap(rawX, box.width, true, box);
        SnapResult y = snap(rawY, box.height, false, box);
        float newX = clamp(x.position, 0f, width - box.width);
        float newY = clamp(y.position, 0f, height - box.height);
        hasSnapX = x.snapped;
        hasSnapY = y.snapped;
        snapX = x.line;
        snapY = y.line;
        BedwarsHudRenderer.setHudAbsolutePosition(cfg, selectedId, newX, newY, box.width, box.height, width, height);
    }

    private void scaleSelected(int mouseX, int mouseY) {
        ClientSettings cfg = settings();
        SnapResult snap = snapPoint(mouseX, true);
        hasSnapX = snap.snapped;
        hasSnapY = false;
        snapX = snap.line;
        float scaleX = (snap.position - scaleOriginX) / Math.max(1f, scaleBaseWidth);
        setHudScale(cfg, selectedId, clamp(scaleX, 0.3f, 10.0f));
    }

    private void nudgeSelected(int dx, int dy) {
        if (selectedId == null) return;
        ClientSettings cfg = settings();
        HudBox box = boxById(BedwarsHudRenderer.getHudBoxes(mc, cfg, true), selectedId);
        if (box == null) return;
        float x = clamp(box.x + dx, 0f, width - box.width);
        float y = clamp(box.y + dy, 0f, height - box.height);
        BedwarsHudRenderer.setHudAbsolutePosition(cfg, selectedId, x, y, box.width, box.height, width, height);
        cfg.save();
    }

    private SnapResult snap(float position, float size, boolean xAxis, HudBox moving) {
        float bestDistance = snapDistance() + 1f;
        float bestPosition = position;
        float bestLine = 0f;

        float[] movingLines = {position, position + size / 2f, position + size};
        float[] targetLines = targetLines(xAxis, moving);
        for (float target : targetLines) {
            for (int i = 0; i < movingLines.length; i++) {
                float distance = Math.abs(target - movingLines[i]);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestLine = target;
                    if (i == 0) bestPosition = target;
                    else if (i == 1) bestPosition = target - size / 2f;
                    else bestPosition = target - size;
                }
            }
        }

        return bestDistance <= snapDistance()
                ? new SnapResult(bestPosition, bestLine, true)
                : new SnapResult(position, bestLine, false);
    }

    private float[] targetLines(boolean xAxis, HudBox moving) {
        List<HudBox> boxes = BedwarsHudRenderer.getHudBoxes(mc, settings(), true);
        List<Float> lines = new ArrayList<>(1 + Math.max(0, boxes.size() - 1) * 3);
        lines.add(xAxis ? width / 2f : height / 2f);

        for (HudBox box : boxes) {
            if (box.id.equals(moving.id)) continue;
            if (xAxis) {
                lines.add(box.x);
                lines.add(box.centerX());
                lines.add(box.right());
            } else {
                lines.add(box.y);
                lines.add(box.centerY());
                lines.add(box.bottom());
            }
        }

        float[] out = new float[lines.size()];
        for (int i = 0; i < lines.size(); i++) out[i] = lines.get(i);
        return out;
    }

    private SnapResult snapPoint(float position, boolean xAxis) {
        float bestDistance = snapDistance() + 1f;
        float bestLine = position;
        float[] lines = targetLines(xAxis, selectedBoxOrEmpty());
        for (float line : lines) {
            float distance = Math.abs(line - position);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestLine = line;
            }
        }
        return bestDistance <= snapDistance()
                ? new SnapResult(bestLine, bestLine, true)
                : new SnapResult(position, bestLine, false);
    }

    private HudBox selectedBoxOrEmpty() {
        HudBox box = boxById(BedwarsHudRenderer.getHudBoxes(mc, settings(), true), selectedId);
        return box == null ? new HudBox("", "", 0f, 0f, 0f, 0f) : box;
    }

    private void drawOutline(HudBox box, int color, int thickness) {
        int x1 = Math.round(box.x) - 3;
        int y1 = Math.round(box.y) - 3;
        int x2 = Math.round(box.right()) + 3;
        int y2 = Math.round(box.bottom()) + 3;
        int t = thickness;
        // Non-overlapping frame: top/bottom span full width, left/right only the gap between them.
        // (Overlapping at the corners would double-blend a translucent color and darken them.)
        drawRect(x1, y1, x2, y1 + t, color);
        drawRect(x1, y2 - t, x2, y2, color);
        drawRect(x1, y1 + t, x1 + t, y2 - t, color);
        drawRect(x2 - t, y1 + t, x2, y2 - t, color);
    }

    private void drawScaleHandle(HudBox box) {
        int x = Math.round(box.right()) + 3 - HANDLE_SIZE / 2;
        int y = Math.round(box.bottom()) + 3 - HANDLE_SIZE / 2;
        drawRect(x, y, x + HANDLE_SIZE, y + HANDLE_SIZE, SELECTED);
    }

    /** "Reset HUD Sizes" button, pinned to the bottom-center of the editor. */
    private float[] resetButtonRect() {
        float bw = GuiRender.textWidth("Reset HUD Sizes", RESET_SCALE, BedwarsQolFont.Weight.MEDIUM) + 12f;
        float bh = BedwarsQolFont.height(RESET_SCALE) + 6f;
        float bx1 = (width - bw) / 2f;
        float by2 = height - 14f;
        return new float[]{bx1, by2 - bh, bx1 + bw, by2};
    }

    private void playClick() {
        mc.getSoundHandler().playSound(
                PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    private boolean inScaleHandle(HudBox box, int mouseX, int mouseY) {
        int x = Math.round(box.right()) + 3 - HANDLE_SIZE / 2;
        int y = Math.round(box.bottom()) + 3 - HANDLE_SIZE / 2;
        return mouseX >= x - 3 && mouseX <= x + HANDLE_SIZE + 3 && mouseY >= y - 3 && mouseY <= y + HANDLE_SIZE + 3;
    }

    private HudBox hitBox(List<HudBox> boxes, int mouseX, int mouseY) {
        for (int i = boxes.size() - 1; i >= 0; i--) {
            HudBox box = boxes.get(i);
            if (box.contains(mouseX, mouseY)) return box;
        }
        return null;
    }

    private HudBox boxById(List<HudBox> boxes, String id) {
        if (id == null) return null;
        for (HudBox box : boxes) {
            if (id.equals(box.id)) return box;
        }
        return null;
    }

    private static float getHudScale(ClientSettings cfg, String id) {
        if (BedwarsHudRenderer.ARMOR_HUD.equals(id)) return cfg.armorHudScale;
        if (BedwarsHudRenderer.INVENTORY_HUD.equals(id)) return cfg.inventoryHudScale;
        if (BedwarsHudRenderer.DIAMOND_TIMER_HUD.equals(id)) return cfg.diamondTimerHudScale;
        if (BedwarsHudRenderer.EMERALD_TIMER_HUD.equals(id)) return cfg.emeraldTimerHudScale;
        if (BedwarsHudRenderer.KEYSTROKES_HUD.equals(id)) return cfg.keystrokesHudScale;
        if (BedwarsHudRenderer.SESSION_HUD.equals(id)) return cfg.sessionStatsHudScale;
        return cfg.potionHudScale;
    }

    private static void setHudScale(ClientSettings cfg, String id, float scale) {
        if (BedwarsHudRenderer.POTION_HUD.equals(id)) {
            cfg.potionHudScale = scale;
        } else if (BedwarsHudRenderer.ARMOR_HUD.equals(id)) {
            cfg.armorHudScale = scale;
        } else if (BedwarsHudRenderer.INVENTORY_HUD.equals(id)) {
            cfg.inventoryHudScale = scale;
        } else if (BedwarsHudRenderer.DIAMOND_TIMER_HUD.equals(id)) {
            cfg.diamondTimerHudScale = scale;
        } else if (BedwarsHudRenderer.EMERALD_TIMER_HUD.equals(id)) {
            cfg.emeraldTimerHudScale = scale;
        } else if (BedwarsHudRenderer.KEYSTROKES_HUD.equals(id)) {
            cfg.keystrokesHudScale = scale;
        } else if (BedwarsHudRenderer.SESSION_HUD.equals(id)) {
            cfg.sessionStatsHudScale = scale;
        }
    }

    private void clearSnap() {
        hasSnapX = false;
        hasSnapY = false;
    }

    private float snapDistance() {
        return SNAP_DISTANCE / (float) Math.max(1, mc == null ? 1 : new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor());
    }

    private static ClientSettings settings() {
        if (BedwarsQol.config == null) BedwarsQol.config = new ClientSettings();
        BedwarsQol.config.sanitize();
        return BedwarsQol.config;
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) return min;
        return Math.min(value, max);
    }

    private static final class SnapResult {
        final float position;
        final float line;
        final boolean snapped;

        SnapResult(float position, float line, boolean snapped) {
            this.position = position;
            this.line = line;
            this.snapped = snapped;
        }
    }
}
