package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.SessionStatsWatch;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.gui.render.GuiBlur;
import com.bedwarsqol.gui.render.GuiRender;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.gui.render.Theme;
import com.bedwarsqol.stats.BedwarsMode;
import com.bedwarsqol.stats.BedwarsModeDetector;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.EligibilitySnapshot;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.StatsCache;
import com.bedwarsqol.stats.SeraphTag;
import com.bedwarsqol.stats.UrchinTag;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Custom-rendered settings screen — floating in the screen's top-right corner over a blurred, dimmed
 * world, with a single user-selectable accent ({@link GuiTheme}). A header cluster (version + a tab bar
 * whose active tab is an accent-filled pill + search field + Edit HUD action) floats along the top, and
 * the active section's modules render as a single right-aligned column of cards below it. Module cards
 * use the accent border by default and carry a top-right {@code +}/{@code -} expander for
 * their sub-settings; the Settings section renders as always-open grouped cards. Built on {@link GuiRender} + the Inter font ({@link BedwarsQolFont}).
 * Everything is sized as a clamped fraction of the screen and laid out in a fixed guiScale-3 virtual
 * space so density is identical at every host GUI Scale. The open keybind lives in Minecraft's native
 * Controls menu; ESC closes (and saves). The accent is GUI-only and never bleeds into the HUD.
 */
public class SettingsGui extends GuiScreen {

    // control kinds
    private static final int
            K_INVENTORY = 5, K_GENTIMERS = 6, K_STATS = 7, K_RANK = 9,
            K_NAMETAG = 10, K_TAB = 11, K_HANDPOS = 13, K_HANDSCALE = 14,
            K_HANDX = 17, K_HANDY = 18, K_HANDZ = 19;
    private static final int K_HUDSIZE = 20, K_DISPLAY = 21, K_GUISIZE = 22;
    private static final int K_SWEATREPORT = 28;
    private static final int K_TAB_HEADERFOOTER = 44;
    private static final int K_CHATHOVER = 53;
    // Per-module "Background" sub-toggles (draw a panel behind the HUD element).
    private static final int K_INVENTORY_BG = 63, K_GENTIMERS_BG = 64;
    // HUD text font: modern (Inter) vs vanilla Minecraft.
    private static final int K_HUDFONT = 66;
    // Party Join Alert: red "Party Joined" when a premade team queues a 2s/3s/4s game.
    private static final int K_PARTYJOIN = 68;
    // Nick Utils module (master toggle) + its sub-settings.
    private static final int K_NICKUTILS = 73;
    private static final int K_NICK_NOTIFY = 74;
    private static final int K_AUTO_DENICK = 75;
    private static final int K_CHATSTATS = 76;
    private static final int K_SCOREBOARD_SIZE = 46, K_STYLEDTAB_SIZE = 47;
    private static final int K_SUPPRESSESC = 48;
    // "In Game Only" sub-toggles: render the HUD only during an active BedWars game.
    private static final int K_INVENTORY_INGAME = 33;
    // GUI accent picker (a dropdown/stepper), plus the two Settings container GROUP cards (Appearance /
    // HUD). Container kinds are never toggled.
    private static final int K_ACCENT = 90;
    private static final int K_GRP_APPEARANCE = 91, K_GRP_HUD = 92;
    // Session Stats HUD (Lunar-style game + session tally) and its Reset action row.
    private static final int K_SESSION = 120, K_SESSION_INGAME = 121, K_SESSION_RESET = 123;
    // Height Limit HUD (Lunar-style map name / build limit / blocks left).
    private static final int K_HEIGHT = 124, K_HEIGHT_INGAME = 125;
    // Chat module: Lunar keeps only the two inc pieces (Lunar Client ships the generic chat QOL
    // natively); kind numbers match the Forge tree's Chat section.
    private static final int K_NOTIFY_INC = 101, K_INC_KEY = 103;
    // Urchin Tags module (master toggle) + its sub-settings (kind numbers shared with the Forge tree).
    private static final int K_URCHIN = 104, K_URCHIN_BADGE_TAB = 105, K_URCHIN_CHAT_ALERT = 106,
            K_URCHIN_SOUND = 107, K_URCHIN_BADGE_NAMETAG = 108;
    // Seraph Tags module (master toggle) + its sub-settings (kind numbers shared with the Forge tree).
    private static final int K_SERAPH = 110, K_SERAPH_BADGE_TAB = 111, K_SERAPH_CHAT_ALERT = 112,
            K_SERAPH_SOUND = 113, K_SERAPH_BADGE_NAMETAG = 114;
    // Pregame-queue chat alerts (kind numbers shared with the Forge tree).
    private static final int K_QUEUE_TAG_ALERT = 115, K_QUEUE_NICK_ALERT = 116;

    private static final String[] GUI_SIZES = {"Small", "Medium", "Large"};
    private static final String[] TEXT_SIZES = {"Small", "Medium", "Large"};
    private static final String[] DISPLAY_MODES = {"Text", "Image"};
    private static final String[] FONT_MODES = {"Modern", "Minecraft"};
    private static final String[] SIZES = {"Small", "Medium", "Large"};
    // Accent picker options + the stable persistence tokens each maps to (see GuiTheme.Accent).
    private static final String[] ACCENT_LABELS = {"Orange", "Red", "Blue", "Green"};
    private static final String[] ACCENT_TOKENS = {"orange", "red", "blue", "green"};

    private static final BedwarsQolFont.Weight MED = BedwarsQolFont.Weight.MEDIUM;

    // Right-side global action pinned into the top tab bar.
    private static final String EDIT_HUD_LABEL = "Edit HUD";

    // A GROUP header is a non-toggle container; its rows are always visible (no expander). Everything
    // else is a normal control.
    /** ACTION: a label with a right-aligned button; clicking runs a one-shot action, no stored value. */
    private enum RowType { TOGGLE, STEPPER, SLIDER, GROUP, ACTION }

    private static final class RowDef {
        final RowType type;
        final String label;
        final String desc;     // module-card description (null for children / group headers)
        final int kind;
        final String[] options;
        final float min, max;  // SLIDER range
        final boolean child;   // indented sub-setting
        final int parentKind;  // master toggle / group this belongs to (0 = top-level)

        RowDef(RowType type, String label, int kind, String[] options) {
            this(type, label, null, kind, options, 0, 0f, 0f);
        }

        RowDef(RowType type, String label, int kind, String[] options, int parentKind) {
            this(type, label, null, kind, options, parentKind, 0f, 0f);
        }

        RowDef(RowType type, String label, int kind, float min, float max, int parentKind) {
            this(type, label, null, kind, null, parentKind, min, max);
        }

        /** Module card: title + description, top-level toggle. */
        RowDef(RowType type, String label, String desc, int kind) {
            this(type, label, desc, kind, null, 0, 0f, 0f);
        }

        private RowDef(RowType type, String label, String desc, int kind, String[] options, int parentKind, float min, float max) {
            this.type = type;
            this.label = label;
            this.desc = desc;
            this.kind = kind;
            this.options = options;
            this.parentKind = parentKind;
            this.child = parentKind != 0;
            this.min = min;
            this.max = max;
        }
    }

    private static final class Section {
        final String tab;
        final boolean grouped;  // Settings: non-toggle GROUP cards (not searchable modules)
        final RowDef[] rows;

        Section(String tab, RowDef... rows) {
            this(tab, false, rows);
        }

        Section(String tab, boolean grouped, RowDef... rows) {
            this.tab = tab;
            this.grouped = grouped;
            this.rows = rows;
        }
    }

    private static final Section[] SECTIONS = {
            new Section("HUD",
                    new RowDef(RowType.TOGGLE, "Inventory", "Stored items at a glance", K_INVENTORY),
                    new RowDef(RowType.TOGGLE, "In Game Only", K_INVENTORY_INGAME, null, K_INVENTORY),
                    new RowDef(RowType.TOGGLE, "Background", K_INVENTORY_BG, null, K_INVENTORY),
                    new RowDef(RowType.TOGGLE, "Gen Timers", "Diamond and emerald timers", K_GENTIMERS),
                    new RowDef(RowType.TOGGLE, "Background", K_GENTIMERS_BG, null, K_GENTIMERS),
                    new RowDef(RowType.TOGGLE, "Session Stats", "Game and session kills, finals, beds, W/L", K_SESSION),
                    new RowDef(RowType.TOGGLE, "In Game Only", K_SESSION_INGAME, null, K_SESSION),
                    new RowDef(RowType.ACTION, "Reset Session", K_SESSION_RESET, null, K_SESSION),
                    new RowDef(RowType.TOGGLE, "Height Limit", "Map name, build limit and blocks left", K_HEIGHT),
                    new RowDef(RowType.TOGGLE, "In Game Only", K_HEIGHT_INGAME, null, K_HEIGHT)),
            new Section("Combat",
                    new RowDef(RowType.TOGGLE, "Hand Position", "Move and resize held item", K_HANDPOS),
                    new RowDef(RowType.SLIDER, "X", K_HANDX, -1.0f, 1.0f, K_HANDPOS),
                    new RowDef(RowType.SLIDER, "Y", K_HANDY, -1.0f, 1.0f, K_HANDPOS),
                    new RowDef(RowType.SLIDER, "Z", K_HANDZ, -1.0f, 1.0f, K_HANDPOS),
                    new RowDef(RowType.SLIDER, "Scale", K_HANDSCALE, 0.5f, 2.0f, K_HANDPOS),
                    new RowDef(RowType.TOGGLE, "Disable Esc Menu", "Stop Esc pausing the game", K_SUPPRESSESC)),
            new Section("Visuals",
                    new RowDef(RowType.TOGGLE, "Hide Tab Header/Footer", "Hide tab header and footer", K_TAB_HEADERFOOTER)),
            new Section("Chat",
                    new RowDef(RowType.TOGGLE, "Inc Alert", "Sound when a teammate says inc", K_NOTIFY_INC),
                    new RowDef(RowType.TOGGLE, "Send INC Keybind", "Key sends /pc INC (bind in Controls)", K_INC_KEY)),
            new Section("Hypixel",
                    new RowDef(RowType.TOGGLE, "Hypixel Stats", "BedWars stats on screen", K_STATS),
                    new RowDef(RowType.TOGGLE, "Show Nametag", K_NAMETAG, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Show Tab", K_TAB, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Chat Hover", K_CHATHOVER, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Chat Stats", K_CHATSTATS, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Show Rank", K_RANK, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Party Report", K_SWEATREPORT, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Party Join Alert", "Red 'Party Joined' when a party queues", K_PARTYJOIN),
                    new RowDef(RowType.TOGGLE, "Nick Utils", "Detect and denick nicked players", K_NICKUTILS),
                    new RowDef(RowType.TOGGLE, "Nick Notify", K_NICK_NOTIFY, null, K_NICKUTILS),
                    new RowDef(RowType.TOGGLE, "Auto Denick", K_AUTO_DENICK, null, K_NICKUTILS),
                    new RowDef(RowType.TOGGLE, "Urchin Tags", "Community-reported blacklist tags from urchin.ws", K_URCHIN),
                    new RowDef(RowType.TOGGLE, "Tab Badge", K_URCHIN_BADGE_TAB, null, K_URCHIN),
                    new RowDef(RowType.TOGGLE, "Chat Alert", K_URCHIN_CHAT_ALERT, null, K_URCHIN),
                    new RowDef(RowType.TOGGLE, "Alert Sound", K_URCHIN_SOUND, null, K_URCHIN),
                    new RowDef(RowType.TOGGLE, "Nametag Badge", K_URCHIN_BADGE_NAMETAG, null, K_URCHIN),
                    new RowDef(RowType.TOGGLE, "Seraph Tags", "Community blacklist/safelist from api.seraph.si", K_SERAPH),
                    new RowDef(RowType.TOGGLE, "Tab Badge", K_SERAPH_BADGE_TAB, null, K_SERAPH),
                    new RowDef(RowType.TOGGLE, "Chat Alert", K_SERAPH_CHAT_ALERT, null, K_SERAPH),
                    new RowDef(RowType.TOGGLE, "Alert Sound", K_SERAPH_SOUND, null, K_SERAPH),
                    new RowDef(RowType.TOGGLE, "Nametag Badge", K_SERAPH_BADGE_NAMETAG, null, K_SERAPH),
                    new RowDef(RowType.TOGGLE, "Queue Tag Alert", "Queue only: tags for players who type", K_QUEUE_TAG_ALERT),
                    new RowDef(RowType.TOGGLE, "Queue Nick Alert", "Queue only: nicks for players who type", K_QUEUE_NICK_ALERT)),
            // Settings: two always-open container GROUP cards (Appearance / HUD) stacked in the column.
            // Each stepper is a child of its group card; the Accent picker is a normal STEPPER (GuiTheme
            // resolves live).
            new Section("Settings", true,
                    new RowDef(RowType.GROUP, "Appearance", K_GRP_APPEARANCE, (String[]) null),
                    new RowDef(RowType.STEPPER, "Accent", K_ACCENT, ACCENT_LABELS, K_GRP_APPEARANCE),
                    new RowDef(RowType.STEPPER, "GUI Size", K_GUISIZE, GUI_SIZES, K_GRP_APPEARANCE),
                    new RowDef(RowType.GROUP, "HUD", K_GRP_HUD, (String[]) null),
                    new RowDef(RowType.STEPPER, "HUD Size", K_HUDSIZE, TEXT_SIZES, K_GRP_HUD),
                    new RowDef(RowType.STEPPER, "Scoreboard Size", K_SCOREBOARD_SIZE, SIZES, K_GRP_HUD),
                    new RowDef(RowType.STEPPER, "Tab List Size", K_STYLEDTAB_SIZE, SIZES, K_GRP_HUD),
                    new RowDef(RowType.STEPPER, "Display", K_DISPLAY, DISPLAY_MODES, K_GRP_HUD),
                    new RowDef(RowType.STEPPER, "Font", K_HUDFONT, FONT_MODES, K_GRP_HUD)),
            // Players: placeholder tab, no modules yet (renders a faint "Coming soon").
            new Section("Players"),
    };

    private static final int MAX_ROWS = 6;

    // ---- GUI-only helper tones (warm; the palette proper lives in GuiTheme, the accent in accent) ----
    // Full-screen scrim over the blurred world: a lighter dim so the blur reads clearly.
    private static final int SCRIM = 0x73000000;
    // Warm keyline for the dropdown popup / focused search field / capturing chip.
    private static final int PANEL_BORDER = 0xCCFFF2E4;
    // Faint warm hover wash (rows + inactive tabs).
    private static final int ROW_HOVER = 0x14FFF2E4;
    // Neutral warm buttons (Edit HUD, chips, dropdown triggers).
    private static final int BTN_BG = 0xFF2A241C;
    private static final int BTN_HOVER = 0xFF362F24;
    private static final int BTN_BORDER = 0x24FFF2E4;
    private static final int SEARCH_BAR_BG = 0xD02A241C;
    private static final int KNOB_RING = 0x4D000000; // soft shadow ring under a switch/slider knob
    // Text on the active (accent-filled) tab pill — a bright neutral that reads on any accent.
    private static final int TAB_ACTIVE_TEXT = 0xFFFFFFFF;
    // ---- dropdown (<select>) menu surface ----
    private static final int DD_BG = 0xFF1E1A14;            // menu surface (fully opaque, slightly elevated)
    private static final int DD_ITEM_SELECTED = 0xFF3A342A; // subtle selection
    private static final int DD_ITEM_HOVER = 0xFF4A4238;    // brighter hovered row
    // Pad above the first option row and below the last. Shared by the list layout, the hover test and
    // the click, so the row the cursor highlights is always the row a click applies (F10).
    private static final float DD_PAD_Y = 2f;
    // Always-reserved gutter on the right of the content column so card width is identical whether or
    // not the scrollbar is showing (the thin scrollbar lives inside this band).
    private static final int SCROLL_GUTTER = 7;
    private static final int CARD_R = Theme.CARD_R;

    // =============================================================================================
    // LAYOUT TUNING — the localized proportion knobs an in-game pass adjusts to match the reference
    // screenshots (colours live in GuiTheme). Panel footprint, then the card-grid rhythm: gaps,
    // internal padding, header roominess, the title/description scale ramp, and the switch pill aspect.
    // Each is a fraction of a responsive base (virtual viewport, panel width, or row height); the
    // per-use clamps keep it sane at every mod GUI size and host Minecraft GUI scale.
    // =============================================================================================
    // The module column's width, and the nominal height that drives rowH/typography (NOT the column's
    // actual on-screen height), as fractions of the virtual (guiScale-3) viewport, before the
    // Small/Medium/Large factor and the on-screen clamps.
    private static final float PANEL_W_FRAC = 0.40f, PANEL_H_FRAC = 0.58f;
    // Card typography as a multiple of the base label scale (title prominent, description subordinate).
    private static final float CARD_TITLE_SCALE_F = 0.78f, CARD_DESC_SCALE_F = 0.55f;
    // Card spacing as fractions of rowH: vertical gap between stacked cards, top/bottom inset around the
    // child block, and the card's left/right internal padding.
    private static final float CARD_GAP_F = 0.20f,
            CARD_INNER_PAD_F = 0.22f, CARD_PAD_X_F = 0.34f;
    // Extra vertical breathing added to a card header beyond its text block (fraction of rowH), for
    // module cards and group cards respectively, plus the px gap between a card title and its description.
    private static final float CARD_HEADER_PAD_F = 0.55f, GROUP_HEADER_PAD_F = 0.62f;
    private static final float CARD_TITLE_DESC_GAP = 2f;

    private static final class Row {
        final RowDef def;
        boolean group;   // owning card is a GROUP container -> this child is never gated by a master toggle
        int x, y, w, h;

        Row(RowDef def) {
            this.def = def;
        }

        boolean hit(int mx, int my) {
            return GuiRender.inside(mx, my, x, y, x + w, y + h);
        }
    }

    /** A card in the masonry grid. A MODULE card uses the accent border and carries an
     *  optional top-right {@code +}/{@code -} expander for its sub-option rows; a GROUP card ({@link #group})
     *  is a non-toggle container whose rows are always visible. The expander hit rectangle is cached here at
     *  layout time. */
    private static final class Card {
        final RowDef module;
        final boolean hasSub;      // MODULE with expandable children -> shows a +/- expander
        boolean group;             // GROUP container (no toggle, no expander, always open)
        int x, y, w, headerH, h;
        // Cached +/- expander rectangle (MODULE + hasSub only; top-right of the header).
        float exX1, exY1, exX2, exY2;
        final List<Row> children = new ArrayList<Row>();

