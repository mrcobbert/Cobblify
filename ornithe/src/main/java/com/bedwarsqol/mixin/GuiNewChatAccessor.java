package com.bedwarsqol.mixin;

import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Rewind playback saves the three chat lists when it starts and puts them back when it ends, so the chat from before
 * playback returns with the ages it had (PLAN section 3 "Exit", round 2 B2). Only {@code RewindPlayback} uses it.
 */
@Mixin(GuiNewChat.class)
public interface GuiNewChatAccessor {

    @Accessor("chatLines")
    List<ChatLine> bedwarsqol$getChatLines();

    @Accessor("drawnChatLines")
    List<ChatLine> bedwarsqol$getDrawnChatLines();

    @Accessor("sentMessages")
    List<String> bedwarsqol$getSentMessages();
}
