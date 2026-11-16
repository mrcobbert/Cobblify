package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.OldAnimations;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiIngame;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1.7 Animations, Hearts: in {@code renderPlayerStats} the second write of {@code healthUpdateCounter} starts the
 * blink when health rose; with Hearts it is skipped, so the hearts blink only when health dropped (the first write).
 */
@Mixin(GuiIngame.class)
public abstract class GuiIngameHeartsMixin {

    @Shadow private int playerHealth;

    @WrapOperation(method = "renderPlayerStats(Lnet/minecraft/client/gui/ScaledResolution;)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/GuiIngame;healthUpdateCounter:J",
                    opcode = Opcodes.PUTFIELD, ordinal = 1), require = 1)
    private void bedwarsqol$noBlinkOnRegen(GuiIngame gui, long value, Operation<Void> original) {
        if (OldAnimations.heartsBlinkOnRise(playerHealth)) original.call(gui, value);
    }
}
