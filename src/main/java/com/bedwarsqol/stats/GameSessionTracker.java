package com.bedwarsqol.stats;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.NickUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Single owner of the "which game are we in" session id and the per-tick {@link EligibilitySnapshot}.
 *
 * <p>Pure core: {@link #tick(Object, boolean, long)} advances a session id with inputs
 * {@code (worldObjectIdentity, isInActiveBedwarsGame, nowMs)} — no world-unload event needed (works on
 * Weave/Lunar). The id increments on (a) a change of the world object reference (including → null on
 * disconnect), or (b) the rising edge of the active-game gate after ≥ 5 s continuously false, but only
 * when no world-change increment has happened since active was last true (a world change OWNS the
 * transition and swallows the following first rising edge). Rising-edge increments exist solely for
 * same-world game→lobby→game.
 */
public final class GameSessionTracker {

    /** Debounce before a same-world rising edge counts as a new game. */
    public static final long ACTIVE_DEBOUNCE_MS = 5000L;

    // ---- pure state machine (instance; unit-tested directly) ----
    private boolean initialized;
    private Object world;
    private boolean active;
    private long falseSinceMs;              // when active last went (or started) false; -1 while active
    private boolean worldChangeSinceActive; // a world change occurred and hasn't been consumed by a rising edge
    private int sessionId;

    public int sessionId() {
        return sessionId;
    }

    /** Advance the machine and return the current session id. */
    public int tick(Object worldObject, boolean activeGame, long nowMs) {
        if (!initialized) {
            initialized = true;
            world = worldObject;
            active = activeGame;
            worldChangeSinceActive = false;
            falseSinceMs = activeGame ? -1L : nowMs;
            return sessionId;
        }

        if (worldObject != world) {
            world = worldObject;
            sessionId++;
            // This increment owns the transition, so it must swallow the FOLLOWING rising edge.
            // But a world change straight into an already-active game has no later rising edge to
            // swallow; leaving the flag set would poison the next same-world game->lobby->game edge.
            worldChangeSinceActive = !activeGame;
            active = activeGame;
            falseSinceMs = activeGame ? -1L : nowMs;
            return sessionId;
        }

        if (activeGame && !active) {
            boolean debounced = falseSinceMs >= 0L && (nowMs - falseSinceMs) >= ACTIVE_DEBOUNCE_MS;
            if (debounced && !worldChangeSinceActive) sessionId++;
            active = true;
            worldChangeSinceActive = false; // rising edge consumed the swallow
            falseSinceMs = -1L;
        } else if (!activeGame && active) {
            active = false;
            falseSinceMs = nowMs;
        }
        return sessionId;
    }

    // ---- client-thread driver ----
    private static final GameSessionTracker INSTANCE = new GameSessionTracker();
    private static volatile int CURRENT;

    /** The live session id (client-thread advanced; read by all Urchin consumers). */
    public static int currentSessionId() {
        return CURRENT;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.currentTimeMillis();
        Object worldObj = mc == null ? null : mc.theWorld;
        boolean activeGame = HypixelContext.isInActiveBedwarsGame();

        int before = CURRENT;
        int id = INSTANCE.tick(worldObj, activeGame, now);
        if (id != before) {
            CURRENT = id;
            // The transition owner clears the identity snapshot in the same client-thread operation;
            // NickUtils' next scan republishes a fresh one.
            NickUtils.clearIdentitySnapshot();
        }

        // Publish the per-tick eligibility snapshot the background dispatch threads consume.
        ClientSettings cfg = BedwarsQol.config;
        boolean masterOn = cfg != null && cfg.urchinTags;
        boolean seraphOn = cfg != null && cfg.seraphTags;
        boolean exactHost = false;
        if (mc != null && !mc.isSingleplayer()) {
            ServerData server = mc.getCurrentServerData();
            if (server != null && server.serverIP != null) {
                exactHost = EligibilitySnapshot.isExactHypixelHost(server.serverIP);
            }
        }
        EligibilitySnapshot.publish(new EligibilitySnapshot(
                id, exactHost, activeGame, masterOn, seraphOn,
                NickUtils.identitySnapshot()));
    }
}
