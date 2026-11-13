package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.ChatLengthLimit;
import net.minecraft.network.play.client.C01PacketChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lifts the send-side half of the 1.8.9 chat length cap. The vanilla constructor does
 * {@code if (messageIn.length() > 100) messageIn = messageIn.substring(0, 100)} before storing the
 * field, and {@code writePacketData} then writes it with no cap of its own — so this one clamp is the
 * only thing shortening outgoing chat on the wire.
 *
 * <p>Re-storing the original argument here covers every send path at once: typed chat and the mod's
 * own {@code OutgoingChat} dispatch both reach the network through
 * {@code EntityPlayerSP.sendChatMessage}, which is nothing but
 * {@code sendQueue.addToSendQueue(new C01PacketChatMessage(message))}.
 *
 * @see ChatLengthLimit for the Hypixel gate and the 256 ceiling
 */
@Mixin(C01PacketChatMessage.class)
public abstract class C01PacketChatMessageMixin {

    @Shadow private String message;

    @Inject(method = "<init>(Ljava/lang/String;)V", at = @At("RETURN"))
    private void bedwarsqol$keepLongMessage(String messageIn, CallbackInfo ci) {
        if (messageIn == null || messageIn.length() <= ChatLengthLimit.VANILLA) return;
        int limit = ChatLengthLimit.limit();
        if (limit <= ChatLengthLimit.VANILLA) return;
        this.message = ChatLengthLimit.clamp(messageIn, limit);
    }
}
