package com.bedwarsqol.mixin;

import com.bedwarsqol.ornithe.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client commands: a chat submit naming one of Cobblify's commands runs it locally and never reaches
 * the server. Same position as Forge's {@code ClientCommandHandler} call: after the line is added
 * to the sent-message history, before {@code EntityPlayerSP.sendChatMessage}.
 */
@Mixin(GuiScreen.class)
public abstract class GuiScreenCommandMixin {

    @Shadow public Minecraft mc;

    @Inject(method = "sendChatMessage(Ljava/lang/String;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/EntityPlayerSP;sendChatMessage(Ljava/lang/String;)V"),
            cancellable = true, require = 1)
    private void bedwarsqol$clientCommand(String msg, boolean addToChat, CallbackInfo ci) {
        if (mc != null && ClientCommands.execute(mc.thePlayer, msg)) ci.cancel();
    }
}
