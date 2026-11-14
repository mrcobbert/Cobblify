package com.bedwarsqol.hud;

import com.bedwarsqol.config.ConfigDir;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * The Potion HUD's modern effect icons as textures. The first {@link #request} of a session starts one
 * {@link EffectIconLoader} on a daemon thread (disk cache under the config directory, else one Range request to
 * Mojang's server); {@link #bind} uploads each verified icon once, on the render thread, and binds it. Until an icon is
 * there, offline, or on any failed check, {@link #bind} returns false and the caller draws the 1.8.9 icon.
 */
public final class EffectIconTextures {

    private static final Logger LOGGER = LogManager.getLogger("Cobblify");

    /** Kill switch: off draws the 1.8.9 icons and makes no request. */
    public static volatile boolean enabled = true;
    /**
     * Harness only (like the {@code cobblify.test.configDir} property): an absolute directory used instead of the real
     * icon cache, read once when the loader is made, so a smoke check can start from an empty cache without touching
     * the player's.
     */
    public static volatile String cacheDirOverride = null;
    /** This session's loader, made by the first {@link #request}; null before it. */
    public static volatile EffectIconLoader LOADER;

    private static final Executor DAEMON = new Executor() {
        @Override
        public void execute(Runnable r) {
            Thread t = new Thread(r, "Cobblify effect icons");
            t.setDaemon(true);
            t.start();
        }
    };

    // render thread only
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<String, ResourceLocation>();
    private static final Set<String> BROKEN = new HashSet<String>();
    private static boolean failureLogged;

    private EffectIconTextures() {
    }

    /** Starts loading the icons, once per session. Cheap to call every frame. */
    public static void request() {
        if (!enabled) return;
        EffectIconLoader l = LOADER;
        if (l == null) {
            synchronized (EffectIconTextures.class) {
                l = LOADER;
                if (l == null) {
                    File dir;
                    try {
                        String override = cacheDirOverride;
                        dir = override != null && !override.isEmpty() ? new File(override)
                                : new File(ConfigDir.get(), "cobblify-effect-icons");
                    } catch (RuntimeException e) {
                        enabled = false; // no config directory: stay on the 1.8.9 icons
                        return;
                    }
                    l = new EffectIconLoader(dir, new HttpRangeFetcher(EffectIcons.JAR_URL), EffectIcons.TABLE);
                    LOADER = l;
                }
            }
        }
        l.start(DAEMON);
    }

    /**
     * Binds the modern icon for 1.8.9 potion id {@code potionId} (an 18x18 texture) and returns true, or returns false
     * when it is not available, so the caller draws the 1.8.9 icon. Render thread only.
     */
    public static boolean bind(int potionId) {
        if (!enabled) return false;
        EffectIconLoader l = LOADER;
        if (l == null) return false;
        if (!failureLogged && l.state() == EffectIconLoader.State.FAILED) {
            failureLogged = true;
            LOGGER.info("Modern potion icons are not available; the Potion HUD keeps the 1.8.9 icons");
        }
        String name = EffectIcons.nameFor(potionId);
        if (name == null || BROKEN.contains(name)) return false;
        Minecraft mc = Minecraft.getMinecraft();
        ResourceLocation loc = TEXTURES.get(name);
        if (loc == null) {
            byte[] png = l.png(name);
            if (png == null) return false;
            try {
                BufferedImage img = TextureUtil.readBufferedImage(new ByteArrayInputStream(png));
                if (img == null) throw new IllegalStateException("not an image");
                loc = mc.getTextureManager().getDynamicTextureLocation("cobblify_effect_" + name, new DynamicTexture(img));
                TEXTURES.put(name, loc);
            } catch (Exception e) {
                BROKEN.add(name); // decoded once; never retried this session
                return false;
            }
        }
        mc.getTextureManager().bindTexture(loc);
        return true;
    }
}
