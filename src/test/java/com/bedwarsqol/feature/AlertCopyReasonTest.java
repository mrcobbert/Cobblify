package com.bedwarsqol.feature;

import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AlertCopyReasonTest {

    @Test
    public void appendsPrimaryReasonToCopiedAlert() {
        ChatComponentText message = alert("Reach and velocity", true);
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(message.getFormattedText());

        assertEquals("[Urchin] Dream is a confirmed cheater \u2014 Reason: Reach and velocity",
                AlertCopyReason.appendToPlain(message, plain));
    }

    @Test
    public void appendsPrimaryReasonToCopiedSeraphAlert() {
        ChatComponentText hover =
                AlertCopyReason.attach(new ChatComponentText("hover"), "Verified report");
        ChatComponentText message = ModChat.mark(new ChatComponentText("[Seraph] Dream is blacklisted"));
        message.getChatStyle().setChatHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));

        assertEquals("[Seraph] Dream is blacklisted \u2014 Reason: Verified report",
                AlertCopyReason.appendToPlain(message, "[Seraph] Dream is blacklisted"));
    }

    @Test
    public void leavesBlankReasonAlertUnchanged() {
        ChatComponentText message = alert("", true);

        assertEquals("[Urchin] Dream is a confirmed cheater",
                AlertCopyReason.appendToPlain(message, "[Urchin] Dream is a confirmed cheater"));
    }

    @Test
    public void leavesOrdinaryChatUnchanged() {
        ChatComponentText message = alert("not for copied chat", false);

        assertEquals("hello", AlertCopyReason.appendToPlain(message, "hello"));
    }

    private static ChatComponentText alert(String reason, boolean marked) {
        ChatComponentText hover = AlertCopyReason.attach(new ChatComponentText("hover"), reason);
        ChatComponentText message =
                new ChatComponentText("\u00a78[\u00a76Urchin\u00a78] \u00a7cDream \u00a77is a confirmed cheater");
        message.getChatStyle().setChatHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
        return marked ? ModChat.mark(message) : message;
    }
}
