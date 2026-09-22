package com.bedwarsqol.bedwars;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the generator-upgrade tier read (F7). Only a line nobody typed can re-anchor the generator
 * timer: a teammate saying "we should upgrade the diamond generator to 2" used to move the countdown,
 * because the tracker filtered on words alone.
 */
public class GeneratorUpgradeParseTest {

    @Test
    public void diamondBroadcastGivesItsRomanTier() {
        assertEquals(2, GeneratorUpgradeParse.tier("Diamond Generators have been upgraded to Tier II", null));
    }

    @Test
    public void emeraldBroadcastGivesItsRomanTier() {
        assertEquals(3, GeneratorUpgradeParse.tier("Emerald Generators have been upgraded to Tier III", null));
    }

    @Test
    public void aDigitTierIsAccepted() {
        assertEquals(2, GeneratorUpgradeParse.tier("Diamond Generators have been upgraded to Tier 2", null));
    }

    @Test
    public void tierFourIsReadAsFour() {
        // Pins today's parser: the roman token table covers IV, and Hypixel has no Tier IV generator,
        // so this documents the value rather than endorsing it.
        assertEquals(4, GeneratorUpgradeParse.tier("Diamond Generators have been upgraded to Tier IV", null));
    }

    @Test
    public void aTypedLineIsNeverABroadcast() {
        assertEquals(-1, GeneratorUpgradeParse.tier(
                "[RED] Steve: we should upgrade the diamond generator to 2", "Steve"));
    }

    @Test
    public void aTypedLineWithoutItsHeadIsStillRefused() {
        // The sender is what disqualifies the line, not the rank/channel prefix in front of it.
        assertEquals(-1, GeneratorUpgradeParse.tier("upgrade the diamond generator to 2", "Steve"));
    }

    @Test
    public void anUnrelatedBroadcastHasNoTier() {
        assertEquals(-1, GeneratorUpgradeParse.tier("Hello", null));
    }

    @Test
    public void nullLineHasNoTier() {
        assertEquals(-1, GeneratorUpgradeParse.tier(null, null));
    }
}
