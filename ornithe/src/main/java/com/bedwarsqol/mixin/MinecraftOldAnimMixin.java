package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.OldAnimations;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.7 Animations. Block Hit: a local swing at the head of {@code sendClickBlockToController} while an item is in
 * use (vanilla returns there) and at the head of {@code clickMouse} during the miss delay (vanilla returns there
 * too). Neither hook cancels, changes or calls anything vanilla does next. Smooth Sneak: the eased camera drop is
 * ticked at the end of {@code runTick}, after the player has moved.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftOldAnimMixin {

    @Shadow private int leftClickCounter;

    @Inject(method = "sendClickBlockToController(Z)V", at = @At("HEAD"), require = 1)
    private void bedwarsqol$blockHitSwing(boolean attackHeld, CallbackInfo ci) {
        OldAnimations.blockHitSwing((Minecraft) (Object) this, attackHeld, leftClickCounter);
    }

    @Inject(method = "clickMouse()V", at = @At("HEAD"), require = 1)
    private void bedwarsqol$missSwing(CallbackInfo ci) {
        OldAnimations.missSwing((Minecraft) (Object) this, leftClickCounter);
    }

    @Inject(method = "runTick()V", at = @At("TAIL"), require = 1)
    private void bedwarsqol$sneakTick(CallbackInfo ci) {
        OldAnimations.tick((Minecraft) (Object) this);
    }
}
