package com.bedwarsqol.mixin;

import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.util.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Rewind's DIG record (format v2, PLAN section A): the controller's private dig state, read once per tick by
 * {@code ActionCapture.onTickEnd}. Read-only.
 */
@Mixin(PlayerControllerMP.class)
public interface PlayerControllerMPRewindAccessor {

    @Accessor("currentBlock")
    BlockPos bedwarsqol$currentBlock();

    @Accessor("curBlockDamageMP")
    float bedwarsqol$curBlockDamageMP();

    @Accessor("isHittingBlock")
    boolean bedwarsqol$isHittingBlock();
}
