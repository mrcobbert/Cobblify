package com.bedwarsqol.stats;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The strict Hypixel host-boundary gate used only by Urchin: exactly {@code hypixel.net} or a
 * {@code *.hypixel.net} subdomain (suffix boundary, never a substring), so spoof hosts get zero traffic.
 */
public class HostBoundaryTest {

    @Test
    public void acceptsHypixelHosts() {
        assertTrue(EligibilitySnapshot.isExactHypixelHost("hypixel.net"));
        assertTrue(EligibilitySnapshot.isExactHypixelHost("mc.hypixel.net"));
        assertTrue(EligibilitySnapshot.isExactHypixelHost("HYPIXEL.NET"));
        assertTrue(EligibilitySnapshot.isExactHypixelHost("mc.hypixel.net:25565"));
    }

    @Test
    public void rejectsSpoofHosts() {
        assertFalse(EligibilitySnapshot.isExactHypixelHost("hypixel.net.example"));
        assertFalse(EligibilitySnapshot.isExactHypixelHost("evil-hypixel.example"));
        assertFalse(EligibilitySnapshot.isExactHypixelHost("nothypixel.net"));
        assertFalse(EligibilitySnapshot.isExactHypixelHost("hypixel.network"));
        assertFalse(EligibilitySnapshot.isExactHypixelHost(null));
    }
}
