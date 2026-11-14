package com.bedwarsqol.feature;

import com.bedwarsqol.feature.TeamUpgradeCatalog.Bracket;
import com.bedwarsqol.feature.TeamUpgradeRows.Layout;
import com.bedwarsqol.feature.TeamUpgradeRows.Row;
import com.bedwarsqol.feature.TeamUpgradeRows.Style;
import com.bedwarsqol.feature.TeamUpgradeShop.Slot;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the rows the Team Upgrades HUD draws (PLAN "What the player sees" + Amendment A): Compact
 * lists owned upgrades and always the Traps section; Full lists all six upgrades (unowned dimmed with
 * their tier-one cost) and three trap slots. Costs: learned shop cost, else the bracket table, else
 * none. A fired trap stays as a red row that fades out over about 1.5 s.
 */
public class TeamUpgradeRowsTest {

    /** One row as {@code KIND|label|value|right|STYLE}. */
    private static String fmt(Row r) {
        return r.kind + "|" + r.label + "|" + r.value + "|" + r.right + "|" + r.style;
    }

    private static List<String> fmt(List<Row> rows) {
        List<String> out = new ArrayList<String>();
        for (Row r : rows) out.add(fmt(r));
        return out;
    }

    private static TeamUpgradeState mock() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Sharpened Swords", 0);
        s.purchase("Reinforced Armor II", 0);
        s.purchase("Iron Forge", 0);
        s.purchase("Heal Pool", 0);
        s.purchase("Blindness Trap", 0);
        s.purchase("Counter-Offensive Trap", 0);
        return s;
    }

    @Test
    public void compactShowsOwnedUpgradesAndTheTrapQueue() {
        assertEquals(Arrays.asList(
                "HEADER|Upgrades|||NORMAL",
                "UPGRADE|Sharpened Swords||Max|NORMAL",
                "UPGRADE|Reinforced Armor|II|8|NORMAL",
                "UPGRADE|Iron Forge||4|NORMAL",
                "UPGRADE|Heal Pool||Max|NORMAL",
                "HEADER|Traps|||NORMAL",
                "TRAP|1 Blindness|||NORMAL",
                "TRAP|2 Counter-Offensive|||NORMAL",
                "NEXT|Next||4|NORMAL"),
                fmt(TeamUpgradeRows.rows(mock(), Layout.COMPACT, Bracket.EIGHT_TEAM, 0)));
    }

    @Test
    public void fourTeamPricesComeFromTheirColumn() {
        List<String> rows = fmt(TeamUpgradeRows.rows(mock(), Layout.COMPACT, Bracket.FOUR_TEAM, 0));
        assertTrue(rows.contains("UPGRADE|Reinforced Armor|II|20|NORMAL"));
        assertTrue(rows.contains("UPGRADE|Iron Forge||8|NORMAL"));
    }

    @Test
    public void unknownBracketShowsNoCostButStillMax() {
        List<String> rows = fmt(TeamUpgradeRows.rows(mock(), Layout.COMPACT, Bracket.UNKNOWN, 0));
        assertTrue(rows.contains("UPGRADE|Reinforced Armor|II||NORMAL"));
        assertTrue(rows.contains("UPGRADE|Heal Pool||Max|NORMAL"));
        assertTrue(rows.contains("NEXT|Next||4|NORMAL")); // trap costs are the same in every mode
    }

    @Test
    public void learnedShopCostBeatsTheTable() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.snapshot(TeamUpgradeShop.parse("Upgrades & Traps", TeamUpgradeShopTest.fullWindow()), 0);
        // the window carries Solo prices; the HUD thinks it is a 4-team game, the lore still wins
        List<String> rows = fmt(TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.FOUR_TEAM, 0));
        assertTrue(rows.toString(), rows.contains("UPGRADE|Reinforced Armor|II|8|NORMAL"));
        assertTrue(rows.toString(), rows.contains("UPGRADE|Iron Forge||4|NORMAL"));
        // and it shows even with no bracket at all
        rows = fmt(TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.UNKNOWN, 0));
        assertTrue(rows.toString(), rows.contains("UPGRADE|Reinforced Armor|II|8|NORMAL"));
    }

    @Test
    public void maxedTiersShowMax() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Reinforced Armor IV", 0);
        s.purchase("Molten Forge", 0);
        s.purchase("Cushioned Boots II", 0);
        assertEquals(Arrays.asList(
                "HEADER|Upgrades|||NORMAL",
                "UPGRADE|Reinforced Armor|IV|Max|NORMAL",
                "UPGRADE|Molten Forge||Max|NORMAL",
                "UPGRADE|Cushioned Boots|II|Max|NORMAL",
                "HEADER|Traps|||NORMAL",
                "NONE|None|||NORMAL"),
                fmt(TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.EIGHT_TEAM, 0)));
    }

    @Test
    public void emptyStateInCompactIsJustTheTrapWarning() {
        assertEquals(Arrays.asList("HEADER|Traps|||NORMAL", "NONE|None|||NORMAL"),
                fmt(TeamUpgradeRows.rows(new TeamUpgradeState(), Layout.COMPACT, Bracket.EIGHT_TEAM, 0)));
    }

    @Test
    public void trapCostsOneTwoFourThenFull() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Blindness Trap", 0);
        assertTrue(fmt(TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.EIGHT_TEAM, 0)).contains("NEXT|Next||2|NORMAL"));
        s.purchase("Blindness Trap", 0);
        assertTrue(fmt(TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.EIGHT_TEAM, 0)).contains("NEXT|Next||4|NORMAL"));
        s.purchase("Miner Fatigue Trap", 0);
        List<String> rows = fmt(TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.EIGHT_TEAM, 0));
        assertEquals(Arrays.asList(
                "HEADER|Traps|||NORMAL",
                "TRAP|1 Blindness|||NORMAL",
                "TRAP|2 Blindness|||NORMAL",
                "TRAP|3 Miner Fatigue|||NORMAL",
                "NEXT|Next||Full|NORMAL"), rows);
    }

    @Test
    public void fullListsAllSixWithUnownedDimmedAtTierOneCost() {
        assertEquals(Arrays.asList(
                "HEADER|Upgrades|||NORMAL",
                "UPGRADE|Sharpened Swords||Max|NORMAL",
                "UPGRADE|Reinforced Armor|II|8|NORMAL",
                "UPGRADE|Maniac Miner||2|DIM",
                "UPGRADE|Iron Forge||4|NORMAL",
                "UPGRADE|Heal Pool||Max|NORMAL",
                "UPGRADE|Cushioned Boots||1|DIM",
                "HEADER|Traps|||NORMAL",
                "TRAP|1 Blindness|||NORMAL",
                "TRAP|2 Counter-Offensive|||NORMAL",
                "TRAP|3 —|||DIM",
                "NEXT|Next||4|NORMAL"),
                fmt(TeamUpgradeRows.rows(mock(), Layout.FULL, Bracket.EIGHT_TEAM, 0)));
    }

    @Test
    public void fullOnAnEmptyStateHasAFixedShape() {
        assertEquals(Arrays.asList(
                "HEADER|Upgrades|||NORMAL",
                "UPGRADE|Sharpened Swords||8|DIM",
                "UPGRADE|Reinforced Armor||5|DIM",
                "UPGRADE|Maniac Miner||4|DIM",
                "UPGRADE|Forge||4|DIM",
                "UPGRADE|Heal Pool||3|DIM",
                "UPGRADE|Cushioned Boots||2|DIM",
                "HEADER|Traps|||NORMAL",
                "TRAP|1 —|||DIM",
                "TRAP|2 —|||DIM",
                "TRAP|3 —|||DIM",
                "NEXT|Next||1|NORMAL"),
                fmt(TeamUpgradeRows.rows(new TeamUpgradeState(), Layout.FULL, Bracket.FOUR_TEAM, 0)));
        List<String> unknown = fmt(TeamUpgradeRows.rows(new TeamUpgradeState(), Layout.FULL, Bracket.UNKNOWN, 0));
        assertTrue(unknown.contains("UPGRADE|Heal Pool|||DIM"));
    }

    @Test
    public void legacyDragonBuffOnlyShowsWhenOwned() {
        List<String> full = fmt(TeamUpgradeRows.rows(new TeamUpgradeState(), Layout.FULL, Bracket.EIGHT_TEAM, 0));
        for (String r : full) assertTrue(r, !r.contains("Dragon Buff"));
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Dragon Buff", 0);
        assertTrue(fmt(TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.EIGHT_TEAM, 0))
                .contains("UPGRADE|Dragon Buff||Max|NORMAL"));
    }

    @Test
    public void unknownUpgradeShowsByItsOwnNameAfterKnownOnes() {
        List<Slot> slots = new ArrayList<Slot>(TeamUpgradeShopTest.fullWindow());
        slots.add(TeamUpgradeShopTest.slot("§aDeadShot II", 2,
                "§aTier 1: 10% more damage§7, §b2 Diamonds",
                "§7Tier 2: 20% more damage§7, §b4 Diamonds"));
        slots.add(TeamUpgradeShopTest.slot("§eLucky Charm", 1, "§7Cost: §b3 Diamonds"));
        TeamUpgradeState s = new TeamUpgradeState();
        s.snapshot(TeamUpgradeShop.parse("Upgrades & Traps", slots), 0);
        List<String> compact = fmt(TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.EIGHT_TEAM, 0));
        assertEquals("UPGRADE|DeadShot|I|4|NORMAL", compact.get(4));
        assertTrue(!compact.toString().contains("Lucky Charm"));
        List<String> full = fmt(TeamUpgradeRows.rows(s, Layout.FULL, Bracket.EIGHT_TEAM, 0));
        assertEquals("UPGRADE|DeadShot|I|4|NORMAL", full.get(7));
        assertEquals("UPGRADE|Lucky Charm||3|DIM", full.get(8));
    }

    @Test
    public void rotatingSwordsTierTwoShowsItsTierAndCost() {
        List<Slot> slots = new ArrayList<Slot>();
        slots.add(TeamUpgradeShopTest.slot("§eSharpened Swords II", 1,
                "§aTier 1: Sharpness I§7, §b4 Diamonds",
                "§7Tier 2: Sharpness II§7, §b16 Diamonds"));
        TeamUpgradeState s = new TeamUpgradeState();
        s.snapshot(TeamUpgradeShop.parse("Upgrades & Traps", slots), 0);
        assertTrue(fmt(TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.EIGHT_TEAM, 0))
                .contains("UPGRADE|Sharpened Swords|I|16|NORMAL"));
    }

    @Test
    public void firedTrapTurnsRedThenFadesOutAndTheRestMoveUp() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Blindness Trap", 0);
        s.purchase("Reveal Trap", 0);
        s.trapFired(null, 10000);
        List<Row> at0 = TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.EIGHT_TEAM, 10000);
        assertEquals(Arrays.asList(
                "HEADER|Traps|||NORMAL",
                "TRAP|1 Blindness|||FIRED",
                "TRAP|2 Reveal|||NORMAL",
                "NEXT|Next||2|NORMAL"), fmt(at0));
        assertEquals(1f, at0.get(1).alpha, 1e-6);
        List<Row> mid = TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.EIGHT_TEAM, 10750);
        assertEquals(Style.FIRED, mid.get(1).style);
        assertEquals(0.5f, mid.get(1).alpha, 1e-3);
        assertEquals(Arrays.asList(
                "HEADER|Traps|||NORMAL",
                "TRAP|1 Reveal|||NORMAL",
                "NEXT|Next||2|NORMAL"),
                fmt(TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.EIGHT_TEAM, 11500)));
    }

    @Test
    public void lastTrapFiringShowsTheFlashBeforeNone() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Miner Fatigue Trap", 0);
        s.trapFired("Miner Fatigue Trap", 5000);
        assertEquals(Arrays.asList("HEADER|Traps|||NORMAL", "TRAP|1 Miner Fatigue|||FIRED", "NEXT|Next||1|NORMAL"),
                fmt(TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.EIGHT_TEAM, 5100)));
        assertEquals(Arrays.asList("HEADER|Traps|||NORMAL", "NONE|None|||NORMAL"),
                fmt(TeamUpgradeRows.rows(s, Layout.COMPACT, Bracket.EIGHT_TEAM, 6500)));
    }

    @Test
    public void fullKeepsThreeSlotsDuringAFlash() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Blindness Trap", 0);
        s.purchase("Reveal Trap", 0);
        s.purchase("Miner Fatigue Trap", 0);
        s.trapFired(null, 10000);
        s.purchase("Counter-Offensive Trap", 10200); // bought during the flash: queue is full again
        List<String> rows = fmt(TeamUpgradeRows.rows(s, Layout.FULL, Bracket.EIGHT_TEAM, 10300));
        int traps = 0;
        for (String r : rows) if (r.startsWith("TRAP|")) traps++;
        assertEquals(3, traps);
    }

    @Test
    public void nullStateHasNoRows() {
        assertTrue(TeamUpgradeRows.rows(null, Layout.COMPACT, Bracket.EIGHT_TEAM, 0).isEmpty());
        assertTrue(TeamUpgradeRows.rows(null, Layout.FULL, Bracket.EIGHT_TEAM, 0).isEmpty());
    }

    @Test
    public void editHudExampleCompact() {
        assertEquals(Arrays.asList(
                "HEADER|Upgrades|||NORMAL",
                "UPGRADE|Sharpened Swords||Max|NORMAL",
                "UPGRADE|Reinforced Armor|II|8|NORMAL",
                "UPGRADE|Iron Forge||4|NORMAL",
                "UPGRADE|Heal Pool||Max|NORMAL",
                "HEADER|Traps|||NORMAL",
                "TRAP|1 Blindness|||NORMAL",
                "TRAP|2 Counter-Offensive|||NORMAL",
                "NEXT|Next||4|NORMAL"),
                fmt(TeamUpgradeRows.example(Layout.COMPACT)));
    }

    @Test
    public void editHudExampleFull() {
        assertEquals(Arrays.asList(
                "HEADER|Upgrades|||NORMAL",
                "UPGRADE|Sharpened Swords||Max|NORMAL",
                "UPGRADE|Reinforced Armor|II|8|NORMAL",
                "UPGRADE|Maniac Miner||2|DIM",
                "UPGRADE|Iron Forge||4|NORMAL",
                "UPGRADE|Heal Pool||Max|NORMAL",
                "UPGRADE|Cushioned Boots||1|DIM",
                "HEADER|Traps|||NORMAL",
                "TRAP|1 Blindness|||NORMAL",
                "TRAP|2 Counter-Offensive|||NORMAL",
                "TRAP|3 —|||DIM",
                "NEXT|Next||4|NORMAL"),
                fmt(TeamUpgradeRows.example(Layout.FULL)));
    }

    @Test
    public void exampleFullIsAtLeastAsTallAsCompact() {
        assertTrue(TeamUpgradeRows.example(Layout.FULL).size() >= TeamUpgradeRows.example(Layout.COMPACT).size());
    }
}
