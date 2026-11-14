package cobbench.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cobbench.Bench;

/** One call per frame: the time between two calls is the whole frame (tick, render, swap). */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void cobbench$frame(CallbackInfo ci) {
        Bench.frame((Minecraft) (Object) this);
    }
}
