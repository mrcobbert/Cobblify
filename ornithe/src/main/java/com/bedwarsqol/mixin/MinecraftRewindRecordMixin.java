package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.ActionCapture;
import com.bedwarsqol.rewind.Rewind;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind's recorder hooks in {@code Minecraft} (format v2, PLAN section A):
 * <ul>
 *   <li>{@code runTick} HEAD: the TICK_HEAD heartbeat with the handled count, every local scope closed, and the HUD
 *       capture's tick head ({@link Rewind#onRunTickHead});</li>
 *   <li>{@code clickMouse}, {@code rightClickMouse}, {@code sendClickBlockToController}: a local scope from HEAD to
 *       every return (attack, dig, place, use), so their block changes and sounds are the player's own.</li>
 * </ul>
 * Nothing is read from or changed in the game; every hook returns at once unless a recording is wired.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftRewindRecordMixin {

    @Inject(method = "runTick", at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindRecordTickHead(CallbackInfo ci) {
        Rewind.onRunTickHead();
    }

    @Inject(method = "clickMouse", at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindClickEnter(CallbackInfo ci) {
        ActionCapture.enterLocal();
    }

    @Inject(method = "clickMouse", at = @At("RETURN"), require = 1)
    private void bedwarsqol$rewindClickExit(CallbackInfo ci) {
        ActionCapture.exitLocal();
    }

    @Inject(method = "rightClickMouse", at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindRightClickEnter(CallbackInfo ci) {
        ActionCapture.enterLocal();
    }

    @Inject(method = "rightClickMouse", at = @At("RETURN"), require = 1)
    private void bedwarsqol$rewindRightClickExit(CallbackInfo ci) {
        ActionCapture.exitLocal();
    }

    @Inject(method = "sendClickBlockToController", at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindDigEnter(boolean leftClick, CallbackInfo ci) {
        ActionCapture.enterLocal();
    }

    @Inject(method = "sendClickBlockToController", at = @At("RETURN"), require = 1)
    private void bedwarsqol$rewindDigExit(boolean leftClick, CallbackInfo ci) {
        ActionCapture.exitLocal();
    }
}
