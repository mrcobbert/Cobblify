package com.bedwarsqol.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Stable-insertion-order animation model for the Players master list. Pure (no Minecraft/GL types) so
 * the seed / append / leave / filter / compaction / hit logic is unit-testable in both trees.
 *
 * <p>Rows keep the slot they first appeared in ({@link LinkedHashMap} iteration order); a new player
 * slides + fades in from just below its slot, a departed player becomes a ghost that fades + drifts up
 * and is then dropped, and every row eases toward its target Y/alpha each frame. Because the owning
 * {@code SettingsGui} is reconstructed per open, a fresh instance is the real "page opened" boundary:
 * the first {@link #sync} seeds the current live players directly to their targets (no intro), and a
 * later {@code initGui} relayout (resize) is not a join event since membership is unchanged.
 */
public final class PlayersListAnim {

    /** Ease factor per frame (matches the list scroll ease in {@code SettingsGui}). */
    public static final float EASE = 0.35f;
    private static final float SNAP_Y = 0.5f;
    private static final float SNAP_A = 0.02f;
    private static final float GONE_A = 0.02f; // a leaving ghost at/below this alpha is dropped
    /** A row must be at least this opaque to be clickable (plus the caller's viewport-clip test). */
    public static final float HIT_A = 0.5f;

    public static final class Row {
        public final UUID uuid;
        public String name;
        public boolean matches = true;  // last filter result (meaningful for live rows)
        public boolean leaving = false; // absent from the live set → fading out
        public float renderY, alpha;
        float targetY, targetAlpha;
        boolean present;                // seen in the current frame's live set
        boolean justAdded;              // created this frame (seed its enter position once)

        Row(UUID uuid, String name) { this.uuid = uuid; this.name = name; }

        /** Clickable at the current alpha (the caller additionally requires the viewport clip). */
        public boolean hittable() { return !leaving && matches && alpha >= HIT_A; }
    }

    private final LinkedHashMap<UUID, Row> rows = new LinkedHashMap<UUID, Row>();
    private boolean seeded = false;

    /** Currently tracked rows (incl. leaving ghosts), in stable insertion order. */
    public Iterable<Row> rows() { return rows.values(); }

    public Row get(UUID uuid) { return rows.get(uuid); }

    public int size() { return rows.size(); }

    /** Count of rows that occupy a slot (present, not leaving, matching the filter) — for scroll extent. */
    public int shownCount() {
        int n = 0;
        for (Row r : rows.values()) if (r.present && !r.leaving && r.matches) n++;
        return n;
    }

    /**
     * Reconcile membership + geometry against the current live tab list in one pass, then call
     * {@link #ease()} to advance the animation.
     *
     * @param liveOrder    live UUIDs in enumeration order (unsorted; stable slot order is derived here)
     * @param names        display name per live UUID, index-aligned with {@code liveOrder}
     * @param matched      filter match per live UUID, index-aligned (null → all match); a non-matching
     *                     live row stays tracked but fades and is excluded from the slot layout
     * @param listTop      top Y of the list viewport (virtual space)
     * @param scrollRender eased scroll offset (virtual space)
     * @param rowStride    per-row vertical stride (virtual space)
     */
    public void sync(List<UUID> liveOrder, List<String> names, boolean[] matched,
                     float listTop, float scrollRender, float rowStride) {
        boolean firstPass = !seeded;

        for (Row r : rows.values()) r.present = false;

        // Add/refresh live rows; new UUIDs append at the end of the map (insertion order = slot order).
        for (int i = 0; i < liveOrder.size(); i++) {
            UUID id = liveOrder.get(i);
            boolean m = matched == null || i >= matched.length || matched[i];
            Row r = rows.get(id);
            if (r == null) {
                r = new Row(id, names.get(i));
                r.justAdded = true;
                r.alpha = firstPass ? 1f : 0f;
                rows.put(id, r);
            } else {
                r.name = names.get(i);
                r.leaving = false; // a rejoin cancels a pending leave
            }
            r.matches = m;
            r.present = true;
        }

        // Tracked rows no longer live become fading ghosts. Capture the one-stride exit target ONCE at
        // the transition so the ghost eases toward a fixed point instead of drifting up every frame.
        for (Row r : rows.values()) {
            if (!r.present && !r.leaving) {
                r.leaving = true;
                r.targetAlpha = 0f;
                r.targetY = r.renderY - rowStride;
            }
        }

        // Assign slots to shown rows (present, non-leaving, matching) in insertion order → target Y.
        // Leaving ghosts keep their captured exit target and are not touched here.
        int slot = 0;
        for (Row r : rows.values()) {
            if (r.present && !r.leaving && r.matches) {
                float ty = listTop - scrollRender + slot * rowStride;
                r.targetY = ty;
                r.targetAlpha = 1f;
                if (firstPass) { r.renderY = ty; r.alpha = 1f; }        // seed to target, no intro
                else if (r.justAdded) { r.renderY = ty + rowStride; }   // slide in from below
                slot++;
            } else if (!r.leaving) {
                r.targetAlpha = 0f;                                     // present but filtered out: fade
            }
            r.justAdded = false;
        }

        seeded = true;
        ease();
    }

    /** Advance every row toward its target Y/alpha; drop leaving ghosts once faded. */
    private void ease() {
        List<UUID> drop = null;
        for (Row r : rows.values()) {
            r.renderY += (r.targetY - r.renderY) * EASE;
            r.alpha += (r.targetAlpha - r.alpha) * EASE;
            if (Math.abs(r.targetY - r.renderY) < SNAP_Y) r.renderY = r.targetY;
            if (Math.abs(r.targetAlpha - r.alpha) < SNAP_A) r.alpha = r.targetAlpha;
            if (r.leaving && r.alpha <= GONE_A) {
                if (drop == null) drop = new ArrayList<UUID>();
                drop.add(r.uuid);
            }
        }
        if (drop != null) for (UUID id : drop) rows.remove(id);
    }
}
