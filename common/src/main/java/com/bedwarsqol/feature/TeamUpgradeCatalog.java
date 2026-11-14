package com.bedwarsqol.feature;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Hypixel Bed Wars team upgrades and traps as data (research-hypixel.md §1-§3): the names Hypixel
 * prints in chat and the shop window, per-tier diamond costs for the 8-team and 4-team brackets, shop
 * order, trap costs, and the mode rules the HUD prices and infers by.
 *
 * <p>Platform-neutral and pure. Names the catalogue does not know are never resolved here; callers
 * keep them by their own text (an unknown upgrade from the shop window) or ignore them (chat).
 */
public final class TeamUpgradeCatalog {

    private TeamUpgradeCatalog() {}

    /** Price bracket, from the number of teams. */
    public enum Bracket { EIGHT_TEAM, FOUR_TEAM, UNKNOWN }

    public static final String SHARPENED_SWORDS = "Sharpened Swords";
    public static final String REINFORCED_ARMOR = "Reinforced Armor";
    public static final String MANIAC_MINER = "Maniac Miner";
    public static final String FORGE = "Forge";
    public static final String HEAL_POOL = "Heal Pool";
    public static final String CUSHIONED_BOOTS = "Cushioned Boots";
    public static final String DRAGON_BUFF = "Dragon Buff";

    public static final String BLINDNESS_TRAP = "Blindness Trap";
    public static final String COUNTER_OFFENSIVE_TRAP = "Counter-Offensive Trap";
    public static final String REVEAL_TRAP = "Reveal Trap";
    public static final String MINER_FATIGUE_TRAP = "Miner Fatigue Trap";

    /** The trap queue holds three. */
    public static final int TRAP_QUEUE_MAX = 3;

    private static final String SECTION = "§";

    /** One team upgrade. Tier numbers are 1-based. */
    public static final class Upgrade {
        /** Stable key and display name ("Forge" for the forge). */
        public final String key;
        /** Tiers Hypixel normally sells. Sharpened Swords is 1; its rotating tier II is learned from the shop. */
        public final int baseTiers;
        /** True when each tier has its own name that replaces the upgrade name (the forge). */
        public final boolean namedTiers;
        /** Retired upgrade: accepted when seen, never listed when not owned. */
        public final boolean legacy;
        private final String[] tierNames;
        private final int[] eight;
        private final int[] four;

        Upgrade(String key, int baseTiers, boolean namedTiers, boolean legacy,
                String[] tierNames, int[] eight, int[] four) {
            this.key = key;
            this.baseTiers = baseTiers;
            this.namedTiers = namedTiers;
            this.legacy = legacy;
            this.tierNames = tierNames;
            this.eight = eight;
            this.four = four;
        }

        /** Every tier the catalogue has a name for, including a rotating one. */
        public int tierCount() {
            return tierNames.length;
        }

        /** The chat/shop name of {@code tier}, or null when the catalogue has none. */
        public String tierName(int tier) {
            return tier >= 1 && tier <= tierNames.length ? tierNames[tier - 1] : null;
        }

        /** Diamond cost of {@code tier} in {@code bracket}, or -1 when unknown. */
        public int cost(int tier, Bracket bracket) {
            int[] table = bracket == Bracket.EIGHT_TEAM ? eight : bracket == Bracket.FOUR_TEAM ? four : null;
            if (table == null || tier < 1 || tier > table.length) return -1;
            return table[tier - 1];
        }
    }

    /** An upgrade with one of its tiers, the result of reading an item name. */
    public static final class Tiered {
        public final Upgrade upgrade;
        public final int tier;

        Tiered(Upgrade upgrade, int tier) {
            this.upgrade = upgrade;
            this.tier = tier;
        }
    }

    /** One trap: its current name and the names it had before 2025-01-29. */
    public static final class Trap {
        public final String name;
        private final String[] legacyNames;

