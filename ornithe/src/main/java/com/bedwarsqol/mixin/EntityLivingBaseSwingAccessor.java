package com.bedwarsqol.mixin;

import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 1.7 Animations: starts a swing that only the local client sees by writing the two fields vanilla's own swing
 * start writes ({@code isSwingInProgress}, {@code swingProgressInt}). The player's swing method is never called,
 * because the local player's override of it also tells the server.
 */
@Mixin(EntityLivingBase.class)
public interface EntityLivingBaseSwingAccessor {

    @Accessor("isSwingInProgress")
    void bedwarsqol$setSwingInProgress(boolean inProgress);

    @Accessor("swingProgressInt")
    void bedwarsqol$setSwingProgressInt(int progress);

    /** Swing length in ticks (6, changed by Haste and Mining Fatigue); reads potion effects only. */
    @Invoker("getArmSwingAnimationEnd")
    int bedwarsqol$swingEnd();
}
