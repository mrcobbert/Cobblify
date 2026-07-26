package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.OutgoingChat;
import net.minecraft.client.entity.EntityPlayerSP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes every non-coordinator {@code sendChatMessage} through {@link OutgoingChat} so typed chat
 * shares pacing/priority with IncSender / SweatReport / AutoGG and is never silently lost when a
 * send lands inside the global gap.
 */
@Mixin(EntityPlayerSP.class)
public abstract class EntityPlayerSPMixin {

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void bedwarsqol$outgoingGate(String message, CallbackInfo ci) {
        OutgoingChat gate = OutgoingChat.get();
        if (gate.isPassthrough()) return;
        if (gate.interceptPlayerSend(message)) {
            ci.cancel();
        }
    }
}
