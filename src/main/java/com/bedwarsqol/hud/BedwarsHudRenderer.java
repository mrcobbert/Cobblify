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
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.gui.render.GuiBlur;
import com.bedwarsqol.gui.render.GuiRender;
import com.bedwarsqol.gui.render.Theme;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class BedwarsHudRenderer {

    public static final String POTION_HUD = "potion";
    public static final String ARMOR_HUD = "armor";
    public static final String INVENTORY_HUD = "inventory";
    public static final String DIAMOND_TIMER_HUD = "diamondtimer";
    public static final String SESSION_HUD = "sessionstats";
    public static final String HEIGHT_LIMIT_HUD = "heightlimit";
    public static final String EMERALD_TIMER_HUD = "emeraldtimer";
    public static final String KEYSTROKES_HUD = "keystrokes";
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

    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
    private static final ResourceLocation INVENTORY_TEXTURE = new ResourceLocation("textures/gui/container/inventory.png");
    private static final float TEXT_HEIGHT = 9f;
    private static final float LINE_GAP = 2f;
    private static final float SECONDARY_SCALE = 0.75f;
    private static final float SECONDARY_GAP = 2f;
    private static final float POTION_ICON_SIZE = 18f;
    private static final float POTION_ICON_TIMER_GAP = 3f;
    private static final float ARMOR_ICON_SIZE = 16f;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    // Keystrokes: WASD over a spacebar, as rounded grayscale key caps that invert when pressed.
    // Geometry is in local units (multiplied by the HUD scale at draw time).
    private static final float KS_UNIT = 18f;     // square key cap side
    private static final float KS_GAP = 2f;       // gap between caps
    private static final float KS_SPACE_H = 14f;  // spacebar height (a bit shorter than the WASD caps)
    private static final float KS_LETTER = 1.0f;  // letter scale within a cap
    // Qualified ref (HUD_BG_FILL is declared further down) — tracks the (now darker) HUD background panel.
    private static final int KS_FILL_OFF = BedwarsHudRenderer.HUD_BG_FILL;  // idle cap over the world
    private static final int KS_FILL_ON = 0x40FFFFFF;        // subtle highlight cap (pressed)
    private static final int KS_TEXT_OFF = 0xFFEDEDED;   // light letter on dark cap
    private static final int KS_TEXT_ON = 0xFFFFFFFF;    // letter brightens when pressed

    // ----- Optional per-module "Background" panel -----
    // A flat, square-cornered translucent dark panel behind a HUD element (no border, no corner
    // radius). One consistent grayscale treatment for every module, built on Theme.PANEL_HUD — the
    // HUD-overlay fill the mod's palette reserves for over-the-world panels (scoreboard/tab list).
    // Padding scales with the element so it stays proportionate.
    private static final int HUD_BG_FILL = 0xD0121212;   // darker than Theme.PANEL_HUD (0xB01A1A1A): more opaque (alpha 0xB0->0xD0) + lower RGB

    @SubscribeEvent
    public void onRenderText(RenderGameOverlayEvent.Text event) {
        Minecraft mc = Minecraft.getMinecraft();
        ClientSettings cfg = BedwarsQol.config;
        if (mc == null || mc.thePlayer == null || cfg == null) return;
        if (mc.gameSettings != null && mc.gameSettings.showDebugInfo) return;

        cfg.sanitize();
        render(mc, cfg, false);
    }

    @SubscribeEvent
    public void onPreOverlay(RenderGameOverlayEvent.Pre event) {
        // Settings GUI open (its world-blur is up): hide the ENTIRE in-game HUD — vanilla chat, scoreboard,
        // hotbar AND our own overlays — so the blurred backdrop stays clean instead of showing a smeared HUD.
        if (GuiBlur.isActive()) event.setCanceled(true);
    }

    public static void renderEditPreview(Minecraft mc, ClientSettings cfg) {
        render(mc, cfg, true);
    }

    public static List<HudBox> getHudBoxes(Minecraft mc, ClientSettings cfg, boolean example) {
        if (mc == null || cfg == null) return Collections.emptyList();
        cfg.sanitize();
        hudVanillaFont = cfg.hudFont == 1;

        List<HudBox> boxes = new ArrayList<>(8);
        addBox(boxes, potionBox(mc, cfg, example));
        addBox(boxes, armorBox(mc, cfg, example));
        addBox(boxes, inventoryBox(mc, cfg, example));
        addBox(boxes, timerBox(mc, cfg, example, true));
        addBox(boxes, timerBox(mc, cfg, example, false));
        addBox(boxes, keystrokesBox(mc, cfg, example));
        addBox(boxes, sessionBox(mc, cfg, example));
        addBox(boxes, heightLimitBox(mc, cfg, example));
        return boxes;
    }

    private static void addBox(List<HudBox> boxes, HudBox box) {
        if (box != null) boxes.add(box);
    }

    public static void setHudAbsolutePosition(ClientSettings cfg, String id, float x, float y, float width, float height, float screenWidth, float screenHeight) {
        int anchor = anchorFor(x, y, width, height, screenWidth, screenHeight);
        int storedX = Math.round(x - ANCHOR_X[anchor] * screenWidth + ANCHOR_X[anchor] * width);
        int storedY = Math.round(y - ANCHOR_Y[anchor] * screenHeight + ANCHOR_Y[anchor] * height);
        if (POTION_HUD.equals(id)) {
            cfg.potionHudAnchor = anchor;
            cfg.potionHudX = storedX;
            cfg.potionHudY = storedY;
        } else if (ARMOR_HUD.equals(id)) {
            cfg.armorHudAnchor = anchor;
            cfg.armorHudX = storedX;
            cfg.armorHudY = storedY;
        } else if (INVENTORY_HUD.equals(id)) {
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
        } else if (KEYSTROKES_HUD.equals(id)) {
            cfg.keystrokesHudAnchor = anchor;
            cfg.keystrokesHudX = storedX;
            cfg.keystrokesHudY = storedY;
        } else if (SESSION_HUD.equals(id)) {
            cfg.sessionStatsHudAnchor = anchor;
            cfg.sessionStatsHudX = storedX;
            cfg.sessionStatsHudY = storedY;
        } else if (HEIGHT_LIMIT_HUD.equals(id)) {
            cfg.heightLimitHudAnchor = anchor;
            cfg.heightLimitHudX = storedX;
            cfg.heightLimitHudY = storedY;
        }
    }

    private static void render(Minecraft mc, ClientSettings cfg, boolean example) {
        hudVanillaFont = cfg.hudFont == 1;
        if (cfg.potionStatusEnabled) drawPotionHud(mc, cfg, example);
        if (cfg.armorTypeEnabled) drawArmorHud(mc, cfg, example);
        drawInventoryHud(mc, cfg, example);
        drawTimerHud(mc, cfg, example, true);
        drawTimerHud(mc, cfg, example, false);
        if (cfg.keystrokesEnabled) drawKeystrokesHud(mc, cfg, example);
        drawSessionHud(mc, cfg, example);
        drawHeightLimitHud(mc, cfg, example);
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
     * Per-row background chips for an icon-mode HUD (Potions / Gen Timers): one rounded panel hugging
     * each row's number text — never a single connected column. Each chip wraps that row's actual drawn
     * glyphs (measured in the BOLD weight the numbers render with) with compact, even padding, vertically
     * centred on the visible glyph band so it sits dead-centre on the icon row at any scale and font.
     *
     * <p>Geometry mirrors the text draws ({@link #drawPotionImages} / {@link #drawIconCounts}) exactly:
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

    private static void drawPotionHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = potionBox(mc, cfg, example);
        if (box == null) return;
        if (cfg.hudDisplayMode == 1) {
            List<PotionEntry> entries = potionEntries(mc, example);
            if (entries.isEmpty()) return;
            if (cfg.potionBackgroundEnabled) {
                // One compact chip per effect, hugging that timer's digits — never a single connected panel.
                List<String> timers = new ArrayList<>(entries.size());
                for (PotionEntry e : entries) timers.add(e.timer);
                drawTextBgChips(box, POTION_ICON_SIZE, timers, cfg.potionHudScale);
            }
            drawPotionImages(mc, cfg, entries, box.x, box.y);
            return;
        }

        if (cfg.potionBackgroundEnabled) drawHudBackground(box, cfg.potionHudScale); // text mode: one panel behind the lines
        List<Line> lines = potionLines(mc, example);
        if (!lines.isEmpty()) drawLines(mc.fontRendererObj, lines, box.x, box.y, cfg.potionHudScale);
    }

    private static void drawArmorHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = armorBox(mc, cfg, example);
        if (box == null) return;
        ItemStack leggings = currentLeggings(mc, example);
        if (leggings == null) return;

        if (cfg.hudDisplayMode == 1) {
            drawArmorIcon(mc, cfg, leggings, box.x, box.y);
            return;
        }

        String name = materialName(leggings.getItem());
        if (name != null) {
            drawLines(mc.fontRendererObj, Collections.singletonList(new Line(name)), box.x, box.y, cfg.armorHudScale);
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
            drawScaledString(fr, line.primary, x, ly, scale);
            if (!line.secondary.isEmpty()) {
                float secX = x + fontWidth(line.primary) * scale + gap;
                drawScaledString(fr, line.secondary, secX, ly + secondaryYOffset, secondaryScale);
            }
        }
    }

    private static void drawPotionImages(Minecraft mc, ClientSettings cfg, List<PotionEntry> entries, float x, float y) {
        float scale = cfg.potionHudScale;
        float iconSize = POTION_ICON_SIZE * scale;
        float lineStep = iconSize + LINE_GAP * scale;
        float timerScale = scale; // match the gen-timer numbers (full scale, not the smaller secondary)
        float gap = POTION_ICON_TIMER_GAP * scale;

        mc.getTextureManager().bindTexture(INVENTORY_TEXTURE);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableTexture2D();
        for (int i = 0; i < entries.size(); i++) {
            PotionEntry entry = entries.get(i);
            int idx = entry.potion.getStatusIconIndex();
            if (idx < 0) continue;

            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y + i * lineStep, 0f);
            GlStateManager.scale(scale, scale, 1f);
            Gui.drawModalRectWithCustomSizedTexture(0, 0, idx % 8 * 18, 198 + idx / 8 * 18, 18, 18, 256f, 256f);
            GlStateManager.popMatrix();
        }

        FontRenderer fr = mc.fontRendererObj;
        if (fr != null) {
            for (int i = 0; i < entries.size(); i++) {
                float ty = y + i * lineStep + (iconSize - TEXT_HEIGHT * timerScale) / 2f;
                drawScaledString(fr, entries.get(i).timer, x + iconSize + gap, ty, timerScale);
            }
        }
        resetGlState();
    }

    private static void drawArmorIcon(Minecraft mc, ClientSettings cfg, ItemStack stack, float x, float y) {
        RenderItem renderItem = mc.getRenderItem();
        if (renderItem == null) return;

        GlStateManager.pushMatrix();
        GlStateManager.enableDepth();
        GlStateManager.enableRescaleNormal();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.translate(x, y, 0f);
        GlStateManager.scale(cfg.armorHudScale, cfg.armorHudScale, 1f);
        float prevZ = renderItem.zLevel;
        renderItem.zLevel = 200f;
        renderItem.renderItemIntoGUI(stack, 0, 0);
        renderItem.zLevel = prevZ;
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.popMatrix();
        resetGlState();
    }

    // ----- HUD font: modern Inter atlas (default) or the vanilla Minecraft font, chosen in Settings -----
    // Cached at each render/measure entry (render(), getHudBoxes) so the width + draw helpers agree within
    // a frame. Client rendering is single-threaded, so the shared static is safe.
    private static boolean hudVanillaFont = false;

    /** Width of {@code text} at scale 1.0 in the active HUD font (multiply by your scale at the call site). */
    private static float fontWidth(String text) {
        if (hudVanillaFont) {
            FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;
            return fr == null ? 0f : fr.getStringWidth(text);
        }
        return BedwarsQolFont.width(text);
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
        fontDraw(text, x, y, scale, TEXT_COLOR, BedwarsQolFont.Weight.BOLD);
    }

    private static Size potionSize(Minecraft mc, ClientSettings cfg, boolean example) {
        if (cfg.hudDisplayMode == 1) {
            List<PotionEntry> entries = potionEntries(mc, example);
            if (entries.isEmpty()) return Size.EMPTY;

            float maxTextWidth = 0f;
            FontRenderer fr = mc.fontRendererObj;
            if (fr != null) {
                for (PotionEntry entry : entries) {
                    maxTextWidth = Math.max(maxTextWidth, fontWidth(entry.timer) * cfg.potionHudScale);
                }
            }
            float width = POTION_ICON_SIZE * cfg.potionHudScale + POTION_ICON_TIMER_GAP * cfg.potionHudScale + maxTextWidth;
            float height = entries.size() * (POTION_ICON_SIZE * cfg.potionHudScale)
                    + Math.max(0, entries.size() - 1) * (LINE_GAP * cfg.potionHudScale);
            return new Size(width, height);
        }

        List<Line> lines = potionLines(mc, example);
        return textSize(mc.fontRendererObj, lines, cfg.potionHudScale);
    }

    private static Size armorSize(Minecraft mc, ClientSettings cfg, boolean example) {
        ItemStack leggings = currentLeggings(mc, example);
        if (leggings == null) return Size.EMPTY;
        if (cfg.hudDisplayMode == 1) {
            float size = ARMOR_ICON_SIZE * cfg.armorHudScale;
            return new Size(size, size);
        }

        String name = materialName(leggings.getItem());
        if (name == null) return Size.EMPTY;
        return textSize(mc.fontRendererObj, Collections.singletonList(new Line(name)), cfg.armorHudScale);
    }

    private static HudBox potionBox(Minecraft mc, ClientSettings cfg, boolean example) {
        if (!cfg.potionStatusEnabled) return null;
        if (cfg.potionInGameOnly && !bedwarsActive(example)) return null;
        Size size = potionSize(mc, cfg, example);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution resolution = new ScaledResolution(mc);
        float x = absoluteX(cfg.potionHudX, cfg.potionHudAnchor, size.width, resolution.getScaledWidth());
        float y = absoluteY(cfg.potionHudY, cfg.potionHudAnchor, size.height, resolution.getScaledHeight());
        return new HudBox(POTION_HUD, "Potion HUD", x, y, size.width, size.height);
    }

    private static HudBox armorBox(Minecraft mc, ClientSettings cfg, boolean example) {
        if (!cfg.armorTypeEnabled) return null;
        if (cfg.armorInGameOnly && !bedwarsActive(example)) return null;
        Size size = armorSize(mc, cfg, example);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution resolution = new ScaledResolution(mc);
        float x = absoluteX(cfg.armorHudX, cfg.armorHudAnchor, size.width, resolution.getScaledWidth());
        float y = absoluteY(cfg.armorHudY, cfg.armorHudAnchor, size.height, resolution.getScaledHeight());
        return new HudBox(ARMOR_HUD, "Armor HUD", x, y, size.width, size.height);
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

    private static List<Line> potionLines(Minecraft mc, boolean example) {
        if (example) {
            List<Line> out = new ArrayList<>(2);
            out.add(new Line("Jump Boost I", "0:38"));
            out.add(new Line("Speed II", "1:24"));
            return out;
        }

        Collection<PotionEffect> active = mc.thePlayer.getActivePotionEffects();
        if (active.isEmpty()) return Collections.emptyList();

        List<Line> lines = new ArrayList<>(active.size());
        for (PotionEffect eff : active) {
            Potion potion = potionFor(eff);
            if (potion == null) continue;
            String name = StatCollector.translateToLocal(potion.getName());
            String timer = eff.getIsPotionDurationMax() ? "**:**" : formatTimer(eff.getDuration());
            lines.add(new Line(name + " " + romanNumeral(eff.getAmplifier()), timer));
        }
        lines.sort((a, b) -> Float.compare(lineWidth(mc.fontRendererObj, b), lineWidth(mc.fontRendererObj, a)));
        return lines;
    }

    private static List<PotionEntry> potionEntries(Minecraft mc, boolean example) {
        if (example) {
            List<PotionEntry> out = new ArrayList<>(2);
            out.add(new PotionEntry(Potion.jump, "0:38"));
            out.add(new PotionEntry(Potion.moveSpeed, "1:24"));
            return out;
        }

        Collection<PotionEffect> active = mc.thePlayer.getActivePotionEffects();
        if (active.isEmpty()) return Collections.emptyList();

        List<PotionEntry> entries = new ArrayList<>(active.size());
        for (PotionEffect eff : active) {
            Potion potion = potionFor(eff);
            if (potion == null) continue;
            String timer = eff.getIsPotionDurationMax() ? "**:**" : formatTimer(eff.getDuration());
            entries.add(new PotionEntry(potion, timer));
        }
        return entries;
    }

    private static ItemStack currentLeggings(Minecraft mc, boolean example) {
        if (example) return new ItemStack(Items.diamond_leggings);
        ItemStack leggings = mc.thePlayer.inventory.armorInventory[1];
        if (leggings == null) return null;
        return materialName(leggings.getItem()) == null ? null : leggings;
    }

    private static Potion potionFor(PotionEffect eff) {
        int id = eff.getPotionID();
        if (id < 0 || id >= Potion.potionTypes.length) return null;
        return Potion.potionTypes[id];
    }

    private static float lineWidth(FontRenderer fr, Line line) {
        if (fr == null) return 0f;
        float width = fontWidth(line.primary);
        if (!line.secondary.isEmpty()) {
            width += SECONDARY_GAP + fontWidth(line.secondary) * SECONDARY_SCALE;
        }
        return width;
    }

    private static String romanNumeral(int amplifier) {
        int level = amplifier + 1;
        if (level >= 1 && level <= 10) return ROMAN[level - 1];
        return String.valueOf(level);
    }

    private static String formatTimer(int durationTicks) {
        int seconds = durationTicks / 20;
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    private static String materialName(Item item) {
        if (item == Items.iron_leggings) return "Iron";
        if (item == Items.diamond_leggings) return "Diamond";
        if (item == Items.golden_leggings) return "Gold";
        if (item == Items.leather_leggings) return "Leather";
        if (item == Items.chainmail_leggings) return "Chainmail";
        return null;
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
                drawTextBgChips(box, ARMOR_ICON_SIZE, Collections.singletonList(timerValue(example, diamond)), scale);
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

    // ----- Height Limit (Lunar-style map / build limit / blocks left) -----

    private static HudBox heightLimitBox(Minecraft mc, ClientSettings cfg, boolean example) {
        if (!HeightLimitWatch.visible(cfg, example)) return null;
        float scale = cfg.heightLimitHudScale;
        Size size = textSize(mc.fontRendererObj, heightLimitLines(mc, example), scale);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution r = new ScaledResolution(mc);
        float x = absoluteX(cfg.heightLimitHudX, cfg.heightLimitHudAnchor, size.width, r.getScaledWidth());
        float y = absoluteY(cfg.heightLimitHudY, cfg.heightLimitHudAnchor, size.height, r.getScaledHeight());
        return new HudBox(HEIGHT_LIMIT_HUD, "Height Limit", x, y, size.width, size.height);
    }

    private static void drawHeightLimitHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = heightLimitBox(mc, cfg, example);
        if (box == null) return;
        float scale = cfg.heightLimitHudScale;
        if (cfg.heightLimitBackgroundEnabled) drawHudBackground(box, scale);
        drawLines(mc.fontRendererObj, heightLimitLines(mc, example), box.x, box.y, scale);
    }

    /**
     * Map / Height Limit / Distance, the three rows of Lunar's Height Limit HUD. The limit is the
     * highest Y a block can be placed at; Distance is how many blocks your feet level sits below it.
     * Unknown values (a map not yet in the table and not yet learned) read "?".
     */
    private static List<Line> heightLimitLines(Minecraft mc, boolean example) {
        List<Line> out = new ArrayList<>(3);
        if (example) {
            out.add(new Line("Map", "Lighthouse"));
            out.add(new Line("Height Limit", "110"));
            out.add(new Line("Distance", "27"));
            return out;
        }
        HeightLimitCore core = HeightLimitWatch.core();
        String map = core.map();
        int limit = core.limit();
        int distance = mc.thePlayer == null ? -1 : core.distance(mc.thePlayer.posY);
        out.add(new Line("Map", map == null ? "?" : map));
        out.add(new Line("Height Limit", limit < 0 ? "?" : Integer.toString(limit)));
        out.add(new Line("Distance", distance < 0 ? "?" : Integer.toString(distance)));
        return out;
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
        if (cfg.sessionStatsBackgroundEnabled) drawHudBackground(box, scale);
        // Numeric panel: always text lines, whichever display mode the other modules use.
        drawLines(mc.fontRendererObj, sessionLines(example), box.x, box.y, scale);
    }

    /** Game block over session block, mirroring Lunar's Hypixel Bedwars panel. */
    private static List<Line> sessionLines(boolean example) {
        List<Line> out = new ArrayList<>(5);
        if (example) {
            out.add(new Line("Game", "Kills 3  Finals 1  Beds 1"));
            out.add(new Line("Session", "12:34"));
            out.add(new Line("W/L", "3 / 1  WLR 3.00"));
            out.add(new Line("FK/FD", "12 / 4  FKDR 3.00"));
            out.add(new Line("Beds", "5"));
            return out;
        }
        SessionStats s = SessionStatsWatch.core();
        out.add(new Line("Game", "Kills " + s.gameKills() + "  Finals " + s.gameFinals() + "  Beds " + s.gameBeds()));
        out.add(new Line("Session", s.elapsed(System.currentTimeMillis())));
        out.add(new Line("W/L", s.wins() + " / " + s.losses() + "  WLR " + SessionStats.formatRatio(s.wlr())));
        out.add(new Line("FK/FD", s.finalKills() + " / " + s.finalDeaths() + "  FKDR " + SessionStats.formatRatio(s.fkdr())));
        out.add(new Line("Beds", Integer.toString(s.beds())));
        return out;
    }

    // ----- Keystrokes (WASD + spacebar) -----

    private static HudBox keystrokesBox(Minecraft mc, ClientSettings cfg, boolean example) {
        if (!cfg.keystrokesEnabled) return null;
        if (cfg.keystrokesInGameOnly && !bedwarsActive(example)) return null;
        float scale = cfg.keystrokesHudScale;
        float width = (3f * KS_UNIT + 2f * KS_GAP) * scale;
        float height = (2f * KS_UNIT + 2f * KS_GAP + KS_SPACE_H) * scale;
        ScaledResolution r = new ScaledResolution(mc);
        float x = absoluteX(cfg.keystrokesHudX, cfg.keystrokesHudAnchor, width, r.getScaledWidth());
        float y = absoluteY(cfg.keystrokesHudY, cfg.keystrokesHudAnchor, height, r.getScaledHeight());
        return new HudBox(KEYSTROKES_HUD, "Keystrokes", x, y, width, height);
    }

    private static void drawKeystrokesHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = keystrokesBox(mc, cfg, example);
        if (box == null) return;
        boolean[] st = keyStates(mc, example);
        // Translucent-dark idle caps over the world; pressed caps brighten.
        int idleFill = KS_FILL_OFF;

        // Draw everything in local (unscaled) coordinates under one translate+scale, so the caps and
        // letters scale together through the modelview matrix.
        GlStateManager.pushMatrix();
        GlStateManager.translate(box.x, box.y, 0f);
        GlStateManager.scale(cfg.keystrokesHudScale, cfg.keystrokesHudScale, 1f);

        float col = KS_UNIT + KS_GAP;
        float row2 = KS_UNIT + KS_GAP;
        float row3 = row2 + KS_UNIT + KS_GAP;
        drawKeyCap(col, 0f, KS_UNIT, KS_UNIT, "W", st[0], idleFill);
        drawKeyCap(0f, row2, KS_UNIT, KS_UNIT, "A", st[1], idleFill);
        drawKeyCap(col, row2, KS_UNIT, KS_UNIT, "S", st[2], idleFill);
        drawKeyCap(2f * col, row2, KS_UNIT, KS_UNIT, "D", st[3], idleFill);
        float spaceW = 3f * KS_UNIT + 2f * KS_GAP;
        drawKeyCap(0f, row3, spaceW, KS_SPACE_H, "", st[4], idleFill);
        drawSpaceSymbol(0f, row3, spaceW, KS_SPACE_H, st[4]);

        GlStateManager.popMatrix();
        resetGlState();
    }

    private static void drawKeyCap(float x, float y, float w, float h, String label, boolean pressed, int idleFill) {
        float radius = Math.min(3f, Math.min(w, h) * 0.25f);
        GuiRender.roundedRect(x, y, x + w, y + h, radius, pressed ? KS_FILL_ON : idleFill);
        if (!label.isEmpty()) {
            float tw = fontWidth(label) * KS_LETTER;
            float tx = x + (w - tw) / 2f;
            float ty = y + (h - fontHeight(KS_LETTER)) / 2f;
            fontDraw(label, tx, ty, KS_LETTER, pressed ? KS_TEXT_ON : KS_TEXT_OFF, BedwarsQolFont.Weight.BOLD);
        }
    }

    /** A centered horizontal bar (thin, square corners) standing in for the space key's label. */
    private static void drawSpaceSymbol(float x, float y, float w, float h, boolean pressed) {
        float barW = w * 0.34f;
        float barH = Math.max(1f, h * 0.045f);
        float bx1 = x + (w - barW) / 2f;
        float by1 = y + (h - barH) / 2f;
        GuiRender.rect(bx1, by1, bx1 + barW, by1 + barH, pressed ? KS_TEXT_ON : KS_TEXT_OFF);
    }

    /** {W, A, S, D, Space} held state, following the player's actual movement binds. */
    private static boolean[] keyStates(Minecraft mc, boolean example) {
        if (example || mc.gameSettings == null) return new boolean[]{true, false, false, false, false};
        return new boolean[]{
                isDown(mc.gameSettings.keyBindForward),
                isDown(mc.gameSettings.keyBindLeft),
                isDown(mc.gameSettings.keyBindBack),
                isDown(mc.gameSettings.keyBindRight),
                isDown(mc.gameSettings.keyBindJump)};
    }

    private static boolean isDown(KeyBinding kb) {
        return kb != null && kb.isKeyDown();
    }

    private static void drawIconCounts(Minecraft mc, List<IconCount> entries, float x, float y, float scale) {
        if (entries.isEmpty()) return;
        RenderItem ri = mc.getRenderItem();
        float iconSize = ARMOR_ICON_SIZE * scale;
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
        float iconSize = ARMOR_ICON_SIZE * scale;
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

        Line(String primary) {
            this(primary, "");
        }

        Line(String primary, String secondary) {
            this.primary = primary;
            this.secondary = secondary == null ? "" : secondary;
        }
    }

    private static final class PotionEntry {
        final Potion potion;
        final String timer;

        PotionEntry(Potion potion, String timer) {
            this.potion = potion;
            this.timer = timer;
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
