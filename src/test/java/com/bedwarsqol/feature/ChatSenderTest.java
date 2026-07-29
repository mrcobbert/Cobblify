package com.bedwarsqol.feature;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regressions for the {@code /party list} roster shape. Its head reads like a channel prefix
 * ("Party Leader: …") but its names sit AFTER the colon, so the channel branch used to return the
 * label word — and because the accounts {@code Leader}, {@code Moderators} and {@code Members} all
 * exist, the line showed a stranger's FKDR instead of the party member's.
 */
public class ChatSenderTest {

    private static IChatComponent line(String text) {
        return new ChatComponentText(text);
    }

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
}
