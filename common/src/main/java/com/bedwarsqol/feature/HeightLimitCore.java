package com.bedwarsqol.feature;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure state behind the Height Limit HUD: which Bedwars map we are on, what its build limit is, and
 * how sure we are. Platform adapters feed it on the client thread; nothing here touches Minecraft.
 *
 * <p><b>Map identity</b> comes from two sources. The Hypixel Mod API {@code hyevent:location} event
 * ({@link #onLocation}) is authoritative for the world it arrived in. The pregame sidebar's
 * {@code Map: <name>} line ({@link #onSidebarMap}) covers a client that never receives the event.
 * Both are tagged with the world object they were observed in; a world change ({@link #onTick})
 * drops a map that belonged to a previous world, so a stale name never survives a server switch.
 *
 * <p><b>The limit</b> starts as the shipped {@link HeightLimitTable} value (Lunar's number: the
 * highest Y a block can be placed at) and is then corrected by what the server actually does:
 * a block placement that the server lets stand at Y proves the limit is at least Y; a placement
 * that Hypixel answers with "Build height limit reached!" proves it is below that Y — when the
 * attempt was near the map centre, because the build envelope is a dome that also lowers the
 * ceiling towards the edge. Corrections are remembered per map ({@link Persist}) so a wrong or
 * missing table entry is fixed the first time it is hit, and a later contradicting observation
 * (a stood block above a learned ceiling) wins because it is the harder evidence.
 */
public final class HeightLimitCore {

    /** Where the current limit number comes from. */
    public enum Source { NONE, TABLE, LEARNED }

    /** How long after a placement attempt the server's verdict is considered in. */
    public static final long SETTLE_MS = 1500L;
    /** Placement attempts remembered at once (a fast bridger sends ~4/s). */
    static final int MAX_PENDING = 12;
    /** Denials count as centre-of-map evidence within this fraction of the known build radius. */
    static final double CENTRE_FRACTION = 0.6;
    /** ... or within this many blocks of the origin when the radius is unknown. */
    static final double CENTRE_FALLBACK = 40.0;

    /** Sink for learned corrections; called with every learned map name → highest placeable Y. */
    public interface Persist {
        void save(Map<String, Integer> learnedByName);
    }

    /** The client's world: is there a placed (non-air) block at this position right now? */
    public interface BlockProbe {
        boolean blockAt(int x, int y, int z);
    }

    private static final class Attempt {
        final int x, y, z;
        final long at;

        Attempt(int x, int y, int z, long at) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.at = at;
        }
    }

    private final Map<String, Integer> learned;       // normalized key → highest placeable Y
    private final Map<String, String> learnedNames;   // normalized key → display name
    private final Persist persist;

    private Object world;
    private String mapName;
    private String mapKey = "";
    private String mode;
    private boolean mapFromLocation;

    private int okY = Integer.MIN_VALUE;
    private int deniedY = Integer.MAX_VALUE;
    private final ArrayDeque<Attempt> pending = new ArrayDeque<>();

    private int limit = -1;
    private Source source = Source.NONE;

    public HeightLimitCore(Map<String, Integer> learnedByName, Persist persist) {
        this.learned = new HashMap<>();
        this.learnedNames = new HashMap<>();
        if (learnedByName != null) {
            for (Map.Entry<String, Integer> e : learnedByName.entrySet()) {
                String key = HeightLimitTable.normalize(e.getKey());
                if (key.isEmpty() || e.getValue() == null || e.getValue() <= 0) continue;
                learned.put(key, e.getValue());
                learnedNames.put(key, e.getKey());
            }
        }
        this.persist = persist;
    }

    // ----- map identity -----

    /** Hypixel Mod API location event, observed while {@code world} was the client world. */
    public void onLocation(Object world, String mode, String map) {
        this.world = world;
        this.mode = mode;
        mapFromLocation = true;
        setMap(map);
    }

    /** Pregame sidebar {@code Map:} value (already trimmed), observed while {@code world} was current. */
    public void onSidebarMap(Object world, String map) {
        if (map == null || map.trim().isEmpty()) return;
        if (mapFromLocation && this.world == world && mapName != null) return; // the server's own word rules this world
        this.world = world;
        mapFromLocation = false;
        setMap(map.trim());
    }

    private void setMap(String map) {
        String key = HeightLimitTable.normalize(map);
        if (key.isEmpty()) {
            mapName = null;
            mapKey = "";
            resetObservations();
            resolve();
            return;
        }
        if (!key.equals(mapKey)) resetObservations();
        mapName = map;
        mapKey = key;
        resolve();
    }

    private void resetObservations() {
        okY = Integer.MIN_VALUE;
        deniedY = Integer.MAX_VALUE;
        pending.clear();
    }

    // ----- placement evidence -----

    /**
     * Whether a plain (formatting-stripped) chat line is Hypixel's ceiling refusal. Bedwars' strings
     * carry both "Build height limit reached!" and "Build limit reached!"; the radius refusal ("You
     * cannot build this far out!") and the floor one ("You cannot build any further down!") are not
     * ceiling evidence and are left alone.
     */
    public static boolean isLimitMessage(String plain) {
        if (plain == null) return false;
        String s = plain.trim().toLowerCase();
        return s.contains("build height limit reached") || s.contains("build limit reached");
    }

    /** The client asked the server to place a block at this position (the C08 target). */
    public void onPlaceAttempt(int x, int y, int z, long now) {
        if (mapName == null) return;
        pending.addLast(new Attempt(x, y, z, now));
        while (pending.size() > MAX_PENDING) pending.pollFirst();
    }

    /** Hypixel's "Build height limit reached!" line. Attributed to the latest still-pending attempt. */
    public void onHeightLimitMessage(long now) {
        Attempt a = pending.pollLast();
        if (a == null) return;
        if (now - a.at > SETTLE_MS) return;
        if (nearCentre(a.x, a.z) && a.y < deniedY) {
            deniedY = a.y;
            resolve();
        }
    }

    /**
     * Per tick: expire a world the map belonged to, and settle attempts old enough for the server to
     * have answered — a block still standing was accepted, proving the limit is at least that Y.
     */
    public void onTick(long now, Object currentWorld, boolean inGame, BlockProbe probe) {
        if (mapName != null && currentWorld != world) {
            mapName = null;
            mapKey = "";
            mode = null;
            mapFromLocation = false;
            resetObservations();
            resolve();
        }
        world = currentWorld;
        if (!inGame) {
            pending.clear();
            return;
        }
        boolean changed = false;
        for (Iterator<Attempt> it = pending.iterator(); it.hasNext(); ) {
            Attempt a = it.next();
            if (now - a.at < SETTLE_MS) break; // queue is in time order
            it.remove();
            if (probe != null && probe.blockAt(a.x, a.y, a.z) && a.y > okY) {
                okY = a.y;
                changed = true;
            }
        }
        if (changed) resolve();
    }

    private boolean nearCentre(int x, int z) {
        HeightLimitMap entry = HeightLimitTable.lookup(mapName);
        double reach = entry != null && entry.radius > 0 ? entry.radius * CENTRE_FRACTION : CENTRE_FALLBACK;
        return (double) x * x + (double) z * z <= reach * reach;
    }

    // ----- resolution -----

    private void resolve() {
        if (mapName == null) {
            limit = -1;
            source = Source.NONE;
            return;
        }
        Integer remembered = learned.get(mapKey);
        HeightLimitMap entry = HeightLimitTable.lookup(mapName);
        int base;
        Source src;
        if (remembered != null) {
            base = remembered;
            src = Source.LEARNED;
        } else if (entry != null && entry.maxY > 0) {
            base = entry.maxY;
            src = Source.TABLE;
        } else {
            base = -1;
            src = Source.NONE;
        }
        int corrected = base;
        if (deniedY != Integer.MAX_VALUE && (corrected < 0 || deniedY - 1 < corrected)) corrected = deniedY - 1;
        if (okY != Integer.MIN_VALUE && okY > corrected) corrected = okY; // a stood block outranks a denial
        if (corrected != base && corrected > 0) {
            learned.put(mapKey, corrected);
            learnedNames.put(mapKey, mapName);
            src = Source.LEARNED;
            if (persist != null) persist.save(learnedByName());
        }
        limit = corrected;
        source = corrected > 0 ? src : Source.NONE;
    }

    private Map<String, Integer> learnedByName() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : learned.entrySet()) {
            String name = learnedNames.get(e.getKey());
            out.put(name != null ? name : e.getKey(), e.getValue());
        }
        return out;
    }

    // ----- read side -----

    /** Display name of the current map, or {@code null} when not on a known Bedwars map server. */
    public String map() {
        return mapName;
    }

    /** Hypixel mode id from the location event (e.g. {@code BEDWARS_EIGHT_ONE}), or {@code null}. */
    public String mode() {
        return mode;
    }

    /** Highest placeable Y for the current map, or {@code -1} while unknown. */
    public int limit() {
        return limit;
    }

    public Source source() {
        return source;
    }

    /** Blocks between the player's feet level and the limit: {@code limit - floor(feetY)}, never negative. */
    public int distance(double feetY) {
        if (limit < 0) return -1;
        int d = limit - (int) Math.floor(feetY);
        return d < 0 ? 0 : d;
    }

    /** Highest Y the server has let a block stand at this game, or {@code Integer.MIN_VALUE}. */
    public int observedOkY() {
        return okY;
    }

    /** Lowest Y Hypixel refused with the height-limit line this game, or {@code Integer.MAX_VALUE}. */
    public int observedDeniedY() {
        return deniedY;
    }

    public int learnedCount() {
        return learned.size();
    }

    /** One-line state dump for the command / diag log. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("map=").append(mapName).append(" mode=").append(mode)
                .append(" src=").append(mapFromLocation ? "modapi" : "sidebar")
                .append(" limit=").append(limit).append('/').append(source);
        HeightLimitMap entry = HeightLimitTable.lookup(mapName);
        sb.append(" table=").append(entry == null ? "-" : entry.maxY);
        sb.append(" ok=").append(okY == Integer.MIN_VALUE ? "-" : String.valueOf(okY));
        sb.append(" denied=").append(deniedY == Integer.MAX_VALUE ? "-" : String.valueOf(deniedY));
        sb.append(" pending=").append(pending.size());
        return sb.toString();
    }
}
