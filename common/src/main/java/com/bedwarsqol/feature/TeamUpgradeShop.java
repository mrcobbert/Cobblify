package com.bedwarsqol.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code Upgrades & Traps} window (research-hypixel.md §4) into the team's full state: owned
 * tiers, exact costs, and the trap queue. One look rebuilds everything, including purchases made
 * before this client joined.
 *
 * <p>Parsed by display name and lore, never by slot index (the Invisible Shop challenge shuffles and
 * hides slots, rotations add tiers). A window with no recognisable upgrade item is "no snapshot" and
 * changes nothing.
 */
public final class TeamUpgradeShop {

    private TeamUpgradeShop() {}

    public static final String TITLE = "Upgrades & Traps";

    private static final Pattern TRAP_SLOT = Pattern.compile("^Trap #(\\d+): (.+)$");
    private static final Pattern TIER_LINE = Pattern.compile("^Tier (\\d+): .*?,\\s*(\\d+) Diamonds?$");
    private static final Pattern COST_LINE = Pattern.compile("^Cost: (\\d+) Diamonds?$");

    /** One window slot as the client shows it. Strings keep their § codes. */
    public static final class Slot {
        public final String name;
        public final List<String> lore;
        public final int stackSize;

        public Slot(String name, List<String> lore, int stackSize) {
            this.name = name;
            this.lore = lore == null ? Collections.<String>emptyList() : lore;
            this.stackSize = stackSize;
        }
    }

    /** What one look at the window says. */
    public static final class Snapshot {
        /** Upgrade key (or an unknown entry's own name) to owned tier, 0 when not bought. */
        public final Map<String, Integer> tiers;
        /** Upgrade key to cost per tier (index = tier - 1, -1 when a tier line had no cost). */
        public final Map<String, int[]> costs;
        /** Queued traps in firing order. */
        public final List<String> traps;

        Snapshot(Map<String, Integer> tiers, Map<String, int[]> costs, List<String> traps) {
            this.tiers = Collections.unmodifiableMap(tiers);
            this.costs = Collections.unmodifiableMap(costs);
            this.traps = Collections.unmodifiableList(traps);
        }
    }

    /** The snapshot in a window, or null when it is not the upgrades window or holds no known upgrade. */
    public static Snapshot parse(String title, List<Slot> slots) {
        if (title == null || slots == null) return null;
        if (!TITLE.equals(TeamUpgradeCatalog.strip(title).trim())) return null;

        Map<String, Integer> tiers = new LinkedHashMap<String, Integer>();
        Map<String, int[]> costs = new LinkedHashMap<String, int[]>();
        TreeMap<Integer, String> queue = new TreeMap<Integer, String>();
        boolean known = false;

        for (Slot slot : slots) {
            if (slot == null || slot.name == null) continue;
            String name = TeamUpgradeCatalog.strip(slot.name).trim();
            if (name.isEmpty()) continue;

            Matcher q = TRAP_SLOT.matcher(name);
            if (q.matches()) {
                String trap = q.group(2).trim();
                if (!"No Trap!".equals(trap)) {
                    String canonical = TeamUpgradeCatalog.trapName(trap);
                    try {
                        queue.put(Integer.valueOf(q.group(1)), canonical != null ? canonical : trap);
                    } catch (NumberFormatException ignored) {
                        // a slot number past int range is no queue slot
                    }
                }
                continue;
            }
            if (TeamUpgradeCatalog.trapName(name) != null) continue; // a trap for sale

            Lore lore = readLore(slot.lore);
            TeamUpgradeCatalog.Upgrade upgrade = TeamUpgradeCatalog.upgradeForShopName(name);
            String key;
            if (upgrade != null) {
                key = upgrade.key;
                known = true;
            } else {
                if (!lore.hasCost() || name.endsWith(" Trap")) continue; // decoration or an unknown trap for sale
                key = lore.tierCosts.isEmpty() ? name : TeamUpgradeCatalog.withoutRoman(name);
            }

            int owned;
            if (!lore.tierCosts.isEmpty()) {
                owned = lore.greenTiers;
            } else {
                owned = TeamUpgradeCatalog.leadingColour(slot.name) == 'a' || lore.unlocked ? 1 : 0;
            }
            tiers.put(key, owned);
            int[] c = lore.costs();
            if (c != null) costs.put(key, c);
        }

        if (!known) return null;
        List<String> traps = new ArrayList<String>(queue.values());
        while (traps.size() > TeamUpgradeCatalog.TRAP_QUEUE_MAX) traps.remove(traps.size() - 1);
        return new Snapshot(tiers, costs, traps);
    }

    private static final class Lore {
        final TreeMap<Integer, Integer> tierCosts = new TreeMap<Integer, Integer>();
        int greenTiers;
        int singleCost = -1;
        boolean unlocked;

        boolean hasCost() {
            return !tierCosts.isEmpty() || singleCost >= 0;
        }

        int[] costs() {
            if (!tierCosts.isEmpty()) {
                int n = tierCosts.lastKey();
                int[] out = new int[n];
                for (int i = 0; i < n; i++) {
                    Integer c = tierCosts.get(i + 1);
                    out[i] = c == null ? -1 : c;
                }
                return out;
            }
            return singleCost >= 0 ? new int[]{singleCost} : null;
        }
    }

    private static Lore readLore(List<String> lines) {
        Lore lore = new Lore();
        for (String raw : lines) {
            if (raw == null) continue;
            String line = TeamUpgradeCatalog.strip(raw).trim();
            Matcher t = TIER_LINE.matcher(line);
            if (t.matches()) {
                try {
                    int tier = Integer.parseInt(t.group(1));
                    int cost = Integer.parseInt(t.group(2));
                    if (tier >= 1 && tier <= 16) {
                        lore.tierCosts.put(tier, cost);
                        if (TeamUpgradeCatalog.leadingColour(raw) == 'a') lore.greenTiers++;
                    }
                } catch (NumberFormatException ignored) {
                    // not a tier line we can use
                }
                continue;
            }
            Matcher c = COST_LINE.matcher(line);
            if (c.matches()) {
                try {
                    lore.singleCost = Integer.parseInt(c.group(1));
                } catch (NumberFormatException ignored) {
                    // not a cost we can use
                }
                continue;
            }
            if ("UNLOCKED".equals(line)) lore.unlocked = true;
        }
        return lore;
    }
}
