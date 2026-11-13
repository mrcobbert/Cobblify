package com.bedwarsqol.feature;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regressions for the {@code /party list} roster shape and the bare-head rule.
 *
 * <p>Roster: its head reads like a channel prefix ("Party Leader: …") but its names sit AFTER the
 * colon, so the channel branch used to return the label word — and because the accounts
 * {@code Leader}, {@code Moderators} and {@code Members} all exist, the line showed a stranger's FKDR
 * instead of the party member's.
 *
 * <p>Bare head: a Default-rank player's line ({@code "Steve: hi"}) has no bracket to prove it is a
 * player, so it is corroborated against the tab list — which in the pregame queue holds only junk
 * names, so every unranked player there silently went untagged. The rule now trusts the bare shape in
 * the anonymized queue (label words excepted) and nowhere else.
 */
public class ChatSenderTest {

    private static IChatComponent line(String text) {
        return new ChatComponentText(text);
    }

    /** A tab list that vouches for no one — the pregame queue's, or a player who isn't in yours. */
    private static final Predicate<String> NOBODY_IN_TAB = name -> false;
    /** A tab list that has the player. */
    private static final Predicate<String> STEVE_IN_TAB = name -> name.equalsIgnoreCase("Steve");

    // ---- roster lines have no sender ---------------------------------------------------------------

    @Test
    public void rosterLabelsAreNotSenders() {
        assertNull(ChatSender.extractName(line("Party Leader: [VIP] SrCobb ●")));
        assertNull(ChatSender.extractName(line("Party Moderators: [MVP+] wnmv ●")));
        assertNull(ChatSender.extractName(line("Party Members: [VIP] wnmv ● Zebra ●")));
    }

    /** typedChatName feeds the INC/queue alerts; a roster line is not something a player typed. */
    @Test
    public void rosterLabelsAreNotTypedChatEither() {
        assertNull(ChatSender.typedChatName(line("Party Leader: [VIP] SrCobb ●")));
    }

    /** The hover path re-parses lines we already tagged, so the label must survive a leading bracket. */
    @Test
    public void rosterLabelIsStillRecognisedUnderOurOwnTag() {
        assertNull(ChatSender.extractName(line("[3.10] Party Leader: [VIP] SrCobb ●")));
    }

    // ---- neighbouring shapes still parse -----------------------------------------------------------

    @Test
    public void partyChatStillNamesItsSender() {
        assertEquals("wnmv", ChatSender.extractName(line("Party > [VIP] wnmv: i died")));
    }

    /** The '>' guard: a real player named Leader stays taggable in party chat. */
    @Test
    public void playerNamedLeaderIsStillTaggableInPartyChat() {
        assertEquals("Leader", ChatSender.extractName(line("Party > [VIP] Leader: hi")));
    }

    @Test
    public void whisperChannelsAreUnaffected() {
        assertEquals("Notch", ChatSender.extractName(line("From [MVP+] Notch: hi")));
        assertEquals("Notch", ChatSender.extractName(line("To [MVP+] Notch: hi")));
    }

    @Test
    public void guildChatIsUnaffected() {
        assertEquals("Notch", ChatSender.extractName(line("Guild > [MVP+] Notch: hi")));
    }

    // ---- member extraction --------------------------------------------------------------------------

    @Test
    public void rosterMembersReadsTheNameAfterTheColon() {
        assertEquals(Arrays.asList("SrCobb"),
                ChatSender.rosterMembers(line("Party Leader: [VIP] SrCobb ●")));
    }

    @Test
    public void rosterMembersReadsEveryNameInOrder() {
        List<String> names = ChatSender.rosterMembers(
                line("Party Members: [MVP+] wnmv ● Zebra ● [MVP++] Bob ●"));
        assertEquals(Arrays.asList("wnmv", "Zebra", "Bob"), names);
    }

    /** Rank tags are bracket spans, so their letters can never be mistaken for a member. */
    @Test
    public void rosterMembersDropsRankTags() {
        assertEquals(Arrays.asList("wnmv"),
                ChatSender.rosterMembers(line("Party Moderators: [MVP+] wnmv ●")));
    }

    @Test
    public void rosterMembersIsEmptyForEveryOtherLine() {
        assertTrue(ChatSender.rosterMembers(line("Party Members (2)")).isEmpty());
        assertTrue(ChatSender.rosterMembers(line("-----------------------------")).isEmpty());
        assertTrue(ChatSender.rosterMembers(line("Party > [VIP] wnmv: i died")).isEmpty());
        assertTrue(ChatSender.rosterMembers(line("[MVP+] Notch: gg wp")).isEmpty());
        assertTrue(ChatSender.rosterMembers(line("From [MVP+] Notch: hi")).isEmpty());
    }

