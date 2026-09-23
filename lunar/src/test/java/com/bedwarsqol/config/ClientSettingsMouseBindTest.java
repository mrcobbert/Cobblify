package com.bedwarsqol.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Lunar's KeybindRegistry copies a Controls rebind into the config and saves; a mouse bind is
 * {@code -100 + button}. sanitize() must keep it, or every save reverts it and the registry saves
 * again next tick.
 */
public class ClientSettingsMouseBindTest {

    @Test
    public void sanitizeKeepsMouseBinds() {
        ClientSettings s = new ClientSettings();
        s.settingsKeyCode = -97;
        s.pauseKeyCode = -98;
        s.pcIncKeyCode = -96;
        s.playersKeyCode = -100;
        s.sanitize();
        assertEquals(-97, s.settingsKeyCode);
        assertEquals(-98, s.pauseKeyCode);
        assertEquals(-96, s.pcIncKeyCode);
        assertEquals(-100, s.playersKeyCode);
    }

    @Test
    public void sanitizeStillResetsCodesBelowTheMouseRange() {
        ClientSettings s = new ClientSettings();
        s.settingsKeyCode = -101;
        s.pauseKeyCode = -500;
        s.pcIncKeyCode = Integer.MIN_VALUE;
        s.playersKeyCode = -200;
        s.sanitize();
        assertEquals(54, s.settingsKeyCode); // Keyboard.KEY_RSHIFT
        assertEquals(0, s.pauseKeyCode);
        assertEquals(0, s.pcIncKeyCode);
        assertEquals(0, s.playersKeyCode);
    }
}
