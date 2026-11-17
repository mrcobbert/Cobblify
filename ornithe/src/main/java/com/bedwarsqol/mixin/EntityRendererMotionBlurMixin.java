package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.MotionBlur;
import com.bedwarsqol.feature.MotionBlurFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Motion Blur, hook W: in {@code renderWorldPass}, at the method's only read of {@code renderHand}, the instruction
 * right after the "hand" profiler swap, so the world (block outline, particles, weather, clouds) is complete and the
 * held item, HUD and screens are drawn after the pass and stay sharp. Only the final pass ({@code pass == 2}; 0 and 1
 * are anaglyph). With the module off, suppressed, failed, or the camera still, it returns before any GL call.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMotionBlurMixin {

    @Inject(method = "renderWorldPass",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/EntityRenderer;renderHand:Z",
                    opcode = Opcodes.GETFIELD),
            require = 1)
    private void bedwarsqol$motionBlur(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        ClientSettings c = BedwarsQol.config;
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (!MotionBlur.gate(pass, c != null && c.motionBlur, mc.theWorld != null && view != null,
                mc.gameSettings.anaglyph)) {
            return;
        }
        MotionBlurFrame.Sample s = MotionBlur.sample();
        s.nanos = System.nanoTime();
        s.yaw = view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * (double) partialTicks;
        s.pitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * (double) partialTicks;
        s.x = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        s.y = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        s.z = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;
        s.world = System.identityHashCode(mc.theWorld);
        s.viewEntity = System.identityHashCode(view);
        s.perspective = mc.gameSettings.thirdPersonView;
        s.width = mc.displayWidth;
        s.height = mc.displayHeight;
        s.fov = mc.gameSettings.fovSetting;
        s.strength = c.mbStrength;
        MotionBlur.render(s);
    }
}
