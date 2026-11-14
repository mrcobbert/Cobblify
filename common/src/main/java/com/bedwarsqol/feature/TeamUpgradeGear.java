package com.bedwarsqol.feature;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimum team upgrade tiers read off the player's own permanent armour and effects (PLAN "Gear
 * inference"), so a rejoin with no chat and no shop look still shows them.
 *
 * <ul>
 *   <li>Reinforced Armor = the highest Protection over the four armour pieces (at tier I Hypixel
 *       updates the helmet only on respawn). Armour is permanent and cannot be dropped, so no other
 *       team's armour can reach the player.</li>
 *   <li>Cushioned Boots = Feather Falling on the boots.</li>
 *   <li>Maniac Miner = Haste amplifier + 1, Heal Pool = Regeneration, each only at a duration of at
 *       least {@link #EFFECT_MIN_TICKS}: Hypixel sends 72000 / 32767, the 1.8.9 packet caps at 32767
 *       and the client counts down between once-a-second refreshes; potions and golden apples are far
 *       shorter.</li>
 *   <li>Never swords: a sword keeps its own Sharpness when dropped and picked up (review B1), so it
 *       is not an input at all.</li>
 *   <li>Only the five standard modes; elsewhere the result is empty.</li>
 * </ul>
 * The result only ever raises tiers ({@link TeamUpgradeState#gear}), and the state ignores it once a
 * shop snapshot has been applied this game.
 */
public final class TeamUpgradeGear {

    private TeamUpgradeGear() {}

    /** Shortest Haste/Regeneration duration that can only be the team upgrade. */
    public static final int EFFECT_MIN_TICKS = 30000;

    /**
     * Upgrade key to minimum owned tier.
     *
     * @param armorProtection Protection level of each worn armour piece (any length, null = none)
     * @param bootsFeatherFalling Feather Falling level on the boots, 0 when none
     * @param hasteAmplifier Haste amplifier (0 = Haste I), negative when there is no Haste effect
     * @param hasteTicks remaining Haste duration in ticks
     * @param regenTicks remaining Regeneration duration in ticks, 0 when there is none
     * @param mode Mod API location mode
     */
    public static Map<String, Integer> infer(int[] armorProtection, int bootsFeatherFalling,
                                             int hasteAmplifier, int hasteTicks, int regenTicks, String mode) {
        if (!TeamUpgradeCatalog.isStandardMode(mode)) return Collections.emptyMap();
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        int prot = 0;
        if (armorProtection != null) for (int p : armorProtection) prot = Math.max(prot, p);
        put(out, TeamUpgradeCatalog.REINFORCED_ARMOR, prot);
        if (hasteAmplifier >= 0 && hasteTicks >= EFFECT_MIN_TICKS) {
            put(out, TeamUpgradeCatalog.MANIAC_MINER, hasteAmplifier + 1);
        }
        if (regenTicks >= EFFECT_MIN_TICKS) put(out, TeamUpgradeCatalog.HEAL_POOL, 1);
        put(out, TeamUpgradeCatalog.CUSHIONED_BOOTS, bootsFeatherFalling);
        return out;
    }

    private static void put(Map<String, Integer> out, String key, int level) {
        if (level <= 0) return;
        int max = TeamUpgradeCatalog.upgrade(key).baseTiers;
        out.put(key, Math.min(level, max));
    }
}
