package com.bedwarsqol.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Rewind playback bounds vanilla's own tick loop (PLAN section 3, round 2 I1): {@code Minecraft.timer} is private, its
 * {@code elapsedTicks} and {@code timerSpeed} are public; {@code isGamePaused} is private and has no setter. Only
 * {@code RewindPlayback} uses these, and only while a recording plays.
 */
@Mixin(Minecraft.class)
public interface MinecraftTimerAccessor {

    @Accessor("timer")
    Timer bedwarsqol$getTimer();

    @Accessor("isGamePaused")
    void bedwarsqol$setGamePaused(boolean paused);
}
