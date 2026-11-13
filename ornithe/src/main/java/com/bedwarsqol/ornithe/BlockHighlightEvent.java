package com.bedwarsqol.ornithe;

import com.bedwarsqol.event.CancelableEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;

/**
 * Posted where vanilla draws the block selection outline (Forge's {@code DrawBlockHighlightEvent});
 * canceling skips the vanilla outline.
 */
public final class BlockHighlightEvent extends CancelableEvent {

    public final EntityPlayer player;
    public final MovingObjectPosition target;
    public final float partialTicks;

    public BlockHighlightEvent(EntityPlayer player, MovingObjectPosition target, float partialTicks) {
        this.player = player;
        this.target = target;
        this.partialTicks = partialTicks;
    }
}