        Card(RowDef module, boolean hasSub) {
            this.module = module;
            this.hasSub = hasSub;
        }
    }

    /** A module shown on the Search page: a top-level toggle RowDef plus the section it belongs to. */
    private static final class SearchModule {
        final RowDef def;     // def.label / def.desc / def.kind
        final String section; // owning section tab (HUD / Combat / Visuals / Hypixel)

        SearchModule(RowDef def, String section) {
            this.def = def;
            this.section = section;
        }
    }

    private int selectedSection = 0; // open to HUD by default; a non-empty search overlays any section
    private final List<Card> cards = new ArrayList<Card>();
    // Which module cards are expanded (by module kind). Empty = all collapsed (the default each open).
    private final java.util.Set<Integer> expandedModules = new java.util.HashSet<Integer>();

    /** The accent resolved from {@code settings().guiAccent} once per frame. Every accent-sensitive
     *  element (active tab pill, toggle-on switch, card border, open-dropdown outline) reads it,
     *  so a picker change recolours live with no {@code initGui}. */
    private GuiTheme.Accent accent = GuiTheme.Accent.ORANGE;

    /** GL multiplier that locks the panel to the "Large" (guiScale 3) density on any host GUI Scale, so the
     *  settings GUI renders at one fixed physical size regardless of the client's Minecraft GUI Scale. 1.0
     *  when the host is already at Large. Set in {@link #initGui()}, applied in {@link #drawScreen}, and the
     *  cursor is divided by it in every mouse handler so hit-testing stays aligned with the scaled render. */
    private float uiScale = 1f;

    // geometry / rhythm (computed in initGui)
    private int panelX, panelY, panelW, panelH, pad;
    private int contentX, contentRight, contentTop, contentH, rowH;
    // Top tab bar band across the panel top (version + tabs left; search + Edit HUD right).
    private int headerY1, headerY2, headerH;
    private float labelScale, valueScale;
    // Module-card typography: a slightly smaller header and a noticeably smaller subtext than the base
    // label scale, so cards read compact/sleek and the (shortened) descriptions fit on a single line.
    private float cardTitleScale, cardDescScale;
    // Dropdown typography: a notch smaller than the value scale so triggers and option rows read compact.
    private float ddFontScale;
    // Widest stepper option label on the current page — shared so every stepper's arrows align.
    private float pageStepperTextW;
    // Compact sub-setting rows; total masonry block height for the scrollbar.
    private int childRowH, rowsBlockH;
    // Viewport the card grid is laid out into (top/bottom). Set in buildCards; read by drawCards/clicks.
    private int cardsViewTop, cardsViewBottom;

    // ---- top tab bar geometry (computed in initGui; draw + click share the cached rectangles) ----
    // One [x1,y1,x2,y2] hit rectangle per section, in SECTIONS order.
    private float[][] tabHits;
    private float tabScale;
    private int tabsY1, tabsY2;
    // Edit HUD button + version label (right cluster / far left), sized to their text.
    private int editHudX1, editHudX2, editHudY1, editHudY2;
    private float editHudScale;
    private float versionScale;
    private int versionX; // left x of the version label, placed to the left of the tab strip
    // Enlarged shared header text scale — tabs, search text and the Edit HUD label all render at this size
    // (see initGui). tabScale / editHudScale are kept equal to it; the version uses its own smaller
    // versionScale (the pre-enlarge value), so bumping this never changes the version text.
    private float headerScale;

    // Active slider drag (0 = none) and the cached track geometry/range for it.
    private int draggingSliderKind;
    private float dragTrackX1, dragTrackX2, dragMin, dragMax;
    // Inline numeric entry on a slider's value text: editingSliderKind == 0 means not editing.
    private int editingSliderKind;
    private final StringBuilder editSliderBuf = new StringBuilder();
    private float editSliderMin, editSliderMax;

    private int caretBlink;
    // The search field is focused only after a click inside it (caret shows); a click anywhere else blurs it.
    private boolean searchFocused;
    // Shared vertical scroll for whichever view is showing (a section grid or the search grid).
    // Reset to 0 on tab/query change; maxScroll == 0 means the view fits and never scrolls.
    private int scroll;
    private int maxScroll;
    // Smooth scroll: `scroll` is the instant target the wheel/thumb set; scrollRender eases toward it
    // each frame so motion is smooth. Layout/hit-testing stay on the integer target; only rendering uses
    // the eased value (via a GL translate).
    private float scrollRender;
    private float scrollAccum; // fractional wheel accumulator; whole pixels are committed to `scroll`
    private long lastFrameNanos;
    private boolean draggingThumb;
    private float thumbGrabDy; // cursor's offset within the thumb when grabbed

    // ---- Dropdown (<select>) popup state ----
    // A stepper's options are chosen from a click-to-open dropdown. Only one is open at a time, keyed by
    // stepper kind (0 = none). The trigger's screen rect is captured at open time so the popup stays
    // anchored even though rows live inside a scrolled/translated matrix; the popup is drawn last (on top
    // of everything) and is modal — it absorbs the next click (pick or dismiss).
    private int openDropdownKind;
    private String[] ddOptions;
    private float ddY1, ddX2, ddY2; // anchor = the trigger button's screen rect (x1 unused: menu is right-aligned)
    private float ddScale;                // option text scale (matches the trigger)
    private float ddPad;                  // horizontal padding (matches the trigger)

    // ---- Search state ----
    private final StringBuilder searchQuery = new StringBuilder();
    private final List<SearchModule> allModules = new ArrayList<SearchModule>();    // collected once in initGui
    private final List<SearchModule> searchResults = new ArrayList<SearchModule>(); // current filtered + ranked view
    private int searchBarX1, searchBarX2, searchBarY1, searchBarY2;
    private int searchClearX1, searchClearX2; // clear-x hit zone
    private int searchListTop, searchListBottom;


    // ---- Players tab state (fetch-on-click master-detail; see PlayersViewState for session persistence) ----
    /** The Players section index (the trailing placeholder tab). */
    private static final int PLAYERS_INDEX = SECTIONS.length - 1;
    // Wide master-detail band under the header (list left, detail right). Computed in initGui.
    private int playersX1, playersX2, playersTop, playersBottom, playersListX2, playersDetailX1;
    private int playersRowH;
    // The list viewport's clip bounds (set each frame in drawPlayers; shared by click hit-testing so a
    // click can never select a row hidden by the scissor/padding).
    private int playersListTop, playersListBottom;
    // Dedicated player-search field pinned above the list (Players tab only). Computed in initGui.
    private int playerSearchX1, playerSearchX2, playerSearchY1, playerSearchY2;
    private int playerSearchClearX1, playerSearchClearX2;
    private boolean playerSearchFocused; // focus for the above-list player field (separate from the header)
    // Per-frame row hit rectangles + parallel uuids/names (rebuilt each draw from the tab list).
    private final List<float[]> playerRowHits = new ArrayList<float[]>();
    private final List<UUID> playerRowUuids = new ArrayList<UUID>();
    private final List<String> playerRowNames = new ArrayList<String>();
    private float[] lookupRowHit; // "Search Hypixel for X" action row rect, or null
    /** Stable-insertion-order row animation; fresh per GUI open (the real "page opened" boundary). */
    private final PlayersListAnim playersAnim = new PlayersListAnim();
    // Own scroll for the player list (kept separate from the shared card scroll).
    private float playersScrollRender;
    // Detail-panel vertical scroll (hero + per-mode cards + Urchin + Seraph can overflow the panel).
    private float detailScroll, detailScrollRender, detailScrollAccum;
    private int detailMaxScroll;
    private float playersScrollAccum;
    private int playersMaxScroll;
    private boolean draggingPlayersThumb;
    private float playersThumbGrabDy;

    private boolean playersTab() { return selectedSection == PLAYERS_INDEX; }

    /** Reopen on the section the user last viewed this session (falls back to the default HUD tab). */
    public SettingsGui() {
        if (PlayersViewState.selectedSection >= 0 && PlayersViewState.selectedSection < SECTIONS.length) {
            selectedSection = PlayersViewState.selectedSection;
        }
    }

    /** Open directly on a specific section (used by the Players keybind to jump straight to the tab). */
    public SettingsGui(int section) {
        if (section >= 0 && section < SECTIONS.length) selectedSection = section;
    }

    /** A settings screen opened straight to the Players tab (used by the Players keybind). */
    public static SettingsGui players() {
        return new SettingsGui(PLAYERS_INDEX);
    }

    @Override
    public void initGui() {
        buttonList.clear();
        closeDropdown(); // never carry an open popup across a resize / panel re-layout
        Keyboard.enableRepeatEvents(true);
        float gf = guiFactor(settings().guiSize);
        // Lock the panel to the "Large" (guiScale 3) physical size on ANY host GUI Scale (see uiScale).
        int actualSf = Math.max(1, new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor());
        uiScale = largeScaleFactor() / (float) actualSf;
        int vW = Math.round(width / uiScale);
        int vH = Math.round(height / uiScale);
        // Right-anchored layout: a single module column pinned to the screen's top-right corner, with the
        // header cluster (version + tabs + search + Edit HUD) floating above it. The column is one card wide
        // (scaled by the Small/Medium/Large factor); the header is wider and extends leftward from the same
        // right edge. The column runs the full height down to a bottom margin and scrolls on overflow.
        int edge = clamp(Math.round(vW * 0.02f), 8, 16);
        int colW = clamp(Math.round(vW * PANEL_W_FRAC), 230, 340);
        panelW = Math.round(colW * gf);
        panelX = vW - edge - panelW;
        panelY = edge;
        panelH = (vH - edge) - panelY;
        pad = clamp(Math.round(panelW * 0.055f), 8, 13);

        contentX = panelX + pad;
        contentRight = panelX + panelW - pad;
        int innerBottom = panelY + panelH - pad;
        // rowH governs typography + card rhythm. Derive it from a nominal (Small/Medium/Large-scaled) height
        // — NOT the full column height — so the GUI Size setting still scales text and card density even
        // though the column itself always extends to the screen bottom.
        int nominalH = Math.round(clamp(Math.round(vH * PANEL_H_FRAC), 180, 300) * gf);
        rowH = clamp((nominalH - 2 * pad) / MAX_ROWS, 16, 40);

        labelScale = clampf(rowH * 0.046f, 1.0f, 1.85f);
        valueScale = labelScale * 0.96f;
        ddFontScale = valueScale * 0.74f; // dropdowns smaller than other value text
        cardTitleScale = labelScale * CARD_TITLE_SCALE_F; // prominent module title
        cardDescScale = labelScale * CARD_DESC_SCALE_F;   // subordinate grey description
        childRowH = clamp(Math.round(rowH * 0.66f), 12, 26); // compact sub-setting rows

        // Header band floating above the column; its height hugs a comfortable pill + search field.
        headerH = Math.round(BedwarsQolFont.height(labelScale)) + 2 * clamp(Math.round(rowH * 0.22f), 4, 7);
        headerH = Math.round(headerH * 1.35f);
        headerY1 = panelY;
        headerY2 = headerY1 + headerH;

        // Shared vertical band for the tab pills, search field and Edit HUD button.
        int barPadY = clamp(Math.round(headerH * 0.16f), 2, 5);
        int barH = Math.round((headerH - 2 * barPadY) * 0.82f);
        int barY1 = headerY1 + (headerH - barH) / 2;
        int barY2 = barY1 + barH;
        tabsY1 = barY1; tabsY2 = barY2;
        // (Search + Edit HUD get a shorter band below, once the header text scale is finalized.)

        // The version keeps its own (smaller) scale; the tabs, search field and Edit HUD label share one
        // enlarged scale. When the right-anchored cluster is too wide for the screen, only that shared scale
        // shrinks (uniformly); the version is independent and never affected.
        versionScale = clampf(valueScale * 0.70f, 0.6f, 1.0f);
        headerScale = versionScale * 1.3f;

        int rightEdge = contentRight; // header right edge aligns with the card column's right edge
        int editPadX = clamp(Math.round(rowH * 0.55f), 12, 20);
        int searchGap = clamp(Math.round(pad * 0.9f), 6, 12);
        int vGap = clamp(Math.round(pad * 1.0f), 6, 14);
        int searchW = clamp(Math.round(vW * 0.14f * gf), 60, 150);
        float versionW = GuiRender.textWidth("v" + BedwarsQol.VERSION, versionScale, BedwarsQolFont.Weight.REGULAR);

        // Fit the shared header scale so the whole right-anchored cluster fits between the left screen margin
        // and the column's right edge. Reserve the Edit HUD width at the target (largest) scale so the tab-fit
        // span is a safe lower bound (a later shrink only frees room); the search + version widths are fixed
        // reservations. fitTabBar then shrinks scale/pad/gap until the tabs provably fit.
        float editHudResW = GuiRender.textWidth(EDIT_HUD_LABEL, headerScale, MED) + editPadX;
        int tabsRightReserved = Math.round(rightEdge - editHudResW - searchGap - searchW - searchGap);
        int tabsFloor = Math.round(edge + versionW + vGap);
        float[] tabW1 = new float[SECTIONS.length];
        for (int i = 0; i < SECTIONS.length; i++) tabW1[i] = GuiRender.textWidth(SECTIONS[i].tab, 1f, MED);
        float[] fit = fitTabBar(tabW1, tabsRightReserved - tabsFloor,
                headerScale, clampf(rowH * 0.30f, 6f, 14f),
                clamp(Math.round(rowH * 0.14f), 3, 9), 0.34f, 2f, 2f);
        headerScale = fit[0];
        tabScale = headerScale;
        // The search field + Edit HUD button hug their text so they read as clearly shorter than the tab
        // pills, while staying centered on the same row. Height = header text height + a small pad, so the
        // labels stay fully readable; the taller band is reserved for the tab pills only.
        int actionTextH = Math.round(BedwarsQolFont.height(headerScale));
        int actionH = Math.min(barH, actionTextH + 2 * clamp(Math.round(rowH * 0.10f), 2, 4));
        actionH = Math.max(actionTextH + 2, Math.round(actionH * 0.9f)); // 10% shorter again; floor keeps the label readable
        int actionY1 = barY1 + (barH - actionH) / 2;
        editHudY1 = actionY1; editHudY2 = actionY1 + actionH;
        searchBarY1 = actionY1; searchBarY2 = actionY1 + actionH;
        editHudScale = headerScale;
        float tabPadX = fit[1];
        int tabGap = Math.round(fit[2]);

        // Right cluster, laid out right-to-left from the column's right edge: Edit HUD, then the search field.
        editHudX2 = rightEdge;
        editHudX1 = Math.round(editHudX2 - (GuiRender.textWidth(EDIT_HUD_LABEL, editHudScale, MED) + editPadX));
        searchBarX2 = editHudX1 - searchGap;
        searchBarX1 = searchBarX2 - searchW;
        // Small clear-x tucked close to the field's right edge (no magnifier icon on the left).
        int searchClearW = clamp(Math.round((searchBarY2 - searchBarY1) * 0.5f), 7, 12);
        searchClearX2 = searchBarX2 - 4;
        searchClearX1 = searchClearX2 - searchClearW;

        // Tabs end just left of the search field, flowing left-to-right from tabsLeft; the version sits left
        // of the tabs. fitTabBar guarantees the strip clears tabsFloor, so the version never collides.
        float tabsWidth = 0f;
        for (int i = 0; i < SECTIONS.length; i++) tabsWidth += tabW1[i] * tabScale + 2 * tabPadX;
        tabsWidth += (SECTIONS.length - 1) * tabGap;
        float tabsRight = searchBarX1 - searchGap;
        float tabsLeft = tabsRight - tabsWidth;
        tabHits = new float[SECTIONS.length][4];
        float cursor = tabsLeft;
        for (int i = 0; i < SECTIONS.length; i++) {
            float w = tabW1[i] * tabScale + 2 * tabPadX;
            tabHits[i] = new float[]{cursor, tabsY1, cursor + w, tabsY2};
            cursor += w + tabGap;
        }
        versionX = Math.round(tabsLeft - vGap - versionW);

        // Content column: below the header, full remaining height (scrolls on overflow).
        contentTop = headerY2 + pad;
        contentH = innerBottom - contentTop;

        // Players tab: a wide master-detail band under the header. The settings column is narrow and
        // right-anchored; the Players view instead spans from the left screen margin to the column's right
        // edge (list on the left ~38%, detail on the right), floating on the same blurred world.
        // Align the right card with the module cards' right edge (they stop a scroll-gutter short of
        // contentRight), then mirror that same inset on the left so the band's left/right screen gaps match.
        playersX2 = contentRight - SCROLL_GUTTER;
        playersX1 = vW - playersX2;
        playersTop = contentTop;
        playersBottom = innerBottom;
        int pGap = clamp(Math.round(pad * 1.2f), 8, 18);
        playersListX2 = playersX1 + Math.round((playersX2 - playersX1) * 0.38f);
        playersDetailX1 = playersListX2 + pGap;
        playersRowH = clamp(Math.round(rowH * 0.92f), 16, 34);

        // Dedicated player-search field pinned to the top of the list panel (Players tab only; the header
        // search field is hidden there). Filters the tab list live and offers a Hypixel lookup for any name
        // not in the list (the action row in drawPlayers).
        int psPad = clamp(Math.round(pad * 0.6f), 5, 10);
        playerSearchX1 = playersX1 + psPad;
        playerSearchX2 = playersListX2 - psPad;
        playerSearchY1 = playersTop + psPad;
        float psScale = clampf(playersRowH * 0.04f, 0.95f, 1.4f);
        playerSearchY2 = playerSearchY1 + Math.round(BedwarsQolFont.height(psScale))
                + 2 * clamp(Math.round(playersRowH * 0.16f), 3, 5);
        int psClearW = clamp(Math.round((playerSearchY2 - playerSearchY1) * 0.5f), 7, 12);
        playerSearchClearX2 = playerSearchX2 - 4;
        playerSearchClearX1 = playerSearchClearX2 - psClearW;

        if (allModules.isEmpty()) collectModules();
        layoutContent();
        GuiBlur.begin(); // start the world-blur fade-in behind the panel
    }

    private static float guiFactor(int guiSize) {
        switch (guiSize) {
            case 0: return 0.70f;  // Small
            case 1: return 0.85f;  // Medium
            default: return 1.0f;  // Large
        }
    }

    /** The scaleFactor vanilla Minecraft would pick at GUI Scale = Large (guiScale 3) on this display. The
     *  panel locks to this density (see {@link #uiScale}) so it renders at the same physical size at any host
     *  GUI Scale. Mirrors 1.8.9 ScaledResolution's loop, capped at 3. */
    private int largeScaleFactor() {
        int gw = mc.displayWidth, gh = mc.displayHeight;
        int sf = 1;
        while (sf < 3 && gw / (sf + 1) >= 320 && gh / (sf + 1) >= 240) sf++;
        return sf;
    }