        Trap(String name, String... legacyNames) {
            this.name = name;
            this.legacyNames = legacyNames;
        }

        boolean matches(String text) {
            if (name.equals(text)) return true;
            for (String l : legacyNames) if (l.equals(text)) return true;
            return false;
        }
    }

    private static final List<Upgrade> UPGRADES = Collections.unmodifiableList(Arrays.asList(
            new Upgrade(SHARPENED_SWORDS, 1, false, false,
                    new String[]{"Sharpened Swords", "Sharpened Swords II"},
                    new int[]{4, 16}, new int[]{8, 32}),
            new Upgrade(REINFORCED_ARMOR, 4, false, false,
                    new String[]{"Reinforced Armor I", "Reinforced Armor II", "Reinforced Armor III", "Reinforced Armor IV"},
                    new int[]{2, 4, 8, 16}, new int[]{5, 10, 20, 30}),
            new Upgrade(MANIAC_MINER, 2, false, false,
                    new String[]{"Maniac Miner I", "Maniac Miner II"},
                    new int[]{2, 4}, new int[]{4, 6}),
            new Upgrade(FORGE, 4, true, false,
                    new String[]{"Iron Forge", "Golden Forge", "Emerald Forge", "Molten Forge"},
                    new int[]{2, 4, 6, 8}, new int[]{4, 8, 12, 16}),
            new Upgrade(HEAL_POOL, 1, false, false,
                    new String[]{"Heal Pool"}, new int[]{1}, new int[]{3}),
            new Upgrade(CUSHIONED_BOOTS, 2, false, false,
                    new String[]{"Cushioned Boots I", "Cushioned Boots II"},
                    new int[]{1, 2}, new int[]{2, 4}),
            new Upgrade(DRAGON_BUFF, 1, false, true,
                    new String[]{"Dragon Buff"}, new int[]{5}, new int[]{5})));

    private static final List<Trap> TRAPS = Collections.unmodifiableList(Arrays.asList(
            new Trap(BLINDNESS_TRAP, "It's a trap!"),
            new Trap(COUNTER_OFFENSIVE_TRAP),
            new Trap(REVEAL_TRAP, "Alarm Trap"),
            new Trap(MINER_FATIGUE_TRAP)));

    private static final List<String> STANDARD_MODES = Collections.unmodifiableList(Arrays.asList(
            "BEDWARS_EIGHT_ONE", "BEDWARS_EIGHT_TWO", "BEDWARS_FOUR_THREE", "BEDWARS_FOUR_FOUR", "BEDWARS_TWO_FOUR"));

    /** Upgrades in shop order, the retired Dragon Buff last. */
    public static List<Upgrade> upgrades() {
        return UPGRADES;
    }

    /** Traps in shop order. */
    public static List<Trap> traps() {
        return TRAPS;
    }

    /** The upgrade with this key, or null. */
    public static Upgrade upgrade(String key) {
        for (Upgrade u : UPGRADES) if (u.key.equals(key)) return u;
        return null;
    }

    /** Shop-order index of a known key, or -1. */
    public static int order(String key) {
        for (int i = 0; i < UPGRADES.size(); i++) if (UPGRADES.get(i).key.equals(key)) return i;
        return -1;
    }

    /**
     * The upgrade and tier an exact item name (as printed in a purchase line) stands for, or null.
     * {@code Sharpened Swords I} is also read as tier 1.
     */
    public static Tiered upgradeTier(String item) {
        if (item == null) return null;
        for (Upgrade u : UPGRADES) {
            for (int t = 1; t <= u.tierNames.length; t++) {
                if (u.tierNames[t - 1].equals(item)) return new Tiered(u, t);
            }
        }
        if ((SHARPENED_SWORDS + " I").equals(item)) return new Tiered(upgrade(SHARPENED_SWORDS), 1);
        return null;
    }

