package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiNewChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keep Chat History. Vanilla clears chat in exactly two places: {@code displayGuiScreen} when the new
 * screen is the main menu (every disconnect and server switch passes through it) and the F3+D handler
 * in {@code runTick}. Redirecting only the {@code displayGuiScreen} call site keeps history across
 * server/lobby switches while leaving the deliberate F3+D clear fully functional.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftChatClearMixin {

    @Redirect(method = "displayGuiScreen",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiNewChat;clearChatMessages()V"))
    private void bedwarsqol$keepChatHistory(GuiNewChat chat) {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.chatKeepHistory) chat.clearChatMessages();
    }
}
