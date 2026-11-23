package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.export.RewindExport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind Export frames (PLAN Solution 2, Phase 2). Verified against the mapped 1.8.9 bytecode: {@code runGameLoop}
 * binds the main framebuffer (@269), calls {@code EntityRenderer.updateCameraAndRender} once (@346), unbinds (@453),
 * draws the main framebuffer into the window (@474) and calls {@code updateDisplay} (@511).
 * <ul>
 *   <li>before {@code updateCameraAndRender}: an export frame's sizes, screen and framebuffer;</li>
 *   <li>after it: read-back and restore;</li>
 *   <li>{@code updateDisplay} HEAD: the window shows nothing of the export.</li>
 * </ul>
 * Every hook returns at once when no export runs.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftRewindExportMixin {

    @Inject(method = "runGameLoop",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;updateCameraAndRender(FJ)V"),
            require = 1)
    private void bedwarsqol$rewindExportBefore(CallbackInfo ci) {
        RewindExport.beforeRender();
    }

    @Inject(method = "runGameLoop",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;updateCameraAndRender(FJ)V",
                    shift = At.Shift.AFTER),
            require = 1)
    private void bedwarsqol$rewindExportAfter(CallbackInfo ci) {
        RewindExport.afterRender();
    }

    @Inject(method = "updateDisplay", at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindExportDisplay(CallbackInfo ci) {
        RewindExport.beforeDisplayUpdate();
    }
}
