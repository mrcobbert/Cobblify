package com.bedwarsqol.feature;

/**
 * The Split module's rules: a teammate's non-final death opens a {@link #WINDOW_MS} window in which
 * the platform draws {@code SPLIT} above the player's home forge, and the home forge is the first
 * iron/gold item the player sees within {@link #LEARN_RANGE} blocks while able to learn (alive, in
 * a game). Clock values and positions are passed in, so this holds no Minecraft state.
 */
public final class SplitRules {

    public static final long WINDOW_MS = 5_000L;
    public static final double LEARN_RANGE = 16.0;
    public static final double NEAR_RANGE = 32.0;
    public static final long RESPAWN_MS = 5_000L;
    public static final long HUD_AFTER_RESPAWN_MS = 5_000L;

    private boolean dead;
    private long deathMs;
    private boolean ownDead;
    private long ownDeathMs;
    private boolean locked;
    private double forgeX, forgeY, forgeZ;

    /** Forgets the death times (teammate and own) and the forge. */
    public void reset() {
        dead = false;
        deathMs = 0L;
        ownDead = false;
        ownDeathMs = 0L;
        locked = false;
        forgeX = forgeY = forgeZ = 0;
    }

    /**
     * Record a non-final death. Counts only when both colours are known, equal, and
     * {@code !victimIsSelf}. Returns whether it counted.
     */
    public boolean onDeath(char victimColour, char ownColour, boolean victimIsSelf, long nowMs) {
        if (victimIsSelf || victimColour == 0 || ownColour == 0) return false;
        if (Character.toLowerCase(victimColour) != Character.toLowerCase(ownColour)) return false;
        dead = true;
        deathMs = nowMs;
        return true;
    }

    /** True for nowMs in [deathMs, deathMs + WINDOW_MS). */
    public boolean active(long nowMs) {
        return dead && nowMs >= deathMs && nowMs - deathMs < WINDOW_MS;
    }

    /**
     * Offer one iron/gold item position. Locks the forge on the first in-range offer when
     * {@code canLearn}; ignored once locked. Returns whether it locked now.
     */
    public boolean offerResource(boolean canLearn, double px, double py, double pz, double ix, double iy, double iz) {
        if (locked || !canLearn) return false;
        double dx = ix - px, dy = iy - py, dz = iz - pz;
        if (dx * dx + dy * dy + dz * dz > LEARN_RANGE * LEARN_RANGE) return false;
        forgeX = ix;
        forgeY = iy;
        forgeZ = iz;
        locked = true;
        return true;
    }

    /**
     * Whole seconds left in the window, rounded up: 5 at +0 ms and +1 ms, 4 at +1000 ms, 1 at +4999 ms;
     * 0 when not active.
     */
    public int secondsLeft(long nowMs) {
        if (!active(nowMs)) return 0;
        return (int) Math.ceil((deathMs + WINDOW_MS - nowMs) / 1000.0);
    }

    /** Whether the player is within {@link #NEAR_RANGE} blocks (3-D, inclusive) of the locked forge; false with no forge. */
    public boolean nearForge(double px, double py, double pz) {
        if (!locked) return false;
        double dx = forgeX - px, dy = forgeY - py, dz = forgeZ - pz;
        return dx * dx + dy * dy + dz * dz <= NEAR_RANGE * NEAR_RANGE;
    }

    /** Records the player's own non-final death. {@link #reset()} forgets it. */
    public void onOwnDeath(long nowMs) {
        ownDead = true;
        ownDeathMs = nowMs;
    }

    /**
     * Whether the HUD cue shows: a teammate's window is {@link #active} and the player respawned from a
     * recorded own death within the last {@link #HUD_AFTER_RESPAWN_MS}, that is {@code nowMs} in
     * [ownDeathMs + {@link #RESPAWN_MS}, ownDeathMs + RESPAWN_MS + HUD_AFTER_RESPAWN_MS).
     */
    public boolean hudCue(long nowMs) {
        if (!ownDead || !active(nowMs)) return false;
        long respawn = ownDeathMs + RESPAWN_MS;
        return nowMs >= respawn && nowMs - respawn < HUD_AFTER_RESPAWN_MS;
    }

    public boolean hasForge() {
        return locked;
    }

    public double forgeX() {
        return forgeX;
    }

    public double forgeY() {
        return forgeY;
    }

    public double forgeZ() {
        return forgeZ;
    }

    /** First {@code §[0-9a-f]} colour code (lower-case) in a formatted string, or 0. */
    public static char leadingColour(String formatted) {
        if (formatted == null) return 0;
        for (int i = 0; i + 1 < formatted.length(); i++) {
            if (formatted.charAt(i) != '§') continue;
            char c = Character.toLowerCase(formatted.charAt(i + 1));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) return c;
        }
        return 0;
    }
}
