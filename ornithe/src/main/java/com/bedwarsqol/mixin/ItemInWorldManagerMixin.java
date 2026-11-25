package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.ClutchPractice;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clutch Practice's fire-charge throw, at the two server-side right-click paths where Forge fired
 * {@code PlayerInteractEvent}: {@code tryUseItem} (right click in the air; Forge fired it just
 * before this call) and {@code activateBlockOrUseItem} (right click on a block; Forge fired it after
 * the spectator branch). When the hook throws, the vanilla use is skipped, and on a block the
 * client gets the block re-sent, as Forge's canceled path did.
 */
@Mixin(ItemInWorldManager.class)
public abstract class ItemInWorldManagerMixin {

    @Shadow public EntityPlayerMP thisPlayerMP;
    @Shadow public World theWorld;

    @Shadow public abstract WorldSettings.GameType getGameType();

    @Inject(method = "tryUseItem", at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$clutchUseItem(EntityPlayer player, World world, ItemStack stack,
                                          CallbackInfoReturnable<Boolean> cir) {
        ClutchPractice clutch = ClutchPractice.get();
        if (clutch != null && clutch.onInteract(world, player)) cir.setReturnValue(false);
    }

    @Inject(method = "activateBlockOrUseItem", at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$clutchUseOnBlock(EntityPlayer player, World world, ItemStack stack, BlockPos pos,
                                             EnumFacing side, float hitX, float hitY, float hitZ,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (getGameType() == WorldSettings.GameType.SPECTATOR) return;
        ClutchPractice clutch = ClutchPractice.get();
        if (clutch == null || !clutch.onInteract(world, player)) return;
        if (thisPlayerMP != null && thisPlayerMP.playerNetServerHandler != null) {
            thisPlayerMP.playerNetServerHandler.sendPacket(new S23PacketBlockChange(theWorld, pos));
        }
        cir.setReturnValue(false);
    }
}
