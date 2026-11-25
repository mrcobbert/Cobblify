package com.bedwarsqol.stats;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.util.UUID;

/** Resolves UUID → current in-game name from tab/world (no Mojang call). */
public final class PlayerNames {

    private PlayerNames() {}

    public static String nameForUuid(UUID uuid) {
        if (uuid == null) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return null;

        if (mc.thePlayer != null && mc.thePlayer.getGameProfile() != null) {
            GameProfile self = mc.thePlayer.getGameProfile();
            if (uuid.equals(self.getId()) && self.getName() != null) {
                return self.getName();
            }
        }

        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return null;
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) continue;
            GameProfile gp = info.getGameProfile();
            if (uuid.equals(gp.getId()) && gp.getName() != null && !gp.getName().isEmpty()) {
                return gp.getName();
            }
        }
        return null;
    }
}
