package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.ActionCapture;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind's OUT record (format v2, PLAN section A, A4): every vanilla send of C02, C07, C08, C09, C0A, C0B, C0D, C0E
 * and C16 ends in {@code addToSendQueue}, whose body is {@code netManager.sendPacket(packet)}. The hook reads the
 * packet at HEAD and returns; it is not cancellable and changes, delays or drops nothing.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class NetHandlerPlayClientRewindMixin {

    @Inject(method = "addToSendQueue", at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindRecordSend(Packet<?> packet, CallbackInfo ci) {
        ActionCapture.onSend(packet);
    }
}
