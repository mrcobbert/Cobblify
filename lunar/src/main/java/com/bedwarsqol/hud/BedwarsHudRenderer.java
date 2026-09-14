package com.bedwarsqol.hud;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.bedwars.GeneratorTracker;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.SessionStats;
import com.bedwarsqol.feature.SessionStatsWatch;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.gui.render.GuiBlur;
import com.bedwarsqol.gui.render.GuiRender;
import com.bedwarsqol.gui.render.Theme;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.weavemc.api.event.RenderGameOverlayEvent;
import net.weavemc.api.event.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BedwarsHudRenderer {

    public static final String INVENTORY_HUD = "inventory";
    public static final String DIAMOND_TIMER_HUD = "diamondtimer";
    public static final String SESSION_HUD = "sessionstats";
    public static final String EMERALD_TIMER_HUD = "emeraldtimer";
    private static final int INV_COLS = 9;
    private static final int INV_ROWS = 3; // storage rows only — the hotbar is already on screen
    // Inventory grid geometry (local pre-scale units). ONE gutter value (INV_GAP) is used everywhere:
    // between adjacent tiles AND between the panel edge and the outer tiles, so every gap is identical.
    // Tiles are sized larger than the 16px item (INV_ITEM_PAD breathing room on every side) so item
    // sprites never touch the tile corners. The cell-to-cell pitch and the panel extent both derive
    // from these — no independent magic numbers, so the spacing stays definitively consistent.
    private static final float INV_ITEM = 16f;        // vanilla item icon size (renderItemIntoGUI draws 16x16)
    private static final float INV_GAP = 2f;           // the single consistent gutter (tile↔tile and panel-edge↔tile)
    private static final float INV_ITEM_PAD = 2f;      // breathing room between a tile's inner edge and the item, all sides
    private static final float INV_TILE = INV_ITEM + 2f * INV_ITEM_PAD;  // 20px slot tile
    private static final float INV_PITCH = INV_TILE + INV_GAP;           // 22px cell-to-cell step
    // Recessed-slot bevel — vanilla-flavored but expressed as subtle overlays on the dark HUD panel:
    // a faint light face, a darker top/left inner shadow, and a whisper-light bottom/right highlight.
    private static final int INV_SLOT_FILL = 0x33FFFFFF;       // ~20% white tile face: a clear lighter gray slot over the darker panel
    private static final int INV_SLOT_SHADOW = 0x40000000;     // ~25% black inner shadow on top + left edges
    private static final int INV_SLOT_HIGHLIGHT = 0x18FFFFFF;  // ~9% white highlight on bottom + right edges
    private static final float[] ANCHOR_X = {0f, 0.5f, 1f, 0f, 0.5f, 1f, 0f, 0.5f, 1f};
    private static final float[] ANCHOR_Y = {0f, 0f, 0f, 0.5f, 0.5f, 0.5f, 1f, 1f, 1f};

    private static final float TEXT_HEIGHT = 9f;
    private static final float LINE_GAP = 2f;
    private static final float SECONDARY_SCALE = 0.75f;
    private static final float SECONDARY_GAP = 2f;
    private static final float POTION_ICON_TIMER_GAP = 3f;
    private static final float ICON_SIZE = 16f;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    // ----- Optional per-module "Background" panel -----
    // A flat, square-cornered translucent dark panel behind a HUD element (no border, no corner
    // radius). One consistent grayscale treatment for every module, built on Theme.PANEL_HUD — the
    // HUD-overlay fill the mod's palette reserves for over-the-world panels (scoreboard/tab list).
    // Padding scales with the element so it stays proportionate.
    private static final int HUD_BG_FILL = 0xD0121212;   // darker than Theme.PANEL_HUD (0xB01A1A1A): more opaque (alpha 0xB0->0xD0) + lower RGB

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        ClientSettings cfg = BedwarsQol.config;
        if (mc == null || mc.thePlayer == null || cfg == null) return;
        if (GuiBlur.isActive()) return; // settings GUI open: its blurred backdrop replaces the HUD
        if (mc.gameSettings != null && mc.gameSettings.showDebugInfo) return;

        cfg.sanitize();
        render(mc, cfg, false);
    }

    public static void renderEditPreview(Minecraft mc, ClientSettings cfg) {
        render(mc, cfg, true);
    }

    public static List<HudBox> getHudBoxes(Minecraft mc, ClientSettings cfg, boolean example) {
        if (mc == null || cfg == null) return Collections.emptyList();
        cfg.sanitize();
        hudVanillaFont = cfg.hudFont == 1;

        List<HudBox> boxes = new ArrayList<>(8);
        addBox(boxes, inventoryBox(mc, cfg, example));
        addBox(boxes, timerBox(mc, cfg, example, true));
        addBox(boxes, timerBox(mc, cfg, example, false));
        addBox(boxes, sessionBox(mc, cfg, example));
        return boxes;
    }

    private static void addBox(List<HudBox> boxes, HudBox box) {
        if (box != null) boxes.add(box);
    }

    public static void setHudAbsolutePosition(ClientSettings cfg, String id, float x, float y, float width, float height, float screenWidth, float screenHeight) {
        int anchor = anchorFor(x, y, width, height, screenWidth, screenHeight);
        int storedX = Math.round(x - ANCHOR_X[anchor] * screenWidth + ANCHOR_X[anchor] * width);
        int storedY = Math.round(y - ANCHOR_Y[anchor] * screenHeight + ANCHOR_Y[anchor] * height);
        if (INVENTORY_HUD.equals(id)) {
            cfg.inventoryHudAnchor = anchor;
            cfg.inventoryHudX = storedX;
            cfg.inventoryHudY = storedY;
        } else if (DIAMOND_TIMER_HUD.equals(id)) {
            cfg.diamondTimerHudAnchor = anchor;
            cfg.diamondTimerHudX = storedX;
            cfg.diamondTimerHudY = storedY;
        } else if (EMERALD_TIMER_HUD.equals(id)) {
            cfg.emeraldTimerHudAnchor = anchor;
            cfg.emeraldTimerHudX = storedX;
            cfg.emeraldTimerHudY = storedY;
        } else if (SESSION_HUD.equals(id)) {
            cfg.sessionStatsHudAnchor = anchor;
            cfg.sessionStatsHudX = storedX;
            cfg.sessionStatsHudY = storedY;
        }
    }

    private static void render(Minecraft mc, ClientSettings cfg, boolean example) {
        hudVanillaFont = cfg.hudFont == 1;
        drawInventoryHud(mc, cfg, example);
        drawTimerHud(mc, cfg, example, true);
        drawTimerHud(mc, cfg, example, false);
        drawSessionHud(mc, cfg, example);
    }

    /**
     * The optional per-module "Background": a flat, square translucent panel (no corner radius, no
     * border) behind a HUD element's content box expanded by a scale-aware padding. Drawn in GUI space
     * BEFORE the element's content (and before any {@code pushMatrix}/scale used for item rendering).
     * {@code GuiRender.rect} is self-contained for GL state, so the content draw that follows gets a
     * clean (texturing on, color white) state.
     */
    private static void drawHudBackground(HudBox box, float scale, int fill) {
        int pad = Math.max(2, Math.round(4f * scale));
        float x1 = box.x - pad, y1 = box.y - pad;
        float x2 = box.right() + pad, y2 = box.bottom() + pad;
        GuiRender.roundedRect(x1, y1, x2, y2, Theme.CARD_R, fill);
    }

    private static void drawHudBackground(HudBox box, float scale) {
        drawHudBackground(box, scale, HUD_BG_FILL);
    }

    /**
     * Per-row background chips for an icon-mode HUD (Gen Timers): one rounded panel hugging
     * each row's number text — never a single connected column. Each chip wraps that row's actual drawn
     * glyphs (measured in the BOLD weight the numbers render with) with compact, even padding, vertically
     * centred on the visible glyph band so it sits dead-centre on the icon row at any scale and font.
     *
     * <p>Geometry mirrors the text draws ({@link #drawIconCounts}) exactly:
     * origin {@code tx = box.x + iconSize + gap}, per-row {@code ty = box.y + i*lineStep + (iconSize-9)/2}.
     * {@code bandTop}/{@code bandH} come from the active font so the chip hugs the ink (cap-top → digit
     * baseline), not the 9px line cell — Inter via capTop/capHeight (digits overshoot 'H' by one atlas
     * unit, hence 25/24); the vanilla font fills roughly the top 7px of its cell.
     */
    private static void drawTextBgChips(HudBox box, float iconLocal, List<String> texts, float scale) {
        if (texts.isEmpty()) return;
        FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;
        float iconSize = iconLocal * scale;
        float lineStep = iconSize + LINE_GAP * scale;
        float tx = box.x + iconSize + POTION_ICON_TIMER_GAP * scale; // text origin X (matches the draw code)
        float padX = 2.0f * scale, padY = 2.25f * scale;
        float bandTop = hudVanillaFont ? 0f : BedwarsQolFont.capTop(scale, BedwarsQolFont.Weight.BOLD);
        float bandH = hudVanillaFont ? 7f * scale
                : BedwarsQolFont.capHeight(scale, BedwarsQolFont.Weight.BOLD) * (25f / 24f);
        for (int i = 0; i < texts.size(); i++) {
            String s = texts.get(i);
            if (s == null || s.isEmpty()) continue;
            float w = hudVanillaFont ? (fr != null ? fr.getStringWidth(s) * scale : 0f)
                    : BedwarsQolFont.width(s, scale, BedwarsQolFont.Weight.BOLD);
            if (w <= 0f) continue;
            float ty = box.y + i * lineStep + (iconSize - TEXT_HEIGHT * scale) / 2f; // exact text draw ty
            float gy = ty + bandTop;
            float x1 = Math.round(tx - padX), y1 = Math.round(gy - padY);
            float x2 = Math.round(tx + w + padX), y2 = Math.round(gy + bandH + padY);
            GuiRender.roundedRect(x1, y1, x2, y2, Theme.CARD_R, HUD_BG_FILL);
        }
    }

    private static void drawLines(FontRenderer fr, List<Line> lines, float x, float y, float scale) {
        if (fr == null || lines.isEmpty()) return;
        float textHeight = TEXT_HEIGHT * scale;
        float lineStep = (TEXT_HEIGHT + LINE_GAP) * scale;
        float secondaryScale = scale * SECONDARY_SCALE;
        float secondaryYOffset = (textHeight - TEXT_HEIGHT * secondaryScale) / 2f;
        float gap = SECONDARY_GAP * scale;

        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            float ly = y + i * lineStep;
            if (line.primary.isEmpty()) continue; // blank spacer row: advances one lineStep, draws nothing
            drawScaledString(fr, line.primary, x, ly, scale, line.weight);
            if (!line.secondary.isEmpty()) {
                float secX = x + fontWidth(line.primary, line.weight) * scale + gap;
                drawScaledString(fr, line.secondary, secX, ly + secondaryYOffset, secondaryScale, line.weight);
            }
        }
    }

    // ----- HUD font: modern Inter atlas (default) or the vanilla Minecraft font, chosen in Settings -----
    // Cached at each render/measure entry (render(), getHudBoxes) so the width + draw helpers agree within
    // a frame. Client rendering is single-threaded, so the shared static is safe.
    private static boolean hudVanillaFont = false;

    /**
     * Width of {@code text} at scale 1.0 in the active HUD font (multiply by your scale at the call
     * site), measured in the weight it is drawn with — every HUD draw is SemiBold unless a
     * {@link Line} says otherwise, and SemiBold advances are wider than Regular.
     */
    private static float fontWidth(String text) {
        return fontWidth(text, BedwarsQolFont.Weight.BOLD);
    }

    private static float fontWidth(String text, BedwarsQolFont.Weight weight) {
        if (hudVanillaFont) {
            FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;
            return fr == null ? 0f : fr.getStringWidth(text);
        }
        return BedwarsQolFont.width(text, 1f, weight);
    }

    /** Line height at {@code scale}; both fonts are ~9px tall at scale 1.0. */
    private static float fontHeight(float scale) {
        return hudVanillaFont ? 9f * scale : BedwarsQolFont.height(scale);
    }

    /** Draw {@code text} with its top-left at (x,y), scaled about that origin, in the active HUD font. */
    private static void fontDraw(String text, float x, float y, float scale, int color, BedwarsQolFont.Weight weight) {
        if (hudVanillaFont) {
            FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;
            if (fr == null) return;
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, 0f);
            GlStateManager.scale(scale, scale, 1f);
            fr.drawString(text, 0, 0, color);
            GlStateManager.popMatrix();
            resetGlState();
        } else {
            BedwarsQolFont.draw(text, x, y, scale, color, false, weight);
        }
    }

    private static void drawScaledString(FontRenderer fr, String text, float x, float y, float scale) {
        // Modern Inter SemiBold (default) or the vanilla Minecraft font, per the HUD "Font" setting.
        drawScaledString(fr, text, x, y, scale, BedwarsQolFont.Weight.BOLD);
    }

    private static void drawScaledString(FontRenderer fr, String text, float x, float y, float scale,
                                         BedwarsQolFont.Weight weight) {
        fontDraw(text, x, y, scale, TEXT_COLOR, weight);
    }

    private static float absoluteX(float storedX, int anchor, float width, float screenWidth) {
        int safeAnchor = Math.max(0, Math.min(8, anchor));
        return storedX + ANCHOR_X[safeAnchor] * screenWidth - ANCHOR_X[safeAnchor] * width;
    }

    private static float absoluteY(float storedY, int anchor, float height, float screenHeight) {
        int safeAnchor = Math.max(0, Math.min(8, anchor));
        return storedY + ANCHOR_Y[safeAnchor] * screenHeight - ANCHOR_Y[safeAnchor] * height;
    }

    private static int anchorFor(float x, float y, float width, float height, float screenWidth, float screenHeight) {
        float right = x + width;
        float bottom = y + height;
        if (x <= screenWidth / 3f && y <= screenHeight / 3f) return 0;
        if (right >= screenWidth / 3f * 2f && y <= screenHeight / 3f) return 2;
        if (x <= screenWidth / 3f && bottom >= screenHeight / 3f * 2f) return 6;
        if (right >= screenWidth / 3f * 2f && bottom >= screenHeight / 3f * 2f) return 8;
        if (y <= screenHeight / 3f) return 1;
        if (x <= screenWidth / 3f) return 3;
        if (right >= screenWidth / 3f * 2f) return 5;
        if (bottom >= screenHeight / 3f * 2f) return 7;
        return 4;
    }

    private static Size textSize(FontRenderer fr, List<Line> lines, float scale) {
        if (fr == null || lines.isEmpty()) return Size.EMPTY;

        float width = 0f;
        for (Line line : lines) {
            width = Math.max(width, lineWidth(fr, line) * scale);
        }
        float height = lines.size() * ((TEXT_HEIGHT + LINE_GAP) * scale) - LINE_GAP * scale;
        return new Size(width, height);
    }

    private static float lineWidth(FontRenderer fr, Line line) {
        if (fr == null) return 0f;
        float width = fontWidth(line.primary, line.weight);
        if (!line.secondary.isEmpty()) {
            width += SECONDARY_GAP + fontWidth(line.secondary, line.weight) * SECONDARY_SCALE;
        }
        return width;
    }

    // ----- BedWars HUDs (mini inventory, diamond/emerald spawn timers) -----

    /** These overlays only make sense inside an active BedWars game; the edit preview bypasses it. */
    private static boolean bedwarsActive(boolean example) {
        return example || HypixelContext.isInActiveBedwarsGame();
    }

    private static HudBox inventoryBox(Minecraft mc, ClientSettings cfg, boolean example) {
        if (!cfg.inventoryHudEnabled) return null; // works anywhere unless "In Game Only" is set
        if (cfg.inventoryInGameOnly && !bedwarsActive(example)) return null;
        float scale = cfg.inventoryHudScale;
        float width = invPanelWidth() * scale;
        float height = invPanelHeight() * scale;
        ScaledResolution r = new ScaledResolution(mc);
        float x = absoluteX(cfg.inventoryHudX, cfg.inventoryHudAnchor, width, r.getScaledWidth());
        float y = absoluteY(cfg.inventoryHudY, cfg.inventoryHudAnchor, height, r.getScaledHeight());
        return new HudBox(INVENTORY_HUD, "Inventory", x, y, width, height);
    }

    private static void drawInventoryHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = inventoryBox(mc, cfg, example);
        if (box == null) return;
        // The panel + recessed slot grid are drawn inside drawMiniInventory's scaled matrix, so every
        // gap (the panel margin and the inter-slot gutter alike) lives in one local space and scales as one.
        drawMiniInventory(mc, cfg, box.x, box.y, example);
    }

    private static void drawMiniInventory(Minecraft mc, ClientSettings cfg, float x, float y, boolean example) {
        float scale = cfg.inventoryHudScale;
        ItemStack[] slots = miniInventorySlots(mc, example);

        RenderItem ri = mc.getRenderItem();
        FontRenderer fr = mc.fontRendererObj;
        if (ri == null) return;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0f);
        GlStateManager.scale(scale, scale, 1f);
        // With the Background on, draw the dark panel and the recessed slot grid first (flat shapes,
        // self-contained GL state) so the items render on top. Panel margin and inter-slot gutters are
        // all INV_GAP, so the spacing reads as one consistent system.
        if (cfg.inventoryBackgroundEnabled) drawInventoryPanel();
        GlStateManager.enableDepth();
        GlStateManager.enableRescaleNormal();
        RenderHelper.enableGUIStandardItemLighting();
        float prevZ = ri.zLevel;
        ri.zLevel = 200f;
        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = slots[i];
            if (stack == null) continue;
            // Center the 16px item inside its tile: tile origin (INV_GAP + col*INV_PITCH) + INV_ITEM_PAD.
            int px = Math.round(INV_GAP + INV_ITEM_PAD + (i % INV_COLS) * INV_PITCH);
            int py = Math.round(INV_GAP + INV_ITEM_PAD + (i / INV_COLS) * INV_PITCH);
            ri.renderItemIntoGUI(stack, px, py);
            if (fr != null) ri.renderItemOverlayIntoGUI(fr, stack, px, py, null);
        }
        ri.zLevel = prevZ;
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.popMatrix();
        resetGlState();
    }

    /** Local-space (pre-scale) extent of the whole inventory panel, gutters included. */
    private static float invPanelWidth() { return INV_COLS * INV_PITCH + INV_GAP; }
    private static float invPanelHeight() { return INV_ROWS * INV_PITCH + INV_GAP; }

    /**
     * The Background panel: a flat dark fill (matching every other module) plus a 9x3 grid of recessed
     * slot tiles. Everything is laid out from INV_GAP/INV_PITCH, so the outer panel margin equals the
     * gutter between tiles. Drawn in the inventory HUD's local (pre-scaled) space.
     */
    private static void drawInventoryPanel() {
        GuiRender.roundedRect(0f, 0f, invPanelWidth(), invPanelHeight(), Theme.CARD_R, HUD_BG_FILL);
        for (int r = 0; r < INV_ROWS; r++) {
            for (int c = 0; c < INV_COLS; c++) {
                drawSlotTile(INV_GAP + c * INV_PITCH, INV_GAP + r * INV_PITCH);
            }
        }
    }

    /**
     * One recessed slot tile at local (tx, ty): a faint light face, a darker top/left inner shadow and a
     * whisper-light bottom/right highlight — the vanilla "pressed-in" slot look, in subtle dark-theme
     * overlays. 1px edges (local units) keep it crisp without a heavy border.
     */
    private static void drawSlotTile(float tx, float ty) {
        float x2 = tx + INV_TILE, y2 = ty + INV_TILE;
        GuiRender.rect(tx, ty, x2, y2, INV_SLOT_FILL);             // tile face
        GuiRender.rect(tx, ty, x2, ty + 1f, INV_SLOT_SHADOW);      // top inner shadow
        GuiRender.rect(tx, ty, tx + 1f, y2, INV_SLOT_SHADOW);      // left inner shadow
        GuiRender.rect(tx, y2 - 1f, x2, y2, INV_SLOT_HIGHLIGHT);   // bottom highlight
        GuiRender.rect(x2 - 1f, ty, x2, y2, INV_SLOT_HIGHLIGHT);   // right highlight
    }

    /** The three storage rows of the inventory (main slots 9..35). The hotbar is excluded. */
    private static ItemStack[] miniInventorySlots(Minecraft mc, boolean example) {
        ItemStack[] out = new ItemStack[INV_COLS * INV_ROWS];
        if (example || mc.thePlayer == null) {
            out[0] = new ItemStack(Items.diamond_pickaxe);
            out[1] = new ItemStack(Items.shears);
            out[2] = new ItemStack(Items.bread, 6);
            out[8] = new ItemStack(Items.arrow, 8);
            out[9] = new ItemStack(Items.golden_apple, 2);
            out[INV_COLS + 1] = new ItemStack(Items.iron_ingot, 32);
            out[INV_COLS + 2] = new ItemStack(Items.gold_ingot, 16);
            out[2 * INV_COLS] = new ItemStack(Items.diamond, 4);
            return out;
        }
        ItemStack[] main = mc.thePlayer.inventory.mainInventory;
        for (int i = 0; i < out.length; i++) {
            out[i] = main[9 + i]; // skip the 9 hotbar slots
        }
        return out;
    }

    private static HudBox timerBox(Minecraft mc, ClientSettings cfg, boolean example, boolean diamond) {
        boolean enabled = cfg.genTimersEnabled;
        if (!enabled || !bedwarsActive(example)) return null;
        float scale = diamond ? cfg.diamondTimerHudScale : cfg.emeraldTimerHudScale;
        Size size = timerSize(mc, cfg, example, diamond);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution r = new ScaledResolution(mc);
        int storedX = diamond ? cfg.diamondTimerHudX : cfg.emeraldTimerHudX;
        int storedY = diamond ? cfg.diamondTimerHudY : cfg.emeraldTimerHudY;
        int anchor = diamond ? cfg.diamondTimerHudAnchor : cfg.emeraldTimerHudAnchor;
        float x = absoluteX(storedX, anchor, size.width, r.getScaledWidth());
        float y = absoluteY(storedY, anchor, size.height, r.getScaledHeight());
        return new HudBox(diamond ? DIAMOND_TIMER_HUD : EMERALD_TIMER_HUD,
                diamond ? "Diamond Timer" : "Emerald Timer", x, y, size.width, size.height);
    }

    private static Size timerSize(Minecraft mc, ClientSettings cfg, boolean example, boolean diamond) {
        float scale = diamond ? cfg.diamondTimerHudScale : cfg.emeraldTimerHudScale;
        if (cfg.hudDisplayMode == 1) {
            return iconCountsSize(mc, Collections.singletonList(timerEntry(example, diamond)), scale);
        }
        return textSize(mc.fontRendererObj, Collections.singletonList(timerLine(example, diamond)), scale);
    }

    private static void drawTimerHud(Minecraft mc, ClientSettings cfg, boolean example, boolean diamond) {
        HudBox box = timerBox(mc, cfg, example, diamond);
        if (box == null) return;
        float scale = diamond ? cfg.diamondTimerHudScale : cfg.emeraldTimerHudScale;
        // One shared "Gen Timers" toggle gates both boxes; each draws its own matching panel.
        if (cfg.genTimersBackgroundEnabled) {
            // Icon mode: one chip hugging the countdown digits (not the gem icon). Text mode is all text.
            if (cfg.hudDisplayMode == 1)
                drawTextBgChips(box, ICON_SIZE, Collections.singletonList(timerValue(example, diamond)), scale);
            else drawHudBackground(box, scale);
        }
        if (cfg.hudDisplayMode == 1) {
            drawIconCounts(mc, Collections.singletonList(timerEntry(example, diamond)), box.x, box.y, scale);
        } else {
            drawLines(mc.fontRendererObj, Collections.singletonList(timerLine(example, diamond)), box.x, box.y, scale);
        }
    }

    private static IconCount timerEntry(boolean example, boolean diamond) {
        return new IconCount(new ItemStack(diamond ? Items.diamond : Items.emerald), timerValue(example, diamond));
    }

    private static Line timerLine(boolean example, boolean diamond) {
        return new Line(diamond ? "Diamond" : "Emerald", timerValue(example, diamond));
    }

    /** Just the spawn countdown, e.g. "23s" — rendered to the right of the gem icon. */
    private static String timerValue(boolean example, boolean diamond) {
        if (example) return diamond ? "23s" : "47s";
        int s = diamond ? GeneratorTracker.diamondSeconds() : GeneratorTracker.emeraldSeconds();
        return s < 0 ? "--" : s + "s";
    }

    private static void drawIconCounts(Minecraft mc, List<IconCount> entries, float x, float y, float scale) {
        if (entries.isEmpty()) return;
        RenderItem ri = mc.getRenderItem();
        float iconSize = ICON_SIZE * scale;
        float lineStep = iconSize + LINE_GAP * scale;
        float gap = POTION_ICON_TIMER_GAP * scale;

        if (ri != null) {
            GlStateManager.pushMatrix();
            GlStateManager.enableDepth();
            GlStateManager.enableRescaleNormal();
            RenderHelper.enableGUIStandardItemLighting();
            float prevZ = ri.zLevel;
            ri.zLevel = 200f;
            for (int i = 0; i < entries.size(); i++) {
                GlStateManager.pushMatrix();
                GlStateManager.translate(x, y + i * lineStep, 0f);
                GlStateManager.scale(scale, scale, 1f);
                ri.renderItemIntoGUI(entries.get(i).stack, 0, 0);
                GlStateManager.popMatrix();
            }
            ri.zLevel = prevZ;
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.popMatrix();
            resetGlState();
        }

        FontRenderer fr = mc.fontRendererObj;
        if (fr != null) {
            for (int i = 0; i < entries.size(); i++) {
                String count = entries.get(i).count;
                if (count.isEmpty()) continue;
                float ty = y + i * lineStep + (iconSize - TEXT_HEIGHT * scale) / 2f;
                drawScaledString(fr, count, x + iconSize + gap, ty, scale);
            }
        }
    }

    private static Size iconCountsSize(Minecraft mc, List<IconCount> entries, float scale) {
        if (entries.isEmpty()) return Size.EMPTY;
        float iconSize = ICON_SIZE * scale;
        float gap = POTION_ICON_TIMER_GAP * scale;
        float maxText = 0f;
        FontRenderer fr = mc.fontRendererObj;
        if (fr != null) {
            for (IconCount e : entries) {
                if (!e.count.isEmpty()) maxText = Math.max(maxText, fontWidth(e.count) * scale);
            }
        }
        float width = iconSize + (maxText > 0f ? gap + maxText : 0f);
        float height = entries.size() * iconSize + Math.max(0, entries.size() - 1) * (LINE_GAP * scale);
        return new Size(width, height);
    }

    private static void resetGlState() {
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableLighting();
    }

    // ----- Session Stats (Lunar-style game + session tally) -----

    private static HudBox sessionBox(Minecraft mc, ClientSettings cfg, boolean example) {
        if (!SessionStatsWatch.visible(cfg, example)) return null;
        float scale = cfg.sessionStatsHudScale;
        Size size = textSize(mc.fontRendererObj, sessionLines(example), scale);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution r = new ScaledResolution(mc);
        float x = absoluteX(cfg.sessionStatsHudX, cfg.sessionStatsHudAnchor, size.width, r.getScaledWidth());
        float y = absoluteY(cfg.sessionStatsHudY, cfg.sessionStatsHudAnchor, size.height, r.getScaledHeight());
        return new HudBox(SESSION_HUD, "Session Stats", x, y, size.width, size.height);
    }

    private static void drawSessionHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = sessionBox(mc, cfg, example);
        if (box == null) return;
        float scale = cfg.sessionStatsHudScale;
        drawHudBackground(box, scale); // always on: the 13-row panel is unreadable over the world without it
        // Text rows whichever display mode the other modules use; each row carries its own weight.
        drawLines(mc.fontRendererObj, sessionLines(example), box.x, box.y, scale);
    }

    /** Weight of every session row that is not a header; headers are SemiBold. */
    private static final BedwarsQolFont.Weight SESSION_ROW_WEIGHT = BedwarsQolFont.Weight.REGULAR;

    /**
     * Lunar's Hypixel Bedwars panel, row for row: a SemiBold "Game" header over finals/beds/kills,
     * a SemiBold "Session" header over the four counter/ratio rows, a blank spacer, then winstreak,
     * games and the clock. Every row is one string at one size; no colours.
     */
    private static List<Line> sessionLines(boolean example) {
        SessionStats s = example ? null : SessionStatsWatch.core();
        int gFinals = example ? 1 : s.gameFinals();
        int gBeds = example ? 1 : s.gameBeds();
        int gKills = example ? 3 : s.gameKills();
        String finals = example ? "12 / FKDR: 3.00" : s.finalKills() + " / FKDR: " + SessionStats.formatRatio(s.fkdr());
        String beds = example ? "5 / BBLR: 2.50" : s.beds() + " / BBLR: " + SessionStats.formatRatio(s.bblr());
        String kills = example ? "40 / KDR: 2.00" : s.kills() + " / KDR: " + SessionStats.formatRatio(s.kdr());
        String wins = example ? "3 / WLR: 3.00" : s.wins() + " / WLR: " + SessionStats.formatRatio(s.wlr());
        int streak = example ? 2 : s.winstreak();
        int games = example ? 4 : s.games();
        String time = example ? "12:34" : s.elapsed(System.currentTimeMillis());

        List<Line> out = new ArrayList<>(13);
        out.add(new Line("Game", BedwarsQolFont.Weight.BOLD));
        out.add(new Line("Finals: " + gFinals, SESSION_ROW_WEIGHT));
        out.add(new Line("Beds: " + gBeds, SESSION_ROW_WEIGHT));
        out.add(new Line("Kills: " + gKills, SESSION_ROW_WEIGHT));
        out.add(new Line("Session", BedwarsQolFont.Weight.BOLD));
        out.add(new Line("Finals: " + finals, SESSION_ROW_WEIGHT));
        out.add(new Line("Beds: " + beds, SESSION_ROW_WEIGHT));
        out.add(new Line("Kills: " + kills, SESSION_ROW_WEIGHT));
        out.add(new Line("Wins: " + wins, SESSION_ROW_WEIGHT));
        out.add(new Line("", SESSION_ROW_WEIGHT));
        out.add(new Line("Winstreak: " + streak, SESSION_ROW_WEIGHT));
        out.add(new Line("Session Games: " + games, SESSION_ROW_WEIGHT));
        out.add(new Line("Session Time: " + time, SESSION_ROW_WEIGHT));
        return out;
    }

    public static final class HudBox {
        public final String id;
        public final String title;
        public final float x;
        public final float y;
        public final float width;
        public final float height;

        public HudBox(String id, String title, float x, float y, float width, float height) {
            this.id = id;
            this.title = title;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public float right() {
            return x + width;
        }

        public float bottom() {
            return y + height;
        }

        public float centerX() {
            return x + width / 2f;
        }

        public float centerY() {
            return y + height / 2f;
        }

        public boolean contains(float px, float py) {
            return px >= x && px <= right() && py >= y && py <= bottom();
        }
    }

    private static final class Size {
        static final Size EMPTY = new Size(0f, 0f);

        final float width;
        final float height;

        Size(float width, float height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class Line {
        final String primary;
        final String secondary;
        /** Weight the row is measured and drawn in; SemiBold unless a module asks otherwise. */
        final BedwarsQolFont.Weight weight;

        Line(String primary) {
            this(primary, "");
        }

        Line(String primary, String secondary) {
            this(primary, secondary, BedwarsQolFont.Weight.BOLD);
        }

        Line(String primary, BedwarsQolFont.Weight weight) {
            this(primary, "", weight);
        }

        Line(String primary, String secondary, BedwarsQolFont.Weight weight) {
            this.primary = primary == null ? "" : primary;
            this.secondary = secondary == null ? "" : secondary;
            this.weight = weight == null ? BedwarsQolFont.Weight.BOLD : weight;
        }
    }

    private static final class IconCount {
        final ItemStack stack;
        final String count;

        IconCount(ItemStack stack, String count) {
            this.stack = stack;
            this.count = count == null ? "" : count;
        }
    }
}
