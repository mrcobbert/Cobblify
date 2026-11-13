package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * Zoom and No Hurt Cam for {@code EntityRendererCameraMixin}: reads the settings and the Zoom key each frame and
 * applies {@link CameraRules}. Holding the key zooms; releasing it restores the view on the next frame.
 */
public final class CameraOptions {

    private CameraOptions() {
    }

    /** The field of view {@code EntityRenderer.getFOVModifier} returns, zoomed while the Zoom key is held. */
    public static float fov(float fov, boolean useFovSetting) {
        return CameraRules.fov(fov, useFovSetting, zooming());
    }

    /** Whether to skip {@code EntityRenderer.hurtCameraEffect}. */
    public static boolean cancelHurtCam() {
        ClientSettings c = BedwarsQol.config;
        return c != null && CameraRules.cancelHurtCam(c.noHurtCam);
    }

    private static boolean zooming() {
        ClientSettings c = BedwarsQol.config;
        KeyBinding key = BedwarsQol.zoomKeyBinding;
        if (c == null || key == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        return CameraRules.zooming(c.zoomEnabled, key.isKeyDown(), mc != null && mc.currentScreen != null);
    }
}
