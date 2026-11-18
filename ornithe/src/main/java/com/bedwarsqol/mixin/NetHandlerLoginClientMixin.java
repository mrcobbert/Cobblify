package com.bedwarsqol.mixin;

import com.bedwarsqol.ornithe.ModEvents;
import com.bedwarsqol.ornithe.ServerConnectEvent;
import net.minecraft.client.network.NetHandlerLoginClient;
import net.minecraft.network.NetworkManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts {@link ModEvents#SERVER_CONNECT} when login succeeds and the connection switches to play,
 * the end of {@code handleLoginSuccess}, where FML starts its client handshake and fires
 * {@code ClientConnectedToServerEvent} (netty thread; the pipeline already has its packet handler).
 */
@Mixin(NetHandlerLoginClient.class)
public abstract class NetHandlerLoginClientMixin {

    @Shadow @Final private NetworkManager networkManager;

    @Inject(method = "handleLoginSuccess", at = @At("RETURN"), require = 1)
    private void bedwarsqol$connected(CallbackInfo ci) {
        ModEvents.post(ModEvents.SERVER_CONNECT, new ServerConnectEvent(networkManager));
    }
}
