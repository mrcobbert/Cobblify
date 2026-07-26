package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pins conservative party-epoch bumps for pending /pc automation. */
public class PartyEpochTest {

    @Test
    public void bumpsOnLeaveKickAndDisband() {
        PartyEpoch epoch = new PartyEpoch();
        assertEquals(0, epoch.current());
        assertTrue(epoch.observeChat("You left the party."));
        assertEquals(1, epoch.current());
        assertTrue(epoch.observeChat("The party was disbanded"));
        assertEquals(2, epoch.current());
        assertTrue(epoch.observeChat("You have been kicked from the party"));
        assertEquals(3, epoch.current());
        assertTrue(epoch.observeChat("Steve has disbanded the party!"));
        assertEquals(4, epoch.current());
    }

    @Test
    public void ignoresInviteExpiryTransferAndPartyChat() {
        PartyEpoch epoch = new PartyEpoch();
        assertFalse(epoch.observeChat("The party invite from Steve has expired."));
        assertFalse(epoch.observeChat("The party was transferred to Steve"));
        assertFalse(epoch.observeChat("Party > Steve: hello"));
        assertFalse(epoch.observeChat("Steve has joined (3/16)!"));
        assertFalse(epoch.observeChat(null));
        assertEquals(0, epoch.current());
    }
}
