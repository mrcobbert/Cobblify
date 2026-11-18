package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.watch.RewindPlayback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rewind's camera is the client player while a recording plays (PLAN section 4). Vanilla creates the client player in
 * one place, {@code PlayerControllerMP.m_75725732(World, StatFileWriter)} (no MCP name; called by
 * {@code Minecraft.loadWorld} and {@code setDimensionAndSpawnPlayer}); during a session it returns a
 * {@code RewindCamera} instead. While a recording plays it also never syncs the held slot (C09). Outside a session
 * vanilla runs unchanged.
 */
@Mixin(PlayerControllerMP.class)
public abstract class PlayerControllerMPRewindMixin {

    @Shadow @Final private Minecraft mc;
    @Shadow @Final private NetHandlerPlayClient netClientHandler;

    @Inject(method = "m_75725732", at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$rewindCamera(World world, StatFileWriter stats, CallbackInfoReturnable<EntityPlayerSP> cir) {
        EntityPlayerSP camera = RewindPlayback.createCamera(mc, world, netClientHandler, stats);
        if (camera != null) cir.setReturnValue(camera);
    }

    /**
     * {@code syncCurrentPlayItem()} (from {@code updateController}, every world tick) sends C09 when the client
     * player's selected slot changed; during playback the camera's slot follows the recording, so nothing is sent.
     */
    @Inject(method = "syncCurrentPlayItem", at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$rewindNoHeldItemSync(CallbackInfo ci) {
        if (RewindPlayback.active()) ci.cancel();
    }
}
