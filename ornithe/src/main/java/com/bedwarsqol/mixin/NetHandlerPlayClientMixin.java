package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.MotionBlur;
import com.bedwarsqol.ornithe.ChatReceivedEvent;
import com.bedwarsqol.ornithe.ModEvents;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts {@link ModEvents#CHAT} for every server chat packet, where Forge posts
 * {@code ClientChatReceivedEvent}: right after {@code checkThreadAndEnqueue}, so only on the client
 * thread (that call throws on the netty thread after re-queueing the packet), before vanilla shows
 * it in chat or the action bar. Canceling the event skips vanilla's print.
 *
 * <p>Motion Blur, hook C: after {@code handlePlayerPosLook} has set the player's position and rotation from a server
 * correction, the next rendered frame is a camera cut ({@link MotionBlur#onPositionCorrected}). It reads nothing from
 * the packet and sends nothing.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class NetHandlerPlayClientMixin {

    @Inject(method = "handleChat",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/PacketThreadUtil;checkThreadAndEnqueue(Lnet/minecraft/network/Packet;Lnet/minecraft/network/INetHandler;Lnet/minecraft/util/IThreadListener;)V",
                    shift = At.Shift.AFTER),
            cancellable = true, require = 1)
    private void bedwarsqol$postChat(S02PacketChat packet, CallbackInfo ci) {
        if (packet.getChatComponent() == null) return;
        if (ModEvents.CHAT.post(new ChatReceivedEvent(packet.getType(), packet.getChatComponent()))) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerPosLook",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/EntityPlayer;setPositionAndRotation(DDDFF)V",
                    shift = At.Shift.AFTER),
            require = 1)
    private void bedwarsqol$motionBlurCut(S08PacketPlayerPosLook packet, CallbackInfo ci) {
        MotionBlur.onPositionCorrected();
    }
}