    // ---- bare heads: tab-corroborated, except in the anonymized queue -------------------------------

    @Test
    public void bareHeadIsTrustedWhenTheTabVouchesForIt() {
        assertEquals("Steve", ChatSender.extractName(line("Steve: hi"), STEVE_IN_TAB, false));
        assertEquals("Steve", ChatSender.typedChatName(line("Steve: hi"), STEVE_IN_TAB, false));
    }

    @Test
    public void bareHeadOutsideTheQueueNeedsTheTab() {
        assertNull(ChatSender.extractName(line("Steve: hi"), NOBODY_IN_TAB, false));
        assertNull(ChatSender.typedChatName(line("Steve: hi"), NOBODY_IN_TAB, false));
    }

    /** The reported bug: an unranked player typing in the queue, whose tab row is junk. */
    @Test
    public void bareHeadInTheAnonymizedQueueIsTrustedWithoutTheTab() {
        assertEquals("Steve", ChatSender.extractName(line("Steve: hi"), NOBODY_IN_TAB, true));
        assertEquals("Steve", ChatSender.typedChatName(line("Steve: hi"), NOBODY_IN_TAB, true));
        // Formatting codes and a message that itself contains a colon change nothing.
        assertEquals("Steve", ChatSender.extractName(line("§7Steve§7: gg: wp"), NOBODY_IN_TAB, true));
    }

    @Test
    public void labelWordsAreNeverSendersEvenInTheQueue() {
        assertNull(ChatSender.extractName(line("Warning: you were kicked"), NOBODY_IN_TAB, true));
        assertNull(ChatSender.extractName(line("Cooldown: wait 3s"), NOBODY_IN_TAB, true));
        assertNull(ChatSender.extractName(line("TIP: press F"), NOBODY_IN_TAB, true));
        for (String w : ChatSender.LABEL_WORDS) {
            assertNull(w, ChatSender.senderFromHead(w, NOBODY_IN_TAB, true));
        }
        // A label word that IS in the tab is a player named that (in a lobby, say).
        assertEquals("Warning", ChatSender.senderFromHead("Warning", name -> true, false));
    }

    /** /locraw prints its JSON to chat; its head {"server" held the lone token "server". */
    @Test
    public void locrawJsonIsNotASenderInTheQueue() {
        String locraw = "{\"server\":\"mini77BW\",\"gametype\":\"BEDWARS\",\"mode\":\"BEDWARS_EIGHT_TWO\","
                + "\"map\":\"Lighthouse\"}";
        assertNull(ChatSender.senderFromHead("{\"server\"", NOBODY_IN_TAB, true));
        assertNull(ChatSender.extractName(line(locraw), NOBODY_IN_TAB, true));
        assertNull(ChatSender.typedChatName(line(locraw), NOBODY_IN_TAB, true));
        assertNull(ChatSender.extractName(line("{\"server\":\"limbo\"}"), NOBODY_IN_TAB, true));
    }

    /** In the queue the bare head must BE the name, not merely contain one. */
    @Test
    public void wrappedBareHeadsAreNotSendersInTheQueue() {
        for (String text : new String[] {
                "\"Steve\": hi", ">>> Steve: hi", "(Steve): hi", "+5 Tokens: earned", "Steve!: hi" }) {
            assertNull(text, ChatSender.extractName(line(text), NOBODY_IN_TAB, true));
            assertNull(text, ChatSender.typedChatName(line(text), NOBODY_IN_TAB, true));
        }
    }

    /** Typed chat is always "Name: msg"; a colon not followed by a space is a URL, a time or JSON. */
    @Test
    public void colonWithoutASpaceIsNotTypedChatInTheQueue() {
        assertNull(ChatSender.extractName(line("https://hypixel.net"), NOBODY_IN_TAB, true));
        assertNull(ChatSender.typedChatName(line("http://example.com/x"), NOBODY_IN_TAB, true));
        assertNull(ChatSender.extractName(line("Steve:hi"), NOBODY_IN_TAB, true));
        // The hover path hands over a trimmed name leaf "Steve:" with the body in a sibling.
        assertEquals("Steve", ChatSender.extractName(line("Steve: "), NOBODY_IN_TAB, true));
    }

