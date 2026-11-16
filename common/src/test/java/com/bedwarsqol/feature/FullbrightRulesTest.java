package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertFalse;

/** Fullbright is off by default. */
public class FullbrightRulesTest {

    @Test
    public void offByDefault() {
        assertFalse(FullbrightRules.DEFAULT_ENABLED);
    }
}
