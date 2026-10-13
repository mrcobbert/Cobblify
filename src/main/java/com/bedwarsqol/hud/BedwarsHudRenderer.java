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
import com.bedwarsqol.gui.render.GuiRender;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.gui.sheet.SheetColors;
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
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
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

    private static final ResourceLocation INVENTORY_TEXTURE = new ResourceLocation("textures/gui/container/inventory.png");
    private static final float TEXT_HEIGHT = 9f;
    private static final float LINE_GAP = 2f;
    private static final float SECONDARY_SCALE = 0.75f;
    private static final float SECONDARY_GAP = 2f;
    private static final float POTION_ICON_SIZE = 18f;
    private static final float POTION_ICON_TIMER_GAP = 3f;
    private static final float ARMOR_ICON_SIZE = 16f;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    // Keystrokes: WASD, a spacebar and a mouse row (LMB/RMB with CPS), as caps in the HUD panel's look
    // (same fill, square corners and white/gray text as Map Info and Session Stats). A pressed cap takes the
    // menu accent and fades back out after release. Geometry is in local units (multiplied by the HUD
    // scale at draw time).
    private static final float KS_UNIT = 18f;     // square key cap side
    private static final float KS_GAP = 2f;       // gap between caps
    private static final float KS_SPACE_H = 14f;  // spacebar height
    private static final float KS_LETTER = 1.0f;  // letter scale within a cap
    private static final float KS_MOUSE_LABEL = 0.75f; // "LMB" / "RMB"
    private static final float KS_CPS_LABEL = 0.55f;   // the "N CPS" line under it
    private static final long KS_FADE_MS = 120L;
    private static final int KS_FILL_OFF = 0xD0121212;   // HUD_BG_FILL
    private static final int KS_TEXT_OFF = 0xFFFFFFFF;
    private static final int KS_TEXT_ON = 0xFFFFFFFF;
    private static final int KS_TEXT_ON_LIGHT = 0xFF121212; // on an accent too light for white to read
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
        ClientSettings cfg = hudSettings();
        if (cfg != null) render(Minecraft.getMinecraft(), cfg, false);
    }

    /**
     * Session Stats goes on top of everything: Forge posts ALL once the scoreboard sidebar, chat and tab list,
     * which all draw after the Text pass, are done.
     */
    @SubscribeEvent
    public void onRenderPost(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        ClientSettings cfg = hudSettings();
        if (cfg != null) drawSessionHud(Minecraft.getMinecraft(), cfg, false);
    }

    /** The settings when the in-game HUD draws this frame, sanitized; null when it does not. */
    private static ClientSettings hudSettings() {
        Minecraft mc = Minecraft.getMinecraft();
        ClientSettings cfg = BedwarsQol.config;
        if (mc == null || mc.thePlayer == null || cfg == null) return null;
        if (mc.gameSettings != null && mc.gameSettings.showDebugInfo) return null;
        if (mc.currentScreen instanceof EditHudGui) return null; // the editor draws its own preview
        cfg.sanitize();
        return cfg;
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
        if (cfg.potionStatusEnabled) drawPotionHud(mc, cfg, example);
        if (cfg.armorTypeEnabled) drawArmorHud(mc, cfg, example);
        drawInventoryHud(mc, cfg, example);
        drawTimerHud(mc, cfg, example, true);
        drawTimerHud(mc, cfg, example, false);
        if (cfg.keystrokesEnabled) drawKeystrokesHud(mc, cfg, example);
        drawHeightLimitHud(mc, cfg, example);
        // In game Session Stats draws later, over everything (onRenderPost); in the editor it is the top module.
        if (example) drawSessionHud(mc, cfg, true);
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

    /**
     * In the Edit HUD preview the panel stops 1 px short of its box, so the editor's 1 px frame on the box
     * edge sits over the world like every other module's frame.
     */
    private static void drawHudBackground(HudBox box, float scale, boolean example) {
        float pad = panelPad(scale) - (example ? 1f : 0f);
        float x1 = box.x - pad, y1 = box.y - pad;
        float x2 = box.right() + pad, y2 = box.bottom() + pad;
        GuiRender.rect(x1, y1, x2, y2, HUD_BG_FILL); // square, so the Edit HUD frame matches its edge
    }

    private static void drawPotionHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = potionBox(mc, cfg, example, false);
        if (box == null) return;
        List<PotionEntry> entries = potionEntries(mc, example);
        if (entries.isEmpty()) return;
        drawPotionImages(mc, cfg, entries, box.x, box.y);
    }

    private static void drawArmorHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = armorBox(mc, cfg, example, false);
        if (box == null) return;
        ItemStack leggings = currentLeggings(mc, example);
        if (leggings == null) return;
        drawArmorIcon(mc, cfg, leggings, box.x, box.y);
    }

    private static void drawPotionImages(Minecraft mc, ClientSettings cfg, List<PotionEntry> entries, float x, float y) {
        float scale = cfg.potionHudScale;
        float iconSize = POTION_ICON_SIZE * scale;
        float lineStep = iconSize + LINE_GAP * scale;
        float timerScale = ts(scale); // match the gen-timer numbers (full scale, not the smaller secondary)
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
                float ty = y + i * lineStep + (iconSize - TEXT_HEIGHT * timerScale) / 2f + inkTrim(timerScale) / 2f;
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

    // ----- HUD font: the modern Inter atlas -----

    /**
     * Width of {@code text} at scale 1.0 in the HUD font (multiply by your scale at the call
     * site), measured in the weight it is drawn with — every HUD draw is SemiBold unless a
     * {@link Line} says otherwise, and SemiBold advances are wider than Regular.
     */
    private static float fontWidth(String text) {
        return fontWidth(text, BedwarsQolFont.Weight.BOLD);
    }

    private static float fontWidth(String text, BedwarsQolFont.Weight weight) {
        return BedwarsQolFont.width(text, 1f, weight);
    }

    /** Line height at {@code scale}; ~9px at scale 1.0. */
    private static float fontHeight(float scale) {
        return BedwarsQolFont.height(scale);
    }

    /**
     * The scale a module's text is laid out and drawn at: its own, or in Minecraft's font snapped to whole device
     * pixels per font pixel (see {@link BedwarsQolFont#textScale}). Measure with it wherever text is drawn with it.
     */
    private static float ts(float scale) {
        return BedwarsQolFont.textScale(scale);
    }

    /**
     * How much of the 9 px line Minecraft's font leaves empty under its capitals and their shadow: one font pixel,
     * dropped from a text block's height and halved when centring it, so the text itself sits centred. 0 with the
     * modern font.
     */
    private static float inkTrim(float textScale) {
        return BedwarsQolFont.minecraft() ? textScale : 0f;
    }

    /** Draw {@code text} with its top-left at (x,y), scaled about that origin, in the HUD font. */
    private static void fontDraw(String text, float x, float y, float scale, int color, BedwarsQolFont.Weight weight) {
        BedwarsQolFont.draw(text, x, y, scale, color, false, weight);
    }

    private static void drawScaledString(FontRenderer fr, String text, float x, float y, float scale) {
        // Inter SemiBold
        drawScaledString(fr, text, x, y, scale, BedwarsQolFont.Weight.BOLD);
    }

    private static void drawScaledString(FontRenderer fr, String text, float x, float y, float scale,
                                         BedwarsQolFont.Weight weight) {
        fontDraw(text, x, y, scale, TEXT_COLOR, weight);
    }

    private static Size potionSize(Minecraft mc, ClientSettings cfg, boolean example) {
        List<PotionEntry> entries = potionEntries(mc, example);
        if (entries.isEmpty()) return Size.EMPTY;

        float maxTextWidth = 0f;
        FontRenderer fr = mc.fontRendererObj;
        if (fr != null) {
            for (PotionEntry entry : entries) {
                maxTextWidth = Math.max(maxTextWidth, fontWidth(entry.timer) * ts(cfg.potionHudScale));
            }
        }
        float width = POTION_ICON_SIZE * cfg.potionHudScale + POTION_ICON_TIMER_GAP * cfg.potionHudScale + maxTextWidth;
        float height = entries.size() * (POTION_ICON_SIZE * cfg.potionHudScale)
                + Math.max(0, entries.size() - 1) * (LINE_GAP * cfg.potionHudScale);
        return new Size(width, height);
    }

    private static Size armorSize(Minecraft mc, ClientSettings cfg, boolean example) {
        ItemStack leggings = currentLeggings(mc, example);
        if (leggings == null) return Size.EMPTY;
        float size = ARMOR_ICON_SIZE * cfg.armorHudScale;
        return new Size(size, size);
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
    private static final Map<String, Spot[]> DEFAULT_SPOTS = new LinkedHashMap<String, Spot[]>();
    static {
        DEFAULT_SPOTS.put(DIAMOND_TIMER_HUD, new Spot[]{new Spot(Horizontal.RIGHT, Vertical.TOP, Slide.LEFT)});
        DEFAULT_SPOTS.put(EMERALD_TIMER_HUD, new Spot[]{new Spot(Horizontal.RIGHT, Vertical.TOP, Slide.LEFT)});
        DEFAULT_SPOTS.put(SESSION_HUD, new Spot[]{new Spot(Horizontal.RIGHT, Vertical.TOP, Slide.DOWN),
                new Spot(Horizontal.LEFT, Vertical.TOP, Slide.DOWN)});
        DEFAULT_SPOTS.put(ARMOR_HUD, new Spot[]{new Spot(Horizontal.LEFT, Vertical.TOP, Slide.DOWN)});
        DEFAULT_SPOTS.put(POTION_HUD, new Spot[]{new Spot(Horizontal.LEFT, Vertical.TOP, Slide.DOWN)});
        DEFAULT_SPOTS.put(KEYSTROKES_HUD, new Spot[]{new Spot(Horizontal.RIGHT, Vertical.BOTTOM, Slide.UP),
                new Spot(Horizontal.RIGHT, Vertical.BOTTOM, Slide.LEFT),
                new Spot(Horizontal.LEFT, Vertical.BOTTOM, Slide.UP)});
        DEFAULT_SPOTS.put(INVENTORY_HUD, new Spot[]{new Spot(Horizontal.CENTRE, Vertical.TOP, Slide.DOWN),
                new Spot(Horizontal.CENTRE, Vertical.TOP, Slide.LEFT),
                new Spot(Horizontal.CENTRE, Vertical.TOP, Slide.RIGHT)});
        DEFAULT_SPOTS.put(HEIGHT_LIMIT_HUD, new Spot[]{new Spot(Horizontal.LEFT, Vertical.MIDDLE, Slide.DOWN),
                new Spot(Horizontal.LEFT, Vertical.MIDDLE, Slide.UP),
                new Spot(Horizontal.LEFT, Vertical.BOTTOM, Slide.UP)});
    }
    private static String layoutKey;
    private static Map<String, float[]> layoutCache;

    /** Drawn box {x, y, width, height} of every module still in its default spot, by id. */
    private static Map<String, float[]> defaultLayout(Minecraft mc, ClientSettings cfg, float sw, float sh) {
        String key = layoutKey(cfg, sw, sh);
        if (key.equals(layoutKey)) return layoutCache;

        List<HudLayout.Box> obstacles = new ArrayList<HudLayout.Box>();
        obstacles.add(new HudLayout.Box((sw - HOTBAR_W) / 2f, sh - HOTBAR_ZONE_H, HOTBAR_W, HOTBAR_ZONE_H));
        List<HudLayout.Item> shown = new ArrayList<HudLayout.Item>();
        List<HudLayout.Item> hidden = new ArrayList<HudLayout.Item>();
        for (Map.Entry<String, Spot[]> e : DEFAULT_SPOTS.entrySet()) {
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
            HudLayout.Item item = new HudLayout.Item(id, size.width + 2f * pad, size.height + 2f * pad, e.getValue());
            (state.enabled ? shown : hidden).add(item);
        }
        shown.addAll(hidden); // hidden modules only take what room is left
        layoutCache = new HudLayout(sw, sh, LAYOUT_EDGE, LAYOUT_GAP).place(shown, obstacles);
        layoutKey = key;
        return layoutCache;
    }

    /** Everything the default layout depends on; it is worked out again only when this changes. */
    private static String layoutKey(ClientSettings cfg, float sw, float sh) {
        // the font sizes every text module (Minecraft's snaps to the GUI scale, which the screen size follows)
        StringBuilder b = new StringBuilder().append(sw).append('x').append(sh).append('|').append(cfg.guiFont);
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

        float t = ts(scale);
        float width = 0f;
        for (Line line : lines) {
            width = Math.max(width, lineWidth(fr, line) * t);
        }
        float height = lines.size() * ((TEXT_HEIGHT + LINE_GAP) * t) - LINE_GAP * t - inkTrim(t);
        return new Size(width, height);
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
        return iconCountsSize(mc, Collections.singletonList(timerEntry(example, diamond)), scale);
    }

    private static void drawTimerHud(Minecraft mc, ClientSettings cfg, boolean example, boolean diamond) {
        HudBox box = timerBox(mc, cfg, example, diamond, false);
        if (box == null) return;
        float scale = diamond ? cfg.diamondTimerHudScale : cfg.emeraldTimerHudScale;
        drawIconCounts(mc, Collections.singletonList(timerEntry(example, diamond)), box.x, box.y, scale);
    }

    private static IconCount timerEntry(boolean example, boolean diamond) {
        return new IconCount(new ItemStack(diamond ? Items.diamond : Items.emerald), timerValue(example, diamond));
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
        float scale = ts(cfg.heightLimitHudScale);
        List<String> rows = heightLimitRows(mc, example);
        float width = 0f;
        for (String row : rows) width = Math.max(width, fontWidth(row, HEIGHT_ROW_WEIGHT) * scale);
        float height = rows.size() * ((TEXT_HEIGHT + LINE_GAP) * scale) - LINE_GAP * scale - inkTrim(scale);
        return new Size(width, height);
    }

    private static void drawHeightLimitHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = heightLimitBox(mc, cfg, example, false);
        if (box == null) return;
        float scale = cfg.heightLimitHudScale;
        drawHudBackground(box, scale, example);
        scale = ts(scale);
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
        drawHudBackground(box, scale, example); // always on: the 13-row panel is unreadable over the world without it
        scale = ts(scale);
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

        float u = KS_UNIT;
        float step = KS_UNIT + KS_GAP;
        float width = 3f * KS_UNIT + 2f * KS_GAP;
        float half = (width - KS_GAP) / 2f;
        // {x, y, w, h} of each cap, in cap order
        float[][] caps = {{step, 0f, u, u}, {0f, step, u, u}, {step, step, u, u}, {2f * step, step, u, u},
                {0f, 2f * step, width, KS_SPACE_H},
                {0f, 2f * step + KS_SPACE_H + KS_GAP, half, u}, {half + KS_GAP, 2f * step + KS_SPACE_H + KS_GAP, half, u}};
        String[] labels = {"W", "A", "S", "D", "", "LMB", "RMB"};
        String[] subs = {null, null, null, null, null, leftCps, rightCps};
        int[] order = {KS_W, KS_A, KS_S, KS_D, KS_LMB, KS_RMB, KS_SPACE};
        // Minecraft's font draws the labels after the caps, in GUI space on whole pixels; the modern font draws
        // them with their caps
        boolean labelsAfter = BedwarsQolFont.minecraft();

        // Draw everything in local (unscaled) coordinates under one translate+scale, so the caps and
        // letters scale together through the modelview matrix.
        GlStateManager.pushMatrix();
        GlStateManager.translate(box.x, box.y, 0f);
        GlStateManager.scale(cfg.keystrokesHudScale, cfg.keystrokesHudScale, 1f);

        for (int i : order) {
            float[] r = caps[i];
            drawKeyCap(r[0], r[1], r[2], r[3], labelsAfter ? "" : labels[i], subs[i], level[i], accent);
        }
        drawSpaceSymbol(0f, 2f * step, width, KS_SPACE_H, level[KS_SPACE], accent);

        GlStateManager.popMatrix();
        if (labelsAfter) {
            for (int i : order) {
                float[] r = caps[i];
                if (labels[i].isEmpty()) continue;
                drawKeyLabel(box, cfg.keystrokesHudScale, r[0] + r[2] / 2f, r[1] + r[3] / 2f, labels[i], subs[i], level[i], accent);
            }
        }
        resetGlState();
    }

    /**
     * A cap's label in Minecraft's font, centred on the cap's local centre (cx, cy) but drawn in GUI space, so its
     * pixels land whole; a mouse cap's CPS line sits under its label, the two centred as one block.
     *
     * <p>The shadow falls on the cap, so it follows the cap's colour: Minecraft's own under the idle white, and on a
     * pressed cap the accent's pressed shade, the sheet's shadow on a pressed face (vanilla's, the text's colour at
     * a quarter, would only smudge the dark text pale accents get).
     */
    private static void drawKeyLabel(HudBox box, float hudScale, float cx, float cy, String label, String sub,
                                     float level, int accent) {
        int on = onAccent(accent);
        int text = GuiRender.lerpColor(KS_TEXT_OFF, on, level);
        int pressedShadow = SheetColors.pressSh(accent & 0xFFFFFF);
        int shadow = GuiRender.lerpColor(SheetColors.textShadow(KS_TEXT_OFF), pressedShadow, level);
        float gx = box.x + cx * hudScale, gy = box.y + cy * hudScale;
        if (sub == null) {
            float t = ts(KS_LETTER * hudScale);
            BedwarsQolFont.drawMinecraftOver(label, gx - fontWidth(label) * t / 2f, gy - fontHeight(t) / 2f, t, text, shadow);
            return;
        }
        // LMB and RMB are Minecraft's letters drawn as tall as the modern font's label (MouseKeyLabel), on whole
        // device pixels, over the same shadow; the CPS line under them stays the font, never larger than designed
        int sf = new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
        int p = MouseKeyLabel.pixel(BedwarsQolFont.capHeight(KS_MOUSE_LABEL * hudScale, BedwarsQolFont.Weight.BOLD) * sf);
        float tc = BedwarsQolFont.textScaleAtMost(KS_CPS_LABEL * hudScale);
        float lh = (MouseKeyLabel.HEIGHT + 1) * p / (float) sf, gap = 2f * tc;
        int left = Math.round(gx * sf - MouseKeyLabel.width(label) * p / 2f);
        int top = Math.round((gy - (lh + gap + fontHeight(tc)) / 2f) * sf);
        drawPixelLabel(label, left + p, top + p, p, sf, shadow);
        drawPixelLabel(label, left, top, p, sf, text);
        int cps = GuiRender.lerpColor(KS_CPS_OFF, on, level);
        BedwarsQolFont.drawMinecraftOver(sub, gx - fontWidth(sub) * tc / 2f, top / (float) sf + lh + gap, tc, cps,
                GuiRender.lerpColor(SheetColors.textShadow(KS_CPS_OFF), pressedShadow, level));
    }

    /** A {@link MouseKeyLabel} with its top left at device px (x, y), {@code p} device px per pixel, in GUI space. */
    private static void drawPixelLabel(String label, final int x, final int y, final int p, final int sf, final int color) {
        MouseKeyLabel.runs(label, (rx, ry, w) -> GuiRender.rect((x + rx * p) / (float) sf, (y + ry * p) / (float) sf,
                (x + (rx + w) * p) / (float) sf, (y + (ry + 1) * p) / (float) sf, color));
    }

    /** One cap: {@code level} 0 is idle, 1 fully pressed (accent fill). */
    private static void drawKeyCap(float x, float y, float w, float h, String label, String sub, float level, int accent) {
        GuiRender.rect(x, y, x + w, y + h, GuiRender.lerpColor(KS_FILL_OFF, accent, level));
        if (label.isEmpty()) return;
        int on = onAccent(accent);
        int text = GuiRender.lerpColor(KS_TEXT_OFF, on, level);
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        if (sub == null) {
            drawCentered(label, cx, cy, KS_LETTER, text);
        } else {
            drawCentered(label, cx, cy - 3f, KS_MOUSE_LABEL, text);
            drawCentered(sub, cx, cy + 3.5f, KS_CPS_LABEL, GuiRender.lerpColor(KS_CPS_OFF, on, level));
        }
    }

    /** A pressed cap's text colour: white, unless white falls under 2:1 on the accent (pale, yellow, white). */
    private static int onAccent(int accent) {
        return GuiTheme.contrast(accent & 0xFFFFFF, 0xFFFFFF) < 2.0 ? KS_TEXT_ON_LIGHT : KS_TEXT_ON;
    }

    private static void drawCentered(String text, float cx, float cy, float scale, int color) {
        float tw = fontWidth(text) * scale;
        fontDraw(text, cx - tw / 2f, cy - fontHeight(scale) / 2f, scale, color, BedwarsQolFont.Weight.BOLD);
    }

    /** A centered horizontal bar (thin, square corners) standing in for the space key's label. */
    private static void drawSpaceSymbol(float x, float y, float w, float h, float level, int accent) {
        float barW = w * 0.30f;
        float barH = 1.25f;
        float bx1 = x + (w - barW) / 2f;
        float by1 = y + (h - barH) / 2f;
        GuiRender.rect(bx1, by1, bx1 + barW, by1 + barH, GuiRender.lerpColor(KS_TEXT_OFF, onAccent(accent), level));
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
                float t = ts(scale);
                float ty = y + i * lineStep + (iconSize - TEXT_HEIGHT * t) / 2f + inkTrim(t) / 2f;
                drawScaledString(fr, count, x + iconSize + gap, ty, t);
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
                if (!e.count.isEmpty()) maxText = Math.max(maxText, fontWidth(e.count) * ts(scale));
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
