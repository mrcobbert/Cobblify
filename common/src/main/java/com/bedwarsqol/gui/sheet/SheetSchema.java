package com.bedwarsqol.gui.sheet;

import com.bedwarsqol.gui.render.GuiTheme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the settings sheet lists: categories, modules, their rows and the Settings page sections, in the
 * approved prototype's order and copy ({@code CATS} in its {@code app.js}). A tree shows only the modules and rows
 * its {@link SheetValues} has, so Lunar's shorter list comes from the same table.
 */
public final class SheetSchema {

    private SheetSchema() {
    }

    public enum Kind { TOGGLE, SEG, SWATCH, SLIDER, KEY, ACTION }

    public static final class Row {
        public final String id;
        public final Kind kind;
        public final String name;
        public final String desc;
        public final String kw;
        String[] opts = new String[0];
        int[] colors = new int[0];
        double min, max, step;
        int decimals;
        String unit = "";
        String dep, reason, label;
        boolean confirm;
        Module module;
        Section section;
        Category category;

        Row(String id, Kind kind, String name, String desc, String kw) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.desc = desc;
            this.kw = kw;
        }

        public String[] options() { return opts; }
        public int[] colors() { return colors; }
        public double min() { return min; }
        public double max() { return max; }
        public double step() { return step; }
        public int decimals() { return decimals; }
        public String unit() { return unit; }
        /** Row id this row depends on (it is disabled while that toggle is off), or null. */
        public String dep() { return dep; }
        public String reason() { return reason; }
        /** Button text of an action row. */
        public String label() { return label; }
        /** An action that asks for a second press. */
        public boolean confirm() { return confirm; }
        public Module module() { return module; }
        public Section section() { return section; }
        public Category category() { return category; }
    }

    public static final class Module {
        public final String id, name, desc, kw;
        public final List<Row> rows;
        Category category;

        Module(String id, String name, String desc, String kw, Row... rows) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.kw = kw;
            List<Row> list = new ArrayList<Row>();
            for (Row r : rows) {
                r.module = this;
                list.add(r);
            }
            this.rows = Collections.unmodifiableList(list);
        }

        public Category category() { return category; }
    }

    public static final class Section {
        /** Shown as a header row; null for a section without one. */
        public final String name;
        public final List<Row> rows;
        Category category;

        Section(String name, Row... rows) {
            this.name = name;
            List<Row> list = new ArrayList<Row>();
            for (Row r : rows) {
                r.section = this;
                list.add(r);
            }
            this.rows = Collections.unmodifiableList(list);
        }

        public Category category() { return category; }
    }

    public static final class Category {
        public final String id, name;
        public final List<Module> modules;
        public final List<Section> sections;

        Category(String id, String name, Module[] modules, Section[] sections) {
            this.id = id;
            this.name = name;
            List<Module> ms = new ArrayList<Module>();
            for (Module m : modules) {
                m.category = this;
                for (Row r : m.rows) r.category = this;
                ms.add(m);
            }
            List<Section> ss = new ArrayList<Section>();
            for (Section s : sections) {
                s.category = this;
                for (Row r : s.rows) r.category = this;
                ss.add(s);
            }
            this.modules = Collections.unmodifiableList(ms);
            this.sections = Collections.unmodifiableList(ss);
        }

        public boolean isSettings() {
            return !sections.isEmpty();
        }
    }

    // ------------------------------------------------------------------ builders

    private static Row toggle(String id, String name, String desc) {
        return new Row(id, Kind.TOGGLE, name, desc, null);
    }

    private static Row toggle(String id, String name, String desc, String kw) {
        return new Row(id, Kind.TOGGLE, name, desc, kw);
    }

    private static Row seg(String id, String name, String desc, String kw, String... opts) {
        Row r = new Row(id, Kind.SEG, name, desc, kw);
        r.opts = opts;
        return r;
    }

    private static Row swatch(String id, String name, String desc, String kw, String[] names, int[] colors) {
        Row r = new Row(id, Kind.SWATCH, name, desc, kw);
        r.opts = names;
        r.colors = colors;
        return r;
    }

    private static Row slider(String id, String name, String desc, double min, double max, double step, int decimals, String unit) {
        Row r = new Row(id, Kind.SLIDER, name, desc, null);
        r.min = min;
        r.max = max;
        r.step = step;
        r.decimals = decimals;
        r.unit = unit;
        return r;
    }

    private static Row dependsOn(Row r, String dep, String reason) {
        r.dep = dep;
        r.reason = reason;
        return r;
    }

    private static Module module(String id, String name, String desc, String kw, Row... rows) {
        return new Module(id, name, desc, kw, rows);
    }

    private static Category category(String id, String name, Module... modules) {
        return new Category(id, name, modules, new Section[0]);
    }

    private static Row inGame(String id, String desc) {
        return toggle(id, "In Game Only", desc);
    }

    private static String[] accentNames() {
        GuiTheme.Accent[] all = GuiTheme.Accent.values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) out[i] = all[i].label();
        return out;
    }

    private static int[] accentColors() {
        GuiTheme.Accent[] all = GuiTheme.Accent.values();
        int[] out = new int[all.length];
        for (int i = 0; i < all.length; i++) out[i] = all[i].base() & 0xFFFFFF;
        return out;
    }

    public static final List<Category> CATS;
    private static final Map<String, Row> ROWS = new LinkedHashMap<String, Row>();
    private static final Map<String, Module> MODULES = new LinkedHashMap<String, Module>();

    static {
        Row sessionKey = dependsOn(new Row("sessionKey", Kind.KEY, "Key", "The key you hold", null),
                "sessionHold", "Turn on Hold Key to Show");
        Row sessionReset = new Row("sessionReset", Kind.ACTION, "Reset Session",
                "Set session kills, finals, beds and wins to zero", null);
        sessionReset.label = "Reset";
        sessionReset.confirm = true;

        List<Category> cats = new ArrayList<Category>();
        cats.add(category("hud", "HUD",
                module("potion", "Potion", "Active effects and timers", "effects speed strength",
                        inGame("potionInGame", "Hide it in lobbies")),
                module("armor", "Armor", "Equipped armor type", "durability",
                        inGame("armorInGame", "Hide it in lobbies")),
                module("inventory", "Inventory", "Stored items at a glance", "items blocks",
                        inGame("inventoryInGame", "Hide it in lobbies")),
                module("genTimers", "Gen Timers", "Diamond and emerald timers", "generator diamond emerald",
                        inGame("genTimersInGame", "Hide them in lobbies")),
                module("keystrokes", "Keystrokes", "WASD, clicks with CPS, spacebar", "cps keys wasd clicks",
                        inGame("keystrokesInGame", "Hide it in lobbies")),
                module("mapInfo", "Map Info", "Map name, build limit and blocks left", "height limit build",
                        inGame("mapInfoInGame", "Hide it in lobbies")),
                module("session", "Session Stats", "Kills, finals, beds and W/L", "kills finals beds wins stats",
                        toggle("sessionHold", "Hold Key to Show", "Show the stats only while a key is held"),
                        sessionKey, sessionReset)));
        cats.add(category("combat", "Combat",
                module("handPos", "Hand Position", "Move and resize held item", "item sword viewmodel",
                        slider("handX", "X", "Left and right", -1, 1, 0.01, 2, ""),
                        slider("handY", "Y", "Up and down", -1, 1, 0.01, 2, ""),
                        slider("handZ", "Z", "Toward and away from you", -1, 1, 0.01, 2, ""),
                        slider("handScale", "Scale", "Size of the held item", 0.5, 2, 0.01, 2, "")),
                module("tnt", "TNT Countdown", "Fuse timer for nearby TNT", "fuse explode",
                        seg("tntRadius", "Radius", "How far away TNT is tracked, in blocks", null, "5", "10", "15", "20", "30")),
                module("noEsc", "Disable Esc Menu", "Stop Esc pausing the game", "pause escape")));
        cats.add(category("visuals", "Visuals",
                module("blockOverlay", "Block Overlay", "Highlight targeted block", "outline selection highlight",
                        toggle("boSee", "See-Through", "Show it through other blocks"),
                        seg("boStyle", "Style", "How the block is marked", null, "Outline", "Fill", "Both"),
                        seg("boOpacity", "Opacity", "How strong the fill is", null, "Low", "Medium", "High"),
                        toggle("boWhite", "White", "White instead of your accent", "colour color")),
                module("tabHF", "Hide Tab Header/Footer", "Hide tab header and footer", "tab list")));
        cats.add(category("chat", "Chat",
                module("chatStack", "Stack Spam Messages", "Collapse repeats into one (xN) line", "spam repeat compact",
                        toggle("stackBlanks", "Ignore Blank Lines", "Never stack empty lines"),
                        toggle("stackTime", "Time-Based Stacking", "Only stack repeats that arrive close together"),
                        dependsOn(slider("stackWindow", "Timeframe", "How close together, in seconds", 1, 30, 1, 0, "s"),
                                "stackTime", "Turn on Time-Based Stacking")),
                module("chatNotify", "Chat Notifications", "Sound alerts from chat lines", "sound alert ping",
                        toggle("notifyMention", "Mention Sound", "Play a sound when someone says your name", "sound ping"),
                        toggle("notifyInc", "Inc Alert", "Play a sound when a teammate calls INC", "sound incoming")),
                module("chatUnlimited", "Unlimited Chat", "Raise message history to 32k lines", "history lines scroll"),
                module("chatKeep", "Keep Chat History", "Keep chat across server switches", "history"),
                module("chatCopy", "Copy Chat", "Right-click a message to copy it", "clipboard"),
                module("chatLong", "Longer Messages", "256-character chat on Hypixel", "length limit"),
                // Lunar has no Chat Notifications module, so there Inc Alert is a module of its own.
                module("incAlert", "Inc Alert", "Sound when a teammate says inc", "sound incoming"),
                module("incKey", "Send INC Keybind", "Sends /pc INC; bind in Controls", "incoming party")));
        cats.add(category("hypixel", "Hypixel",
                module("stats", "Hypixel Stats", "BedWars stats on screen", "fkdr stars level wlr",
                        toggle("statsNametag", "Show Nametag", "Above players' heads"),
                        toggle("statsTab", "Show Tab", "Next to names in the tab list"),
                        toggle("statsHover", "Chat Hover", "When you hover a name in chat"),
                        toggle("statsChat", "Chat Stats", "Posted in chat when players join"),
                        toggle("statsRank", "Show Rank", "Include each player's rank"),
                        toggle("statsReport", "Party Report", "Your party's stats when you queue")),
                module("nickUtils", "Nick Utils", "Detect and denick nicked players", "nick denick",
                        toggle("nickNotify", "Nick Notify", "Tell you when a nicked player is found"),
                        toggle("autoDenick", "Auto Denick", "Look up nicked players' real names")),
                module("tagUtils", "Tag Utils", "Urchin and Seraph cheater tags", "cheater hacker blacklist",
                        toggle("tagUrchin", "Urchin", "Tags from the Urchin list"),
                        toggle("tagSeraph", "Seraph", "Tags from the Seraph list"),
                        toggle("tagTab", "Tab Badge", "Next to names in the tab list"),
                        toggle("tagNametag", "Nametag Badge", "Above players' heads"),
                        toggle("tagChat", "Chat Alert", "A chat line when a tagged player joins"),
                        toggle("tagSound", "Alert Sound", "Play a sound with the chat alert", "sound")),
                module("autoGg", "Auto GG", "Say gg when a game ends", "gg"),
                module("partyJoin", "Party Join Alert", "Warns when a party queues", "queue")));
        // The Settings page is one headerless section: the HUD sizes, then this menu's size and accent.
        cats.add(new Category("settings", "Settings", new Module[0], new Section[]{
                new Section(null,
                        seg("hudSize", "HUD Size", "Size of every HUD module", "scale", "Small", "Medium", "Large"),
                        seg("sbSize", "Scoreboard Size", "Size of the sidebar scoreboard", "sidebar", "Small", "Medium", "Large"),
                        seg("tabSize", "Tab List Size", "Size of the player list", "players", "Small", "Medium", "Large"),
                        seg("guiSize", "GUI Size", "Size of this menu", "menu scale", "Small", "Medium", "Large"),
                        seg("font", "Font", "Text in this menu and the HUD", "minecraft typeface text", "Modern", "Minecraft"),
                        swatch("accent", "Accent", "Colour for on, selected and highlights", "colour color theme",
                                accentNames(), accentColors()))}));
        CATS = Collections.unmodifiableList(cats);
        for (Category c : CATS) {
            for (Module m : c.modules) {
                MODULES.put(m.id, m);
                for (Row r : m.rows) ROWS.put(r.id, r);
            }
            for (Section s : c.sections) for (Row r : s.rows) ROWS.put(r.id, r);
        }
    }

    public static Row row(String id) {
        return ROWS.get(id);
    }

    public static Module module(String id) {
        return MODULES.get(id);
    }

    public static Category category(String id) {
        for (Category c : CATS) if (c.id.equals(id)) return c;
        return null;
    }
}
