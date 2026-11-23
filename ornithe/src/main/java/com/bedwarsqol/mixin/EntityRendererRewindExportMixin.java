package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.export.RewindExport;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind Export frames inside {@code updateCameraAndRender} (PLAN Solution 2, Phase 2). Verified against the mapped
 * 1.8.9 bytecode: after {@code renderWorld} (@546), inside the {@code OpenGlHelper.shadersSupported} branch, vanilla
 * binds the main framebuffer again ({@code mc.getFramebuffer().bindFramebuffer(true)}, @606-610) before the HUD (@673)
 * and the screen (@765); an export frame binds its own right after that call. Another mod may rewrite that call, so it
 * is {@code require = 0, expect = 1}: the frame log's binding check at read-back is the real guard. The chunk-upload
 * deadline {@code renderWorld} gets is pushed far out on export frames.
 */
@Mixin(value = EntityRenderer.class, priority = 900)
public abstract class EntityRendererRewindExportMixin {

    @Inject(method = "updateCameraAndRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/shader/Framebuffer;bindFramebuffer(Z)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1)
    private void bedwarsqol$rewindExportRebind(float partialTicks, long nanoTime, CallbackInfo ci) {
        RewindExport.afterMidFrameBind();
    }

    @ModifyArg(method = "updateCameraAndRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;renderWorld(FJ)V"),
            index = 1, require = 1)
    private long bedwarsqol$rewindExportDeadline(long finishTimeNano) {
        return RewindExport.renderDeadline(finishTimeNano);
    }
}