    /**
     * The upgrade a shop-window item name belongs to, whatever tier the name shows: a forge tier
     * name, or an upgrade key optionally followed by a roman numeral. Null when unknown.
     */
    public static Upgrade upgradeForShopName(String name) {
        if (name == null) return null;
        String n = name.trim();
        for (Upgrade u : UPGRADES) {
            if (u.namedTiers) {
                for (String t : u.tierNames) if (t.equals(n)) return u;
            } else if (u.key.equals(n) || u.key.equals(withoutRoman(n))) {
                return u;
            }
        }
        return null;
    }

    /** {@code name} without a trailing " I".." V", or {@code name} itself. */
    public static String withoutRoman(String name) {
        if (name == null) return null;
        int sp = name.lastIndexOf(' ');
        if (sp > 0 && romanValue(name.substring(sp + 1)) > 0) return name.substring(0, sp);
        return name;
    }

    /** The current name of a trap, from its current or legacy name; null when not a known trap. */
    public static String trapName(String text) {
        if (text == null) return null;
        for (Trap t : TRAPS) if (t.matches(text)) return t.name;
        return null;
    }

    /** A trap's row label: its name without the trailing " Trap". */
    public static String trapLabel(String trapName) {
        if (trapName == null) return "";
        return trapName.endsWith(" Trap") ? trapName.substring(0, trapName.length() - 5) : trapName;
    }

    /** Diamonds for the next trap with {@code queued} traps already queued: 1, 2, 4, or -1 when full. */
    public static int trapCost(int queued) {
        if (queued <= 0) return 1;
        if (queued == 1) return 2;
        if (queued == 2) return 4;
        return -1;
    }

    /**
     * Price bracket for a Mod API location {@code mode}: {@code BEDWARS_EIGHT_*} is 8-team;
     * {@code BEDWARS_FOUR_*} and {@code BEDWARS_TWO_FOUR*} (4v4) are 4-team; anything else unknown.
     */
    public static Bracket bracket(String mode) {
        if (mode == null) return Bracket.UNKNOWN;
        if (mode.startsWith("BEDWARS_EIGHT_")) return Bracket.EIGHT_TEAM;
        if (mode.startsWith("BEDWARS_FOUR_") || mode.startsWith("BEDWARS_TWO_FOUR")) return Bracket.FOUR_TEAM;
        return Bracket.UNKNOWN;
    }

    /** Solo, Doubles, 3s, 4s and 4v4: the modes gear inference trusts. */
    public static boolean isStandardMode(String mode) {
        return mode != null && STANDARD_MODES.contains(mode);
    }

    /** Roman numeral for 1..5, else the number in digits. */
    public static String roman(int n) {
        switch (n) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            default: return String.valueOf(n);
        }
    }

    /** Value of a roman numeral I..V, else 0. */
    public static int romanValue(String s) {
        if ("I".equals(s)) return 1;
        if ("II".equals(s)) return 2;
        if ("III".equals(s)) return 3;
        if ("IV".equals(s)) return 4;
        if ("V".equals(s)) return 5;
        return 0;
    }

    /** {@code s} without § colour/format codes (a lone trailing § is dropped). */
    public static String strip(String s) {
        if (s == null) return null;
        if (s.indexOf(SECTION.charAt(0)) < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == SECTION.charAt(0)) {
                i++;
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }

    /**
     * The colour code letter (lower case {@code 0-9a-f}) in effect at the first visible character of
     * {@code formatted}, or 0 when none is set (or the text is empty). §r resets the colour.
     */
    public static char leadingColour(String formatted) {
        if (formatted == null) return 0;
        char colour = 0;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == SECTION.charAt(0)) {
                if (i + 1 < formatted.length()) {
                    char code = Character.toLowerCase(formatted.charAt(i + 1));
                    if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) colour = code;
                    else if (code == 'r') colour = 0;
                }
                i++;
                continue;
            }
            if (!Character.isWhitespace(c)) return colour;
        }
        return 0;
    }
}
