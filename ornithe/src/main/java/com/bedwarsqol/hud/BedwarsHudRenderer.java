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
import com.bedwarsqol.feature.TeamUpgradeCatalog;
import com.bedwarsqol.feature.TeamUpgradeLifecycle;
import com.bedwarsqol.feature.TeamUpgradeRows;
import com.bedwarsqol.feature.TeamUpgradesWatch;
import com.bedwarsqol.ornithe.MouseButtonEvent;
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
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ResourceLocation;
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
    public static final String TEAM_UPGRADES_HUD = "teamupgrades";
    public static final String EMERALD_TIMER_HUD = "emeraldtimer";
    public static final String KEYSTROKES_HUD = "keystrokes";
    public static final String FPS_HUD = "fps";
    public static final String CPS_HUD = "cps";
    public static final String PING_HUD = "ping";
    /** Vanilla's sidebar scoreboard, chat and tab list as HUD items (see {@link HudModules#overlayIds()}). */
    public static final String SCOREBOARD_HUD = "scoreboard";
    public static final String CHAT_HUD = "chat";
    public static final String TAB_LIST_HUD = "tablist";
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
    private static final float POTION_ICON_TIMER_GAP = 3f;
    private static final float ARMOR_ICON_SIZE = 16f;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    // Keystrokes: Lunar's caps (KeystrokesLayout): WASD, the mouse row and a spacebar, see-through idle caps while
    // its Background is on, none while it is off. A pressed cap takes the menu accent (KeyCapStyle) and fades back out
    // after release. Geometry is in local units, multiplied by the labels' own scale at draw time.
    private static final long KS_FADE_MS = 120L;

    /** Cap order everywhere below: W, A, S, D, Space, LMB, RMB. */
    private static final int KS_W = 0, KS_A = 1, KS_S = 2, KS_D = 3, KS_SPACE = 4, KS_LMB = 5, KS_RMB = 6;
    private static final KeyFade[] KS_FADES = new KeyFade[7];
    static {
        for (int i = 0; i < KS_FADES.length; i++) KS_FADES[i] = new KeyFade(KS_FADE_MS);
    }
    private static final ClickCounter LEFT_CLICKS = new ClickCounter();
    private static final ClickCounter RIGHT_CLICKS = new ClickCounter();

    // ----- HUD panel (every module with its Background switch on) -----
    // Lunar's flat, square see-through panel behind a HUD element (no border, no corner radius).
    // Padding scales with the element so it stays proportionate.
    private static final int HUD_BG_FILL = HudBackground.FILL;

    public void onRenderText() {
        ClientSettings cfg = hudSettings();
        if (cfg != null) render(Minecraft.getMinecraft(), cfg, false);
    }

    /**
     * Session Stats goes on top of everything: this runs at the end of the HUD frame, once the scoreboard sidebar, chat and tab list,
     * which all draw after the Text pass, are done. While the tab list shows it is hidden like every other
     * module, in every mode, Hold Key included (holding Tab hides the Cobblify HUD).
     */
    public void onRenderPost() {
        if (TabHudGate.hidesHud()) return;
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

    /** Counts in-game presses for the CPS module and the Keystrokes mouse caps; the in-game mouse loop runs only with no screen open (or one that allows input). */
    public void onMouse(MouseButtonEvent event) {
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

    /**
     * True while Edit HUD measures or draws: In Game Only and the other visibility gates pass and panels keep the
     * editor's 1 px inset, but each item shows live data unless it has nothing live to show ({@link PreviewData}).
     * Set only by {@link #renderEditPreview}, {@link #getEditorBoxes} and {@link #getEditorBox}, in try/finally, on the
     * render thread.
     */
    private static boolean editing;

    public static void renderEditPreview(Minecraft mc, ClientSettings cfg) {
        boolean was = editing;
        editing = true;
        try {
            render(mc, cfg, true);
        } finally {
            editing = was;
        }
    }

    /**
     * Every module's box for Edit HUD, hidden modules included, measured from the data the editor shows (live, or the
     * sample for an item with nothing live). {@link HudBox#shown} says which ones the player has switched on.
     */
    public static List<HudBox> getEditorBoxes(Minecraft mc, ClientSettings cfg) {
        if (mc == null || cfg == null) return Collections.emptyList();
        cfg.sanitize();

        boolean was = editing;
        editing = true;
        try {
            PreviewData.Facts f = previewFacts(mc);
            List<HudBox> boxes = new ArrayList<>(8);
            for (String id : EDITOR_ORDER) addEditorBox(boxes, cfg, editorItemBox(mc, cfg, id, f));
            return boxes;
        } finally {
            editing = was;
        }
    }

    /** Edit HUD's item order (its boxes and their draw order); the tab list is not an editor box (cycle B). */
    private static final String[] EDITOR_ORDER = {POTION_HUD, ARMOR_HUD, INVENTORY_HUD, DIAMOND_TIMER_HUD,
            EMERALD_TIMER_HUD, KEYSTROKES_HUD, SESSION_HUD, HEIGHT_LIMIT_HUD, TEAM_UPGRADES_HUD, FPS_HUD, CPS_HUD, PING_HUD,
            SCOREBOARD_HUD, CHAT_HUD};

    /** One module's Edit HUD box with the editor's data choice, whether or not it is switched on. */
    private static HudBox editorItemBox(Minecraft mc, ClientSettings cfg, String id, PreviewData.Facts f) {
        boolean sample = sample(id, f);
        if (POTION_HUD.equals(id)) return potionBox(mc, cfg, sample, true);
        if (ARMOR_HUD.equals(id)) return armorBox(mc, cfg, sample, true);
        if (INVENTORY_HUD.equals(id)) return inventoryBox(mc, cfg, sample, true);
        if (DIAMOND_TIMER_HUD.equals(id)) return timerBox(mc, cfg, sample, true, true);
        if (EMERALD_TIMER_HUD.equals(id)) return timerBox(mc, cfg, sample, false, true);
        if (KEYSTROKES_HUD.equals(id)) return keystrokesBox(mc, cfg, sample, true);
        if (SESSION_HUD.equals(id)) return sessionBox(mc, cfg, sample, true);
        if (HEIGHT_LIMIT_HUD.equals(id)) return heightLimitBox(mc, cfg, sample, true);
        if (TEAM_UPGRADES_HUD.equals(id)) return teamUpgradesEditorBox(mc, cfg, true); // always the Full size
        if (isTextModule(id)) return textBox(mc, cfg, id, sample, true);
        // the scoreboard and chat are live, with no box when there is nothing to show (no sidebar objective)
        if (SCOREBOARD_HUD.equals(id)) return scoreboardBox(mc, cfg);
        if (CHAT_HUD.equals(id)) return chatBox(mc, cfg);
        return null;
    }

    /** Whether item {@code id} draws sample data in the editor ({@code f} null: not in the editor, so never). */
    private static boolean sample(String id, PreviewData.Facts f) {
        return f != null && PreviewData.sample(id, f);
    }

    /** What play has to show right now, from the same sources the HUD draws from. */
    private static PreviewData.Facts previewFacts(Minecraft mc) {
        boolean player = mc != null && mc.thePlayer != null;
        int potions = player ? potionEntries(mc, false).size() : 0;
        boolean legs = player && currentLeggings(mc, false) != null;
        return new PreviewData.Facts(player, potions, legs, GeneratorTracker.diamondSeconds(),
                GeneratorTracker.emeraldSeconds(), HeightLimitWatch.core().map() != null);
    }

    // ----- Vanilla overlays as HUD items (scoreboard, chat, tab list) -----

    /**
     * An overlay item's live box (pad 0), or null when there is nothing to show (no sidebar objective, no
     * chat line, ...). The box is where vanilla's drawing lands after the item's move and scale.
     */
    public static HudBox overlayBox(Minecraft mc, ClientSettings cfg, String id) {
        if (mc == null || cfg == null) return null;
        if (SCOREBOARD_HUD.equals(id)) return scoreboardBox(mc, cfg);
        if (TAB_LIST_HUD.equals(id)) return tabListBox(mc, cfg);
        if (CHAT_HUD.equals(id)) return chatBox(mc, cfg);
        return null;
    }

    /** Whether overlay {@code id} draws its Background (fill behind its lines): its Background switch, On without a config. */
    public static boolean overlayBackground(ClientSettings cfg, String id) {
        return cfg == null || HudModules.background(cfg, id);
    }

    /** The sidebar scoreboard's box: vanilla's sidebar for the shown objective, moved and scaled; null without one. */
    public static HudBox scoreboardBox(Minecraft mc, ClientSettings cfg) {
        if (mc == null || cfg == null) return null;
        ScaledResolution r = new ScaledResolution(mc);
        float[] rect = OverlayScale.scoreboardRect(OverlayScale.sidebarObjective(mc), r.getScaledWidth(), r.getScaledHeight());
        if (rect == null) return null;
        float[] box = OverlayScale.scoreboardBox(cfg, rect, r.getScaledWidth(), r.getScaledHeight());
        return new HudBox(SCOREBOARD_HUD, box[0], box[1], box[2], box[3], 0f);
    }

    /**
     * The tab list's box: the live player list (header and footer included unless hidden), moved and scaled,
     * whether or not vanilla draws it this frame; null without a connection. Not an editor box: the tab list
     * is never an obstacle, and Edit HUD shows it only while Tab is held.
     */
    public static HudBox tabListBox(Minecraft mc, ClientSettings cfg) {
        if (mc == null || cfg == null || mc.ingameGUI == null || mc.theWorld == null) return null;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return null;
        ScaledResolution r = new ScaledResolution(mc);
        float[] m = ((TabOverlayScale) mc.ingameGUI.getTabList())
                .bedwarsqol$measure(r.getScaledWidth(), board, board.getObjectiveInDisplaySlot(0));
        if (m == null) return null;
        float[] box = TabOverlayScale.placement(cfg, m, r.getScaledWidth(), r.getScaledHeight());
        return new HudBox(TAB_LIST_HUD, box[0], box[1], box[2], box[3], 0f);
    }

    /**
     * The largest tab list item scale whose list still fits the screen width (cycle B's fit rule,
     * {@link TabListLayout#effectiveScale}): Edit HUD's corner resize stops growing there. {@code CornerResize.MAX_SCALE}
     * when there is no list to measure.
     */
    public static float tabListFitScale(Minecraft mc, ClientSettings cfg) {
        if (mc == null || cfg == null || mc.ingameGUI == null || mc.theWorld == null) return CornerResize.MAX_SCALE;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return CornerResize.MAX_SCALE;
        ScaledResolution r = new ScaledResolution(mc);
        float[] m = ((TabOverlayScale) mc.ingameGUI.getTabList())
                .bedwarsqol$measure(r.getScaledWidth(), board, board.getObjectiveInDisplaySlot(0));
        return m == null ? CornerResize.MAX_SCALE : TabListLayout.effectiveScale(CornerResize.MAX_SCALE, m[4], r.getScaledWidth());
    }

    /**
     * The chat item's slot ({@link ChatLayout}): {@code closedLineCount} rows whose bottom the item's anchor
     * holds, never clamped, as {@code {x, y, w, h, rowHeight, closedRows}}; null before the in-game GUI exists.
     */
    private static float[] chatSlot(Minecraft mc, ClientSettings cfg, float sw, float sh) {
        if (mc == null || cfg == null || mc.ingameGUI == null || mc.gameSettings == null) return null;
        GuiNewChat chat = mc.ingameGUI.getChatGUI();
        float f = chat.getChatScale(), s = cfg.chatHudScale;
        int closed = ChatLayout.closedLineCount(
                GuiNewChat.calculateChatboxHeight(mc.gameSettings.chatHeightUnfocused) / ChatLayout.ROW_HEIGHT);
        float[] auto = ChatLayout.autoSlot(ChatLayout.vanillaSlot((int) sh, chat.getChatWidth(), f, closed), sh, s);
        HudModuleState st = HudModules.get(cfg, CHAT_HUD); // the stored slot to a fraction of a GUI pixel
        float[] slot = ChatLayout.slot(st.exactX(), st.exactY(), st.anchor, auto, sw, sh);
        return new float[]{slot[0], slot[1], slot[2], slot[3], ChatLayout.ROW_HEIGHT * f * s, closed};
    }

    /**
     * The chat's box: the rows a closed chat shows (at least one), at the slot's bottom, clamped on screen.
     * Its bottom-left is where vanilla's chat is drawn from and where clicks are measured from; an open
     * chat grows upward from it.
     */
    public static HudBox chatBox(Minecraft mc, ClientSettings cfg) {
        if (mc == null || cfg == null) return null;
        ScaledResolution r = new ScaledResolution(mc);
        float sw = r.getScaledWidth(), sh = r.getScaledHeight();
        float[] slot = chatSlot(mc, cfg, sw, sh);
        if (slot == null) return null;
        int drawn = ((ChatItemAccess) mc.ingameGUI.getChatGUI()).bedwarsqol$drawnLineCount();
        float[] box = ChatLayout.visibleBox(slot, ChatLayout.boxRows(drawn, (int) slot[5]), slot[4], sw, sh);
        return new HudBox(CHAT_HUD, box[0], box[1], box[2], box[3], 0f);
    }

    /** The {@code drawChat} call-site transform {@code {tx, ty, scale}}; null at vanilla's spot and size. */
    public static float[] chatTransform(Minecraft mc, ClientSettings cfg) {
        HudBox box = chatBox(mc, cfg);
        if (box == null) return null;
        float s = cfg.chatHudScale;
        float[] t = ChatLayout.callSiteTranslate(box.x, box.bottom(), s, new ScaledResolution(mc).getScaledHeight());
        if (t[0] == 0f && t[1] == 0f && s == 1f) return null;
        return new float[]{t[0], t[1], s};
    }

    /** A raw LWJGL mouse x over the chat item, as vanilla's {@code getChatComponent} expects it over vanilla's chat. */
    public static int chatRawX(int rawX) {
        Minecraft mc = Minecraft.getMinecraft();
        ClientSettings cfg = BedwarsQol.config;
        HudBox box = chatBox(mc, cfg);
        if (box == null) return rawX;
        return ChatLayout.toVanillaRawX(rawX, new ScaledResolution(mc).getScaleFactor(), box.x, cfg.chatHudScale);
    }

    /** As {@link #chatRawX} for the raw mouse y (device px from the window's bottom). */
    public static int chatRawY(int rawY) {
        Minecraft mc = Minecraft.getMinecraft();
        ClientSettings cfg = BedwarsQol.config;
        HudBox box = chatBox(mc, cfg);
        if (box == null) return rawY;
        ScaledResolution r = new ScaledResolution(mc);
        return ChatLayout.toVanillaRawY(rawY, r.getScaleFactor(), r.getScaledHeight(), box.bottom(), cfg.chatHudScale);
    }

    private static void addEditorBox(List<HudBox> boxes, ClientSettings cfg, HudBox box) {
        if (box == null) return;
        HudModuleState state = HudModules.get(cfg, box.id);
        boxes.add(state != null && !state.enabled ? box.hidden() : box);
    }

    /** One module's Edit HUD box, measured alone (the corner-resize wall probe), as {@link #getEditorBoxes} has it. */
    public static HudBox getEditorBox(Minecraft mc, ClientSettings cfg, String id) {
        if (mc == null || cfg == null) return null;
        boolean was = editing;
        editing = true;
        try {
            HudBox box = editorItemBox(mc, cfg, id, previewFacts(mc));
            if (box == null) return null;
            HudModuleState state = HudModules.get(cfg, id);
            return state != null && !state.enabled ? box.hidden() : box;
        } finally {
            editing = was;
        }
    }

    /**
     * Stores module {@code id} with its top-left at (x, y), anchored to the screen third it is in, to a fraction of a
     * GUI pixel (the int fields get exactly what the whole-pixel path stored; see {@link ClientSettings#hudSubPixel}).
     * Every pin and placement goes through here. For the chat, (x, y, width, height) is its visible box and the stored
     * slot shares the box's bottom ({@link ChatLayout#storedSlot}, the chat slot rule of cycle B, a contract).
     */
    public static void setHudAbsolutePosition(ClientSettings cfg, String id, float x, float y, float width, float height, float screenWidth, float screenHeight) {
        HudModuleState state = HudModules.get(cfg, id);
        if (state == null) return;
        if (CHAT_HUD.equals(id)) {
            float[] slot = chatSlot(Minecraft.getMinecraft(), cfg, screenWidth, screenHeight);
            float slotH = slot == null ? height : slot[3];
            // exact, so a wall-safe spot stays clear and a one-screen-pixel nudge sticks (code review round 2, I1);
            // the int fields get ChatLayout.storedSlot's whole position
            float[] at = ChatLayout.exactStoredSlot(new float[]{x, y, width, height}, slotH, screenWidth, screenHeight);
            HudModules.set(cfg, id, state.withExactPosition(at[0], at[1], (int) at[2]));
            return;
        }
        HudModules.set(cfg, id, HudPins.pinned(state, x, y, width, height, screenWidth, screenHeight));
    }

    /**
     * Pin-on-change: every shown module still on Auto, other than {@code changingIds}, is stored where it is drawn now,
     * so a change to those items moves nothing else (the Auto layout re-flows around every moved module).
     */
    public static void pinShownAuto(Minecraft mc, ClientSettings cfg, Collection<String> changingIds) {
        if (mc == null || cfg == null) return;
        ScaledResolution r = new ScaledResolution(mc);
        float sw = r.getScaledWidth(), sh = r.getScaledHeight();
        List<HudBox> boxes = getEditorBoxes(mc, cfg); // every spot as it is now, before the first write
        // and the size Team Upgrades is drawn at now: a panel the default layout stepped down keeps it once pinned,
        // as when it is moved itself (pinDrawnScale); read before the first pin re-flows the layout
        float teamScale = cfg.teamUpgradesHudAnchor == HudPlacement.AUTO ? teamUpgradesScale(cfg) : cfg.teamUpgradesHudScale;
        for (HudBox b : boxes) {
            // only modules the Auto layout places: an overlay on Auto sits at its vanilla spot, which no change moves
            if (!HudModules.ids().contains(b.id)) continue;
            HudModuleState s = HudModules.get(cfg, b.id);
            if (s != null && HudPins.shouldPin(b.id, b.shown, s.anchor, changingIds)) {
                if (TEAM_UPGRADES_HUD.equals(b.id)) {
                    cfg.teamUpgradesHudScale = teamScale;
                    // the Auto layout holds its top edge (place); pinned the same way, the live panel, smaller than
                    // the Full box pinned here, stays put (outcome review round 1, I1)
                    HudModules.set(cfg, b.id, HudPins.pinnedHoldingTop(HudModules.get(cfg, b.id), b.x, b.y, b.width, b.height, sw, sh));
                    continue;
                }
                setHudAbsolutePosition(cfg, b.id, b.x, b.y, b.width, b.height, sw, sh);
            }
        }
    }

    /**
     * Reset Positions (G26): every module goes to its {@link DefaultHudLayout} spot for this screen, measured at the
     * stable sample sizes the Auto layout uses (D5); a hidden module and any module without a spot go on Auto. Only
     * position, anchor and fraction change: size, on/off and Background stay. The caller saves.
     */
    public static void resetPositions(Minecraft mc, ClientSettings cfg) {
        if (mc == null || cfg == null) return;
        cfg.sanitize();
        ScaledResolution r = new ScaledResolution(mc);
        List<DefaultHudLayout.Item> items = new ArrayList<DefaultHudLayout.Item>();
        // every item: the scoreboard, chat and tab list have no spot, so they go back to Auto (their vanilla spot)
        for (String id : HudModules.allIds()) {
            HudModuleState s = HudModules.get(cfg, id);
            if (s == null) continue;
            Size size = layoutSize(mc, cfg, id);
            items.add(new DefaultHudLayout.Item(id, size.width, size.height, layoutPad(cfg, id), s.enabled));
        }
        Map<String, DefaultHudLayout.Spot> spots = DefaultHudLayout.place(r.getScaledWidth(), r.getScaledHeight(), items);
        for (String id : HudModules.allIds()) {
            DefaultHudLayout.Spot spot = spots.get(id);
            if (spot != null) HudModules.set(cfg, id, spot.apply(HudModules.get(cfg, id)));
        }
    }

    /**
     * Shows modules {@code ids}: the other shown Auto modules are pinned first, then each goes on Auto and takes the
     * first free Auto spot, whatever spot it had before it was hidden. A module that comes on with them (the two gen
     * timers share one switch) is newly shown too.
     */
    public static void show(Minecraft mc, ClientSettings cfg, Collection<String> ids) {
        Map<String, HudModuleState> before = HudModules.snapshot(cfg);
        pinShownAuto(mc, cfg, ids);
        for (String id : ids) {
            HudModuleState s = HudModules.get(cfg, id);
            if (s != null) HudModules.set(cfg, id, HudPins.shown(s));
        }
        for (String id : HudModules.ids()) {
            HudModuleState was = before.get(id), now = HudModules.get(cfg, id);
            if (was != null && now != null && !was.enabled && now.enabled && !ids.contains(id)) {
                HudModules.set(cfg, id, HudPins.shown(now));
            }
        }
    }

    /**
     * Draws every module: in play with live data; in the editor ({@code editor}) each with live data unless it has
     * nothing live to show. Every draw line passes {@code editor && sample(ID, f)}, never {@code editor} alone.
     */
    private static void render(Minecraft mc, ClientSettings cfg, boolean editor) {
        PreviewData.Facts f = editor ? previewFacts(mc) : null;
        if (cfg.potionStatusEnabled) drawPotionHud(mc, cfg, editor && sample(POTION_HUD, f));
        if (cfg.armorTypeEnabled) drawArmorHud(mc, cfg, editor && sample(ARMOR_HUD, f));
        drawInventoryHud(mc, cfg, editor && sample(INVENTORY_HUD, f));
        drawTimerHud(mc, cfg, editor && sample(DIAMOND_TIMER_HUD, f), true);
        drawTimerHud(mc, cfg, editor && sample(EMERALD_TIMER_HUD, f), false);
        if (cfg.keystrokesEnabled) drawKeystrokesHud(mc, cfg, editor && sample(KEYSTROKES_HUD, f));
        drawTextModule(mc, cfg, FPS_HUD, editor && sample(FPS_HUD, f));
        drawTextModule(mc, cfg, CPS_HUD, editor && sample(CPS_HUD, f));
        drawTextModule(mc, cfg, PING_HUD, editor && sample(PING_HUD, f));
        drawHeightLimitHud(mc, cfg, editor && sample(HEIGHT_LIMIT_HUD, f));
        // Team Upgrades has nothing live outside an active game: the editor then shows its example (PreviewData's rule)
        drawTeamUpgradesHud(mc, cfg, editor && teamUpgradesRows(cfg, false).isEmpty());
        // In game Session Stats draws later, over everything (onRenderPost); in the editor it is the top module.
        if (editor) drawSessionHud(mc, cfg, sample(SESSION_HUD, f));
    }

    /**
     * The panel behind a module whose Background is on: a flat, square see-through panel (no corner
     * radius, no border) behind a HUD element's content box expanded by a scale-aware padding.
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
    /** Panel padding at module scale {@code scale}, from the scale the module is really drawn at (its text scale). */
    private static float panelPad(float scale) {
        return HudBackground.pad(ts(scale));
    }

    /**
     * Space around module {@code id}'s content box: its panel's padding while its Background is on, else the plain
     * margin. Keystrokes keeps the margin either way: its caps are its background. FPS, CPS and Ping are boxes that
     * are their own panel.
     */
    private static float padFor(ClientSettings cfg, String id, float scale) {
        if (KEYSTROKES_HUD.equals(id)) return CONTENT_MARGIN;
        // FPS, CPS, Ping, Armor and the gen timers: the box is the panel, so no padding around it; bare content keeps
        // the margin
        if (isTextModule(id) || isIconBox(id)) return HudModules.background(cfg, id) ? 0f : CONTENT_MARGIN;
        return HudModules.background(cfg, id) ? panelPad(scale) : CONTENT_MARGIN;
    }

    /** Module {@code id}'s panel, if its Background is on. */
    private static void drawPanel(ClientSettings cfg, String id, HudBox box, float scale, boolean example) {
        if (HudModules.background(cfg, id)) drawHudBackground(box, scale, example);
    }

    /**
     * In the Edit HUD preview the panel stops 1 px short of its box, so the editor's 1 px frame on the box
     * edge sits over the world like every other module's frame.
     */
    private static void drawHudBackground(HudBox box, float scale, boolean example) {
        float pad = panelPad(scale) - (example || editing ? 1f : 0f);
        float x1 = box.x - pad, y1 = box.y - pad;
        float x2 = box.right() + pad, y2 = box.bottom() + pad;
        GuiRender.rect(x1, y1, x2, y2, HUD_BG_FILL); // square, so the Edit HUD frame matches its edge
    }

    private static void drawPotionHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = potionBox(mc, cfg, example, false);
        if (box == null) return;
        List<PotionEntry> entries = potionEntries(mc, example);
        if (entries.isEmpty()) return;
        drawPanel(cfg, POTION_HUD, box, cfg.potionHudScale, example);
        drawPotionImages(mc, cfg, entries, box.x, box.y);
    }

    private static void drawArmorHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = armorBox(mc, cfg, example, false);
        if (box == null) return;
        ItemStack leggings = currentLeggings(mc, example);
        if (leggings == null) return;
        float e = ts(cfg.armorHudScale);
        float ix = box.x, iy = box.y;
        if (HudModules.background(cfg, ARMOR_HUD)) {
            GuiRender.rect(box.x, box.y, box.right(), box.bottom(), HudBackground.FILL);
            ix += TextModules.HPAD * e;
            iy += ICON_BOX_DY * e;
        }
        drawArmorIcon(mc, leggings, onDeviceGrid(mc, ix), onDeviceGrid(mc, iy), e);
    }

    /**
     * Lunar's potion rows ({@link PotionRows}): icon, then the name with its level in white over the time in gray.
     * Everything is laid out at the text's own scale, so the text always fits its box. A row with under 10 s left
     * blinks; the rows under it keep their place. The icon is the modern one once {@link EffectIconTextures} has it,
     * else the 1.8.9 one.
     */
    private static void drawPotionImages(Minecraft mc, ClientSettings cfg, List<PotionEntry> entries, float x, float y) {
        float e = ts(cfg.potionHudScale);
        float textX = x + (PotionRows.ICON + PotionRows.GAP) * e;
        long now = Minecraft.getSystemTime();
        boolean requested = false;
        for (int i = 0; i < entries.size(); i++) {
            PotionEntry entry = entries.get(i);
            if (PotionRows.blinkHidden(entry.durationTicks, entry.max, now)) continue;
            if (!requested) {
                EffectIconTextures.request(); // once per session, and only once the HUD draws a row
                requested = true;
            }
            float rowY = y + i * PotionRows.ROW * e;
            int idx = entry.potion.getStatusIconIndex();
            boolean modern = EffectIconTextures.bind(entry.potion.id);
            if (modern || idx >= 0) {
                if (!modern) mc.getTextureManager().bindTexture(INVENTORY_TEXTURE);
                GlStateManager.color(1f, 1f, 1f, 1f);
                GlStateManager.enableTexture2D();
                GlStateManager.enableBlend();
                GlStateManager.blendFunc(770, 771);
                GlStateManager.pushMatrix();
                GlStateManager.translate(x, rowY, 0f);
                GlStateManager.scale(e, e, 1f);
                if (modern) Gui.drawModalRectWithCustomSizedTexture(0, 0, 0, 0, 18, 18, 18f, 18f);
                else Gui.drawModalRectWithCustomSizedTexture(0, 0, idx % 8 * 18, 198 + idx / 8 * 18, 18, 18, 256f, 256f);
                GlStateManager.popMatrix();
            }
            BedwarsQolFont.drawHud(entry.label, textX, rowY + PotionRows.NAME_DY * e, e, PotionRows.NAME_COLOR, true,
                    BedwarsQolFont.Weight.BOLD);
            BedwarsQolFont.drawHud(entry.time, textX, rowY + PotionRows.TIME_DY * e, e, PotionRows.TIME_COLOR, true,
                    BedwarsQolFont.Weight.BOLD);
        }
        resetGlState();
    }

    private static void drawArmorIcon(Minecraft mc, ItemStack stack, float x, float y, float scale) {
        RenderItem renderItem = mc.getRenderItem();
        if (renderItem == null) return;

        GlStateManager.pushMatrix();
        GlStateManager.enableDepth();
        GlStateManager.enableRescaleNormal();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.translate(x, y, 0f);
        GlStateManager.scale(scale, scale, 1f);
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

    /**
     * The scale a module's text is laid out and drawn at: its own, or in Minecraft's font snapped to whole device
     * pixels per font pixel (see {@link BedwarsQolFont#textScale}). Measure with it wherever text is drawn with it.
     */
    private static float ts(float scale) {
        return BedwarsQolFont.hudScale(scale);
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
        BedwarsQolFont.drawHud(text, x, y, scale, color, false, weight);
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
        float e = ts(cfg.potionHudScale);
        float name = 0f, time = 0f;
        for (PotionEntry entry : entries) {
            name = Math.max(name, fontWidth(entry.label));
            time = Math.max(time, fontWidth(entry.time));
        }
        float trail = BedwarsQolFont.minecraft() ? 1f : 0f; // Minecraft's font ends every string on an empty column
        return new Size(PotionRows.width(name, time, trail) * e, PotionRows.height(entries.size()) * e);
    }

    private static Size armorSize(Minecraft mc, ClientSettings cfg, boolean example) {
        ItemStack leggings = currentLeggings(mc, example);
        if (leggings == null) return Size.EMPTY;
        // the icon follows the text scale (whole device px per texel), like every text item's size
        float e = ts(cfg.armorHudScale);
        if (HudModules.background(cfg, ARMOR_HUD)) {
            return new Size((ARMOR_ICON_SIZE + 2f * TextModules.HPAD) * e, TextModules.BOX_H * e);
        }
        return new Size(ARMOR_ICON_SIZE * e, ARMOR_ICON_SIZE * e);
    }

    private static HudBox potionBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !cfg.potionStatusEnabled) return null;
        if (!all && cfg.potionInGameOnly && !bedwarsActive(example)) return null;
        Size size = potionSize(mc, cfg, example);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution resolution = new ScaledResolution(mc);
        float pad = padFor(cfg, POTION_HUD, cfg.potionHudScale);
        float[] at = place(mc, cfg, POTION_HUD, cfg.potionHudX, cfg.potionHudY, cfg.potionHudAnchor, size.width, size.height, pad, resolution);
        return new HudBox(POTION_HUD, at[0], at[1], size.width, size.height, pad);
    }

    private static HudBox armorBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !cfg.armorTypeEnabled) return null;
        if (!all && cfg.armorInGameOnly && !bedwarsActive(example)) return null;
        Size size = armorSize(mc, cfg, example);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution resolution = new ScaledResolution(mc);
        float pad = padFor(cfg, ARMOR_HUD, cfg.armorHudScale);
        float[] at = place(mc, cfg, ARMOR_HUD, cfg.armorHudX, cfg.armorHudY, cfg.armorHudAnchor, size.width, size.height, pad, resolution);
        return new HudBox(ARMOR_HUD, at[0], at[1], size.width, size.height, pad);
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
            // Team Upgrades stacks under the module above it and grows down: its top edge stays put.
            if (TEAM_UPGRADES_HUD.equals(id)) a %= 3;
            x = HudPlacement.absoluteX(HudPlacement.storedX(exX, a, exW, sw), a, width, sw);
            y = HudPlacement.absoluteY(HudPlacement.storedY(exY, a, exH, sh), a, height, sh);
        } else {
            // A moved module's stored spot plus its sub-pixel part (ClientSettings.hudSubPixel), by id.
            float[] frac = cfg.hudSubPixel == null ? null : cfg.hudSubPixel.get(id);
            boolean fine = frac != null && frac.length == 2;
            x = HudPlacement.absoluteX(storedX + (fine ? frac[0] : 0f), anchor, width, sw);
            y = HudPlacement.absoluteY(storedY + (fine ? frac[1] : 0f), anchor, height, sh);
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
    private static final float HOTBAR_W = HudWalls.HOTBAR_W;
    private static final float HOTBAR_ZONE_H = HudWalls.HOTBAR_ZONE_H; // Edit HUD's hotbar wall is the same zone
    private static final Map<String, Spot[]> DEFAULT_SPOTS = new LinkedHashMap<String, Spot[]>();
    static {
        DEFAULT_SPOTS.put(DIAMOND_TIMER_HUD, new Spot[]{new Spot(Horizontal.RIGHT, Vertical.TOP, Slide.LEFT)});
        DEFAULT_SPOTS.put(EMERALD_TIMER_HUD, new Spot[]{new Spot(Horizontal.RIGHT, Vertical.TOP, Slide.LEFT)});
        DEFAULT_SPOTS.put(SESSION_HUD, new Spot[]{new Spot(Horizontal.RIGHT, Vertical.TOP, Slide.DOWN),
                new Spot(Horizontal.LEFT, Vertical.TOP, Slide.DOWN)});
        // FPS, CPS and Ping go first down the left edge, then Armor and Potion under them (the approved layout)
        DEFAULT_SPOTS.put(FPS_HUD, new Spot[]{new Spot(Horizontal.LEFT, Vertical.TOP, Slide.DOWN)});
        DEFAULT_SPOTS.put(CPS_HUD, new Spot[]{new Spot(Horizontal.LEFT, Vertical.TOP, Slide.DOWN)});
        DEFAULT_SPOTS.put(PING_HUD, new Spot[]{new Spot(Horizontal.LEFT, Vertical.TOP, Slide.DOWN)});
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
        DEFAULT_SPOTS.put(TEAM_UPGRADES_HUD, new Spot[]{new Spot(Horizontal.LEFT, Vertical.MIDDLE, Slide.DOWN),
                new Spot(Horizontal.LEFT, Vertical.MIDDLE, Slide.UP),
                new Spot(Horizontal.LEFT, Vertical.BOTTOM, Slide.UP)});
    }
    private static String layoutKey;
    private static Map<String, float[]> layoutCache;
    /** The scale Team Upgrades fits at in its default spot, worked out with the layout. */
    private static float layoutTeamScale = 1f;

    /** Drawn box {x, y, width, height} of every module still in its default spot, by id. */
    private static Map<String, float[]> defaultLayout(Minecraft mc, ClientSettings cfg, float sw, float sh) {
        List<float[]> overlays = overlayObstacles(mc, cfg, sw, sh);
        String key = layoutKey(cfg, sw, sh, overlays);
        if (key.equals(layoutKey)) return layoutCache;

        List<HudLayout.Box> obstacles = new ArrayList<HudLayout.Box>();
        obstacles.add(new HudLayout.Box((sw - HOTBAR_W) / 2f, sh - HOTBAR_ZONE_H, HOTBAR_W, HOTBAR_ZONE_H));
        // nothing Auto lands on or right by the crosshair (a full-size Team Upgrades at half width did)
        float kw = sw * CROSSHAIR_KEEP_OUT, kh = sh * CROSSHAIR_KEEP_OUT;
        obstacles.add(new HudLayout.Box((sw - kw) / 2f, (sh - kh) / 2f, kw, kh));
        for (float[] o : overlays) obstacles.add(new HudLayout.Box(o[0], o[1], o[2], o[3]));
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
            HudLayout.Item item;
            if (TEAM_UPGRADES_HUD.equals(id)) {
                item = new HudLayout.Item(id, 0f, 0f, e.getValue()); // sized below, once the modules before it are known
            } else {
                Size size = layoutSize(mc, cfg, id);
                float pad = layoutPad(cfg, id);
                item = new HudLayout.Item(id, size.width + 2f * pad, size.height + 2f * pad, e.getValue());
            }
            (state.enabled ? shown : hidden).add(item);
        }
        shown.addAll(hidden); // hidden modules only take what room is left
        HudLayout layout = new HudLayout(sw, sh, LAYOUT_EDGE, LAYOUT_GAP);
        fitTeamUpgrades(layout, shown, obstacles, cfg);
        layoutCache = layout.place(shown, obstacles);
        layoutKey = key;
        return layoutCache;
    }

    /**
     * The scoreboard and chat as Auto-layout obstacles (as walls in Edit HUD; the tab list is neither), whole GUI px:
     * the scoreboard's live box while it shows, and the chat's whole closed-chat slot, so the layout does not move
     * while the chat fills up. (Edit HUD frames the chat's visible rows.)
     */
    private static List<float[]> overlayObstacles(Minecraft mc, ClientSettings cfg, float sw, float sh) {
        List<float[]> out = new ArrayList<float[]>(2);
        HudModuleState sb = HudModules.get(cfg, SCOREBOARD_HUD);
        HudBox board = sb != null && sb.enabled ? scoreboardBox(mc, cfg) : null;
        if (board != null) out.add(whole(board.x, board.y, board.width, board.height));
        float[] slot = chatSlot(mc, cfg, sw, sh);
        if (slot != null) out.add(whole(slot[0], slot[1], slot[2], slot[3]));
        return out;
    }

    private static float[] whole(float x, float y, float w, float h) {
        float x0 = (float) Math.floor(x), y0 = (float) Math.floor(y);
        return new float[]{x0, y0, (float) Math.ceil(x + w) - x0, (float) Math.ceil(y + h) - y0};
    }

    /**
     * Team Upgrades in its default spot draws at the player's scale, except that a Full panel with no free
     * spot (a small window or a large HUD Size, beside Map Info and the hotbar) steps down one font pixel at
     * a time until it has one (review code-1 I2; HUD Size 2 smoke). Replaces its placeholder in {@code items}.
     */
    private static void fitTeamUpgrades(HudLayout layout, List<HudLayout.Item> items, List<HudLayout.Box> obstacles,
                                        ClientSettings cfg) {
        float scale = cfg.teamUpgradesHudScale;
        layoutTeamScale = scale;
        int at = -1;
        for (int i = 0; i < items.size(); i++) if (TEAM_UPGRADES_HUD.equals(items.get(i).id)) at = i;
        if (at < 0) return;
        // Minecraft's font steps one screen pixel per font pixel and stops at one (the GUI scale 1 size);
        // the modern font steps by an eighth and stops at half size.
        boolean mcFont = BedwarsQolFont.minecraft();
        float px = 1f / new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
        float step = mcFont ? px : 0.125f;
        float min = Math.min(scale, mcFont ? px : 0.5f);
        HudLayout.Sizer sizer = s -> {
            Size size = teamUpgradesReserve(s);
            float pad = padFor(cfg, TEAM_UPGRADES_HUD, s);
            return new float[]{size.width + 2f * pad, size.height + 2f * pad};
        };
        HudLayout.Fit fit = layout.fit(items.subList(0, at), obstacles, TEAM_UPGRADES_HUD, scale, step, min, sizer,
                DEFAULT_SPOTS.get(TEAM_UPGRADES_HUD), TEAM_UPGRADES_CROWDED_SPOTS);
        layoutTeamScale = fit.scale;
        float[] size = sizer.size(fit.scale);
        items.set(at, new HudLayout.Item(TEAM_UPGRADES_HUD, size[0], size[1], fit.spots));
    }

    /**
     * With the left edge full: beside the left column (review code-2 I1), the top first. Beside it in the middle
     * runs into the crosshair on a narrow window (half width: 369 GUI px), and sliding on past the crosshair's
     * keep-out puts the panel right next to it.
     */
    private static final HudLayout.Spot[] TEAM_UPGRADES_CROWDED_SPOTS = {
            new Spot(Horizontal.LEFT, Vertical.TOP, Slide.RIGHT), new Spot(Horizontal.LEFT, Vertical.MIDDLE, Slide.RIGHT)};

    /** The default layout keeps out of the middle fifth of the screen (each way), around the crosshair. */
    private static final float CROSSHAIR_KEEP_OUT = 0.2f;

    /**
     * Whether HUD item {@code id} is drawn only at whole steps of its scale ({@link ScaleSteps}): in Minecraft's font
     * every HUD module lays out at its text scale, so Edit HUD resizes it step by step; the scoreboard, chat and tab
     * list scale smoothly.
     */
    public static boolean stepsScale(String id) {
        return BedwarsQolFont.minecraft() && HudModules.ids().contains(id);
    }

    /**
     * The scale HUD item {@code id} is drawn at now: Team Upgrades in its default spot at the layout's fitted scale,
     * the tab list shrunk to the screen width, and a stepped item ({@link #stepsScale}) at its step. What the Edit HUD
     * badge shows and a corner resize starts from.
     */
    public static float drawnScale(ClientSettings cfg, String id) {
        HudModuleState state = HudModules.get(cfg, id);
        if (state == null) return 1f;
        float s = TEAM_UPGRADES_HUD.equals(id) ? teamUpgradesScale(cfg) : state.scale;
        if (TAB_LIST_HUD.equals(id)) s = Math.min(s, tabListFitScale(Minecraft.getMinecraft(), cfg));
        return stepsScale(id) ? ts(s) : s;
    }

    /**
     * Edit HUD calls this before it moves or resizes module {@code id}: a Team Upgrades panel the default
     * layout stepped down keeps the size it is drawn at once it leaves its default spot (review code-2 I2).
     */
    public static void pinDrawnScale(ClientSettings cfg, String id) {
        if (cfg == null || !TEAM_UPGRADES_HUD.equals(id) || cfg.teamUpgradesHudAnchor != HudPlacement.AUTO) return;
        cfg.teamUpgradesHudScale = teamUpgradesScale(cfg);
    }

    /** Everything the default layout depends on; it is worked out again only when this changes. */
    private static String layoutKey(ClientSettings cfg, float sw, float sh, List<float[]> overlays) {
        // the font sizes every text module (Minecraft's snaps to the GUI scale, which the screen size follows)
        // and the scale factor: 1280x720 at 2 and 1920x1080 at 3 are both 640x360, with different font steps
        int factor = new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
        StringBuilder b = new StringBuilder().append(sw).append('x').append(sh).append('@').append(factor)
                .append('|').append(cfg.guiFont);
        for (float[] o : overlays) b.append("|o").append(o[0]).append(',').append(o[1]).append(',').append(o[2]).append(',').append(o[3]);
        for (String id : DEFAULT_SPOTS.keySet()) {
            HudModuleState s = HudModules.get(cfg, id);
            if (s != null) b.append('|').append(s.exactX()).append(',').append(s.exactY()).append(',').append(s.anchor)
                    .append(',').append(s.scale).append(',').append(s.enabled).append(',').append(s.background);
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
        if (isTextModule(id)) return textModuleSize(cfg, id, textSegs(mc, cfg, id, true));
        // Team Upgrades: the Full example at its drawn scale (Reset Positions); the Auto layout fits it itself (fitTeamUpgrades)
        if (TEAM_UPGRADES_HUD.equals(id)) return teamUpgradesReserve(teamUpgradesScale(cfg));
        return Size.EMPTY;
    }

    private static float layoutPad(ClientSettings cfg, String id) {
        HudModuleState s = HudModules.get(cfg, id);
        return s == null ? CONTENT_MARGIN : padFor(cfg, id, s.scale);
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
        if (TEAM_UPGRADES_HUD.equals(id)) return teamUpgradesEditorBox(mc, cfg, true);
        if (isTextModule(id)) return textBox(mc, cfg, id, true, true);
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
            out.add(new PotionEntry(Potion.moveSpeed, 1, 1680, false)); // Speed II 1:24
            out.add(new PotionEntry(Potion.jump, 4, 760, false));       // Jump Boost V 0:38
            return out;
        }

        Collection<PotionEffect> active = mc.thePlayer.getActivePotionEffects();
        if (active.isEmpty()) return Collections.emptyList();

        List<PotionEntry> entries = new ArrayList<>(active.size());
        for (PotionEffect eff : active) {
            Potion potion = potionFor(eff);
            if (potion == null) continue;
            entries.add(new PotionEntry(potion, eff.getAmplifier(), eff.getDuration(), eff.getIsPotionDurationMax()));
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
        return example || editing || HypixelContext.isInActiveBedwarsGame();
    }

    private static HudBox inventoryBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !cfg.inventoryHudEnabled) return null; // works anywhere unless "In Game Only" is set
        if (!all && cfg.inventoryInGameOnly && !bedwarsActive(example)) return null;
        Size size = inventorySize(cfg);
        ScaledResolution r = new ScaledResolution(mc);
        float pad = padFor(cfg, INVENTORY_HUD, cfg.inventoryHudScale);
        float[] at = place(mc, cfg, INVENTORY_HUD, cfg.inventoryHudX, cfg.inventoryHudY, cfg.inventoryHudAnchor, size.width, size.height, pad, r);
        return new HudBox(INVENTORY_HUD, at[0], at[1], size.width, size.height, pad);
    }

    private static Size inventorySize(ClientSettings cfg) {
        float e = ts(cfg.inventoryHudScale); // icons at the text scale, like every other item
        return new Size(invPanelWidth() * e, invPanelHeight() * e);
    }

    private static void drawInventoryHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = inventoryBox(mc, cfg, example, false);
        if (box == null) return;
        drawPanel(cfg, INVENTORY_HUD, box, cfg.inventoryHudScale, example);
        drawMiniInventory(mc, cfg, box.x, box.y, example);
    }

    private static void drawMiniInventory(Minecraft mc, ClientSettings cfg, float x, float y, boolean example) {
        float scale = ts(cfg.inventoryHudScale);
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
        float pad = padFor(cfg, id, diamond ? cfg.diamondTimerHudScale : cfg.emeraldTimerHudScale);
        float[] at = place(mc, cfg, id, storedX, storedY, anchor, size.width, size.height, pad, r);
        return new HudBox(id, at[0], at[1], size.width, size.height, pad);
    }

    private static Size timerSize(Minecraft mc, ClientSettings cfg, boolean example, boolean diamond) {
        float e = ts(diamond ? cfg.diamondTimerHudScale : cfg.emeraldTimerHudScale); // icon and number at the text scale
        IconCount entry = timerEntry(example, diamond);
        Size inner = iconCountsSize(mc, Collections.singletonList(entry), e);
        if (!HudModules.background(cfg, diamond ? DIAMOND_TIMER_HUD : EMERALD_TIMER_HUD)) return inner;
        // Minecraft's font ends every string on an empty column: drop it so both sides keep HPAD, as FPS does
        float trail = BedwarsQolFont.minecraft() && !entry.count.isEmpty() ? e : 0f;
        return new Size(inner.width - trail + 2f * TextModules.HPAD * e, TextModules.BOX_H * e);
    }

    private static void drawTimerHud(Minecraft mc, ClientSettings cfg, boolean example, boolean diamond) {
        HudBox box = timerBox(mc, cfg, example, diamond, false);
        if (box == null) return;
        float e = ts(diamond ? cfg.diamondTimerHudScale : cfg.emeraldTimerHudScale);
        float ix = box.x, iy = box.y;
        if (HudModules.background(cfg, box.id)) {
            GuiRender.rect(box.x, box.y, box.right(), box.bottom(), HudBackground.FILL);
            ix += TextModules.HPAD * e;
            iy += ICON_BOX_DY * e;
        }
        drawIconCounts(mc, Collections.singletonList(timerEntry(example, diamond)), onDeviceGrid(mc, ix),
                onDeviceGrid(mc, iy), e);
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

    /** Rows are Inter Regular like the Session Stats rows; labels in the accent, values white. */
    private static final BedwarsQolFont.Weight HEIGHT_ROW_WEIGHT = BedwarsQolFont.Weight.REGULAR;

    private static HudBox heightLimitBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !HeightLimitWatch.visible(cfg, example || editing)) return null;
        Size size = heightLimitSize(mc, cfg, example);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution r = new ScaledResolution(mc);
        float pad = padFor(cfg, HEIGHT_LIMIT_HUD, cfg.heightLimitHudScale);
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
        drawPanel(cfg, HEIGHT_LIMIT_HUD, box, scale, example);
        scale = ts(scale);
        float step = (TEXT_HEIGHT + LINE_GAP) * scale;
        int label = GuiTheme.fromToken(cfg.guiAccent).base();
        List<String> rows = heightLimitRows(mc, example);
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            drawStatRow(row, alignedX(mc, box, row, scale, HEIGHT_ROW_WEIGHT), box.y + i * step, scale, HEIGHT_ROW_WEIGHT, label);
        }
    }

    /**
     * One {@code Label: value [/ Label: value]} row, coloured run by run ({@link StatRowColors}): values white,
     * {@code " / "} dark gray, labels in {@code labelColor}.
     */
    private static void drawStatRow(String row, float x, float y, float scale, BedwarsQolFont.Weight weight, int labelColor) {
        float cx = x;
        for (StatRowSegments.Segment seg : StatRowSegments.split(row)) {
            cx = drawSegment(seg.text, cx, y, scale, StatRowColors.of(seg.kind, labelColor), weight);
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

    // ----- Team Upgrades (upgrades and trap queue, Session Stats look) -----

    /** Next-tier diamond costs, in Minecraft's aqua. */
    private static final int TEAM_COST_COLOR = 0xFF55FFFF;
    /** A trap that just fired, in Minecraft's red; it fades with the row's alpha. */
    private static final int TEAM_FIRED_RGB = 0xFF5555;
    /** Space between the label column and the right-aligned cost column, in local units. */
    private static final float TEAM_COLUMN_GAP = 8f;

    private static TeamUpgradeRows.Layout teamUpgradesLayout(ClientSettings cfg) {
        return cfg.teamUpgradesLayout == ClientSettings.TEAM_UPGRADES_FULL
                ? TeamUpgradeRows.Layout.FULL : TeamUpgradeRows.Layout.COMPACT;
    }

    /** Edit HUD and the default layout measure this, so switching to Full never runs into a neighbour. */
    private static List<TeamUpgradeRows.Row> teamUpgradesFullExample() {
        return TeamUpgradeRows.example(TeamUpgradeRows.Layout.FULL);
    }

    /**
     * The room Team Upgrades takes at module scale {@code scale}, whichever layout is chosen: the larger of the two
     * layouts' full-height examples, so switching layout never runs into a neighbour and either one fills it.
     */
    private static Size teamUpgradesReserve(float scale) {
        Size full = teamUpgradesSize(scale, teamUpgradesFullExample());
        Size compact = teamUpgradesSize(scale, TeamUpgradeRows.example(TeamUpgradeRows.Layout.COMPACT));
        return new Size(Math.max(full.width, compact.width), Math.max(full.height, compact.height));
    }

    /** The rows to draw: the example in the editor; in game the visible state's, empty outside an active game. */
    private static List<TeamUpgradeRows.Row> teamUpgradesRows(ClientSettings cfg, boolean example) {
        if (example) return TeamUpgradeRows.example(teamUpgradesLayout(cfg));
        TeamUpgradeLifecycle lifecycle = TeamUpgradesWatch.lifecycle();
        return TeamUpgradeRows.rows(lifecycle.visible(), teamUpgradesLayout(cfg),
                TeamUpgradeCatalog.bracket(lifecycle.mode()), System.currentTimeMillis());
    }

    /**
     * The scale Team Upgrades draws at: the player's, or in its default spot the one the layout fitted
     * ({@link #fitTeamUpgrades}). A panel the player moved keeps their scale.
     */
    private static float teamUpgradesScale(ClientSettings cfg) {
        if (cfg.teamUpgradesHudAnchor != HudPlacement.AUTO) return cfg.teamUpgradesHudScale;
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution r = new ScaledResolution(mc);
        defaultLayout(mc, cfg, r.getScaledWidth(), r.getScaledHeight());
        return layoutTeamScale;
    }

    /** The editor's and the layout's box: the Full example's size, whichever layout is chosen. */
    private static HudBox teamUpgradesEditorBox(Minecraft mc, ClientSettings cfg, boolean all) {
        if (!all && !cfg.teamUpgradesEnabled) return null;
        return teamUpgradesBox(mc, cfg, teamUpgradesReserve(teamUpgradesScale(cfg)));
    }

    private static HudBox teamUpgradesBox(Minecraft mc, ClientSettings cfg, Size size) {
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution r = new ScaledResolution(mc);
        float pad = padFor(cfg, TEAM_UPGRADES_HUD, teamUpgradesScale(cfg));
        float[] at = place(mc, cfg, TEAM_UPGRADES_HUD, cfg.teamUpgradesHudX, cfg.teamUpgradesHudY, cfg.teamUpgradesHudAnchor, size.width, size.height, pad, r);
        return new HudBox(TEAM_UPGRADES_HUD, at[0], at[1], size.width, size.height, pad);
    }

    /** Widest left column + gap + widest right column wide, one line per row high, at module scale {@code moduleScale}. */
    private static Size teamUpgradesSize(float moduleScale, List<TeamUpgradeRows.Row> rows) {
        if (rows.isEmpty()) return Size.EMPTY;
        float scale = ts(moduleScale);
        float left = 0f, right = 0f;
        for (TeamUpgradeRows.Row row : rows) {
            left = Math.max(left, teamLeftWidth(row) * scale);
            right = Math.max(right, fontWidth(row.right, teamWeight(row)) * scale);
        }
        float width = left + (right > 0f ? TEAM_COLUMN_GAP * scale + right : 0f);
        float height = rows.size() * ((TEXT_HEIGHT + LINE_GAP) * scale) - LINE_GAP * scale - inkTrim(scale);
        return new Size(width, height);
    }

    private static BedwarsQolFont.Weight teamWeight(TeamUpgradeRows.Row row) {
        return row.kind == TeamUpgradeRows.Kind.HEADER ? SESSION_HEADER_WEIGHT : SESSION_ROW_WEIGHT;
    }

    /** Label, then a space and the value when there is one. */
    private static String teamLeftText(TeamUpgradeRows.Row row) {
        return row.value.isEmpty() ? row.label : row.label + " " + row.value;
    }

    private static float teamLeftWidth(TeamUpgradeRows.Row row) {
        return fontWidth(teamLeftText(row), teamWeight(row));
    }

    private static void drawTeamUpgradesHud(Minecraft mc, ClientSettings cfg, boolean example) {
        if (!cfg.teamUpgradesEnabled) return;
        List<TeamUpgradeRows.Row> rows = teamUpgradesRows(cfg, example);
        if (rows.isEmpty()) return; // no active game: nothing at all
        float scale = teamUpgradesScale(cfg);
        // The example sits where the live panel would: the editor's box is the Full panel's, and a smaller
        // panel holds that box's edge nearest the screen side, as in game.
        HudBox box = teamUpgradesBox(mc, cfg, teamUpgradesSize(scale, rows));
        if (box == null) return;
        drawPanel(cfg, TEAM_UPGRADES_HUD, box, scale, example);
        scale = ts(scale);
        int header = GuiTheme.fromToken(cfg.guiAccent).base();
        float step = (TEXT_HEIGHT + LINE_GAP) * scale;
        for (int i = 0; i < rows.size(); i++) {
            TeamUpgradeRows.Row row = rows.get(i);
            float y = box.y + i * step;
            BedwarsQolFont.Weight weight = teamWeight(row);
            if (row.kind == TeamUpgradeRows.Kind.HEADER) {
                fontDraw(row.label, box.x, y, scale, header, weight);
                continue;
            }
            int fired = 0;
            if (row.style == TeamUpgradeRows.Style.FIRED) {
                int a = Math.round(Math.max(0f, Math.min(1f, row.alpha)) * 255f);
                if (a < 8) continue; // the vanilla font would draw an almost transparent colour opaque
                fired = (a << 24) | TEAM_FIRED_RGB;
            }
            boolean dim = row.style == TeamUpgradeRows.Style.DIM;
            int label = fired != 0 ? fired : dim ? StatRowColors.SLASH : StatRowColors.LABEL_NEUTRAL;
            int value = fired != 0 ? fired : dim ? StatRowColors.SLASH : StatRowColors.VALUE;
            float x = box.x;
            if (row.kind == TeamUpgradeRows.Kind.TRAP) {
                // "1 Blindness": the slot number gray, the trap name white
                int sp = row.label.indexOf(' ');
                if (sp > 0) {
                    x = drawSegment(row.label.substring(0, sp + 1), x, y, scale, label, weight);
                    drawSegment(row.label.substring(sp + 1), x, y, scale, value, weight);
                } else {
                    drawSegment(row.label, x, y, scale, value, weight);
                }
            } else if (row.kind == TeamUpgradeRows.Kind.NONE) {
                drawSegment(row.label, x, y, scale, value, weight);
            } else {
                x = drawSegment(row.label, x, y, scale, label, weight);
                if (!row.value.isEmpty()) drawSegment(" " + row.value, x, y, scale, value, weight);
            }
            if (!row.right.isEmpty()) {
                boolean number = Character.isDigit(row.right.charAt(0));
                int color = fired != 0 ? fired : dim ? StatRowColors.SLASH : number ? TEAM_COST_COLOR : StatRowColors.VALUE;
                fontDraw(row.right, box.right() - fontWidth(row.right, weight) * scale, y, scale, color, weight);
            }
        }
    }

    // ----- Session Stats (Lunar-style game + session tally) -----

    private static HudBox sessionBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !SessionStatsWatch.visible(cfg, example || editing)) return null;
        Size size = sessionSize(mc, cfg, example);
        if (size.width <= 0f || size.height <= 0f) return null;
        ScaledResolution r = new ScaledResolution(mc);
        float pad = padFor(cfg, SESSION_HUD, cfg.sessionStatsHudScale);
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
        drawPanel(cfg, SESSION_HUD, box, scale, example);
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
            else drawStatRow(line.primary, lx, ly, scale, line.weight, StatRowColors.LABEL_NEUTRAL);
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

    // ----- FPS, CPS and Ping (TextModules) -----

    /**
     * Armor and the gen timers: one line of icon (and number), drawn in an FPS-sized box ({@link TextModules#BOX_H} tall,
     * {@link TextModules#HPAD} at the sides) while the Background is on, so every one-line item is the same height.
     */
    private static boolean isIconBox(String id) {
        return ARMOR_HUD.equals(id) || DIAMOND_TIMER_HUD.equals(id) || EMERALD_TIMER_HUD.equals(id);
    }

    /** Top of a 16 px icon centred in a one-line box, in font pixels of the box's scale. */
    private static final float ICON_BOX_DY = (TextModules.BOX_H - ARMOR_ICON_SIZE) / 2f;

    /** {@code v} on the device-pixel grid in Minecraft's font, so an item icon drawn at a text step stays crisp. */
    private static float onDeviceGrid(Minecraft mc, float v) {
        if (!BedwarsQolFont.minecraft()) return v;
        int sf = new ScaledResolution(mc).getScaleFactor();
        return Math.round(v * sf) / (float) sf;
    }

    private static boolean isTextModule(String id) {
        return FPS_HUD.equals(id) || CPS_HUD.equals(id) || PING_HUD.equals(id);
    }

    /** The HUD font's advance at scale 1, as TextModules measures. */
    private static final TextModules.Advance HUD_ADVANCE = new TextModules.Advance() {
        @Override
        public float of(String s) {
            return fontWidth(s);
        }
    };

    /** The module's runs, between brackets when its Brackets switch is on (with or without its Background). */
    private static List<TextModules.Seg> textSegs(Minecraft mc, ClientSettings cfg, String id, boolean example) {
        List<TextModules.Seg> segs = textRuns(mc, id, example);
        return HudModules.brackets(cfg, id) ? TextModules.bracketed(segs) : segs;
    }

    private static List<TextModules.Seg> textRuns(Minecraft mc, String id, boolean example) {
        if (FPS_HUD.equals(id)) return TextModules.fps(example ? TextModules.EXAMPLE_FPS : Minecraft.getDebugFPS());
        if (CPS_HUD.equals(id)) {
            if (example) return TextModules.cps(TextModules.EXAMPLE_CPS_LEFT, TextModules.EXAMPLE_CPS_RIGHT);
            long now = Minecraft.getSystemTime();
            return TextModules.cps(LEFT_CLICKS.cps(now), RIGHT_CLICKS.cps(now));
        }
        return TextModules.ping(example ? TextModules.EXAMPLE_PING : pingValue(mc));
    }

    /** The player's own latency as the tab list shows it (only read: no packet is sent), or "--". */
    private static String pingValue(Minecraft mc) {
        NetHandlerPlayClient net = mc.getNetHandler();
        NetworkPlayerInfo info = net == null || mc.thePlayer == null ? null : net.getPlayerInfo(mc.thePlayer.getUniqueID());
        return TextModules.pingText(mc.isSingleplayer(), info != null, info == null ? 0 : info.getResponseTime());
    }

    /**
     * The text module's box, laid out at its text's own scale {@code e} so the text always fits: an 18 px box hugging
     * the text with its Background on, else the bare text (brackets, if on, are part of the runs).
     */
    private static Size textModuleSize(ClientSettings cfg, String id, List<TextModules.Seg> segs) {
        float e = ts(HudModules.get(cfg, id).scale);
        if (HudModules.background(cfg, id)) {
            float trail = BedwarsQolFont.minecraft() ? 1f : 0f; // Minecraft's font ends every string on an empty column
            return new Size(TextModules.boxWidth(segs, HUD_ADVANCE, trail) * e, TextModules.BOX_H * e);
        }
        return new Size(TextModules.bareWidth(segs, HUD_ADVANCE) * e, TextModules.BARE_H * e);
    }

    private static HudBox textBox(Minecraft mc, ClientSettings cfg, String id, boolean example, boolean all) {
        return textBox(mc, cfg, id, textSegs(mc, cfg, id, example), all);
    }

    private static HudBox textBox(Minecraft mc, ClientSettings cfg, String id, List<TextModules.Seg> segs, boolean all) {
        HudModuleState s = HudModules.get(cfg, id);
        if (s == null || (!all && !s.enabled)) return null;
        Size size = textModuleSize(cfg, id, segs);
        float pad = padFor(cfg, id, s.scale);
        float[] at = place(mc, cfg, id, s.x, s.y, s.anchor, size.width, size.height, pad, new ScaledResolution(mc));
        return new HudBox(id, at[0], at[1], size.width, size.height, pad);
    }

    /**
     * One text module: numbers and brackets white, the unit in the accent, CPS's divider and its shadow as rects; text
     * shadowed.
     */
    private static void drawTextModule(Minecraft mc, ClientSettings cfg, String id, boolean example) {
        List<TextModules.Seg> segs = textSegs(mc, cfg, id, example);
        HudBox box = textBox(mc, cfg, id, segs, false);
        if (box == null) return;
        float e = ts(HudModules.get(cfg, id).scale);
        boolean background = HudModules.background(cfg, id);
        int accent = GuiTheme.fromToken(cfg.guiAccent).base();
        float ox = box.x, oy = box.y;
        if (background) {
            GuiRender.rect(box.x, box.y, box.right(), box.bottom(), HudBackground.FILL);
            ox += TextModules.HPAD * e;
            oy += TextModules.TEXT_DY * e;
        }
        if (BedwarsQolFont.minecraft()) {
            // the font lands on whole device pixels; put the divider rects on the same grid
            int sf = new ScaledResolution(mc).getScaleFactor();
            ox = Math.round(ox * sf) / (float) sf;
            oy = Math.round(oy * sf) / (float) sf;
        }
        for (TextModules.Placed p : TextModules.layout(segs, HUD_ADVANCE)) {
            if (p.rect != null) {
                GuiRender.rect(ox + p.shadow[0] * e, oy + p.shadow[1] * e, ox + p.shadow[2] * e, oy + p.shadow[3] * e,
                        TextModules.DIVIDER_SHADOW);
                GuiRender.rect(ox + p.rect[0] * e, oy + p.rect[1] * e, ox + p.rect[2] * e, oy + p.rect[3] * e,
                        TextModules.DIVIDER);
            } else {
                int color = p.seg.role == TextModules.Role.UNIT ? accent
                        : p.seg.role == TextModules.Role.BRACKET ? TextModules.BRACKET : TextModules.NUMBER;
                drawShadowed(p.seg.text, ox + p.x * e, oy, e, color);
            }
        }
        resetGlState();
    }

    /** HUD text with its shadow in either font (Minecraft's own shadow, or the modern font's). */
    private static void drawShadowed(String text, float x, float y, float e, int color) {
        BedwarsQolFont.drawHud(text, x, y, e, color, true, BedwarsQolFont.Weight.BOLD);
    }

    // ----- Keystrokes (WASD, mouse buttons, spacebar) -----

    private static HudBox keystrokesBox(Minecraft mc, ClientSettings cfg, boolean example, boolean all) {
        if (!all && !cfg.keystrokesEnabled) return null;
        if (!all && cfg.keystrokesInGameOnly && !bedwarsActive(example)) return null;
        Size size = keystrokesSize(cfg);
        ScaledResolution r = new ScaledResolution(mc);
        float[] at = place(mc, cfg, KEYSTROKES_HUD, cfg.keystrokesHudX, cfg.keystrokesHudY, cfg.keystrokesHudAnchor, size.width, size.height, CONTENT_MARGIN, r);
        return new HudBox(KEYSTROKES_HUD, at[0], at[1], size.width, size.height, CONTENT_MARGIN);
    }

    private static Size keystrokesSize(ClientSettings cfg) {
        float e = ts(cfg.keystrokesHudScale);
        return new Size(KeystrokesLayout.WIDTH * e, KeystrokesLayout.HEIGHT * e);
    }

    /**
     * Lunar's caps, in cap order: the fill per {@link KeyCapStyle}, then the label (or the space bar's line). Laid out
     * at the labels' own scale {@code e}, so they always fit their caps; labels shadowed only on bare, unpressed caps.
     */
    private static void drawKeystrokesHud(Minecraft mc, ClientSettings cfg, boolean example) {
        HudBox box = keystrokesBox(mc, cfg, example, false);
        if (box == null) return;
        boolean[] held = keyStates(mc, example);
        long now = Minecraft.getSystemTime();
        boolean background = HudModules.background(cfg, KEYSTROKES_HUD);
        int accent = GuiTheme.fromToken(cfg.guiAccent).base();
        float e = ts(cfg.keystrokesHudScale);
        float ox = box.x, oy = box.y, trail = 0f;
        if (BedwarsQolFont.minecraft()) {
            // the font lands on whole device pixels; put the caps on the same grid
            int sf = new ScaledResolution(mc).getScaleFactor();
            ox = Math.round(ox * sf) / (float) sf;
            oy = Math.round(oy * sf) / (float) sf;
            trail = 1f; // Minecraft's font ends every string on an empty column
        }
        for (int i = 0; i < KeystrokesLayout.CAPS.length; i++) {
            // The editor preview shows a still frame and must not disturb the live fades.
            float level = example || editing ? (held[i] ? 1f : 0f) : KS_FADES[i].level(held[i], now);
            float[] r = KeystrokesLayout.CAPS[i];
            int fill = KeyCapStyle.fill(level, background, accent);
            if ((fill >>> 24) != 0) {
                GuiRender.rect(ox + r[0] * e, oy + r[1] * e, ox + (r[0] + r[2]) * e, oy + (r[1] + r[3]) * e, fill);
            }
            int text = KeyCapStyle.text(level, accent);
            boolean shadow = KeyCapStyle.shadow(level, background);
            String label = KeystrokesLayout.LABELS[i];
            if (label.isEmpty()) {
                float[] l = KeystrokesLayout.SPACE_LINE;
                if (shadow) {
                    GuiRender.rect(ox + (l[0] + 1) * e, oy + (l[1] + 1) * e, ox + (l[0] + l[2] + 1) * e,
                            oy + (l[1] + l[3] + 1) * e, KeystrokesLayout.SPACE_LINE_SHADOW);
                }
                GuiRender.rect(ox + l[0] * e, oy + l[1] * e, ox + (l[0] + l[2]) * e, oy + (l[1] + l[3]) * e, text);
            } else {
                float lx = ox + KeystrokesLayout.labelX(r, fontWidth(label), trail) * e;
                float ly = oy + KeystrokesLayout.labelY(r) * e;
                if (shadow) BedwarsQolFont.drawHud(label, lx, ly, e, text, true, BedwarsQolFont.Weight.BOLD);
                else BedwarsQolFont.drawHudFlat(label, lx, ly, e, text);
            }
        }
        resetGlState();
    }

    /**
     * Held state in cap order (W, A, S, D, Space, LMB, RMB). Movement follows the player's binds; the
     * mouse row is the physical buttons its labels name, and only counts in game (no screen open).
     * In Edit HUD a screen leaves the bindings unpressed, so the movement caps read their keys' keyboard
     * state, and the mouse caps stay unlit: the mouse is the editor's pointer (D12). Gameplay input stays off.
     */
    private static boolean[] keyStates(Minecraft mc, boolean example) {
        if (example || mc.gameSettings == null) return new boolean[]{true, false, false, false, false, false, false};
        if (editing) {
            GameSettings g = mc.gameSettings;
            return new boolean[]{GameSettings.isKeyDown(g.keyBindForward), GameSettings.isKeyDown(g.keyBindLeft),
                    GameSettings.isKeyDown(g.keyBindBack), GameSettings.isKeyDown(g.keyBindRight),
                    GameSettings.isKeyDown(g.keyBindJump), false, false};
        }
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
        final int durationTicks;
        final boolean max;
        /** The name with its level, e.g. "Speed II", and the time left, e.g. "1:24". */
        final String label;
        final String time;

        PotionEntry(Potion potion, int amplifier, int durationTicks, boolean max) {
            this.potion = potion;
            this.durationTicks = durationTicks;
            this.max = max;
            this.label = PotionRows.label(I18n.format(potion.getName()), amplifier);
            this.time = PotionRows.time(durationTicks, max);
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
