package com.bedwarsqol.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides which game the Team Upgrades state belongs to (PLAN "TeamUpgradeLifecycle", rev 3), so the
 * HUD keeps a game across death and {@code /rejoin} but never shows a previous game.
 *
 * <p>Observations are fed ungated, in whatever order they happen: every world load, the queue and
 * active-game flags each tick, every Mod API location, every title. Ingestion (chat events, shop
 * snapshots, gear) applies only while an active game is shown and the game has not ended.
 *
 * <ul>
 *   <li>A game location has a {@code map} and no {@code lobbyName}.</li>
 *   <li>A pregame queue or a game-end title clears everything, including the kept copy. After an end
 *       title nothing is ingested until the next world load or queue.</li>
 *   <li>Entering an active game starts a pending game: events are applied to an empty state and
 *       logged. It is decided by the first game location after the last world load, else after
 *       {@link #LOCATION_WAIT_MS} by the most recent game location ever received. Same server name as
 *       the kept copy: the kept copy plus the logged events (a rejoin). Otherwise only the logged
 *       events, and the kept copy is dropped.</li>
 *   <li>A world load while a decided game exists keeps it as the one hidden copy. A world load while
 *       still pending drops that pending game and leaves the earlier copy alone.</li>
 *   <li>The active flag going off and on without a world load keeps the state.</li>
 * </ul>
 */
public final class TeamUpgradeLifecycle {

    /** How long a pending game waits for its own location before falling back to the latest one. */
    public static final long LOCATION_WAIT_MS = 10000;

    private static final class Loc {
        final String server;
        final String mode;

        Loc(String server, String mode) {
            this.server = server;
            this.mode = mode;
        }
    }

    /** One logged ingestion, replayed onto the kept copy on a rejoin. */
    private static final class Op {
        final TeamUpgradeChat.Event event;
        final TeamUpgradeShop.Snapshot snapshot;
        final Map<String, Integer> gear;
        final long now;

        Op(TeamUpgradeChat.Event event, TeamUpgradeShop.Snapshot snapshot, Map<String, Integer> gear, long now) {
            this.event = event;
            this.snapshot = snapshot;
            this.gear = gear;
            this.now = now;
        }

        void applyTo(TeamUpgradeState s) {
            if (event != null) s.apply(event, now);
            if (snapshot != null) s.snapshot(snapshot, now);
            if (gear != null) s.gear(gear);
        }
    }

    private TeamUpgradeState live;
    private String liveServer;
    private String liveMode;
    private TeamUpgradeState kept;
    private String keptServer;
    private boolean active;
    private boolean pending;
    private boolean ended;
    private long pendingSince;
    private final List<Op> log = new ArrayList<Op>();
    private Loc firstGameLocSinceWorldLoad;
    private Loc lastGameLoc;

    private static final int NO_WINDOW = Integer.MIN_VALUE;
    private int shopWindow = NO_WINDOW;
    private String shopApplied;

    public TeamUpgradeLifecycle() {}

    // --- observations (ungated) ---

    /** Every world load (join and respawn worlds alike). */
    public void worldLoad(long now) {
        if (live != null && !pending) {
            kept = live;
            keptServer = liveServer;
        }
        live = null;
        liveServer = null;
        liveMode = null;
        pending = false;
        log.clear();
        active = false;
        ended = false;
        firstGameLocSinceWorldLoad = null;
        shopClosed();
    }

    /** Once per client tick: whether the pregame queue or an active Bed Wars game is showing. */
    public void tick(boolean inQueue, boolean inActiveGame, long now) {
        if (inQueue) {
            clearAll();
            ended = false;
            active = false;
            return;
        }
        if (inActiveGame) {
            if (!active) {
                active = true;
                if (live == null && !ended) startPending(now);
            }
        } else {
            active = false;
        }
        if (pending && now - pendingSince >= LOCATION_WAIT_MS) decide(lastGameLoc);
    }

    /** Every Mod API location as it is handled. */
    public void location(String serverName, String mode, String map, String lobbyName, long now) {
        boolean game = map != null && !map.isEmpty() && (lobbyName == null || lobbyName.isEmpty());
        if (!game) return;
        Loc loc = new Loc(serverName, mode);
        lastGameLoc = loc;
        if (firstGameLocSinceWorldLoad == null) firstGameLocSinceWorldLoad = loc;
        if (pending) decide(firstGameLocSinceWorldLoad);
    }

    /** Every title and subtitle; {@code VICTORY!} / {@code GAME OVER!} end the game. */
    public void title(String text, long now) {
        if (!isGameEndTitle(text)) return;
        clearAll();
        ended = true;
    }

    /** True for Hypixel's game-end titles. */
    public static boolean isGameEndTitle(String text) {
        if (text == null) return false;
        String t = TeamUpgradeCatalog.strip(text).trim();
        return t.equals("VICTORY!") || t.equals("GAME OVER!");
    }

    // --- ingestion (gated) ---

    /** True while events may change the state: an active game is showing and it has not ended. */
    public boolean ingestionAllowed() {
        return active && !ended && live != null;
    }

    /** A chat or title event; ignored unless ingestion is allowed. */
    public void chat(TeamUpgradeChat.Event e, long now) {
        if (e == null || !ingestionAllowed()) return;
        live.apply(e, now);
        if (pending) log.add(new Op(e, null, null, now));
    }

    /** A shop window snapshot; ignored when null or unless ingestion is allowed. */
    public void shop(TeamUpgradeShop.Snapshot s, long now) {
        if (s == null || !ingestionAllowed()) return;
        live.snapshot(s, now);
        if (pending) log.add(new Op(null, s, null, now));
    }

    /**
     * A look at the open {@code Upgrades & Traps} window, {@code windowId} naming that window. Applied only
     * when it is news: the first look at a window (Hypixel reopens it after every purchase or removal) or
     * contents that changed since the last look applied. Reading an unchanged window again would undo the
     * chat and title events that arrived after it was drawn (review code-1 I1).
     */
    public void shopWindow(int windowId, TeamUpgradeShop.Snapshot s, long now) {
        if (s == null || !ingestionAllowed()) return;
        String signature = TeamUpgradeShop.signature(s);
        if (windowId == shopWindow && signature.equals(shopApplied)) return;
        shopWindow = windowId;
        shopApplied = signature;
        shop(s, now);
    }

    /** No upgrades window is open: the next one is news even if it shows the same. */
    public void shopClosed() {
        shopWindow = NO_WINDOW;
        shopApplied = null;
    }

    /** A gear poll (see {@link TeamUpgradeGear#infer}); priced by the decided game's mode. */
    public void gear(int[] armorProtection, int bootsFeatherFalling, int hasteAmplifier,
                     int hasteTicks, int regenTicks, long now) {
        if (!ingestionAllowed()) return;
        Map<String, Integer> raises = TeamUpgradeGear.infer(armorProtection, bootsFeatherFalling,
                hasteAmplifier, hasteTicks, regenTicks, mode());
        if (raises.isEmpty()) return;
        live.gear(raises);
        if (pending) log.add(new Op(null, null, raises, now));
    }

    // --- outputs ---

    /** The state to draw: null outside an active game (and after its end title). */
    public TeamUpgradeState visible() {
        return active && !ended ? live : null;
    }

    /** Mod API mode of the decided game, or null (pending, or decided without a location). */
    public String mode() {
        return pending ? null : liveMode;
    }

    /** True while the current game waits for its location. */
    public boolean pending() {
        return pending;
    }

    // --- internals ---

    private void startPending(long now) {
        live = new TeamUpgradeState();
        liveServer = null;
        liveMode = null;
        pending = true;
        pendingSince = now;
        log.clear();
        if (firstGameLocSinceWorldLoad != null) decide(firstGameLocSinceWorldLoad);
    }

    private void decide(Loc loc) {
        String server = loc == null ? null : loc.server;
        if (kept != null && server != null && server.equals(keptServer)) {
            TeamUpgradeState restored = kept;
            for (Op op : log) op.applyTo(restored);
            live = restored;
        }
        kept = null;
        keptServer = null;
        liveServer = server;
        liveMode = loc == null ? null : loc.mode;
        pending = false;
        log.clear();
    }

    private void clearAll() {
        live = null;
        liveServer = null;
        kept = null;
        keptServer = null;
        pending = false;
        log.clear();
    }
}
