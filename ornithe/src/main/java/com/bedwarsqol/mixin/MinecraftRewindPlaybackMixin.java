package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.watch.RewindPlayback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind playback's frame and tick hooks (PLAN section 3, round 2 I1). Verified against the mapped 1.8.9 bytecode:
 * {@code runGameLoop} calls {@code timer.updateTimer()} twice (paused and running branches, one per frame), then runs
 * {@code for (j = 0; j < timer.elapsedTicks; ++j) runTick();} re-reading {@code elapsedTicks} on every pass, and writes
 * {@code isGamePaused} once, after the loop; {@code runTick} has one {@code return}.
 * <ul>
 *   <li>after {@code updateTimer}: the frame's tick bound, timer speed and pause flag;</li>
 *   <li>after the {@code isGamePaused} write: the pause flag forced again;</li>
 *   <li>{@code runTick} HEAD: the tick's recorded packets; RETURN: end the loop at a stop or allow one more catch-up
 *       tick;</li>
 *   <li>{@code displayGuiScreen} HEAD: the screen policy (recorded screens never open).</li>
 * </ul>
 * Every hook returns at once when no recording plays.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftRewindPlaybackMixin {

    @Inject(method = "runGameLoop",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Timer;updateTimer()V", shift = At.Shift.AFTER),
            require = 2)
    private void bedwarsqol$rewindFrameStart(CallbackInfo ci) {
        RewindPlayback.onFrameStart();
    }

    @Inject(method = "runGameLoop",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;isGamePaused:Z",
                    opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER),
            require = 1)
    private void bedwarsqol$rewindAfterPauseWrite(CallbackInfo ci) {
        RewindPlayback.onPauseWritten();
    }

    @Inject(method = "runTick", at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindTickHead(CallbackInfo ci) {
        RewindPlayback.onTickHead();
    }

    @Inject(method = "runTick", at = @At("RETURN"), require = 1)
    private void bedwarsqol$rewindTickEnd(CallbackInfo ci) {
        RewindPlayback.onTickEnd();
    }

    @Inject(method = "displayGuiScreen", at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$rewindScreenPolicy(GuiScreen screen, CallbackInfo ci) {
        if (!RewindPlayback.allowScreen(screen)) ci.cancel();
    }
}
