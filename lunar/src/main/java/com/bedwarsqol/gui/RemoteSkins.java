package com.bedwarsqol.gui;

import com.bedwarsqol.stats.MojangNameResolver;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTexture.Type;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.util.ResourceLocation;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a player's skin from a bare name (name &rarr; uuid &rarr; textures) for players who aren't in
 * the tab list, so the Players detail hero can show a head for a manually searched player. Results are
 * cached per name; resolution runs off-thread and is best-effort (an unknown name simply never resolves,
 * leaving the placeholder). Skin loading itself hops back to the client thread inside the skin manager.
 *
 * <p>The profile handed to the skin manager must carry a <b>real</b> UUID: filling a name-only profile
 * is a server-side path that no-ops on a client, and the skin manager then dereferences the profile's
 * null id. So the id comes from {@link MojangNameResolver} (the resolver {@code /bw} already uses,
 * cached for hours) and the session service fills the textures onto it.
 *
 * <p>The hero draws every frame, so the lookup is fenced by a {@link ResolveGate}: one live attempt per
 * name, and a name that failed is left alone for ten minutes instead of being retried each frame. An
 * attempt that never reports back stops blocking after a minute; because the gate is ticketed, that
 * abandoned attempt's late reply is ignored instead of settling or cancelling its replacement, while
 * the skin it may have fetched is still cached — a skin for a name is right whichever attempt got it.
 */
final class RemoteSkins {
    private RemoteSkins() {}

    private static final Map<String, ResourceLocation> SKIN = new ConcurrentHashMap<String, ResourceLocation>();

    /** One live resolve per name (a silent attempt blocks for at most 60 s); a failed name waits 10 min. */
    private static final ResolveGate GATE = new ResolveGate(60_000L, 10L * 60_000L);

    /** The cached face skin for {@code name}, or null while it resolves (the first call starts one async
     *  resolve; later calls return the cached result once it lands). */
    static ResourceLocation face(String name) {
        if (name == null || name.isEmpty()) return null;
        final String key = name.toLowerCase(Locale.ROOT);
        ResourceLocation cached = SKIN.get(key);
        if (cached != null) return cached;
        final long ticket = GATE.tryStart(key, System.currentTimeMillis());
        if (ticket < 0) return null; // an attempt is already live, or this name failed recently
        final String requested = name;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    UUID uuid = MojangNameResolver.resolve(requested);
                    if (uuid == null) { // no such player (nicked, typo): don't ask again for a while
                        GATE.fail(key, ticket, System.currentTimeMillis());
                        return;
                    }
                    Minecraft mc = Minecraft.getMinecraft();
                    GameProfile profile = mc.getSessionService()
                            .fillProfileProperties(new GameProfile(uuid, requested), false);
                    if (profile == null || profile.getProperties().get("textures").isEmpty()) {
                        GATE.fail(key, ticket, System.currentTimeMillis()); // nothing to load
                        return;
                    }
                    mc.getSkinManager().loadProfileTextures(profile,
                            new SkinManager.SkinAvailableCallback() {
                                public void skinAvailable(Type type, ResourceLocation location,
                                                          MinecraftProfileTexture texture) {
                                    if (type == Type.SKIN && location != null) {
                                        SKIN.put(key, location);
                                        GATE.succeed(key, ticket);
                                    }
                                }
                            }, false);
                } catch (Throwable ignored) {
                    // Unknown name / offline / mapping quirk: leave the placeholder, don't crash the GUI.
                    GATE.fail(key, ticket, System.currentTimeMillis());
                }
            }
        }, "bwqol-remote-skin");
        t.setDaemon(true);
        t.start();
        return null;
    }
}
