package com.bedwarsqol.mixin;

import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Rewind's recorder reads what is typed in the open chat bar (PLAN section A "CHAT_INPUT"): vanilla's protected
 * {@code GuiChat.inputField}. Only {@code com.bedwarsqol.rewind.ViewCapture} reads it, once per frame while chat is open
 * and a game is recorded; the text goes only into the local recording, never into stats or logs.
 */
@Mixin(GuiChat.class)
public interface GuiChatRewindAccessor {

    @Accessor("inputField")
    GuiTextField bedwarsqol$getInputField();
}
