package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.OldAnimations;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1.7 Animations in third person. Smooth Sneak: the held item's sneak {@code translate(0, 0.203125, 0)} (ordinal
 * 3) is skipped with the arm's. Item Position and Block Hit: 1.7's held-item placement and blocking adjust go on
 * the matrix right before the draw call; the layer's own push/pop scopes them. The draw itself is unchanged.
 */
@Mixin(LayerHeldItem.class)
public abstract class LayerHeldItemOldAnimMixin {

    private static final String DO_RENDER_LAYER = "doRenderLayer(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V";

    @WrapOperation(method = DO_RENDER_LAYER,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;translate(FFF)V",
                    ordinal = 3), require = 1)
    private void bedwarsqol$oldSneakItem(float x, float y, float z, Operation<Void> original) {
        if (OldAnimations.vanillaSneakTranslate()) original.call(x, y, z);
    }

    @WrapOperation(method = DO_RENDER_LAYER,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemRenderer;renderItem(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;)V"),
            require = 1)
    private void bedwarsqol$oldThirdPersonItem(ItemRenderer renderer, EntityLivingBase entity, ItemStack stack,
                                               ItemCameraTransforms.TransformType type, Operation<Void> original) {
        OldAnimations.thirdPerson(entity, stack);
        original.call(renderer, entity, stack, type);
    }
}
