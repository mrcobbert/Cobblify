package com.bedwarsqol.ornithe;

import net.minecraft.entity.EntityLivingBase;

/**
 * Posted after a living entity's name tag pass, whether or not vanilla drew a name (Forge's
 * {@code RenderLivingEvent.Specials.Post}).
 */
public final class NametagRenderEvent {

    public final EntityLivingBase entity;
    public final double x;
    public final double y;
    public final double z;

    public NametagRenderEvent(EntityLivingBase entity, double x, double y, double z) {
        this.entity = entity;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
