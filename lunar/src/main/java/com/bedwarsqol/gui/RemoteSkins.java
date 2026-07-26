package com.bedwarsqol.gui;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTexture.Type;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.util.ResourceLocation;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a player's skin from a bare name (name &rarr; uuid &rarr; textures) for players who aren't in
 * the tab list, so the Players detail hero can show a head for a manually searched player. Results are
 * cached per name; resolution runs off-thread and is best-effort (an unknown name simply never resolves,
 * leaving the placeholder). Skin loading itself hops back to the client thread inside the skin manager.
 */
final class RemoteSkins {
    private RemoteSkins() {}

    private static final Map<String, ResourceLocation> SKIN = new ConcurrentHashMap<String, ResourceLocation>();
    private static final Map<String, Boolean> INFLIGHT = new ConcurrentHashMap<String, Boolean>();

    /** The cached face skin for {@code name}, or null while it resolves (the first call starts one async
     *  resolve; later calls return the cached result once it lands). */
    static ResourceLocation face(String name) {
        if (name == null || name.isEmpty()) return null;
        final String key = name.toLowerCase(Locale.ROOT);
        ResourceLocation cached = SKIN.get(key);
        if (cached != null) return cached;
        if (INFLIGHT.putIfAbsent(key, Boolean.TRUE) != null) return null; // already resolving
        final String requested = name;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    GameProfile profile = TileEntitySkull.updateGameprofile(new GameProfile(null, requested));
                    if (profile == null) return;
                    Minecraft.getMinecraft().getSkinManager().loadProfileTextures(profile,
                            new SkinManager.SkinAvailableCallback() {
                                public void skinAvailable(Type type, ResourceLocation location,
                                                          MinecraftProfileTexture texture) {
                                    if (type == Type.SKIN && location != null) SKIN.put(key, location);
                                }
                            }, false);
                } catch (Throwable ignored) {
                    // Unknown name / offline / mapping quirk: leave the placeholder, don't crash the GUI.
                } finally {
                    INFLIGHT.remove(key);
                }
            }
        }, "bwqol-remote-skin");
        t.setDaemon(true);
        t.start();
        return null;
    }
}
