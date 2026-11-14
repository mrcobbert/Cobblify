package com.bedwarsqol.feature;

import com.bedwarsqol.feature.TeamUpgradeShop.Slot;
import com.bedwarsqol.feature.TeamUpgradeShop.Snapshot;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the Team Upgrades state rules (PLAN "TeamUpgradeState"): tiers never go down from a purchase,
 * the trap queue is FIFO with at most three entries and allows duplicates, a fired trap leaves (first
 * of its name, else the head) with a 5 s dedupe, a removal takes the last of its name, and a shop
 * snapshot replaces everything exactly.
 */
public class TeamUpgradeStateTest {

    private static List<String> traps(String... names) {
        return Arrays.asList(names);
    }

    @Test
    public void purchaseSetsTheTier() {
        TeamUpgradeState s = new TeamUpgradeState();
        assertTrue(s.isEmpty());
        s.purchase("Reinforced Armor I", 0);
        assertEquals(1, s.tier("Reinforced Armor"));
        s.purchase("Reinforced Armor II", 0);
        assertEquals(2, s.tier("Reinforced Armor"));
        s.purchase("Golden Forge", 0);
        assertEquals(2, s.tier("Forge"));
        assertFalse(s.isEmpty());
    }

    @Test
    public void aLowerTierNeverReplacesAHigherOne() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Reinforced Armor III", 0);
        s.purchase("Reinforced Armor I", 0);
        assertEquals(3, s.tier("Reinforced Armor"));
    }

    @Test
    public void unknownPurchaseChangesNothing() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Wool", 0);
        s.purchase(null, 0);
        assertTrue(s.isEmpty());
    }

    @Test
    public void legacyPurchasesMapToCurrentNames() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("It's a trap!", 0);
        s.purchase("Alarm Trap", 0);
        s.purchase("Dragon Buff", 0);
        assertEquals(traps("Blindness Trap", "Reveal Trap"), s.traps());
        assertEquals(1, s.tier("Dragon Buff"));
    }

    @Test
    public void keysAreInShopOrder() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Heal Pool", 0);
        s.purchase("Iron Forge", 0);
        s.purchase("Sharpened Swords", 0);
        assertEquals(Arrays.asList("Sharpened Swords", "Forge", "Heal Pool"), s.keys());
    }

    @Test
    public void trapsQueueInPurchaseOrderWithDuplicates() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Miner Fatigue Trap", 0);
        s.purchase("Miner Fatigue Trap", 0);
        s.purchase("Blindness Trap", 0);
        assertEquals(traps("Miner Fatigue Trap", "Miner Fatigue Trap", "Blindness Trap"), s.traps());
    }

    @Test
    public void aPurchaseIntoAFullQueueDropsTheHeadItMustHaveMissed() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Blindness Trap", 0);
        s.purchase("Reveal Trap", 0);
        s.purchase("Miner Fatigue Trap", 0);
        s.purchase("Counter-Offensive Trap", 0);
        assertEquals(traps("Reveal Trap", "Miner Fatigue Trap", "Counter-Offensive Trap"), s.traps());
    }

    @Test
    public void firedRemovesTheFirstOfThatName() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Blindness Trap", 0);
        s.purchase("Reveal Trap", 0);
        s.purchase("Reveal Trap", 0);
        s.trapFired("Reveal Trap", 1000);
        assertEquals(traps("Blindness Trap", "Reveal Trap"), s.traps());
    }

    @Test
    public void firedWithoutAMatchingNameRemovesTheHead() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Blindness Trap", 0);
        s.purchase("Reveal Trap", 0);
        s.trapFired(null, 1000);
        assertEquals(traps("Reveal Trap"), s.traps());
        s.trapFired("Miner Fatigue Trap", 60000);
        assertEquals(traps(), s.traps());
    }

    @Test
    public void firedOnAnEmptyQueueIsHarmless() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.trapFired(null, 1000);
        assertTrue(s.traps().isEmpty());
        assertNull(s.firedTrap(1000));
    }

    @Test
    public void aSecondFiredSignalWithinFiveSecondsIsTheSameTrigger() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Blindness Trap", 0);
        s.purchase("Counter-Offensive Trap", 0);
        s.purchase("Miner Fatigue Trap", 0);
        s.trapFired(null, 10000);                     // title TRAP TRIGGERED!
        s.trapFired("Blindness Trap", 10050);         // subtitle, same tick
        s.trapFired("Blindness Trap", 14999);         // chat line, still within 5 s
        assertEquals(traps("Counter-Offensive Trap", "Miner Fatigue Trap"), s.traps());
        s.trapFired("Counter-Offensive Trap", 40100); // the next trigger, ~30 s later
        assertEquals(traps("Miner Fatigue Trap"), s.traps());
    }

    @Test
    public void dedupeAlsoCoversAnEarlierChatLineThenTitle() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Reveal Trap", 0);
        s.purchase("Blindness Trap", 0);
        s.trapFired("Reveal Trap", 5000);
        s.trapFired(null, 5100);
        assertEquals(traps("Blindness Trap"), s.traps());
    }

    @Test
    public void firedTrapFlashesForAboutOneAndAHalfSeconds() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Blindness Trap", 0);
        s.purchase("Reveal Trap", 0);
        s.trapFired("Reveal Trap", 10000);
        assertEquals("Reveal Trap", s.firedTrap(10000));
        assertEquals(1, s.firedIndex());
        assertEquals(10000, s.firedAt());
        assertEquals("Reveal Trap", s.firedTrap(11499));
        assertNull(s.firedTrap(11500));
        assertNull(s.firedTrap(9999));
    }

    @Test
    public void removedTakesTheLastOfThatName() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Miner Fatigue Trap", 0);
        s.purchase("Blindness Trap", 0);
        s.purchase("Miner Fatigue Trap", 0);
        s.trapRemoved("Miner Fatigue Trap", 0);
        assertEquals(traps("Miner Fatigue Trap", "Blindness Trap"), s.traps());
        s.trapRemoved("Reveal Trap", 0);
        assertEquals(traps("Miner Fatigue Trap", "Blindness Trap"), s.traps());
    }

    @Test
    public void snapshotReplacesTiersAndQueueExactly() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Reinforced Armor IV", 0);
        s.purchase("Maniac Miner II", 0);
        s.purchase("Reveal Trap", 0);
        Snapshot snap = TeamUpgradeShop.parse("Upgrades & Traps", TeamUpgradeShopTest.fullWindow());
        s.snapshot(snap, 1000);
        assertEquals(2, s.tier("Reinforced Armor")); // the window rules, even downward
        assertEquals(0, s.tier("Maniac Miner"));
        assertEquals(1, s.tier("Forge"));
        assertEquals(1, s.tier("Heal Pool"));
        assertEquals(traps("Blindness Trap", "Miner Fatigue Trap"), s.traps());
        assertTrue(s.snapshotApplied());
        assertEquals(8, s.learnedCost("Reinforced Armor", 3));
        assertEquals(4, s.learnedTierCount("Reinforced Armor"));
        assertEquals(-1, s.learnedCost("Maniac Miner", 3));
    }

    @Test
    public void nullSnapshotChangesNothing() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Heal Pool", 0);
        s.snapshot(null, 0);
        assertEquals(1, s.tier("Heal Pool"));
        assertFalse(s.snapshotApplied());
    }

    @Test
    public void unknownUpgradeFromSnapshotComesAfterKnownOnes() {
        List<Slot> slots = new ArrayList<Slot>(TeamUpgradeShopTest.fullWindow());
        slots.add(0, TeamUpgradeShopTest.slot("§aLucky Charm", 1, "§7Cost: §b3 Diamonds", "§aUNLOCKED"));
        TeamUpgradeState s = new TeamUpgradeState();
        s.snapshot(TeamUpgradeShop.parse("Upgrades & Traps", slots), 0);
        List<String> keys = s.keys();
        assertEquals("Lucky Charm", keys.get(keys.size() - 1));
        assertEquals(1, s.tier("Lucky Charm"));
    }

    @Test
    public void gearRaisesOnlyAndStopsAfterASnapshot() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Reinforced Armor III", 0);
        Map<String, Integer> raises = new LinkedHashMap<String, Integer>();
        raises.put("Reinforced Armor", 2);
        raises.put("Heal Pool", 1);
        s.gear(raises);
        assertEquals(3, s.tier("Reinforced Armor"));
        assertEquals(1, s.tier("Heal Pool"));

        TeamUpgradeState t = new TeamUpgradeState();
        List<Slot> one = new ArrayList<Slot>();
        one.add(TeamUpgradeShopTest.slot("§eHeal Pool", 1, "§7Cost: §b1 Diamond"));
        t.snapshot(TeamUpgradeShop.parse("Upgrades & Traps", one), 0);
        t.gear(Collections.singletonMap("Heal Pool", 1));
        assertEquals(0, t.tier("Heal Pool"));
    }

    @Test
    public void applyDispatchesChatEvents() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.apply(TeamUpgradeChat.classify("Steve purchased Blindness Trap", "§aSteve purchased §6Blindness Trap", null), 0);
        s.apply(TeamUpgradeChat.classify("Steve purchased Heal Pool", "§aSteve purchased §6Heal Pool", null), 0);
        assertEquals(traps("Blindness Trap"), s.traps());
        s.apply(TeamUpgradeChat.classifyTitle("TRAP TRIGGERED!"), 1000);
        assertTrue(s.traps().isEmpty());
        s.apply(null, 2000);
        assertEquals(1, s.tier("Heal Pool"));
    }

    @Test
    public void copyIsIndependent() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Heal Pool", 0);
        s.purchase("Blindness Trap", 0);
        TeamUpgradeState c = s.copy();
        s.purchase("Reinforced Armor I", 0);
        s.trapFired(null, 1000);
        assertEquals(0, c.tier("Reinforced Armor"));
        assertEquals(traps("Blindness Trap"), c.traps());
        assertEquals(1, c.tier("Heal Pool"));
    }
}
