package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertFalse;

/** Item Physics is off by default. */
public class ItemPhysicsRulesTest {

    @Test
    public void offByDefault() {
        assertFalse(ItemPhysicsRules.DEFAULT_ENABLED);
    }
}
