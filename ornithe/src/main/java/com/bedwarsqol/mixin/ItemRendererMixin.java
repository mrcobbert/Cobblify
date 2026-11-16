package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.feature.OldAnimations;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Configurable first-person held-item position — a 1.8.9 take on the "HandPosition" mod. When the
 * master {@code handPositionEnabled} is on, the held item is translated by the user's X/Y/Z offset
 * (eye space: +X right, +Y up, +Z toward the camera) before vanilla's own placement, shifting the
 * whole item, then scaled by the user's size factor at the end (resizing it in place). Purely
 * visual; sends no packets.
 *
 * Applied at the HEAD of {@link ItemRenderer#transformFirstPersonItem(float, float)} and skipped
 * while actively using an item — the eat/drink/bow/block branches call that method a second time,
 * which would otherwise double-apply the offset.
 *
 * 1.7 Animations hooks live here too, so their order against Hand Position is fixed: Item Position's 1.7
 * placement runs at the TAIL after vanilla's transforms and before Hand Position's scale (idle/swing pose), or
 * right before the draw for the using poses; Block Hit passes the real swing to the using poses; Item Position
 * keeps the item up when only the stack's count or damage changed.
 *
 * VERIFIED against 1.8.9 MCP-named bytecode: private void transformFirstPersonItem(float, float).
 */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    private static final String TRANSFORM_FIRST_PERSON =
            "Lnet/minecraft/client/renderer/ItemRenderer;transformFirstPersonItem(FF)V";
    private static final String RENDER_ITEM_IN_FIRST_PERSON = "renderItemInFirstPerson(F)V";

    @Shadow private ItemStack itemToRender;
    @Shadow private int equippedItemSlot;

    @Inject(method = "transformFirstPersonItem", at = @At("HEAD"))
    private void bedwarsqol$handPosition(float equipProgress, float swingProgress, CallbackInfo ci) {
        if (BedwarsQol.config == null || !BedwarsQol.config.handPositionEnabled) return;
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null && player.isUsingItem()) return;
        GlStateManager.translate(BedwarsQol.config.handPosX, BedwarsQol.config.handPosY, BedwarsQol.config.handPosZ);
    }

    @Inject(method = "transformFirstPersonItem", at = @At("TAIL"))
    private void bedwarsqol$handScale(float equipProgress, float swingProgress, CallbackInfo ci) {
        // 1.7 Animations, Item Position: before Hand Position's scale. No-op unless the row is effective.
        OldAnimations.firstPersonIdle(itemToRender);
        if (BedwarsQol.config == null || !BedwarsQol.config.handPositionEnabled) return;
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null && player.isUsingItem()) return;
        float s = BedwarsQol.config.handScale;
        if (s != 1.0f) GlStateManager.scale(s, s, s);
    }

    /** 1.7 Animations, Item Position: the using poses get the 1.7 placement after their own transforms. */
    @WrapOperation(method = RENDER_ITEM_IN_FIRST_PERSON,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemRenderer;renderItem(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;)V"),
            require = 1)
    private void bedwarsqol$oldFirstPersonUsing(ItemRenderer renderer, EntityLivingBase entity, ItemStack stack,
                                                ItemCameraTransforms.TransformType type, Operation<Void> original) {
        OldAnimations.firstPersonUsing(stack);
        original.call(renderer, entity, stack, type);
    }

    /** 1.7 Animations, Block Hit: the eat/drink branch swings (vanilla passes 0). */
    @ModifyArg(method = RENDER_ITEM_IN_FIRST_PERSON,
            at = @At(value = "INVOKE", target = TRANSFORM_FIRST_PERSON, ordinal = 1), index = 1, require = 1)
    private float bedwarsqol$swingWhileEating(float swing, @Local(argsOnly = true) float partialTicks) {
        return OldAnimations.usingSwing(swing, partialTicks);
    }

    /** 1.7 Animations, Block Hit: the sword-block branch swings (vanilla passes 0). */
    @ModifyArg(method = RENDER_ITEM_IN_FIRST_PERSON,
            at = @At(value = "INVOKE", target = TRANSFORM_FIRST_PERSON, ordinal = 2), index = 1, require = 1)
    private float bedwarsqol$swingWhileBlocking(float swing, @Local(argsOnly = true) float partialTicks) {
        return OldAnimations.usingSwing(swing, partialTicks);
    }

    /** 1.7 Animations, Block Hit: the bow branch swings (vanilla passes 0). */
    @ModifyArg(method = RENDER_ITEM_IN_FIRST_PERSON,
            at = @At(value = "INVOKE", target = TRANSFORM_FIRST_PERSON, ordinal = 3), index = 1, require = 1)
    private float bedwarsqol$swingWhileDrawing(float swing, @Local(argsOnly = true) float partialTicks) {
        return OldAnimations.usingSwing(swing, partialTicks);
    }

    /**
     * 1.7 Animations, Item Position: when the selected stack is a new object of the same item with only its count
     * or damage changed, it becomes the rendered stack at once and the re-equip dip is skipped (1.7).
     */
    @WrapOperation(method = "updateEquippedItem()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;getIsItemStackEqual(Lnet/minecraft/item/ItemStack;)Z"),
            require = 1)
    private boolean bedwarsqol$sameItemNoDip(ItemStack held, ItemStack current, Operation<Boolean> original) {
        boolean equal = original.call(held, current);
        if (equal) return true;
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return false;
        int slot = player.inventory.currentItem;
        if (!OldAnimations.keepEquipped(held, current, slot == equippedItemSlot)) return false;
        itemToRender = current;
        equippedItemSlot = slot;
        return true;
    }
}
