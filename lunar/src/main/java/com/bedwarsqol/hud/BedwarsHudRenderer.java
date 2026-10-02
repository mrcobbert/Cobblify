package com.bedwarsqol.hud;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.bedwars.GeneratorTracker;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.SessionStats;
import com.bedwarsqol.feature.HeightLimitCore;
import com.bedwarsqol.feature.HeightLimitWatch;
import com.bedwarsqol.feature.SessionStatsWatch;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import com.bedwarsqol.gui.EditHudGui;
import com.bedwarsqol.gui.StatRowSegments;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.gui.render.GuiBlur;
import com.bedwarsqol.gui.render.GuiRender;
import com.bedwarsqol.gui.render.GuiTheme;
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
    public static final String HEIGHT_LIMIT_HUD = "heightlimit";
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

    private static final float TEXT_HEIGHT = 9f;
    private static final float LINE_GAP = 2f;
    private static final float SECONDARY_SCALE = 0.75f;
    private static final float SECONDARY_GAP = 2f;
    private static final float POTION_ICON_TIMER_GAP = 3f;
    private static final float ICON_SIZE = 16f;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    // ----- HUD panel (Height Limit and Session Stats, always on) -----
    // A flat, square-cornered translucent dark panel behind a HUD element (no border, no corner
    // radius). One consistent grayscale treatment, built on Theme.PANEL_HUD — the
    // HUD-overlay fill the mod's palette reserves for over-the-world panels (scoreboard/tab list).
    // Padding scales with the element so it stays proportionate.
    private static final int HUD_BG_FILL = 0xD0121212;   // darker than Theme.PANEL_HUD (0xB01A1A1A): more opaque (alpha 0xB0->0xD0) + lower RGB

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        ClientSettings cfg = BedwarsQol.config;
        if (mc == null || mc.thePlayer == null || cfg == null) return;
        if (GuiBlur.isActive()) return; // settings GUI open: its blurred backdrop replaces the HUD
        if (mc.currentScreen instanceof EditHudGui) return; // the editor draws its own preview
        if (mc.gameSettings != null && mc.gameSettings.showDebugInfo) return;

        cfg.sanitize();
        render(mc, cfg, false);
    }

    public static void renderEditPreview(Minecraft mc, ClientSettings cfg) {
        render(mc, cfg, true);
    }

    /**
     * Every module's box for Edit HUD, with example data and hidden modules included. {@link HudBox#shown}
     * says which ones the player has switched on.
     */
    public static List<HudBox> getEditorBoxes(Minecraft mc, ClientSettings cfg) {
        if (mc == null || cfg == null) return Collections.emptyList();
        cfg.sanitize();
        hudVanillaFont = cfg.hudFont == 1;

        List<HudBox> boxes = new ArrayList<>(8);
        addEditorBox(boxes, cfg, inventoryBox(mc, cfg, true, true));
        addEditorBox(boxes, cfg, timerBox(mc, cfg, true, true, true));
        addEditorBox(boxes, cfg, timerBox(mc, cfg, true, false, true));
        addEditorBox(boxes, cfg, sessionBox(mc, cfg, true, true));
        addEditorBox(boxes, cfg, heightLimitBox(mc, cfg, true, true));
        return boxes;
    }

    private static void addEditorBox(List<HudBox> boxes, ClientSettings cfg, HudBox box) {
        if (box == null) return;
        HudModuleState state = HudModules.get(cfg, box.id);
        boxes.add(state != null && !state.enabled ? box.hidden() : box);
    }

    /** Stores module {@code id} with its top-left at (x, y), anchored to the screen third it is in. */
    public static void setHudAbsolutePosition(ClientSettings cfg, String id, float x, float y, float width, float height, float screenWidth, float screenHeight) {
        HudModuleState state = HudModules.get(cfg, id);
        if (state == null) return;
        int anchor = HudPlacement.anchorFor(x, y, width, height, screenWidth, screenHeight);
        HudModules.set(cfg, id, state.withPosition(HudPlacement.storedX(x, anchor, width, screenWidth),
                HudPlacement.storedY(y, anchor, height, screenHeight), anchor));
    }

    private static void render(Minecraft mc, ClientSettings cfg, boolean example) {
        hudVanillaFont = cfg.hudFont == 1;
        drawInventoryHud(mc, cfg, example);
        drawTimerHud(mc, cfg, example, true);
        drawTimerHud(mc, cfg, example, false);
        drawSessionHud(mc, cfg, example);
        drawHeightLimitHud(mc, cfg, example);
    }

    /**
     * The always-on panel behind Height Limit and Session Stats: a flat, square translucent panel (no
     * corner radius, no border) behind a HUD element's content box expanded by a scale-aware padding.
     * Drawn in GUI space BEFORE the element's content (and before any {@code pushMatrix}/scale used for
     * item rendering).
     * {@code GuiRender.rect} is self-contained for GL state, so the content draw that follows gets a
     * clean (texturing on, color white) state.
     */
    /** Padding of the panel around a HUD element's content box: it grows with the element. */
    private static float panelPad(float scale) {
        return Math.max(2, Math.round(4f * scale));
    }

    private static void drawHudBackground(HudBox box, float scale, int fill) {
        float pad = panelPad(scale);
        float x1 = box.x - pad, y1 = box.y - pad;
        float x2 = box.right() + pad, y2 = box.bottom() + pad;
        GuiRender.roundedRect(x1, y1, x2, y2, Theme.CARD_R, fill);
    }

    private static void drawHudBackground(HudBox box, float scale) {
        drawHudBackground(box, scale, HUD_BG_FILL);
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
    // Cached at each render/measure entry (render(), getEditorBoxes) so the width + draw helpers agree within
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

    /** Left edge from a stored placement, pulled fully on screen with {@code pad} around the box. */
    private static float placeX(float storedX, int anchor, float width, float pad, float screenWidth) {
        return HudPlacement.clamp(HudPlacement.absoluteX(storedX, anchor, width, screenWidth), width, pad, screenWidth);
    }

    /** Top edge from a stored placement, pulled fully on screen with {@code pad} around the box. */
    private static float placeY(float storedY, int anchor, float height, float pad, float screenHeight) {
        return HudPlacement.clamp(HudPlacement.absoluteY(storedY, anchor, height, screenHeight), height, pad, screenHeight);
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

    /** Whether a HUD's "In Game Only" is satisfied: an active BedWars game, or the edit preview. */
    private static boolean bedwarsActive(boolean example) {
        return example || HypixelContext.isInActiveBedwarsGame();
    }

    private static HudBox inventoryBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !cfg.inventoryHudEnabled) return null; // works anywhere unless "In Game Only" is set
        if (!all && cfg.inventoryInGameOnly && !bedwarsActive(example)) return null;
        float scale = cfg.inventoryHudScale;
        float width = invPanelWidth() * scale;
        float height = invPanelHeight() * scale;
        ScaledResolution r = new ScaledResolution(mc);
        float x = placeX(cfg.inventoryHudX, cfg.inventoryHudAnchor, width, 0f, r.getScaledWidth());
        float y = placeY(cfg.inventoryHudY, cfg.inventoryHudAnchor, height, 0f, r.getScaledHeight());
        return new HudBox(INVENTORY_HUD, x, y, width, height, 0f);
    }

    private static void drawInventoryHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = inventoryBox(mc, cfg, example, false);
        if (box == null) return;
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

    private static HudBox timerBox(Minecraft mc, ClientSettings cfg, boolean example, boolean diamond, boolean all) {
        if (!all && !cfg.genTimersEnabled) return null; // works anywhere unless "In Game Only" is set
        if (!all && cfg.genTimersInGameOnly && !bedwarsActive(example)) return null;
        float scale = diamond ? cfg.diamondTimerHudScale : cfg.emeraldTimerHudScale;
        Size size = timerSize(mc, cfg, example, diamond);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution r = new ScaledResolution(mc);
        int storedX = diamond ? cfg.diamondTimerHudX : cfg.emeraldTimerHudX;
        int storedY = diamond ? cfg.diamondTimerHudY : cfg.emeraldTimerHudY;
        int anchor = diamond ? cfg.diamondTimerHudAnchor : cfg.emeraldTimerHudAnchor;
        float x = placeX(storedX, anchor, size.width, 0f, r.getScaledWidth());
        float y = placeY(storedY, anchor, size.height, 0f, r.getScaledHeight());
        return new HudBox(diamond ? DIAMOND_TIMER_HUD : EMERALD_TIMER_HUD, x, y, size.width, size.height, 0f);
    }

    private static Size timerSize(Minecraft mc, ClientSettings cfg, boolean example, boolean diamond) {
        float scale = diamond ? cfg.diamondTimerHudScale : cfg.emeraldTimerHudScale;
        if (cfg.hudDisplayMode == 1) {
            return iconCountsSize(mc, Collections.singletonList(timerEntry(example, diamond)), scale);
        }
        return textSize(mc.fontRendererObj, Collections.singletonList(timerLine(example, diamond)), scale);
    }

    private static void drawTimerHud(Minecraft mc, ClientSettings cfg, boolean example, boolean diamond) {
        HudBox box = timerBox(mc, cfg, example, diamond, false);
        if (box == null) return;
        float scale = diamond ? cfg.diamondTimerHudScale : cfg.emeraldTimerHudScale;
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

    // ----- Height Limit (Lunar-style Map / Height Limit / Distance rows) -----

    /** Rows are Inter Regular like the Session Stats rows; the panel is always drawn. */
    private static final BedwarsQolFont.Weight HEIGHT_ROW_WEIGHT = BedwarsQolFont.Weight.REGULAR;

    private static HudBox heightLimitBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !HeightLimitWatch.visible(cfg, example)) return null;
        float scale = cfg.heightLimitHudScale;
        List<String> rows = heightLimitRows(mc, example);
        float width = 0f;
        for (String row : rows) width = Math.max(width, fontWidth(row, HEIGHT_ROW_WEIGHT) * scale);
        float height = rows.size() * ((TEXT_HEIGHT + LINE_GAP) * scale) - LINE_GAP * scale;
        if (width <= 0f || height <= 0f) return null;
        ScaledResolution r = new ScaledResolution(mc);
        float pad = panelPad(cfg.heightLimitHudScale);
        float x = placeX(cfg.heightLimitHudX, cfg.heightLimitHudAnchor, width, pad, r.getScaledWidth());
        float y = placeY(cfg.heightLimitHudY, cfg.heightLimitHudAnchor, height, pad, r.getScaledHeight());
        return new HudBox(HEIGHT_LIMIT_HUD, x, y, width, height, pad);
    }

    private static void drawHeightLimitHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = heightLimitBox(mc, cfg, example, false);
        if (box == null) return;
        float scale = cfg.heightLimitHudScale;
        drawHudBackground(box, scale);
        float step = (TEXT_HEIGHT + LINE_GAP) * scale;
        List<String> rows = heightLimitRows(mc, example);
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            drawStatRow(row, alignedX(mc, box, row, scale, HEIGHT_ROW_WEIGHT), box.y + i * step, scale, HEIGHT_ROW_WEIGHT);
        }
    }

    /** Neutral stat colours that sit with any accent: values white, labels light gray, {@code " / "} dark gray. */
    private static final int STAT_VALUE_COLOR = 0xFFFFFFFF;
    private static final int STAT_LABEL_COLOR = 0xFFAAAAAA;
    private static final int STAT_SLASH_COLOR = 0xFF666666;

    /** One {@code Label: value [/ Label: value]} row, coloured run by run. */
    private static void drawStatRow(String row, float x, float y, float scale, BedwarsQolFont.Weight weight) {
        float cx = x;
        for (StatRowSegments.Segment seg : StatRowSegments.split(row)) {
            int color = seg.kind == StatRowSegments.Kind.VALUE ? STAT_VALUE_COLOR
                    : seg.kind == StatRowSegments.Kind.SEPARATOR ? STAT_SLASH_COLOR : STAT_LABEL_COLOR;
            cx = drawSegment(seg.text, cx, y, scale, color, weight);
        }
    }

    private static float drawSegment(String text, float x, float y, float scale, int color, BedwarsQolFont.Weight weight) {
        fontDraw(text, x, y, scale, color, weight);
        return x + fontWidth(text, weight) * scale;
    }

    /** Rows hug the box's left edge on the left half of the screen and its right edge on the right half. */
    private static float alignedX(Minecraft mc, HudBox box, String row, float scale, BedwarsQolFont.Weight weight) {
        float screenW = new ScaledResolution(mc).getScaledWidth();
        if (box.centerX() <= screenW / 2f) return box.x;
        return box.right() - fontWidth(row, weight) * scale;
    }

    /**
     * {@code Map: X} / {@code Height Limit: N} / {@code Distance: N}, the three rows of Lunar's Height
     * Limit HUD. The limit is the highest Y a block can be placed at; Distance is how many blocks your
     * feet level sits below it. Unknown values (a map not yet in the table and not yet learned) read "?".
     */
    private static List<String> heightLimitRows(Minecraft mc, boolean example) {
        List<String> out = new ArrayList<>(3);
        if (example) {
            out.add("Map: Lighthouse");
            out.add("Height Limit: 110");
            out.add("Distance: 27");
            return out;
        }
        HeightLimitCore core = HeightLimitWatch.core();
        String map = core.map();
        int limit = core.limit();
        int distance = mc.thePlayer == null ? -1 : core.distance(mc.thePlayer.posY);
        out.add("Map: " + (map == null ? "?" : map));
        out.add("Height Limit: " + (limit < 0 ? "?" : Integer.toString(limit)));
        out.add("Distance: " + (distance < 0 ? "?" : Integer.toString(distance)));
        return out;
    }

    // ----- Session Stats (Lunar-style game + session tally) -----

    private static HudBox sessionBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !SessionStatsWatch.visible(cfg, example)) return null;
        float scale = cfg.sessionStatsHudScale;
        Size size = textSize(mc.fontRendererObj, sessionLines(example), scale);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution r = new ScaledResolution(mc);
        float pad = panelPad(cfg.sessionStatsHudScale);
        float x = placeX(cfg.sessionStatsHudX, cfg.sessionStatsHudAnchor, size.width, pad, r.getScaledWidth());
        float y = placeY(cfg.sessionStatsHudY, cfg.sessionStatsHudAnchor, size.height, pad, r.getScaledHeight());
        return new HudBox(SESSION_HUD, x, y, size.width, size.height, pad);
    }

    private static void drawSessionHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = sessionBox(mc, cfg, example, false);
        if (box == null) return;
        float scale = cfg.sessionStatsHudScale;
        drawHudBackground(box, scale); // always on: the 13-row panel is unreadable over the world without it
        // Headers in the settings menu's accent, rows in the neutral stat colours; each row keeps its weight.
        int header = GuiTheme.fromToken(cfg.guiAccent).base();
        float step = (TEXT_HEIGHT + LINE_GAP) * scale;
        List<Line> lines = sessionLines(example);
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            float lx = alignedX(mc, box, line.primary, scale, line.weight);
            float ly = box.y + i * step;
            if (line.weight == SESSION_HEADER_WEIGHT) fontDraw(line.primary, lx, ly, scale, header, line.weight);
            else drawStatRow(line.primary, lx, ly, scale, line.weight);
        }
    }

    /** Weight of every session row that is not a header; headers are SemiBold. */
    private static final BedwarsQolFont.Weight SESSION_ROW_WEIGHT = BedwarsQolFont.Weight.REGULAR;
    private static final BedwarsQolFont.Weight SESSION_HEADER_WEIGHT = BedwarsQolFont.Weight.BOLD;

    /**
     * Lunar's Hypixel Bedwars panel, row for row: a SemiBold "Game" header over kills/finals/beds,
     * a SemiBold "Session" header over the four counter/ratio rows, a blank spacer, then winstreak,
     * games and the clock. Every row is one string at one size; drawSessionHud colours it.
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
        out.add(new Line("Game", SESSION_HEADER_WEIGHT));
        out.add(new Line("Kills: " + gKills, SESSION_ROW_WEIGHT));
        out.add(new Line("Finals: " + gFinals, SESSION_ROW_WEIGHT));
        out.add(new Line("Beds: " + gBeds, SESSION_ROW_WEIGHT));
        out.add(new Line("Session", SESSION_HEADER_WEIGHT));
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
        /** Content box: what the module draws, before any panel padding. */
        public final float x;
        public final float y;
        public final float width;
        public final float height;
        /** Panel padding drawn around the content box; 0 for modules without a panel. */
        public final float pad;
        /** False for a module the player has switched off (Edit HUD shows it as a ghost). */
        public final boolean shown;

        public HudBox(String id, float x, float y, float width, float height, float pad) {
            this(id, x, y, width, height, pad, true);
        }

        private HudBox(String id, float x, float y, float width, float height, float pad, boolean shown) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.pad = pad;
            this.shown = shown;
        }

        HudBox hidden() {
            return new HudBox(id, x, y, width, height, pad, false);
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

        /** The box as drawn, panel included: what clicks, snapping and frames use. */
        public float visualX() {
            return x - pad;
        }

        public float visualY() {
            return y - pad;
        }

        public float visualRight() {
            return x + width + pad;
        }

        public float visualBottom() {
            return y + height + pad;
        }

        public float visualWidth() {
            return width + 2f * pad;
        }

        public float visualHeight() {
            return height + 2f * pad;
        }

        public boolean containsVisual(float px, float py) {
            return px >= visualX() && px <= visualRight() && py >= visualY() && py <= visualBottom();
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