    private void layoutContent() {
        cards.clear();
        if (searchActive()) {
            layoutSearchResults();
            return;
        }
        layoutCards();
    }

    /** Builds the cards for the current section (module cards, or GROUP cards for Settings/Debug). */
    private void layoutCards() {
        Section section = SECTIONS[selectedSection];
        // Group rows into headers (top-level, i.e. non-child) + their child defs (a child follows its
        // parent). This partitions both module sections (toggle header + sub-options) and grouped
        // sections (GROUP header + its always-visible rows) identically.
        List<RowDef> moduleDefs = new ArrayList<RowDef>();
        List<List<RowDef>> childDefs = new ArrayList<List<RowDef>>();
        for (RowDef rd : section.rows) {
            if (!rd.child) {
                moduleDefs.add(rd);
                childDefs.add(new ArrayList<RowDef>());
            } else {
                childDefs.get(childDefs.size() - 1).add(rd);
            }
        }
        buildCards(moduleDefs, childDefs, contentTop, contentH);
    }

    /** Re-runs whichever layout owns the current {@link #cards} (the search view or a section view). */
    private void relayoutCards() {
        if (searchActive()) layoutSearchResults();
        else layoutCards();
    }

    /** All sub-option defs for a module, found by {@code parentKind} (kinds are globally unique). */
    private List<RowDef> childrenOf(RowDef module) {
        List<RowDef> kids = new ArrayList<RowDef>();
        for (Section s : SECTIONS) {
            for (RowDef rd : s.rows) {
                if (rd.child && rd.parentKind == module.kind) kids.add(rd);
            }
        }
        return kids;
    }

    /** Lays {@link #cards} out as a single right-aligned column inside a viewport at {@code top} of height
     *  {@code viewH}. Each card's full height (header, plus children when expanded/grouped) is measured
     *  first; cards then stack vertically at the full column width, every placement offset by {@code contentX}
     *  and {@code (top - scroll)}. Shared by section + search views. */
    private void buildCards(List<RowDef> moduleDefs, List<List<RowDef>> childDefs, int top, int viewH) {
        cards.clear();
        cardsViewTop = top;
        cardsViewBottom = top + viewH;
        // Widest child stepper option, so their dropdown triggers align (measured at the child scale).
        pageStepperTextW = 0f;
        for (List<RowDef> kids : childDefs) {
            for (RowDef rd : kids) {
                if (rd.type == RowType.STEPPER && rd.options != null) {
                    for (String opt : rd.options) {
                        pageStepperTextW = Math.max(pageStepperTextW, GuiRender.textWidth(opt, ddFontScale));
                    }
                }
            }
        }
        int cardGap = clamp(Math.round(rowH * CARD_GAP_F), 3, 8);
        int innerPad = clamp(Math.round(rowH * CARD_INNER_PAD_F), 3, 9);
        int cIndent = clamp(Math.round(rowH * 0.5f), 10, 22);
        int padX = cardPadX();
        float titleH = BedwarsQolFont.height(cardTitleScale);
        float descH = BedwarsQolFont.height(cardDescScale);
        int moduleHeaderH = Math.round(titleH + CARD_TITLE_DESC_GAP + descH) + clamp(Math.round(rowH * CARD_HEADER_PAD_F), 9, 18);
        int groupHeaderH = Math.round(titleH) + clamp(Math.round(rowH * GROUP_HEADER_PAD_F), 9, 18);

        int n = moduleDefs.size();
        int[] heights = new int[n];
        boolean[] grp = new boolean[n];
        boolean[] show = new boolean[n];   // children currently visible for this card
        int[] headerHs = new int[n];
        for (int m = 0; m < n; m++) {
            RowDef mod = moduleDefs.get(m);
            List<RowDef> defs = childDefs.get(m);
            boolean g = mod.type == RowType.GROUP;
            grp[m] = g;
            boolean showChildren = g ? !defs.isEmpty() : (!defs.isEmpty() && expandedModules.contains(mod.kind));
            show[m] = showChildren;
            // A titled GROUP (the Appearance / HUD Settings cards) gets a group header band; a titleless one
            // would need only a small top pad so its first row sits near the card top.
            int hh = g ? (mod.label.isEmpty() ? innerPad : groupHeaderH) : moduleHeaderH;
            headerHs[m] = hh;
            int childrenH = showChildren ? defs.size() * childRowH + 2 * innerPad : 0;
            heights[m] = hh + childrenH;
        }

        int cw = contentRight - contentX - SCROLL_GUTTER;
        // Single right-aligned column: every card spans the full column width and stacks vertically.
        int[] plX = new int[n], plY = new int[n], plW = new int[n];
        int yy = 0;
        for (int m = 0; m < n; m++) { plX[m] = 0; plW[m] = cw; plY[m] = yy; yy += heights[m] + cardGap; }
        rowsBlockH = n > 0 ? yy - cardGap : 0;
        maxScroll = Math.max(0, rowsBlockH - viewH);
        scroll = clamp(scroll, 0, maxScroll);
        // A collapse-at-bottom shrinks maxScroll; clamp the eased render offset too, or drawCards would
        // translate cards away from their freshly baked hit rects (and show an already-bottomed scrollbar).
        scrollRender = clampf(scrollRender, 0, maxScroll);
        int baseY = top - scroll; // placement y is content-local; offset by the (integer) scroll target

        for (int m = 0; m < n; m++) {
            RowDef mod = moduleDefs.get(m);
            List<RowDef> defs = childDefs.get(m);
            Card card = new Card(mod, !grp[m] && !defs.isEmpty());
            card.group = grp[m];
            card.x = contentX + plX[m];
            card.w = plW[m];
            card.y = baseY + plY[m];
            card.headerH = headerHs[m];

            int childrenH = 0;
            if (show[m]) {
                int cy = card.y + card.headerH + innerPad;
                for (RowDef cd : defs) {
                    Row r = new Row(cd);
                    r.group = card.group;
                    r.x = card.x + cIndent;
                    r.y = cy;
                    r.w = card.w - cIndent - innerPad;
                    r.h = childRowH;
                    card.children.add(r);
                    cy += childRowH;
                }
                childrenH = defs.size() * childRowH + 2 * innerPad;
            }
            card.h = card.headerH + childrenH;

            // Cache the +/- expander rectangle at the header's top-right (where the master toggle used to
            // sit), vertically centered in the header, so draw + click share the exact same geometry.
            if (!card.group && card.hasSub) {
                float exSize = descH + 3f;
                float exCy = card.y + card.headerH / 2f;
                card.exX2 = card.x + card.w - padX;
                card.exX1 = card.exX2 - exSize;
                card.exY1 = exCy - exSize / 2f;
                card.exY2 = exCy + exSize / 2f;
            }
            cards.add(card);
        }
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Resolve the accent once per frame — every accent-sensitive element reads it (live recolour).
        accent = GuiTheme.fromToken(settings().guiAccent);
        // Re-establish the 2D GUI orthographic projection ourselves (GuiIngameMixin cancels
        // renderGameOverlay while the panel is open, which otherwise skips vanilla's setupOverlayRendering).
        mc.entityRenderer.setupOverlayRendering();
        GuiBlur.update(); // render the world blur onto the framebuffer before anything draws on top
        advanceScroll();
        // Remap the host cursor into the fixed-"Large" virtual space the panel is laid out in.
        mouseX = Math.round(mouseX / uiScale);
        mouseY = Math.round(mouseY / uiScale);
        if (GuiBlur.isActive()) GuiRender.rect(0, 0, width, height, SCRIM);
        else drawDefaultBackground();

        GlStateManager.pushMatrix();
        GlStateManager.scale(uiScale, uiScale, 1f);
        GuiRender.scissorScale = uiScale;

        // Frameless: no outer panel body/header-band/border — the tabs and cards float directly on the
        // blurred, dimmed world. Layout still uses the (now invisible) panelX/Y/W/H region bounds.

        // While a dropdown is open it's modal: feed the rest of the GUI an off-screen cursor.
        int hx = openDropdownKind != 0 ? -1 : mouseX;
        int hy = openDropdownKind != 0 ? -1 : mouseY;

        drawTopBar(hx, hy);
        if (searchActive()) drawSearchResults(hx, hy);       // global search overlays any tab, incl. Players
        else if (playersTab()) drawPlayers(hx, hy);
        else drawCards(hx, hy);

        drawDropdownPopup(mouseX, mouseY);

        GuiRender.scissorScale = 1f;
        GlStateManager.popMatrix();
    }

    /** Top tab bar (frameless): version (far left), the section tabs (active = accent pill), then the
     *  search field and Edit HUD button (right). Floats directly on the world — no band or divider. */
    private void drawTopBar(int mouseX, int mouseY) {
        // Version — same size as the tabs, vertically centered to the same tab-bubble row so its baseline
        // lines up with the tab text.
        float vy = vcenter(tabsY1, tabsY2 - tabsY1, versionScale);
        GuiRender.text("v" + BedwarsQol.VERSION, versionX, vy, versionScale, GuiTheme.TEXT_MID, BedwarsQolFont.Weight.REGULAR);

        for (int i = 0; i < SECTIONS.length; i++) {
            float[] t = tabHits[i];
            boolean selected = i == selectedSection;
            boolean hover = !selected && GuiRender.inside(mouseX, mouseY, t[0], t[1], t[2], t[3]);
            float r = (t[3] - t[1]) / 2f;
            if (selected) {
                GuiRender.roundedRect(t[0], t[1], t[2], t[3], r, accent.pillFill());
            } else if (hover) {
                GuiRender.roundedRect(t[0], t[1], t[2], t[3], r, ROW_HOVER);
            }
            int col = selected ? TAB_ACTIVE_TEXT : (hover ? GuiTheme.TEXT_HI : GuiTheme.TEXT_MID);
            GuiRender.textCentered(SECTIONS[i].tab, (t[0] + t[2]) / 2f, vcenter(t[1], t[3] - t[1], tabScale), tabScale, col, MED);
        }

        drawSearchBar(mouseX, mouseY); // header search: global module search on every tab (incl. Players)
        drawEditHudButton(mouseX, mouseY);
    }

    /** The global Edit HUD action pinned to the top-right of the tab bar. Neutral/accent styled (never a
     *  destructive red): a subtle keyline that warms to the accent on hover. */
    private void drawEditHudButton(int mouseX, int mouseY) {
        boolean hover = GuiRender.inside(mouseX, mouseY, editHudX1, editHudY1, editHudX2, editHudY2);
        float r = (editHudY2 - editHudY1) / 2f; // pill corners, matching the tab buttons
        GuiRender.roundedRect(editHudX1, editHudY1, editHudX2, editHudY2, r, hover ? BTN_HOVER : BTN_BG);
        GuiRender.roundedRectOutline(editHudX1, editHudY1, editHudX2, editHudY2, r, 0.5f,
                hover ? TAB_ACTIVE_TEXT : GuiTheme.TEXT_MID);
        GuiRender.textCentered(EDIT_HUD_LABEL, (editHudX1 + editHudX2) / 2f,
                vcenter(editHudY1, editHudY2 - editHudY1, editHudScale), editHudScale,
                hover ? GuiTheme.TEXT_HI : GuiTheme.TEXT_MID, MED);
    }

    /** Persistent search field (right of the tab bar): a translucent rounded field (text + clear-x). The
     *  border appears only while focused. Typing switches the content area to results. */
    private void drawSearchBar(int mouseX, int mouseY) {
        String q = searchQuery.toString();
        float r = (searchBarY2 - searchBarY1) / 2f; // pill corners, matching the tab buttons
        GuiRender.roundedRect(searchBarX1, searchBarY1, searchBarX2, searchBarY2, r, SEARCH_BAR_BG);
        GuiRender.roundedRectOutline(searchBarX1, searchBarY1, searchBarX2, searchBarY2, r, 0.5f,
                searchFocused ? TAB_ACTIVE_TEXT : GuiTheme.TEXT_MID);
        float barCy = (searchBarY1 + searchBarY2) / 2f;
        float textX = searchBarX1 + 8;
        float textRight = searchClearX1 - 6;
        float sScale = headerScale; // same size as the tabs / Edit HUD
        float ty = vcenter(searchBarY1, searchBarY2 - searchBarY1, sScale);
        if (q.isEmpty()) {
            GuiRender.text("Search...", textX, ty, sScale, GuiTheme.TEXT_LO);
        } else {
            GuiRender.text(ellipsize(q, sScale, textRight - textX), textX, ty, sScale, GuiTheme.TEXT_HI);
        }
        if (!q.isEmpty()) {
            boolean xHover = GuiRender.inside(mouseX, mouseY, searchClearX1, searchBarY1, searchClearX2, searchBarY2);
            drawCross((searchClearX1 + searchClearX2) / 2f, barCy, (searchClearX2 - searchClearX1) * 0.28f,
                    xHover ? GuiTheme.TEXT_HI : GuiTheme.TEXT_MID);
        }
    }

