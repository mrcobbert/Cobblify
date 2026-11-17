package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.ItemPhysics;
import com.bedwarsqol.feature.ItemPhysicsRules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.item.EntityItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Item Physics: dropped items lie flat with a fixed yaw, tumble while airborne, rest on the drawn surface
 * and pile neatly. Only the existing {@code GlStateManager} transform calls of vanilla's transform helper
 * ({@code m_34765557}, MCP {@code func_177077_a}) and of {@code doRender}'s copy loop are wrapped; the two
 * added rotations go through {@code GlStateManager} too, so Argentum's capture of the wrapped
 * {@code doRender} records them. Nothing is drawn here and {@code doRender} is never replaced or cancelled.
 * Module off: every wrapper calls the original with vanilla's arguments and adds nothing.
 */
@Mixin(RenderEntityItem.class)
public abstract class RenderEntityItemMixin {

    private static final String TRANSFORM =
            "m_34765557(Lnet/minecraft/entity/item/EntityItem;DDDFLnet/minecraft/client/resources/model/IBakedModel;)I";
    private static final String DO_RENDER = "doRender(Lnet/minecraft/entity/item/EntityItem;DDDFF)V";
    private static final String TRANSLATE = "Lnet/minecraft/client/renderer/GlStateManager;translate(FFF)V";
    private static final String ROTATE = "Lnet/minecraft/client/renderer/GlStateManager;rotate(FFFF)V";

    /** The pile copy the copy loop is on; reset by the transform helper, which runs once before the loop. */
    @Unique private int bedwarsqol$pileCopy;

    /** Vanilla's bobbing translate: on, the rest height (no bob), then the fixed yaw and the flat/tumble pitch. */
    @WrapOperation(method = TRANSFORM, at = @At(value = "INVOKE", target = TRANSLATE, ordinal = 0), require = 1)
    private void bedwarsqol$restPose(float x, float y, float z, Operation<Void> original,
                                     @Local(argsOnly = true) EntityItem item,
                                     @Local(argsOnly = true, ordinal = 1) double renderY,
                                     @Local(argsOnly = true) float partialTicks,
                                     @Local(argsOnly = true) IBakedModel model) {
        if (!ItemPhysics.on()) {
            original.call(x, y, z);
            return;
        }
        bedwarsqol$pileCopy = 0;
        original.call(x, ItemPhysicsRules.restY(true, y, renderY, ItemPhysics.restLift(model),
                ItemPhysics.surfaceShift(item)), z);
        GlStateManager.rotate(ItemPhysicsRules.yaw(item.getEntityId()), 0f, 1f, 0f);
        GlStateManager.rotate(ItemPhysicsRules.pitch(item.getAge(), partialTicks, item.onGround), 1f, 0f, 0f);
    }

    /** Vanilla's spin about Y: skipped while on. */
    @WrapOperation(method = TRANSFORM, at = @At(value = "INVOKE", target = ROTATE, ordinal = 0), require = 1)
    private void bedwarsqol$noSpin(float angle, float ax, float ay, float az, Operation<Void> original) {
        if (ItemPhysicsRules.vanillaSpin(ItemPhysics.on())) original.call(angle, ax, ay, az);
    }

    /** Vanilla centres a flat pile on the origin: skipped while on, so the pile grows up from the rest. */
    @WrapOperation(method = TRANSFORM, at = @At(value = "INVOKE", target = TRANSLATE, ordinal = 1), require = 1)
    private void bedwarsqol$noPileCentering(float x, float y, float z, Operation<Void> original) {
        if (ItemPhysicsRules.vanillaPileCentering(ItemPhysics.on())) original.call(x, y, z);
    }

    /**
     * A 3D copy's random scatter (inside the copy's push/pop, copies after the first): on, a fixed offset up
     * (local -Z after the pitch) and a small side step.
     */
    @WrapOperation(method = DO_RENDER, at = @At(value = "INVOKE", target = TRANSLATE, ordinal = 0), require = 1)
    private void bedwarsqol$pile3d(float x, float y, float z, Operation<Void> original) {
        if (!ItemPhysics.on()) {
            original.call(x, y, z);
            return;
        }
        int copy = ++bedwarsqol$pileCopy;
        float side = ItemPhysicsRules.pileSide(copy, true);
        original.call(side, side, -ItemPhysicsRules.pileUp(copy, true));
    }

    /** A flat copy's step to the next one (flat branch only; accumulates, after the copy is drawn): on, a fixed step up. */
    @WrapOperation(method = DO_RENDER, at = @At(value = "INVOKE", target = TRANSLATE, ordinal = 1), require = 1)
    private void bedwarsqol$pileFlat(float x, float y, float z, Operation<Void> original) {
        if (!ItemPhysics.on()) {
            original.call(x, y, z);
            return;
        }
        int copy = bedwarsqol$pileCopy++;
        original.call(0f, 0f, -(ItemPhysicsRules.pileUp(copy + 1, false) - ItemPhysicsRules.pileUp(copy, false)));
    }
}
