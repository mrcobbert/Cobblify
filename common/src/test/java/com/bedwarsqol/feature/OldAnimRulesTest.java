package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 1.7 Animations: off by default with every row on; a row only counts while the module is on. */
public class OldAnimRulesTest {

    @Test
    public void moduleOffEveryRowOnByDefault() {
        assertFalse(OldAnimRules.DEFAULT_ENABLED);
        assertTrue(OldAnimRules.DEFAULT_BLOCK_HIT);
        assertTrue(OldAnimRules.DEFAULT_ITEM_POSITION);
        assertTrue(OldAnimRules.DEFAULT_SMOOTH_SNEAK);
        assertTrue(OldAnimRules.DEFAULT_HEARTS);
        assertTrue(OldAnimRules.DEFAULT_RED_ARMOR);
    }

    @Test
    public void aRowIsEffectiveOnlyWhileTheModuleIsOn() {
        assertTrue(OldAnimRules.effective(true, true));
        assertFalse(OldAnimRules.effective(true, false));
        assertFalse(OldAnimRules.effective(false, true));
        assertFalse(OldAnimRules.effective(false, false));
    }
}
