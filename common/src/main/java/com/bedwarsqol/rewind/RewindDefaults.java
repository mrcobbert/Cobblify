package com.bedwarsqol.rewind;

/**
 * Defaults of the Rewind module (Ornithe build): the module and Record Games are on, so every game is recorded unless
 * the player turns either off; Marker Key and Clean View Key are unbound (0), so a stray press never keeps a game
 * forever. Key codes use the Session Stats key's encoding ({@code SessionHoldKey}): an LWJGL key code, a mouse button
 * as button - 100, or 0 for unbound. The export settings start at 1080p, 60, High, H.264 and Chat on.
 */
public final class RewindDefaults {

    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_RECORD_GAMES = true;
    public static final int DEFAULT_MARKER_KEY = 0;
    public static final int DEFAULT_CLEAN_VIEW_KEY = 0;
    /** Export (G10, G12, G42): the first export is 1080p at 60 frames per second, High, H.264, with chat. */
    public static final int DEFAULT_EXPORT_HEIGHT = 1080;
    public static final int DEFAULT_EXPORT_FPS = 60;
    public static final String DEFAULT_EXPORT_QUALITY = "high";
    public static final String DEFAULT_EXPORT_CODEC = "h264";
    public static final boolean DEFAULT_EXPORT_CHAT = true;

    private RewindDefaults() {
    }
}
