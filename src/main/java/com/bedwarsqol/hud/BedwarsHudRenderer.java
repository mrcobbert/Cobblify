package com.bedwarsqol.hud;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.bedwars.GeneratorTracker;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.ClickCounter;
import com.bedwarsqol.feature.KeyFade;
import com.bedwarsqol.feature.SessionStats;
import com.bedwarsqol.feature.HeightLimitCore;
import com.bedwarsqol.feature.HeightLimitWatch;
import com.bedwarsqol.feature.SessionStatsWatch;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import com.bedwarsqol.gui.EditHudGui;
import com.bedwarsqol.hud.HudLayout.Horizontal;
import com.bedwarsqol.hud.HudLayout.Slide;
import com.bedwarsqol.hud.HudLayout.Spot;
import com.bedwarsqol.hud.HudLayout.Vertical;
import com.bedwarsqol.gui.StatRowSegments;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.gui.render.GuiBlur;
import com.bedwarsqol.gui.render.GuiRender;
import com.bedwarsqol.gui.render.GuiTheme;
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
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // Keystrokes: WASD, a spacebar and a mouse row (LMB/RMB with CPS), as caps in the HUD panel's
    // look (same fill, corners and white/gray text as Map Info and Session Stats). A pressed cap takes the menu accent and fades back
    // out after release. Geometry is in local units (multiplied by the HUD scale at draw time).
    private static final float KS_UNIT = 18f;     // square key cap side
    private static final float KS_GAP = 2f;       // gap between caps
    private static final float KS_SPACE_H = 14f;  // spacebar height
    private static final float KS_RADIUS = Theme.CARD_R;
    private static final float KS_LETTER = 1.0f;  // letter scale within a cap
    private static final float KS_MOUSE_LABEL = 0.75f; // "LMB" / "RMB"
    private static final float KS_CPS_LABEL = 0.55f;   // the "N CPS" line under it
    private static final long KS_FADE_MS = 120L;
    private static final int KS_FILL_OFF = 0xD0121212;   // HUD_BG_FILL
    private static final int KS_TEXT_OFF = 0xFFFFFFFF;
    private static final int KS_TEXT_ON = 0xFFFFFFFF;
    private static final int KS_CPS_OFF = 0xFFAAAAAA;    // STAT_LABEL_COLOR

    /** Cap order everywhere below: W, A, S, D, Space, LMB, RMB. */
    private static final int KS_W = 0, KS_A = 1, KS_S = 2, KS_D = 3, KS_SPACE = 4, KS_LMB = 5, KS_RMB = 6;
    private static final KeyFade[] KS_FADES = new KeyFade[7];
    static {
        for (int i = 0; i < KS_FADES.length; i++) KS_FADES[i] = new KeyFade(KS_FADE_MS);
    }
    private static final ClickCounter LEFT_CLICKS = new ClickCounter();
    private static final ClickCounter RIGHT_CLICKS = new ClickCounter();

    // ----- HUD panel (Height Limit and Session Stats, always on) -----
    // A flat, square-cornered translucent dark panel behind a HUD element (no border, no corner
    // radius). One consistent grayscale treatment, built on Theme.PANEL_HUD — the
    // HUD-overlay fill the mod's palette reserves for over-the-world panels (scoreboard/tab list).
    // Padding scales with the element so it stays proportionate.
    private static final int HUD_BG_FILL = 0xD0121212;   // darker than Theme.PANEL_HUD (0xB01A1A1A): more opaque (alpha 0xB0->0xD0) + lower RGB

    @SubscribeEvent
    public void onRenderText(RenderGameOverlayEvent.Text event) {
        Minecraft mc = Minecraft.getMinecraft();
        ClientSettings cfg = BedwarsQol.config;
        if (mc == null || mc.thePlayer == null || cfg == null) return;
        if (mc.gameSettings != null && mc.gameSettings.showDebugInfo) return;
        if (mc.currentScreen instanceof EditHudGui) return; // the editor draws its own preview

        cfg.sanitize();
        render(mc, cfg, false);
    }

    /** Counts in-game presses for the Keystrokes CPS line; Forge posts this only with no screen open. */
    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (!event.buttonstate) return;
        long now = Minecraft.getSystemTime();
        if (event.button == 0) {
            LEFT_CLICKS.click(now);
            KS_FADES[KS_LMB].tap(now);
        } else if (event.button == 1) {
            RIGHT_CLICKS.click(now);
            KS_FADES[KS_RMB].tap(now);
        }
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

    /**
     * Every module's box for Edit HUD, with example data and hidden modules included. {@link HudBox#shown}
     * says which ones the player has switched on.
     */
    public static List<HudBox> getEditorBoxes(Minecraft mc, ClientSettings cfg) {
        if (mc == null || cfg == null) return Collections.emptyList();
        cfg.sanitize();
        hudVanillaFont = cfg.hudFont == 1;

        List<HudBox> boxes = new ArrayList<>(8);
        addEditorBox(boxes, cfg, potionBox(mc, cfg, true, true));
        addEditorBox(boxes, cfg, armorBox(mc, cfg, true, true));
        addEditorBox(boxes, cfg, inventoryBox(mc, cfg, true, true));
        addEditorBox(boxes, cfg, timerBox(mc, cfg, true, true, true));
        addEditorBox(boxes, cfg, timerBox(mc, cfg, true, false, true));
        addEditorBox(boxes, cfg, keystrokesBox(mc, cfg, true, true));
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
     * The always-on panel behind Height Limit and Session Stats: a flat, square translucent panel (no
     * corner radius, no border) behind a HUD element's content box expanded by a scale-aware padding.
     * Drawn in GUI space BEFORE the element's content (and before any {@code pushMatrix}/scale used for
     * item rendering).
     * {@code GuiRender.rect} is self-contained for GL state, so the content draw that follows gets a
     * clean (texturing on, color white) state.
     */
    /**
     * Space kept clear around a module without a panel: Edit HUD's frame, snapping and the screen
     * edge stay this far from its text and icons.
     */
    private static final float CONTENT_MARGIN = 3f;

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

    private static void drawPotionHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = potionBox(mc, cfg, example, false);
        if (box == null) return;
        if (cfg.hudDisplayMode == 1) {
            List<PotionEntry> entries = potionEntries(mc, example);
            if (entries.isEmpty()) return;
            drawPotionImages(mc, cfg, entries, box.x, box.y);
            return;
        }

        List<Line> lines = potionLines(mc, example);
        if (!lines.isEmpty()) drawLines(mc.fontRendererObj, lines, box.x, box.y, cfg.potionHudScale);
    }

    private static void drawArmorHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = armorBox(mc, cfg, example, false);
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
            if (line.primary.isEmpty()) continue; // blank spacer row: advances one lineStep, draws nothing
            drawScaledString(fr, line.primary, x, ly, scale, line.weight);
            if (!line.secondary.isEmpty()) {
                float secX = x + fontWidth(line.primary, line.weight) * scale + gap;
                drawScaledString(fr, line.secondary, secX, ly + secondaryYOffset, secondaryScale, line.weight);
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

    private static HudBox potionBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !cfg.potionStatusEnabled) return null;
        if (!all && cfg.potionInGameOnly && !bedwarsActive(example)) return null;
        Size size = potionSize(mc, cfg, example);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution resolution = new ScaledResolution(mc);
        float[] at = place(mc, cfg, POTION_HUD, cfg.potionHudX, cfg.potionHudY, cfg.potionHudAnchor, size.width, size.height, CONTENT_MARGIN, resolution);
        return new HudBox(POTION_HUD, at[0], at[1], size.width, size.height, CONTENT_MARGIN);
    }

    private static HudBox armorBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !cfg.armorTypeEnabled) return null;
        if (!all && cfg.armorInGameOnly && !bedwarsActive(example)) return null;
        Size size = armorSize(mc, cfg, example);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution resolution = new ScaledResolution(mc);
        float[] at = place(mc, cfg, ARMOR_HUD, cfg.armorHudX, cfg.armorHudY, cfg.armorHudAnchor, size.width, size.height, CONTENT_MARGIN, resolution);
        return new HudBox(ARMOR_HUD, at[0], at[1], size.width, size.height, CONTENT_MARGIN);
    }

    /**
     * Top-left of a module's content box: its saved spot, or its place in the default layout while
     * the player has never moved it. Either way it is pulled fully on screen with {@code pad} around it.
     */
    private static float[] place(Minecraft mc, ClientSettings cfg, String id, float storedX, float storedY, int anchor,
                                 float width, float height, float pad, ScaledResolution r) {
        float sw = r.getScaledWidth(), sh = r.getScaledHeight();
        float x, y;
        float[] auto = anchor == HudPlacement.AUTO ? defaultLayout(mc, cfg, sw, sh).get(id) : null;
        if (auto != null) {
            // The layout used example data; hold the edge nearest the screen side, as a saved anchor
            // would, so a timer counting down from "23s" to "9s" keeps its right edge on the right.
            float exX = auto[0] + pad, exY = auto[1] + pad;
            float exW = auto[2] - 2f * pad, exH = auto[3] - 2f * pad;
            int a = HudPlacement.anchorFor(exX, exY, exW, exH, sw, sh);
            x = HudPlacement.absoluteX(HudPlacement.storedX(exX, a, exW, sw), a, width, sw);
            y = HudPlacement.absoluteY(HudPlacement.storedY(exY, a, exH, sh), a, height, sh);
        } else {
            x = HudPlacement.absoluteX(storedX, anchor, width, sw);
            y = HudPlacement.absoluteY(storedY, anchor, height, sh);
        }
        return new float[]{HudPlacement.clamp(x, width, pad, sw), HudPlacement.clamp(y, height, pad, sh)};
    }

    // ----- Default layout: where a module sits until the player moves it -----
    // HudLayout places the modules still in their default spots, in this order, each at the first of
    // its spots that is free: clear of the others, of modules the player moved, and of the vanilla
    // hotbar with the health, armour and food bars above it. Sizes come from example data, so the
    // layout never shifts mid-game, and it follows HUD Size and the window on its own.
    private static final float LAYOUT_EDGE = 2f;
    private static final float LAYOUT_GAP = 2f;
    private static final float HOTBAR_W = 182f;
    private static final float HOTBAR_ZONE_H = 50f;
    private static final Map<String, List<Spot>> DEFAULT_SPOTS = new LinkedHashMap<String, List<Spot>>();
    static {
        DEFAULT_SPOTS.put(DIAMOND_TIMER_HUD, spots(new Spot(Horizontal.RIGHT, Vertical.TOP, Slide.LEFT)));
        DEFAULT_SPOTS.put(EMERALD_TIMER_HUD, spots(new Spot(Horizontal.RIGHT, Vertical.TOP, Slide.LEFT)));
        DEFAULT_SPOTS.put(SESSION_HUD, spots(new Spot(Horizontal.RIGHT, Vertical.TOP, Slide.DOWN),
                new Spot(Horizontal.LEFT, Vertical.TOP, Slide.DOWN)));
        DEFAULT_SPOTS.put(ARMOR_HUD, spots(new Spot(Horizontal.LEFT, Vertical.TOP, Slide.DOWN)));
        DEFAULT_SPOTS.put(POTION_HUD, spots(new Spot(Horizontal.LEFT, Vertical.TOP, Slide.DOWN)));
        DEFAULT_SPOTS.put(KEYSTROKES_HUD, spots(new Spot(Horizontal.RIGHT, Vertical.BOTTOM, Slide.UP),
                new Spot(Horizontal.RIGHT, Vertical.BOTTOM, Slide.LEFT),
                new Spot(Horizontal.LEFT, Vertical.BOTTOM, Slide.UP)));
        DEFAULT_SPOTS.put(INVENTORY_HUD, spots(new Spot(Horizontal.CENTRE, Vertical.TOP, Slide.DOWN),
                new Spot(Horizontal.CENTRE, Vertical.TOP, Slide.LEFT),
                new Spot(Horizontal.CENTRE, Vertical.TOP, Slide.RIGHT)));
        DEFAULT_SPOTS.put(HEIGHT_LIMIT_HUD, spots(new Spot(Horizontal.LEFT, Vertical.MIDDLE, Slide.DOWN),
                new Spot(Horizontal.LEFT, Vertical.MIDDLE, Slide.UP),
                new Spot(Horizontal.LEFT, Vertical.BOTTOM, Slide.UP)));
    }
    private static String layoutKey;
    private static Map<String, float[]> layoutCache;

    private static List<Spot> spots(Spot... spots) {
        return Arrays.asList(spots);
    }

    /** Drawn box {x, y, width, height} of every module still in its default spot, by id. */
    private static Map<String, float[]> defaultLayout(Minecraft mc, ClientSettings cfg, float sw, float sh) {
        String key = layoutKey(cfg, sw, sh);
        if (key.equals(layoutKey)) return layoutCache;

        List<HudLayout.Box> obstacles = new ArrayList<HudLayout.Box>();
        obstacles.add(new HudLayout.Box((sw - HOTBAR_W) / 2f, sh - HOTBAR_ZONE_H, HOTBAR_W, HOTBAR_ZONE_H));
        List<HudLayout.Item> shown = new ArrayList<HudLayout.Item>();
        List<HudLayout.Item> hidden = new ArrayList<HudLayout.Item>();
        for (Map.Entry<String, List<Spot>> e : DEFAULT_SPOTS.entrySet()) {
            String id = e.getKey();
            HudModuleState state = HudModules.get(cfg, id);
            if (state == null) continue;
            if (state.anchor != HudPlacement.AUTO) {
                // A module the player placed is in the way of the default layout while it shows.
                HudBox moved = state.enabled ? editorBox(mc, cfg, id) : null;
                if (moved != null) {
                    obstacles.add(new HudLayout.Box(moved.visualX(), moved.visualY(), moved.visualWidth(), moved.visualHeight()));
                }
                continue;
            }
            Size size = layoutSize(mc, cfg, id);
            float pad = layoutPad(cfg, id);
            HudLayout.Item item = new HudLayout.Item(id, size.width + 2f * pad, size.height + 2f * pad,
                    e.getValue().toArray(new Spot[0]));
            (state.enabled ? shown : hidden).add(item);
        }
        shown.addAll(hidden); // hidden modules only take what room is left
        layoutCache = new HudLayout(sw, sh, LAYOUT_EDGE, LAYOUT_GAP).place(shown, obstacles);
        layoutKey = key;
        return layoutCache;
    }

    /** Everything the default layout depends on; it is worked out again only when this changes. */
    private static String layoutKey(ClientSettings cfg, float sw, float sh) {
        StringBuilder b = new StringBuilder().append(sw).append('x').append(sh)
                .append('|').append(cfg.hudDisplayMode).append(cfg.hudFont);
        for (String id : DEFAULT_SPOTS.keySet()) {
            HudModuleState s = HudModules.get(cfg, id);
            if (s != null) b.append('|').append(s.x).append(',').append(s.y).append(',').append(s.anchor)
                    .append(',').append(s.scale).append(',').append(s.enabled);
        }
        return b.toString();
    }

    private static Size layoutSize(Minecraft mc, ClientSettings cfg, String id) {
        if (POTION_HUD.equals(id)) return potionSize(mc, cfg, true);
        if (ARMOR_HUD.equals(id)) return armorSize(mc, cfg, true);
        if (KEYSTROKES_HUD.equals(id)) return keystrokesSize(cfg);
        if (INVENTORY_HUD.equals(id)) return inventorySize(cfg);
        if (DIAMOND_TIMER_HUD.equals(id)) return timerSize(mc, cfg, true, true);
        if (EMERALD_TIMER_HUD.equals(id)) return timerSize(mc, cfg, true, false);
        if (SESSION_HUD.equals(id)) return sessionSize(mc, cfg, true);
        if (HEIGHT_LIMIT_HUD.equals(id)) return heightLimitSize(mc, cfg, true);
        return Size.EMPTY;
    }

    private static float layoutPad(ClientSettings cfg, String id) {
        if (SESSION_HUD.equals(id)) return panelPad(cfg.sessionStatsHudScale);
        if (HEIGHT_LIMIT_HUD.equals(id)) return panelPad(cfg.heightLimitHudScale);
        return CONTENT_MARGIN;
    }

    /** A module's box for layout purposes: example data, whether or not it is switched on. */
    private static HudBox editorBox(Minecraft mc, ClientSettings cfg, String id) {
        if (POTION_HUD.equals(id)) return potionBox(mc, cfg, true, true);
        if (ARMOR_HUD.equals(id)) return armorBox(mc, cfg, true, true);
        if (KEYSTROKES_HUD.equals(id)) return keystrokesBox(mc, cfg, true, true);
        if (INVENTORY_HUD.equals(id)) return inventoryBox(mc, cfg, true, true);
        if (DIAMOND_TIMER_HUD.equals(id)) return timerBox(mc, cfg, true, true, true);
        if (EMERALD_TIMER_HUD.equals(id)) return timerBox(mc, cfg, true, false, true);
        if (SESSION_HUD.equals(id)) return sessionBox(mc, cfg, true, true);
        if (HEIGHT_LIMIT_HUD.equals(id)) return heightLimitBox(mc, cfg, true, true);
        return null;
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
        float width = fontWidth(line.primary, line.weight);
        if (!line.secondary.isEmpty()) {
            width += SECONDARY_GAP + fontWidth(line.secondary, line.weight) * SECONDARY_SCALE;
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

    /** Whether a HUD's "In Game Only" is satisfied: an active BedWars game, or the edit preview. */
    private static boolean bedwarsActive(boolean example) {
        return example || HypixelContext.isInActiveBedwarsGame();
    }

    private static HudBox inventoryBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !cfg.inventoryHudEnabled) return null; // works anywhere unless "In Game Only" is set
        if (!all && cfg.inventoryInGameOnly && !bedwarsActive(example)) return null;
        Size size = inventorySize(cfg);
        ScaledResolution r = new ScaledResolution(mc);
        float[] at = place(mc, cfg, INVENTORY_HUD, cfg.inventoryHudX, cfg.inventoryHudY, cfg.inventoryHudAnchor, size.width, size.height, CONTENT_MARGIN, r);
        return new HudBox(INVENTORY_HUD, at[0], at[1], size.width, size.height, CONTENT_MARGIN);
    }

    private static Size inventorySize(ClientSettings cfg) {
        return new Size(invPanelWidth() * cfg.inventoryHudScale, invPanelHeight() * cfg.inventoryHudScale);
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
        Size size = timerSize(mc, cfg, example, diamond);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution r = new ScaledResolution(mc);
        int storedX = diamond ? cfg.diamondTimerHudX : cfg.emeraldTimerHudX;
        int storedY = diamond ? cfg.diamondTimerHudY : cfg.emeraldTimerHudY;
        int anchor = diamond ? cfg.diamondTimerHudAnchor : cfg.emeraldTimerHudAnchor;
        String id = diamond ? DIAMOND_TIMER_HUD : EMERALD_TIMER_HUD;
        float[] at = place(mc, cfg, id, storedX, storedY, anchor, size.width, size.height, CONTENT_MARGIN, r);
        return new HudBox(id, at[0], at[1], size.width, size.height, CONTENT_MARGIN);
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

    // ----- Height Limit (Lunar-style Map / Height Limit / Distance rows) -----

    /** Rows are Inter Regular like the Session Stats rows; the panel is always drawn. */
    private static final BedwarsQolFont.Weight HEIGHT_ROW_WEIGHT = BedwarsQolFont.Weight.REGULAR;

    private static HudBox heightLimitBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !HeightLimitWatch.visible(cfg, example)) return null;
        Size size = heightLimitSize(mc, cfg, example);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution r = new ScaledResolution(mc);
        float pad = panelPad(cfg.heightLimitHudScale);
        float[] at = place(mc, cfg, HEIGHT_LIMIT_HUD, cfg.heightLimitHudX, cfg.heightLimitHudY, cfg.heightLimitHudAnchor, size.width, size.height, pad, r);
        return new HudBox(HEIGHT_LIMIT_HUD, at[0], at[1], size.width, size.height, pad);
    }

    private static Size heightLimitSize(Minecraft mc, ClientSettings cfg, boolean example) {
        float scale = cfg.heightLimitHudScale;
        List<String> rows = heightLimitRows(mc, example);
        float width = 0f;
        for (String row : rows) width = Math.max(width, fontWidth(row, HEIGHT_ROW_WEIGHT) * scale);
        float height = rows.size() * ((TEXT_HEIGHT + LINE_GAP) * scale) - LINE_GAP * scale;
        return new Size(width, height);
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
        Size size = sessionSize(mc, cfg, example);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution r = new ScaledResolution(mc);
        float pad = panelPad(cfg.sessionStatsHudScale);
        float[] at = place(mc, cfg, SESSION_HUD, cfg.sessionStatsHudX, cfg.sessionStatsHudY, cfg.sessionStatsHudAnchor, size.width, size.height, pad, r);
        return new HudBox(SESSION_HUD, at[0], at[1], size.width, size.height, pad);
    }

    private static Size sessionSize(Minecraft mc, ClientSettings cfg, boolean example) {
        return textSize(mc.fontRendererObj, sessionLines(example), cfg.sessionStatsHudScale);
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

    // ----- Keystrokes (WASD, spacebar, mouse buttons with CPS) -----

    private static HudBox keystrokesBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !cfg.keystrokesEnabled) return null;
        if (!all && cfg.keystrokesInGameOnly && !bedwarsActive(example)) return null;
        Size size = keystrokesSize(cfg);
        ScaledResolution r = new ScaledResolution(mc);
        float[] at = place(mc, cfg, KEYSTROKES_HUD, cfg.keystrokesHudX, cfg.keystrokesHudY, cfg.keystrokesHudAnchor, size.width, size.height, CONTENT_MARGIN, r);
        return new HudBox(KEYSTROKES_HUD, at[0], at[1], size.width, size.height, CONTENT_MARGIN);
    }

    private static Size keystrokesSize(ClientSettings cfg) {
        float scale = cfg.keystrokesHudScale;
        return new Size((3f * KS_UNIT + 2f * KS_GAP) * scale, (3f * KS_UNIT + 3f * KS_GAP + KS_SPACE_H) * scale);
    }

    private static void drawKeystrokesHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = keystrokesBox(mc, cfg, example, false);
        if (box == null) return;
        boolean[] held = keyStates(mc, example);
        long now = Minecraft.getSystemTime();
        float[] level = new float[held.length];
        for (int i = 0; i < held.length; i++) {
            // The editor preview shows a still frame and must not disturb the live fades.
            level[i] = example ? (held[i] ? 1f : 0f) : KS_FADES[i].level(held[i], now);
        }
        int accent = GuiTheme.fromToken(cfg.guiAccent).base();
        String leftCps = (example ? 12 : LEFT_CLICKS.cps(now)) + " CPS";
        String rightCps = (example ? 3 : RIGHT_CLICKS.cps(now)) + " CPS";

        // Draw everything in local (unscaled) coordinates under one translate+scale, so the caps and
        // letters scale together through the modelview matrix.
        GlStateManager.pushMatrix();
        GlStateManager.translate(box.x, box.y, 0f);
        GlStateManager.scale(cfg.keystrokesHudScale, cfg.keystrokesHudScale, 1f);

        float u = KS_UNIT;
        float step = KS_UNIT + KS_GAP;
        float width = 3f * KS_UNIT + 2f * KS_GAP;
        float half = (width - KS_GAP) / 2f;
        drawKeyCap(step, 0f, u, u, "W", null, level[KS_W], accent);
        drawKeyCap(0f, step, u, u, "A", null, level[KS_A], accent);
        drawKeyCap(step, step, u, u, "S", null, level[KS_S], accent);
        drawKeyCap(2f * step, step, u, u, "D", null, level[KS_D], accent);
        drawKeyCap(0f, 2f * step + KS_SPACE_H + KS_GAP, half, u, "LMB", leftCps, level[KS_LMB], accent);
        drawKeyCap(half + KS_GAP, 2f * step + KS_SPACE_H + KS_GAP, half, u, "RMB", rightCps, level[KS_RMB], accent);
        drawKeyCap(0f, 2f * step, width, KS_SPACE_H, "", null, level[KS_SPACE], accent);
        drawSpaceSymbol(0f, 2f * step, width, KS_SPACE_H, level[KS_SPACE]);

        GlStateManager.popMatrix();
        resetGlState();
    }

    /** One cap: {@code level} 0 is idle, 1 fully pressed (accent fill). */
    private static void drawKeyCap(float x, float y, float w, float h, String label, String sub, float level, int accent) {
        float radius = Math.min(KS_RADIUS, Math.min(w, h) * 0.25f);
        GuiRender.roundedRect(x, y, x + w, y + h, radius, GuiRender.lerpColor(KS_FILL_OFF, accent, level));
        if (label.isEmpty()) return;
        int text = GuiRender.lerpColor(KS_TEXT_OFF, KS_TEXT_ON, level);
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        if (sub == null) {
            drawCentered(label, cx, cy, KS_LETTER, text);
        } else {
            drawCentered(label, cx, cy - 3f, KS_MOUSE_LABEL, text);
            drawCentered(sub, cx, cy + 3.5f, KS_CPS_LABEL, GuiRender.lerpColor(KS_CPS_OFF, KS_TEXT_ON, level));
        }
    }

    private static void drawCentered(String text, float cx, float cy, float scale, int color) {
        float tw = fontWidth(text) * scale;
        fontDraw(text, cx - tw / 2f, cy - fontHeight(scale) / 2f, scale, color, BedwarsQolFont.Weight.BOLD);
    }

    /** A centered horizontal bar (thin, square corners) standing in for the space key's label. */
    private static void drawSpaceSymbol(float x, float y, float w, float h, float level) {
        float barW = w * 0.30f;
        float barH = 1.25f;
        float bx1 = x + (w - barW) / 2f;
        float by1 = y + (h - barH) / 2f;
        GuiRender.rect(bx1, by1, bx1 + barW, by1 + barH, GuiRender.lerpColor(KS_TEXT_OFF, KS_TEXT_ON, level));
    }

    /**
     * Held state in cap order (W, A, S, D, Space, LMB, RMB). Movement follows the player's binds; the
     * mouse row is the physical buttons its labels name, and only counts in game (no screen open).
     */
    private static boolean[] keyStates(Minecraft mc, boolean example) {
        if (example || mc.gameSettings == null) return new boolean[]{true, false, false, false, false, false, false};
        boolean inGame = mc.currentScreen == null;
        return new boolean[]{
                isDown(mc.gameSettings.keyBindForward),
                isDown(mc.gameSettings.keyBindLeft),
                isDown(mc.gameSettings.keyBindBack),
                isDown(mc.gameSettings.keyBindRight),
                isDown(mc.gameSettings.keyBindJump),
                inGame && Mouse.isButtonDown(0),
                inGame && Mouse.isButtonDown(1)};
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
        /** Content box: what the module draws, before any panel padding. */
        public final float x;
        public final float y;
        public final float width;
        public final float height;
        /**
         * Space around the content box: the panel's padding, or a small margin for modules without
         * a panel. Frames, clicks, snapping and the screen edge all use the box grown by this.
         */
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
