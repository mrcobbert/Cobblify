package com.bedwarsqol.mixin;

import com.bedwarsqol.ornithe.ModEvents;
import com.bedwarsqol.ornithe.MouseButtonEvent;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts {@link ModEvents#MOUSE} for each event of {@code runTick}'s in-game mouse loop, where Forge
 * posts {@code MouseEvent}: at the start of the loop body, the first {@code Mouse.getEventButton()}
 * call in {@code runTick} (right after {@code Mouse.next()}).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMouseMixin {

    @Inject(method = "runTick",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getEventButton()I", ordinal = 0),
            require = 1)
    private void bedwarsqol$mouseEvent(CallbackInfo ci) {
        ModEvents.MOUSE.post(new MouseButtonEvent(Mouse.getEventButton(), Mouse.getEventButtonState()));
    }
}
