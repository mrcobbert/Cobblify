package com.bedwarsqol.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.NetworkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla keeps {@code NetworkManager}'s netty channel private; Forge added {@code channel()} for it. */
@Mixin(NetworkManager.class)
public interface NetworkManagerAccessor {

    @Accessor("channel")
    Channel bedwarsqol$getChannel();
}