    /** One-word server/mod labels that reach chat in the queue. */
    @Test
    public void moreLabelWordsAreNotSendersInTheQueue() {
        for (String text : new String[] {
                "Map: Lighthouse", "Mode: Solo", "Server: mini77BW", "Team: Red", "Status: online",
                "Queue: 5/8", "Game: starting", "Lobby: 3", "Store: store.hypixel.net",
                "Discord: discord.gg/x", "Party: none" }) {
            assertNull(text, ChatSender.extractName(line(text), NOBODY_IN_TAB, true));
        }
    }

    /** The tightening must not cost the shapes the queue rule exists for. */
    @Test
    public void realQueueChatStillResolves() {
        assertEquals("Steve", ChatSender.extractName(line("Steve: hi"), NOBODY_IN_TAB, true));
        assertEquals("Steve", ChatSender.extractName(line(" Steve : hi"), NOBODY_IN_TAB, true));
        assertEquals("Steve", ChatSender.extractName(line("[4.30] Steve: hi"), NOBODY_IN_TAB, true));
        assertEquals("Steve", ChatSender.extractName(line("[VIP] Steve: hi"), NOBODY_IN_TAB, true));
        assertEquals("Steve", ChatSender.extractName(line("Steve: hi"), STEVE_IN_TAB, false));
        assertEquals("Steve", ChatSender.extractName(line("Steve:hi"), STEVE_IN_TAB, false));
    }

    @Test
    public void multiWordBareHeadsStayRejectedEverywhere() {
        assertNull(ChatSender.extractName(line("Command Failed: no such player"), NOBODY_IN_TAB, true));
        assertNull(ChatSender.extractName(line("You are now: AFK"), NOBODY_IN_TAB, true));
    }

    /** The queue's anonymized broadcasts have no colon, so the new rule can never reach them. */
    @Test
    public void queueBroadcastsStillNameNoOne() {
        assertNull(ChatSender.extractName(line("vj3x1s4w18 has joined (5/8)!"), NOBODY_IN_TAB, true));
        assertNull(ChatSender.extractName(line("vj3x1s4w18 has quit!"), NOBODY_IN_TAB, true));
        assertNull(ChatSender.extractName(line("vj3x1s4w18 disconnected."), NOBODY_IN_TAB, true));
        assertNull(ChatSender.typedChatName(line("vj3x1s4w18 has joined (5/8)!"), NOBODY_IN_TAB, true));
    }

    /** Ranked and channel shapes never needed corroboration; the queue flag must not change them. */
    @Test
    public void bracketedAndChannelHeadsAreUnchangedByTheQueueFlag() {
        for (boolean queue : new boolean[]{false, true}) {
            assertEquals("Notch", ChatSender.extractName(line("[MVP+] Notch: hi"), NOBODY_IN_TAB, queue));
            assertEquals("Notch", ChatSender.extractName(line("Party > [VIP] Notch: hi"), NOBODY_IN_TAB, queue));
            assertEquals("Notch", ChatSender.extractName(line("From Notch: hi"), NOBODY_IN_TAB, queue));
            assertNull(ChatSender.extractName(line("Party Leader: [VIP] SrCobb ●"), NOBODY_IN_TAB, queue));
        }
    }

    // ---- hover on a message-body or suffix leaf (F1) -----------------------------------------------

    /** Hypixel puts the hover on the name leaf and the body "§f: hello" in a sibling; the mod's own
     *  " (Nicked)" suffix is another. Neither names a sender, whatever the tab or the queue says. */
    @Test
    public void bodyAndSuffixLeavesHaveNoSender() {
        for (String leaf : new String[] {
                ": hello", " (Nicked)", "(Nicked)", ": nice one", "§f: hello", " §7(§cNicked§7)" }) {
            assertNull("leaf: " + leaf, ChatSender.extractName(line(leaf), STEVE_IN_TAB, false));
            assertNull("leaf in queue: " + leaf, ChatSender.extractName(line(leaf), NOBODY_IN_TAB, true));
        }
    }

    @Test
    public void nameLeavesStillResolve() {
        assertEquals("Steve", ChatSender.extractName(line("[MVP+] Steve"), NOBODY_IN_TAB, false));
        assertEquals("Steve", ChatSender.extractName(line("Steve"), NOBODY_IN_TAB, false));
        assertEquals("Steve", ChatSender.extractName(line("§b[MVP§c+§b] Steve§f: hi"), NOBODY_IN_TAB, false));
        assertEquals("Steve", ChatSender.extractName(line("Party > [VIP] Steve: hi"), NOBODY_IN_TAB, false));
        // A decorated lone token is not the bare name leaf.
        assertNull(ChatSender.extractName(line("Steve ●"), NOBODY_IN_TAB, false));
    }
}
