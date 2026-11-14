package com.bedwarsqol.feature;

import com.bedwarsqol.feature.TeamUpgradeCatalog.Bracket;
import com.bedwarsqol.feature.TeamUpgradeCatalog.Tiered;
import com.bedwarsqol.feature.TeamUpgradeCatalog.Upgrade;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the Team Upgrades catalogue against research-hypixel.md §1-§3: shop order, per-tier diamond
 * costs for the 8-team and 4-team brackets, the item names Hypixel prints (with the pre-2025 legacy
 * names), the 1/2/4 trap costs and the mode-to-bracket rule.
 */
public class TeamUpgradeCatalogTest {

    @Test
    public void upgradesAreInShopOrder() {
        List<String> keys = new ArrayList<String>();
        for (Upgrade u : TeamUpgradeCatalog.upgrades()) keys.add(u.key);
        assertEquals("[Sharpened Swords, Reinforced Armor, Maniac Miner, Forge, Heal Pool, Cushioned Boots, Dragon Buff]",
                keys.toString());
        assertTrue(TeamUpgradeCatalog.upgrade("Dragon Buff").legacy);
        assertFalse(TeamUpgradeCatalog.upgrade("Heal Pool").legacy);
    }

    @Test
    public void eightTeamCostsPerTier() {
        assertCosts("Sharpened Swords", Bracket.EIGHT_TEAM, 4);
        assertCosts("Reinforced Armor", Bracket.EIGHT_TEAM, 2, 4, 8, 16);
        assertCosts("Maniac Miner", Bracket.EIGHT_TEAM, 2, 4);
        assertCosts("Forge", Bracket.EIGHT_TEAM, 2, 4, 6, 8);
        assertCosts("Heal Pool", Bracket.EIGHT_TEAM, 1);
        assertCosts("Cushioned Boots", Bracket.EIGHT_TEAM, 1, 2);
    }

    @Test
    public void fourTeamCostsPerTier() {
        assertCosts("Sharpened Swords", Bracket.FOUR_TEAM, 8);
        assertCosts("Reinforced Armor", Bracket.FOUR_TEAM, 5, 10, 20, 30);
        assertCosts("Maniac Miner", Bracket.FOUR_TEAM, 4, 6);
        assertCosts("Forge", Bracket.FOUR_TEAM, 4, 8, 12, 16);
        assertCosts("Heal Pool", Bracket.FOUR_TEAM, 3);
        assertCosts("Cushioned Boots", Bracket.FOUR_TEAM, 2, 4);
    }

    @Test
    public void rotatingSwordsTierTwoHasATableCostButIsNotSoldByDefault() {
        Upgrade swords = TeamUpgradeCatalog.upgrade("Sharpened Swords");
        assertEquals(1, swords.baseTiers);
        assertEquals(16, swords.cost(2, Bracket.EIGHT_TEAM));
        assertEquals(32, swords.cost(2, Bracket.FOUR_TEAM));
    }

    @Test
    public void unknownBracketHasNoCost() {
        assertEquals(-1, TeamUpgradeCatalog.upgrade("Reinforced Armor").cost(1, Bracket.UNKNOWN));
        assertEquals(-1, TeamUpgradeCatalog.upgrade("Reinforced Armor").cost(5, Bracket.EIGHT_TEAM));
        assertEquals(-1, TeamUpgradeCatalog.upgrade("Reinforced Armor").cost(0, Bracket.EIGHT_TEAM));
    }

    @Test
    public void chatItemNamesResolveToUpgradeAndTier() {
        assertTier("Sharpened Swords", "Sharpened Swords", 1);
        assertTier("Sharpened Swords II", "Sharpened Swords", 2);
        assertTier("Reinforced Armor I", "Reinforced Armor", 1);
        assertTier("Reinforced Armor IV", "Reinforced Armor", 4);
        assertTier("Maniac Miner II", "Maniac Miner", 2);
        assertTier("Iron Forge", "Forge", 1);
        assertTier("Golden Forge", "Forge", 2);
        assertTier("Emerald Forge", "Forge", 3);
        assertTier("Molten Forge", "Forge", 4);
        assertTier("Heal Pool", "Heal Pool", 1);
        assertTier("Cushioned Boots II", "Cushioned Boots", 2);
        assertTier("Dragon Buff", "Dragon Buff", 1);
    }

    @Test
    public void nonCatalogueItemsResolveToNothing() {
        assertNull(TeamUpgradeCatalog.upgradeTier("Wool"));
        assertNull(TeamUpgradeCatalog.upgradeTier("Reinforced Armor V"));
        assertNull(TeamUpgradeCatalog.upgradeTier("Reinforced Armor"));
        assertNull(TeamUpgradeCatalog.upgradeTier("Diamond Forge"));
        assertNull(TeamUpgradeCatalog.upgradeTier(null));
        assertNull(TeamUpgradeCatalog.upgradeTier("Blindness Trap"));
    }

