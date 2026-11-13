package com.bedwarsqol.feature;

/**
 * Zoom, No Hurt Cam and Dynamic FOV, the camera options of the Ornithe build (Lunar has its own). Zoom divides
 * the world field of view by {@link #ZOOM_FACTOR} while its key is held, like OptiFine's zoom; it is
 * evaluated every frame, so releasing the key restores the view at once. No Hurt Cam skips the camera
 * tilt when the player takes damage.
 */
public final class CameraRules {

    public static final boolean DEFAULT_ZOOM_ENABLED = true;
    public static final boolean DEFAULT_NO_HURT_CAM = false;
    /** Vanilla's moving FOV (sprint, Speed, Slowness, bow draw); OptiFine's "Dynamic FOV". */
    public static final boolean DEFAULT_DYNAMIC_FOV = true;
    /** LWJGL {@code Keyboard.KEY_C}, OptiFine's default zoom key. */
    public static final int DEFAULT_ZOOM_KEY = 46;
    /** The vanilla key binding's name; options.txt stores it as {@code key_Zoom}. */
    public static final String ZOOM_KEY_NAME = "Zoom";
    /** OptiFine's zoom: a quarter of the field of view. */
    public static final float ZOOM_FACTOR = 4f;

    private CameraRules() {
    }

    /** Whether zoom applies this frame: enabled, key held, and no screen open (so typing a C never zooms). */
    public static boolean zooming(boolean enabled, boolean keyDown, boolean screenOpen) {
        return enabled && keyDown && !screenOpen;
    }

    /**
     * The field of view to render with. Only the world pass ({@code useFovSetting}, the player's FOV
     * setting) zooms; the held-item pass keeps its fixed FOV.
     */
    public static float fov(float fov, boolean useFovSetting, boolean zooming) {
        return useFovSetting && zooming ? fov / ZOOM_FACTOR : fov;
    }

    /** Whether to skip vanilla's hurt camera tilt. */
    public static boolean cancelHurtCam(boolean noHurtCam) {
        return noHurtCam;
    }

    /**
     * The player's FOV modifier ({@code AbstractClientPlayer.getFovModifier}): vanilla's with Dynamic FOV on,
     * a steady 1 with it off, so sprinting, Speed and bows leave the field of view alone.
     */
    public static float fovModifier(float vanilla, boolean dynamicFov) {
        return dynamicFov ? vanilla : 1.0f;
    }
}
