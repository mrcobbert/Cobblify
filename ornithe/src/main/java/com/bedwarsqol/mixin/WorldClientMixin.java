package com.bedwarsqol.mixin;

import com.bedwarsqol.ornithe.ModEvents;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Posts {@link ModEvents#WORLD_LOAD} when a client world is built, where Forge posts {@code WorldEvent.Load}. */
@Mixin(WorldClient.class)
public abstract class WorldClientMixin {

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void bedwarsqol$worldLoad(CallbackInfo ci) {
        ModEvents.WORLD_LOAD.post((WorldClient) (Object) this);
    }
}
