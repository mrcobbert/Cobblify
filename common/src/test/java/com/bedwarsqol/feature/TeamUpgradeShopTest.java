package com.bedwarsqol.feature;

import com.bedwarsqol.feature.TeamUpgradeShop.Slot;
import com.bedwarsqol.feature.TeamUpgradeShop.Snapshot;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the {@code Upgrades & Traps} window read (research-hypixel.md §4): owned tiers from green tier
 * lore, single upgrades from a green name or {@code UNLOCKED}, exact costs from the lore, the trap
 * queue from {@code Trap #N} names, unknown entries by their own name, and "no snapshot" for a window
 * with no known upgrade (Invisible Shop, any other chest).
 */
public class TeamUpgradeShopTest {

    private static final String TITLE = "Upgrades & Traps";

    static Slot slot(String name, int stack, String... lore) {
        return new Slot(name, Arrays.asList(lore), stack);
    }

    /** A Solo-priced window: Armor II bought, Iron Forge bought, Heal Pool bought, two traps queued. */
    static List<Slot> fullWindow() {
        List<Slot> s = new ArrayList<Slot>();
        s.add(slot("§cSharpened Swords", 1,
                "§7Your team permanently gains", "§7Sharpness I on all swords and", "§7axes!", "",
                "§7Cost: §b4 Diamonds", "", "§cYou don't have enough Diamonds!"));
        s.add(slot("§aReinforced Armor III", 2,
                "§7Your team permanently gains", "§7Protection on all armor pieces!", "",
                "§aTier 1: Protection I§7, §b2 Diamonds",
                "§aTier 2: Protection II§7, §b4 Diamonds",
                "§7Tier 3: Protection III§7, §b8 Diamonds",
                "§7Tier 4: Protection IV§7, §b16 Diamonds", "", "§eClick to purchase!"));
        s.add(slot("§eManiac Miner I", 1,
                "§7All players on your team", "§7permanently gain Haste.", "",
                "§7Tier 1: Haste I§7, §b2 Diamonds",
                "§7Tier 2: Haste II§7, §b4 Diamonds", "", "§eClick to purchase!"));
        s.add(slot("§aGolden Forge", 1,
                "§7Upgrade resource spawning on", "§7your island.", "",
                "§aTier 1: +50% Resources§7, §b2 Diamonds",
                "§7Tier 2: +100% Resources§7, §b4 Diamonds",
                "§7Tier 3: Spawn emeralds§7, §b6 Diamonds",
                "§7Tier 4: +200% Resources§7, §b8 Diamonds", "", "§eClick to purchase!"));
        s.add(slot("§aHeal Pool", 1,
                "§7Creates a Regeneration field", "§7around your base!", "",
                "§7Cost: §b1 Diamond", "", "§aUNLOCKED"));
        s.add(slot("§eCushioned Boots I", 1,
                "§7Your team permanently gains", "§7Feather Falling on boots.", "",
                "§7Tier 1: Feather Falling I§7, §b1 Diamond",
                "§7Tier 2: Feather Falling II§7, §b2 Diamonds", "", "§eClick to purchase!"));
        s.add(slot("§eBlindness Trap", 1, "§7Inflicts Blindness and Slowness", "§7for 8 seconds.", "",
                "§7Cost: §b4 Diamonds"));
        s.add(slot("§eCounter-Offensive Trap", 1, "§7Grants Speed II and Jump Boost II", "", "§7Cost: §b4 Diamonds"));
        s.add(slot("§eReveal Trap", 1, "§7Reveals invisible players", "", "§7Cost: §b4 Diamonds"));
        s.add(slot("§eMiner Fatigue Trap", 1, "§7Inflict Mining Fatigue", "", "§7Cost: §b4 Diamonds"));
        s.add(slot("§8⬆ §7Purchasable", 1, "§8⬇ §7Traps Queue"));
        s.add(slot("§aTrap #2: Miner Fatigue Trap", 2, "§7Inflict Mining Fatigue", "", "§7Queued by: §bSteve"));
        s.add(slot("§aTrap #1: Blindness Trap", 1, "§7Inflicts Blindness", "", "§7Queued by: §bSteve",
                "§eRight Click to remove! §8(No refund)"));
        s.add(slot("§cTrap #3: No Trap!", 3, "§7The third enemy to walk into your base will trigger this trap!",
                "", "§7Purchasing a trap will queue it here. Its cost will scale based on the number of traps queued.",
                "", "§7Next trap: §b4 Diamonds"));
        return s;
    }

    @Test
    public void fullWindowGivesTiersCostsAndQueue() {
        Snapshot snap = TeamUpgradeShop.parse("§8" + TITLE, fullWindow());
        assertNotNull(snap);
        assertEquals(Integer.valueOf(0), snap.tiers.get("Sharpened Swords"));
        assertEquals(Integer.valueOf(2), snap.tiers.get("Reinforced Armor"));
        assertEquals(Integer.valueOf(0), snap.tiers.get("Maniac Miner"));
        assertEquals(Integer.valueOf(1), snap.tiers.get("Forge"));
        assertEquals(Integer.valueOf(1), snap.tiers.get("Heal Pool"));
        assertEquals(Integer.valueOf(0), snap.tiers.get("Cushioned Boots"));
        assertEquals(6, snap.tiers.size()); // traps and panes are not upgrades
        assertEquals(Arrays.asList("Blindness Trap", "Miner Fatigue Trap"), snap.traps); // by #N, not slot order
    }

    @Test
    public void costsAreLearnedFromLore() {
        Snapshot snap = TeamUpgradeShop.parse(TITLE, fullWindow());
        assertArrayEquals(new int[]{4}, snap.costs.get("Sharpened Swords"));
        assertArrayEquals(new int[]{2, 4, 8, 16}, snap.costs.get("Reinforced Armor"));
        assertArrayEquals(new int[]{2, 4, 6, 8}, snap.costs.get("Forge"));
        assertArrayEquals(new int[]{1}, snap.costs.get("Heal Pool"));
        assertArrayEquals(new int[]{1, 2}, snap.costs.get("Cushioned Boots"));
    }

    @Test
    public void slotOrderDoesNotMatter() {
        List<Slot> shuffled = fullWindow();
        Collections.reverse(shuffled);
        Snapshot a = TeamUpgradeShop.parse(TITLE, fullWindow());
        Snapshot b = TeamUpgradeShop.parse(TITLE, shuffled);
        assertEquals(a.tiers, b.tiers);
        assertEquals(a.traps, b.traps);
    }

    @Test
    public void rotatingSwordsTierTwoIsRevealedByItsLore() {
        List<Slot> s = new ArrayList<Slot>();
        s.add(slot("§eSharpened Swords II", 1, "§7Sharpness on swords", "",
                "§aTier 1: Sharpness I§7, §b4 Diamonds",
                "§7Tier 2: Sharpness II§7, §b16 Diamonds",
                "§8§oTier 2 is a Rotating Item!", "", "§eClick to purchase!"));
        Snapshot snap = TeamUpgradeShop.parse(TITLE, s);
        assertEquals(Integer.valueOf(1), snap.tiers.get("Sharpened Swords"));
        assertArrayEquals(new int[]{4, 16}, snap.costs.get("Sharpened Swords"));
    }

    @Test
    public void maxedTieredUpgradeCountsAllGreenLines() {
        List<Slot> s = new ArrayList<Slot>();
        s.add(slot("§aManiac Miner II", 2, "",
                "§aTier 1: Haste I§7, §b4 Diamonds",
                "§aTier 2: Haste II§7, §b6 Diamonds", "", "§aUNLOCKED"));
        Snapshot snap = TeamUpgradeShop.parse(TITLE, s);
        assertEquals(Integer.valueOf(2), snap.tiers.get("Maniac Miner"));
        assertArrayEquals(new int[]{4, 6}, snap.costs.get("Maniac Miner"));
    }

    @Test
    public void singleUpgradeOwnedByGreenNameOrUnlocked() {
        List<Slot> green = new ArrayList<Slot>();
        green.add(slot("§aSharpened Swords", 1, "§7Cost: §b8 Diamonds"));
        assertEquals(Integer.valueOf(1), TeamUpgradeShop.parse(TITLE, green).tiers.get("Sharpened Swords"));

        List<Slot> unlocked = new ArrayList<Slot>();
        unlocked.add(slot("§fHeal Pool", 1, "§7Cost: §b3 Diamonds", "", "§aUNLOCKED"));
        assertEquals(Integer.valueOf(1), TeamUpgradeShop.parse(TITLE, unlocked).tiers.get("Heal Pool"));

        List<Slot> yellow = new ArrayList<Slot>();
        yellow.add(slot("§eHeal Pool", 1, "§7Cost: §b3 Diamonds", "", "§eClick to purchase!"));
        assertEquals(Integer.valueOf(0), TeamUpgradeShop.parse(TITLE, yellow).tiers.get("Heal Pool"));
    }

    @Test
    public void emptyQueueIsEmpty() {
        List<Slot> s = new ArrayList<Slot>();
        s.add(slot("§eHeal Pool", 1, "§7Cost: §b1 Diamond"));
        s.add(slot("§cTrap #1: No Trap!", 1, "§7Next trap: §b1 Diamond"));
        s.add(slot("§cTrap #2: No Trap!", 2));
        s.add(slot("§cTrap #3: No Trap!", 3));
        Snapshot snap = TeamUpgradeShop.parse(TITLE, s);
        assertTrue(snap.traps.isEmpty());
    }

    @Test
    public void duplicateAndLegacyTrapsInTheQueue() {
        List<Slot> s = new ArrayList<Slot>();
        s.add(slot("§eHeal Pool", 1, "§7Cost: §b1 Diamond"));
        s.add(slot("§aTrap #1: Miner Fatigue Trap", 1));
        s.add(slot("§aTrap #2: Miner Fatigue Trap", 2));
        s.add(slot("§aTrap #3: It's a trap!", 3));
        Snapshot snap = TeamUpgradeShop.parse(TITLE, s);
        assertEquals(Arrays.asList("Miner Fatigue Trap", "Miner Fatigue Trap", "Blindness Trap"), snap.traps);
    }

    @Test
    public void unknownTrapInTheQueueKeepsItsName() {
        List<Slot> s = new ArrayList<Slot>();
        s.add(slot("§eHeal Pool", 1, "§7Cost: §b1 Diamond"));
        s.add(slot("§aTrap #1: Illusion Trap", 1));
        assertEquals(Collections.singletonList("Illusion Trap"), TeamUpgradeShop.parse(TITLE, s).traps);
    }

    @Test
    public void unknownUpgradeWithCostLoreIsKeptByItsOwnName() {
        List<Slot> s = fullWindow();
        s.add(slot("§aDeadShot II", 2, "§7Guns deal more damage", "",
                "§aTier 1: 10% more damage§7, §b2 Diamonds",
                "§7Tier 2: 20% more damage§7, §b4 Diamonds",
                "§7Tier 3: 30% more damage§7, §b6 Diamonds"));
        s.add(slot("§aLucky Charm", 1, "§7Cost: §b3 Diamonds", "", "§aUNLOCKED"));
        Snapshot snap = TeamUpgradeShop.parse(TITLE, s);
        assertEquals(Integer.valueOf(1), snap.tiers.get("DeadShot"));
        assertArrayEquals(new int[]{2, 4, 6}, snap.costs.get("DeadShot"));
        assertEquals(Integer.valueOf(1), snap.tiers.get("Lucky Charm"));
        assertArrayEquals(new int[]{3}, snap.costs.get("Lucky Charm"));
    }

    @Test
    public void entriesWithoutCostLoreAreNotUpgrades() {
        List<Slot> s = fullWindow();
        s.add(slot("§aSomething Shiny", 1, "§7Just a decoration"));
        s.add(slot("§eIllusion Trap", 1, "§7Warps them back", "", "§7Cost: §b2 Diamonds"));
        Snapshot snap = TeamUpgradeShop.parse(TITLE, s);
        assertFalse(snap.tiers.containsKey("Something Shiny"));
        assertFalse(snap.tiers.containsKey("Illusion Trap")); // a trap for sale is not an upgrade
    }

    @Test
    public void invisibleShopWithNoKnownItemIsNoSnapshot() {
        List<Slot> s = new ArrayList<Slot>();
        s.add(slot("§c???", 1, "§7Cost: §b2 Diamonds"));
        s.add(slot("§c???", 1, "§7Cost: §b4 Diamonds"));
        s.add(slot("§cTrap #1: No Trap!", 1));
        assertNull(TeamUpgradeShop.parse(TITLE, s));
    }

    @Test
    public void otherWindowsAreNoSnapshot() {
        assertNull(TeamUpgradeShop.parse("Quick Buy", fullWindow()));
        assertNull(TeamUpgradeShop.parse(null, fullWindow()));
        assertNull(TeamUpgradeShop.parse(TITLE, null));
        assertNull(TeamUpgradeShop.parse(TITLE, new ArrayList<Slot>()));
    }

    @Test
    public void nullSlotsAndNullLoreAreTolerated() {
        List<Slot> s = new ArrayList<Slot>();
        s.add(null);
        s.add(new Slot(null, null, 0));
        s.add(new Slot("§aHeal Pool", null, 1));
        Snapshot snap = TeamUpgradeShop.parse(TITLE, s);
        assertEquals(Integer.valueOf(1), snap.tiers.get("Heal Pool"));
    }
}