    private void drawCards(int mouseX, int mouseY) {
        ClientSettings cfg = settings();
        int viewTop = cardsViewTop, viewBottom = cardsViewBottom;
        boolean clip = maxScroll > 0;
        float scrollDy = scroll - scrollRender; // shift baked (target) positions to the eased offset
        if (clip) GuiRender.beginScissor(contentX, viewTop, contentRight, viewBottom);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, scrollDy, 0f);
        int padX = cardPadX();
        float titleH = BedwarsQolFont.height(cardTitleScale);
        float descH = BedwarsQolFont.height(cardDescScale);
        for (Card card : cards) {
            if (clip && (card.y + card.h + scrollDy < viewTop || card.y + scrollDy > viewBottom)) continue;
            boolean module = !card.group;
            boolean on = module && toggleValue(cfg, card.module.kind);
            int base = on ? GuiTheme.CARD_ON_BG : GuiTheme.CARD_OFF_BG;
            GuiRender.gradientRoundedRectH(card.x, card.y, card.x + card.w, card.y + card.h, CARD_R,
                    gradHi(base), gradLo(base)); // left→right sheen on the card fill
            // Accent keyline on enabled modules and on the always-open Settings group containers; off
            // modules get the neutral hairline.
            GuiRender.roundedRectOutline(card.x, card.y, card.x + card.w, card.y + card.h, CARD_R, 0.5f,
                    (on || card.group) ? accent.enabledCardBorder() : GuiTheme.CARD_OFF_BORDER);

            if (module) {
                float blockH = titleH + CARD_TITLE_DESC_GAP + descH;
                float top = card.y + (card.headerH - blockH) / 2f;
                // Title (top-left) truncates before the top-right expander when present, else the full width.
                float titleMaxW = card.hasSub ? card.exX1 - (card.x + padX) - 4f : card.w - 2 * padX;
                GuiRender.text(ellipsize(card.module.label, cardTitleScale, titleMaxW), card.x + padX, top,
                        cardTitleScale, on ? GuiTheme.TEXT_HI : GuiTheme.TEXT_MID, MED);
                // +/- expander at the header's top-right.
                if (card.hasSub) {
                    // Rounded "+/-" chip behind the glyph (matches the reference cards' expander box).
                    float exR = clampf((card.exX2 - card.exX1) * 0.22f, 1.5f, 3f);
                    GuiRender.roundedRect(card.exX1, card.exY1, card.exX2, card.exY2, exR, GuiTheme.TRACK_OFF);
                    GuiRender.roundedRectOutline(card.exX1, card.exY1, card.exX2, card.exY2, exR, 0.5f, GuiTheme.CARD_OFF_BORDER);
                    drawExpandIcon((card.exX1 + card.exX2) / 2f, (card.exY1 + card.exY2) / 2f,
                            (card.exX2 - card.exX1) * 0.5f, expandedModules.contains(card.module.kind),
                            on ? GuiTheme.TEXT_HI : GuiTheme.TEXT_MID);
                }
                // Description on its own full-width line below the title.
                if (card.module.desc != null) {
                    float descX = card.x + padX;
                    GuiRender.text(ellipsize(card.module.desc, cardDescScale, (card.x + card.w - padX) - descX),
                            descX, top + titleH + CARD_TITLE_DESC_GAP, cardDescScale, GuiTheme.TEXT_LO);
                }
            } else if (!card.module.label.isEmpty()) {
                // GROUP header: just the label, vertically centered. A titleless container draws no header.
                float top = card.y + (card.headerH - titleH) / 2f;
                GuiRender.text(ellipsize(card.module.label, cardTitleScale, card.w - 2 * padX), card.x + padX, top,
                        cardTitleScale, GuiTheme.TEXT_HI, MED);
            }
            for (Row r : card.children) {
                drawControl(r, mouseX, mouseY, cfg);
            }
        }
        GlStateManager.popMatrix();
        if (clip) GuiRender.endScissor();
        // An empty section (e.g. the Players placeholder) draws a faint centered hint instead of cards.
        if (cards.isEmpty()) {
            GuiRender.textCentered("Coming soon", (contentX + contentRight) / 2f,
                    contentTop + contentH / 2f, cardTitleScale, GuiTheme.TEXT_LO, MED);
        }
        if (maxScroll > 0) drawScrollbar(viewTop, viewBottom, rowsBlockH);
    }

    /** Expand indicator: a "+" when collapsed, a "-" when expanded. Drawn as two device-pixel-snapped
     *  rects so the glyph stays crisp and symmetric at every GUI size. */
    private void drawExpandIcon(float cx, float cy, float w, boolean expanded, int color) {
        float dp = 1f / largeScaleFactor();
        int tpx = Math.max(2, Math.round(clampf(w * 0.16f, 0.5f, 0.9f) / dp));
        tpx -= tpx & 1;
        int apx = Math.max(tpx + 1, Math.round(w * 0.5f / dp));
        float gx = Math.round(cx / dp) * dp;
        float gy = Math.round(cy / dp) * dp;
        float ht = (tpx / 2) * dp;
        float arm = apx * dp;
        GuiRender.rect(gx - arm, gy - ht, gx + arm, gy + ht, color);
        if (!expanded) {
            GuiRender.rect(gx - ht, gy - arm, gx + ht, gy + arm, color);
        }
    }

    /** Renders one sub-option control (toggle / stepper / slider / keybind / action) inside a card. */
    private void drawControl(Row row, int mouseX, int mouseY, ClientSettings cfg) {
        float lScale = labelScale * 0.594f;
        float vScale = valueScale * 0.594f;
        float labelY = vcenter(row.y, row.h, lScale);
        // A MODULE child greys out while its parent master toggle is off; GROUP children are always live.
        boolean enabled = childEnabled(row.group, toggleValue(cfg, row.def.parentKind));
        int labelColor = enabled ? GuiTheme.TEXT_HI : GuiTheme.TEXT_LO;
        if (row.hit(mouseX, mouseY)) {
            GuiRender.roundedRect(row.x, row.y + 1, row.x + row.w, row.y + row.h - 1, 4, ROW_HOVER);
        }
        switch (row.def.type) {
            case TOGGLE: {
                GuiRender.text(row.def.label, row.x + 2, labelY, lScale, labelColor, MED);
                float[] sw = childSwitchRect(row);
                drawSwitch(sw[0], sw[1], sw[2], sw[3], toggleValue(cfg, row.def.kind), enabled);
                break;
            }
            case STEPPER: {
                GuiRender.text(row.def.label, row.x + 2, labelY, lScale, labelColor, MED);
                drawDropdownTrigger(row, row.def.options[stepperIndex(cfg, row.def.kind)], ddFontScale, enabled, mouseX, mouseY);
                break;
            }
            case ACTION: {
                GuiRender.text(row.def.label, row.x + 2, labelY, lScale, labelColor, MED);
                drawActionButton(row, actionLabel(row.def.kind), ddFontScale, enabled, mouseX, mouseY);
                break;
            }
            case SLIDER: {
                GuiRender.text(row.def.label, row.x + 2, labelY, lScale, labelColor, MED);
                float[] tr = sliderTrack(row);
                float trackX1 = tr[0], trackX2 = tr[1];
                float trackH = clampf(row.h * 0.16f, 3f, 5f);
                float trackY = row.y + row.h / 2f - trackH / 2f;
                float val = sliderValue(cfg, row.def.kind);
                float frac = clampf((val - row.def.min) / (row.def.max - row.def.min), 0f, 1f);
                float handleX = trackX1 + frac * (trackX2 - trackX1);
                GuiRender.roundedRect(trackX1, trackY, trackX2, trackY + trackH, trackH / 2f, enabled ? GuiTheme.TRACK_OFF : GuiTheme.TRACK_DISABLED);
                if (enabled) GuiRender.roundedRect(trackX1, trackY, handleX, trackY + trackH, trackH / 2f, GuiTheme.KNOB_OFF);
                float knobR = clampf(row.h * 0.13f, 2.5f, 4.5f);
                float kcy = row.y + row.h / 2f;
                GuiRender.circle(handleX, kcy, knobR + 0.6f, KNOB_RING);
                GuiRender.circle(handleX, kcy, knobR, enabled ? GuiTheme.TEXT_HI : GuiTheme.KNOB_DISABLED);
                if (enabled && editingSliderKind == row.def.kind) {
                    String buf = editSliderBuf.toString();
                    float vw = Math.max(GuiRender.textWidth(buf, vScale), GuiRender.textWidth("-0.00", vScale));
                    float vx2 = row.x + row.w;
                    float vx1 = vx2 - vw;
                    float vy = vcenter(row.y, row.h, vScale);
                    GuiRender.roundedRect(vx1 - 3, row.y + 2, vx2 + 1, row.y + row.h - 2, 3, 0x14FFF2E4);
                    GuiRender.text(buf, vx1, vy, vScale, GuiTheme.TEXT_HI);
                    if ((caretBlink / 6) % 2 == 0) {
                        float cx = vx1 + GuiRender.textWidth(buf, vScale);
                        GuiRender.rect(cx, vy - 1, cx + 1, vy + BedwarsQolFont.height(vScale), GuiTheme.TEXT_HI);
                    }
                } else {
                    String vs = formatSlider(val);
                    GuiRender.text(vs, (row.x + row.w) - GuiRender.textWidth(vs, vScale),
                            vcenter(row.y, row.h, vScale), vScale, enabled ? GuiTheme.TEXT_HI : GuiTheme.TEXT_LO);
                }
                break;
            }
            default:
                break;
        }
    }



    /** Compact accent switch: a rounded track with a circular knob. ON reads the resolved accent; OFF and
     *  disabled use the neutral warm track/knob. Used for module master toggles and child toggle rows. */
    private void drawSwitch(float x1, float y1, float x2, float y2, boolean on, boolean enabled) {
        float h = y2 - y1;
        float r = h / 2f;
        int track = !enabled ? GuiTheme.TRACK_DISABLED : (on ? accent.toggleOnTrack() : GuiTheme.TRACK_OFF);
        GuiRender.roundedRect(x1, y1, x2, y2, r, track);
        float knobR = r - clampf(h * 0.16f, 1f, 2.4f);
        float cy = (y1 + y2) / 2f;
        float cx = on ? (x2 - r) : (x1 + r);
        int knob = !enabled ? GuiTheme.KNOB_DISABLED : (on ? accent.toggleOnKnob() : GuiTheme.KNOB_OFF);
        GuiRender.circle(cx, cy, knobR + 0.6f, KNOB_RING);
        GuiRender.circle(cx, cy, knobR, knob);
    }

    /** Right-aligned switch rect for a child toggle row, sized to the row height. */
    private float[] childSwitchRect(Row row) {
        float h = clampf(row.h * 0.52f, 8f, 13f);
        float w = h * 1.9f;
        float rightPad = controlRightPad(row.h);
        float x2 = row.x + row.w - rightPad;
        float x1 = x2 - w;
        float y1 = row.y + (row.h - h) / 2f;
        return new float[]{x1, y1, x2, y1 + h};
    }

    /** The dropdown trigger button rect [x1,y1,x2,y2] for a stepper row. Right-aligned to the row and
     *  given a uniform width per page (widest option + padding + caret) so every dropdown lines up. */
    private float[] dropdownRect(Row row) {
        float h = clampf(row.h * 0.50f, 10f, 15f);
        float padX = ddPadX(row.h);
        float caretW = clampf(h * 0.24f, 2f, 3.2f);
        float w = pageStepperTextW + 2f * padX + padX * 0.6f + caretW;
        float x2 = row.x + row.w - controlRightPad(row.h);
        float x1 = x2 - w;
        float y1 = row.y + (row.h - h) / 2f;
        return new float[]{x1, y1, x2, y1 + h};
    }

    private float ddPadX(float rowHeight) {
        return clampf(rowHeight * 0.16f, 4f, 7f);
    }

    /** Card left/right internal padding. Shared by buildCards (switch/expander geometry) and drawCards
     *  (title/description text) so the two can never drift apart. */
    private int cardPadX() {
        return clamp(Math.round(rowH * CARD_PAD_X_F), 7, 13);
    }

    /** Inset from the row's right edge shared by the switch and the dropdown trigger, so their right
     *  edges line up down the column. */
    private float controlRightPad(float rowHeight) {
        return clampf(rowHeight * 0.32f, 7f, 13f);
    }

    /** Draws a stepper's dropdown trigger: a pill showing the current value with a caret. Brightens on
     *  hover; the open pill gets an accent outline. */
    private void drawDropdownTrigger(Row row, String value, float scale, boolean enabled, int mouseX, int mouseY) {
        float[] c = dropdownRect(row);
        boolean open = openDropdownKind == row.def.kind;
        boolean hover = enabled && GuiRender.inside(mouseX, mouseY, c[0], c[1], c[2], c[3]);
        float r = (c[3] - c[1]) * 0.13f;
        GuiRender.roundedRect(c[0], c[1], c[2], c[3], r, (hover || open) ? BTN_HOVER : BTN_BG);
        GuiRender.roundedRectOutline(c[0], c[1], c[2], c[3], r, 0.75f, open ? accent.dropdownOutline() : BTN_BORDER);
        float padX = ddPadX(row.h);
        float caretW = clampf((c[3] - c[1]) * 0.24f, 2f, 3.2f);
        float caretLeft = c[2] - padX - caretW;
        GuiRender.textCentered(value, (c[0] + caretLeft) / 2f, vcenter(c[1], c[3] - c[1], scale), scale,
                enabled ? GuiTheme.TEXT_HI : GuiTheme.TEXT_LO, MED);
        drawCaret(caretLeft + caretW / 2f, (c[1] + c[3]) / 2f, caretW / 2f, caretW * 0.34f, open, enabled ? GuiTheme.TEXT_MID : GuiTheme.TEXT_LO);
    }

    /** Button text for an ACTION row. */
    private static String actionLabel(int kind) {
        return kind == K_SESSION_RESET ? "Reset" : "Run";
    }

    /** One-shot action rows: no stored value, just an effect. */
    private static void runAction(int kind) {
        if (kind == K_SESSION_RESET) SessionStatsWatch.reset();
    }

    /** Button rect [x1,y1,x2,y2] for an ACTION row: right-aligned like the dropdown trigger, sized to its text. */
    private float[] actionRect(Row row, String label, float scale) {
        float h = clampf(row.h * 0.50f, 10f, 15f);
        float padX = ddPadX(row.h);
        float w = GuiRender.textWidth(label, scale, MED) + 2f * padX;
        float x2 = row.x + row.w - controlRightPad(row.h);
        float x1 = x2 - w;
        float y1 = row.y + (row.h - h) / 2f;
        return new float[]{x1, y1, x2, y1 + h};
    }

    /** Draws an ACTION row's button: the dropdown pill without a caret. Brightens on hover. */
    private void drawActionButton(Row row, String label, float scale, boolean enabled, int mouseX, int mouseY) {
        float[] c = actionRect(row, label, scale);
        boolean hover = enabled && GuiRender.inside(mouseX, mouseY, c[0], c[1], c[2], c[3]);
        float r = (c[3] - c[1]) * 0.13f;
        GuiRender.roundedRect(c[0], c[1], c[2], c[3], r, hover ? BTN_HOVER : BTN_BG);
        GuiRender.roundedRectOutline(c[0], c[1], c[2], c[3], r, 0.75f, BTN_BORDER);
        GuiRender.textCentered(label, (c[0] + c[2]) / 2f, vcenter(c[1], c[3] - c[1], scale), scale,
                enabled ? GuiTheme.TEXT_HI : GuiTheme.TEXT_LO, MED);
    }

    /** A small "v" (down) / "^" (up) caret, drawn as two AA hairlines. */
    private void drawCaret(float cx, float cy, float hw, float hh, boolean up, int color) {
        if (up) {
            GuiRender.line(cx - hw, cy + hh, cx, cy - hh, 0.85f, color);
            GuiRender.line(cx, cy - hh, cx + hw, cy + hh, 0.85f, color);
        } else {
            GuiRender.line(cx - hw, cy - hh, cx, cy + hh, 0.85f, color);
            GuiRender.line(cx, cy + hh, cx + hw, cy - hh, 0.85f, color);
        }
    }

    /** Option-list rect [x1,y1,x2,y2,itemH] for the open dropdown: dropped below the anchor, flipped
     *  above (or clamped within the panel) when it would overflow the bottom edge. */
    private float[] dropdownListGeom() {
        int n = ddOptions.length;
        float itemH = clampf(ddY2 - ddY1, 10f, 16f);
        float maxOpt = 0f;
        for (String o : ddOptions) maxOpt = Math.max(maxOpt, GuiRender.textWidth(o, ddScale, MED));
        float w = maxOpt + ddPad * 2f;
        float x2 = ddX2;
        float x1 = x2 - w;
        float totalH = n * itemH + DD_PAD_Y * 2f;
        float y1 = ddY2 + 2f;
        float limitTop = contentTop;
        float limitBottom = panelY + panelH - pad;
        if (y1 + totalH > limitBottom) {
            float above = ddY1 - 2f - totalH;
            y1 = above >= limitTop ? above : Math.max(limitTop, limitBottom - totalH);
        }
        return new float[]{x1, y1, x2, y1 + totalH, itemH};
    }

    /** Draws the open dropdown's option list on top of everything (called last in drawScreen). */
    private void drawDropdownPopup(int mouseX, int mouseY) {
        if (openDropdownKind == 0 || ddOptions == null) return;
        float[] g = dropdownListGeom();
        float x1 = g[0], y1 = g[1], x2 = g[2], y2 = g[3], itemH = g[4];
        float r = 2.5f;
        GuiRender.roundedRect(x1, y1, x2, y2, r, DD_BG);
        int sel = stepperIndex(settings(), openDropdownKind);
        float inset = 0.5f;
        float rr = r - inset;
        int last = ddOptions.length - 1;
        int n = ddOptions.length;
        // The hovered row comes from the same rule as the click (F10, I2). The old per-row containment
        // test was inclusive at both ends, so a boundary pixel painted two rows while the click applied
        // the one above the highlight.
        int hoverIdx = GuiRender.inside(mouseX, mouseY, x1, y1, x2, y2)
                ? DropdownHit.indexAt(mouseY, y1, DD_PAD_Y, itemH, n) : -1;
        for (int i = 0; i < n; i++) {
            float iy1 = DropdownHit.rowTop(y1, DD_PAD_Y, itemH, i);
            float iy2 = iy1 + itemH;
            boolean hover = i == hoverIdx;
            boolean selected = i == sel;
            if (hover || selected) {
                float hTop = i == 0 ? y1 + inset : iy1;
                float hBot = i == last ? y2 - inset : iy2;
                float rTL = 0f, rTR = 0f, rBR = 0f, rBL = 0f;
                if (n == 1) { rTL = rTR = rBR = rBL = rr; }
                else if (i == 0) { rTL = rTR = rr; }
                else if (i == last) { rBL = rBR = rr; }
                GuiRender.roundedRect(x1 + inset, hTop, x2 - inset, hBot, rTL, rTR, rBR, rBL,
                        hover ? DD_ITEM_HOVER : DD_ITEM_SELECTED);
            }
            GuiRender.text(ddOptions[i], x1 + ddPad, vcenter(iy1, itemH, ddScale), ddScale,
                    (selected || hover) ? GuiTheme.TEXT_HI : GuiTheme.TEXT_MID, MED);
        }
        // Keyline LAST, on top — the open menu's edge reads the accent to tie it to its trigger.
        GuiRender.roundedRectOutline(x1, y1, x2, y2, r, 0.75f, accent.dropdownOutline());
    }

    /** Opens (or toggles closed) the dropdown for a stepper row, anchoring it to the trigger button. */
    private void openDropdown(Row row, float scale) {
        if (openDropdownKind == row.def.kind) { closeDropdown(); return; }
        float[] c = dropdownRect(row);
        openDropdownKind = row.def.kind;
        ddOptions = row.def.options;
        ddY1 = c[1]; ddX2 = c[2]; ddY2 = c[3];
        ddScale = scale;
        ddPad = ddPadX(row.h);
        playClick();
    }

    private void closeDropdown() {
        openDropdownKind = 0;
        ddOptions = null;
    }

    private static float vcenter(float top, float boxH, float scale) {
        return top + (boxH - BedwarsQolFont.height(scale)) / 2f;
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        mouseX = Math.round(mouseX / uiScale);
        mouseY = Math.round(mouseY / uiScale);
        commitSliderEdit();
        ClientSettings cfg = settings();

        // An open dropdown is modal: the next click either picks an option or dismisses the menu.
        if (openDropdownKind != 0) {
            if (mouseButton == 0) {
                float[] g = dropdownListGeom();
                if (GuiRender.inside(mouseX, mouseY, g[0], g[1], g[2], g[3])) {
                    int idx = DropdownHit.indexAt(mouseY, g[1], DD_PAD_Y, g[4], ddOptions.length);
                    int kind = openDropdownKind;
                    setStepperIndex(cfg, kind, idx);
                    closeDropdown();
                    playClick();
                    cfg.save();
                    if (kind == K_GUISIZE) initGui(); // resize the panel live
                    return;
                }
            }
            closeDropdown();
            return;
        }

        if (mouseButton == 1) { // right-click: expand/collapse a module card (search or section)
            rightClickCards(mouseX, mouseY);
            return;
        }
        if (mouseButton != 0) return;

        // Two independent search fields: the header field (global module search, every tab) and the
        // above-list player field (Players tab, only while the list — not global results — is showing). A
        // click focuses whichever it lands in and blurs the other; any other click blurs both.
        boolean inHeaderSearch = GuiRender.inside(mouseX, mouseY, searchBarX1, searchBarY1, searchBarX2, searchBarY2);
        boolean inPlayerSearch = playersTab() && !searchActive()
                && GuiRender.inside(mouseX, mouseY, playerSearchX1, playerSearchY1, playerSearchX2, playerSearchY2);
        searchFocused = inHeaderSearch;
        playerSearchFocused = inPlayerSearch;
        if (inHeaderSearch) {
            boolean clearHit = GuiRender.inside(mouseX, mouseY, searchClearX1, searchBarY1, searchClearX2, searchBarY2);
            if (searchQuery.length() > 0 && clearHit) {
                searchQuery.setLength(0);
                resetScroll();
                layoutContent();
            }
            playClick();
            return;
        }
        if (inPlayerSearch) {
            boolean clearHit = GuiRender.inside(mouseX, mouseY, playerSearchClearX1, playerSearchY1, playerSearchClearX2, playerSearchY2);
            if (PlayersViewState.playerQuery.length() > 0 && clearHit && PlayersViewState.setQuery("")) {
                playersScrollRender = 0f;
            }
            playClick();
            return;
        }

        // Edit HUD button (top-right of the tab bar).
        if (GuiRender.inside(mouseX, mouseY, editHudX1, editHudY1, editHudX2, editHudY2)) {
            stopEditing();
            playClick();
            cfg.save();
            mc.displayGuiScreen(new EditHudGui());
            return;
        }

        // Scrollbar: grab the thumb to drag it, or click the track above/below to page.
        float[] sb = (playersTab() && !searchActive()) ? null : scrollbarGeom();
        if (sb != null && mouseX >= sb[0] - 3 && mouseX <= sb[1] + 1 && mouseY >= sb[2] && mouseY <= sb[3]) {
            float thumbY = sb[2] + sb[5] * clampf(scrollRender / maxScroll, 0f, 1f);
            if (mouseY >= thumbY && mouseY <= thumbY + sb[4]) {
                draggingThumb = true;
                thumbGrabDy = mouseY - thumbY;
            } else {
                int page = Math.max(rowH, (int) ((sb[3] - sb[2]) * 0.9f));
                scroll = clamp(scroll + (mouseY < thumbY ? -page : page), 0, maxScroll);
                layoutContent();
                playClick();
            }
            return;
        }

        // Top tabs — draw + click consume the SAME cached rectangles (no index-formula re-derivation).
        for (int i = 0; i < SECTIONS.length; i++) {
            float[] t = tabHits[i];
            if (GuiRender.inside(mouseX, mouseY, t[0], t[1], t[2], t[3])) {
                if (i != selectedSection || searchActive()) {
                    stopEditing();
                    selectedSection = i;
                    resetScroll();
                    searchQuery.setLength(0); // leaving search: clear the query so the section shows
                    layoutContent();
                    playClick();
                }
                return;
            }
        }

        if (playersTab() && !searchActive()) {
            playersClick(mouseX, mouseY, cfg);
            return;
        }
        // Section grid or search grid — same card click model either way.
        cardsClick(mouseX, mouseY, cfg);
    }

    /** Click routing inside the Players tab: list scrollbar, a player row (fetch on click), or the
     *  remote-lookup action row. */
    private void playersClick(int mouseX, int mouseY, ClientSettings cfg) {
        float[] sb = playersScrollbarGeom();
        if (sb != null && mouseX >= sb[0] - 3 && mouseX <= sb[1] + 2 && mouseY >= sb[2] && mouseY <= sb[3]) {
            float thumbY = sb[2] + sb[5] * clampf(playersScrollRender / playersMaxScroll, 0f, 1f);
            if (mouseY >= thumbY && mouseY <= thumbY + sb[4]) {
                draggingPlayersThumb = true;
                playersThumbGrabDy = mouseY - thumbY;
            } else {
                int page = Math.max(playersRowH, (int) ((sb[3] - sb[2]) * 0.9f));
                PlayersViewState.listScroll = clampf(PlayersViewState.listScroll + (mouseY < thumbY ? -page : page),
                        0, playersMaxScroll);
                playClick();
            }
            return;
        }
        for (int i = 0; i < playerRowHits.size(); i++) {
            float[] r = playerRowHits.get(i);
            if (mouseY >= playersListTop && mouseY < playersListBottom
                    && GuiRender.inside(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                UUID u = playerRowUuids.get(i);
                String n = playerRowNames.get(i);
                PlayersViewState.selectedUuid = u;
                PlayersViewState.selectedName = n;
                PlayersViewState.lookupName = null;
                detailScroll = detailScrollRender = detailScrollAccum = 0f; // new selection: top of detail
                if (u != null && HypixelContext.isOnHypixel()) {
                    boolean elig = cfg.urchinTags && UrchinTag.badgeAllowed(EligibilitySnapshot.current(), n, u);
                    StatsCache.ensureFetched(u, StatsCache.PRIORITY_USER, elig); // async, deduped, non-blocking
                }
                playClick();
                return;
            }
        }
        if (lookupRowHit != null
                && mouseY >= playersListTop && mouseY < playersListBottom
                && GuiRender.inside(mouseX, mouseY, lookupRowHit[0], lookupRowHit[1], lookupRowHit[2], lookupRowHit[3])) {
            triggerPlayerLookup();
        }
    }

    /** Runs the "Search Hypixel for X" remote lookup for the current player-search query. Shared by the
     *  action-row click and the Enter key; a no-op on a blank query. */
    private void triggerPlayerLookup() {
        String q = PlayersViewState.playerQuery.trim();
        if (q.isEmpty()) return;
        PlayersViewState.lookupName = q;
        PlayersViewState.selectedUuid = null;
        detailScroll = detailScrollRender = detailScrollAccum = 0f; // new selection: top of detail
        StatsCache.ensureFetchedByName(q, StatsCache.PRIORITY_USER);
        playClick();
    }

    private void cardsClick(int mouseX, int mouseY, ClientSettings cfg) {
        if (maxScroll > 0 && (mouseY < cardsViewTop || mouseY > cardsViewBottom)) return;
        for (Card card : cards) {
            if (GuiRender.inside(mouseX, mouseY, card.x, card.y, card.x + card.w, card.y + card.headerH)) {
                if (!card.group) {
                    if (card.hasSub && GuiRender.inside(mouseX, mouseY, card.exX1, card.exY1, card.exX2, card.exY2)) {
                        // The +/- expander is the explicit expand region; the rest of the header toggles.
                        Integer k = card.module.kind;
                        if (expandedModules.contains(k)) expandedModules.remove(k);
                        else expandedModules.add(k);
                        playClick();
                        relayoutCards();
                    } else {
                        toggle(cfg, card.module.kind);
                        playClick();
                        cfg.save();
                    }
                }
                // GROUP header: no-op (containers never toggle), but consume the click.
                return;
            }
            for (Row r : card.children) {
                if (!r.hit(mouseX, mouseY)) continue;
                // Consume the click either way; only dispatch it when the child is live under its parent.
                if (childEnabled(r.group, toggleValue(cfg, r.def.parentKind))) controlClick(r, mouseX, mouseY, cfg);
                return;
            }
        }
    }

    private void rightClickCards(int mouseX, int mouseY) {
        if (maxScroll > 0 && (mouseY < cardsViewTop || mouseY > cardsViewBottom)) return;
        for (Card card : cards) {
            if (card.group || !card.hasSub) continue;
            if (GuiRender.inside(mouseX, mouseY, card.x, card.y, card.x + card.w, card.y + card.headerH)) {
                Integer k = card.module.kind;
                if (expandedModules.contains(k)) expandedModules.remove(k);
                else expandedModules.add(k);
                playClick();
                relayoutCards();
                return;
            }
        }
    }

    /** Click handling for a single sub-option control inside a card. */
    private void controlClick(Row row, int mouseX, int mouseY, ClientSettings cfg) {
        switch (row.def.type) {
            case TOGGLE:
                toggle(cfg, row.def.kind);
                playClick();
                cfg.save();
                break;
            case STEPPER:
                openDropdown(row, ddFontScale);
                break;
            case ACTION: {
                float[] c = actionRect(row, actionLabel(row.def.kind), ddFontScale);
                if (GuiRender.inside(mouseX, mouseY, c[0], c[1], c[2], c[3])) {
                    runAction(row.def.kind);
                    playClick();
                }
                break;
            }
            case SLIDER: {
                float[] vr = sliderValueRect(row);
                if (GuiRender.inside(mouseX, mouseY, vr[0] - 3, vr[1] - 2, vr[2] + 2, vr[3] + 2)) {
                    startSliderEdit(row, cfg);
                    playClick();
                    return;
                }
                float[] tr = sliderTrack(row);
                if (mouseX >= tr[0] - 4 && mouseX < vr[0] - 3) {
                    draggingSliderKind = row.def.kind;
                    dragTrackX1 = tr[0];
                    dragTrackX2 = tr[1];
                    dragMin = row.def.min;
                    dragMax = row.def.max;
                    applySliderDrag(cfg, mouseX);
                    playClick();
                }
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onGuiClosed() {
        stopEditing();
        Keyboard.enableRepeatEvents(false);
        GuiBlur.end();
        // Persist the selected section so a normal reopen returns to the same tab (other Players view
        // state — selected player, list scroll, query — already lives in PlayersViewState statics).
        PlayersViewState.selectedSection = selectedSection;
        settings().save();
    }

    @Override
    public void updateScreen() {
        caretBlink++;
    }

    private static final float SCROLL_TAU = 0.10f; // scroll easing time-constant (s); smaller = snappier

    /** Eases the rendered scroll offset toward the integer target, frame-rate-independently. */
    private void advanceScroll() {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        if (maxScroll <= 0 || draggingThumb) { scrollRender = scroll; return; }
        if (dt <= 0f) { scrollRender = scroll; return; }
        if (dt > 0.1f) dt = 0.1f;
        scrollRender += (scroll - scrollRender) * (1f - (float) Math.exp(-dt / SCROLL_TAU));
        if (Math.abs(scroll - scrollRender) < 0.5f) scrollRender = scroll;
    }

    /** True when the hardware cursor is over the Players detail panel (right column), in virtual space. */
    private boolean wheelOverDetail() {
        if (mc.displayWidth <= 0) return false;
        float vx = (Mouse.getX() * width / (float) mc.displayWidth) / uiScale;
        return vx >= playersListX2;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int dwheel = Mouse.getEventDWheel();
        if (dwheel == 0) return;
        if (openDropdownKind != 0) return; // an open dropdown is modal: swallow the wheel
        if (playersTab() && !searchActive()) {
            // Route the wheel by cursor column: over the detail panel scrolls the detail, else the list.
            if (wheelOverDetail()) {
                if (detailMaxScroll <= 0) return;
                float dd = dwheel / 120f * (rowH * 1.1f);
                detailScrollAccum -= clampf(dd, -rowH * 2.5f, rowH * 2.5f);
                int dw = (int) detailScrollAccum;
                if (dw != 0) {
                    detailScrollAccum -= dw;
                    detailScroll = clampf(detailScroll + dw, 0, detailMaxScroll);
                }
                return;
            }
            if (playersMaxScroll <= 0) return;
            float d = dwheel / 120f * (playersRowH * 1.1f);
            playersScrollAccum -= clampf(d, -playersRowH * 2.5f, playersRowH * 2.5f);
            int whole = (int) playersScrollAccum;
            if (whole != 0) {
                playersScrollAccum -= whole;
                PlayersViewState.listScroll = clampf(PlayersViewState.listScroll + whole, 0, playersMaxScroll);
            }
            return;
        }
        if (maxScroll <= 0) return;
        int rh = Math.max(8, rowH);
        float delta = dwheel / 120f * (rh * 1.1f);
        delta = clampf(delta, -rh * 2.5f, rh * 2.5f);
        scrollAccum -= delta;
        int whole = (int) scrollAccum;
        if (whole != 0) {
            scrollAccum -= whole;
            scroll = clamp(scroll + whole, 0, maxScroll);
            layoutContent(); // cards bake y from `scroll` -> re-layout
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        mouseX = Math.round(mouseX / uiScale);
        mouseY = Math.round(mouseY / uiScale);
        if (clickedMouseButton == 0 && draggingPlayersThumb) {
            float[] sb = playersScrollbarGeom();
            if (sb != null && sb[5] > 0f) {
                float newTop = clampf(mouseY - sb[2] - playersThumbGrabDy, 0f, sb[5]);
                PlayersViewState.listScroll = clampf(Math.round(newTop / sb[5] * playersMaxScroll), 0, playersMaxScroll);
                playersScrollRender = PlayersViewState.listScroll;
            }
            return;
        }
        if (clickedMouseButton == 0 && draggingThumb) {
            float[] sb = scrollbarGeom();
            if (sb != null && sb[5] > 0f) {
                float newTop = clampf(mouseY - sb[2] - thumbGrabDy, 0f, sb[5]);
                scroll = clamp(Math.round(newTop / sb[5] * maxScroll), 0, maxScroll);
                scrollRender = scroll;
                layoutContent();
            }
            return;
        }
        if (clickedMouseButton == 0 && draggingSliderKind != 0) {
            applySliderDrag(settings(), mouseX);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        draggingThumb = false;
        draggingPlayersThumb = false;
        if (draggingSliderKind != 0) {
            draggingSliderKind = 0;
            settings().save();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (openDropdownKind != 0) {
            if (keyCode == Keyboard.KEY_ESCAPE) { closeDropdown(); playClick(); }
            return;
        }
        if (editingSliderKind != 0) {
            sliderEditKey(typedChar, keyCode);
            return;
        }
        if (searchFocused) { searchKey(typedChar, keyCode); return; }              // header: global search
        if (playerSearchFocused) { playersSearchKey(typedChar, keyCode); return; } // above-list: player filter
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---------------------------------------------------------------- config glue

    /** Whether an expanded sub-option row is interactive: GROUP children (synthetic parent kind, no real
     *  toggle) are always live; MODULE children are live only while their parent master toggle is on. Pure
     *  so the disabled-child contract can be unit-tested without a GL context. */
    static boolean childEnabled(boolean group, boolean parentOn) {
        return group || parentOn;
    }

    private static boolean toggleValue(ClientSettings cfg, int kind) {
        switch (kind) {
            case K_INVENTORY: return cfg.inventoryHudEnabled;
            case K_GENTIMERS: return cfg.genTimersEnabled;
            case K_SESSION: return cfg.sessionStatsEnabled;
            case K_SESSION_INGAME: return cfg.sessionStatsInGameOnly;
            case K_HEIGHT: return cfg.heightLimitEnabled;
            case K_HEIGHT_INGAME: return cfg.heightLimitInGameOnly;
            case K_STATS: return cfg.playerStats;
            case K_NAMETAG: return cfg.playerStatsNametag;
            case K_TAB: return cfg.playerStatsTab;
            case K_CHATHOVER: return cfg.playerStatsChatHover;
            case K_CHATSTATS: return cfg.playerStatsChat;
            case K_RANK: return cfg.playerStatsShowRank;
            case K_SWEATREPORT: return cfg.statsSweatReport;
            case K_PARTYJOIN: return cfg.partyJoinAlert;
            case K_NICKUTILS: return cfg.nickUtils;
            case K_NICK_NOTIFY: return cfg.nickNotify;
            case K_AUTO_DENICK: return cfg.autoDenick;
            case K_TAB_HEADERFOOTER: return cfg.tabHideHeaderFooter;
            case K_INVENTORY_INGAME: return cfg.inventoryInGameOnly;
            case K_INVENTORY_BG: return cfg.inventoryBackgroundEnabled;
            case K_GENTIMERS_BG: return cfg.genTimersBackgroundEnabled;
            case K_HANDPOS: return cfg.handPositionEnabled;
            case K_SUPPRESSESC: return cfg.suppressEscMenu;
            case K_NOTIFY_INC: return cfg.chatNotifyInc;
            case K_INC_KEY: return cfg.pcIncKey;
            case K_URCHIN: return cfg.urchinTags;
            case K_URCHIN_BADGE_TAB: return cfg.urchinBadgeTab;
            case K_URCHIN_CHAT_ALERT: return cfg.urchinChatAlert;
            case K_URCHIN_SOUND: return cfg.urchinAlertSound;
            case K_URCHIN_BADGE_NAMETAG: return cfg.urchinBadgeNametag;
            case K_SERAPH: return cfg.seraphTags;
            case K_SERAPH_BADGE_TAB: return cfg.seraphBadgeTab;
            case K_SERAPH_CHAT_ALERT: return cfg.seraphChatAlert;
            case K_SERAPH_SOUND: return cfg.seraphAlertSound;
            case K_SERAPH_BADGE_NAMETAG: return cfg.seraphBadgeNametag;
            case K_QUEUE_TAG_ALERT: return cfg.queueTagAlert;
            case K_QUEUE_NICK_ALERT: return cfg.queueNickAlert;
            default: return false;
        }
    }

    private static void toggle(ClientSettings cfg, int kind) {
        switch (kind) {
            case K_INVENTORY: cfg.inventoryHudEnabled = !cfg.inventoryHudEnabled; break;
            case K_GENTIMERS: cfg.genTimersEnabled = !cfg.genTimersEnabled; break;
            case K_SESSION: cfg.sessionStatsEnabled = !cfg.sessionStatsEnabled; break;
            case K_SESSION_INGAME: cfg.sessionStatsInGameOnly = !cfg.sessionStatsInGameOnly; break;
            case K_HEIGHT: cfg.heightLimitEnabled = !cfg.heightLimitEnabled; break;
            case K_HEIGHT_INGAME: cfg.heightLimitInGameOnly = !cfg.heightLimitInGameOnly; break;
            case K_STATS: cfg.playerStats = !cfg.playerStats; break;
            case K_NAMETAG: cfg.playerStatsNametag = !cfg.playerStatsNametag; break;
            case K_TAB: cfg.playerStatsTab = !cfg.playerStatsTab; break;
            case K_CHATHOVER: cfg.playerStatsChatHover = !cfg.playerStatsChatHover; break;
            case K_CHATSTATS: cfg.playerStatsChat = !cfg.playerStatsChat; break;
            case K_RANK: cfg.playerStatsShowRank = !cfg.playerStatsShowRank; break;
            case K_SWEATREPORT: cfg.statsSweatReport = !cfg.statsSweatReport; break;
            case K_PARTYJOIN: cfg.partyJoinAlert = !cfg.partyJoinAlert; break;
            case K_NICKUTILS: cfg.nickUtils = !cfg.nickUtils; break;
            case K_NICK_NOTIFY: cfg.nickNotify = !cfg.nickNotify; break;
            case K_AUTO_DENICK: cfg.autoDenick = !cfg.autoDenick; break;
            case K_TAB_HEADERFOOTER: cfg.tabHideHeaderFooter = !cfg.tabHideHeaderFooter; break;
            case K_INVENTORY_INGAME: cfg.inventoryInGameOnly = !cfg.inventoryInGameOnly; break;
            case K_INVENTORY_BG: cfg.inventoryBackgroundEnabled = !cfg.inventoryBackgroundEnabled; break;
            case K_GENTIMERS_BG: cfg.genTimersBackgroundEnabled = !cfg.genTimersBackgroundEnabled; break;
            case K_HANDPOS: cfg.handPositionEnabled = !cfg.handPositionEnabled; break;
            case K_SUPPRESSESC: cfg.suppressEscMenu = !cfg.suppressEscMenu; break;
            case K_NOTIFY_INC: cfg.chatNotifyInc = !cfg.chatNotifyInc; break;
            case K_INC_KEY: cfg.pcIncKey = !cfg.pcIncKey; break;
            case K_URCHIN: cfg.urchinTags = !cfg.urchinTags; StatsCache.invalidateUrchinResolution(); break;
            case K_URCHIN_BADGE_TAB: cfg.urchinBadgeTab = !cfg.urchinBadgeTab; break;
            case K_URCHIN_CHAT_ALERT: cfg.urchinChatAlert = !cfg.urchinChatAlert; break;
            case K_URCHIN_SOUND: cfg.urchinAlertSound = !cfg.urchinAlertSound; break;
            case K_URCHIN_BADGE_NAMETAG: cfg.urchinBadgeNametag = !cfg.urchinBadgeNametag; break;
            case K_SERAPH: cfg.seraphTags = !cfg.seraphTags; StatsCache.invalidateSeraphResolution(); break;
            case K_SERAPH_BADGE_TAB: cfg.seraphBadgeTab = !cfg.seraphBadgeTab; break;
            case K_SERAPH_CHAT_ALERT: cfg.seraphChatAlert = !cfg.seraphChatAlert; break;
            case K_SERAPH_SOUND: cfg.seraphAlertSound = !cfg.seraphAlertSound; break;
            case K_SERAPH_BADGE_NAMETAG: cfg.seraphBadgeNametag = !cfg.seraphBadgeNametag; break;
            case K_QUEUE_TAG_ALERT: cfg.queueTagAlert = !cfg.queueTagAlert; break;
            case K_QUEUE_NICK_ALERT: cfg.queueNickAlert = !cfg.queueNickAlert; break;
            default: break;
        }
    }

    private static int stepperIndex(ClientSettings cfg, int kind) {
        if (kind == K_ACCENT) return GuiTheme.fromToken(cfg.guiAccent).ordinal();
        if (kind == K_GUISIZE) return cfg.guiSize;
        if (kind == K_HUDSIZE) return cfg.defaultTextSize;
        if (kind == K_SCOREBOARD_SIZE) return cfg.scoreboardSize;
        if (kind == K_STYLEDTAB_SIZE) return cfg.styledTabListSize;
        if (kind == K_HUDFONT) return cfg.hudFont;
        return cfg.hudDisplayMode;
    }

    /** Sets a stepper to a specific option index (chosen from the dropdown menu). */
    private static void setStepperIndex(ClientSettings cfg, int kind, int idx) {
        if (kind == K_ACCENT) {
            cfg.guiAccent = ACCENT_TOKENS[clamp(idx, 0, ACCENT_TOKENS.length - 1)];
        } else if (kind == K_GUISIZE) {
            cfg.guiSize = idx;
        } else if (kind == K_HUDSIZE) {
            cfg.defaultTextSize = idx;
            cfg.applyDefaultTextSize();
        } else if (kind == K_DISPLAY) {
            cfg.hudDisplayMode = idx;
        } else if (kind == K_SCOREBOARD_SIZE) {
            cfg.scoreboardSize = idx;
        } else if (kind == K_STYLEDTAB_SIZE) {
            cfg.styledTabListSize = idx;
        } else if (kind == K_HUDFONT) {
            cfg.hudFont = idx;
        }
    }

    // ---- sliders (continuous values, e.g. hand position X/Y/Z) ----

    private static float sliderValue(ClientSettings cfg, int kind) {
        switch (kind) {
            case K_HANDX: return cfg.handPosX;
            case K_HANDY: return cfg.handPosY;
            case K_HANDZ: return cfg.handPosZ;
            case K_HANDSCALE: return cfg.handScale;
            default: return 0f;
        }
    }

    private static void setSlider(ClientSettings cfg, int kind, float val) {
        switch (kind) {
            case K_HANDX: cfg.handPosX = val; break;
            case K_HANDY: cfg.handPosY = val; break;
            case K_HANDZ: cfg.handPosZ = val; break;
            case K_HANDSCALE: cfg.handScale = val; break;
            default: break;
        }
    }

    /** Track [x1, x2] for a slider row, sized so it never shifts as the value (and its width) changes. */
    private float[] sliderTrack(Row row) {
        float vScale = row.def.child ? valueScale * 0.9f : valueScale;
        float vw = GuiRender.textWidth("-0.00", vScale);
        float trackW = clampf(row.w * 0.42f, 44f, 96f);
        float trackX2 = (row.x + row.w) - vw - clampf(row.h * 0.3f, 6f, 12f);
        return new float[]{trackX2 - trackW, trackX2};
    }

    private void applySliderDrag(ClientSettings cfg, int mouseX) {
        float frac = clampf((mouseX - dragTrackX1) / Math.max(1f, dragTrackX2 - dragTrackX1), 0f, 1f);
        float val = dragMin + frac * (dragMax - dragMin);
        val = Math.round(val * 100f) / 100f;
        setSlider(cfg, draggingSliderKind, val);
    }

    private static String formatSlider(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    /** Screen rect [x1,y1,x2,y2] of a slider's right-aligned numeric value (the click-to-type hot zone). */
    private float[] sliderValueRect(Row row) {
        float vScale = row.def.child ? valueScale * 0.9f : valueScale;
        String vs = editingSliderKind == row.def.kind ? editSliderBuf.toString()
                : formatSlider(sliderValue(settings(), row.def.kind));
        float w = Math.max(GuiRender.textWidth(vs, vScale), GuiRender.textWidth("-0.00", vScale));
        float x2 = row.x + row.w;
        float x1 = x2 - w;
        float y1 = vcenter(row.y, row.h, vScale);
        float y2 = y1 + BedwarsQolFont.height(vScale);
        return new float[]{x1, y1, x2, y2};
    }

    private void startSliderEdit(Row row, ClientSettings cfg) {
        commitSliderEdit();
        draggingSliderKind = 0;
        editingSliderKind = row.def.kind;
        editSliderMin = row.def.min;
        editSliderMax = row.def.max;
        editSliderBuf.setLength(0);
        editSliderBuf.append(formatSlider(sliderValue(cfg, row.def.kind)));
        caretBlink = 0;
    }

    private void commitSliderEdit() {
        if (editingSliderKind == 0) return;
        int kind = editingSliderKind;
        editingSliderKind = 0;
        float val;
        try {
            val = Float.parseFloat(editSliderBuf.toString().trim());
        } catch (NumberFormatException e) {
            return;
        }
        val = clampf(val, editSliderMin, editSliderMax);
        val = Math.round(val * 100f) / 100f;
        ClientSettings cfg = settings();
        setSlider(cfg, kind, val);
        cfg.save();
    }

    private void cancelSliderEdit() {
        editingSliderKind = 0;
    }

    private void sliderEditKey(char typedChar, int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                commitSliderEdit();
                playClick();
                return;
            case Keyboard.KEY_ESCAPE:
                cancelSliderEdit();
                return;
            case Keyboard.KEY_BACK:
                if (editSliderBuf.length() > 0) editSliderBuf.deleteCharAt(editSliderBuf.length() - 1);
                caretBlink = 0;
                return;
            default:
                if (editSliderBuf.length() >= 8) return;
                if ((typedChar >= '0' && typedChar <= '9')
                        || (typedChar == '-' && editSliderBuf.length() == 0)
                        || (typedChar == '.' && editSliderBuf.indexOf(".") < 0)) {
                    editSliderBuf.append(typedChar);
                    caretBlink = 0;
                }
        }
    }

    /** Active scrollbar geometry for the current view, or null when nothing scrolls.
     *  Indices: [0]=x1 [1]=x2 [2]=top [3]=bottom [4]=thumbH [5]=travel — mirrors {@link #drawScrollbar}. */
    private float[] scrollbarGeom() {
        if (maxScroll <= 0) return null;
        int top = cardsViewTop, bottom = cardsViewBottom, contentPx = rowsBlockH;
        int trackH = bottom - top;
        if (trackH <= 0 || contentPx <= 0) return null;
        float thumbH = Math.max(14f, trackH * (trackH / (float) contentPx));
        return new float[]{contentRight - 3, contentRight, top, bottom, thumbH, trackH - thumbH};
    }

    /** Thin scrollbar on the right edge of a viewport [top, bottom] given the total content height. */
    private void drawScrollbar(int top, int bottom, int contentPx) {
        int trackH = bottom - top;
        if (trackH <= 0 || contentPx <= 0) return;
        float x2 = contentRight, x1 = x2 - 1.0f;
        GuiRender.roundedRect(x1, top, x2, bottom, 0.5f, 0x14FFF2E4);
        float thumbH = Math.max(14f, trackH * (trackH / (float) contentPx));
        float travel = trackH - thumbH;
        float t = maxScroll == 0 ? 0f : clampf(scrollRender / maxScroll, 0f, 1f);
        float thumbY = top + travel * t;
        GuiRender.roundedRect(x1, thumbY, x2, thumbY + thumbH, 0.5f, 0x59FFF2E4);
    }

    private void drawCross(float cx, float cy, float r, int color) {
        GuiRender.line(cx - r, cy - r, cx + r, cy + r, 0.75f, color);
        GuiRender.line(cx - r, cy + r, cx + r, cy - r, 0.75f, color);
    }

    private void stopEditing() {
        commitSliderEdit();
        closeDropdown();
    }

    private static String ellipsize(String s, float scale, float maxW) {
        if (s == null || s.isEmpty()) return "";
        if (GuiRender.textWidth(s, scale) <= maxW) return s;
        float ew = GuiRender.textWidth("...", scale);
        int end = s.length();
        while (end > 0 && GuiRender.textWidth(s.substring(0, end), scale) + ew > maxW) end--;
        return s.substring(0, end) + "...";
    }

    // ---------------------------------------------------------------- search

    /** Collects every top-level module (toggle) from the searchable (non-grouped) card sections. */
    private void collectModules() {
        allModules.clear();
        for (Section s : SECTIONS) {
            if (s.grouped) continue; // Settings/Debug groups are not searchable modules
            for (RowDef rd : s.rows) {
                if (!rd.child && rd.desc != null) allModules.add(new SearchModule(rd, s.tab));
            }
        }
    }

    /** True when a non-empty query is active — the content area shows search results instead of the
     *  selected section. The search field itself lives in the top bar and is always visible. */
    private boolean searchActive() {
        return searchQuery.toString().trim().length() > 0;
    }

    /** Filters + ranks the module list for the current query and lays the results out as module cards
     *  across the full content band. */
    private void layoutSearchResults() {
        searchListTop = contentTop;
        searchListBottom = contentTop + contentH;

        searchResults.clear();
        final String q = searchQuery.toString().trim();
        if (q.isEmpty()) {
            searchResults.addAll(allModules);
        } else {
            final java.util.IdentityHashMap<SearchModule, Integer> score =
                    new java.util.IdentityHashMap<SearchModule, Integer>();
            List<Integer> tmp = new ArrayList<Integer>();
            for (SearchModule m : allModules) {
                int best = ModuleSearch.scoreField(q, m.def.label, tmp);
                int d = ModuleSearch.scoreField(q, m.def.desc, tmp);
                if (d != ModuleSearch.NO_MATCH) best = Math.max(best, d - 40);
                int sc = ModuleSearch.scoreField(q, m.section, tmp);
                if (sc != ModuleSearch.NO_MATCH) best = Math.max(best, sc - 60);
                if (best != ModuleSearch.NO_MATCH) {
                    score.put(m, best);
                    searchResults.add(m);
                }
            }
            java.util.Collections.sort(searchResults, new java.util.Comparator<SearchModule>() {
                public int compare(SearchModule a, SearchModule b) {
                    int s = Integer.compare(score.get(b), score.get(a));
                    if (s != 0) return s;
                    int l = Integer.compare(a.def.label.length(), b.def.label.length());
                    if (l != 0) return l;
                    return Integer.compare(allModules.indexOf(a), allModules.indexOf(b));
                }
            });
        }

        List<RowDef> moduleDefs = new ArrayList<RowDef>(searchResults.size());
        List<List<RowDef>> childDefs = new ArrayList<List<RowDef>>(searchResults.size());
        for (SearchModule m : searchResults) {
            moduleDefs.add(m.def);
            childDefs.add(childrenOf(m.def));
        }
        buildCards(moduleDefs, childDefs, searchListTop, Math.max(0, searchListBottom - searchListTop));
    }

    private void drawSearchResults(int mouseX, int mouseY) {
        if (searchResults.isEmpty()) {
            GuiRender.textCentered("No matches", (contentX + contentRight) / 2f,
                    searchListTop + (searchListBottom - searchListTop) / 2f - BedwarsQolFont.height(valueScale) / 2f,
                    valueScale, GuiTheme.TEXT_LO);
        } else {
            drawCards(mouseX, mouseY);
        }
    }

    private void searchKey(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (searchQuery.length() > 0) {
                searchQuery.setLength(0);
                resetScroll();
                layoutContent();
            }
            searchFocused = false;
            return;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (searchQuery.length() > 0) searchQuery.deleteCharAt(searchQuery.length() - 1);
            resetScroll();
            layoutContent();
            return;
        }
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar) && searchQuery.length() < 48) {
            searchQuery.append(typedChar);
            resetScroll();
            layoutContent();
        }
    }

    private void resetScroll() {
        scroll = 0;
        scrollRender = 0f;
        scrollAccum = 0f;
    }

    /** Search-box key handling while the Players tab is active — drives the (separate, session-persisted)
     *  player query, never the module query. */
    private void playersSearchKey(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (PlayersViewState.setQuery("")) playersScrollRender = 0f;
            playerSearchFocused = false; // the field that routed this key, so a second ESC can close the GUI (F8)
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            // Off Hypixel there is nothing to look up, and the action row that offers it is not even
            // built - Enter must not bypass that gate (F9).
            if (HypixelContext.isOnHypixel()) triggerPlayerLookup();
            return;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            String cur = PlayersViewState.playerQuery;
            if (cur.length() > 0 && PlayersViewState.setQuery(cur.substring(0, cur.length() - 1))) {
                playersScrollRender = 0f;
            }
            return;
        }
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar) && PlayersViewState.playerQuery.length() < 48) {
            if (PlayersViewState.setQuery(PlayersViewState.playerQuery + typedChar)) playersScrollRender = 0f;
        }
    }

    // =====================================================================================
    // Players tab — a fetch-on-click master-detail view. The left list shows the tab-list players
    // (no fetching); clicking a player fetches that one via StatsCache (existing deduped USER path) and
    // the right panel renders its full stats + Urchin state. Search filters the names; a no-match query
    // offers a single remote lookup. All view state lives in PlayersViewState (session-persistent).
    // =====================================================================================

    private static final int PLAYERS_PANEL_BG = 0xDE201B15;   // list/detail fill (matches module card opacity)
    private static final int PLAYERS_ROW_SEL  = 0x33FFF2E4;    // selected row wash
    private static final int DETAIL_CARD_BG   = 0x16FFFFFF;    // per-mode card fill
    private static final int DETAIL_DIVIDER   = 0x22FFFFFF;    // section rule

    /** Multiply an ARGB color's alpha by {@code a} (clamped) for the list slide/fade. Keeps alpha &ge; 1 so
     *  the custom font's "alpha 0 &rarr; opaque" quirk never triggers on a nearly-faded row. */
    private static int applyAlpha(int argb, float a) {
        int base = (argb >>> 24) & 0xFF;
        int na = Math.max(1, Math.round(base * clampf(a, 0f, 1f)));
        return (na << 24) | (argb & 0xFFFFFF);
    }

    private void drawPlayers(int mouseX, int mouseY) {
        ClientSettings cfg = settings();
        // Ease the list scroll toward its (session-persisted) target.
        playersScrollRender += (PlayersViewState.listScroll - playersScrollRender) * 0.35f;
        if (Math.abs(PlayersViewState.listScroll - playersScrollRender) < 0.5f) {
            playersScrollRender = PlayersViewState.listScroll;
        }

        // Panel backdrops for the two columns, each with an accent keyline matching the module cards.
        GuiRender.roundedRect(playersX1, playersTop, playersListX2, playersBottom, CARD_R, PLAYERS_PANEL_BG);
        GuiRender.roundedRect(playersDetailX1, playersTop, playersX2, playersBottom, CARD_R, PLAYERS_PANEL_BG);
        GuiRender.roundedRectOutline(playersX1, playersTop, playersListX2, playersBottom, CARD_R, 0.5f, accent.enabledCardBorder());
        GuiRender.roundedRectOutline(playersDetailX1, playersTop, playersX2, playersBottom, CARD_R, 0.5f, accent.enabledCardBorder());

        // Dedicated player-search field at the top of the list panel.
        drawPlayerSearchBar(mouseX, mouseY);

        boolean onHypixel = HypixelContext.isOnHypixel();

        // Live players in enumeration order + per-row search match (insertion order = slot order).
        String q = PlayersViewState.playerQuery.trim();
        List<UUID> liveUuids = new ArrayList<UUID>();
        List<String> liveNames = new ArrayList<String>();
        List<Integer> tmp = new ArrayList<Integer>();
        List<Boolean> matchList = new ArrayList<Boolean>();
        int shownLive = 0;
        for (NetworkPlayerInfo info : tabPlayers()) {
            UUID uuid = info.getGameProfile().getId();
            String name = profileName(info);
            if (uuid == null || name == null) continue;
            boolean m = q.isEmpty() || ModuleSearch.scoreField(q, name, tmp) != ModuleSearch.NO_MATCH;
            liveUuids.add(uuid);
            liveNames.add(name);
            matchList.add(m);
            if (m) shownLive++;
        }
        boolean[] matched = new boolean[matchList.size()];
        for (int i = 0; i < matched.length; i++) matched[i] = matchList.get(i);

        int listPad = clamp(Math.round(pad * 0.6f), 5, 10);
        int listInnerX1 = playersX1 + listPad;
        int listInnerX2 = playersListX2 - listPad;
        int listTop = playerSearchY2 + clamp(Math.round(pad * 0.5f), 4, 9); // below the search field
        int listBottom = playersBottom - listPad;
        playersListTop = listTop;
        playersListBottom = listBottom;
        int rowGap = 2;
        int rowStride = playersRowH + rowGap;
        boolean wantLookup = !q.isEmpty() && shownLive == 0 && onHypixel;
        int rows = shownLive + (wantLookup ? 1 : 0);
        int blockH = rows * rowStride;
        int viewH = listBottom - listTop;
        playersMaxScroll = Math.max(0, blockH - viewH);
        // Clamp both the target and the eased render offset so a shrinking/filtered list can't leave a
        // stale negative/overrun scroll that paints (or hit-tests) into the panel padding.
        PlayersViewState.listScroll = clampf(PlayersViewState.listScroll, 0, playersMaxScroll);
        playersScrollRender = clampf(playersScrollRender, 0, playersMaxScroll);

        // Advance the stable-insertion animation model against the live set (seed/append/leave/filter).
        playersAnim.sync(liveUuids, liveNames, matched, listTop, playersScrollRender, rowStride);

        // ---- Left: player list (clipped, scrollable) ----
        playerRowHits.clear();
        playerRowUuids.clear();
        playerRowNames.clear();
        lookupRowHit = null;

        // Always scissor the list (even at zero max) so nothing paints into the top/bottom padding.
        GuiRender.beginScissor(playersX1, listTop, playersListX2, listBottom);
        float rowScale = clampf(playersRowH * 0.042f, 1.0f, 1.6f);
        long now = System.currentTimeMillis();
        for (PlayersListAnim.Row row : playersAnim.rows()) {
            if (row.alpha <= 0.02f) continue;
            float y = row.renderY;
            if (!(y + playersRowH > listTop && y < listBottom)) continue; // half-open viewport clip
            boolean sel = !row.leaving && row.uuid.equals(PlayersViewState.selectedUuid);
            boolean hittable = row.hittable();
            boolean hover = hittable && !sel
                    && GuiRender.inside(mouseX, mouseY, listInnerX1, y, listInnerX2, y + playersRowH)
                    && mouseY >= listTop && mouseY < listBottom;
            if (sel) GuiRender.roundedRect(listInnerX1, y, listInnerX2, y + playersRowH, CARD_R,
                    applyAlpha(PLAYERS_ROW_SEL, row.alpha));
            else if (hover) GuiRender.roundedRect(listInnerX1, y, listInnerX2, y + playersRowH, CARD_R,
                    applyAlpha(ROW_HOVER, row.alpha));
            drawPlayerRow(cfg, row, listInnerX1, listInnerX2, y, rowScale, now);
            // A hit rectangle is appended (with its UUID/name) only for a present, matching, clickable
            // live row — never a fading ghost or a filtered row — so the shared hit index in
            // playersClick() can never resolve a click to one.
            if (hittable) {
                playerRowHits.add(new float[]{listInnerX1, y, listInnerX2, y + playersRowH});
                playerRowUuids.add(row.uuid);
                playerRowNames.add(row.name);
            }
        }
        if (wantLookup) {
            float y = listTop - playersScrollRender + shownLive * rowStride;
            if (y + playersRowH > listTop && y < listBottom) {
                boolean hover = GuiRender.inside(mouseX, mouseY, listInnerX1, y, listInnerX2, y + playersRowH)
                        && mouseY >= listTop && mouseY < listBottom;
                if (hover) GuiRender.roundedRect(listInnerX1, y, listInnerX2, y + playersRowH, CARD_R, ROW_HOVER);
                GuiRender.text(ellipsize("Search Hypixel for \"" + q + "\"", rowScale, listInnerX2 - listInnerX1 - 10),
                        listInnerX1 + 6, vcenter((int) y, playersRowH, rowScale), rowScale, accent.enabledCardBorder(), MED);
                lookupRowHit = new float[]{listInnerX1, y, listInnerX2, y + playersRowH};
            }
        }
        GuiRender.endScissor();

        if (shownLive == 0 && !wantLookup) {
            String msg = !onHypixel ? "Open on Hypixel to see players"
                    : (q.isEmpty() ? "No players in the tab list" : "No match");
            GuiRender.textCentered(msg, (playersX1 + playersListX2) / 2f,
                    listTop + viewH / 2f - BedwarsQolFont.height(rowScale) / 2f, rowScale, GuiTheme.TEXT_LO);
        }
        if (playersMaxScroll > 0) drawPlayersScrollbar(listTop, listBottom, blockH);

        // ---- Right: detail panel (own sequential, non-nested scissor so long content can't overrun) ----
        GuiRender.beginScissor(playersDetailX1, playersTop, playersX2, playersBottom);
        drawPlayerDetail(cfg, now);
        GuiRender.endScissor();
    }

    /** Dedicated player-search field at the top of the list panel: filters the tab list live and, for a name
     *  not in the list, drives the "Search Hypixel for X" action row. Border shows only while focused. */
    private void drawPlayerSearchBar(int mouseX, int mouseY) {
        String q = PlayersViewState.playerQuery;
        GuiRender.roundedRect(playerSearchX1, playerSearchY1, playerSearchX2, playerSearchY2, CARD_R, SEARCH_BAR_BG);
        if (playerSearchFocused) {
            GuiRender.roundedRectOutline(playerSearchX1, playerSearchY1, playerSearchX2, playerSearchY2, CARD_R, 0.5f,
                    accent.dropdownOutline());
        }
        float sScale = clampf(playersRowH * 0.04f, 0.95f, 1.4f);
        float ty = vcenter(playerSearchY1, playerSearchY2 - playerSearchY1, sScale);
        float textX = playerSearchX1 + 8;
        float textRight = playerSearchClearX1 - 6;
        if (q.isEmpty()) {
            GuiRender.text("Search players...", textX, ty, sScale, GuiTheme.TEXT_LO);
        } else {
            GuiRender.text(ellipsize(q, sScale, textRight - textX), textX, ty, sScale, GuiTheme.TEXT_HI);
            boolean xHover = GuiRender.inside(mouseX, mouseY, playerSearchClearX1, playerSearchY1, playerSearchClearX2, playerSearchY2);
            drawCross((playerSearchClearX1 + playerSearchClearX2) / 2f, (playerSearchY1 + playerSearchY2) / 2f,
                    (playerSearchClearX2 - playerSearchClearX1) * 0.28f, xHover ? GuiTheme.TEXT_HI : GuiTheme.TEXT_MID);
        }
    }

    /** One master-list row from the animation model: name + colored FKDR + one severity
     *  chip when resolved; a "Fetching…" hint while a click's fetch is in flight; a faint "click to load"
     *  dot when unfetched; or a state hint (nicked/never played/error). Every color is pre-multiplied by
     *  the row's eased alpha for the slide/fade. Never triggers a fetch (cached reads only). */
    private void drawPlayerRow(ClientSettings cfg, PlayersListAnim.Row row,
                               int x1, int x2, float y, float scale, long now) {
        UUID uuid = row.uuid;
        String name = row.name;
        float a = row.alpha;
        BedwarsStats st = StatsCache.getCached(uuid);
        float ty = vcenter((int) y, playersRowH, scale);
        float rightX = x2 - 6;

        if (st != null && st.state == BedwarsStats.State.OK) {
            // Severity chip (gating-filtered per provider), then colored FKDR, then name — right to left.
            EligibilitySnapshot snap = EligibilitySnapshot.current();
            List<UrchinTag> ut = cfg.urchinTags && UrchinTag.badgeAllowed(snap, name, uuid)
                    ? st.urchinTags : java.util.Collections.<UrchinTag>emptyList();
            List<SeraphTag> stg = cfg.seraphTags && SeraphTag.badgeAllowed(snap, name, uuid)
                    ? st.seraphTags : java.util.Collections.<SeraphTag>emptyList();
            PlayersFormat.Chip chip = PlayersFormat.chipFor(ut, now, stg);
            if (chip != null && chip.code != null) {
                float cs = scale * 0.85f;
                float cw = GuiRender.textWidth(chip.code, cs, MED);
                GuiRender.text(chip.code, rightX - cw, vcenter((int) y, playersRowH, cs), cs,
                        applyAlpha(chip.argb, a), MED);
                rightX -= cw + 6;
            } else if (chip != null) {
                float sq = playersRowH * 0.28f;
                GuiRender.roundedRect(rightX - sq, y + (playersRowH - sq) / 2f, rightX,
                        y + (playersRowH + sq) / 2f, 1.5f, applyAlpha(chip.argb, a));
                rightX -= sq + 6;
            }
            // The row's one number follows the global display mode like every other surface; the
            // detail card beneath keeps its Overall hero + per-mode cards, which need no label.
            BedwarsMode rowMode = BedwarsModeDetector.displayMode(cfg);
            String rowLabel = st.labelFor(rowMode, BedwarsModeDetector.isForced(cfg));
            double rowFkdr = st.statsFor(rowMode).fkdr;
            String fkdr = BedwarsStats.fkdrColor(rowFkdr) + (rowLabel == null ? "" : rowLabel + " ") + PlayersFormat.fmt2(rowFkdr);
            float fw = GuiRender.textWidth(fkdr, scale, MED);
            GuiRender.text(fkdr, rightX - fw, ty, scale, applyAlpha(GuiTheme.TEXT_HI, a), MED);
            GuiRender.text(ellipsize(name, scale, (rightX - fw - 8) - (x1 + 6)), x1 + 6, ty, scale,
                    applyAlpha(GuiTheme.TEXT_HI, a), MED);
            return;
        }

        if (st == null) {
            // Not resolved: "Fetching…" while this player's click-fetch is in flight, else a faint dot.
            if (StatsCache.isFetching(uuid)) {
                String f = "Fetching...";
                float fw = GuiRender.textWidth(f, scale, BedwarsQolFont.Weight.REGULAR);
                GuiRender.text(f, rightX - fw, ty, scale, applyAlpha(GuiTheme.TEXT_MID, a),
                        BedwarsQolFont.Weight.REGULAR);
                GuiRender.text(ellipsize(name, scale, (rightX - fw - 8) - (x1 + 6)), x1 + 6, ty, scale,
                        applyAlpha(GuiTheme.TEXT_HI, a), MED);
            } else {
                String dot = "·"; // faint "click to load" affordance
                float dw = GuiRender.textWidth(dot, scale, BedwarsQolFont.Weight.REGULAR);
                GuiRender.text(dot, rightX - dw, ty, scale, applyAlpha(GuiTheme.TEXT_LO, a),
                        BedwarsQolFont.Weight.REGULAR);
                GuiRender.text(ellipsize(name, scale, x2 - 12 - (x1 + 6)), x1 + 6, ty, scale,
                        applyAlpha(GuiTheme.TEXT_HI, a), MED);
            }
            return;
        }

        // Resolved non-OK: name + a state hint.
        String hint = st.state == BedwarsStats.State.NICKED ? "nicked"
                : st.state == BedwarsStats.State.NEVER_PLAYED ? "never played"
                : st.state == BedwarsStats.State.ERROR ? "error" : null;
        if (hint != null) {
            float hw = GuiRender.textWidth(hint, scale, BedwarsQolFont.Weight.REGULAR);
            GuiRender.text(hint, rightX - hw, ty, scale, applyAlpha(GuiTheme.TEXT_LO, a),
                    BedwarsQolFont.Weight.REGULAR);
            GuiRender.text(ellipsize(name, scale, (rightX - hw - 8) - (x1 + 6)), x1 + 6, ty, scale,
                    applyAlpha(GuiTheme.TEXT_MID, a), MED);
        } else {
            GuiRender.text(ellipsize(name, scale, x2 - 12 - (x1 + 6)), x1 + 6, ty, scale,
                    applyAlpha(GuiTheme.TEXT_HI, a), MED);
        }
    }

    /** Right panel: full stats for the selected (or remote-looked-up) player, fetched on demand. Shows the
     *  entire available data ceiling — all six raw counters + FKDR/WLR/KD for every mode — plus a
     *  always-present Urchin section. */
    private void drawPlayerDetail(ClientSettings cfg, long now) {
        // Ease the detail scroll toward its target.
        detailScrollRender += (detailScroll - detailScrollRender) * 0.35f;
        if (Math.abs(detailScroll - detailScrollRender) < 0.5f) detailScrollRender = detailScroll;

        int dpad = clamp(Math.round(pad * 0.8f), 6, 14);
        int dx1 = playersDetailX1 + dpad;
        int dx2 = playersX2 - dpad;
        int top = playersTop + dpad;
        int viewH = (playersBottom - dpad) - top;
        float midX = (playersDetailX1 + playersX2) / 2f;

        UUID uuid = PlayersViewState.selectedUuid;
        String lookup = PlayersViewState.lookupName;
        BedwarsStats st = uuid != null ? StatsCache.getCached(uuid)
                : (lookup != null ? StatsCache.getCachedByName(lookup) : null);
        boolean remote = uuid == null && lookup != null;

        if (uuid == null && lookup == null) {
            GuiRender.textCentered("Select a player", midX, (playersTop + playersBottom) / 2f,
                    valueScale, GuiTheme.TEXT_LO);
            detailMaxScroll = 0;
            return;
        }

        // The exact live-row name (captured at click) gates the safety sections even before stats resolve.
        String liveName = remote ? lookup : PlayersViewState.selectedName;
        String gateName = liveName != null && !liveName.isEmpty() ? liveName
                : (st != null ? PlayersFormat.stripSection(st.displayName) : null);
        int startY = top - Math.round(detailScrollRender);
        int dy = startY;

        if (st == null || st.state != BedwarsStats.State.OK) {
            // Simple header (name + rank) then an honest status line; the hero card is OK-players only.
            String title = st != null && st.displayName != null && !st.displayName.isEmpty()
                    ? PlayersFormat.stripSection(st.displayName)
                    : (liveName != null && !liveName.isEmpty() ? liveName : "Player");
            GuiRender.text(ellipsize(title, valueScale * 1.25f, dx2 - dx1), dx1, dy, valueScale * 1.25f,
                    GuiTheme.TEXT_HI, MED);
            dy += Math.round(BedwarsQolFont.height(valueScale * 1.25f)) + 4;
            if (st != null && st.rankPrefix != null && !st.rankPrefix.isEmpty()) {
                GuiRender.text(st.rankPrefix, dx1, dy, valueScale * 0.85f, GuiTheme.TEXT_MID,
                        BedwarsQolFont.Weight.REGULAR);
                dy += Math.round(BedwarsQolFont.height(valueScale * 0.85f)) + 4;
            }
            boolean fetching = st == null
                    && (remote ? StatsCache.isFetchingName(lookup) : StatsCache.isFetching(uuid));
            String status = st == null
                    ? (fetching ? "Fetching..." : "Not loaded - click again to retry")
                    : st.state == BedwarsStats.State.NICKED ? "Nicked player"
                    : st.state == BedwarsStats.State.NEVER_PLAYED ? "Never played Bedwars"
                    : (remote ? "No player found" : "Failed to load - click again to retry");
            GuiRender.text(status, dx1, dy + 4, valueScale, GuiTheme.TEXT_LO);
            dy += Math.round(BedwarsQolFont.height(valueScale)) + 18;
        } else {
            // Hero "Overall" card, then a card per specific mode.
            dy = drawHeroCard(st, uuid, dx1, dx2, dy);
            dy += 5;
            String[] mLabels = {"Solo", "Doubles", "3s", "4s"};
            BedwarsStats.ModeStats[] mArr = {st.solo, st.doubles, st.threes, st.fours};
            for (int i = 0; i < mLabels.length; i++) {
                dy = drawModeCard(dx1, dx2, dy, mLabels[i], mArr[i]);
                dy += 3;
            }
            dy += 3;
        }

        dy = drawUrchinSection(cfg, gateName, st, uuid, remote, now, dx1, dx2, dy);
        dy += 6;
        dy = drawSeraphSection(cfg, gateName, st, uuid, remote, now, dx1, dx2, dy);

        // Scroll bookkeeping (content height is scroll-independent: dy started at top - detailScrollRender).
        int contentH = dy - startY;
        detailMaxScroll = Math.max(0, contentH - viewH);
        detailScroll = clampf(detailScroll, 0, detailMaxScroll);
        detailScrollRender = clampf(detailScrollRender, 0, detailMaxScroll);
        if (detailMaxScroll > 0) {
            // Display-only scrollbar hugging the detail panel's right inner edge.
            float thumbH = Math.max(12f, viewH * (float) viewH / (float) contentH);
            float thumbY = top + (viewH - thumbH) * clampf(detailScrollRender / detailMaxScroll, 0f, 1f);
            float sx2 = playersX2 - 2.5f, sx1 = sx2 - 2.5f;
            GuiRender.roundedRect(sx1, thumbY, sx2, thumbY + thumbH, 1.25f, 0x55FFFFFF);
        }
    }

    /** The prominent "Overall" hero: player head + one-line colored rank/name, then big colored
     *  FKDR / WLR / KD centered on a band. Compact so the detail need not scroll. Returns the y just
     *  below the card. */
    private int drawHeroCard(BedwarsStats st, UUID uuid, int dx1, int dx2, int dy) {
        float nameScale = valueScale * 1.2f;
        float bigScale = valueScale * 1.15f;
        float labScale = bigScale * 0.5f;
        int padX = 8, padY = 6, gap = 6;
        float nameH = BedwarsQolFont.height(nameScale);
        float bandH = BedwarsQolFont.height(bigScale) + 2 + BedwarsQolFont.height(labScale);
        int bodyH = Math.round(nameH + gap + bandH);
        int headPx = bodyH; // big head — spans the full hero body height
        int cardTop = dy;
        int cardH = padY * 2 + bodyH;
        int cardBot = cardTop + cardH;
        GuiRender.roundedRect(dx1, cardTop, dx2, cardBot, CARD_R, applyAlpha(accent.enabledCardBorder(), 0.16f));

        int ix1 = dx1 + padX, ix2 = dx2 - padX;
        int headY = cardTop + padY;
        String nameOnly = PlayersFormat.stripSection(st.displayName == null ? "" : st.displayName);
        // Big head on the far right (placeholder behind for unresolved skins). Tab-list players resolve from
        // their live skin; a manually searched player resolves from their name (async, cached).
        int headX = ix2 - headPx;
        GuiRender.roundedRect(headX, headY, headX + headPx, headY + headPx, 3f, 0x33000000);
        drawPlayerHead(uuid, nameOnly, headX, headY, headPx);

        // Header column on the left, filling the hero height: rank/name line then the big
        // FKDR / WLR / KD band beneath (each value authentic-colored; §r resets to the name color).
        int lx2 = headX - 10; // header text right boundary (clear of the head)
        String rank = st.rankPrefix != null && !st.rankPrefix.isEmpty() ? st.rankPrefix + " " : "";
        String line1 = rank + "§r" + nameOnly;
        GuiRender.text(ellipsize(line1, nameScale, lx2 - ix1), ix1, headY, nameScale, GuiTheme.TEXT_HI, MED);

        float bandTop = headY + nameH + gap;
        int third = (lx2 - ix1) / 3;
        drawBandStat(ix1, bandTop, bandH, "FKDR",
                BedwarsStats.fkdrColor(st.overall.fkdr) + PlayersFormat.fmt2(st.overall.fkdr), bigScale, labScale);
        drawBandStat(ix1 + third, bandTop, bandH, "WLR",
                BedwarsStats.wlrColor(st.overall.wlr) + PlayersFormat.fmt2(st.overall.wlr), bigScale, labScale);
        drawBandStat(ix1 + 2 * third, bandTop, bandH, "KD",
                BedwarsStats.kdColor(st.overall.kd) + PlayersFormat.fmt2(st.overall.kd), bigScale, labScale);
        return cardBot;
    }

    /** Draw the player's skin head (face + hat overlay) at ({@code x},{@code y}) scaled to {@code size}px.
     *  Uses the live tab-list skin when {@code uuid} is present, else resolves {@code name} remotely
     *  (async, cached). No-op until a skin is available. */
    private void drawPlayerHead(UUID uuid, String name, int x, int y, int size) {
        ResourceLocation skin = null;
        if (uuid != null) {
            NetHandlerPlayClient net = mc.getNetHandler();
            NetworkPlayerInfo info = net == null ? null : net.getPlayerInfo(uuid);
            if (info != null) skin = info.getLocationSkin();
        }
        if (skin == null && name != null && !name.isEmpty()) skin = RemoteSkins.face(name);
        if (skin == null) return;
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(1f, 1f, 1f, 1f);
        mc.getTextureManager().bindTexture(skin);
        drawScaledCustomSizeModalRect(x, y, 8f, 8f, 8, 8, size, size, 64f, 64f);   // face
        drawScaledCustomSizeModalRect(x, y, 40f, 8f, 8, 8, size, size, 64f, 64f);  // hat overlay
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** A big value (its §-codes carry the color) with a small neutral label beneath, the pair vertically
     *  centered in a band of height {@code bandH} at x. This shared band baseline replaces the old ad-hoc
     *  per-label offset so stat rows are consistently centered. */
    private void drawBandStat(float x, float bandTop, float bandH, String label, String value,
                              float valScale, float labScale) {
        float vh = BedwarsQolFont.height(valScale);
        float lh = BedwarsQolFont.height(labScale);
        float stackH = vh + 2 + lh;
        float sy = bandTop + (bandH - stackH) / 2f;
        GuiRender.text(value, x, sy, valScale, GuiTheme.TEXT_HI, MED);
        GuiRender.text(label, x, sy + vh + 2, labScale, GuiTheme.TEXT_LO, BedwarsQolFont.Weight.REGULAR);
    }

    /** One per-mode card: mode label + colored FKDR/WLR/KD, then a sub-line of abbreviated raw counters
     *  (FK/FD/W/L/K/D). A mode with no games shows "no games" (public fields read directly — never the
     *  Overall fallback). Returns the y just below the card. */
    private int drawModeCard(int dx1, int dx2, int dy, String label, BedwarsStats.ModeStats m) {
        boolean played = m != null && m.hasGames();
        float lScale = clampf(valueScale * 0.85f, 0.85f, 1.2f);
        float subScale = clampf(valueScale * 0.68f, 0.72f, 1.05f);
        int padX = 8, padY = 4;
        float lineH = BedwarsQolFont.height(lScale);
        float subH = played ? BedwarsQolFont.height(subScale) + 2 : 0;
        int cardTop = dy;
        int cardH = Math.round(padY + lineH + subH + padY);
        int cardBot = cardTop + cardH;
        GuiRender.roundedRect(dx1, cardTop, dx2, cardBot, 3f, DETAIL_CARD_BG);
        int ix1 = dx1 + padX, ix2 = dx2 - padX;
        float ty = cardTop + padY;
        GuiRender.text(label, ix1, ty, lScale, GuiTheme.TEXT_HI, MED);
        if (!played) {
            String ng = "no games";
            float w = GuiRender.textWidth(ng, lScale, BedwarsQolFont.Weight.REGULAR);
            GuiRender.text(ng, ix2 - w, ty, lScale, GuiTheme.TEXT_LO, BedwarsQolFont.Weight.REGULAR);
            return cardBot;
        }
        String[] ratios = {
                "FKDR " + BedwarsStats.fkdrColor(m.fkdr) + PlayersFormat.fmt2(m.fkdr),
                "WLR " + BedwarsStats.wlrColor(m.wlr) + PlayersFormat.fmt2(m.wlr),
                "KD " + BedwarsStats.kdColor(m.kd) + PlayersFormat.fmt2(m.kd)
        };
        float rx = ix2;
        for (int i = ratios.length - 1; i >= 0; i--) {
            float w = GuiRender.textWidth(ratios[i], lScale, MED);
            GuiRender.text(ratios[i], rx - w, ty, lScale, GuiTheme.TEXT_HI, MED);
            rx -= w + 10;
        }
        String counters = "FK " + PlayersFormat.abbrev(m.finalKills) + "   FD " + PlayersFormat.abbrev(m.finalDeaths)
                + "   W " + PlayersFormat.abbrev(m.wins) + "   L " + PlayersFormat.abbrev(m.losses)
                + "   K " + PlayersFormat.abbrev(m.kills) + "   D " + PlayersFormat.abbrev(m.deaths);
        String c = ellipsize(counters, subScale, ix2 - ix1);
        float cw = GuiRender.textWidth(c, subScale, BedwarsQolFont.Weight.REGULAR);
        GuiRender.text(c, ix2 - cw, ty + lineH + 2, subScale, GuiTheme.TEXT_MID, BedwarsQolFont.Weight.REGULAR);
        return cardBot;
    }

    /** Urchin panel — ALWAYS rendered (heading + state), even while stats are loading or for
     *  nicked/never-played/error base stats. {@code name} is the exact live-row tab name (independent of
     *  {@code st}, which may be null while loading) so {@code badgeAllowed} stays an exact (name, uuid)
     *  gate. Name-keyed remote lookups are explicitly unavailable. */
    private int drawUrchinSection(ClientSettings cfg, String name, BedwarsStats st, UUID uuid, boolean remote,
                                  long now, int dx1, int dx2, int dy) {
        float mScale = valueScale * 0.82f;
        GuiRender.rect(dx1, dy - 2, dx2, dy - 1, DETAIL_DIVIDER);
        dy += 4;
        GuiRender.text("Urchin", dx1, dy, mScale, GuiTheme.TEXT_MID, MED);
        dy += Math.round(BedwarsQolFont.height(mScale)) + 4;
        boolean eligible = !remote && uuid != null && name != null && cfg.urchinTags
                && UrchinTag.badgeAllowed(EligibilitySnapshot.current(), name, uuid);
        if (!eligible) {
            GuiRender.text(remote ? "unavailable for name lookups" : "available in an active game",
                    dx1, dy, mScale, GuiTheme.TEXT_LO, BedwarsQolFont.Weight.REGULAR);
            return dy + Math.round(BedwarsQolFont.height(mScale));
        }
        StatsCache.UrchinResolution res = StatsCache.urchinResolution(uuid);
        if (res == StatsCache.UrchinResolution.PENDING || res == StatsCache.UrchinResolution.ABSENT) {
            GuiRender.text("checking...", dx1, dy, mScale, GuiTheme.TEXT_LO, BedwarsQolFont.Weight.REGULAR);
            return dy + Math.round(BedwarsQolFont.height(mScale));
        }
        if (res == StatsCache.UrchinResolution.RESOLVED_EMPTY) {
            GuiRender.text("no tags", dx1, dy, mScale, GuiTheme.TEXT_LO, BedwarsQolFont.Weight.REGULAR);
            return dy + Math.round(BedwarsQolFont.height(mScale));
        }
        List<UrchinTag> tags = st != null ? st.urchinTags : Collections.<UrchinTag>emptyList();
        for (UrchinTag t : UrchinTag.activeTags(tags, now)) {
            int color = PlayersFormat.sectionColorToRgb(t.color()); // authoritative per-type §-color
            String icon = t.displayIcon();
            String header = (icon != null ? "[" + icon + "] " : "") + PlayersFormat.stripSection(t.displayName());
            GuiRender.text(header, dx1, dy, mScale, color, MED);
            String meta = urchinMeta(t, now);
            if (!meta.isEmpty()) {
                float mw = GuiRender.textWidth(meta, mScale, BedwarsQolFont.Weight.REGULAR);
                GuiRender.text(meta, dx2 - mw, dy, mScale, GuiTheme.TEXT_LO, BedwarsQolFont.Weight.REGULAR);
            }
            dy += Math.round(BedwarsQolFont.height(mScale)) + 2;
            if (t.reason != null && !t.reason.isEmpty()) {
                GuiRender.text(ellipsize("  " + t.reason, mScale, dx2 - dx1), dx1, dy, mScale,
                        GuiTheme.TEXT_MID, BedwarsQolFont.Weight.REGULAR);
                dy += Math.round(BedwarsQolFont.height(mScale)) + 3;
            }
        }
        return dy;
    }

    /** Seraph safety panel — mirrors Urchin's gating. The header carries a right-aligned
     *  threat/encounters readout when resolved and present ({@code -1} omitted); each tag shows its
     *  icon+color and reason. Returns the y just below the section. */
    private int drawSeraphSection(ClientSettings cfg, String name, BedwarsStats st, UUID uuid, boolean remote,
                                  long now, int dx1, int dx2, int dy) {
        float mScale = valueScale * 0.82f;
        GuiRender.rect(dx1, dy - 2, dx2, dy - 1, DETAIL_DIVIDER);
        dy += 4;
        GuiRender.text("Seraph", dx1, dy, mScale, GuiTheme.TEXT_MID, MED);
        boolean eligible = !remote && uuid != null && name != null && cfg.seraphTags
                && SeraphTag.badgeAllowed(EligibilitySnapshot.current(), name, uuid);
        // The threat/encounters readout is Seraph data — show it only behind the same eligibility gate.
        if (eligible && st != null && (st.seraphThreat >= 0 || st.seraphEncounters >= 0)) {
            StringBuilder b = new StringBuilder();
            if (st.seraphThreat >= 0) b.append("threat ").append(st.seraphThreat);
            if (st.seraphEncounters >= 0) {
                if (b.length() > 0) b.append("   ·   ");
                b.append(st.seraphEncounters).append(" seen");
            }
            String meta = b.toString();
            float mw = GuiRender.textWidth(meta, mScale, BedwarsQolFont.Weight.REGULAR);
            GuiRender.text(meta, dx2 - mw, dy, mScale, GuiTheme.TEXT_LO, BedwarsQolFont.Weight.REGULAR);
        }
        dy += Math.round(BedwarsQolFont.height(mScale)) + 4;
        if (!eligible) {
            GuiRender.text(remote ? "unavailable for name lookups" : "available in an active game",
                    dx1, dy, mScale, GuiTheme.TEXT_LO, BedwarsQolFont.Weight.REGULAR);
            return dy + Math.round(BedwarsQolFont.height(mScale));
        }
        StatsCache.UrchinResolution res = StatsCache.seraphResolution(uuid);
        if (res == StatsCache.UrchinResolution.PENDING || res == StatsCache.UrchinResolution.ABSENT) {
            GuiRender.text("checking...", dx1, dy, mScale, GuiTheme.TEXT_LO, BedwarsQolFont.Weight.REGULAR);
            return dy + Math.round(BedwarsQolFont.height(mScale));
        }
        if (res == StatsCache.UrchinResolution.RESOLVED_EMPTY) {
            GuiRender.text("no tags", dx1, dy, mScale, GuiTheme.TEXT_LO, BedwarsQolFont.Weight.REGULAR);
            return dy + Math.round(BedwarsQolFont.height(mScale));
        }
        List<SeraphTag> tags = st != null ? st.seraphTags : Collections.<SeraphTag>emptyList();
        for (SeraphTag t : SeraphTag.activeTags(tags)) {
            int color = PlayersFormat.sectionColorToRgb(t.color());
            String icon = t.displayIcon();
            String header = (icon != null ? "[" + icon + "] " : "") + t.displayName();
            GuiRender.text(header, dx1, dy, mScale, color, MED);
            dy += Math.round(BedwarsQolFont.height(mScale)) + 2;
            if (t.reason != null && !t.reason.isEmpty()) {
                GuiRender.text(ellipsize("  " + t.reason, mScale, dx2 - dx1), dx1, dy, mScale,
                        GuiTheme.TEXT_MID, BedwarsQolFont.Weight.REGULAR);
                dy += Math.round(BedwarsQolFont.height(mScale)) + 3;
            }
        }
        return dy;
    }

    /** "added 3d  expires in 5d" metadata for a tag, from its addedOnMs / expiresAtMs. */
    private static String urchinMeta(UrchinTag t, long now) {
        StringBuilder b = new StringBuilder();
        if (t.addedOnMs > 0) b.append("added ").append(durString(now - t.addedOnMs)).append(" ago");
        if (t.expiresAtMs != null) {
            if (b.length() > 0) b.append("   ");
            long left = t.expiresAtMs - now;
            b.append(left > 0 ? "expires in " + durString(left) : "expired");
        }
        return b.toString();
    }

    private static String durString(long ms) {
        long s = Math.abs(ms) / 1000L;
        long d = s / 86400L; if (d > 0) return d + "d";
        long h = s / 3600L;  if (h > 0) return h + "h";
        long m = s / 60L;    if (m > 0) return m + "m";
        return s + "s";
    }

    /** Live tab-list players in the net handler's own enumeration order (no sort — {@link PlayersListAnim}
     *  keeps a stable per-player insertion slot, so re-buckets never reorder rows). Never fetches. */
    private List<NetworkPlayerInfo> tabPlayers() {
        List<NetworkPlayerInfo> out = new ArrayList<NetworkPlayerInfo>();
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return out;
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info != null && info.getGameProfile() != null) out.add(info);
        }
        return out;
    }

    private static String profileName(NetworkPlayerInfo info) {
        if (info == null) return null;
        GameProfile p = info.getGameProfile();
        return p == null ? null : p.getName();
    }

    private void drawPlayersScrollbar(int viewTop, int viewBottom, int blockH) {
        float track = viewBottom - viewTop;
        if (blockH <= track) return;
        float thumbH = Math.max(18f, track * (track / blockH));
        float span = track - thumbH;
        float thumbY = viewTop + span * clampf(playersScrollRender / playersMaxScroll, 0f, 1f);
        float sx2 = playersListX2 - 2, sx1 = sx2 - 2.5f;
        GuiRender.roundedRect(sx1, thumbY, sx2, thumbY + thumbH, 1.2f, 0x55FFFFFF);
    }

    /** [x1,x2,top,bottom,thumbH,span] for the player-list scrollbar, or null when it doesn't scroll. */
    private float[] playersScrollbarGeom() {
        if (playersMaxScroll <= 0) return null;
        int viewTop = playersListTop, viewBottom = playersListBottom; // set each frame in drawPlayers
        float track = viewBottom - viewTop;
        int blockH = playersMaxScroll + (int) track;
        float thumbH = Math.max(18f, track * (track / blockH));
        float sx2 = playersListX2 - 2, sx1 = sx2 - 2.5f;
        return new float[]{sx1, sx2, viewTop, viewBottom, thumbH, track - thumbH};
    }

    private void playClick() {
        mc.getSoundHandler().playSound(
                PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    private static ClientSettings settings() {
        if (BedwarsQol.config == null) BedwarsQol.config = new ClientSettings();
        BedwarsQol.config.sanitize();
        return BedwarsQol.config;
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        return Math.min(v, max);
    }

    private static float clampf(float v, float min, float max) {
        if (v < min) return min;
        return Math.min(v, max);
    }

    /** Pure tab-bar fit solver (no Minecraft, so {@code TabBarFitTest} can pin the guarantee). Given the tab
     *  label widths measured at scale 1.0 ({@code tabW1}) and the horizontal {@code available} span the
     *  strip may occupy — n tab boxes (each the scaled text plus {@code 2*padX}), joined by n-1 inter-tab
     *  gaps, plus one trailing gap — it shrinks scale + padding together, then the gap, until the strip
     *  provably fits, returning {@code {scale, padX, gap}}. The floors are hard safety limits reached only
     *  if the caller's panel min-width is itself too small; otherwise the result is GUARANTEED to satisfy
     *  {@code scale*sum(tabW1) + 2*n*padX + n*gap <= available}, i.e. {@code tabsRight + gap <= available}. */
    static float[] fitTabBar(float[] tabW1, float available,
                             float startScale, float startPadX, float startGap,
                             float minScale, float minPadX, float minGap) {
        int n = tabW1.length;
        float sumW1 = 0f;
        for (int i = 0; i < n; i++) sumW1 += tabW1[i];
        float scale = startScale, padX = startPadX, gap = startGap;
        for (int guard = 0; guard < 256; guard++) {
            float need = scale * sumW1 + 2 * n * padX + n * gap;
            if (need <= available) break;
            if (scale > minScale || padX > minPadX) {
                scale = Math.max(minScale, scale * 0.95f);
                padX = Math.max(minPadX, padX * 0.96f);
            } else if (gap > minGap) {
                gap = Math.max(minGap, gap - 1f);
            } else {
                break; // pinned at the floors (the panel min-width is sized so the floors always fit)
            }
        }
        return new float[]{scale, padX, gap};
    }

    // ---- gradient fill (OkLab-derived left→right sheen on module cards) ----
    private static int gradHi(int base) { return okShift(base,  0.105f, 0.90f,  5f); }
    private static int gradLo(int base) { return okShift(base, -0.090f, 1.14f, -5f); }

    /** Returns {@code argb} shifted in OkLCH by (+{@code dL} lightness, chroma ×{@code cMul},
     *  +{@code dHueDeg} hue), then converted back to sRGB. The alpha byte is preserved. */
    private static int okShift(int argb, float dL, float cMul, float dHueDeg) {
        int alpha = argb >>> 24 & 0xFF;
        float r = srgbToLinear((argb >> 16 & 0xFF) / 255f);
        float g = srgbToLinear((argb >> 8 & 0xFF) / 255f);
        float b = srgbToLinear((argb & 0xFF) / 255f);
        float lm = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b;
        float mm = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b;
        float sm = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b;
        float lc = (float) Math.cbrt(lm), mc = (float) Math.cbrt(mm), sc = (float) Math.cbrt(sm);
        float L = 0.2104542553f * lc + 0.7936177850f * mc - 0.0040720468f * sc;
        float A = 1.9779984951f * lc - 2.4285922050f * mc + 0.4505937099f * sc;
        float Bb = 0.0259040371f * lc + 0.7827717662f * mc - 0.8086757660f * sc;
        float C = (float) Math.hypot(A, Bb);
        float h = (float) Math.atan2(Bb, A) + (float) Math.toRadians(dHueDeg);
        L = clampf(L + dL, 0f, 1f);
        C = Math.max(0f, C * cMul);
        A = C * (float) Math.cos(h);
        Bb = C * (float) Math.sin(h);
        float l_ = L + 0.3963377774f * A + 0.2158037573f * Bb;
        float m_ = L - 0.1055613458f * A - 0.0638541728f * Bb;
        float s_ = L - 0.0894841775f * A - 1.2914855480f * Bb;
        l_ = l_ * l_ * l_; m_ = m_ * m_ * m_; s_ = s_ * s_ * s_;
        float or =  4.0767416621f * l_ - 3.3077115913f * m_ + 0.2309699292f * s_;
        float og = -1.2684380046f * l_ + 2.6097574011f * m_ - 0.3413193965f * s_;
        float ob = -0.0041960863f * l_ - 0.7034186147f * m_ + 1.7076147010f * s_;
        int R = clampByte(Math.round(linearToSrgb(or) * 255f));
        int G = clampByte(Math.round(linearToSrgb(og) * 255f));
        int Bc = clampByte(Math.round(linearToSrgb(ob) * 255f));
        return (alpha << 24) | (R << 16) | (G << 8) | Bc;
    }

    private static float srgbToLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    private static float linearToSrgb(float c) {
        if (c <= 0f) return 0f;
        if (c >= 1f) return 1f;
        return c <= 0.0031308f ? 12.92f * c : 1.055f * (float) Math.pow(c, 1f / 2.4f) - 0.055f;
    }

    private static int clampByte(int v) { return v < 0 ? 0 : Math.min(255, v); }
}
