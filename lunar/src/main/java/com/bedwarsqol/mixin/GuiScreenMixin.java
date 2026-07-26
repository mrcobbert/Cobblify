package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.ChatHoverStats;
import com.bedwarsqol.feature.OutgoingChat;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Injects BedWars stats into the chat hover card. {@code handleComponentHover} is vanilla's hook for
 * rendering a chat component's SHOW_TEXT tooltip (Hypixel's rank card); we intercept it for chat
 * screens, merge in the hovered player's stats, draw the combined card ourselves, and cancel the
 * vanilla draw so there is a single tooltip. See {@link ChatHoverStats}.
 *
 * <p>Also notifies {@link OutgoingChat} on every chat-GUI submit so client commands still
 * cancel optional automation and open the quiet window.
 */
@Mixin(GuiScreen.class)
public abstract class GuiScreenMixin {

    @Shadow protected abstract void drawHoveringText(List<String> textLines, int x, int y);

    @Inject(method = "handleComponentHover", at = @At("HEAD"), cancellable = true)
    private void bedwarsqol$chatHoverStats(IChatComponent component, int x, int y, CallbackInfo ci) {
        if (!((Object) this instanceof GuiChat)) return;
        List<String> lines = ChatHoverStats.buildTooltip(component);
        if (lines == null || lines.isEmpty()) return;
        this.drawHoveringText(lines, x, y);
        ci.cancel();
    }

    @Inject(method = "sendChatMessage(Ljava/lang/String;Z)V", at = @At("HEAD"))
    private void bedwarsqol$outgoingUserIntent(String msg, boolean addToChat, CallbackInfo ci) {
        if (!((Object) this instanceof GuiChat)) return;
        if (OutgoingChat.get().isPassthrough()) return;
        OutgoingChat.get().onUserIntent(msg);
    }
}
