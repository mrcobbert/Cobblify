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

    @Test
    public void membershipStartsUnknownAndFallsToRejectionOrLeave() {
        PartyEpoch epoch = new PartyEpoch();
        assertTrue(epoch.inParty()); // unknown allows sending

        // All three rejection wordings seen in real logs; each tells us the state, changes nothing.
        for (String rejection : new String[] {
                "You are not in a party.",
                "You are not in a party right now.",
                "You are not currently in a party." }) {
            assertFalse(epoch.observeChat("Party > Steve: rejoin"));
            assertTrue(epoch.inParty());
            assertFalse(epoch.observeChat(rejection));
            assertFalse("not matched: " + rejection, epoch.inParty());
            assertEquals(0, epoch.current());
        }

        assertTrue(epoch.observeChat("You left the party."));
        assertFalse(epoch.inParty());
        assertEquals(1, epoch.current());
    }

    @Test
    public void selfJoinRestoresMembership() {
        PartyEpoch epoch = new PartyEpoch();
        assertTrue(epoch.observeChat("You left the party."));
        assertFalse(epoch.inParty());
        assertFalse(epoch.observeChat("You have joined StunrunWasTaken's party!"));
        assertTrue(epoch.inParty());
        assertTrue(epoch.observeChat("You left the party."));
        assertFalse(epoch.observeChat("You have joined [VIP+] SpacePandaRemix's party!"));
        assertTrue(epoch.inParty());
    }

    @Test
    public void disbandVariantWithTrailingReasonStillEndsTheParty() {
        PartyEpoch epoch = new PartyEpoch();
        assertTrue(epoch.observeChat(
                "The party was disbanded because all invites expired and the party was empty."));
        assertFalse(epoch.inParty());
        assertEquals(1, epoch.current());
    }

    @Test
    public void ourOwnFkdrPrefixDoesNotHidePartyChat() {
        PartyEpoch epoch = new PartyEpoch();
        assertFalse(epoch.observeChat("You are not in a party."));
        assertFalse(epoch.inParty());
        // ChatNameTags prepends its bracket ahead of the line — seen in real logs.
        assertFalse(epoch.observeChat("[7.19] Party > [VIP] wnmv: i died"));
        assertTrue(epoch.inParty());
    }

    @Test
    public void partyChatIsNeverReadAsASystemLine() {
        PartyEpoch epoch = new PartyEpoch();
        // A member typing a system-looking message must not end the party.
        assertFalse(epoch.observeChat("Party > Steve: The party was disbanded"));
        assertFalse(epoch.observeChat("Party > Steve: You are not in a party right now."));
        assertFalse(epoch.observeChat("[7.19] Party > Steve: You left the party."));
        assertEquals(0, epoch.current());
        assertTrue(epoch.inParty());
    }

    @Test
    public void lobbyChatCannotSpoofMembershipSignals() {
        PartyEpoch epoch = new PartyEpoch();
        // Real log shapes: rank/level/shout prefixes strip away, but the name+colon head remains.
        assertFalse(epoch.observeChat("[83✫] [VIP] ixdine: you are not in a party"));
        assertFalse(epoch.observeChat("[SHOUT] [GREEN] [MVP+] UrBeds: Party > fake"));
        assertFalse(epoch.observeChat("[MVP++] Linkze: you left the party"));
        assertEquals(0, epoch.current());
        assertTrue(epoch.inParty());
    }
}
