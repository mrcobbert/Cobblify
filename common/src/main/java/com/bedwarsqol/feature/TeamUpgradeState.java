package com.bedwarsqol.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One team's upgrades and trap queue, built from events. Pure event application: it knows nothing
 * about games or time beyond the {@code now} each event carries (the Lifecycle decides which game a
 * state belongs to).
 *
 * <ul>
 *   <li>Purchase: sets that upgrade's tier, a lower tier never replacing a higher one; a trap is
 *       appended (a purchase into a full queue proves a trigger was missed, so the head goes).</li>
 *   <li>Trap fired: removes the first queued trap of that name, else the head. Any second fired
 *       signal within 5 s is the same trigger (Hypixel spaces triggers about 30 s apart).</li>
 *   <li>Trap removed: removes the last queued trap of that name.</li>
 *   <li>Shop snapshot: replaces tiers and queue exactly; costs it shows are learned.</li>
 *   <li>Gear: raises only, and never once a snapshot has been applied.</li>
 * </ul>
 */
public final class TeamUpgradeState {

    /** A second fired signal this soon after the last is the same trigger. */
    public static final long FIRED_DEDUPE_MS = 5000;
    /** How long a fired trap stays on the HUD, fading out. */
    public static final long FIRED_FLASH_MS = 1500;

    private final Map<String, Integer> tiers = new LinkedHashMap<String, Integer>();
    private final Map<String, int[]> learnedCosts = new HashMap<String, int[]>();
    private final List<String> traps = new ArrayList<String>();
    private boolean snapshotApplied;
    private boolean anyFired;
    private long lastFiredAt;
    private String firedTrap;
    private int firedIndex = -1;
    private long firedAt;

    public TeamUpgradeState() {}

    /** An independent copy. */
    public TeamUpgradeState copy() {
        TeamUpgradeState c = new TeamUpgradeState();
        c.tiers.putAll(tiers);
        for (Map.Entry<String, int[]> e : learnedCosts.entrySet()) c.learnedCosts.put(e.getKey(), e.getValue().clone());
        c.traps.addAll(traps);
        c.snapshotApplied = snapshotApplied;
        c.anyFired = anyFired;
        c.lastFiredAt = lastFiredAt;
        c.firedTrap = firedTrap;
        c.firedIndex = firedIndex;
        c.firedAt = firedAt;
        return c;
    }

    /** Applies one chat/title event; null is ignored. */
    public void apply(TeamUpgradeChat.Event e, long now) {
        if (e == null) return;
        switch (e.kind) {
            case PURCHASE: purchase(e.item, now); break;
            case TRAP_FIRED: trapFired(e.item, now); break;
            case TRAP_REMOVED: trapRemoved(e.item, now); break;
            default: break;
        }
    }

    /** A purchase of {@code item}, a catalogue upgrade tier or trap name (legacy names accepted). */
    public void purchase(String item, long now) {
        if (item == null) return;
        String trap = TeamUpgradeCatalog.trapName(item);
        if (trap != null) {
            if (traps.size() >= TeamUpgradeCatalog.TRAP_QUEUE_MAX) traps.remove(0);
            traps.add(trap);
            return;
        }
        TeamUpgradeCatalog.Tiered t = TeamUpgradeCatalog.upgradeTier(item);
        if (t != null) raise(t.upgrade.key, t.tier);
    }

    /** A trap went off: {@code trap} names it, or null when only "a trap" is known. */
    public void trapFired(String trap, long now) {
        if (anyFired && now >= lastFiredAt && now - lastFiredAt < FIRED_DEDUPE_MS) return;
        anyFired = true;
        lastFiredAt = now;
        if (traps.isEmpty()) return;
        int i = trap == null ? -1 : traps.indexOf(trap);
        if (i < 0) i = 0;
        firedTrap = traps.remove(i);
        firedIndex = i;
        firedAt = now;
    }

    /** A trap was taken out of the queue by its buyer. */
    public void trapRemoved(String trap, long now) {
        if (trap == null) return;
        int i = traps.lastIndexOf(trap);
        if (i >= 0) traps.remove(i);
    }

    /** The shop window's state replaces this one exactly; null changes nothing. */
    public void snapshot(TeamUpgradeShop.Snapshot s, long now) {
        if (s == null) return;
        tiers.clear();
        tiers.putAll(s.tiers);
        for (Map.Entry<String, int[]> e : s.costs.entrySet()) learnedCosts.put(e.getKey(), e.getValue().clone());
        traps.clear();
        traps.addAll(s.traps);
        snapshotApplied = true;
    }

    /** Minimum tiers read off armour and effects; raises only, ignored once a snapshot was applied. */
    public void gear(Map<String, Integer> raises) {
        if (snapshotApplied || raises == null) return;
        for (Map.Entry<String, Integer> e : raises.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) raise(e.getKey(), e.getValue());
        }
    }

    private void raise(String key, int tier) {
        if (tier <= 0) return;
        Integer cur = tiers.get(key);
        if (cur == null || cur < tier) tiers.put(key, tier);
    }

    /** Owned tier of an upgrade key (or unknown entry name); 0 when not owned. */
    public int tier(String key) {
        Integer t = tiers.get(key);
        return t == null ? 0 : t;
    }

    /** Every upgrade this state knows of (owned, or seen in the window): shop order, unknown names after. */
    public List<String> keys() {
        List<String> out = new ArrayList<String>();
        for (TeamUpgradeCatalog.Upgrade u : TeamUpgradeCatalog.upgrades()) {
            if (tiers.containsKey(u.key)) out.add(u.key);
        }
        for (String k : tiers.keySet()) {
            if (TeamUpgradeCatalog.order(k) < 0) out.add(k);
        }
        return out;
    }

    /** Queued traps in firing order. */
    public List<String> traps() {
        return Collections.unmodifiableList(new ArrayList<String>(traps));
    }

    /** Cost of {@code tier} read from the shop window, or -1. */
    public int learnedCost(String key, int tier) {
        int[] c = learnedCosts.get(key);
        if (c == null || tier < 1 || tier > c.length) return -1;
        return c[tier - 1];
    }

    /** How many tiers the shop window listed for {@code key}, or 0 when never seen. */
    public int learnedTierCount(String key) {
        int[] c = learnedCosts.get(key);
        return c == null ? 0 : c.length;
    }

    /** True once a shop snapshot has been applied (gear inference stops). */
    public boolean snapshotApplied() {
        return snapshotApplied;
    }

    /** The trap that fired, while it is still flashing at {@code now}; else null. */
    public String firedTrap(long now) {
        if (firedTrap == null || now < firedAt || now - firedAt >= FIRED_FLASH_MS) return null;
        return firedTrap;
    }

    /** Queue position the last fired trap had, or -1. */
    public int firedIndex() {
        return firedIndex;
    }

    /** When the last trap fired. */
    public long firedAt() {
        return firedAt;
    }

    /** True when nothing is owned or queued. */
    public boolean isEmpty() {
        if (!traps.isEmpty()) return false;
        for (Integer t : tiers.values()) if (t != null && t > 0) return false;
        return true;
    }
}
