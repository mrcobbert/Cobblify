package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.ActionCapture;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind's handled order (format v2, PLAN section A "Handled order", A7): every server packet whose handler runs on
 * the client thread runs inside {@code PacketThreadUtil$1.run} ({@code packet.processPacket(handler)}, queued by
 * {@code checkThreadAndEnqueue}). At HEAD the recorder pops the packet's entry from the capture handler's ring. The
 * anonymous class's captured fields have no MCP names ({@code f_54564080} packet, {@code f_26049944} handler, as in the
 * mapped jar). Read-only.
 */
@Mixin(targets = "net.minecraft.network.PacketThreadUtil$1")
public abstract class PacketThreadUtilRunMixin {

    @Shadow @Final Packet<?> f_54564080;
    @Shadow @Final INetHandler f_26049944;

    @Inject(method = "run", at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindHandled(CallbackInfo ci) {
        ActionCapture.onPacketRun(f_54564080, f_26049944);
    }
}