    @Test
    public void trapNamesIncludingLegacyAreCanonical() {
        assertEquals("Blindness Trap", TeamUpgradeCatalog.trapName("Blindness Trap"));
        assertEquals("Blindness Trap", TeamUpgradeCatalog.trapName("It's a trap!"));
        assertEquals("Reveal Trap", TeamUpgradeCatalog.trapName("Alarm Trap"));
        assertEquals("Reveal Trap", TeamUpgradeCatalog.trapName("Reveal Trap"));
        assertEquals("Counter-Offensive Trap", TeamUpgradeCatalog.trapName("Counter-Offensive Trap"));
        assertEquals("Miner Fatigue Trap", TeamUpgradeCatalog.trapName("Miner Fatigue Trap"));
        assertNull(TeamUpgradeCatalog.trapName("Illusion Trap"));
        assertNull(TeamUpgradeCatalog.trapName("Heal Pool"));
    }

    @Test
    public void trapLabelDropsTheTrapSuffix() {
        assertEquals("Blindness", TeamUpgradeCatalog.trapLabel("Blindness Trap"));
        assertEquals("Counter-Offensive", TeamUpgradeCatalog.trapLabel("Counter-Offensive Trap"));
        assertEquals("Illusion", TeamUpgradeCatalog.trapLabel("Illusion Trap"));
        assertEquals("Mystery", TeamUpgradeCatalog.trapLabel("Mystery"));
    }

    @Test
    public void trapCostScalesWithQueueLength() {
        assertEquals(1, TeamUpgradeCatalog.trapCost(0));
        assertEquals(2, TeamUpgradeCatalog.trapCost(1));
        assertEquals(4, TeamUpgradeCatalog.trapCost(2));
        assertEquals(-1, TeamUpgradeCatalog.trapCost(3));
        assertEquals(3, TeamUpgradeCatalog.TRAP_QUEUE_MAX);
    }

    @Test
    public void bracketFromModApiMode() {
        assertEquals(Bracket.EIGHT_TEAM, TeamUpgradeCatalog.bracket("BEDWARS_EIGHT_ONE"));
        assertEquals(Bracket.EIGHT_TEAM, TeamUpgradeCatalog.bracket("BEDWARS_EIGHT_TWO"));
        assertEquals(Bracket.EIGHT_TEAM, TeamUpgradeCatalog.bracket("BEDWARS_EIGHT_TWO_RUSH"));
        assertEquals(Bracket.FOUR_TEAM, TeamUpgradeCatalog.bracket("BEDWARS_FOUR_THREE"));
        assertEquals(Bracket.FOUR_TEAM, TeamUpgradeCatalog.bracket("BEDWARS_FOUR_FOUR"));
        assertEquals(Bracket.FOUR_TEAM, TeamUpgradeCatalog.bracket("BEDWARS_TWO_FOUR"));
        assertEquals(Bracket.UNKNOWN, TeamUpgradeCatalog.bracket("BEDWARS_CASTLE"));
        assertEquals(Bracket.UNKNOWN, TeamUpgradeCatalog.bracket("BEDWARS_TWO_ONE_DUELS"));
        assertEquals(Bracket.UNKNOWN, TeamUpgradeCatalog.bracket("LOBBY"));
        assertEquals(Bracket.UNKNOWN, TeamUpgradeCatalog.bracket(null));
    }

    @Test
    public void standardModesAreExactlyTheFive() {
        assertTrue(TeamUpgradeCatalog.isStandardMode("BEDWARS_EIGHT_ONE"));
        assertTrue(TeamUpgradeCatalog.isStandardMode("BEDWARS_EIGHT_TWO"));
        assertTrue(TeamUpgradeCatalog.isStandardMode("BEDWARS_FOUR_THREE"));
        assertTrue(TeamUpgradeCatalog.isStandardMode("BEDWARS_FOUR_FOUR"));
        assertTrue(TeamUpgradeCatalog.isStandardMode("BEDWARS_TWO_FOUR"));
        assertFalse(TeamUpgradeCatalog.isStandardMode("BEDWARS_EIGHT_TWO_RUSH"));
        assertFalse(TeamUpgradeCatalog.isStandardMode("BEDWARS_CASTLE"));
        assertFalse(TeamUpgradeCatalog.isStandardMode(null));
    }

    @Test
    public void stripRemovesColourCodes() {
        assertEquals("Steve purchased Heal Pool", TeamUpgradeCatalog.strip("§aSteve purchased §r§6Heal Pool§r"));
        assertEquals("", TeamUpgradeCatalog.strip("§"));
        assertNull(TeamUpgradeCatalog.strip(null));
    }

    private static void assertCosts(String key, Bracket b, int... costs) {
        Upgrade u = TeamUpgradeCatalog.upgrade(key);
        assertEquals(key + " base tiers", costs.length, u.baseTiers);
        for (int i = 0; i < costs.length; i++) {
            assertEquals(key + " tier " + (i + 1), costs[i], u.cost(i + 1, b));
        }
    }

    private static void assertTier(String item, String key, int tier) {
        Tiered t = TeamUpgradeCatalog.upgradeTier(item);
        assertEquals(item, key, t == null ? null : t.upgrade.key);
        assertEquals(item, tier, t.tier);
    }
}
