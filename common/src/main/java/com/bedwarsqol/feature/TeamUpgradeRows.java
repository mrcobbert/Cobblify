package com.bedwarsqol.feature;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows the Team Upgrades HUD draws, as text (PLAN "What the player sees", Amendment A). The HUD
 * only lays them out: gray label, white value, right-hand cost (or {@code Max} / {@code Full}).
 *
 * <ul>
 *   <li>Compact: owned upgrades in shop order ({@code Reinforced Armor  II  8}, the forge by its tier
 *       name, {@code Max} when maxed), then always the Traps section: {@code 1 Blindness}, ... and a
 *       {@code Next} row with 1/2/4 or {@code Full}; {@code None} above it when nothing is queued.</li>
 *   <li>Full: all six upgrades in shop order, unowned ones dimmed with their tier-one cost (unknown
 *       entries from the shop after them), and three trap slots, empty ones as a dash.</li>
 *   <li>Costs: the cost the shop window showed beats the bracket table; with neither, no cost.</li>
 *   <li>A fired trap keeps its row in red for {@link TeamUpgradeState#FIRED_FLASH_MS}, fading out,
 *       then the rows below move up.</li>
 * </ul>
 */
public final class TeamUpgradeRows {

    private TeamUpgradeRows() {}

    public enum Layout { COMPACT, FULL }

    public enum Kind { HEADER, UPGRADE, TRAP, NEXT, NONE }

    public enum Style { NORMAL, DIM, FIRED }

    public static final String UPGRADES = "Upgrades";
    public static final String TRAPS = "Traps";
    public static final String MAX = "Max";
    public static final String FULL = "Full";
    public static final String NONE = "None";
    public static final String NEXT = "Next";
    public static final String EMPTY_SLOT = "—";

    /** One HUD row. {@code alpha} is 1 except for a fading fired trap. Strings are never null. */
    public static final class Row {
        public final Kind kind;
        public final String label;
        public final String value;
        public final String right;
        public final Style style;
        public final float alpha;

        public Row(Kind kind, String label, String value, String right, Style style, float alpha) {
            this.kind = kind;
            this.label = label == null ? "" : label;
            this.value = value == null ? "" : value;
            this.right = right == null ? "" : right;
            this.style = style;
            this.alpha = alpha;
        }

        @Override
        public String toString() {
            return kind + "|" + label + "|" + value + "|" + right + "|" + style;
        }
    }

    /** The rows for {@code state} at {@code now}; empty for a null state (nothing to draw). */
    public static List<Row> rows(TeamUpgradeState state, Layout layout, TeamUpgradeCatalog.Bracket bracket, long now) {
        List<Row> out = new ArrayList<Row>();
        if (state == null) return out;
        boolean full = layout == Layout.FULL;

        List<Row> upgrades = new ArrayList<Row>();
        if (full) {
            for (TeamUpgradeCatalog.Upgrade u : TeamUpgradeCatalog.upgrades()) {
                int owned = state.tier(u.key);
                if (u.legacy && owned <= 0) continue;
                upgrades.add(upgradeRow(state, u.key, owned, bracket));
            }
            for (String key : state.keys()) {
                if (TeamUpgradeCatalog.order(key) < 0) upgrades.add(upgradeRow(state, key, state.tier(key), bracket));
            }
        } else {
            for (String key : state.keys()) {
                int owned = state.tier(key);
                if (owned > 0) upgrades.add(upgradeRow(state, key, owned, bracket));
            }
        }
        if (full || !upgrades.isEmpty()) {
            out.add(header(UPGRADES));
            out.addAll(upgrades);
        }

        out.add(header(TRAPS));
        List<String> queue = state.traps();
        List<String> names = new ArrayList<String>(queue);
        List<Style> styles = new ArrayList<Style>();
        for (int i = 0; i < names.size(); i++) styles.add(Style.NORMAL);
        String fired = state.firedTrap(now);
        int firedAt = -1;
        float fade = 1f;
        if (fired != null && queue.size() < TeamUpgradeCatalog.TRAP_QUEUE_MAX) {
            firedAt = Math.max(0, Math.min(state.firedIndex(), names.size()));
            names.add(firedAt, fired);
            styles.add(firedAt, Style.FIRED);
            fade = 1f - (now - state.firedAt()) / (float) TeamUpgradeState.FIRED_FLASH_MS;
            fade = Math.max(0f, Math.min(1f, fade));
        }
        if (names.isEmpty() && !full) out.add(new Row(Kind.NONE, NONE, "", "", Style.NORMAL, 1f));
        for (int i = 0; i < names.size(); i++) {
            out.add(new Row(Kind.TRAP, (i + 1) + " " + TeamUpgradeCatalog.trapLabel(names.get(i)), "", "",
                    styles.get(i), i == firedAt ? fade : 1f));
        }
        if (full) {
            for (int i = names.size(); i < TeamUpgradeCatalog.TRAP_QUEUE_MAX; i++) {
                out.add(new Row(Kind.TRAP, (i + 1) + " " + EMPTY_SLOT, "", "", Style.DIM, 1f));
            }
        }
        int next = TeamUpgradeCatalog.trapCost(queue.size());
        out.add(new Row(Kind.NEXT, NEXT, "", next < 0 ? FULL : String.valueOf(next), Style.NORMAL, 1f));
        return out;
    }

    /**
     * Example rows for Edit HUD: Sharpened Swords Max, Reinforced Armor II 8, Iron Forge 4, Heal Pool
     * Max; traps Blindness, Counter-Offensive; Next 4 (two traps queued, so the real next cost). Compact
     * grows with what the team owns, so its example is the panel at full height, as Edit HUD reserves it:
     * Maniac Miner and Cushioned Boots owned too and a third trap queued (Next Full). The frame is then
     * filled instead of part empty (HUD tweaks).
     */
    public static List<Row> example(Layout layout) {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Sharpened Swords", 0);
        s.purchase("Reinforced Armor II", 0);
        if (layout == Layout.COMPACT) s.purchase("Maniac Miner I", 0);
        s.purchase("Iron Forge", 0);
        s.purchase("Heal Pool", 0);
        if (layout == Layout.COMPACT) s.purchase("Cushioned Boots I", 0);
        s.purchase(TeamUpgradeCatalog.BLINDNESS_TRAP, 0);
        s.purchase(TeamUpgradeCatalog.COUNTER_OFFENSIVE_TRAP, 0);
        if (layout == Layout.COMPACT) s.purchase(TeamUpgradeCatalog.REVEAL_TRAP, 0);
        return rows(s, layout, TeamUpgradeCatalog.Bracket.EIGHT_TEAM, 0);
    }

    private static Row header(String text) {
        return new Row(Kind.HEADER, text, "", "", Style.NORMAL, 1f);
    }

    private static Row upgradeRow(TeamUpgradeState state, String key, int owned,
                                  TeamUpgradeCatalog.Bracket bracket) {
        TeamUpgradeCatalog.Upgrade u = TeamUpgradeCatalog.upgrade(key);
        int maxTiers = Math.max(Math.max(u == null ? 0 : u.baseTiers, state.learnedTierCount(key)), owned);
        if (owned <= 0) {
            return new Row(Kind.UPGRADE, key, "", cost(state, u, key, 1, bracket), Style.DIM, 1f);
        }
        boolean named = u != null && u.namedTiers;
        String label = key;
        if (named) {
            String tierName = u.tierName(owned);
            if (tierName != null) label = tierName;
        }
        String value = !named && maxTiers > 1 ? TeamUpgradeCatalog.roman(owned) : "";
        String right = owned >= maxTiers ? MAX : cost(state, u, key, owned + 1, bracket);
        return new Row(Kind.UPGRADE, label, value, right, Style.NORMAL, 1f);
    }

    private static String cost(TeamUpgradeState state, TeamUpgradeCatalog.Upgrade u, String key, int tier,
                               TeamUpgradeCatalog.Bracket bracket) {
        int c = state.learnedCost(key, tier);
        if (c < 0 && u != null) c = u.cost(tier, bracket);
        return c < 0 ? "" : String.valueOf(c);
    }
}
