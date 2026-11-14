package com.bedwarsqol.feature;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins gear inference (PLAN "Gear inference", review B1 and round 2 I1): permanent armour and the
 * team's permanent effects raise four upgrades; swords are never an input, so Sharpness can never
 * raise anything; only the five standard modes; Haste/Regeneration count only at durations of at
 * least 30000 ticks.
 */
public class TeamUpgradeGearTest {

    private static final String SOLO = "BEDWARS_EIGHT_ONE";

    @Test
    public void protectionIsTheMaxOverTheFourPieces() {
        assertEquals(Integer.valueOf(1), TeamUpgradeGear.infer(new int[]{0, 1, 0, 0}, 0, -1, 0, 0, SOLO)
                .get("Reinforced Armor")); // tier I: only some pieces update until respawn
        assertEquals(Integer.valueOf(3), TeamUpgradeGear.infer(new int[]{3, 2, 3, 1}, 0, -1, 0, 0, SOLO)
                .get("Reinforced Armor"));
        assertNull(TeamUpgradeGear.infer(new int[]{0, 0, 0, 0}, 0, -1, 0, 0, SOLO).get("Reinforced Armor"));
        assertEquals(Integer.valueOf(4), TeamUpgradeGear.infer(new int[]{7, 0, 0, 0}, 0, -1, 0, 0, SOLO)
                .get("Reinforced Armor")); // capped at the catalogue's last tier
        assertNull(TeamUpgradeGear.infer(null, 0, -1, 0, 0, SOLO).get("Reinforced Armor"));
    }

    @Test
    public void featherFallingOnBootsGivesCushionedBoots() {
        assertEquals(Integer.valueOf(2), TeamUpgradeGear.infer(null, 2, -1, 0, 0, SOLO).get("Cushioned Boots"));
        assertEquals(Integer.valueOf(1), TeamUpgradeGear.infer(null, 1, -1, 0, 0, SOLO).get("Cushioned Boots"));
        assertNull(TeamUpgradeGear.infer(null, 0, -1, 0, 0, SOLO).get("Cushioned Boots"));
    }

    @Test
    public void hasteAtTeamDurationsGivesManiacMiner() {
        assertEquals(Integer.valueOf(1), TeamUpgradeGear.infer(null, 0, 0, 72000, 0, SOLO).get("Maniac Miner"));
        assertEquals(Integer.valueOf(2), TeamUpgradeGear.infer(null, 0, 1, 32767, 0, SOLO).get("Maniac Miner"));
        assertEquals(Integer.valueOf(2), TeamUpgradeGear.infer(null, 0, 1, 32747, 0, SOLO).get("Maniac Miner"));
        assertEquals(Integer.valueOf(1), TeamUpgradeGear.infer(null, 0, 0, 30000, 0, SOLO).get("Maniac Miner"));
    }

    @Test
    public void shortHasteDoesNotCount() {
        assertNull(TeamUpgradeGear.infer(null, 0, 1, 600, 0, SOLO).get("Maniac Miner"));
        assertNull(TeamUpgradeGear.infer(null, 0, 1, 1200, 0, SOLO).get("Maniac Miner"));
        assertNull(TeamUpgradeGear.infer(null, 0, 0, 29999, 0, SOLO).get("Maniac Miner"));
        assertNull(TeamUpgradeGear.infer(null, 0, -1, 72000, 0, SOLO).get("Maniac Miner"));
    }

    @Test
    public void regenerationAtTeamDurationsGivesHealPool() {
        assertEquals(Integer.valueOf(1), TeamUpgradeGear.infer(null, 0, -1, 0, 72000, SOLO).get("Heal Pool"));
        assertEquals(Integer.valueOf(1), TeamUpgradeGear.infer(null, 0, -1, 0, 32767, SOLO).get("Heal Pool"));
        assertEquals(Integer.valueOf(1), TeamUpgradeGear.infer(null, 0, -1, 0, 32747, SOLO).get("Heal Pool"));
    }

    @Test
    public void potionAndGoldenAppleRegenerationDoNotCount() {
        assertNull(TeamUpgradeGear.infer(null, 0, -1, 0, 600, SOLO).get("Heal Pool"));
        assertNull(TeamUpgradeGear.infer(null, 0, -1, 0, 1200, SOLO).get("Heal Pool"));
        assertNull(TeamUpgradeGear.infer(null, 0, -1, 0, 0, SOLO).get("Heal Pool"));
    }

    @Test
    public void onlyTheFiveStandardModes() {
        for (String mode : new String[]{"BEDWARS_EIGHT_ONE", "BEDWARS_EIGHT_TWO", "BEDWARS_FOUR_THREE",
                "BEDWARS_FOUR_FOUR", "BEDWARS_TWO_FOUR"}) {
            assertFalse(mode, TeamUpgradeGear.infer(new int[]{2, 2, 2, 2}, 0, -1, 0, 0, mode).isEmpty());
        }
        for (String mode : new String[]{"BEDWARS_EIGHT_TWO_RUSH", "BEDWARS_CASTLE", "BEDWARS_TWO_ONE_DUELS",
                "BEDWARS_FOUR_FOUR_ULTIMATE", null}) {
            assertTrue(String.valueOf(mode),
                    TeamUpgradeGear.infer(new int[]{4, 4, 4, 4}, 2, 1, 72000, 32767, mode).isEmpty());
        }
    }

    /**
     * B1 counterexample: a sword picked up from another team keeps its own Sharpness, so Sharpness
     * proves nothing. The inference has no sword input at all, and no reading of every input it does
     * have can ever produce Sharpened Swords.
     */
    @Test
    public void sharpnessCanNeverRaiseAnything() {
        for (Method m : TeamUpgradeGear.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            assertFalse(m.getName(), n.contains("sword") || n.contains("sharp"));
        }
        for (int prot = 0; prot <= 4; prot++) {
            for (int amp = -1; amp <= 1; amp++) {
                Map<String, Integer> r = TeamUpgradeGear.infer(new int[]{prot, prot, prot, prot}, 2, amp, 72000, 32767, SOLO);
                assertFalse(r.containsKey("Sharpened Swords"));
            }
        }
        TeamUpgradeState s = new TeamUpgradeState();
        s.gear(TeamUpgradeGear.infer(new int[]{4, 4, 4, 4}, 2, 1, 72000, 32767, SOLO));
        assertEquals(0, s.tier("Sharpened Swords"));
        assertEquals(4, s.tier("Reinforced Armor"));
    }

    @Test
    public void gearNeverLowersAndStopsAfterASnapshot() {
        TeamUpgradeState s = new TeamUpgradeState();
        s.purchase("Reinforced Armor III", 0);
        s.gear(TeamUpgradeGear.infer(new int[]{1, 1, 1, 1}, 0, -1, 0, 0, SOLO));
        assertEquals(3, s.tier("Reinforced Armor"));

        TeamUpgradeState t = new TeamUpgradeState();
        t.snapshot(TeamUpgradeShop.parse("Upgrades & Traps", TeamUpgradeShopTest.fullWindow()), 0);
        t.gear(TeamUpgradeGear.infer(new int[]{4, 4, 4, 4}, 2, 1, 72000, 32767, SOLO));
        assertEquals(2, t.tier("Reinforced Armor"));
        assertEquals(0, t.tier("Maniac Miner"));
        assertEquals(0, t.tier("Cushioned Boots"));
    }
}
